//! Session + playback engine.
//!
//! One process-wide instance, guarded by a mutex. All librespot work happens on
//! a dedicated multi-thread tokio runtime that outlives individual JNI calls;
//! JNI methods only enqueue commands and return immediately, so the Android main
//! thread is never blocked on network I/O.

use jni::objects::{GlobalRef, JObject, JValue};
use jni::JavaVM;
use librespot_connect::{
    AdoptRequest, ConnectConfig, LoadRequest, LoadRequestOptions, PlayingTrack, Spirc,
};
use librespot_core::{
    authentication::Credentials, cache::Cache, config::DeviceType, config::SessionConfig,
    session::Session, spotify_uri::SpotifyUri,
};
use librespot_playback::{
    config::{AudioFormat, Bitrate, NormalisationMethod, PlayerConfig},
    mixer::{softmixer::SoftMixer, Mixer, MixerConfig},
    player::{Player, PlayerEvent},
};
use crate::events::{self, EndReason, EventService, Listen};
use once_cell::sync::OnceCell;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::runtime::Runtime;

pub(crate) static JAVA_VM: OnceCell<JavaVM> = OnceCell::new();
static ENGINE: Mutex<Option<Engine>> = Mutex::new(None);
static CONTEXT_INITIALIZED: AtomicBool = AtomicBool::new(false);
/// Set when the last handshake failed and nothing is being tried right now.
///
/// Different from [`CONNECTED`], which is simply whether there is a device,
/// and from [`RECONNECTING`], which means one is being made. This means the
/// attempt was made and the network did not answer — and the app goes on
/// anyway, playing what is already on the phone, until the next attempt or a
/// network coming back.
static OFFLINE: AtomicBool = AtomicBool::new(false);

/// Whether the sink may make sound.
///
/// Every load shuts it, and the track that load asked for opens it again.
///
/// A load is a message to the Connect device, and it returns long before the
/// music changes: about a second passes while the new track is fetched, and the
/// old one is still being decoded throughout. The fade that wraps a skip is over
/// by then, so the volume came back up on the *previous* song, which played on
/// for a moment before the right one cut in.
///
/// Keyed on the track rather than on the load, which is where this was first put
/// and why it did not work: a load has to activate the device before it can hand
/// over a queue, and activating is what makes a freshly built device take up
/// whatever the account still believes this phone was playing.
static PLAYBACK_ARMED: AtomicBool = AtomicBool::new(true);

/// What the output is waiting for, when it is waiting for something.
///
/// A load knows the track it asked for, so it waits for that one. A skip does
/// not: `next` is a message to the Connect device, which picks the track
/// itself, so all this side knows is which one it wants to stop hearing.
enum Gate {
    /// Open when this track starts.
    Expect(String),
    /// Open when anything other than this track starts.
    Leave(String),
}

static GATE: Mutex<Option<Gate>> = Mutex::new(None);

/// The track currently being played, so a skip knows what it is leaving.
static CURRENT_URI: Mutex<String> = Mutex::new(String::new());

/// When to give up waiting, in milliseconds since the first call to [`uptime_ms`].
///
/// Silence that outlives its reason is worse than the noise it was there to
/// stop: a command that never lands would otherwise leave the app mute with no
/// way back except a restart.
static ARM_BY_MS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// How long the output stays shut while waiting.
const ARM_TIMEOUT_MS: u64 = 15_000;

static STARTED: OnceCell<std::time::Instant> = OnceCell::new();

fn uptime_ms() -> u64 {
    STARTED
        .get_or_init(std::time::Instant::now)
        .elapsed()
        .as_millis() as u64
}

/// Whether the sink may write. See [`PLAYBACK_ARMED`].
pub(crate) fn playback_armed() -> bool {
    PLAYBACK_ARMED.load(Ordering::SeqCst) || uptime_ms() >= ARM_BY_MS.load(Ordering::SeqCst)
}

/// How a track is fetched, which is most of how long it takes to start.
///
/// While a track is being opened the file is in random-access mode: every read
/// the decoder makes that lands on bytes nobody has yet asks the CDN for a
/// block and waits for it. Symphonia makes several of those — the Ogg headers,
/// Spotify's own normalisation packet, then the seek to the starting position —
/// and with librespot's default block of 64 KiB each one is a separate request
/// with a full round trip in front of it. Measured on this phone, on a healthy
/// connection: the download itself took 300 ms and opening the decoder took two
/// seconds, all of it waiting for those small blocks.
///
/// What is left here are the waits, not the sizes: a phone on wifi is not half
/// a second away from a CDN, and half a second of audio in hand is enough to
/// start on. The block size is the crate's own; see the note beside it.
///
/// Set once for the process; a second call is refused by the crate and ignored
/// here, which is right — the first player's numbers are as good as the next's.
fn tune_fetching() {
    use std::time::Duration;

    // Back at the crate's own size, and deliberately.
    //
    // Raising it looked obvious — fewer requests for the same bytes — and made
    // things worse: while a track is opening, every read the decoder makes on
    // bytes nobody has fetches a whole block and waits for it, so a bigger
    // block is a longer wait at each of those. What actually cost the time was
    // the seek that is no longer done at all; see the note in player.rs.
    let minimum_download_size = 64 * 1024;
    let minimum_throughput = 8 * 1024;
    let _ = librespot_audio::AudioFetchParams::set(librespot_audio::AudioFetchParams {
        minimum_download_size,
        minimum_throughput,
        // A phone on wifi is not half a second away from a CDN, and assuming it
        // is makes the loader hold back on the prefetch that would hide the
        // next round trip.
        initial_ping_time_estimate: Duration::from_millis(150),
        maximum_assumed_ping_time: Duration::from_millis(800),
        // What has to be in hand before the first sample is played. A second is
        // a second of waiting on every track; half of one is still several
        // blocks at this size.
        read_ahead_before_playback: Duration::from_millis(500),
        // How far past the decoder's position the reads themselves ask for,
        // and how much the fetcher keeps in flight on its own while a track
        // streams. Both were the crate's defaults, tuned for a desktop on a
        // fixed line, where a request answers before the next one is due. A
        // phone on a poor link is different: the fetcher kept one block in
        // flight at a time, and with a full round trip between blocks the
        // link delivered about a bitrate's worth and no more — the buffer sat
        // at the edge of empty for the whole song and every hiccup was heard.
        // Several requests pipelined hide the round trips, and a quarter of a
        // minute in hand rides out the ones that stall.
        read_ahead_during_playback: Duration::from_secs(15),
        prefetch_threshold_factor: 12.0,
        // A block that has not arrived in this long is not going to. Longer
        // than the crate's fifteen seconds, because giving up is a reload from
        // the current position, which on a network file is a seek that costs
        // seconds of its own; a slow block is cheaper to wait for.
        download_timeout: Duration::from_secs(25),
    });
}

/// Shuts the output until `gate` is satisfied.
fn shut(gate: Gate) {
    if let Ok(mut current) = GATE.lock() {
        *current = Some(gate);
    }
    ARM_BY_MS.store(uptime_ms() + ARM_TIMEOUT_MS, Ordering::SeqCst);
    PLAYBACK_ARMED.store(false, Ordering::SeqCst);
}

/// The track this device is on, for a skip to name as the one it is leaving.
fn current_uri() -> String {
    CURRENT_URI.lock().map(|uri| uri.clone()).unwrap_or_default()
}

/// Notes which track is playing, and opens the output if this is the one it was
/// waiting for.
fn track_started(uri: &str) {
    if let Ok(mut current) = CURRENT_URI.lock() {
        *current = uri.to_string();
    }
    if PLAYBACK_ARMED.load(Ordering::SeqCst) {
        return;
    }
    let satisfied = match GATE.lock().as_deref() {
        Ok(Some(Gate::Expect(wanted))) => wanted == uri,
        Ok(Some(Gate::Leave(old))) => old != uri,
        // Nothing to wait for: whatever shut the output has no opinion left.
        _ => true,
    };
    if satisfied {
        log::info!("output open again on {uri}");
        PLAYBACK_ARMED.store(true, Ordering::SeqCst);
    }
}

/// Set while a session is being built; see [`reconnect`].
///
/// Read without the engine lock. A handshake used to be made *under* that lock,
/// which froze every question asked of the engine for as long as the access
/// point took to answer — on a weak signal, the whole app for ten seconds. The
/// handshake now runs on the runtime, holding nothing, and this is how the
/// rest of the engine knows one is in flight.
static RECONNECTING: AtomicBool = AtomicBool::new(false);

/// Whether there is a Connect device right now.
///
/// The one fact the rest of the engine reads before deciding who does a thing:
/// with a device, loads and transport go through it so the account sees them;
/// without one, they go straight to the player, and the device is told what
/// it missed when it comes back. See [`adopt_now`].
static CONNECTED: AtomicBool = AtomicBool::new(false);

/// Whether the Connect device has been told what the player is on.
///
/// False after every load that went straight to the player — because there was
/// no device, or the device did not answer — and true once the device has
/// either loaded the track itself or adopted it. While it is false the device's
/// idea of the queue is not the player's, so the app owns the advance at the
/// end of a track; see [`is_offline`], which is what the Kotlin side reads.
static SPIRC_KNOWS: AtomicBool = AtomicBool::new(false);

/// Which Connect device is current, so a task ending late is told from one
/// ending now. See the watcher spawned by [`install_device`].
static DEVICE_GENERATION: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// When the next connection attempt may be made, in [`uptime_ms`] terms.
///
/// Attempts that nobody forced wait longer each time they fail — five seconds,
/// then ten, then twenty, up to two minutes — so a phone with no network is
/// not handshaking on a loop. A network coming back, or the listener asking,
/// is a reason to try at once; see [`reconnect`].
static NEXT_ATTEMPT_MS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
static FAILED_ATTEMPTS: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);

/// The first wait after a failed attempt, doubled each time up to [`MAX_BACKOFF_MS`].
const FIRST_BACKOFF_MS: u64 = 5_000;
const MAX_BACKOFF_MS: u64 = 120_000;

/// How long one handshake may take before it is called failed.
///
/// The access point resolves, connects with retries, authenticates, fetches a
/// client token and a login5 token; on a poor link each step can stall, and
/// librespot bounds none of them. Past this the attempt is abandoned and the
/// next is scheduled, which is the difference between "connecting" and "hung".
const CONNECT_TIMEOUT: Duration = Duration::from_secs(45);

/// How long a streamed load waits for a session before it goes to the network
/// anyway. See `PlayerConfig::session_ready`.
const SESSION_WAIT: Duration = Duration::from_secs(30);

/// How long the Connect device is given to start loading a track it was handed.
///
/// A device whose task has died still accepts the message and nobody reads
/// it; the account's keepalive takes up to eighty seconds to notice. Past this
/// the track is loaded straight into the player and the device is rebuilt.
/// Longer than the longest bounded wait in the device's own loop, so a loop
/// that was merely busy telling the account something is not mistaken for a
/// dead one.
const LOAD_ACK: Duration = Duration::from_millis(2_500);

/// Tells the player's loader when it may ask the network; see [`session_ready`].
static SESSION_UP: OnceCell<tokio::sync::watch::Sender<bool>> = OnceCell::new();

/// What the player reported last, kept for [`adopt_now`].
///
/// The device that adopts a track has to describe it as the player's own load:
/// the id the player gave that load, where it is, how long it is. All of it
/// arrives as events and is kept here by the forwarder.
static CURRENT_PLAY_REQUEST_ID: std::sync::atomic::AtomicU64 =
    std::sync::atomic::AtomicU64::new(0);
static CURRENT_POSITION_MS: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
static CURRENT_DURATION_MS: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);

/// A load handed to the Connect device and not yet seen to start.
///
/// `(sequence, uri)`: the watchdog spawned by [`load_queue`] checks that the
/// same load is still pending when its time is up, so a later load does not
/// have an earlier one's watchdog fire on it.
static LOAD_EXPECT: Mutex<Option<(u64, String)>> = Mutex::new(None);
static LOAD_SEQ: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// What the `session` event tells the Kotlin side.
pub const SESSION_FAILED: i64 = 0;
pub const SESSION_CONNECTED: i64 = 1;
pub const SESSION_PREMIUM_REQUIRED: i64 = 2;

pub fn store_java_vm(vm: JavaVM) {
    let _ = JAVA_VM.set(vm);
}

/// The engine: one runtime, one mixer, one player, and whichever session and
/// Connect device are current.
///
/// The player lives as long as the engine, and that is the whole design. It
/// used to be thrown away with every session — librespot binds a player to
/// the session it streams through, and a session that has lost its connection
/// cannot be reconnected — so every network drop was also a stop, and every
/// repair a second of silence and a queue to hand over again. The player now
/// keeps going across all of it: a session is swapped underneath it with
/// [`Player::set_session`], the track it is decoding finishes on the fetcher
/// it already has, and the next load uses the new session. A downloaded track
/// never notices the network at all.
///
/// The runtime and the audio output are here for the reason they always were:
/// dropping the runtime from a JNI thread while librespot's own threads are
/// inside it aborted the process, and the output belongs to the Android
/// service, not to any session.
pub struct Engine {
    rt: Runtime,
    mixer: Arc<SoftMixer>,
    /// Everything needed to build another session, kept so a reconnection
    /// needs nothing from the Kotlin side.
    recipe: Recipe,
    /// Where player events go. Owned here so the pump thread, and with it the
    /// listener reference, lives as long as the engine.
    events_tx: std::sync::mpsc::Sender<Pump>,
    /// The one player; see the type's note.
    player: Arc<Player>,
    /// The session the player streams through. Replaced whole by a
    /// reconnection; never connected at all until the first handshake lands.
    session: Session,
    /// The Connect device, when there is one. `None` while the first handshake
    /// is being made, after a session has died, and when the network is gone.
    spirc: Option<Spirc>,
}

