use super::*;
use crate::network::{NetworkAuthority, Snapshot};
use tokio::net::TcpListener;

pub async fn manual_offline_dealer_stops_heartbeat_and_reconnect_on_its_original_lease() {
    let server = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = server.local_addr().unwrap();
    let authority = NetworkAuthority::new(Snapshot { revision:0, manual:false });
    let admissions = Arc::new(Mutex::new(Vec::new())); let recorded = admissions.clone();
    authority.set_dispatch_observer(Arc::new(move |kind| { recorded.lock().unwrap().push(kind); Ok(()) }));
    let urls = Arc::new(std::sync::atomic::AtomicUsize::new(0)); let url_calls = urls.clone();
    eprintln!("dealer stage launch");
    let lease = authority.lease().unwrap();
    let launch = tokio::spawn(async move { Builder::new().with_network(lease).launch(move || {
        url_calls.fetch_add(1, atomic::Ordering::SeqCst);
        async move { Ok(Url::parse(&format!("ws://{address}/synthetic")).unwrap()) }
    }, None).await });
    eprintln!("dealer stage accept");
    let (peer, _) = tokio::time::timeout(Duration::from_secs(2), server.accept()).await.unwrap().unwrap();
    eprintln!("dealer stage websocket");
    let mut socket = tokio::time::timeout(Duration::from_secs(2), tokio_tungstenite::accept_async(peer)).await.unwrap().unwrap();
    eprintln!("dealer stage first heartbeat");
    let dealer = tokio::time::timeout(Duration::from_secs(2), launch).await.unwrap().unwrap().unwrap();
    let first = tokio::time::timeout(Duration::from_secs(2), socket.next()).await.unwrap().unwrap().unwrap();
    assert!(matches!(first, WsMessage::Ping(_)));
    eprintln!("dealer stage revoke");
    authority.apply(Snapshot { revision:1, manual:true }).unwrap();
    authority.apply(Snapshot { revision:2, manual:false }).unwrap();
    for _ in 0..4 { tokio::task::yield_now().await; }
    let sent_at_ack = admissions.lock().unwrap().len();
    drop(socket); // Simulate socket loss after revocation, which used to schedule reconnection.
    eprintln!("dealer stage clock advance");
    tokio::time::pause();
    tokio::time::advance(Duration::from_secs(65)).await;
    for _ in 0..4 { tokio::task::yield_now().await; }
    assert_eq!(admissions.lock().unwrap().len(), sent_at_ack);
    assert_eq!(urls.load(atomic::Ordering::SeqCst), 1);
    eprintln!("dealer stage close");
    tokio::time::timeout(Duration::from_secs(2), dealer.close()).await.expect("dealer cleanup did not quiesce");
    eprintln!("dealer stage terminal");
}
