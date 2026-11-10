//! Provider-free acceptance tests on the same local factory, sidecar lookup and loader as JNI.
//! Synthetic encrypted MP3; no account data and no provider endpoints.
use std::{collections::HashMap, path::PathBuf, sync::{Arc, Mutex, atomic::{AtomicUsize, Ordering}}, time::Duration};
use librespot_core::{config::SessionConfig, http_client::{HttpClient, HttpTransport, ResponseFuture},
    network::{NetworkAuthority, NetworkLease, Snapshot}, spotify_uri::SpotifyUri, Error, Session};
use librespot_playback::{audio_backend::{Sink, SinkResult}, config::PlayerConfig, convert::Converter,
    decoder::AudioPacket, mixer::NoOpVolume, player::PlayerEvent};

static SOURCE_TEST: Mutex<()> = Mutex::new(());
const URI: &str = "spotify:track:0000000000000000000001";
const ID: &str = "00000000000000000000000000000001";

struct Temp(PathBuf);
impl Temp {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!("fluidify-offline-{}", crate::events::random_id()));
        std::fs::create_dir_all(path.join("audio/00")).unwrap();
        std::fs::create_dir_all(path.join("meta/00")).unwrap();
        Self(path)
    }
    fn audio(&self) -> PathBuf { self.0.join("audio/00/000000000000000000000000000001") }
    fn meta(&self) -> PathBuf { self.0.join("meta/00/000000000000000000000000000001.json") }
    fn seed(&self) {
        std::fs::write(self.audio(), include_bytes!("../tests/fixtures/manual-offline/tone.mp3.aes")).unwrap();
        self.sidecar("00112233445566778899aabbccddeeff");
    }
    fn sidecar(&self, key: &str) {
        std::fs::write(self.meta(), serde_json::json!({"v":1,"uri":URI,"trackId":ID,
            "fileId":"0000000000000000000000000000000000000000", "format":"MP3_160",
            "key":key,"name":"Synthetic tone", "durationMs":1200,"album":"Synthetic"}).to_string()).unwrap();
    }
    fn bind(&self, owner: &str, namespace: &str) {
        crate::downloads::bind_source(crate::downloads::SourceBinding {
            owner:owner.into(), namespace:namespace.into(), root:self.0.clone(),
            records:HashMap::from([(URI.into(), ID.into())]),
        }).unwrap();
    }
}
impl Drop for Temp { fn drop(&mut self) { let _ = std::fs::remove_dir_all(&self.0); } }

struct Pcm(Arc<AtomicUsize>);
impl Sink for Pcm {
    fn write(&mut self, packet: AudioPacket, _: &mut Converter) -> SinkResult<()> {
        if let AudioPacket::Samples(samples) = packet {
            assert!(samples.iter().all(|sample| sample.is_finite()));
            self.0.fetch_add(samples.iter().filter(|sample| sample.abs() > 0.000001).count(), Ordering::SeqCst);
        }
        Ok(())
    }
}

fn config(authority: &NetworkAuthority, remote: Arc<AtomicUsize>) -> PlayerConfig {
    let authority = authority.clone();
    PlayerConfig {
        normalisation:false, ditherer:None, download_lookup:Some(crate::downloads::scoped_lookup()),
        network_lease:Some(Arc::new(move || authority.lease().unwrap_or_else(|_| NetworkLease::denied()))),
        session_ready:Some(Arc::new(move || {
            remote.fetch_add(1, Ordering::SeqCst);
            Box::pin(async { None })
        })), ..Default::default()
    }
}