impl Engine {
    /// The Connect device, or the error a command on a missing one becomes.
    ///
    /// Every transport call goes through this rather than testing for a device
    /// first: the callers already handle a device that refuses — that is what a
    /// lost one looks like — and refusing is exactly the right answer when there
    /// is no device at all. See [`transport`], which then goes straight to the
    /// player.
    fn spirc(&self) -> Result<&Spirc, librespot_core::Error> {
        self.spirc
            .as_ref()
            .ok_or_else(|| librespot_core::Error::unavailable("no connect device"))
    }
}

/// The inputs to a session, all of them cheap to clone.
struct Recipe {
    session_config: SessionConfig,
    player_config: PlayerConfig,
    connect_config: ConnectConfig,
    /// The OAuth credentials from the original login, if the caller had a token
    /// to give. Only the first login really needs one: the access point answers
    /// a successful handshake with a reusable credential, which is kept and used
    /// from then on. Empty means "use what was kept".
    credentials: Option<Credentials>,
    credentials_dir: String,
    cache_dir: String,
}

/// What the event pump reads.
///
/// Events are the reason it exists; the session arrives with every new one,
/// because listening history is reported through the session and the pump holds
/// one for the life of the engine.
enum Pump {
    Event(PlayerEvent),
    Session(Session),
    /// Something changed on another of the account's devices; see `remote`.
    Cluster,
    /// Something the app wants said to Kotlin that did not come from the
    /// player — download progress, and whether a session could be made.
    ///
    /// Carried on this channel rather than one of its own because the listener
    /// and the permanent JVM attachment are both here. A second thread saying
    /// the same kind of thing would be a second attachment to make, a second
    /// reference to keep alive and a second thing to shut down, for messages
    /// that arrive a few times a second at most.
    App {
        kind: String,
        uri: String,
        value: i64,
    },
}

/// Errors are flattened to a string because they cross the JNI boundary and the
/// Kotlin side only ever surfaces them as a message.
pub type EngineResult<T> = Result<T, String>;

fn with_engine<T>(f: impl FnOnce(&Engine) -> T) -> EngineResult<T> {
    let guard = ENGINE.lock().map_err(|_| "engine mutex poisoned")?;
    let engine = guard.as_ref().ok_or("engine not started")?;
    Ok(f(engine))
}

/// Borrow the authenticated session, e.g. to clone it for a catalogue request.
///
/// Before the first handshake lands this is a session that has never been
/// connected, and a request on it fails the way it always did offline. The
/// Kotlin side asks [`is_connected`] before it reads the catalogue.
pub fn with_session<T>(f: impl FnOnce(&Session) -> T) -> EngineResult<T> {
    with_engine(|engine| f(&engine.session))
}

/// A Web API access token minted for the session that is playing.
///
/// The listener's own Web API application is a registration they make in
/// Spotify's dashboard, and such an application lives in Development Mode.
/// Spotify refuses some endpoints to applications in that mode outright, and
/// `POST /v1/playlists/{id}/tracks` is one of them. That was measured rather
/// than guessed: with the account owning the playlist and both
/// `playlist-modify-*` scopes provably granted, a body of `{"uris":[]}` was
/// refused exactly like a real one, while `PUT /v1/playlists/{id}/followers` on
/// the same playlist with the same token succeeded. Nothing about the payload,
/// the scopes or the ownership explains it; the application does.
///
/// The session is authenticated as the official client, which is under no such
/// restriction, and Spotify hands out Web API tokens for it on request. So the
/// app writes to the account through the same credential it plays with, and the
/// dashboard registration goes back to being what it was meant to be — a way to
/// have one's own search quota, not a thing the app depends on.
///
/// `scopes` is comma-separated, which is what librespot's provider expects and
/// is *not* the space-separated spelling the OAuth endpoints use.
pub fn web_token(scopes: &str) -> EngineResult<String> {
    let session = with_session(|s| s.clone())?;
    let scopes = scopes.to_string();
    // The provider keeps what it is given until it expires, so the common case
    // costs a lock and a scan rather than a round trip.
    runtime_handle()?.block_on(async move {
        // What the session itself carries. librespot authenticates every
        // spclient call with this, so it is known good against Spotify's own
        // hosts; whether api.spotify.com honours it for a write is the question
        // this answers.
        match session.login5().auth_token().await {
            Ok(token) => {
                log::debug!("using the session's login5 token for {scopes}");
                return Ok(token.access_token);
            }
            Err(e) => log::debug!("no login5 token: {e}"),
        }

        let mut last = String::new();
        for client_id in token_client_ids(&session) {
            match session
                .token_provider()
                .get_token_with_client_id(&scopes, &client_id)
                .await
            {
                Ok(token) => return Ok(token.access_token),
                Err(e) => {
                    log::debug!("no {scopes} token for client {client_id}: {e}");
                    last = e.to_string();
                }
            }
        }
        Err(format!("session token for {scopes} refused: {last}"))
    })
}

/// Which applications to ask for a Web API token, in order.
///
/// Spotify does not grant every scope to every client id — librespot's own
/// provider says as much — and the one this session authenticates with is the
/// keymaster id, which is refused `playlist-modify-*` outright: the keymaster
/// endpoint answers 403 `{"code":4,"errorDescription":"Invalid request"}`. The
/// official mobile application is granted them, because it is what the phone
/// app writes playlists with, so it is asked next.
///
/// The ids are librespot's own, from `librespot-core/src/config.rs`, repeated
/// here because they are `pub(crate)` there and patching the vendored crate to
/// widen them would be a patch to carry forever for two constants.
fn token_client_ids(session: &Session) -> Vec<String> {
    const ANDROID: &str = "9a8d2f0ce77a4e248bb71fefcb557637";
    const IOS: &str = "58bd3c95768941ea9eb4350aaa033eb3";

    let mut ids = vec![session.client_id()];
    for candidate in [ANDROID, IOS] {
        if !ids.iter().any(|id| id == candidate) {
            ids.push(candidate.to_string());
        }
    }
    ids
}

/// A handle to the engine's runtime.
///
/// Returned by value so callers can release the engine lock before blocking on
/// a request; holding it across an await would serialise playback commands
/// behind network I/O.
pub fn runtime_handle() -> EngineResult<tokio::runtime::Handle> {
    with_engine(|engine| engine.rt.handle().clone())
}

/// Publish an application `Context` to `ndk_context` so cpal's AAudio host can
/// resolve the output device. Must run before any playback starts.
///
/// Idempotent. `ndk_context::initialize_android_context` asserts that no context
/// was set before and aborts the process otherwise, and the Android service that
/// calls this can be destroyed and recreated any number of times within one
/// process. The context never changes over that lifetime, so the second and
/// later calls are simply no-ops.
pub fn init_android_context(context: &JObject) -> EngineResult<()> {
    if CONTEXT_INITIALIZED.swap(true, Ordering::SeqCst) {
        return Ok(());
    }

    let vm = JAVA_VM.get().ok_or("JNI_OnLoad did not run")?;
    let env = vm.get_env().map_err(|e| e.to_string())?;
    let global = env
        .new_global_ref(context)
        .map_err(|e| format!("failed to pin Context: {e}"))?;

    // SAFETY: both pointers stay valid for the lifetime of the process — the VM
    // is never destroyed on Android, and `global` is deliberately leaked so the
    // Context reference is never collected while ndk_context holds it.
    #[cfg(target_os = "android")]
    unsafe {
        ndk_context::initialize_android_context(
            vm.get_java_vm_pointer() as *mut _,
            global.as_raw() as *mut _,
        );
    }
    std::mem::forget(global);
    Ok(())
}

/// What a streamed load waits for before it asks the network.
///
/// Handed to the player through `PlayerConfig::session_ready`. Resolves at once
/// with the current session when there is a Connect device; otherwise waits
/// for the handshake in flight, bounded by [`SESSION_WAIT`], and answers with
/// whatever session the engine has by then — the connected one if the
/// handshake landed, the placeholder if it did not, in which case the load
/// fails the ordinary way and is retried by the player.
fn session_ready() -> std::pin::Pin<Box<dyn std::future::Future<Output = Option<Session>> + Send>> {
    Box::pin(async {
        if !CONNECTED.load(Ordering::SeqCst) {
            if let Some(up) = SESSION_UP.get() {
                let mut rx = up.subscribe();
                let waited = tokio::time::timeout(SESSION_WAIT, async move {
                    while !*rx.borrow_and_update() {
                        if rx.changed().await.is_err() {
                            break;
                        }
                    }
                })
                .await;
                if waited.is_err() {
                    log::warn!("no session after {SESSION_WAIT:?}; loading against what there is");
                }
            }
        }
        with_engine(|engine| engine.session.clone()).ok()
    })
}

