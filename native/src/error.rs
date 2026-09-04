use thiserror::Error;

#[derive(Error, Debug)]
pub enum SidetrackError {
    #[error("Session error: {0}")]
    Session(String),

    #[error("Player error: {0}")]
    Player(String),

    #[allow(dead_code)]
    #[error("Authentication error: {0}")]
    Auth(String),

    #[error("JNI error: {0}")]
    Jni(#[from] jni::errors::Error),

    #[error("Serialization error: {0}")]
    Serde(#[from] serde_json::Error),

    #[error("No active session")]
    NoSession,

    #[error("No active player")]
    NoPlayer,
}

pub type Result<T> = std::result::Result<T, SidetrackError>;
