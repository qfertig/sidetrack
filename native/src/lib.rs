//! sidetrack-native: JNI bridge between librespot and the Android application.
//!
//! This crate exposes a C-compatible API via JNI that the Kotlin side calls
//! to manage Spotify sessions, control playback, and receive events.

mod audio_sink;
mod bridge;
mod discovery;
mod error;
mod library;
mod metadata;
mod player;
mod session;

use jni::JNIEnv;
use jni::objects::JClass;
use log::LevelFilter;

/// Initialize the native library. Called once from Application.onCreate().
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidetrack_bridge_NativeBridge_nativeInit(
    _env: JNIEnv,
    _class: JClass,
) {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(LevelFilter::Info)
            .with_tag("sidetrack-native"),
    );
    log::info!("sidetrack native library initialized");
}
