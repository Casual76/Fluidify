//! Telling Spotify what was listened to.
//!
//! Playback through librespot never appeared in the account's history, and the
//! reason is not a setting: Spotify builds "recently played" from *events* the
//! client posts, and librespot posts none. It publishes its Connect state, so
//! the servers know the device exists and what it is doing, but nothing in that
//! path counts as a listen — verified on the device by starting a track through
//! the Web API and watching the history stay unchanged while the audio played.
//!
//! The wire format is not documented by Spotify. It is taken from
//! librespot-java's `EventService` (Apache-2.0, by devgianlu), reimplemented
//! here rather than copied: events are Mercury POSTs to
//! `hm://event-service/v1/events`, whose body is a list of fields separated by
//! `0x09` and beginning with a numeric event id and a second constant nobody
//! has a name for.
//!
//! Three events make up one listen:
//!
//! * `557` — a new *session*, meaning a context was started.
//! * `558` — a new *playback*, meaning a track began within it.
//! * `12` — the transition off that track, carrying how far it got. This is the
//!   one Spotify counts.
//!
//! The Rust Mercury client has neither POST nor header fields, so both were
//! added to the vendored copy of librespot-core; see native/vendor/README.md.
//! An event that fails is a missing history entry, never a failed playback, so
//! nothing here is allowed to break anything.

use librespot_core::session::Session;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

/// Separates fields in an event body.
const SEP: u8 = 0x09;

const EVENT_URI: &str = "hm://event-service/v1/events";

/// One event, built field by field.
struct Event {
    body: Vec<u8>,
}

impl Event {
    /// `id` and `unknown` identify the kind; see the module note.
    fn new(id: &str, unknown: &str) -> Self {
        let mut event = Event {
            body: id.as_bytes().to_vec(),
        };
        event.field(unknown);
        event
    }

    fn field(&mut self, value: &str) -> &mut Self {
        self.body.push(SEP);
        self.body.extend_from_slice(value.as_bytes());
        self
    }

    fn number(&mut self, value: impl ToString) -> &mut Self {
        self.field(&value.to_string())
    }
}

/// What ended a track, in Spotify's own vocabulary.
#[derive(Clone, Copy)]
pub enum EndReason {
    /// Played to the end.
    TrackDone,
    /// Skipped forwards.
    Forward,
    /// Skipped backwards.
    Backward,
    /// Playback stopped without another track following.
    EndPlay,
}

impl EndReason {
    fn parse(value: &str) -> Option<Self> {
        Some(match value {
            "trackdone" => EndReason::TrackDone,
            "fwdbtn" => EndReason::Forward,
            "backbtn" => EndReason::Backward,
            "endplay" => EndReason::EndPlay,
            _ => return None,
        })
    }

    fn as_str(self) -> &'static str {
        match self {
            EndReason::TrackDone => "trackdone",
            EndReason::Forward => "fwdbtn",
            EndReason::Backward => "backbtn",
            EndReason::EndPlay => "endplay",
        }
    }
}

/// Everything one finished track needs to be reported.
pub struct Listen<'a> {
    /// The track's id in hex — its gid, not the base62 in the URI.
    pub track_hex: &'a str,
    pub playback_id: &'a str,
    pub context_uri: &'a str,
    /// Where playback started within the track, in milliseconds.
    pub start_ms: u32,
    /// Where it stopped. This is what decides whether it counts as a listen.
    pub end_ms: u32,
    pub duration_ms: u32,
    pub reason_start: &'a str,
    pub reason_end: EndReason,
    /// Wall clock at the moment the track started, in milliseconds.
    pub started_at: u128,
}

/// Posts the events that make up the account's listening history.
pub struct EventService {
    session: Session,
    transitions: AtomicU64,
}

impl EventService {
    pub fn new(session: Session) -> Self {
        EventService {
            session,
            transitions: AtomicU64::new(0),
        }
    }

    /// A context was started — a playlist, an album, a single track.
    pub fn new_session(&self, session_id: &str, context_uri: &str, context_size: usize) {
        let mut event = Event::new("557", "3");
        event
            .field(session_id)
            .field(context_uri)
            .field(context_uri)
            .number(now_ms())
            .field("")
            .number(context_size)
            // The context's own URL, which the client uses to page through it.
            .field(&format!("context://{context_uri}"));
        self.post(event);
    }

    /// A track began within that context.
    pub fn new_playback(&self, playback_id: &str, session_id: &str) {
        let mut event = Event::new("558", "1");
        event.field(playback_id).field(session_id).number(now_ms());
        self.post(event);
    }