#[tokio::test(flavor = "current_thread")]
async fn manual_cold_factory_decodes_encrypted_owned_restore_and_preserves_player_on_toggle() {
    let _serial = SOURCE_TEST.lock().unwrap();
    let temp = Temp::new(); temp.seed(); temp.bind("A", "namespace-A");
    let authority = NetworkAuthority::new(Snapshot { revision:1, manual:true });
    let remote = Arc::new(AtomicUsize::new(0));
    let pcm = Arc::new(AtomicUsize::new(0)); let sink = pcm.clone();
    let (placeholder, player) = crate::local_player::build(config(&authority, remote.clone()),
        SessionConfig::default(), None, Box::new(NoOpVolume), move || Box::new(Pcm(sink)));
    assert!(placeholder.network().check().is_err());
    let identity = Arc::as_ptr(&player);
    let mut events = player.get_player_event_channel();
    player.load_with_restore_nonce(SpotifyUri::from_uri(URI).unwrap(), true, 0, Some("owned-restore".into()));
    let mut nonce = false;
    tokio::time::timeout(Duration::from_secs(5), async {
        while let Some(event) = events.recv().await {
            match event {
                PlayerEvent::OwnedLoadBound(binding) => { assert_eq!(binding.nonce, "owned-restore"); nonce = true; }
                PlayerEvent::EndOfTrack { .. } => break,
                PlayerEvent::Unavailable { .. } => panic!("synthetic downloaded audio became unavailable"),
                _ => (),
            }
        }
    }).await.unwrap();
    assert!(nonce); assert!(pcm.load(Ordering::SeqCst) > 0);
    authority.apply(Snapshot { revision:2, manual:false }).unwrap();
    authority.apply(Snapshot { revision:3, manual:true }).unwrap();
    assert_eq!(identity, Arc::as_ptr(&player));
    assert!(placeholder.network().check().is_err());
    assert_eq!(remote.load(Ordering::SeqCst), 0);
    assert!(temp.audio().is_file()); assert!(temp.meta().is_file());
    player.stop(); drop(player); crate::downloads::clear_source();
}

#[tokio::test(flavor = "current_thread")]
async fn missing_corrupt_audio_or_key_is_terminal_without_remote_fallback_or_retry() {
    let _serial = SOURCE_TEST.lock().unwrap();
    for broken in ["missing-file", "missing-sidecar", "missing-key", "wrong-key", "corrupt-file"] {
        let temp = Temp::new(); temp.seed();
        match broken {
            "missing-file" => std::fs::remove_file(temp.audio()).unwrap(),
            "missing-sidecar" => std::fs::remove_file(temp.meta()).unwrap(),
            "missing-key" => temp.sidecar(""),
            "wrong-key" => temp.sidecar("ffffffffffffffffffffffffffffffff"),
            "corrupt-file" => std::fs::write(temp.audio(), [0u8; 48]).unwrap(),
            _ => unreachable!(),
        }
        temp.bind("A", "namespace-A");
        let authority = NetworkAuthority::new(Snapshot { revision:1, manual:true });
        let remote = Arc::new(AtomicUsize::new(0));
        let pcm = Arc::new(AtomicUsize::new(0)); let sink = pcm.clone();
        let (_, player) = crate::local_player::build(config(&authority, remote.clone()),
            SessionConfig::default(), None, Box::new(NoOpVolume), move || Box::new(Pcm(sink)));
        let mut events = player.get_player_event_channel();
        player.load(SpotifyUri::from_uri(URI).unwrap(), true, 0);
        let mut loading = 0;
        tokio::time::timeout(Duration::from_secs(5), async {
            while let Some(event) = events.recv().await {
                match event {
                    PlayerEvent::Loading { .. } => loading += 1,
                    PlayerEvent::Stopped { .. } => break,
                    PlayerEvent::Unavailable { .. } => panic!("manual failure must not remove the track"),
                    _ => (),
                }
            }
        }).await.unwrap();
        assert_eq!(loading, 1, "{broken}");
        assert_eq!(remote.load(Ordering::SeqCst), 0, "{broken}");
        assert_eq!(pcm.load(Ordering::SeqCst), 0, "{broken}");
        // Same production player must recover immediately with a valid local file.
        temp.seed(); temp.bind("A", "namespace-A");
        player.load(SpotifyUri::from_uri(URI).unwrap(), true, 0);
        tokio::time::timeout(Duration::from_secs(5), async {
            loop { if pcm.load(Ordering::SeqCst) > 0 { break; } tokio::task::yield_now().await; }
        }).await.unwrap();
        player.stop(); drop(player);
    }
    crate::downloads::clear_source();
}

