use crate::{authentication::Credentials, connection, http_client::HttpClient,
    network::{NetworkAuthority, Snapshot}, Error};
use std::{sync::{Arc, Mutex}, time::Duration};
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream}};

fn authority() -> NetworkAuthority { NetworkAuthority::new(Snapshot { revision:0, manual:false }) }
fn revoke(authority: &NetworkAuthority) {
    authority.apply(Snapshot { revision:1, manual:true }).unwrap();
    authority.apply(Snapshot { revision:2, manual:false }).unwrap();
}

pub async fn manual_offline_http_429_wait_is_cancelled_and_old_request_never_retries() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = listener.local_addr().unwrap();
    let authority = authority(); let waiting = Arc::new(tokio::sync::Notify::new()); let seen = waiting.clone();
    let admissions = Arc::new(Mutex::new(Vec::new())); let recorded = admissions.clone();
    authority.set_dispatch_observer(Arc::new(move |kind| {
        recorded.lock().unwrap().push(kind); if kind == "http.retry.wait" { seen.notify_one(); } Ok(())
    }));
    let client = HttpClient::new_with_network(None, authority.lease().unwrap());
    let task = tokio::spawn(async move { client.request(http::Request::builder()
        .uri(format!("http://{address}/synthetic")).body(bytes::Bytes::new()).unwrap()).await });
    let (mut peer, _) = listener.accept().await.unwrap(); let mut buffer = [0;2048];
    assert!(peer.read(&mut buffer).await.unwrap() > 0);
    peer.write_all(b"HTTP/1.1 429 Too Many Requests\r\nRetry-After: 5\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").await.unwrap();
    tokio::time::timeout(Duration::from_secs(2), waiting.notified()).await.unwrap();
    revoke(&authority);
    assert!(tokio::time::timeout(Duration::from_secs(1), task).await.unwrap().unwrap().is_err());
    assert_eq!(admissions.lock().unwrap().iter().filter(|&&kind| kind == "http").count(), 1);
    assert!(tokio::time::timeout(Duration::from_millis(40), listener.accept()).await.is_err());
}

pub async fn manual_offline_ap_handshake_revocation_prevents_retry_even_after_fast_off() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap(); let port = listener.local_addr().unwrap().port();
    let authority = authority(); let lease = authority.lease().unwrap();
    let task = tokio::spawn(async move { connection::connect_with_network("127.0.0.1", port, None, 3, &lease).await });
    let (mut peer, _) = listener.accept().await.unwrap(); let mut buffer = [0;4096];
    // Real DH client hello, sent to a synthetic loopback peer which deliberately never answers.
    assert!(peer.read(&mut buffer).await.unwrap() > 0);
    revoke(&authority);
    assert!(tokio::time::timeout(Duration::from_secs(1), task).await.unwrap().unwrap().is_err());
    assert!(tokio::time::timeout(Duration::from_millis(40), listener.accept()).await.is_err());
}

pub async fn manual_offline_ap_auth_revocation_drops_real_encrypted_login_without_followup() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap(); let address = listener.local_addr().unwrap();
    let stream = TcpStream::connect(address).await.unwrap(); let (mut peer, _) = listener.accept().await.unwrap();
    let authority = authority(); let lease = authority.lease().unwrap();
    let task = tokio::spawn(async move {
        let mut transport = connection::Transport::new(stream, connection::ApCodec::new(&[7;32], &[9;32]));
        connection::authenticate_with_network(&mut transport, Credentials::with_access_token("synthetic-token"),
            "synthetic-device", &lease).await
    });
    let mut buffer = [0;4096]; assert!(peer.read(&mut buffer).await.unwrap() > 0);
    revoke(&authority);
    assert!(tokio::time::timeout(Duration::from_secs(1), task).await.unwrap().unwrap().is_err());
}

pub fn manual_offline_received_local_credential_can_commit_until_its_account_owner_retires() {
    let authority = authority(); let lease = authority.lease().unwrap();
    revoke(&authority);
    let received = Mutex::new(None);
    lease.commit_local(|| *received.lock().unwrap() = Some("synthetic-rotated-credential")).unwrap();
    assert!(lease.check().is_err()); assert_eq!(*received.lock().unwrap(), Some("synthetic-rotated-credential"));
    authority.close();
    assert!(lease.commit_local(|| *received.lock().unwrap() = Some("wrong-owner")).is_err());
    assert_eq!(*received.lock().unwrap(), Some("synthetic-rotated-credential"));
    // Positive independent owner remains enabled; closing A does not decide B's network policy.
    assert!(NetworkAuthority::new(Snapshot { revision:2, manual:false }).lease().is_ok());
}

pub async fn parsed_apwelcome_rotation_survives_concurrent_revocation() {
    use futures_util::{SinkExt,StreamExt}; use protobuf::Message;
    let listener=TcpListener::bind("127.0.0.1:0").await.unwrap(); let address=listener.local_addr().unwrap();
    let stream=TcpStream::connect(address).await.unwrap(); let (peer,_)=listener.accept().await.unwrap();
    let authority=authority(); let lease=authority.lease().unwrap(); let revoked=authority.clone();
    let received=Arc::new(Mutex::new(None)); let saved=received.clone();
    let revoker=Arc::new(Mutex::new(None)); let worker=revoker.clone();
    let client=tokio::spawn(async move {
        let mut transport=connection::Transport::new(stream,connection::ApCodec::new(&[7;32],&[9;32]));
        connection::authenticate_with_network_and_receipt(&mut transport,Credentials::with_access_token("synthetic-token"),
            "synthetic-device",&lease,move |credential| {
                *saved.lock().unwrap()=Some(credential.clone());
                *worker.lock().unwrap()=Some(std::thread::spawn(move || revoke(&revoked))); Ok(())
            }).await
    });
    let mut peer=connection::Transport::new(peer,connection::ApCodec::new(&[9;32],&[7;32]));
    let login=peer.next().await.unwrap().unwrap(); assert_eq!(login.0,crate::packet::PacketType::Login as u8);
    let mut welcome=crate::protocol::authentication::APWelcome::new();
    welcome.set_canonical_username("synthetic-account".into());
    welcome.set_account_type_logged_in(crate::protocol::authentication::AccountType::Spotify);
    welcome.set_credentials_type_logged_in(crate::protocol::authentication::AccountType::Spotify);
    welcome.set_reusable_auth_credentials_type(crate::protocol::authentication::AuthenticationType::AUTHENTICATION_STORED_SPOTIFY_CREDENTIALS);
    welcome.set_reusable_auth_credentials(b"synthetic-rotation".to_vec());
    peer.send((crate::packet::PacketType::APWelcome as u8,welcome.write_to_bytes().unwrap())).await.unwrap();
    // Both outcomes are permitted at the concurrent boundary: pre-ack success or post-ack rejection.
    let _outcome=tokio::time::timeout(Duration::from_secs(1),client).await.unwrap().unwrap();
    revoker.lock().unwrap().take().unwrap().join().unwrap();
    assert!(authority.lease().unwrap().revision()==2);
    let receipt=received.lock().unwrap(); let receipt=receipt.as_ref().unwrap();
    assert_eq!(receipt.username.as_deref(),Some("synthetic-account")); assert_eq!(receipt.auth_data,b"synthetic-rotation");
}
