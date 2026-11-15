//! Small synchronous policies used by the real native recovery and catalogue paths.
use std::sync::{Mutex, MutexGuard};

/// One lock covers both revocation and the actual fallback effect, closing check/use races.
/// Callers acquire this before the engine lock and never retain it across an await.
pub struct TransportIntent(Mutex<u64>);
impl TransportIntent {
    pub const fn new() -> Self { Self(Mutex::new(0)) }
    pub fn advance(&self) -> MutexGuard<'_, u64> {
        let mut guard = self.0.lock().unwrap_or_else(|error| error.into_inner());
        *guard = guard.wrapping_add(1);
        guard
    }
    pub fn admit(&self, expected: u64) -> Option<MutexGuard<'_, u64>> {
        let guard = self.0.lock().unwrap_or_else(|error| error.into_inner());
        if *guard == expected { Some(guard) } else { None }
    }
}

pub fn next_page(from: usize, raw_count: usize, truncated: bool) -> Result<Option<usize>, String> {
    if !truncated { return Ok(None); }
    if raw_count == 0 { return Err("rootlist incomplete: empty truncated page".into()); }
    from.checked_add(raw_count).map(Some)
        .ok_or_else(|| "rootlist offset overflow".into())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn later_pause_stop_load_play_or_skip_revokes_delayed_recovery() {
        let intent = TransportIntent::new();
        let first = *intent.advance();
        assert!(intent.admit(first).is_some());
        for _ in 0..5 {
            drop(intent.advance());
            assert!(intent.admit(first).is_none());
        }
        let latest = *intent.advance();
        assert!(intent.admit(latest).is_some());
    }
    #[test]
    fn fallback_and_pause_are_serialized_through_the_effect() {
        use std::sync::{Arc, mpsc};
        let intent = Arc::new(TransportIntent::new());
        let ticket = *intent.advance();
        let guard = intent.admit(ticket).unwrap();
        let (sent, received) = mpsc::channel();
        let other = intent.clone();
        let worker = std::thread::spawn(move || {
            let _pause = other.advance();
            sent.send(()).unwrap();
        });
        assert!(received.try_recv().is_err());
        drop(guard);
        received.recv_timeout(std::time::Duration::from_secs(2)).unwrap();
        worker.join().unwrap();
        assert!(intent.admit(ticket).is_none());
    }
    #[test]
    fn pagination_passes_old_cap_and_counts_raw_folder_entries() {
        let mut from = 0;
        for _ in 0..11 { from = next_page(from, 250, true).unwrap().unwrap(); }
        assert_eq!(from, 2750);
        assert_eq!(next_page(from, 3, true).unwrap(), Some(2753));
        assert_eq!(next_page(2753, 0, false).unwrap(), None);
        assert!(next_page(2753, 0, true).is_err());
        assert!(next_page(usize::MAX, 1, true).is_err());
    }
}