#[test]
fn source_binding_rejects_another_account_same_root_and_late_decoded_results() {
    let _serial = SOURCE_TEST.lock().unwrap();
    let temp = Temp::new(); temp.seed(); temp.bind("A", "namespace-A");
    let lookup = crate::downloads::scoped_lookup();
    let uri = SpotifyUri::from_uri(URI).unwrap(); let id = (&uri).try_into().unwrap();
    let downloaded = lookup(&id).unwrap();
    assert!(downloaded.is_current.as_ref().unwrap()());
    temp.bind("B", "namespace-B");
    assert!(lookup(&id).is_none());
    assert!(!downloaded.is_current.as_ref().unwrap()());
    assert!(temp.audio().is_file()); crate::downloads::clear_source();
}

struct RecordingTransport { calls: Arc<AtomicUsize> }
impl HttpTransport for RecordingTransport {
    fn request(&self, _: http::Request<bytes::Bytes>) -> ResponseFuture {
        self.calls.fetch_add(1, Ordering::SeqCst);
        Box::pin(std::future::pending())
    }
}

#[tokio::test]
async fn actual_http_raw_stream_and_pending_request_keep_their_original_lease() {
    let authority = NetworkAuthority::new(Snapshot { revision:0, manual:false });
    let old = authority.lease().unwrap();
    let calls = Arc::new(AtomicUsize::new(0));
    let client = HttpClient::with_transport(old.clone(), Arc::new(RecordingTransport { calls:calls.clone() }));
    let request = || http::Request::builder().uri("https://synthetic.invalid/audio")
        .body(bytes::Bytes::new()).unwrap();
    let mut pending = client.request_fut(request()).unwrap();
    assert!(futures_util::poll!(pending.as_mut()).is_pending());
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    authority.apply(Snapshot { revision:1, manual:true }).unwrap();
    authority.apply(Snapshot { revision:2, manual:false }).unwrap();
    assert!(pending.await.is_err());
    assert!(client.request_stream(request()).is_err());
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    let fresh = HttpClient::with_transport(authority.lease().unwrap(), Arc::new(RecordingTransport { calls:calls.clone() }));
    drop(fresh.request_fut(request()).unwrap());
    assert_eq!(calls.load(Ordering::SeqCst), 2);
    authority.close();
    assert!(fresh.request_fut(request()).is_err());
}

#[tokio::test]
async fn placeholder_and_manual_ap_auth_packet_dealer_are_denied_while_legacy_wear_is_online() {
    let manual = Session::new_with_network(SessionConfig::default(), None, NetworkLease::denied());
    assert!(manual.connect(librespot_core::authentication::Credentials::with_access_token("synthetic"), false).await.is_err());
    assert!(manual.send_packet(librespot_core::packet::PacketType::Ping, vec![]).is_err());
    assert!(manual.dealer().start().await.is_err());
    let legacy = Session::new(SessionConfig::default(), None);
    assert!(legacy.network().check().is_ok()); // No connect: Wear default compatibility, not a provider call.
}