/// Build the runtime, the player and a session to hang it on, then connect.
///
/// Returns as soon as there is a player, which is at once: the handshake runs
/// on the runtime and what it produces — a session and a Connect device — is
/// installed when it lands, and announced to the Kotlin side as a `session`
/// event. Music on the phone plays in the meantime, and music that has to be
/// streamed waits for the session inside the loader; see [`session_ready`].
///
/// The one exception is a device that has never logged in. Then the only way
/// in is the access token the caller just obtained, the caller is a login
/// screen waiting for an answer, and there is nothing on the phone to play
/// meanwhile — so that handshake is made here and its result returned.
///
/// `listener` must implement `dev.lelonio.square.nativecore.NativeEvents`.
pub fn start(
    client_id: &str,
    device_name: &str,
    device_id: &str,
    access_token: &str,
    credentials_dir: &str,
    cache_dir: &str,
    language: &str,
    bitrate_kbps: i32,
    crossfade_ms: i32,
    listener: GlobalRef,
) -> EngineResult<()> {
    tune_fetching();

    let mut guard = ENGINE.lock().map_err(|_| "engine mutex poisoned")?;
    if guard.is_some() {
        return Err("engine already started".into());
    }

    let rt = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .thread_name("squarecore")
        .build()
        .map_err(|e| format!("failed to build runtime: {e}"))?;

    // An empty `client_id` means "use the platform default", which on Android is
    // the client id librespot ships for the official mobile app. login5 only
    // grants streaming to ids it recognises, so overriding this with a client id
    // registered in the Spotify dashboard will authenticate against the Web API
    // but be rejected at the access point.
    let mut session_config = SessionConfig::default();
    session_config.tmp_dir = std::path::PathBuf::from(cache_dir);
    // What Spotify should answer in. Artwork for the generated playlists is
    // localised — the language is in the cover's own URL — so a client that
    // never says which one it wants gets English tiles.
    if !language.is_empty() {
        session_config.language = language.to_string();
    }
    if !client_id.is_empty() {
        session_config.client_id = client_id.to_string();
    }
    // The same id on every launch, chosen by the app and kept by it.
    //
    // librespot's default is a fresh uuid per session, which is right for a
    // daemon started once and wrong for a phone: the account saw a new "A059"
    // every time the engine came up, the list filled with ghosts of this same
    // device, and nothing could recognise the phone it had been playing on an
    // hour earlier.
    if !device_id.is_empty() {
        session_config.device_id = device_id.to_string();
    }

    let credentials = if access_token.is_empty() {
        None
    } else {
        Some(Credentials::with_access_token(access_token))
    };

    let mixer = Arc::new(
        SoftMixer::open(MixerConfig::default()).map_err(|e| format!("mixer failed: {e}"))?,
    );

    // SoftMixer::open starts at an attenuation factor of 0.5 — half amplitude,
    // about -6 dB — and nothing else ever sets it, so every track played through
    // this engine was quiet by default. The device's own volume keys are the
    // right place to control loudness, so the software mixer is left wide open.
    mixer.set_volume(u16::MAX);

    let player_config = PlayerConfig {
        // Chosen by the caller, and changed in place later; see set_quality.
        bitrate: match bitrate_kbps {
            96 => Bitrate::Bitrate96,
            160 => Bitrate::Bitrate160,
            _ => Bitrate::Bitrate320,
        },
        // Loudness normalisation, which is what keeps one track from being
        // twice as loud as the next.
        //
        // On its own it made the app quieter than everything else on the phone:
        // it pulls every track down towards a target and nothing puts the level
        // back. The pregain is what puts it back — Spotify's own "loud" setting
        // is about three decibels over its normal one, and a couple more brings
        // this level with apps that do no normalisation at all.
        normalisation: true,
        normalisation_pregain_db: 5.0,
        // The limiter, not a flat scale: with five decibels added, the loudest
        // moments of a loud master would otherwise clip.
        normalisation_method: NormalisationMethod::Dynamic,
        // Dithering runs per sample — 88200 times a second at 44.1 kHz stereo —
        // for a noise-floor benefit nobody hears on a phone. Off, to leave the
        // decoder more headroom against output underruns.
        ditherer: None,
        // Every update crosses JNI and rebuilds the Media3 state on the main
        // thread; once a second is plenty for a seek bar.
        position_update_interval: Some(Duration::from_secs(1)),
        // How long one track dissolves into the next, chosen by the user. See
        // the patch notes in native/vendor/README.md: the player asks for the
        // next track a fade early and mixes the one going out underneath it.
        // Changed in place later, like the bitrate; see set_quality.
        crossfade_duration_ms: crossfade_ms.max(0) as u32,

        // How the loader asks whether a track is already on the phone; see
        // downloads.rs. Installed unconditionally: the lookup answers "no" for
        // everything until a download root has been set, so there is no order
        // to get right between this and the Kotlin side setting one up.
        download_lookup: Some(crate::downloads::lookup()),
        // What a streamed load waits for; see session_ready.
        session_ready: Some(Arc::new(session_ready)),
        ..PlayerConfig::default()
    };

    // Spirc is what makes this a Spotify Connect device: it publishes the
    // playback state to the account, which is also the only way plays are
    // recorded in listening history — Spotify counts what a device reports, not
    // what it decodes. That is why nothing appeared in the history before this
    // existed.
    //
    // It drives the player whenever it is there. Driving the player directly as
    // well would play audio the published state knows nothing about, so every
    // transport command below goes through the Spirc handle when there is one —
    // and tells the device what it missed when there was not; see adopt_now.
    let connect_config = ConnectConfig {
        name: device_name.to_string(),
        // Smartphone rather than the librespot default of Speaker: the icon in
        // the device list should match what the user is holding.
        device_type: DeviceType::Smartphone,
        // Full scale, matching the mixer. Loudness belongs to the phone's own
        // volume keys.
        initial_volume: u16::MAX,
        ..ConnectConfig::default()
    };

    let recipe = Recipe {
        session_config,
        player_config,
        connect_config,
        credentials,
        credentials_dir: credentials_dir.to_string(),
        cache_dir: cache_dir.to_string(),
    };

    // Started before anything else and outliving every session: the pump owns
    // the listener reference, and that must be dropped on a thread still
    // attached to the JVM.
    let (events_tx, events_rx) = std::sync::mpsc::channel();
    spawn_event_pump(
        events_rx,
        listener,
        device_name.to_string(),
        cache_dir.to_string(),
    );

    let up = SESSION_UP.get_or_init(|| tokio::sync::watch::channel(false).0);
    let _ = up.send(false);
    CONNECTED.store(false, Ordering::SeqCst);
    SPIRC_KNOWS.store(false, Ordering::SeqCst);
    OFFLINE.store(false, Ordering::SeqCst);
    FAILED_ATTEMPTS.store(0, Ordering::SeqCst);
    NEXT_ATTEMPT_MS.store(0, Ordering::SeqCst);

    sweep_stale_downloads(cache_dir);

    // Taken before anything can report against it, and before the player
    // exists: the sink the player builds carries this number, which is what
    // keeps a replaced player's last packets out of the new one's output. One
    // player now lives for the whole engine, so this only ever moves at
    // shutdown — but the sink still asks.
    let generation = GENERATION.fetch_add(1, Ordering::SeqCst) + 1;

    // The player, over a session that has not been connected and may never
    // be. `Session::new` registers tasks of its own the moment it is built,
    // and off a runtime thread that is a panic, so it is built inside one.
    let (session, player) = {
        let _runtime = rt.enter();
        let session = new_session(&recipe)?;
        let player = Player::new(
            recipe.player_config.clone(),
            session.clone(),
            mixer.get_soft_volume(),
            move || Box::new(crate::sink::AndroidSink::new(AudioFormat::S16, generation)),
        );
        (session, player)
    };
    spawn_event_forwarder(&rt, player.clone(), events_tx.clone(), generation);
    // Listens are tracked from the first note, whatever the network is doing;
    // the reporting is pointed at the connected session when there is one.
    let _ = events_tx.send(Pump::Session(session.clone()));

    // Whether this device has been in before. A kept credential is one the
    // access point issued to it, so a failure with it is the network's; a
    // device with only a token is a login screen waiting for an answer.
    let has_kept = kept_credentials(&recipe).is_some();

    *guard = Some(Engine {
        rt,
        mixer,
        recipe,
        events_tx,
        player,
        session,
        spirc: None,
    });
    drop(guard);

    if has_kept {
        log::info!("player ready; connecting in the background");
        reconnect(true)
    } else {
        log::info!("first login; connecting before answering");
        RECONNECTING.store(true, Ordering::SeqCst);
        let handle = runtime_handle()?;
        let outcome = handle.block_on(connect_attempt());
        match outcome {
            Ok(()) => Ok(()),
            Err(e) => {
                // Nothing to keep: a device that has never been in has
                // nothing on the phone either, and the caller starts again.
                shutdown();
                Err(e)
            }
        }
    }
}

/// Where a reusable credential is kept, apart from librespot's own cache.
///
/// The credential the access point issued the first time this device logged
/// in. It is the difference between a session that lasts and one that has to
/// be renewed: an OAuth access token is good for an hour, and the refresh token
/// behind it is rotated on every use and revoked on the first mistake, so an
/// engine that needs one at every launch is one bad refresh away from asking
/// the listener to sign in again. The blob the handshake answers with has
/// neither property: it is what go-librespot writes to `credentials.json` and
/// then reuses forever, and it is why signing in there is something you do
/// once. Kept apart from librespot's own credentials file because that file is
/// written by the connection itself, and what has to survive is the copy
/// nothing else touches.
fn kept_cache(recipe: &Recipe) -> EngineResult<Cache> {
    Cache::new(
        Some(&std::path::Path::new(&recipe.credentials_dir).join("reusable")),
        None,
        None,
        None,
    )
    .map_err(|e| format!("cache failed: {e}"))
}

fn kept_credentials(recipe: &Recipe) -> Option<Credentials> {
    kept_cache(recipe).ok().and_then(|cache| cache.credentials())
}

/// A session over the engine's cache, not yet connected.
///
/// A credentials cache is not an optimisation here, it is required for
/// catalogue access. `connect` persists reusable credentials into it, and
/// login5 then issues the access point's HTTP token as a *stored credential*
/// request, which it signs with the session's client id. Without a cache it
/// falls back to the platform default id instead, which no longer matches the
/// id the OAuth token was minted for, and every spclient call fails with
/// "Login request was denied: BAD_REQUEST".
///
/// Bounded: an audio cache with no limit had grown past a gigabyte on the test
/// phone.
fn new_session(recipe: &Recipe) -> EngineResult<Session> {
    let cache = Cache::new(
        Some(std::path::Path::new(&recipe.credentials_dir)),
        None,
        Some(std::path::Path::new(&recipe.cache_dir)),
        Some(AUDIO_CACHE_LIMIT),
    )
    .map_err(|e| format!("cache failed: {e}"))?;
    Ok(Session::new(recipe.session_config.clone(), Some(cache)))
}

/// Makes a session and a Connect device over the engine's player, and installs
/// them.
///
/// Runs on the runtime, holding no lock across anything that waits. Every
/// question asked of the engine meanwhile is answered from what it has: the
/// player, the placeholder session, no device.
///
/// Both sides of the outcome are announced as a `session` event, because the
/// caller that started this is long gone: it either returned at once or is
/// blocking on this future with nothing else to do.
async fn connect_attempt() -> EngineResult<()> {
    let outcome = tokio::time::timeout(CONNECT_TIMEOUT, build_device())
        .await
        .unwrap_or_else(|_| Err("the handshake took too long".to_string()));

    match outcome {
        Ok((session, spirc, spirc_task)) => {
            install_device(session, spirc, spirc_task)?;
            RECONNECTING.store(false, Ordering::SeqCst);
            FAILED_ATTEMPTS.store(0, Ordering::SeqCst);
            log::info!("connected");
            emit_app("session", "", SESSION_CONNECTED);
            Ok(())
        }
        Err(e) if e == PREMIUM_REQUIRED => {
            RECONNECTING.store(false, Ordering::SeqCst);
            OFFLINE.store(true, Ordering::SeqCst);
            emit_app("session", "", SESSION_PREMIUM_REQUIRED);
            Err(e)
        }
        Err(e) => {
            // Nothing to stream through, and nothing left in flight. Whatever
            // is on the phone plays on; the next attempt is scheduled below,
            // and a network coming back brings one forward.
            let failures = FAILED_ATTEMPTS.fetch_add(1, Ordering::SeqCst) + 1;
            let backoff = (FIRST_BACKOFF_MS << (failures - 1).min(5)).min(MAX_BACKOFF_MS);
            NEXT_ATTEMPT_MS.store(uptime_ms() + backoff, Ordering::SeqCst);
            RECONNECTING.store(false, Ordering::SeqCst);
            OFFLINE.store(true, Ordering::SeqCst);
            log::warn!("{e}; playing what is on the phone, trying again in {backoff} ms");
            emit_app("session", "", SESSION_FAILED);
            if let Ok(handle) = runtime_handle() {
                handle.spawn(async move {
                    tokio::time::sleep(Duration::from_millis(backoff)).await;
                    let _ = reconnect(false);
                });
            }
            Err(e)
        }
    }
}

/// The handshake itself: a session connected with the credentials this device
/// has, and a Connect device over the engine's player.
async fn build_device() -> EngineResult<(Session, Spirc, impl std::future::Future<Output = ()>)> {
    let (recipe, mixer, player, events_tx) = with_engine(|engine| {
        (
            Recipe {
                session_config: engine.recipe.session_config.clone(),
                player_config: engine.recipe.player_config.clone(),
                connect_config: engine.recipe.connect_config.clone(),
                credentials: engine.recipe.credentials.clone(),
                credentials_dir: engine.recipe.credentials_dir.clone(),
                cache_dir: engine.recipe.cache_dir.clone(),
            },
            engine.mixer.clone(),
            engine.player.clone(),
            engine.events_tx.clone(),
        )
    })?;

    let kept = kept_cache(&recipe)?;

    // The kept credential first, the token second. A token is only ever the way
    // in for a device that has never been in.
    let attempts: Vec<(&str, Credentials)> = kept
        .credentials()
        .into_iter()
        .map(|c| ("the kept credential", c))
        .chain(recipe.credentials.clone().map(|c| ("an access token", c)))
        .collect();
    if attempts.is_empty() {
        return Err("no credentials".into());
    }

    let mixer_for_spirc: Arc<dyn Mixer> = mixer;
    let last = attempts.len() - 1;
    let mut failure = String::new();

    for (n, (kind, credentials)) in attempts.into_iter().enumerate() {
        log::info!("logging in with {kind}");
        // Seed the cache, then hand the cached copy to `connect` with storing
        // switched off. Letting `connect` store instead would overwrite the
        // cache with the handshake's own blob, and what is in the cache is what
        // gets sent as the stored credential when login5 issues the access
        // point's HTTP token.
        let session = new_session(&recipe)?;
        if let Some(cache) = session.cache() {
            cache.save_credentials(&credentials);
        }

        // The account's other devices, watched over the same dealer.
        //
        // Subscribed before the session connects, which is the whole point of
        // doing it here: Spotify pushes a cluster update when this device
        // appears, and that push is the only one that arrives without something
        // changing later. Subscribing afterwards meant opening the app next to
        // a speaker that was already playing and being told nothing until the
        // speaker's track ended.
        let watch_tx = events_tx.clone();
        crate::remote::watch(&session, move || {
            let _ = watch_tx.send(Pump::Cluster);
        });

        // No `session.connect` here: `Spirc::new` registers its dealer
        // listeners and then connects the session itself. Connecting first
        // authenticated twice and left the second attempt reporting "Session
        // is not connected", which is what this looked like from the outside.
        let connected = Spirc::new(
            recipe.connect_config.clone(),
            session.clone(),
            credentials.clone(),
            player.clone(),
            mixer_for_spirc.clone(),
        )
        .await;

        let (spirc, spirc_task) = match connected {
            Ok(pair) => pair,
            Err(e) => {
                session.shutdown();
                failure = format!("connect failed: {e}");
                // A kept credential the account no longer honours — the
                // password changed, the device was removed from the list — is a
                // dead end, not a reason to stop: it is thrown away so the token
                // behind it gets its turn, and so the next launch does not try
                // it again.
                if n < last {
                    log::warn!("{failure}, trying the next credential");
                    let _ = std::fs::remove_dir_all(
                        std::path::Path::new(&recipe.credentials_dir).join("reusable"),
                    );
                    continue;
                }
                return Err(failure);
            }
        };

        // Spirc connects with credential storing switched on, so the cache now
        // holds the blob the access point answered with. That is the one worth
        // keeping, and the seed goes back into the cache after it is taken:
        // login5 signs its stored-credential request with the session's client
        // id, and the pair that is known to agree is the one this session
        // actually logged in with.
        if let Some(cache) = session.cache() {
            if let Some(reusable) = cache.credentials() {
                kept.save_credentials(&reusable);
            }
            cache.save_credentials(&credentials);
        }

        // Checked here rather than left to the library. The vendored
        // librespot-core used to call exit(1) on a non-premium account, which
        // took the app's process down and left Android restarting the service
        // in a loop; it now only logs, so the refusal has to be made an error
        // the caller can show.
        if let Some(account_type) = session.get_user_attribute("type") {
            if account_type != "premium" {
                session.shutdown();
                return Err(PREMIUM_REQUIRED.to_string());
            }
        }

        return Ok((session, spirc, spirc_task));
    }

    Err(failure)
}