    /// A track ended. The one event Spotify turns into history.
    pub fn track_transition(&self, listen: &Listen) {
        let device_id = self.session.device_id().to_string();
        let count = self.transitions.fetch_add(1, Ordering::Relaxed);

        // Several of these are timings the official client measures and we do
        // not: how long the audio key took, how much was decrypted, how far the
        // decoder read ahead. Zero is what librespot-java sends when it has no
        // figure either, and Spotify has never rejected an event over them —
        // what it reads is the track, the context and how much was played.
        let mut event = Event::new("12", "38");
        event
            .number(count)
            .field(&device_id)
            .field(listen.playback_id)
            .field("00000000000000000000000000000000")
            // How playback began, and how it ended.
            .field("unknown")
            .field(listen.reason_start)
            .field("unknown")
            .field(listen.reason_end.as_str())
            // Bytes decoded, bytes total.
            .number(0)
            .number(0)
            // Where it stopped, twice.
            .number(listen.end_ms)
            .number(listen.end_ms)
            .number(listen.duration_ms)
            // Decrypt time, crossfade overlap, and two constants.
            .number(0)
            .number(0)
            .field("0")
            .field("0")
            // Whether it started from the beginning, and where from.
            .field(if listen.start_ms == 0 { "0" } else { "1" })
            .number(listen.start_ms)
            .field("0")
            .field("-1")
            .field("context")
            // Audio key timing.
            .number(0)
            .field("0")
            .field("0")
            .field("0")
            .field("0")
            .field("0")
            .number(listen.end_ms)
            .number(listen.end_ms)
            .field("0")
            .number(BITRATE)
            .field(listen.context_uri)
            .field(ENCODING)
            .field(listen.track_hex)
            .field("")
            .field("0")
            .number(listen.started_at)
            .field("0")
            .field("context")
            .field(REFERRER)
            .field(FEATURE_VERSION)
            .field("com.spotify")
            .field("none")
            .field("none")
            // Which device sent the last command. Ours: playback is started
            // here, not by a phone across the room.
            .field(&device_id)
            .field("na")
            .field("none");
        self.post(event);
    }

    /// Posts the event and logs what Spotify made of it.
    ///
    /// The reply matters more than it looks: a well-formed packet the server
    /// silently drops and a rejected one are indistinguishable without it, and
    /// this whole format is reverse-engineered. Upstream's Mercury client has
    /// neither POST nor header fields, so both were added to the vendored copy.
    fn post(&self, event: Event) {
        let body = event.body;
        let fields = vec![
            ("Accept-Language".to_string(), b"en".to_vec()),
            (
                "X-ClientTimeStamp".to_string(),
                now_ms().to_string().into_bytes(),
            ),
        ];

        let request = match self.session.mercury().post(EVENT_URI, body.clone(), fields) {
            Ok(request) => request,
            Err(e) => {
                log::warn!("event not sent: {e}");
                return;
            }
        };

        // Waited for on the engine's runtime rather than on the caller's
        // thread: this is called from the player's event pump, and blocking
        // there would hold up every other event behind a network round trip.
        // The event's kind, never its body. The body carries the track, the
        // context and the time — the user's listening, in a log any app holding
        // READ_LOGS can read.
        let kind = kind_of(&body);
        let Ok(handle) = crate::engine::runtime_handle() else {
            return;
        };
        handle.spawn(async move {
            match request.await {
                Ok(response) if response.status_code == 200 => {
                    log::debug!("event {kind} accepted")
                }
                Ok(response) => log::warn!("event {kind} refused: {}", response.status_code),
                Err(e) => log::warn!("event {kind} failed: {e}"),
            }
        });
    }
}

/// The event's numeric kind, which is all that may be logged; see [EventService::post].
fn kind_of(body: &[u8]) -> String {
    body.split(|byte| *byte == SEP)
        .next()
        .map(|id| String::from_utf8_lossy(id).to_string())
        .unwrap_or_default()
}

/// A listen interrupted by the process going away.
///
/// Written to disk while a track plays and cleared as soon as the transition is
/// sent. A phone kills a music app the moment it needs the memory, and until
/// this existed everything heard since the last pause went with it: the event
/// only ever left at a boundary, and being killed is not a boundary.
///
/// Reported on the next start, with the position it had reached and its
/// original timestamp. Late, but true — the alternative was silence.
pub mod pending {
    use super::{EndReason, EventService, Listen};
    use std::path::{Path, PathBuf};

    const FILE_NAME: &str = "pending-listen";

    pub struct Pending {
        pub track_hex: String,
        pub playback_id: String,
        pub context_uri: String,
        pub start_ms: u32,
        pub position_ms: u32,
        pub duration_ms: u32,
        pub started_at: u128,
    }