/// Entire native engine initializer, real queue/restore/transport, event pump and decrypted PCM.
/// Only the JVM event consumer and Android output are replaced by recording host sinks.
#[test]
fn manual_native_engine_cold_boot_preserves_queue_and_pcm_through_toggles_and_stale_handles() {
    let _serial = SOURCE_TEST.lock().unwrap();
    let temp = Temp::new(); temp.seed();
    let uri2 = "spotify:track:0000000000000000000002".to_string();
    let id2 = "00000000000000000000000000000002".to_string();
    let audio2 = temp.0.join("audio/00/000000000000000000000000000002");
    let meta2 = temp.0.join("meta/00/000000000000000000000000000002.json");
    std::fs::copy(temp.audio(), &audio2).unwrap();
    let mut second: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(temp.meta()).unwrap()).unwrap();
    second["uri"] = uri2.clone().into(); second["trackId"] = id2.clone().into();
    std::fs::write(&meta2, second.to_string()).unwrap();
    crate::downloads::bind_source(crate::downloads::SourceBinding { owner:"A".into(), namespace:"namespace-A".into(),
        root:temp.0.clone(), records:HashMap::from([(URI.into(), ID.into()), (uri2.clone(), id2)]) }).unwrap();
    let authority = NetworkAuthority::new(Snapshot { revision:10, manual:true });
    let admissions = Arc::new(Mutex::new(Vec::new())); let recorded = admissions.clone();
    authority.set_dispatch_observer(Arc::new(move |kind| {
        recorded.lock().unwrap().push(kind);
        Err(Error::unavailable("test forbids every external transport"))
    }));
    let pcm = Arc::new(AtomicUsize::new(0)); let sink = pcm.clone();
    let (events_tx, events_rx) = std::sync::mpsc::channel();
    struct Paced(Pcm);
    impl Sink for Paced {
        fn write(&mut self, packet: AudioPacket, converter: &mut Converter) -> SinkResult<()> {
            let result = self.0.write(packet, converter);
            std::thread::sleep(Duration::from_millis(2)); result
        }
    }
    let handle = crate::engine::start_host(temp.0.to_str().unwrap(), Snapshot { revision:10, manual:true },
        events_tx, Box::new(move |_| Box::new(Paced(Pcm(sink)))), authority.clone()).unwrap();
    struct StopEngine;
    impl Drop for StopEngine { fn drop(&mut self) { crate::engine::shutdown(); } }
    let _stop = StopEngine;
    let (player, _, generation) = crate::engine::host_state().unwrap(); let identity = Arc::as_ptr(&player) as usize; drop(player);
    let queue = vec![URI.into(), uri2.clone()];
    crate::engine::load_queue_owned(queue.clone(), 0, false, 250, "spotify:playlist:synthetic".into(), false,
        Some("native-owned-restore".into())).unwrap();
    let deadline = std::time::Instant::now() + Duration::from_secs(5);
    loop {
        let event = events_rx.recv_timeout(deadline.saturating_duration_since(std::time::Instant::now())).unwrap();
        if event.0 == "paused" { break; }
    }
    assert_eq!(pcm.load(Ordering::SeqCst), 0);
    crate::engine::play().unwrap();
    while pcm.load(Ordering::SeqCst) == 0 { assert!(std::time::Instant::now() < deadline); std::thread::yield_now(); }
    let before = pcm.load(Ordering::SeqCst);
    #[cfg(feature = "offline-contract-tests")]
    let mut connect_task = None;
    for (revision, manual) in [(11,false),(12,true),(13,false),(14,true)] {
        assert_eq!(crate::engine::apply_network_snapshot(handle, Snapshot { revision, manual }).unwrap(), revision);
        let (player, actual_queue, audio_generation) = crate::engine::host_state().unwrap();
        assert_eq!(identity, Arc::as_ptr(&player) as usize); assert_eq!(actual_queue, queue); assert_eq!(generation, audio_generation);
        #[cfg(feature = "offline-contract-tests")]
        if revision == 11 {
            let rt=crate::engine::runtime_handle().unwrap();
            connect_task=Some(rt.block_on(async {
                let session=Session::new_with_network(SessionConfig::default(),None,authority.lease().unwrap());
                let (ready,started)=tokio::sync::oneshot::channel();
                let task=tokio::spawn(librespot_connect::manual_offline_connected_task(session,player.clone(),ready));
                tokio::time::timeout(Duration::from_secs(1),started).await.unwrap().unwrap();
                task
            }));
        }
    }
    #[cfg(feature = "offline-contract-tests")]
    crate::engine::runtime_handle().unwrap().block_on(async {
        tokio::time::timeout(Duration::from_secs(1),connect_task.unwrap()).await.unwrap().unwrap();
    });
    crate::engine::seek(500).unwrap();
    // The phone owns offline advancement and sends the queue at its next index.
    crate::engine::load_queue(queue.clone(), 1, true, 0, "spotify:playlist:synthetic".into(), false).unwrap();
    let deadline = std::time::Instant::now() + Duration::from_secs(5);
    while pcm.load(Ordering::SeqCst) <= before { assert!(std::time::Instant::now() < deadline); std::thread::yield_now(); }
    assert!(admissions.lock().unwrap().is_empty());
    assert_eq!(crate::engine::current_context(), "spotify:playlist:synthetic");
    crate::engine::shutdown();
    assert!(crate::engine::apply_network_snapshot(handle, Snapshot { revision:15, manual:false }).is_err());
    assert!(temp.audio().is_file() && temp.meta().is_file());
}