/// Puts a freshly connected session and device into the engine.
///
/// The player is told about the session first, so the next load streams
/// through it; the device is then told what the player is already on, if it is
/// on anything, so the account catches up without the music restarting.
fn install_device(
    session: Session,
    spirc: Spirc,
    spirc_task: impl std::future::Future<Output = ()> + Send + 'static,
) -> EngineResult<()> {
    let generation = DEVICE_GENERATION.fetch_add(1, Ordering::SeqCst) + 1;

    // The account's picture of every device, whenever a state update brings
    // one back. The dealer pushes the same thing when something changes
    // elsewhere; this is for the pushes that are missed.
    let listener_tx = with_engine(|engine| engine.events_tx.clone())?;
    spirc.set_cluster_listener(Some(Arc::new(move |cluster| {
        crate::remote::set_cluster(cluster);
        let _ = listener_tx.send(Pump::Cluster);
    })));

    let (handle, events_tx) = {
        let mut guard = ENGINE.lock().map_err(|_| "engine mutex poisoned")?;
        let engine = guard.as_mut().ok_or("engine not started")?;
        engine.player.set_session(session.clone());
        // The device that was there is gone, whatever it was doing.
        if let Some(old) = engine.spirc.take() {
            old.set_cluster_listener(None);
        }
        engine.session = session.clone();
        engine.spirc = Some(spirc);
        (engine.rt.handle().clone(), engine.events_tx.clone())
    };

    // The event loop runs for the life of the device: it is what answers the
    // dealer, so without it the device appears once and then goes stale.
    handle.spawn(async move {
        spirc_task.await;
        if DEVICE_GENERATION.load(Ordering::SeqCst) != generation {
            log::info!("connect device {generation} stopped, already replaced");
            return;
        }
        // The authoritative moment the Connect device is gone.
        //
        // Not a failed command, which is what this used to key off and why the
        // repair never fired: when the task ends, its channel still accepts
        // messages and nobody reads them. Watching the task itself is the only
        // signal that does not depend on being lucky enough to get an error
        // back. The music is not touched: whatever the player is on, it stays
        // on, and the queue is the app's to advance until a new device has
        // adopted it.
        log::info!("connect device stopped");
        device_lost();
        let _ = reconnect(false);
    });

    // The pump reports listening history through the session, so it is told
    // about this one before any of its events can arrive.
    let _ = events_tx.send(Pump::Session(session));

    OFFLINE.store(false, Ordering::SeqCst);
    SPIRC_KNOWS.store(false, Ordering::SeqCst);
    CONNECTED.store(true, Ordering::SeqCst);
    if let Some(up) = SESSION_UP.get() {
        let _ = up.send(true);
    }
    // What the player is on, if anything, is described to the new device as
    // soon as it is listening; see the forwarder, which tries on every tick.
    let _ = adopt_now();
    Ok(())
}

/// Notes that the Connect device is gone, without touching the player.
fn device_lost() {
    CONNECTED.store(false, Ordering::SeqCst);
    SPIRC_KNOWS.store(false, Ordering::SeqCst);
    if let Some(up) = SESSION_UP.get() {
        let _ = up.send(false);
    }
}

/// Which player the sink belongs to.
///
/// There is one player for the life of the engine now, so this moves only at
/// shutdown — but the sink still asks, because a player that has been told to
/// stop does not fall silent at once: its playback thread finishes the packets
/// it already holds, and there is one AudioTrack for the whole app. Without
/// the check those packets came out after everything else had gone.
static GENERATION: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// The live player's number, for a sink that belongs to an older one.
pub(crate) fn live_generation() -> u64 {
    GENERATION.load(Ordering::SeqCst)
}

/// Feeds the player's events to the engine-wide pump, keeps the bookkeeping the
/// Connect device needs to adopt a track, and preloads ahead of it.
///
/// Runs for the life of the player, which is the life of the engine. The
/// generation check is for the events already in flight at shutdown: a dying
/// player emits a `Stopped`, and delivered late that would tell Kotlin the
/// engine had stopped before it was asked to.
fn spawn_event_forwarder(
    rt: &Runtime,
    player: Arc<Player>,
    events_tx: std::sync::mpsc::Sender<Pump>,
    generation: u64,
) {
    let mut events = player.get_player_event_channel();
    rt.spawn(async move {
        // The track whose successor has already been fetched.
        let mut preloaded: Option<String> = None;

        while let Some(event) = events.recv().await {
            if GENERATION.load(Ordering::SeqCst) != generation {
                break;
            }

            match &event {
                PlayerEvent::Playing { position_ms, .. } => {
                    LOCAL_PLAYING.store(true, Ordering::SeqCst);
                    CURRENT_POSITION_MS.store(*position_ms, Ordering::SeqCst);
                }
                PlayerEvent::Paused { position_ms, .. } => {
                    LOCAL_PLAYING.store(false, Ordering::SeqCst);
                    CURRENT_POSITION_MS.store(*position_ms, Ordering::SeqCst);
                }
                PlayerEvent::Stopped { .. } => {
                    LOCAL_PLAYING.store(false, Ordering::SeqCst);
                    if let Ok(mut current) = CURRENT_URI.lock() {
                        current.clear();
                    }
                }
                PlayerEvent::PositionChanged { position_ms, .. }
                | PlayerEvent::Seeked { position_ms, .. }
                | PlayerEvent::PositionCorrection { position_ms, .. } => {
                    CURRENT_POSITION_MS.store(*position_ms, Ordering::SeqCst);
                }
                PlayerEvent::PlayRequestIdChanged { play_request_id } => {
                    CURRENT_PLAY_REQUEST_ID.store(*play_request_id, Ordering::SeqCst);
                }
                PlayerEvent::TrackChanged { audio_item } => {
                    CURRENT_DURATION_MS.store(audio_item.duration_ms, Ordering::SeqCst);
                }
                _ => {}
            }

            // The track the app asked for is on its way: let it be heard.
            // `Loading` rather than `Playing`, so nothing of its opening is lost
            // while the two sides agree the silence is over.
            if let PlayerEvent::Loading { track_id, .. } | PlayerEvent::Playing { track_id, .. } =
                &event
            {
                let uri = uri_string(track_id);
                track_started(&uri);
                // The Connect device answered the load it was handed.
                if let Ok(mut expect) = LOAD_EXPECT.lock() {
                    if expect.as_ref().is_some_and(|(_, wanted)| *wanted == uri) {
                        *expect = None;
                    }
                }
            }

            // Warm up the next track — but not the instant this one starts.
            //
            // Preloading on the first note meant a run of skips fired a burst
            // of audio-key requests, Spotify refused them ("error audio key"),
            // and librespot went on to decode the still-encrypted bytes:
            // megabytes of noise through the decoder, thousands of log lines,
            // and an app that looked frozen. A few seconds in, a track is one
            // the listener is actually on, and a skipped-past track asks for
            // nothing.
            if let PlayerEvent::PositionChanged {
                track_id,
                position_ms,
                ..
            } = &event
            {
                let uri = uri_string(track_id);
                if *position_ms > PRELOAD_AFTER_MS && preloaded.as_deref() != Some(&uri) {
                    preload_after(&player, &uri);
                    preloaded = Some(uri);
                }

                // A device that came up after the track did, or a load that went
                // past it: told now, once a second, until it has been told.
                if CONNECTED.load(Ordering::SeqCst) && !SPIRC_KNOWS.load(Ordering::SeqCst) {
                    let _ = adopt_now();
                }
            }

            if events_tx.send(Pump::Event(event)).is_err() {
                break;
            }
        }
        log::info!("event forwarder {generation} ended");
    });
}

/// Changes what the player asks for, in place.
///
/// The bitrate and the crossfade used to belong to a configuration fixed for
/// the life of a player, so a new value meant a new player, which meant a new
/// session — a second of silence and a Connect device to hand the queue to
/// again. Both are now read live by the player (see the patch notes in
/// native/vendor/README.md), so this is two messages and nothing rebuilt: what
/// is playing keeps the file and the fade it started with, and the next track
/// gets the new ones.
pub fn set_quality(bitrate_kbps: i32, crossfade_ms: i32) -> EngineResult<()> {
    let bitrate = match bitrate_kbps {
        96 => Bitrate::Bitrate96,
        160 => Bitrate::Bitrate160,
        _ => Bitrate::Bitrate320,
    };
    let crossfade = crossfade_ms.max(0) as u32;
    let mut guard = ENGINE.lock().map_err(|_| "engine mutex poisoned")?;
    let engine = guard.as_mut().ok_or("engine not started")?;
    engine.recipe.player_config.bitrate = bitrate;
    engine.recipe.player_config.crossfade_duration_ms = crossfade;
    engine.player.set_bitrate(bitrate);
    engine.player.set_crossfade(crossfade);
    Ok(())
}

/// Changes the quality of what is asked for next, without rebuilding anything.
///
/// The bitrate is read while a track is being loaded, to put the formats it
/// exists in into order of preference, and nowhere else. What is playing keeps
/// the file it started with. The next track gets this.
pub fn set_bitrate(bitrate_kbps: i32) -> EngineResult<()> {
    let bitrate = match bitrate_kbps {
        96 => Bitrate::Bitrate96,
        160 => Bitrate::Bitrate160,
        _ => Bitrate::Bitrate320,
    };
    let mut guard = ENGINE.lock().map_err(|_| "engine mutex poisoned")?;
    let engine = guard.as_mut().ok_or("engine not started")?;
    // Kept on the recipe as well, so a session rebuilt for any other reason
    // starts where the listening left it rather than back at the setting.
    engine.recipe.player_config.bitrate = bitrate;
    engine.player.set_bitrate(bitrate);
    Ok(())
}

/// Gets a session and a Connect device, when there is none.
///
/// This is the whole answer to a network drop. librespot's Spirc builder is
/// handed out once per session, so a Connect device that has died cannot be
/// replaced on the session it belonged to; go-librespot reaches the same
/// conclusion and discards the session too. What is *not* discarded any more
/// is the player: the session is swapped underneath it and the music goes on.
///
/// Returns at once. The handshake runs on the runtime, and its outcome arrives
/// as a `session` event. Idempotent, and safe to call from anywhere at any
/// time: a live device is left alone, an attempt in flight is not doubled, and
/// an attempt nobody forced waits out its backoff.
///
/// `force` is for a reason to try now — a network that has just come back, a
/// listener pressing retry, the engine starting — rather than at the time the
/// last failure said.
pub fn reconnect(force: bool) -> EngineResult<()> {
    if CONNECTED.load(Ordering::SeqCst) && session_invalid() == Some(false) {
        return Ok(());
    }
    if RECONNECTING.swap(true, Ordering::SeqCst) {
        return Ok(());
    }
    if !force && uptime_ms() < NEXT_ATTEMPT_MS.load(Ordering::SeqCst) {
        RECONNECTING.store(false, Ordering::SeqCst);
        return Err("not yet: waiting out the last failure".into());
    }
    if force {
        NEXT_ATTEMPT_MS.store(0, Ordering::SeqCst);
        FAILED_ATTEMPTS.store(0, Ordering::SeqCst);
    }

    let handle = match with_engine(|engine| engine.rt.handle().clone()) {
        Ok(handle) => handle,
        Err(e) => {
            RECONNECTING.store(false, Ordering::SeqCst);
            return Err(e);
        }
    };

    // The device that was there is gone, whatever the flags said. Its session
    // is invalidated so its task ends, and the account is told nothing: a
    // session that has lost its connection cannot tell the account anything,
    // and one that is merely believed lost will be replaced by the device
    // built next, which the account sees as the same device coming back.
    if let Ok(mut guard) = ENGINE.lock() {
        if let Some(engine) = guard.as_mut() {
            if let Some(old) = engine.spirc.take() {
                old.set_cluster_listener(None);
                engine.session.shutdown();
            }
        }
    }
    crate::remote::unwatch();
    device_lost();

    log::info!("connecting{}", if force { " now" } else { "" });
    handle.spawn(async {
        let _ = connect_attempt().await;
    });
    Ok(())
}

/// Asks the account what every device is doing; see `Spirc::refresh_cluster`.
///
/// Refused without a device: a state update is the device's own, and there is
/// nothing to send one as.
pub fn refresh_cluster() -> EngineResult<()> {
    with_engine(|engine| engine.spirc()?.refresh_cluster())?
        .map_err(|e| format!("cluster refresh failed: {e}"))
}


