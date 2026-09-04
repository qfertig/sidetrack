//! Spotify Connect Zeroconf pairing.
//!
//! Advertises this device over mDNS so the official Spotify app can find it
//! under Devices and hand over stored credentials directly — no browser or
//! OAuth redirect involved, so it works from any phone on the same network.

use std::sync::{Mutex as StdMutex, OnceLock};

use futures_util::StreamExt;
use librespot_discovery::{Builder, Credentials, DeviceType};
use protobuf::Enum;
use serde::Serialize;
use tokio::sync::oneshot;

use crate::session;

/// Same client id librespot's login5 stored-credential exchange expects
/// (see [`crate::session`] / `SessionConfig::default()`), so pairing over
/// Zeroconf doesn't hit the client-id mismatch that OAuth-via-redirect can.
const ZEROCONF_CLIENT_ID: &str = "65b708073fc0480ea92a077233ca87bd";

#[derive(Clone, Serialize)]
#[serde(tag = "status", rename_all = "lowercase")]
enum DiscoveryStatus {
    Idle,
    Pending,
    Connected {
        username: String,
        #[serde(rename = "authData")]
        auth_data: String,
        #[serde(rename = "authType")]
        auth_type: i32,
    },
    Error {
        message: String,
    },
}

static STATUS: OnceLock<StdMutex<DiscoveryStatus>> = OnceLock::new();
static SHUTDOWN: OnceLock<StdMutex<Option<oneshot::Sender<()>>>> = OnceLock::new();

fn status_slot() -> &'static StdMutex<DiscoveryStatus> {
    STATUS.get_or_init(|| StdMutex::new(DiscoveryStatus::Idle))
}

fn shutdown_slot() -> &'static StdMutex<Option<oneshot::Sender<()>>> {
    SHUTDOWN.get_or_init(|| StdMutex::new(None))
}

fn set_status(status: DiscoveryStatus) {
    *status_slot().lock().unwrap() = status;
}

/// Start advertising this device for Zeroconf pairing under `device_name`.
/// Cancels any pairing already in progress first.
pub fn start(device_name: String) {
    stop();
    set_status(DiscoveryStatus::Pending);

    let (tx, rx) = oneshot::channel::<()>();
    *shutdown_slot().lock().unwrap() = Some(tx);

    session::runtime().spawn(async move {
        let device_id = format!("sidetrack-{}", uuid_v4());

        let discovery = match Builder::new(device_id, ZEROCONF_CLIENT_ID.to_string())
            .name(device_name)
            .device_type(DeviceType::Tv)
            .launch()
        {
            Ok(d) => d,
            Err(e) => {
                log::error!("discovery: launch failed: {e}");
                set_status(DiscoveryStatus::Error {
                    message: format!("Couldn't start Zeroconf pairing: {e}"),
                });
                return;
            }
        };
        let mut discovery = discovery;

        tokio::select! {
            _ = rx => {
                log::info!("discovery: pairing cancelled");
            }
            _ = async {
                while let Some(credentials) = discovery.next().await {
                    log::info!("discovery: credentials received, connecting session");
                    handle_credentials(credentials).await;
                    break;
                }
            } => {}
        }
    });
}

async fn handle_credentials(credentials: Credentials) {
    use base64::Engine as _;

    let username = credentials.username.clone().unwrap_or_default();
    let auth_type = credentials.auth_type.value();
    let auth_data = base64::engine::general_purpose::STANDARD.encode(&credentials.auth_data);

    match session::connect_with_credentials(credentials).await {
        Ok(()) => {
            set_status(DiscoveryStatus::Connected {
                username,
                auth_data,
                auth_type,
            });
        }
        Err(e) => {
            log::error!("discovery: session connect failed: {e}");
            set_status(DiscoveryStatus::Error {
                message: format!("{e}"),
            });
        }
    }
}

/// Stop advertising and abandon any pairing in progress.
pub fn stop() {
    if let Some(tx) = shutdown_slot().lock().unwrap().take() {
        let _ = tx.send(());
    }
    set_status(DiscoveryStatus::Idle);
}

/// Current pairing status as JSON: `{"status":"idle"|"pending"}`,
/// `{"status":"connected","username":...,"authData":...,"authType":...}`,
/// or `{"status":"error","message":...}`.
pub fn poll_result_json() -> String {
    serde_json::to_string(&*status_slot().lock().unwrap())
        .unwrap_or_else(|_| "{\"status\":\"error\",\"message\":\"serialize failed\"}".into())
}

/// A random-enough id for the mDNS advertisement; doesn't need to be a
/// cryptographic UUID, just unique per device.
fn uuid_v4() -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let mut bytes = [0u8; 8];
    let mut rng_state = nanos as u64 ^ (std::process::id() as u64).rotate_left(32);
    for b in bytes.iter_mut() {
        // xorshift64
        rng_state ^= rng_state << 13;
        rng_state ^= rng_state >> 7;
        rng_state ^= rng_state << 17;
        *b = (rng_state & 0xff) as u8;
    }
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}
