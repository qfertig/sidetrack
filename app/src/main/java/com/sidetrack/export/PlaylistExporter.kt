package com.sidetrack.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.sidetrack.bridge.NativeBridge
import com.sidetrack.bridge.TrackInfo
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ExportResult(
    val jsonFileName: String,
    val m3uFileName: String,
    val trackCount: Int,
)

// Fetch this many tracks at once. get_playlist_info only returns URIs - each track's
// title/artist/duration needs its own metadataGetTrack call, so a 1000-track playlist
// is 1000 JNI round trips. Some parallelism keeps that from being dog slow; too much
// just floods librespot for no gain, so keep it modest.
private const val TRACK_FETCH_CONCURRENCY = 8

/**
 * Writes a Spotify playlist to Documents/Sidetrack as the same JSON schema Keytune
 * uses for its own exports (see Keytune's PlaylistExporter.kt - this is meant to be a
 * drop-in match, not a lookalike), plus a derived M3U. Both are written incrementally
 * page by page so a large playlist never has to sit fully in memory.
 */
class PlaylistExporter(private val context: Context) {

    suspend fun export(
        title: String,
        trackUris: List<String>,
        onProgress: (fetched: Int, total: Int) -> Unit,
    ): ExportResult = withContext(Dispatchers.IO) {
        val safeName = title
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .ifBlank { "playlist" }
        val jsonFileName = "$safeName.json"
        // Not ".m3u" - see HARDWARE-AND-LESSONS.md, this device's playlist scanner
        // rewrites files with that exact extension down to just their header once it
        // can't resolve every entry as local media. ".m3u.txt" dodges it; the content
        // is still plain #EXTM3U and renames back to .m3u fine anywhere else.
        val m3uFileName = "$safeName.m3u.txt"

        val localJson = File(context.cacheDir, jsonFileName)
        val localM3u = File(context.cacheDir, m3uFileName)

        var total = 0
        val fetchSemaphore = Semaphore(TRACK_FETCH_CONCURRENCY)
        var fetched = 0

        localJson.bufferedWriter().use { json ->
            localM3u.bufferedWriter().use { m3u ->
                json.write(jsonHeader(title))
                m3u.write("#EXTM3U\n")

                var wroteAnyTrack = false
                trackUris.chunked(TRACK_FETCH_CONCURRENCY).forEach { chunk ->
                    val tracks = coroutineScope {
                        chunk.map { uri ->
                            async {
                                fetchSemaphore.withPermit {
                                    NativeBridge.metadataGetTrack(uri)?.let { TrackInfo.fromJson(it) }
                                }
                            }
                        }.awaitAll()
                    }
                    for (track in tracks) {
                        if (track == null) continue
                        if (wroteAnyTrack) json.write(",")
                        wroteAnyTrack = true
                        json.write(trackJson(track).toString())
                        m3u.write(m3uEntry(track))
                        total++
                    }
                    fetched += chunk.size
                    onProgress(fetched, trackUris.size)
                }

                json.write("]}")
            }
        }

        val jsonUri = createDocumentFile(jsonFileName, "application/json")
        val m3uUri = createDocumentFile(m3uFileName, "text/plain")
        copyFileToUri(localJson, jsonUri)
        copyFileToUri(localM3u, m3uUri)
        localJson.delete()
        localM3u.delete()

        ExportResult(jsonFileName, m3uFileName, total)
    }

    private fun copyFileToUri(source: File, destination: Uri) {
        source.inputStream().use { input ->
            context.contentResolver.openOutputStream(destination)!!.use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun jsonHeader(title: String): String {
        val header = JSONObject().apply {
            put("schemaVersion", 1)
            put("title", title)
            put("source", "spotify")
            put("exportedAt", Instant.now().toString())
        }
        return header.toString().removeSuffix("}") + ",\"tracks\":["
    }

    private fun trackJson(track: TrackInfo): JSONObject = JSONObject().apply {
        put("title", track.name)
        put("artist", track.artistName)
        put("durationSeconds", track.durationMs / 1000)
        put("sourceIds", JSONObject().put("spotify", track.uri))
    }

    private fun m3uEntry(track: TrackInfo): String {
        val duration = track.durationMs / 1000
        return "#EXTINF:$duration,${track.artistName} - ${track.name}\n" +
            "${track.uri}\n"
    }

    private fun createDocumentFile(fileName: String, mimeType: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/Sidetrack")
        }
        return context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
            ?: error("Could not create $fileName in Documents/Sidetrack")
    }
}
