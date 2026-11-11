#[macro_use]
extern crate log;

mod decrypt;
mod fetch;

#[cfg(feature = "offline-contract-tests")]
#[doc(hidden)]
pub use fetch::manual_offline_tests;

mod range_set;

pub use decrypt::AudioDecrypt;
pub use fetch::{AudioFetchParams, AudioFile, AudioFileError, StreamLoaderController};
