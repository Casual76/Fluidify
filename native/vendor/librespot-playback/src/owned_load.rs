// SPDX-License-Identifier: MIT
// Fluidify local extension to librespot: causal, command-owned restore correlation.
// No correlation is inferred from ordering, URI, position, or request-id arithmetic.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OwnedLoadBinding {
    pub nonce: String,
    pub play_request_id: u64,
}
impl OwnedLoadBinding {
    pub fn for_command(nonce: Option<String>, play_request_id: u64) -> Option<Self> {
        nonce.map(|nonce| Self { nonce, play_request_id })
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn untagged_remote_load_never_gets_ownership() {
        assert_eq!(OwnedLoadBinding::for_command(None, 42), None);
    }
    #[test] fn interleaved_requests_bind_only_the_tagged_command() {
        let before = OwnedLoadBinding::for_command(None, 41);
        let owned = OwnedLoadBinding::for_command(Some("owned-nonce".into()), 42).unwrap();
        let after = OwnedLoadBinding::for_command(None, 43);
        assert_eq!(before, None); assert_eq!(after, None);
        assert_eq!(owned.nonce, "owned-nonce"); assert_eq!(owned.play_request_id, 42);
    }
    #[test] fn concurrent_owned_commands_keep_their_own_nonce() {
        let a = OwnedLoadBinding::for_command(Some("a".into()), 90).unwrap();
        let b = OwnedLoadBinding::for_command(Some("b".into()), 91).unwrap();
        assert_eq!((a.nonce.as_str(), a.play_request_id), ("a", 90));
        assert_eq!((b.nonce.as_str(), b.play_request_id), ("b", 91));
    }
}
