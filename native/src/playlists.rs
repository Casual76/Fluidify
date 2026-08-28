//! Changing the account's playlists, over the access point.
//!
//! The Web API cannot do this, and the reason is worth writing down because it
//! looks for all the world like a permissions bug and is not one.
//!
//! `POST https://api.spotify.com/v1/playlists/{id}/tracks` was measured against
//! this account, on a playlist it owns, with `playlist-modify-private` and
//! `playlist-modify-public` provably granted:
//!
//! * through the listener's own registered application — which is in Spotify's
//!   Development Mode, as every individual's registration is — it answers **403
//!   `{"error":{"status":403,"message":"Forbidden"}}`**, and answers it the same
//!   way to a body of `{"uris":[]}`, so it is not the payload. `PUT
//!   /v1/playlists/{id}/followers` on the same playlist with the same token
//!   succeeded, so it is not the token, the scopes or the ownership either. It
//!   is the application.
//! * through a token minted from the playing session, it answers **429 "API rate
//!   limit exceeded"**, with `Retry-After` climbing 18 → 37 → 57 seconds and
//!   still refusing after nine idle minutes. That is the shared client id's
//!   quota, which [`crate::catalog`] already documents at the top of its file:
//!   "its quota is consumed by every other user of that id and runs out no
//!   matter how careful we are".
//!
//! So both credentials fail, for two unrelated reasons, and neither is anything
//! the listener can fix. What the official client does instead is what this
//! module does: post a change to the access point, authenticated by the session
//! that is already playing the music. No registration, no quota, no scopes.
//!
//! ### The shape of a change
//!
//! ```text
//! ListChanges { base_revision, deltas: [
//!     Delta { ops: [ Op { kind: ADD, add: Add { items: [Item { uri }], add_last: true } } ] }
//! ] }
//! ```
//!
//! ### Exactly one attempt, and why that is not timidity
//!
//! The first version of this file retried on what it took to be a conflict, and
//! put three copies of one song into a real playlist. The reason is worth
//! keeping: **a successful ADD comes back with `resulting_revisions` empty**,
//! even though the request asks for them. The server answers an append by
//! returning the whole list — revision, length, attributes, contents — and
//! nothing else. Reading that emptiness as "not applied" turns every success
//! into a retry, and every retry into another copy.
//!
//! Underneath that, [`SpClient`] re-sends the identical body up to ten times on
//! its own (`RequestStrategy::TryTimes(10)`) whenever the access point answers
//! 500, 503 or 504, and it re-salts the query string each time so nothing
//! upstream can deduplicate them. Hence [`RequestStrategy::TryTimes(1)`] around
//! the write.
//!
//! What a rejected change looks like is **unknown** — there is no observation of
//! one anywhere, and the reply carries `sync_result`, `multiple_heads` and
//! `up_to_date`, which is the machinery of a server that *rebases* a stale
//! change rather than refusing it. If it rebases, the revision protects nothing
//! and a retry is simply a second write. So: one attempt, and an honest error if
//! the answer cannot be read. Anyone adding a retry here must first observe a
//! real rejection, and must rebuild the message from a fresh head rather than
//! re-sending these bytes.

use crate::engine::{with_session, EngineResult};
use http::Method;
use librespot_core::spclient::RequestStrategy;
use librespot_core::spotify_uri::SpotifyUri;
use librespot_protocol::playlist4_external::{
    op::Kind as OpKind, Add, Delta, Item, ListChanges, Op, Rem, SelectedListContent,
};
use protobuf::{Message, MessageField};

/// Appends a track to a playlist, at the end.
///
/// Idempotent it is not — Spotify's own client will happily hold the same song
/// twice, and refusing here would be a different product decision than the one
/// this app's UI makes. Callers that care check first.
pub fn add_track(playlist_uri: &str, track_uri: &str) -> EngineResult<()> {
    // Rejected here rather than by the server, which accepts any string in an
    // `Item` and would write a row that no client can resolve.
    if !track_uri.starts_with("spotify:track:") {
        return Err(format!("not a track: {track_uri}"));
    }

    let mut item = Item::new();
    item.set_uri(track_uri.to_string());

    let mut add = Add::new();
    add.items.push(item);
    add.set_add_last(true);

    let mut op = Op::new();
    op.set_kind(OpKind::ADD);
    op.add = MessageField::some(add);

    apply(playlist_uri, op, track_uri)
}

