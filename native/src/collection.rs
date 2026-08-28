//! Liked Songs, over the access point.
//!
//! The same story as [`crate::playlists`], on a different endpoint: the Web API
//! refuses this write to the listener's own registered application with a bare
//! 403 "Forbidden", and refuses it to the shared client id with a 429 that does
//! not clear. `PUT /v1/me/tracks` is not available to this app by any credential
//! it can obtain, so the heart in the player went nowhere.
//!
//! What the real client does is `POST /collection/v2/write` on the access point,
//! carrying a small protobuf. Adding and removing are the same call: the item
//! has an `is_removed` flag, and the "collection" set is a set, so writing a
//! track that is already in it is a no-op. That last property is why this file
//! may retry where [`crate::playlists`] must not — there is no way for a
//! duplicate request here to produce a duplicate anything.
//!
//! ### Why the message is written out by hand
//!
//! `collection2v2.proto` ships inside librespot-protocol but is **not** in that
//! crate's build script, so no Rust type for it exists and no dependent crate
//! can reach one. The alternatives were to fork the crate or to add a build
//! script and a codegen dependency here, both to obtain four fields.
//!
//! Four fields of proto3 is twenty lines of encoder, and the encoder is the part
//! that cannot silently drift: the schema below is copied from the .proto that
//! is already on disk, and a wrong tag would fail loudly at the server rather
//! than quietly writing the wrong thing.
//!
//! ```text
//! WriteRequest {
//!     1 username         string
//!     2 set              string   // "collection"
//!     3 items            repeated CollectionItem
//!     4 client_update_id string
//! }
//! CollectionItem {
//!     1 uri        string   // spotify:track:<base62>
//!     2 added_at   int32    // unix SECONDS, not millis
//!     3 is_removed bool
//! }
//! ```

use crate::engine::{with_session, EngineResult};
use http::header::{ACCEPT, CONTENT_TYPE};
use http::{HeaderMap, HeaderValue, Method};
use std::time::{SystemTime, UNIX_EPOCH};

/// The endpoint speaks its own vendor content type and refuses `application/
/// x-protobuf`, which is what the generic protobuf helper would send.
const COLLECTION_PROTO: &str = "application/vnd.collection-v2.spotify.proto";

/// Which of the account's sets. The liked tracks are simply "collection".
const SET: &str = "collection";

/// Puts a track in Liked Songs.
pub fn like(track_uri: &str) -> EngineResult<()> {
    write(track_uri, false)
}

/// Takes it out again.
pub fn unlike(track_uri: &str) -> EngineResult<()> {
    write(track_uri, true)
}

fn write(track_uri: &str, removed: bool) -> EngineResult<()> {
    if !track_uri.starts_with("spotify:track:") {
        return Err(format!("not a track: {track_uri}"));
    }

    let session = with_session(|s| s.clone())?;
    let username = session.username().to_string();
    let track_uri = track_uri.to_string();

    crate::engine::runtime_handle()?.block_on(async move {
        let mut item = Vec::new();
        put_string(&mut item, 1, &track_uri);
        // Zero when removing: the server is being told the row is gone, and a
        // timestamp for a thing that no longer exists would be noise.
        put_varint(&mut item, 2, if removed { 0 } else { now_seconds() });
        put_varint(&mut item, 3, u64::from(removed));

        let mut body = Vec::new();
        put_string(&mut body, 1, &username);
        put_string(&mut body, 2, SET);
        put_bytes(&mut body, 3, &item);
        // Free-form, and only used to recognise our own change when it comes
        // back over the dealer. Derived from the clock rather than a random
        // source because this crate has no RNG of its own and the value has no
        // security role whatever.
        put_string(&mut body, 4, &format!("{:016x}", now_millis()));

        let mut headers = HeaderMap::new();
        headers.insert(CONTENT_TYPE, HeaderValue::from_static(COLLECTION_PROTO));
        headers.insert(ACCEPT, HeaderValue::from_static(COLLECTION_PROTO));

        session
            .spclient()
            .request(&Method::POST, "/collection/v2/write", Some(headers), Some(&body))
            .await
            .map_err(|e| format!("collection write refused: {e}"))?;

        // Nothing to read. The answer carries no verdict — every client that
        // speaks this endpoint takes the status alone — and the real echo comes
        // later over the dealer, carrying the id sent above.
        Ok(())
    })
}

fn now_seconds() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn now_millis() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0)
}

// ----------------------------------------------------------- protobuf, by hand

/// Tag byte: the field number and the wire type it is encoded in.
fn put_tag(out: &mut Vec<u8>, field: u32, wire: u8) {
    put_raw_varint(out, u64::from(field) << 3 | u64::from(wire));
}

fn put_raw_varint(out: &mut Vec<u8>, mut value: u64) {
    loop {
        let byte = (value & 0x7f) as u8;
        value >>= 7;
        if value == 0 {
            out.push(byte);
            return;
        }
        out.push(byte | 0x80);
    }
}

fn put_varint(out: &mut Vec<u8>, field: u32, value: u64) {
    put_tag(out, field, 0);
    put_raw_varint(out, value);
}

fn put_bytes(out: &mut Vec<u8>, field: u32, value: &[u8]) {
    put_tag(out, field, 2);
    put_raw_varint(out, value.len() as u64);
    out.extend_from_slice(value);
}

fn put_string(out: &mut Vec<u8>, field: u32, value: &str) {
    put_bytes(out, field, value.as_bytes());
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The encoder against bytes worked out by hand, because a tag off by one
    /// would still be valid protobuf and would still be accepted by a lenient
    /// server — as a different field.
    #[test]
    fn encodes_a_known_item() {
        let mut out = Vec::new();
        put_string(&mut out, 1, "ab");
        put_varint(&mut out, 2, 300);
        put_varint(&mut out, 3, 1);
        assert_eq!(out, vec![0x0a, 0x02, b'a', b'b', 0x10, 0xac, 0x02, 0x18, 0x01]);
    }

    #[test]
    fn nests_a_submessage() {
        let mut out = Vec::new();
        put_bytes(&mut out, 3, &[0x08, 0x01]);
        assert_eq!(out, vec![0x1a, 0x02, 0x08, 0x01]);
    }
}