/// Forward player events to Kotlin. Runs for the life of the engine.
///
/// Deliberately a dedicated OS thread rather than a tokio task. The thread is
/// attached to the JVM once for its whole life, which avoids attaching and
/// detaching on every event and — more importantly — guarantees the listener's
/// `GlobalRef` is dropped while the thread is still attached. A tokio task
/// migrates between workers, so its `GlobalRef` could be released on a detached
/// thread, where JNI cannot free it ("Dropping a GlobalRef in a detached
/// thread").
/// Runs for the life of the engine, across any number of sessions: whichever
/// player is current has a forwarder feeding this one channel.
fn spawn_event_pump(
    events: std::sync::mpsc::Receiver<Pump>,
    listener: GlobalRef,
    device_name: String,
    state_dir: String,
) {
    std::thread::Builder::new()
        .name("squarecore-events".into())
        .spawn(move || {
            let vm = match JAVA_VM.get() {
                Some(vm) => vm,
                None => return,
            };
            // Permanent attach: detached only when this thread exits, by which
            // point `listener` has already been dropped.
            if let Err(e) = vm.attach_current_thread_permanently() {
                log::error!("event pump could not attach to the JVM: {e}");
                return;
            }

            log::info!("event pump started for device {device_name}");
            // What the account's listening history is built from; see events.rs.
            // Built on the first session, which arrives before any of its
            // events, and rebound to each one after that.
            let mut history: Option<History> = None;

            while let Ok(message) = events.recv() {
                let event = match message {
                    Pump::Event(event) => event,
                    // Carries nothing: the Kotlin side reads the state it wants
                    // when it hears there is a new one, rather than having every
                    // field of it pushed through this boundary.
                    Pump::Cluster => {
                        if let Err(e) = emit(&listener, "cluster", "", 0) {
                            log::warn!("dropping a cluster update: {e}");
                        }
                        continue;
                    }
                    Pump::App { kind, uri, value } => {
                        if let Err(e) = emit(&listener, &kind, &uri, value) {
                            log::warn!("dropping {kind}: {e}");
                        }
                        continue;
                    }
                    Pump::Session(session) => {
                        match history.as_mut() {
                            // The listen in progress survives: the same person
                            // is hearing the same track, and only the socket it
                            // will be reported over has changed.
                            Some(history) => history.rebind(session),
                            None => history = Some(History::new(session, state_dir.clone())),
                        }
                        continue;
                    }
                };

                if let Some(history) = history.as_mut() {
                    history.observe(&event);
                }

                let (kind, uri, position_ms) = match event {
                    PlayerEvent::Playing {
                        track_id,
                        position_ms,
                        ..
                    } => ("playing", uri_string(&track_id), position_ms as i64),
                    PlayerEvent::Paused {
                        track_id,
                        position_ms,
                        ..
                    } => ("paused", uri_string(&track_id), position_ms as i64),
                    PlayerEvent::PositionChanged {
                        track_id,
                        position_ms,
                        ..
                    } => ("position", uri_string(&track_id), position_ms as i64),
                    PlayerEvent::Stopped { track_id, .. } => ("stopped", uri_string(&track_id), 0),
                    PlayerEvent::EndOfTrack { track_id, .. } => {
                        ("end_of_track", uri_string(&track_id), 0)
                    }
                    PlayerEvent::Loading { track_id, .. } => ("loading", uri_string(&track_id), 0),
                    PlayerEvent::Unavailable { track_id, .. } => {
                        ("unavailable", uri_string(&track_id), 0)
                    }
                    // Shuffle and repeat belong to the account, so the app has
                    // to hear about them rather than only ever set them: they
                    // arrive here whenever another client changes them on this
                    // device, and whenever a handover brings the account's own
                    // along with the music.
                    PlayerEvent::ShuffleChanged { shuffle } => {
                        ("shuffle", String::new(), shuffle as i64)
                    }
                    // One number for two flags, and the track wins when both are
                    // set: that is the order `set_repeat` already reads them in,
                    // and it is the only combination a player with three buttons
                    // can draw. 0 is off, 1 the context, 2 the track.
                    PlayerEvent::RepeatChanged { context, track } => (
                        "repeat",
                        String::new(),
                        if track {
                            2
                        } else if context {
                            1
                        } else {
                            0
                        },
                    ),
                    // Volume and the session's own comings and goings are not
                    // surfaced yet.
                    _ => continue,
                };

                if let Err(e) = emit(&listener, kind, &uri, position_ms) {
                    log::warn!("dropping event {kind}: {e}");
                }
            }

            // Explicit so the ordering is obvious: the listener must go while
            // this thread is still attached to the JVM.
            drop(listener);
            log::info!("event pump ended");
        })
        .expect("failed to spawn the event pump thread");
}

/// Turns the player's events into the three Spotify wants for a listen.
///
/// Kept beside the pump rather than inside `events` because only here is the
/// order of things known: a track is "finished" when the next one starts, when
/// the player stops, or when it runs off the end, and only one of those is an
/// event that says so.
struct History {
    events: EventService,
    /// Where the in-progress listen is kept, so a killed process loses nothing.
    state_dir: String,
    session_id: String,
    /// The context every listen is attributed to, once one has been started.
    context_uri: String,
    current: Option<Playing>,
    /// Durations seen so far, by track URI.
    ///
    /// `TrackChanged` is the only event carrying one and it arrives *before*
    /// the track starts, so there is nothing to attach it to yet. Held here
    /// until the matching `Playing` shows up. Without this the transition
    /// reported the played time as the track's length — a three-minute song
    /// skipped at forty seconds went to Spotify as a forty-second song played
    /// to the end, and nothing about that was going to be believed.
    durations: std::collections::HashMap<String, u32>,
}

struct Playing {
    uri: String,
    hex: String,
    playback_id: String,
    start_ms: u32,
    position_ms: u32,
    duration_ms: u32,
    started_at: u128,
    reason_start: &'static str,
}

impl History {
    fn new(session: Session, state_dir: String) -> Self {
        let session_id = session.session_id();
        let events = EventService::new(session);
        // Whatever the last run was in the middle of when it went away.
        events::pending::flush(&state_dir, &events);
        History {
            events,
            state_dir,
            session_id: if session_id.is_empty() {
                events::random_id()
            } else {
                session_id
            },
            context_uri: String::new(),
            current: None,
            durations: std::collections::HashMap::new(),
        }
    }

    /// Points the reporting at a new session, keeping the listen in progress.
    ///
    /// The session id changes because Spotify's is per connection, and a report
    /// carrying the dead one's would be filed against a session the account no
    /// longer has. Everything else about the listen is unchanged, which is the
    /// point: the network went away, the music did not.
    fn rebind(&mut self, session: Session) {
        let session_id = session.session_id();
        self.events = EventService::new(session);
        self.session_id = if session_id.is_empty() {
            events::random_id()
        } else {
            session_id
        };
        if !self.context_uri.is_empty() {
            self.events
                .new_session(&self.session_id, &self.context_uri, 1);
        }
    }

    fn observe(&mut self, event: &PlayerEvent) {
        match event {
            PlayerEvent::TrackChanged { audio_item } => {
                // Kept for whenever this track starts; see `durations`. The map
                // is bounded by the queue, and a queue is not unbounded.
                if audio_item.duration_ms > 0 {
                    self.durations
                        .insert(audio_item.uri.clone(), audio_item.duration_ms);
                }
                if let Some(playing) = self.current.as_mut() {
                    if playing.uri == audio_item.uri {
                        playing.duration_ms = audio_item.duration_ms;
                    }
                }
            }

            PlayerEvent::Playing {
                track_id,
                position_ms,
                ..
            } => self.start(uri_string(track_id), *position_ms),

            PlayerEvent::PositionChanged { position_ms, .. }
            | PlayerEvent::Seeked { position_ms, .. } => {
                if let Some(playing) = self.current.as_mut() {
                    playing.position_ms = *position_ms;
                }
                // Once a second, which is what this event arrives at.
                self.remember();
            }

            // A pause ends the stretch that was being listened to.
            //
            // Reported straight away rather than held until the track finishes,
            // because most of the time it never does: the track is paused, the
            // app is left, and the process goes away with the listen still
            // sitting in memory. That is why only one of a session's tracks
            // ever reached the account's history.
            //
            // Resuming opens a *new* playback at the position it resumes from,
            // so what is reported is always a stretch that really was heard —
            // never one invented to make a pause look like a full play.
            PlayerEvent::Paused { position_ms, .. } => {
                if let Some(playing) = self.current.as_mut() {
                    playing.position_ms = *position_ms;
                }
                // Not when the pause is the track running out: the end of a
                // track is reported as one, and reporting it here first would
                // take the completed listen away and leave a stopped one in its
                // place.
                let at_end = self
                    .current
                    .as_ref()
                    .map(|playing| {
                        playing.duration_ms > 0
                            && playing.position_ms + END_MARGIN_MS >= playing.duration_ms
                    })
                    .unwrap_or(false);
                if !at_end {
                    self.finish(EndReason::EndPlay);
                }
            }

            // Ran to the end: the one that counts as a full listen.
            PlayerEvent::EndOfTrack { .. } => self.finish(EndReason::TrackDone),
            PlayerEvent::Stopped { .. } => self.finish(EndReason::EndPlay),
            _ => {}
        }
    }

    fn start(&mut self, uri: String, position_ms: u32) {
        if let Some(playing) = self.current.as_ref() {
            // Un-pausing and seeking both arrive as `Playing` on a track that is
            // already open, and neither is a new listen.
            if playing.uri == uri {
                if let Some(playing) = self.current.as_mut() {
                    playing.position_ms = position_ms;
                }
                return;
            }
            // A different track without an end event: something skipped.
            self.finish(EndReason::Forward);
        }

        let hex = hex_id(&uri);
        if hex.is_empty() {
            return;
        }

        // The playlist or album this queue came from, or the track itself when
        // it came from neither — which is what the official client sends for a
        // track played on its own.
        let context = match current_context() {
            context if context.is_empty() => uri.clone(),
            context => context,
        };
        if self.context_uri != context {
            self.context_uri = context;
            // A context is a session: leaving one and starting another is a new
            // session id, not a continuation of the last.
            self.session_id = events::random_id();
            self.events
                .new_session(&self.session_id, &self.context_uri, 1);
        }

        let playback_id = events::random_id();
        self.events.new_playback(&playback_id, &self.session_id);

        let duration_ms = self.durations.get(&uri).copied().unwrap_or(0);
        self.current = Some(Playing {
            uri,
            hex,
            playback_id,
            start_ms: position_ms,
            position_ms,
            duration_ms,
            started_at: events::now_ms(),
            reason_start: "playbtn",
        });
        self.remember();
    }

    /// Keeps the in-progress listen on disk; see `events::pending`.
    fn remember(&self) {
        let Some(playing) = self.current.as_ref() else {
            return;
        };
        events::pending::write(
            &self.state_dir,
            &events::pending::Pending {
                track_hex: playing.hex.clone(),
                playback_id: playing.playback_id.clone(),
                context_uri: self.context_uri.clone(),
                start_ms: playing.start_ms,
                position_ms: playing.position_ms,
                duration_ms: playing.duration_ms,
                started_at: playing.started_at,
            },
        );
    }

    fn finish(&mut self, reason: EndReason) {
        let Some(playing) = self.current.take() else {
            return;
        };
        // A stretch too short to have been listened to at all — a pause landing
        // in the same instant as the start, a track that failed to open.
        if playing.position_ms.saturating_sub(playing.start_ms) < MIN_LISTEN_MS
            && !matches!(reason, EndReason::TrackDone)
        {
            return;
        }
        // Running off the end means the whole track was heard, whatever the
        // last position report happened to say.
        let end_ms = match reason {
            EndReason::TrackDone if playing.duration_ms > 0 => playing.duration_ms,
            _ => playing.position_ms.max(playing.start_ms),
        };
        let duration_ms = if playing.duration_ms > 0 {
            playing.duration_ms
        } else {
            end_ms
        };

        // Sent, so it must not be sent again on the next start.
        events::pending::clear(&self.state_dir);

        self.events.track_transition(&Listen {
            track_hex: &playing.hex,
            playback_id: &playing.playback_id,
            context_uri: &self.context_uri,
            start_ms: playing.start_ms,
            end_ms,
            duration_ms,
            reason_start: playing.reason_start,
            reason_end: reason,
            started_at: playing.started_at,
        });
    }
}

/// How close to the end counts as the track finishing rather than being paused.
const END_MARGIN_MS: u32 = 2_000;

/// Below this, a stretch is a mis-tap rather than a listen.
const MIN_LISTEN_MS: u32 = 1_000;

/// A track's gid in hex, which is what the events carry — not the base62 id.
fn hex_id(uri: &str) -> String {
    match SpotifyUri::from_uri(uri) {
        Ok(SpotifyUri::Track { id }) => id.to_base16().unwrap_or_default(),
        _ => String::new(),
    }
}

fn uri_string(uri: &SpotifyUri) -> String {
    uri.to_uri().unwrap_or_default()
}