    fn path(dir: &str) -> PathBuf {
        Path::new(dir).join(FILE_NAME)
    }

    /// Tab-separated and hand-parsed: six values, written once a second.
    pub fn write(dir: &str, listen: &Pending) {
        let line = format!(
            "{}\t{}\t{}\t{}\t{}\t{}\t{}",
            listen.track_hex,
            listen.playback_id,
            listen.context_uri,
            listen.start_ms,
            listen.position_ms,
            listen.duration_ms,
            listen.started_at,
        );
        // Through a temporary file and a rename: this runs once a second, and a
        // process killed halfway through a plain write left a truncated line
        // that `flush` then threw away as malformed — the very listen this
        // file exists to save.
        let target = path(dir);
        let temporary = target.with_extension("tmp");
        if std::fs::write(&temporary, line).is_ok() {
            let _ = std::fs::rename(&temporary, &target);
        }
    }

    pub fn clear(dir: &str) {
        let _ = std::fs::remove_file(path(dir));
    }

    /// Sends whatever the last run was in the middle of, and forgets it.
    ///
    /// Only with a session that can carry it: the first session the pump sees
    /// is the placeholder that has never connected, and a post on it fails with
    /// "not connected" after the file is already gone. Without one, the listen
    /// goes into the outbox with everything else heard offline; see
    /// [`to_outbox`].
    pub fn flush(dir: &str, events: &EventService) {
        take(dir, |listen| {
            log::info!("reporting a listen the last run did not finish");
            events.track_transition(listen);
        });
    }

    /// Moves the interrupted listen into the outbox, to go with the next session.
    pub fn to_outbox(dir: &str) {
        take(dir, |listen| super::outbox::append(dir, listen));
    }

    fn take(dir: &str, deliver: impl FnOnce(&Listen)) {
        let Ok(raw) = std::fs::read_to_string(path(dir)) else {
            return;
        };
        clear(dir);

        let parts: Vec<&str> = raw.split('\t').collect();
        if parts.len() != 7 {
            return;
        }
        let number = |index: usize| parts[index].parse().unwrap_or(0);
        let position_ms: u32 = number(4);
        let start_ms: u32 = number(3);
        if position_ms <= start_ms {
            return;
        }

        deliver(&Listen {
            track_hex: parts[0],
            playback_id: parts[1],
            context_uri: parts[2],
            start_ms,
            end_ms: position_ms,
            duration_ms: number(5),
            reason_start: "playbtn",
            reason_end: EndReason::EndPlay,
            started_at: parts[6].parse().unwrap_or(0),
        });
    }
}

/// Listens heard with no connection, kept until there is one.
///
/// A watch playing its downloads on a run has no session to report through,
/// and a listen that cannot be sent at the moment it ends used to be a listen
/// that never happened. Here it waits instead: one line per finished track, in
/// the engine's state directory, sent the next time a session comes up.
///
/// Bounded both ways. At most [MAX_ENTRIES] are kept (the oldest go first), and
/// anything older than [MAX_AGE_MS] is dropped when the queue is sent rather
/// than reported: Spotify files a listen by the time it carries, and a month-old
/// one is closer to noise than to history.
pub mod outbox {
    use super::{now_ms, EndReason, EventService, Listen};
    use std::io::Write;
    use std::path::{Path, PathBuf};

    const FILE_NAME: &str = "listen-outbox";
    pub const MAX_ENTRIES: usize = 500;
    pub const MAX_AGE_MS: u128 = 30 * 24 * 60 * 60 * 1000;

    fn path(dir: &str) -> PathBuf {
        Path::new(dir).join(FILE_NAME)
    }

    /// Adds one listen. Tab-separated, like the pending listen beside it.
    pub fn append(dir: &str, listen: &Listen) {
        let line = format!(
            "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\n",
            listen.track_hex,
            listen.playback_id,
            listen.context_uri,
            listen.start_ms,
            listen.end_ms,
            listen.duration_ms,
            listen.reason_start,
            listen.reason_end.as_str(),
            listen.started_at,
        );
        let file = path(dir);
        let existing = std::fs::read_to_string(&file).unwrap_or_default();
        let count = existing.lines().count();
        if count >= MAX_ENTRIES {
            // Keep the newest: drop from the front, then add.
            let kept: Vec<&str> = existing.lines().skip(count + 1 - MAX_ENTRIES).collect();
            let _ = std::fs::write(&file, kept.join("\n") + "\n" + &line);
            return;
        }
        if let Ok(mut out) = std::fs::OpenOptions::new().create(true).append(true).open(&file) {
            let _ = out.write_all(line.as_bytes());
        }
        log::info!("listen kept for later ({} waiting)", count + 1);
    }

