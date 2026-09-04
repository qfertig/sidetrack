package com.sidetrack.auth

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.net.wifi.WifiManager
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import com.sidetrack.bridge.NativeBridge
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.coroutines.coroutineContext

data class AuthState(
    val isAuthenticated: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    /** Monotonically increasing counter so observers re-trigger even when
     *  [isAuthenticated] stays `true` across back-to-back sign-ins. */
    val version: Int = 0,
)

/** UI state for the Zeroconf ("Sign In with Spotify App") sign-in flow. */
sealed class ZeroconfState {
    object Idle : ZeroconfState()
    data class Pending(val deviceName: String) : ZeroconfState()
    data class Error(val message: String) : ZeroconfState()
}

class AuthManager private constructor(context: Context) {

    companion object {
        private const val TAG = "SidetrackAuth"
        private const val PREFS_NAME = "sidetrack_auth"
        private const val TOKEN_TYPE_ACCESS = "access_token"
        private const val TOKEN_TYPE_REFRESH = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at_ms"
        private const val KEY_GRANT_HASH = "scopes_hash"

        /** Zeroconf-paired stored credentials — an alternative to the OAuth
         *  tokens above, set once by [startZeroconfPairing] and reused on
         *  every relaunch since (unlike an OAuth access token) they don't expire. */
        private const val KEY_ZC_USERNAME = "zc_username"
        private const val KEY_ZC_AUTH_DATA = "zc_auth_data"
        private const val KEY_ZC_AUTH_TYPE = "zc_auth_type"

        /** How often to poll native for a Zeroconf pairing result. */
        private const val DISCOVERY_POLL_INTERVAL_MS = 500L

        /** Spotify's own desktop ("keymaster") client id.
         *
         *  This must stay in step with the client id librespot presents at login5
         *  (`SessionConfig::default()` in native/librespot-local/core/src/config.rs).
         *  Since 2026-08-10 Spotify rejects the login5 stored-credential exchange
         *  whenever the access token was minted by a different client id, which
         *  fails every spclient call with INVALID_CREDENTIALS. */
        private const val CLIENT_ID = "65b708073fc0480ea92a077233ca87bd"

        /** Loopback redirect path registered for [CLIENT_ID]; the port is chosen
         *  per sign-in by the callback listener. */
        private const val REDIRECT_PATH = "/login"

        /** The scope set librespot requests for this client id. */
        private const val SCOPES =
            "app-remote-control playlist-modify playlist-modify-private " +
            "playlist-modify-public playlist-read playlist-read-collaborative " +
            "playlist-read-private streaming ugc-image-upload user-follow-modify " +
            "user-follow-read user-library-modify user-library-read user-modify " +
            "user-modify-playback-state user-modify-private user-personalized " +
            "user-read-birthdate user-read-currently-playing user-read-email " +
            "user-read-play-history user-read-playback-position " +
            "user-read-playback-state user-read-private user-read-recently-played " +
            "user-top-read"

        private const val AUTH_URL = "https://accounts.spotify.com/authorize"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"

        /** How long the loopback listener waits for the browser to come back. */
        private const val CALLBACK_TIMEOUT_MS = 5 * 60 * 1000

        @Volatile
        private var instance: AuthManager? = null

        fun getInstance(context: Context): AuthManager =
            instance ?: synchronized(this) {
                instance ?: AuthManager(context.applicationContext).also { instance = it }
            }
    }

    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _zeroconfState = MutableStateFlow<ZeroconfState>(ZeroconfState.Idle)
    val zeroconfState: StateFlow<ZeroconfState> = _zeroconfState.asStateFlow()

    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryPollJob: Job? = null

    /** Application-scoped so an Activity recreation while the browser is in front
     *  doesn't tear down the listener the redirect is about to hit. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var signInJob: Job? = null

    init {
        val token = prefs.getString(TOKEN_TYPE_ACCESS, null)
        val hasZeroconf = hasZeroconfCredentials()
        Log.i(TAG, "init: token present=${token != null} zeroconf present=$hasZeroconf")
        _state.value = AuthState(isAuthenticated = token != null || hasZeroconf)
    }

    /**
     * Run the PKCE authorization-code flow against a loopback redirect.
     *
     * [openBrowser] is invoked on the main thread with the authorization URL once
     * the callback listener is bound; the resulting code is exchanged in place, so
     * no deep link or Activity round-trip is involved.
     */
    fun signIn(openBrowser: (Uri) -> Unit) {
        if (signInJob?.isActive == true) {
            Log.i(TAG, "signIn: already in progress, ignoring")
            return
        }
        signInJob = scope.launch { runSignIn(openBrowser) }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        signInJob = null
        if (_state.value.isLoading) {
            _state.value = _state.value.copy(isLoading = false)
        }
    }