/// Says something to the Kotlin listener from outside the player.
///
/// Best effort on purpose. A progress tick that cannot be delivered — because
/// the engine is between two sessions, or because the pump has already been
/// torn down — is a ring that does not move for a moment. That is not a reason
/// to fail the download it belongs to.
pub fn emit_app(kind: &str, uri: &str, value: i64) {
    let Ok(sender) = with_engine(|engine| engine.events_tx.clone()) else {
        return;
    };
    let _ = sender.send(Pump::App {
        kind: kind.to_string(),
        uri: uri.to_string(),
        value,
    });
}

/// Invoke `NativeEvents.onEvent` from a tokio worker thread.
fn emit(listener: &GlobalRef, kind: &str, uri: &str, position_ms: i64) -> Result<(), String> {
    let vm = JAVA_VM.get().ok_or("JNI_OnLoad did not run")?;
    // Tokio worker threads are not known to the VM, so they must attach. The
    // guard detaches on drop, which keeps the thread's local ref table bounded.
    let mut env = vm
        .attach_current_thread()
        .map_err(|e| format!("attach failed: {e}"))?;

    let j_kind = env.new_string(kind).map_err(|e| e.to_string())?;
    let j_uri = env.new_string(uri).map_err(|e| e.to_string())?;

    env.call_method(
        listener,
        "onEvent",
        "(Ljava/lang/String;Ljava/lang/String;J)V",
        &[
            JValue::Object(&j_kind),
            JValue::Object(&j_uri),
            JValue::Long(position_ms),
        ],
    )
    .map_err(|e| e.to_string())?;

    // A Kotlin listener that throws would otherwise leave the exception pending
    // and poison every later JNI call on this thread.
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
        return Err("listener threw".into());
    }
    Ok(())
}

/// The playlist or album the current queue came from, for the listening events.
///
/// A global rather than a field on the engine: the event pump reads it from its
/// own thread, and it changes whenever a queue is loaded — which is a different
/// lock from the one that owns playback.
static CONTEXT_URI: Mutex<String> = Mutex::new(String::new());

/// The queue as the app handed it over, in play order.
///
/// Kept so the engine can fetch the next track before it is asked for; see
/// [`preload_after`].
static QUEUE: Mutex<Vec<String>> = Mutex::new(Vec::new());

/// How much of the phone the cached audio may take: 512 MB.
const AUDIO_CACHE_LIMIT: u64 = 512 * 1024 * 1024;

/// Deletes the temporary files left behind by downloads that never finished.
///
/// librespot writes each download to a `.tmp…` file beside the cache and
/// renames it when it completes; a skip mid-download leaves the temporary file
/// where it is, several megabytes at a time, for ever.
fn sweep_stale_downloads(dir: &str) {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return;
    };
    let mut removed = 0;
    for entry in entries.flatten() {
        let name = entry.file_name();
        let Some(name) = name.to_str() else { continue };
        if !name.starts_with(".tmp") {
            continue;
        }
        // Only the ones nothing is writing any more. An hour is far longer than
        // any download and short enough that they do not pile up.
        let stale = entry
            .metadata()
            .and_then(|meta| meta.modified())
            .map(|modified| {
                modified
                    .elapsed()
                    .map(|age| age.as_secs() > 3600)
                    .unwrap_or(false)
            })
            .unwrap_or(false);
        if stale && std::fs::remove_file(entry.path()).is_ok() {
            removed += 1;
        }
    }
    if removed > 0 {
        log::info!("swept {removed} unfinished downloads");
    }
}

/// What `start` returns for an account Spotify does not stream to this client.
///
/// Matched by the Kotlin side, so it stays a stable string.
pub const PREMIUM_REQUIRED: &str = "premium account required";

/// Plays one track on the player, with no Connect device in the way.
///
/// The direct half of [`load_queue`], and the only way anything is heard
/// without a device: while the first handshake is being made, after a session
/// has died, and with no network at all. It does what Spirc would have done
/// with the track it picked — load it, at this position, playing or not — and
/// stops there: the track after it is loaded by the next call, when the app
/// hears that this one ended, and the device is told about all of it when it
/// is back; see [`adopt_now`].
///
/// Warming the successor is skipped on purpose. Without a device the next
/// track is most often a file on this phone, and preloading it would decode a
/// second stream to save four milliseconds.
fn local_load(uris: &[String], index: u32, play: bool, position_ms: u32) -> EngineResult<()> {
    let uri = uris
        .get(index as usize)
        .ok_or_else(|| "nothing at that index".to_string())?;
    let parsed = SpotifyUri::from_uri(uri).map_err(|e| format!("bad uri {uri}: {e}"))?;
    log::info!("direct: loading {uri} at {position_ms}ms, play={play}");
    SPIRC_KNOWS.store(false, Ordering::SeqCst);
    with_engine(|e| e.player.load(parsed, play, position_ms))
}

/// Whether there is no Connect device right now.
///
/// True while the first handshake is being made, after a session has died and
/// until the next one lands, and with no network at all. The Kotlin side reads
/// it with [`is_adopted`] to know whose the queue is; see that.
pub fn is_offline() -> bool {
    !CONNECTED.load(Ordering::SeqCst)
}

/// Whether a handshake is in flight.
pub fn is_connecting() -> bool {
    RECONNECTING.load(Ordering::SeqCst)
}

/// Whether the Connect device knows what the player is on.
///
/// The question the Kotlin side asks before it advances the queue itself: a
/// device that loaded the current track, or adopted it, advances at the end
/// of it and skips through the list it was given; a device that does not know
/// the track does neither, and a device that is not there cannot. So the app
/// owns the queue exactly while this is false, and hands it over — through
/// the next load — the moment it is true again.
pub fn is_adopted() -> bool {
    CONNECTED.load(Ordering::SeqCst) && SPIRC_KNOWS.load(Ordering::SeqCst)
}

/// How far into a track its successor is fetched, in milliseconds.
const PRELOAD_AFTER_MS: u32 = 5_000;

/// Warms up the track after `uri`, so a skip does not start from nothing.
///
/// librespot preloads on its own, but only in the last thirty seconds of a
/// track — that is for gapless playback, and it does nothing for someone
/// skipping. Between the tap and the first sound there was most of a second of
/// audio key and first chunk; fetched now, while the current track plays, the
/// skip has them already.
fn preload_after(player: &Player, uri: &str) {
    // Not without a session: the head start would go to the network, fail,
    // and be forgotten, for nothing.
    if !CONNECTED.load(Ordering::SeqCst) {
        return;
    }
    let next = {
        let Ok(queue) = QUEUE.lock() else { return };
        let Some(position) = queue.iter().position(|item| item == uri) else {
            return;
        };
        queue.get(position + 1).cloned()
    };
    let Some(next) = next else { return };
    if let Ok(parsed) = SpotifyUri::from_uri(&next) {
        player.preload(parsed);
    }
}

pub fn current_context() -> String {
    CONTEXT_URI.lock().map(|c| c.clone()).unwrap_or_default()
}

pub fn load_queue(
    uris: Vec<String>,
    index: u32,
    start_playing: bool,
    position_ms: u32,
    context_uri: String,
    play_as_context: bool,
) -> EngineResult<()> {
    if uris.is_empty() {
        return Err("empty queue".into());
    }
    for uri in &uris {
        let parsed = SpotifyUri::from_uri(uri).map_err(|e| format!("bad uri {uri}: {e}"))?;
        if !parsed.is_playable() {
            return Err(format!("{uri} is not a playable item"));
        }
    }

    // Read before the request takes the list: what the load asks for is also
    // what the output waits for, and what the watchdog below looks for.
    let wanted = uris.get(index as usize).cloned().unwrap_or_default();

    let context = context_uri.clone();
    if let Ok(mut stored) = CONTEXT_URI.lock() {
        *stored = context_uri;
    }
    if let Ok(mut stored) = QUEUE.lock() {
        *stored = uris.clone();
    }

    // A queue that nobody asked to hear does not go out while the account is
    // playing somewhere else.
    //
    // A load activates this device, which is right when the listener has just
    // tapped a track and wrong every other time. Opening the app restores the
    // last queue, paused, and that restore was taking the session away from
    // whatever was playing in the other room: Fluidify went silent-but-active
    // and the music stopped mid-track.
    //
    // Asked before the direct path below as well as after it, because the
    // answer does not depend on there being a device: it comes from the
    // account, and loading a paused track into the player would fetch a song
    // nobody is going to hear.
    //
    // Refused rather than quietly dropped, so the caller keeps its queue marked
    // as one the engine has never seen and hands it over again the moment the
    // listener really does ask for it.
    if !start_playing && elsewhere_active() {
        return Err("another device has playback".into());
    }

    // Without a device that is listening, the player is given the track
    // itself and the device is asked for. The music does not wait for the
    // access point: a downloaded track starts in milliseconds whatever the
    // network is doing, a streamed one waits for the session inside its
    // loader, and the device is told what the player is on the moment it can
    // hear it; see adopt_now. Everything above still runs, so what the app
    // believes about the queue is unchanged either way — the only difference
    // is who advances it, and the app reads that from is_adopted.
    //
    // With the account playing elsewhere this is a track the listener has
    // just asked for, since a restore was refused above. It starts here at
    // once, which for a moment means two devices making sound; the adoption
    // that follows the handshake activates this one, and the other stops.
    // The alternative is silence under a finger that asked for a song.
    let listening = with_engine(|e| e.spirc.as_ref().is_some_and(Spirc::is_established))?;
    if !CONNECTED.load(Ordering::SeqCst) || !listening {
        let result = local_load(&uris, index, start_playing, position_ms);
        let _ = reconnect(false);
        return result;
    }

    let options = LoadRequestOptions {
        start_playing,
        seek_to: position_ms,
        playing_track: Some(PlayingTrack::Index(index)),
        ..LoadRequestOptions::default()
    };

    // Handed over as the context itself when the queue really is one.
    //
    // A list of URIs is not a place: played that way, the account sees a queue
    // of loose tracks, other devices see no playlist, and the listen has
    // nowhere to be filed. The caller says when the two agree — the rows on
    // screen being the playlist in its own order — because only it can know.
    let request = if play_as_context {
        let start = uris
            .get(index as usize)
            .cloned()
            .map(PlayingTrack::Uri)
            .or(Some(PlayingTrack::Index(index)));
        LoadRequest::from_context_uri(
            context,
            LoadRequestOptions {
                playing_track: start,
                ..options
            },
        )
    } else if !context.is_empty() {
        // The tracks in the order on screen, published under the playlist they
        // came from.
        //
        // Shuffling a playlist, or adding a track to it, makes a queue that is
        // the playlist without being the playlist's order, and upstream has no
        // shape for that: either the context, whose order is Spotify's, or a
        // list of tracks, published as `spotify:web-api`. The second is what
        // this app sent nearly always, and it is a context no other device can
        // resolve: handing playback to one gave it a queue that would not play,
        // and taking it back arrived with a track that could not be found. See
        // native/vendor/README.md.
        LoadRequest::from_tracks_in(context, uris.clone(), options)
    } else {
        LoadRequest::from_tracks(uris.clone(), options)
    };

    // Nothing but this track may be heard until it starts; see PLAYBACK_ARMED.
    if !wanted.is_empty() {
        shut(Gate::Expect(wanted.clone()));
    }

    let sequence = LOAD_SEQ.fetch_add(1, Ordering::SeqCst) + 1;
    if let Ok(mut expect) = LOAD_EXPECT.lock() {
        *expect = Some((sequence, wanted.clone()));
    }

    let sent = with_engine(|e| {
        // Without this the device is registered but idle, and a load is
        // ignored: playback belongs to whichever device the account has
        // active, and taking that over is an explicit step.
        let spirc = e.spirc()?;
        spirc.activate()?;
        spirc.load(request)?;
        // And the account's options again, because the load has just wiped
        // them: `handle_load` opens with `reset_options`, which turns shuffle
        // and both kinds of repeat off before it reads a single track. Every
        // tap on a song therefore ended repeat, and there was no way to tell
        // that from the app deciding to end it.
        //
        // Sent after the load rather than before it for the same reason: the
        // commands are one queue read one at a time, so anything said first is
        // said to a device that is about to forget it.
        republish_options(spirc)
    })?;

    match sent {
        Ok(()) => {
            SPIRC_KNOWS.store(true, Ordering::SeqCst);
            watch_load(sequence, uris, index, start_playing, position_ms);
            Ok(())
        }
        Err(e) => {
            // The channel is closed: the device's task has ended and nobody
            // noticed yet. The player is given the track itself, which is what
            // the listener asked for, and a new device is asked for.
            log::warn!("the connect device refused the load ({e}); loading it directly");
            if let Ok(mut expect) = LOAD_EXPECT.lock() {
                *expect = None;
            }
            device_lost();
            let result = local_load(&uris, index, start_playing, position_ms);
            let _ = reconnect(false);
            result
        }
    }
}

