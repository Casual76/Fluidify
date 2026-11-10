//! Synthetic peers, but the streaming reader, range dispatch and revocation watcher are production.
use super::*;
use librespot_core::{network::{NetworkAuthority, Snapshot}};
use std::io::Write;

pub(super) fn shared(network: librespot_core::network::NetworkLease) -> Arc<AudioFileShared> {
    Arc::new(AudioFileShared {
        network, cdn_url: "http://127.0.0.1:1/synthetic".into(), file_size: 128, bytes_per_second: 1,
        cond: Condvar::new(), download_status: Mutex::new(AudioFileDownloadStatus {
            requested: RangeSet::new(), downloaded: RangeSet::new(),
        }), download_streaming: AtomicBool::new(false), download_slots: Semaphore::new(1),
        ping_time_ms: AtomicUsize::new(0), read_position: AtomicUsize::new(0), throughput: AtomicUsize::new(0),
    })
}

pub async fn blocked_streaming_reader_wakes_on_revocation_and_cached_bytes_can_drain() {
    let authority = NetworkAuthority::new(Snapshot { revision:1, manual:false });
    let shared = shared(authority.lease().unwrap());
    let mut file = NamedTempFile::new().unwrap();
    file.write_all(&[7u8;128]).unwrap();
    let (tx,mut rx) = mpsc::unbounded_channel();
    let mut reader = AudioFileStreaming { read_file:file.reopen().unwrap(), position:0,
        stream_loader_command_tx:tx, shared:shared.clone() };
    let watcher = tokio::spawn(watch_revocation(shared.clone()));
    let (done_tx, done_rx) = oneshot::channel();
    let worker = std::thread::spawn(move || {
        let result = reader.read(&mut [0;16]);
        done_tx.send((result,reader)).ok().unwrap();
    });
    assert!(matches!(tokio::time::timeout(Duration::from_secs(2), rx.recv()).await.unwrap(), Some(StreamLoaderCommand::Fetch(_))));
    authority.apply(Snapshot { revision:2,manual:true }).unwrap();
    authority.apply(Snapshot { revision:3,manual:false }).unwrap();
    let (result,mut reader) = tokio::time::timeout(Duration::from_secs(1),done_rx).await.unwrap().unwrap();
    assert_eq!(result.unwrap_err().kind(), io::ErrorKind::Interrupted);
    watcher.await.unwrap(); worker.join().unwrap();
    shared.download_status.lock().unwrap().downloaded.add_range(&Range::new(0,16));
    let mut bytes=[0;16];
    assert_eq!(reader.read(&mut bytes).unwrap(),16); assert_eq!(bytes,[7;16]);
    assert!(rx.try_recv().is_err());
}

pub async fn range_dispatch_is_fenced_even_when_fetch_command_was_already_queued() {
    super::receive::manual_offline_range_dispatch().await;
}
