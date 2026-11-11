//! A mirror of a host policy, never a second preference. Leases never renew themselves.
use std::{future::Future, sync::{Arc, RwLock}};
use tokio::sync::watch;
use crate::Error;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Snapshot { pub revision: u64, pub manual: bool }

#[derive(Debug, thiserror::Error)]
#[error("Spotify network lease revoked")]
pub struct NetworkDenied;

pub type DispatchObserver = Arc<dyn Fn(&'static str) -> Result<(), Error> + Send + Sync>;

struct Inner { observer: std::sync::Mutex<Option<DispatchObserver>>, closed: std::sync::atomic::AtomicBool, state: RwLock<Snapshot>, changed: watch::Sender<Snapshot> }
#[derive(Clone)]
pub struct NetworkAuthority(Arc<Inner>);
#[derive(Clone)]
pub struct NetworkLease { authority: NetworkAuthority, revision: u64, enabled: bool, owners: Vec<Arc<watch::Sender<bool>>> }

impl NetworkAuthority {
    pub fn new(snapshot: Snapshot) -> Self {
        Self(Arc::new(Inner { observer: std::sync::Mutex::new(None), closed: std::sync::atomic::AtomicBool::new(false), state: RwLock::new(snapshot), changed: watch::channel(snapshot).0 }))
    }
    /// Optional recording/denying transport hook; never changes host policy.
    pub fn set_dispatch_observer(&self, observer: DispatchObserver) {
        *self.0.observer.lock().expect("network observer poisoned") = Some(observer);
    }
    /// Owner revocation is a lifetime fence, not a second manual-mode decision.
    pub fn close(&self) {
        let state = self.0.state.write().expect("network policy poisoned");
        self.0.closed.store(true, std::sync::atomic::Ordering::SeqCst);
        self.0.changed.send_replace(*state);
    }
    pub fn is_closed(&self) -> bool { self.0.closed.load(std::sync::atomic::Ordering::SeqCst) }
    pub fn snapshot(&self) -> Snapshot { *self.0.state.read().expect("network policy poisoned") }
    pub fn apply(&self, snapshot: Snapshot) -> Result<(), Error> {
        let mut state = self.0.state.write().expect("network policy poisoned");
        if snapshot.revision < state.revision || (snapshot.revision == state.revision && snapshot != *state) {
            return Err(Error::failed_precondition("stale network snapshot"));
        }
        *state = snapshot;
        self.0.changed.send_replace(snapshot);
        Ok(())
    }
    pub fn lease(&self) -> Result<NetworkLease, Error> {
        let state = self.snapshot();
        let lease = NetworkLease { authority: self.clone(), revision: state.revision, enabled: true, owners: Vec::new() };
        lease.check()?;
        Ok(lease)
    }
}
impl Default for NetworkLease {
    // Session::new stays online for legacy callers, including Wear's independent host.
    fn default() -> Self { NetworkAuthority::new(Snapshot { revision: 0, manual: false }).lease().unwrap() }
}
impl NetworkLease {
    pub fn denied() -> Self { Self { enabled: false, ..Self::default() } }
    /// A Session lifetime is subordinate to the host lease. Shutdown retires only this child.
    pub fn child(&self) -> Self {
        let mut child = self.clone();
        child.owners.push(Arc::new(watch::channel(false).0));
        child
    }
    pub fn retire(&self) { if let Some(owner) = self.owners.last() { owner.send_replace(true); } }
    fn valid(&self, state: &Snapshot) -> bool { self.enabled && self.owners.iter().all(|owner| !*owner.borrow()) && !self.authority.0.closed.load(std::sync::atomic::Ordering::SeqCst) && !state.manual && state.revision == self.revision }
    pub fn same_lease(&self, other: &Self) -> bool { self.revision == other.revision && Arc::ptr_eq(&self.authority.0, &other.authority.0) }
    pub fn revision(&self) -> u64 { self.revision }
    pub fn check(&self) -> Result<(), Error> { self.admit(|| ()) }
    /// Serialize dispatch with apply. Never hold this guard across an await.
    pub fn admit<T>(&self, dispatch: impl FnOnce() -> T) -> Result<T, Error> {
        let state = self.authority.0.state.read().expect("network policy poisoned");
        if !self.valid(&state) { return Err(Error::cancelled(NetworkDenied)); }
        Ok(dispatch())
    }
    /// The same policy admission, with instrumentation at actual transport creation/send sites.
    pub fn transport<T>(&self, kind: &'static str, dispatch: impl FnOnce() -> T) -> Result<T, Error> {
        self.admit(|| {
            let observer = self.authority.0.observer.lock().expect("network observer poisoned").clone();
            if let Some(observer) = observer { observer(kind)?; }
            Ok(dispatch())
        })?
    }

    /// Account-local receipt persistence may survive mode revocation, but never owner retirement.
    /// This MUST NOT dispatch transport work. The owner close waits for these short commits.
    pub fn commit_local<T>(&self, commit: impl FnOnce() -> T) -> Result<T, Error> {
        let _state = self.authority.0.state.read().expect("network policy poisoned");
        if self.authority.0.closed.load(std::sync::atomic::Ordering::SeqCst) {
            return Err(Error::cancelled(NetworkDenied));
        }
        Ok(commit())
    }

    pub async fn revoked(&self) {
        let mut rx = self.authority.0.changed.subscribe();
        loop {
            if !self.valid(&rx.borrow_and_update()) { return; }
            let owner_changes = self.owners.iter().map(|owner| {
                let mut owner = owner.subscribe();
                Box::pin(async move {
                    loop {
                        if *owner.borrow_and_update() { return; }
                        if owner.changed().await.is_err() { return; }
                    }
                }) as std::pin::Pin<Box<dyn Future<Output = ()> + Send>>
            });
            if self.owners.is_empty() {
                if rx.changed().await.is_err() { return; }
            } else {
                let owners = futures_util::future::select_all(owner_changes);
                tokio::select! { biased; _ = owners => return, result = rx.changed() => { if result.is_err() { return; } } }
            }
        }
    }
    pub async fn run<T>(&self, work: impl Future<Output = Result<T, Error>>) -> Result<T, Error> {
        self.check()?;
        tokio::select! {
            biased;
            _ = self.revoked() => Err(Error::cancelled(NetworkDenied)),
            result = work => { self.check()?; result }
        }
    }
}