/// Makes sure a load handed to the Connect device actually reached the player.
///
/// A device whose task has died still accepts the message; nobody reads it,
/// nothing loads, and the old track plays on, for as long as the account's
/// keepalive takes to notice — up to eighty seconds of a listener pressing
/// things at a player that will not move. This waits [`LOAD_ACK`] for the
/// player to say it is loading the track, and when it does not, gives the
/// player the track itself and has the device rebuilt.
fn watch_load(sequence: u64, uris: Vec<String>, index: u32, play: bool, position_ms: u32) {
    let Ok(handle) = runtime_handle() else { return };
    handle.spawn(async move {
        tokio::time::sleep(LOAD_ACK).await;
        let pending = LOAD_EXPECT
            .lock()
            .map(|expect| expect.as_ref().is_some_and(|(seq, _)| *seq == sequence))
            .unwrap_or(false);
        if !pending {
            return;
        }
        if let Ok(mut expect) = LOAD_EXPECT.lock() {
            *expect = None;
        }
        let uri = uris.get(index as usize).cloned().unwrap_or_default();
        log::warn!(
            "the connect device did not start {uri} within {LOAD_ACK:?}; \
             loading it directly and rebuilding the device"
        );
        // Dead to us whatever the account thinks. Its session is invalidated
        // so its task ends, and a late load from it cannot restart the track
        // the player is about to be given.
        if let Ok(mut guard) = ENGINE.lock() {
            if let Some(engine) = guard.as_mut() {
                if let Some(old) = engine.spirc.take() {
                    old.set_cluster_listener(None);
                    engine.session.shutdown();
                }
            }
        }
        device_lost();
        if let Err(e) = local_load(&uris, index, play, position_ms) {
            log::error!("direct load failed as well: {e}");
        }
        let _ = reconnect(true);
    });
}

/// Tells the Connect device what the player is on.
///
/// The other half of [`local_load`]. A track loaded straight into the player
/// is one the device knows nothing about: it will not advance at the end of
/// it, will not preload what follows, and the account shows a device that is
/// playing nothing. Loading the track again through the device would tell it,
/// at the price of starting the song over. This describes it instead — the
/// queue, the track, the position and the player's own id for the load — and
/// the device carries on as if it had loaded the song itself; see the patch
/// note in native/vendor/README.md.
///
/// Called when a device lands while something is playing, once a second from
/// the forwarder until it succeeds, and before any transport command that
/// needs the device to know the track. Fails harmlessly when there is nothing
/// to describe, or nobody listening yet.
fn adopt_now() -> EngineResult<()> {
    let uri = current_uri();
    if uri.is_empty() {
        return Err("nothing to adopt".into());
    }
    let playing = LOCAL_PLAYING.load(Ordering::SeqCst);
    // Paused, with another device active: adopting activates this one, which
    // would take the session away from the device that is playing, for
    // nothing anybody asked for. The next play here is that request.
    if !playing && crate::remote::elsewhere_active() {
        return Err("another device has playback".into());
    }

    let uris = QUEUE.lock().map(|queue| queue.clone()).unwrap_or_default();
    let index = uris
        .iter()
        .position(|item| *item == uri)
        .ok_or_else(|| format!("the player is on {uri}, which the queue does not hold"))?;
    let context = current_context();
    let request = AdoptRequest {
        context_uri: (!context.is_empty()).then_some(context),
        tracks: uris,
        index,
        play_request_id: CURRENT_PLAY_REQUEST_ID.load(Ordering::SeqCst),
        position_ms: CURRENT_POSITION_MS.load(Ordering::SeqCst),
        duration_ms: CURRENT_DURATION_MS.load(Ordering::SeqCst),
        playing,
    };

    let sent = with_engine(|e| {
        let spirc = e.spirc()?;
        if !spirc.is_established() {
            return Err(librespot_core::Error::unavailable(
                "connect device not listening yet",
            ));
        }
        spirc.adopt(request)?;
        republish_options(spirc)
    })?;
    sent.map_err(|e| format!("adopt failed: {e}"))?;
    SPIRC_KNOWS.store(true, Ordering::SeqCst);
    log::info!("the connect device adopted {uri}");
    Ok(())
}

/// Runs a transport command through Spirc, and through the player if Spirc is
/// not there or does not know the track.
///
/// Spirc and the player are separate things: the first is the Connect device,
/// the second is what decodes audio and writes it to the sink. Every transport
/// command goes through Spirc so the account sees it, but a Spirc command is a
/// message into a task, and when that task has died the channel is closed and
/// the command comes back an error while the player carries on making sound.
///
/// That is the "pause does nothing and only killing the app stops the music"
/// failure. It cannot be fixed by reporting the error, because the audio is
/// still playing either way: something has to reach the thing making it. So the
/// player is told directly, and the account is left with a stale idea of what
/// this device is doing until the device is rebuilt. Silence under the user's
/// finger is worth more than a tidy Connect state.
fn transport(
    what: &str,
    via_spirc: impl FnOnce(&Engine) -> Result<(), librespot_core::Error>,
    via_player: impl FnOnce(&Engine),
) -> EngineResult<()> {
    // LOCAL PATCH: every command this side sends, so a skip nobody asked for
    // can be told from one this app asked for. See spirc.rs, which says the
    // same about the commands that arrive from the account.
    log::info!("this device asks to {what}");

    // Another device has the account's playback: this one must not reach for it.
    //
    // Every command here activates the device first, which is right when the
    // listener is asking this phone to play and catastrophic when they are not.
    // A pause sent while a speaker was playing took the session away from the
    // speaker and then stopped it, which is what closing the app did to the
    // music in the other room, and what opening it did on the way in.
    //
    // The player is still told, because the point of a pause is silence here.
    // Spirc is not, so nothing is taken from anyone.
    if elsewhere_active() {
        return with_engine(|engine| {
            log::info!("{what}: another device has playback, keeping this one to itself");
            via_player(engine);
        });
    }

    // Asked before trying, not after failing.
    //
    // A command sent to a Spirc task that has already ended does not come back
    // an error: the channel still accepts the message and nobody ever reads it.
    // Waiting for a failure meant the fallback almost never ran, which is why
    // pause went on doing nothing with the network gone. And a device that
    // does not know the track would answer a command about it with nothing.
    if !is_adopted() {
        return with_engine(|engine| {
            log::info!("{what}: no connect device on this track, going straight to the player");
            via_player(engine);
        });
    }

    let refused = with_engine(|engine| match {
        // Taken over first, the same way a load does.
        //
        // A device that is registered but not the account's active one drops
        // every transport command on the floor, and says so at warning level:
        // "SpircCommand::Play will be ignored while Not Active". Nothing came
        // back as an error, so the app saw a pause that had been accepted and a
        // player that went on regardless.
        let _ = engine.spirc().map(Spirc::activate);
        via_spirc(engine)
    } {
        Ok(()) => false,
        Err(e) => {
            log::warn!("{what}: spirc refused it ({e}), going straight to the player");
            via_player(engine);
            true
        }
    })?;
    // Outside the borrow above: a reconnection takes the same lock.
    if refused {
        device_lost();
        let _ = reconnect(false);
    }
    Ok(())
}

/// Makes sure a skip handed to the Connect device actually moved the music.
///
/// The same watch as [`watch_load`], for the same dead-but-not-yet-noticed
/// device: a skip it swallows leaves the old track playing under a listener
/// who asked for the next one. The output was shut when the skip went out,
/// to open on the first track that is not the one being left; if it is still
/// shut when the time is up, nothing moved, and the engine moves the queue
/// itself from the copy it holds — forward or back, as asked, wrapping when
/// the account's repeat says so — and has the device rebuilt.
fn watch_skip(before: String, forward: bool) {
    if before.is_empty() {
        return;
    }
    let Ok(handle) = runtime_handle() else { return };
    handle.spawn(async move {
        tokio::time::sleep(LOAD_ACK).await;
        if PLAYBACK_ARMED.load(Ordering::SeqCst) || current_uri() != before {
            return;
        }
        log::warn!(
            "the connect device did not move off {before} within {LOAD_ACK:?};              moving the queue directly and rebuilding the device"
        );
        if let Ok(mut guard) = ENGINE.lock() {
            if let Some(engine) = guard.as_mut() {
                if let Some(old) = engine.spirc.take() {
                    old.set_cluster_listener(None);
                    engine.session.shutdown();
                }
            }
        }
        device_lost();

        let uris = QUEUE.lock().map(|queue| queue.clone()).unwrap_or_default();
        let at = uris.iter().position(|uri| *uri == before);
        let wrap = REPEAT_CONTEXT.load(Ordering::SeqCst);
        let target = match (at, forward) {
            (Some(index), true) if index + 1 < uris.len() => Some(index + 1),
            (Some(_), true) if wrap && !uris.is_empty() => Some(0),
            (Some(index), false) if index > 0 => Some(index - 1),
            (Some(_), false) if wrap && !uris.is_empty() => Some(uris.len() - 1),
            _ => None,
        };
        match target {
            Some(index) => {
                if let Err(e) = local_load(&uris, index as u32, true, 0) {
                    log::error!("direct skip failed as well: {e}");
                }
            }
            None => {
                // Nothing to move to: the end of the queue, which is a stop.
                let _ = with_engine(|engine| engine.player.stop());
            }
        }
        let _ = reconnect(true);
    });
}

/// Whether this device is decoding audio right now.
///
/// The plainest fact available, and the one that settles the argument when the
/// other two sources disagree; see [`elsewhere_active`].
static LOCAL_PLAYING: AtomicBool = AtomicBool::new(false);

/// Whether the account's playback belongs to another device.
///
/// Asked of librespot rather than of the cluster, because the two disagree for
/// as long as it takes the previous device to let go. After playback is
/// transferred *to* this phone, the account can go on naming the device it came
/// from: trusting that meant refusing to play on a device that was already
/// playing, and every guard in here reading the wrong answer at once.
///
/// The cluster is still the fallback, for the moment before there is a device
/// at all.
pub fn elsewhere_active() -> bool {
    // Sound coming out of this phone settles it.
    //
    // The other two answers can both be wrong at once, and were: after
    // playback was taken back here, the account still named the device it came
    // from and the Connect state had not caught up either, so a pause pressed
    // on a phone that was playing went out to a laptop that was not. Whatever
    // the bookkeeping says, the device making the sound is the device the
    // buttons belong to.
    if LOCAL_PLAYING.load(Ordering::SeqCst) {
        return false;
    }

    match with_engine(|engine| engine.spirc.as_ref().is_some_and(Spirc::is_active)) {
        Ok(true) => false,
        Ok(false) => crate::remote::elsewhere_active(),
        Err(_) => crate::remote::elsewhere_active(),
    }
}

/// What the Connect state says this device is playing, as JSON.
///
/// `{"contextUri", "trackUri", "index", "tracks"}`, any of which can be empty.
///
/// The track list is the queue as the account handed it over, which is the
/// only copy of it for a context this app cannot read for itself.
///
/// Asked when another client has driven this device somewhere the app did not
/// send it. The account does not push this device a cluster update about its
/// own playing, so there is nothing to read from the outside: this comes from
/// the Connect state itself, which is the same thing the other devices are
/// shown.
pub fn playing_here() -> EngineResult<String> {
    // Without a device there is no Connect state to read, and no other client
    // that could have driven this device somewhere the app does not know
    // about — which is the only reason this call exists. An empty answer is
    // the honest one, and it is a shape the caller already handles.
    let Some(playing) = with_engine(|engine| engine.spirc.as_ref().map(Spirc::playing))? else {
        return Ok("{\"contextUri\":\"\",\"trackUri\":\"\",\"index\":0,\"videoId\":\"\",\"tracks\":[]}".to_string());
    };
    let escape = |value: &str| serde_json::to_string(value).unwrap_or_else(|_| "\"\"".into());
    let tracks: Vec<String> = playing.tracks.iter().map(|uri| escape(uri)).collect();
    // `videoId` rides along rather than getting a call of its own: it changes
    // exactly when the track does, and this is already the answer to "what is
    // this device playing". Empty for everything without a music video, which
    // is almost everything.
    Ok(format!(
        "{{\"contextUri\":{},\"trackUri\":{},\"index\":{},\"videoId\":{},\"tracks\":[{}]}}",
        escape(&playing.context_uri),
        escape(&playing.track_uri),
        playing.index,
        escape(&playing.video_id),
        tracks.join(","),
    ))
}

pub fn play() -> EngineResult<()> {
    // Nothing happens here while the music is somewhere else. A play that
    // arrives then is Android's, not the listener's: focus coming back, a
    // service waking. The listener's own play is sent to the device that is
    // actually playing, by the app, and never reaches this.
    if elsewhere_active() {
        log::info!("play: another device has playback, ignoring");
        return Ok(());
    }
    // A device that is there but does not know the track is told first, so
    // the play goes through it and the account sees the song. When it cannot
    // be told — not listening yet, nothing loaded — the player is played
    // directly, which is what the listener asked for.
    if CONNECTED.load(Ordering::SeqCst) && !SPIRC_KNOWS.load(Ordering::SeqCst) {
        if let Err(e) = adopt_now() {
            log::info!("play: {e}; playing directly");
        }
    }
    transport("play", |e| e.spirc()?.play(), |e| e.player.play())
}

pub fn pause() -> EngineResult<()> {
    // The sound stops here, before anything is asked of the network.
    //
    // A pause is a message to the Connect task, and that task also talks to
    // Spotify: while it is waiting on a request it does not read its own
    // channel, so on a weak signal the music went on playing for seconds after
    // the button. The player is the thing making the noise and it is right
    // here, so it is told first and the account is told whenever it can be.
    // Pausing a player that is already paused, or one that was never playing
    // because the music is on another device, costs nothing.
    let _ = with_engine(|engine| engine.player.pause());

    transport("pause", |e| e.spirc()?.pause(), |e| e.player.pause())
}