    /// Sends everything waiting through [events], oldest first, and empties the queue.
    pub fn flush(dir: &str, events: &EventService) {
        let file = path(dir);
        let Ok(raw) = std::fs::read_to_string(&file) else {
            return;
        };
        let _ = std::fs::remove_file(&file);
        let now = now_ms();
        let mut sent = 0;
        for line in raw.lines() {
            let parts: Vec<&str> = line.split('\t').collect();
            if parts.len() != 9 {
                continue;
            }
            let number = |index: usize| parts[index].parse::<u32>().unwrap_or(0);
            let started_at: u128 = parts[8].parse().unwrap_or(0);
            if started_at == 0 || now.saturating_sub(started_at) > MAX_AGE_MS {
                continue;
            }
            let Some(reason_end) = EndReason::parse(parts[7]) else {
                continue;
            };
            events.track_transition(&Listen {
                track_hex: parts[0],
                playback_id: parts[1],
                context_uri: parts[2],
                start_ms: number(3),
                end_ms: number(4),
                duration_ms: number(5),
                reason_start: parts[6],
                reason_end,
                started_at,
            });
            sent += 1;
        }
        if sent > 0 {
            log::info!("reported {sent} listens heard offline");
        }
    }
}

pub fn now_ms() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0)
}

/// A 32-character hex id, which is the shape Spotify uses for both of these.
pub fn random_id() -> String {
    let mut id = String::with_capacity(32);
    for _ in 0..4 {
        // Exactly eight characters per part: Spotify's ids are 32 long, and a
        // u64 printed as hex ran to sixteen when the value was large enough,
        // which made every id a different length.
        let part = (SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.subsec_nanos() as u64)
            .unwrap_or(0)
            ^ rand_seed()) as u32;
        id.push_str(&format!("{part:08x}"));
    }
    id
}

fn rand_seed() -> u64 {
    use std::collections::hash_map::RandomState;
    use std::hash::{BuildHasher, Hasher};
    RandomState::new().build_hasher().finish()
}

/// 160 kbps, the ordinary quality this client asks for.
const BITRATE: u32 = 160_000;
const ENCODING: &str = "vorbis";

/// Where the play came from, in the client's own vocabulary.
const REFERRER: &str = "unknown";
const FEATURE_VERSION: &str = "harmony:4.21.0";

#[cfg(test)]
mod tests {
    use super::{outbox, EndReason, Listen};

    fn listen(started_at: u128) -> Listen<'static> {
        Listen {
            track_hex: "00ff",
            playback_id: "p",
            context_uri: "spotify:playlist:x",
            start_ms: 0,
            end_ms: 120_000,
            duration_ms: 200_000,
            reason_start: "playbtn",
            reason_end: EndReason::TrackDone,
            started_at,
        }
    }

    fn scratch(name: &str) -> String {
        let dir = std::env::temp_dir().join(format!("fluidify-outbox-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir.to_string_lossy().to_string()
    }

    fn lines(dir: &str) -> Vec<String> {
        std::fs::read_to_string(std::path::Path::new(dir).join("listen-outbox"))
            .unwrap_or_default()
            .lines()
            .map(str::to_string)
            .collect()
    }

    #[test]
    fn appends_one_line_per_listen_with_every_field() {
        let dir = scratch("append");
        outbox::append(&dir, &listen(1));
        outbox::append(&dir, &listen(2));
        let lines = lines(&dir);
        assert_eq!(lines.len(), 2);
        assert_eq!(lines[1].split('\t').count(), 9);
        assert!(lines[1].ends_with("\ttrackdone\t2"));
    }

    #[test]
    fn keeps_only_the_newest_when_full() {
        let dir = scratch("bound");
        for i in 0..(outbox::MAX_ENTRIES as u128 + 7) {
            outbox::append(&dir, &listen(i + 1));
        }
        let lines = lines(&dir);
        assert_eq!(lines.len(), outbox::MAX_ENTRIES);
        assert!(lines[0].ends_with("\t8"), "oldest kept is {}", lines[0]);
        assert!(lines.last().unwrap().ends_with(&format!("\t{}", outbox::MAX_ENTRIES + 7)));
    }

    #[test]
    fn end_reasons_round_trip() {
        for reason in [EndReason::TrackDone, EndReason::Forward, EndReason::Backward, EndReason::EndPlay] {
            assert_eq!(EndReason::parse(reason.as_str()).map(|r| r.as_str()), Some(reason.as_str()));
        }
    }
}