#[cfg(feature = "offline-contract-tests")]
mod transport_contract {
    #[tokio::test(flavor = "current_thread")]
    async fn parsed_apwelcome_receipt() { librespot_core::manual_offline_tests::parsed_apwelcome_rotation_survives_concurrent_revocation().await; }
    #[tokio::test(flavor = "current_thread")]
    async fn blocked_streaming_read() { librespot_audio::manual_offline_tests::blocked_streaming_reader_wakes_on_revocation_and_cached_bytes_can_drain().await; }
    #[tokio::test(flavor = "current_thread")]
    async fn audio_range_dispatch() { librespot_audio::manual_offline_tests::range_dispatch_is_fenced_even_when_fetch_command_was_already_queued().await; }
    use librespot_core::manual_offline_tests as scenarios;
    #[tokio::test(flavor = "current_thread")]
    async fn http429() { scenarios::manual_offline_http_429_wait_is_cancelled_and_old_request_never_retries().await; }
    #[tokio::test(flavor = "current_thread")]
    async fn ap_handshake() { scenarios::manual_offline_ap_handshake_revocation_prevents_retry_even_after_fast_off().await; }
    #[tokio::test(flavor = "current_thread")]
    async fn ap_auth() { scenarios::manual_offline_ap_auth_revocation_drops_real_encrypted_login_without_followup().await; }
    #[tokio::test(flavor = "current_thread")]
    async fn ap_packet_queue() { librespot_core::session::manual_offline_packet_tests::manual_offline_packet_sender_does_not_drain_queued_packets_after_revocation().await; }
    #[tokio::test(flavor = "current_thread")]
    async fn dealer_heartbeat_reconnect() { librespot_core::dealer::manual_offline_tests::manual_offline_dealer_stops_heartbeat_and_reconnect_on_its_original_lease().await; }
    #[test]
    fn local_credential_receipt() { scenarios::manual_offline_received_local_credential_can_commit_until_its_account_owner_retires(); }
}

