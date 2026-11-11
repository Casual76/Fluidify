//! The production local factory shared by JNI boot and provider-free host tests.
use librespot_core::{cache::Cache, config::SessionConfig, network::NetworkLease, Session};
use librespot_playback::{audio_backend::Sink, config::PlayerConfig, mixer::VolumeGetter, player::Player};
use std::sync::Arc;

pub(crate) fn build<F>(config: PlayerConfig, session_config: SessionConfig, cache: Option<Cache>,
    volume: Box<dyn VolumeGetter + Send>, sink: F) -> (Session, Arc<Player>)
where F: FnOnce() -> Box<dyn Sink> + Send + 'static {
    let session = Session::new_with_network(session_config, cache, NetworkLease::denied());
    let player = Player::new(config, session.clone(), volume, sink);
    (session, player)
}