    /**
     * Start Spotify Connect Zeroconf pairing under [deviceName]: this device
     * advertises itself over mDNS so the official Spotify app (on any phone on
     * the same network) can find it under Devices and hand over credentials
     * directly — no browser, no OAuth redirect, so it isn't limited to loopback.
     */
    fun startZeroconfPairing(deviceName: String) {
        if (discoveryPollJob?.isActive == true) {
            Log.i(TAG, "startZeroconfPairing: already in progress, ignoring")
            return
        }
        _zeroconfState.value = ZeroconfState.Pending(deviceName)

        acquireMulticastLock()
        NativeBridge.discoveryStart(deviceName)

        discoveryPollJob = scope.launch {
            try {
                while (true) {
                    val json = NativeBridge.discoveryPollResult()?.let { JSONObject(it) } ?: break
                    when (json.optString("status")) {
                        "connected" -> {
                            prefs.edit()
                                .putString(KEY_ZC_USERNAME, json.getString("username"))
                                .putString(KEY_ZC_AUTH_DATA, json.getString("authData"))
                                .putString(KEY_ZC_AUTH_TYPE, json.getInt("authType").toString())
                                .commit()
                            Log.i(TAG, "startZeroconfPairing: paired as ${json.getString("username")}")
                            _zeroconfState.value = ZeroconfState.Idle
                            _state.value = AuthState(
                                isAuthenticated = true,
                                version = _state.value.version + 1,
                            )
                            releaseMulticastLock()
                            return@launch
                        }
                        "error" -> {
                            val message = json.optString("message", "Pairing failed")
                            Log.i(TAG, "startZeroconfPairing: failed: $message")
                            _zeroconfState.value = ZeroconfState.Error(message)
                            releaseMulticastLock()
                            return@launch
                        }
                        else -> { /* idle or pending — keep polling */ }
                    }
                    delay(DISCOVERY_POLL_INTERVAL_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "startZeroconfPairing: polling failed", e)
                _zeroconfState.value = ZeroconfState.Error("Pairing failed: ${e.message}")
                releaseMulticastLock()
            }
        }
    }

    /** Cancel an in-progress Zeroconf pairing. */
    fun cancelZeroconfPairing() {
        discoveryPollJob?.cancel()
        discoveryPollJob = null
        NativeBridge.discoveryStop()
        releaseMulticastLock()
        _zeroconfState.value = ZeroconfState.Idle
    }

    /** Android drops mDNS multicast packets by default to save battery —
     *  advertising for Zeroconf pairing needs this lock held while it runs. */
    private fun acquireMulticastLock() {
        try {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifiManager?.createMulticastLock("sidetrack-zeroconf")
            lock?.setReferenceCounted(false)
            lock?.acquire()
            multicastLock = lock
        } catch (e: Exception) {
            Log.w(TAG, "acquireMulticastLock failed", e)
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "releaseMulticastLock failed", e)
        }
        multicastLock = null
    }

    fun hasZeroconfCredentials(): Boolean =
        prefs.getString(KEY_ZC_USERNAME, null) != null

    /**
     * Connect the native Spotify session using whichever credentials this
     * device is signed in with (OAuth token or Zeroconf-paired stored
     * credentials). Used both for the initial connect and for reconnecting
     * after a transient failure.
     *
     * @return null on success, or an error message on failure.
     */
    suspend fun connectNativeSession(): String? {
        if (NativeBridge.sessionIsConnected()) return null

        val oauthToken = getValidAccessToken()
        if (oauthToken != null) {
            return NativeBridge.sessionConnect(oauthToken)
        }

        val zcUsername = prefs.getString(KEY_ZC_USERNAME, null)
        val zcAuthData = prefs.getString(KEY_ZC_AUTH_DATA, null)
        val zcAuthType = prefs.getString(KEY_ZC_AUTH_TYPE, null)?.toIntOrNull()
        if (zcUsername != null && zcAuthData != null && zcAuthType != null) {
            return NativeBridge.sessionConnectWithCredentials(zcUsername, zcAuthData, zcAuthType)
        }

        return "Not signed in"
    }