#[test]
fn manual_bootstrap_without_receipt_then_canonical_source_and_relogin_use_distinct_owners() {
    let _serial=SOURCE_TEST.lock().unwrap();
    let temp=Temp::new(); temp.seed(); crate::downloads::clear_source();
    let authority=NetworkAuthority::new(Snapshot { revision:20,manual:true });
    let admitted=Arc::new(AtomicUsize::new(0)); let calls=admitted.clone();
    authority.set_dispatch_observer(Arc::new(move |_| { calls.fetch_add(1,Ordering::SeqCst); Err(Error::unavailable("synthetic provider denial")) }));
    let (tx,rx)=std::sync::mpsc::channel();
    let pcm=Arc::new(AtomicUsize::new(0)); let output=pcm.clone();
    let handle=crate::engine::start_host(temp.0.to_str().unwrap(),Snapshot { revision:20,manual:true },tx,
        Box::new(move |_| Box::new(Pcm(output))),authority.clone()).unwrap();
    struct Stop; impl Drop for Stop { fn drop(&mut self) { crate::engine::shutdown(); } } let _stop=Stop;
    assert!(!temp.0.join("reusable/credentials.json").exists());
    let (player,_,_)=crate::engine::host_state().unwrap(); let identity=Arc::as_ptr(&player) as usize;
    let lookup=crate::downloads::scoped_lookup(); let uri=SpotifyUri::from_uri(URI).unwrap();
    assert!(lookup(&(&uri).try_into().unwrap()).is_none());
    crate::engine::apply_network_snapshot(handle,Snapshot { revision:21,manual:false }).unwrap();
    // No local receipt: an explicitly admitted OAuth bootstrap still reaches a recorded transport.
    crate::engine::ensure_online(handle,21,"synthetic-oauth-token").unwrap();
    let deadline=std::time::Instant::now()+Duration::from_secs(3);
    while admitted.load(Ordering::SeqCst)==0 { assert!(std::time::Instant::now()<deadline); std::thread::yield_now(); }
    crate::engine::apply_network_snapshot(handle,Snapshot { revision:22,manual:true }).unwrap();
    assert!(lookup(&(&uri).try_into().unwrap()).is_none());
    let source=serde_json::json!({"owner":"verified-A","namespace":"namespace-A","root":temp.0,
        "records":{URI:ID}}).to_string();
    crate::engine::bind_download_source(handle,&source).unwrap();
    let downloaded=lookup(&(&uri).try_into().unwrap()).unwrap(); assert!(downloaded.is_current.as_ref().unwrap()());
    crate::engine::load_queue(vec![URI.into()],0,true,0,"".into(),false).unwrap();
    while pcm.load(Ordering::SeqCst)==0 { assert!(std::time::Instant::now()<deadline); std::thread::yield_now(); }
    assert_eq!(identity,Arc::as_ptr(&crate::engine::host_state().unwrap().0) as usize);
    let replacement=source.replace("verified-A","verified-B").replace("namespace-A","namespace-B");
    assert!(crate::engine::bind_download_source(handle,&replacement).is_err());
    crate::engine::retire_network_owner(handle).unwrap();
    assert!(!downloaded.is_current.as_ref().unwrap()());
    assert!(crate::engine::bind_download_source(handle,&source).is_err());
    crate::engine::shutdown(); drop(player); drop(rx);
    // Logout's tombstone/credential deletion leaves no receipt. Reauthentication gets a new owner,
    // while synthetic downloaded files are retained and inaccessible until a new canonical binding.
    let (tx,_rx)=std::sync::mpsc::channel();
    let second_policy=NetworkAuthority::new(Snapshot { revision:22,manual:true });
    let calls=admitted.clone();
    second_policy.set_dispatch_observer(Arc::new(move |_| { calls.fetch_add(1,Ordering::SeqCst); Err(Error::unavailable("synthetic relogin denial")) }));
    let previous=admitted.load(Ordering::SeqCst);
    let second=crate::engine::start_host(temp.0.to_str().unwrap(),Snapshot { revision:22,manual:true },tx,
        Box::new(move |_| Box::new(Pcm(Arc::new(AtomicUsize::new(0))))),second_policy).unwrap();
    assert_ne!(handle,second); assert!(lookup(&(&uri).try_into().unwrap()).is_none());
    assert!(crate::engine::bind_download_source(handle,&source).is_err());
    crate::engine::apply_network_snapshot(second,Snapshot { revision:23,manual:false }).unwrap();
    crate::engine::ensure_online(second,23,"synthetic-second-oauth").unwrap();
    let deadline=std::time::Instant::now()+Duration::from_secs(3);
    while admitted.load(Ordering::SeqCst)<=previous { assert!(std::time::Instant::now()<deadline); std::thread::yield_now(); }
    crate::engine::apply_network_snapshot(second,Snapshot { revision:24,manual:true }).unwrap();
    crate::engine::bind_download_source(second,&replacement).unwrap();
    assert!(temp.audio().exists());
    crate::engine::shutdown();
}