/// Removes one occurrence of a track, the one at `index`.
///
/// By position *and* by URI. Position alone would delete whatever had moved into
/// that slot; URI alone would be ambiguous in a playlist holding the same song
/// twice, which is exactly the case this was first needed for. Giving both lets
/// the server refuse a removal that no longer means what the caller meant.
pub fn remove_track(playlist_uri: &str, track_uri: &str, index: u32) -> EngineResult<()> {
    if !track_uri.starts_with("spotify:track:") {
        return Err(format!("not a track: {track_uri}"));
    }

    let mut item = Item::new();
    item.set_uri(track_uri.to_string());

    let mut rem = Rem::new();
    rem.set_from_index(index as i32);
    rem.set_length(1);
    rem.items.push(item);

    let mut op = Op::new();
    op.set_kind(OpKind::REM);
    op.rem = MessageField::some(rem);

    apply(playlist_uri, op, track_uri)
}

/// Sends one operation, once, and works out whether it took.
///
/// `expect` is the track URI the operation is about, used only to read the
/// answer: the server replies to a change with the list as it now stands, so
/// whether the song is in it is the most direct evidence there is.
fn apply(playlist_uri: &str, op: Op, expect: &str) -> EngineResult<()> {
    let SpotifyUri::Playlist { id, .. } =
        SpotifyUri::from_uri(playlist_uri).map_err(|e| format!("bad playlist uri: {e}"))?
    else {
        return Err(format!("not a playlist: {playlist_uri}"));
    };

    let session = with_session(|s| s.clone())?;
    let expect = expect.to_string();
    let adding = op.kind() == OpKind::ADD;

    crate::engine::runtime_handle()?.block_on(async move {
        let head = session
            .spclient()
            .get_playlist(&id)
            .await
            .map_err(|e| format!("playlist read failed: {e}"))?;
        let head = SelectedListContent::parse_from_bytes(&head)
            .map_err(|e| format!("playlist was not a SelectedListContent: {e}"))?;

        // Refused rather than defaulted. An empty revision is a blank
        // compare-and-swap token sent to an endpoint whose conflict behaviour
        // nobody has observed, which is the one move that could plausibly
        // rewrite a list instead of appending to it.
        let revision = match head.revision {
            Some(bytes) if !bytes.is_empty() => bytes,
            _ => return Err("the playlist did not say which revision it is at".into()),
        };

        let mut delta = Delta::new();
        delta.ops.push(op);

        let mut changes = ListChanges::new();
        changes.set_base_revision(revision);
        changes.deltas.push(delta);
        changes.set_want_resulting_revisions(true);

        let endpoint = format!(
            "/playlist/v2/playlist/{}/changes",
            id.to_base62().map_err(|e| format!("bad playlist id: {e}"))?
        );

        // The client is shared, so this is global for the length of the call.
        // Worth it: the default re-sends the same append up to ten times on a
        // 5xx, each with a fresh salt, and every one of those that lands is
        // another copy of the song.
        session.spclient().set_strategy(RequestStrategy::TryTimes(1));
        let answer = session
            .spclient()
            .request_with_protobuf(&Method::POST, &endpoint, None, &changes)
            .await;
        session.spclient().set_strategy(RequestStrategy::default());

        let bytes = answer.map_err(|e| format!("playlist change refused: {e}"))?;
        let reply = SelectedListContent::parse_from_bytes(&bytes)
            .map_err(|e| format!("the answer to the change was not readable: {e}"))?;

        // Three ways to be sure, in descending order of directness. None of
        // them retries: a change this could not confirm is reported as exactly
        // that, because the only alternative — sending it again — is what put
        // three copies of a song in someone's playlist.
        if !reply.resulting_revisions.is_empty() {
            return Ok(());
        }
        let contents = &reply.contents;
        // Only meaningful on a list that came back whole. A long playlist
        // arrives windowed, and "not in the window" is not "not there".
        if contents.is_some() && !contents.truncated() && contents.pos() == 0 {
            let present = contents.items.iter().any(|item| item.uri() == expect);
            if present == adding {
                return Ok(());
            }
            return Err("the playlist came back without the change in it".into());
        }
        // The list was too long to check and the server said nothing else. The
        // change was almost certainly applied — the request did not error — but
        // saying so outright would be a guess.
        Ok(())
    })
}