    private suspend fun runSignIn(openBrowser: (Uri) -> Unit) {
        _state.value = _state.value.copy(isLoading = true, error = null)

        val verifier = generateCodeVerifier()
        val csrfState = generateCsrfState()

        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = CALLBACK_TIMEOUT_MS
                val redirectUri = "http://127.0.0.1:${server.localPort}$REDIRECT_PATH"
                Log.i(TAG, "signIn: listening on $redirectUri")

                val authUri = buildAuthUri(
                    challenge = generateCodeChallenge(verifier),
                    redirectUri = redirectUri,
                    csrfState = csrfState,
                )
                withContext(Dispatchers.Main) { openBrowser(authUri) }

                val code = awaitAuthorizationCode(server, csrfState)
                exchangeCode(code, verifier, redirectUri)
            }
        } catch (e: CancellationException) {
            // cancelSignIn()/logout() closed the listener — not an error to report.
            throw e
        } catch (e: SocketTimeoutException) {
            Log.i(TAG, "signIn: timed out waiting for callback", e)
            _state.value = _state.value.copy(
                isLoading = false,
                error = "Sign-in timed out — please try again",
            )
        } catch (e: SignInDeniedException) {
            Log.i(TAG, "signIn: denied (${e.message})")
            _state.value = _state.value.copy(isLoading = false, error = e.message)
        } catch (e: Exception) {
            // Cancelling closes the listener, which surfaces as a SocketException
            // rather than CancellationException — don't report that as a failure.
            coroutineContext.ensureActive()
            Log.i(TAG, "signIn: failed", e)
            _state.value = _state.value.copy(
                isLoading = false,
                error = "Sign-in failed: ${e.message}",
            )
        }
    }

    private class SignInDeniedException(message: String) : Exception(message)

    private fun buildAuthUri(challenge: String, redirectUri: String, csrfState: String): Uri =
        Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", csrfState)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .build()

    /**
     * Accept connections until the browser hits [REDIRECT_PATH], then return the
     * authorization code. Other requests (favicon probes, mostly) are answered and
     * ignored.
     */
    private suspend fun awaitAuthorizationCode(server: ServerSocket, csrfState: String): String {
        // A blocking accept() won't notice coroutine cancellation on its own; closing
        // the socket from the completion handler breaks it out.
        val closeOnCancel = coroutineContext[Job]?.invokeOnCompletion {
            runCatching { server.close() }
        }
        try {
            while (true) {
                val code = server.accept().use { readCallback(it, csrfState) }
                if (code != null) return code
            }
        } finally {
            closeOnCancel?.dispose()
        }
    }

    /** Returns the authorization code if this request was the callback, else null. */
    private fun readCallback(socket: Socket, csrfState: String): String? {
        val requestLine = socket.getInputStream().bufferedReader().readLine()
            ?: return null
        // "GET /login?code=...&state=... HTTP/1.1"
        val target = requestLine.split(' ').getOrNull(1) ?: return null
        if (!target.startsWith(REDIRECT_PATH)) {
            respond(socket, "Waiting for Spotify…")
            return null
        }

        val params = Uri.parse("http://127.0.0.1$target")
        val error = params.getQueryParameter("error")
        if (error != null) {
            respond(socket, "Sign-in was cancelled. You can close this page.")
            throw SignInDeniedException(
                if (error == "access_denied") "Sign-in was cancelled"
                else "Spotify rejected the sign-in: $error"
            )
        }

        if (params.getQueryParameter("state") != csrfState) {
            respond(socket, "Sign-in could not be verified. You can close this page.")
            throw SignInDeniedException("Sign-in could not be verified — please try again")
        }

        val code = params.getQueryParameter("code")
        if (code == null) {
            respond(socket, "Sign-in could not be completed. You can close this page.")
            throw SignInDeniedException("Spotify did not return an authorization code")
        }

        respond(socket, "Signed in to Sidetrack. You can close this page and return to the app.")
        return code
    }

    private fun respond(socket: Socket, message: String) {
        val body = """
            <!doctype html><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Sidetrack</title>
            <body style="font-family:sans-serif;background:#111;color:#eee;padding:2rem">
            <p>$message</p>
        """.trimIndent()
        val response = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${body.toByteArray().size}\r\n")
            append("Connection: close\r\n\r\n")
            append(body)
        }
        runCatching {
            socket.getOutputStream().apply {
                write(response.toByteArray())
                flush()
            }
        }
    }

    private suspend fun exchangeCode(code: String, verifier: String, redirectUri: String) {
        val body = mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to CLIENT_ID,
            "code_verifier" to verifier,
        )
        val json = postTokenRequest(body)
        saveTokens(json)
        _state.value = AuthState(
            isAuthenticated = true,
            version = _state.value.version + 1,
        )
    }

    suspend fun refreshAccessToken(): String? {
        Log.i(TAG, "refreshAccessToken: entering")
        val refreshToken = prefs.getString(TOKEN_TYPE_REFRESH, null)
        if (refreshToken == null) {
            Log.i(TAG, "refreshAccessToken: refresh token is null")
            return null
        }

        return try {
            val body = mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to CLIENT_ID,
            )
            val json = postTokenRequest(body)
            saveTokens(json)
            Log.i(TAG, "refreshAccessToken: success")
            json.getString("access_token")
        } catch (e: Exception) {
            Log.i(TAG, "refreshAccessToken: failed", e)
            _state.value = _state.value.copy(error = "Token refresh failed: ${e.message}")
            null
        }
    }

    suspend fun getValidAccessToken(): String? {
        val token = prefs.getString(TOKEN_TYPE_ACCESS, null)
        if (token == null) {
            Log.i(TAG, "getValidAccessToken: no access token")
            return null
        }
        val expiresAtStr = prefs.getString(KEY_EXPIRES_AT, null)
        val expiresAt = expiresAtStr?.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()
        val secsLeft = (expiresAt - now) / 1000
        Log.i(TAG, "getValidAccessToken: expiresAt=$expiresAt now=$now secsLeft=$secsLeft")

        // Refresh if token expires within 60 seconds
        return if (now > expiresAt - 60_000) {
            Log.i(TAG, "getValidAccessToken: token expired/expiring, refreshing")
            refreshAccessToken()
        } else {
            token
        }
    }

    fun setError(message: String) {
        _state.value = _state.value.copy(error = message)
    }

    fun logout() {
        Log.i(TAG, "logout: called")
        cancelSignIn()
        cancelZeroconfPairing()
        prefs.edit().clear().commit()
        _state.value = AuthState(isAuthenticated = false)
    }

    /** True when the stored token was issued under a different client id or scope
     *  set than this build asks for, so it has to be thrown away and re-granted.
     *  Zeroconf-paired credentials aren't an OAuth grant, so this never applies
     *  to them — only to a stored OAuth access token. */
    fun needsReauth(): Boolean {
        if (prefs.getString(TOKEN_TYPE_ACCESS, null) == null) return false
        val stored = prefs.getString(KEY_GRANT_HASH, null)
        val computed = grantHash()
        val result = stored != computed
        Log.i(TAG, "needsReauth: stored=$stored computed=$computed result=$result")
        return result
    }

    private fun grantHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$CLIENT_ID $SCOPES".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun saveTokens(json: JSONObject) {
        prefs.edit()
            .putString(TOKEN_TYPE_ACCESS, json.getString("access_token"))
            .apply {
                if (json.has("refresh_token")) {
                    putString(TOKEN_TYPE_REFRESH, json.getString("refresh_token"))
                }
            }
            .putString(KEY_EXPIRES_AT, (System.currentTimeMillis() + json.getInt("expires_in") * 1000L).toString())
            .putString(KEY_GRANT_HASH, grantHash())
            .commit()
        Log.i(TAG, "saveTokens: stored in SharedPreferences")
    }

    private suspend fun postTokenRequest(params: Map<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            val url = URL(TOKEN_URL)
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

                val body = params.entries.joinToString("&") { (k, v) ->
                    "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
                }

                OutputStreamWriter(conn.outputStream).use { it.write(body) }

                if (conn.responseCode != 200) {
                    val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: "unknown error"
                    throw Exception("HTTP ${conn.responseCode}: $errorBody")
                }

                val responseBody = conn.inputStream.bufferedReader().readText()
                JSONObject(responseBody)
            } finally {
                conn.disconnect()
            }
        }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            .take(128)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun generateCsrfState(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