pub fn stop() -> EngineResult<()> {
    // Disconnecting rather than stopping the player: it tells the account this
    // device has given up playback, which is what makes it disappear from the
    // device list instead of lingering as a paused phantom. The fallback does
    // stop the player, because a stop that leaves audio running is not a stop.
    let result = transport("stop", |e| e.spirc()?.disconnect(true), |e| e.player.stop());
    // Whatever the device believed about the queue, it is not playing it now.
    SPIRC_KNOWS.store(false, Ordering::SeqCst);
    result
}

pub fn seek(position_ms: u32) -> EngineResult<()> {
    if is_adopted() {
        with_engine(|e| e.spirc()?.set_position_ms(position_ms))?
            .map_err(|e| format!("seek failed: {e}"))
    } else {
        with_engine(|e| e.player.seek(position_ms))
    }
}

// Skipping is a Spirc command like the rest, and it was the one left out of
// [`transport`]: with the device gone it was accepted, discarded, and the track
// that was already playing carried on. Stopping is the fallback because there
// is nothing better available here. The player owns no queue, so it cannot pick
// the next track itself, and going on with the current one is the one answer
// that is certainly wrong: the listener asked for something else. The app
// does not send these without a device that knows the track — it pushes the
// queue instead — so the fallback is for a device that dies between the two.
pub fn next() -> EngineResult<()> {
    let before = current_uri();
    leaving();
    let through_device = is_adopted() && !elsewhere_active();
    transport("next", |e| e.spirc()?.next(), |e| e.player.stop())?;
    if through_device {
        watch_skip(before, true);
    }
    Ok(())
}

pub fn previous() -> EngineResult<()> {
    let before = current_uri();
    leaving();
    let through_device = is_adopted() && !elsewhere_active();
    transport("previous", |e| e.spirc()?.prev(), |e| e.player.stop())?;
    if through_device {
        watch_skip(before, false);
    }
    Ok(())
}

/// Shuts the output until the track being played is a different one.
///
/// A skip is a message to the Connect device and the music changes about a
/// second later; the old track is decoded for the whole of that second, and the
/// fade the app wraps a skip in is long over by then. That was the previous song
/// coming back at full volume before the new one cut in.
fn leaving() {
    let current = current_uri();
    if !current.is_empty() {
        shut(Gate::Leave(current));
    }
}

/// Republishes what is playing as the context it came from.
///
/// Only useful in one moment, just before playback is handed to another device.
///
/// A queue that is not a playlist in its own order goes to the engine as a bare
/// list of URIs, and librespot has to invent a context for it: `spotify:web-api`,
/// which is what the logs call `type: Default`. That is fine here, where the
/// order on screen is the order that plays, and useless to anyone else: a device
/// receiving the handover gets a context it cannot resolve, so its bar moves and
/// nothing comes out.
///
/// So the same music is loaded once more as the real playlist, at the same track
/// and the same second, and only then is the handover sent. The cost is that a
/// shuffled queue continues over there in Spotify's order rather than in the one
/// that was on screen: the alternative is handing over something that does not
/// play at all.
pub fn publish_context(position_ms: u32) -> EngineResult<bool> {
    let current = current_uri();
    if current.is_empty() {
        return Ok(false);
    }

    // Nothing to do in the ordinary case, which is what makes a handover quick.
    //
    // Loads now carry the playlist they came from even when the order is this
    // app's own, so the state is already something another device can resolve.
    // Reloading anyway cost a restart of the audio and a wait, on every single
    // change of device, to republish what was published already.
    if !current_context().is_empty() && is_adopted() {
        return Ok(false);
    }

    // The track itself, since there is no playlist behind this queue.
    //
    // A track is a context: it is what the official client publishes when a
    // single song is played out of a search, and unlike `spotify:web-api` it is
    // something the other end can resolve. Without this a queue with no
    // playlist behind it, which is most of what a search produces, handed over
    // as a context that answers 400 and left the receiving device with nothing
    // to play.
    let context = {
        let known = current_context();
        if known.is_empty() { current.clone() } else { known }
    };

    log::info!("republishing {current} as {context} before handing over");
    let request = LoadRequest::from_context_uri(
        context,
        LoadRequestOptions {
            start_playing: true,
            seek_to: position_ms,
            playing_track: Some(PlayingTrack::Uri(current)),
            ..LoadRequestOptions::default()
        },
    );

    with_engine(|e| {
        e.spirc()?.activate()?;
        e.spirc()?.load(request)
    })?
    .map_err(|e| format!("could not republish the context: {e}"))?;
    SPIRC_KNOWS.store(true, Ordering::SeqCst);
    Ok(true)
}

/// Starts playing here, at the track and second another device was on.
///
/// The whole handover in one call, so it can happen before anything is
/// resolved. The app used to fetch the entire context first, which for a long
/// playlist is a second or two of nothing happening at all, with no way for the
/// listener to tell the request from a request that was lost. The queue on
/// screen catches up afterwards, from the state this load publishes.
pub fn resume_here(context_uri: &str, track_uri: &str, position_ms: u32) -> EngineResult<()> {
    if track_uri.is_empty() {
        return Err("nothing to resume".into());
    }

    let context = if context_uri.is_empty() {
        track_uri.to_string()
    } else {
        context_uri.to_string()
    };
    log::info!("resuming {track_uri} here, from {context} at {position_ms}ms");

    // Anything the previous device was decoding stays out until this track
    // starts; see PLAYBACK_ARMED.
    shut(Gate::Expect(track_uri.to_string()));

    let request = LoadRequest::from_context_uri(
        context,
        LoadRequestOptions {
            start_playing: true,
            seek_to: position_ms,
            playing_track: Some(PlayingTrack::Uri(track_uri.to_string())),
            ..LoadRequestOptions::default()
        },
    );

    with_engine(|e| {
        e.spirc()?.activate()?;
        e.spirc()?.load(request)
    })?
    .map_err(|e| format!("could not resume here: {e}"))?;
    SPIRC_KNOWS.store(true, Ordering::SeqCst);
    Ok(())
}

/// Takes the account's playback for this device.
///
/// The local half of the device picker. Choosing another device is a request to
/// the server, addressed from here to there; choosing this one cannot be, since
/// a command sent from a device to itself goes out to the access point and
/// comes back refused. Activating is the same thing done directly.
pub fn take_over() -> EngineResult<()> {
    with_engine(|e| e.spirc()?.activate())?.map_err(|e| format!("could not take over: {e}"))
}

/// Hands the device a new running order without touching what is playing.
///
/// The app owns the order — it draws its own shuffle — and when the listener
/// turns that on or off the list changes under a track that is still playing.
/// Reloading the queue would say the same thing at the cost of a gap in the
/// song, for a change that is only ever about what comes after it.
///
/// Without a device that knows the track there is nothing to hand it to, and
/// nothing lost: the order is kept here and travels with the adoption.
pub fn set_queue_order(uris: Vec<String>, index: u32) -> EngineResult<()> {
    if uris.is_empty() {
        return Err("empty queue".into());
    }
    let at = (index as usize).min(uris.len() - 1);
    let prev = uris[..at].to_vec();
    let next = uris[at + 1..].to_vec();

    if let Ok(mut stored) = QUEUE.lock() {
        *stored = uris;
    }

    if !is_adopted() {
        return Ok(());
    }
    with_engine(|e| e.spirc()?.set_queue_tracks(prev, next))?
        .map_err(|e| format!("queue order failed: {e}"))
}

/// Shuffle and repeat as the account holds them, so a load can put them back.
///
/// These belong to the listener, not to any one device: Spotify keeps them in
/// the Connect state and every client shows the same three buttons. The engine
/// has to keep a copy because loading a queue resets them — see the note beside
/// [`republish_options`] — and because a rebuilt session starts from nothing.
static SHUFFLE: AtomicBool = AtomicBool::new(false);
static REPEAT_CONTEXT: AtomicBool = AtomicBool::new(false);
static REPEAT_TRACK: AtomicBool = AtomicBool::new(false);

/// Says the options again, for a device that has just been made to forget them.
///
/// Only what is on. Everything is off after a load anyway, so saying so a second
/// time is a command the Connect task has to answer for no change at all — and
/// `repeat(false)` is not free: it refills the coming tracks from the context,
/// in the context's order, which is not the order the app is showing.
fn republish_options(spirc: &Spirc) -> Result<(), librespot_core::Error> {
    if SHUFFLE.load(Ordering::SeqCst) {
        spirc.shuffle(true)?;
    }
    if REPEAT_TRACK.load(Ordering::SeqCst) {
        spirc.repeat_track(true)?;
    }
    if REPEAT_CONTEXT.load(Ordering::SeqCst) {
        spirc.repeat(true)?;
    }
    Ok(())
}

/// Publishes shuffle to the account, without letting the device reorder for it.
///
/// The device carries the flag and the app carries the order; see the patch note
/// in native/vendor/README.md. Before that they both carried both, and the flag
/// had to be forced off on every load to keep the two orders in step — which is
/// why the account showed this phone as never shuffling, and why shuffle turned
/// on elsewhere never arrived.
///
/// Kept here whatever the device's state, and said to it when there is one:
/// without a device the flag travels with the next adoption or load.
pub fn set_shuffle(shuffle: bool) -> EngineResult<()> {
    SHUFFLE.store(shuffle, Ordering::SeqCst);
    if !is_adopted() {
        return Ok(());
    }
    with_engine(|e| e.spirc()?.shuffle(shuffle))?.map_err(|e| format!("shuffle failed: {e}"))
}

/// `repeat_track` takes precedence: the two are separate flags in the protocol.
pub fn set_repeat(repeat_context: bool, repeat_track: bool) -> EngineResult<()> {
    REPEAT_CONTEXT.store(repeat_context, Ordering::SeqCst);
    REPEAT_TRACK.store(repeat_track, Ordering::SeqCst);
    if !is_adopted() {
        return Ok(());
    }
    with_engine(|e| {
        e.spirc()?.repeat_track(repeat_track)?;
        e.spirc()?.repeat(repeat_context)
    })?
    .map_err(|e| format!("repeat failed: {e}"))
}

/// `volume` is the raw 0..=65535 range librespot uses.
pub fn set_volume(volume: u16) -> EngineResult<()> {
    with_engine(|e| e.mixer.set_volume(volume))
}

pub fn volume() -> EngineResult<u16> {
    with_engine(|e| e.mixer.volume())
}

/// Whether the Connect device is gone.
///
/// What the Kotlin side reads before nudging a reconnection. There is no
/// device while the first handshake is being made, after a session has died
/// and until the next one lands, and with no network at all; the nudge is
/// harmless in every one of those, since [`reconnect`] refuses to double an
/// attempt in flight and waits out a backoff nobody forced. An invalid
/// session under a device that still exists counts too: the task will end a
/// moment later, and the answer is the same either way.
pub fn spirc_lost() -> bool {
    !CONNECTED.load(Ordering::SeqCst) || session_invalid().unwrap_or(false)
}

/// Whether there is a session to read the catalogue through.
pub fn is_connected() -> bool {
    CONNECTED.load(Ordering::SeqCst) && session_invalid().map(|bad| !bad) == Some(true)
}

/// Whether the current session has been invalidated, or `None` if asking would
/// have meant waiting.
fn session_invalid() -> Option<bool> {
    let guard = ENGINE.try_lock().ok()?;
    let engine = guard.as_ref()?;
    Some(engine.session.is_invalid())
}

/// Tear the engine down. Safe to call when it was never started.
pub fn shutdown() {
    let taken = ENGINE.lock().ok().and_then(|mut g| g.take());
    if let Some(engine) = taken {
        // Nothing may report against this player from here on, and the pump is
        // about to go with the sender. A device task ending late says nothing.
        GENERATION.fetch_add(1, Ordering::SeqCst);
        DEVICE_GENERATION.fetch_add(1, Ordering::SeqCst);
        CONNECTED.store(false, Ordering::SeqCst);
        SPIRC_KNOWS.store(false, Ordering::SeqCst);
        RECONNECTING.store(false, Ordering::SeqCst);
        OFFLINE.store(false, Ordering::SeqCst);
        if let Some(up) = SESSION_UP.get() {
            let _ = up.send(false);
        }
        // Told to the account before the socket goes: a device that vanishes
        // without disconnecting stays in the user's list until it times out.
        if let Some(spirc) = engine.spirc.as_ref() {
            spirc.set_cluster_listener(None);
            let _ = spirc.disconnect(true);
            let _ = spirc.shutdown();
        }
        engine.player.stop();
        engine.session.shutdown();
        crate::remote::clear();
        // Dropped so the pump thread's loop ends and the listener is released
        // while that thread is still attached to the JVM.
        drop(engine.events_tx);
        crate::sink::clear_output();
        // Dropping the runtime from a JNI thread is fine: no tokio context here.
        engine.rt.shutdown_timeout(Duration::from_secs(2));
    }
}
