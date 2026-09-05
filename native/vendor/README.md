# Vendored dependencies

## librespot-core

Copy of [`librespot-core` 0.8.0](https://github.com/librespot-org/librespot)
(MIT, licence retained), wired in through `[patch.crates-io]` in
`native/Cargo.toml` so that `librespot-playback` and `librespot-metadata`
resolve to this copy as well.

### The patches

#### 1. `src/mercury` — POST with header fields

Spotify builds the account's listening history from events posted to
`hm://event-service/v1/events`, and that request is a POST carrying
`Accept-Language` and `X-ClientTimeStamp` as Mercury header fields. Upstream's
Mercury client has neither: no POST method, and `MercuryRequest` has nowhere to
put a header field. Both were added — a `MercuryMethod::Post`, a `user_fields`
list on the request, and a `post()` on the manager.

The alternative was building the packet by hand and calling `send_packet`
directly, which works but throws the reply away: the sequence number would not
be in Mercury's pending table, so there is no way to see whether Spotify
accepted the event. On a format this app reverse-engineered, that answer is
worth the patch.

#### 2. `src/config.rs` — the advertised OS

One line:

```rust
-pub const OS: &str = std::env::consts::OS;
+pub const OS: &str = "linux";
```

#### Why

`OS` decides three things at once: the default client id, the `platform` field
librespot sends to Spotify, and the HTTP user agent. On Android the real value
makes librespot present itself as the Spotify Android app, while this client
authenticates with the desktop "keymaster" client id — the one whose OAuth flow
accepts a loopback redirect, and therefore the only one usable here.

Spotify checks that the client id and the advertised platform agree. They do not,
so the access point handshake succeeds and then every client-token and login5
request is refused with a generic `BAD_REQUEST`:

```
rootlist failed: Invalid state { Login request was denied: BAD_REQUEST }
```

The comment above `OS` upstream warns about exactly this: mocking a platform
requires the rest of the identity to match it. Pinning `"linux"` presents a
consistent desktop identity, which is what the desktop client id expects.

The same fix exists in the librespot fork behind
[Outify](https://github.com/iTomKo/Outify) — commit *"Fixed login5 errors by
mocking linux"* — which is how it was found.

#### 3. `src/spclient.rs` and `src/config.rs` — the language to answer in

Spotify localises the artwork of its generated playlists: the cover of "Your
All-Time Top Songs" is served from a URL ending in the language, and the
Italian one is a different picture from the English one. librespot sends no
`Accept-Language` at all, so everything came back English regardless of what
the app was set to.

A `language` field was added to `SessionConfig` (default `"en"`, so behaviour
for anyone not setting it is unchanged), and the spclient request builder now
sends it as `Accept-Language`. The app fills it from the device locale.

Not everything follows it: the charts, daylist, blend and seed-mix covers are
picked by the server from the account's own language and stay English.

#### 4. `src/session.rs` — a non-premium account is not a reason to exit

Upstream refuses to run on a free account, and does it by calling
`std::process::exit(1)` from inside `check_catalogue`, with a TODO of its own
saying it should log out instead:

```rust
-                // TODO: logout instead of exiting
-                exit(1);
```

That ends a command-line daemon tidily. Inside an Android app it takes the whole
process down: the service dies mid-login, Android restarts it, the same account
authenticates again, and the loop repeats with a growing backoff and no message
anywhere. From the outside the app simply vanishes.

The check now only logs, and `engine::start` reads the `type` attribute itself
and returns `premium account required`, which the service turns into a message
and a return to the login screen. The refusal is unchanged; what changed is that
it is an error the caller can handle rather than a process exit.

### Maintenance

Re-apply this patch when bumping `librespot-core`. If the whole file is replaced,
the marker to look for is `LOCAL PATCH` in `src/config.rs`.

## librespot-playback

Copy of [`librespot-playback` 0.8.0](https://github.com/librespot-org/librespot)
(MIT, licence retained), wired in the same way.

### The patch: crossfade

Upstream plays one track at a time, start to finish, and the next one begins
where the last one stopped. Overlapping them cannot be done from outside the
crate: the player owns the decoder and the sink, and nothing it exposes lets a
caller reach the samples of two tracks at once.

`PlayerConfig` gains `crossfade_duration_ms`, zero by default, which leaves
upstream behaviour exactly as it was.

When it is set, the player announces `EndOfTrack` a fade's length before the
track really ends. Whoever owns the queue answers with a load, as it always
does, and that load is what starts the fade: instead of dropping the decoder of
the track being replaced, `start_playback` hands it to `fade_out`, where it is
read for the length of the fade and then dropped. Nothing in the player decides
what plays next, so the Connect device stays the only thing that does.

The mix is equal power, `cos` out and `sin` in, one gain per frame. Two straight
ramps summed dip in the middle, where both tracks are at half; squared cosine
and sine sum to one, so the loudness holds across the overlap. Both sides carry
their own normalisation factor, since normalisation exists to make two masters
sound alike and a fade is the one moment both are heard. The result is clamped
to `32767/32768`, because full scale is asymmetric and a mix that reaches 1.0
wraps to the bottom and is heard as a click.

A track shorter than the fade simply drains early: the incoming track goes on
rising, over silence rather than over music.

### The patch: a slow connection is not a missing track

Upstream gives a load one attempt. If it fails, the player sends `Unavailable`,
and Connect answers that by skipping to the next track and dropping the failed
one out of the queue. That is right for a track the account cannot play, and
wrong for every other reason a load fails: on a weak signal each track failed in
turn, so the queue ran itself out in seconds without a note being heard, and the
songs it gave up on were gone from the queue for the rest of the session.

The loading state now retries the same track, three times, waiting 0.9s, 1.8s
and 3.6s, and sends a fresh `Loading` each time so the app can go on saying it
is working. `Unavailable` is only sent once all of them have failed, which
leaves its meaning intact for the case it was written for.

The counter and the position it retries from live on `PlayerInternal` and are
reset by `handle_command_load`, so every new load starts with a full set of
attempts.

### The patch: a failed head start is not a missing track

The player fetches the next track while the current one plays, and upstream
answers a failed fetch by telling Spirc the track is unavailable, which takes it
out of the queue. On a weak link that head start is the first thing to fail, and
it fails while the connection is busy carrying the song being listened to: songs
were vanishing from the queue before anything had tried to play them.

A failed preload now just forgets the head start. The track is loaded normally
when its turn comes, with the retries that path has.

### The patch: the quality can change without a new player

The bitrate is read in one place, while a track is being loaded, to put the
formats it exists in into order of preference. Upstream keeps it in the
player's immutable config all the same, so following a connection as it changes
meant building a new session, which costs a second of silence and a Connect
device that has to be handed the queue again.

`PlayerCommand::SetBitrate` and `Player::set_bitrate` change it in place, and
drop whatever was preloaded, since that was fetched at the old quality. What is
playing keeps the file it started with; the next track gets the new one.

### The patch: a refused key ends the load

Spotify hands out a decryption key per file, and it refuses them when it is
throttling a session — which says nothing about the track being asked for.
Upstream then loads the file anyway, without a key, on the grounds that a file
which turns out to be unencrypted still plays and an encrypted one will simply
fail to parse.

What that failure looks like from the outside is the whole queue flicking past
in silence: the decoder dies on the first frame, the track "ends" in half a
second, the engine moves to the next one, and that one is refused too. Nothing
in the log says the word key.

A refusal or a timeout now ends the load, which puts it on the retry path with
the rest, and says so if it still cannot be played. Any other key failure keeps
upstream's behaviour, since a genuinely unencrypted file has to go on playing.
See also the retry in `librespot-core`'s `audio_key.rs`, which is what tries a
refused key again before it gets this far.

### The patch: the crossfade can change without a new player

The same shape as the bitrate above. The length of the dissolve is read at the
two moments it matters — when a track's end is announced early, and when the
outgoing decoder is mixed under the incoming one — and both read the live
configuration, so there was never a reason for it to be fixed. With it the
last setting that needed a new session is gone: `PlayerCommand::SetCrossfade`
and `Player::set_crossfade` change it in place. The track playing keeps the
fade it has already begun, if any; the next transition uses the new one.

### The patch: a streamed load waits for the session

The player now outlives the session it streams through (see the engine), so a
track can be asked for while the access point is still being reached. A load
that went to the network in that window failed at once, was retried a few
times over the next seconds, and was then declared unavailable — for a song
that would have played perfectly a moment later.

`PlayerConfig` gains `session_ready`, a closure the owner of the player fills
in: the loader awaits it before anything touches the network, and takes the
session it answers with, which is the connected one by then. A downloaded
track never reaches it, because it is found on disk before the network is
thought about. `None`, the default, is upstream behaviour.

### Maintenance

The markers are `LOCAL PATCH` in `src/player.rs` and `src/config.rs`.

## librespot-connect

Copy of [`librespot-connect` 0.8.0](https://github.com/librespot-org/librespot)
(MIT, licence retained), wired in the same way.

### The patch: is this device the one playing?

`ConnectState` knows, and nothing outside the event loop could ask. `Spirc` now
carries an `AtomicBool` that the loop republishes every turn, and a
`Spirc::is_active()` that reads it.

The alternative was reading the cluster, which is a different question wearing
the same clothes. When another client hands playback to this device, Spotify
sends the transfer and this device starts playing immediately, but the account
goes on naming the previous device as active for as long as that device takes to
let go. A web player holds on for tens of seconds. Every guard in the engine that
asked the cluster therefore got "somebody else is playing" while this phone was
the one making the sound, and refused to do its job.

go-librespot keeps the same answer as a single boolean it owns and gates
everything on it; this is that boolean, borrowed back out of librespot.

### The patch: what is this device playing?

The same shape one step further in. `Spirc` also carries a `PlayingHere` behind
a mutex — context uri, track uri, and the previous, current and coming tracks in
playing order — republished by the loop alongside the flag above.

Another client can point this device anywhere: a track from a playlist the app
never opened, with no transfer to announce it. Nothing arrives from outside
either, because the account does not send a device the cluster update that
describes that device, so the owner of the handle was left showing whatever it
believed before.

The track list is there for the contexts Spotify makes rather than stores. A
daily mix, a radio, "Pop Mix" resolve to nothing for a client that is not
Spotify's own, and the only copy of that queue in existence here is the one the
account handed the device to play.

### The patch: a weak signal must not hold the buttons

The task's `select!` is the only thing reading the command channel, and one of
its branches tells the account what this device is doing, which is an HTTP
request. Awaiting it inline suspends the whole loop, so a pause sent from the
app sat unread until the request came back: on a weak signal, seconds of music
under a finger that had already asked for silence.

The state update is now bounded by `STATE_UPDATE_TIMEOUT`, one and a half
seconds, after which the loop goes back to reading commands and leaves the
update flagged so the next turn tries again. The account can be told late. The
listener cannot.

### The patch: a new running order, without a reload

`Spirc::set_queue_tracks(prev, next)` replaces what comes before and after the
current track and publishes the queue revision, leaving playback alone.

The app draws its own shuffle: the order on screen is the order the device is
given. Turning shuffle on or off therefore changes the list under a song that is
still playing, and upstream has only one way to say so — load the queue again,
which restarts the decoder. The listener heard a gap in the middle of the track
for a change that was only ever about the tracks after it.

`state::metadata` and its `Metadata` trait are `pub(crate)` for this, so a track
built in `spirc.rs` can be stamped with the context it belongs to.

### The patch: shuffle is the account's, the order is the app's

`SpircTask::handle_shuffle` now only writes the `shuffling_context` flag into the
published state. Upstream also reshuffles the context behind it, and that half is
gone: `ConnectState::handle_shuffle` is deleted from `state/handle.rs` rather
than left unused, so the file does not offer two ways to shuffle.

Shuffle arrives as two things at once — a flag the account keeps for every
client, and a permutation of the tracks — and only the first of them belongs to
this device. The app draws its own order, because it has to: it holds tracks the
account never sent it, a queued song among them, and it hands the result over
with `set_queue_tracks`. A device that shuffled as well produced two orders for
one queue, the screen followed one and the decoder the other, and a skip landed
on a song nobody could see. The engine used to answer that by forcing the flag
off before every load, which kept the orders in step at the cost of an account
that never knew this device was shuffling — and of a shuffle set on a laptop
never reaching the phone.

With the permutation dropped there is nothing left to disagree about. Turned on
here, the flag goes to the account and shows up on every other client; turned on
there, it arrives as `SetShufflingContext` and reaches the app as a shuffle event
to obey by reordering its own queue.

### The patch: the options a transfer brings with it

`handle_transfer` emits `ShuffleChanged` and `RepeatChanged` once
`handle_initial_transfer` has put the account's options into the state.

`handle_activate`, which runs a few lines earlier, already emits both — with the
values this device happened to be left with when it last played. On a handover
that is precisely the stale answer, and it was the only one the owner of the
handle ever heard: playback arrived from a laptop that was repeating a track and
the phone went on showing repeat off.

### The patch: waking up is not news about shuffle and repeat

`handle_activate` no longer emits `ShuffleChanged` and `RepeatChanged`.

What it emitted was the state this device happened to be left with, which on a
fresh session is the protocol's default of everything off. Activating is the
first thing a load does, so the app was told "shuffle off" a moment before it
handed over a queue it had shuffled itself, and the button turned itself off on
launch. Both are still announced everywhere they actually change — the three
handlers, and the transfer above, which is the one moment a device really does
learn them from somewhere else.

### The patch: the crossfade actually overlaps

The crossfade above only ever overlapped anything when the next track happened
to be sitting in `PlayerPreload::Ready`. `begin_fade_out` ran from
`start_playback`, which is a whole load later than the load command: on every
other path `handle_command_load` had by then put the player in
`PlayerState::Loading`, and replacing the state is what drops the decoder the
fade was going to read. So the song being left stopped dead a crossfade short of
its own end — the tail thrown away, not faded — the next one arrived after the
load's silence at full level, and `early_end` had been consumed on the way past,
so nothing downstream could tell. A fade-out that is a cut and a fade-in that is
not a fade: which is what a listener means by "it does not dissolve".

The outgoing decoder is now taken in `handle_command_load`, while the track it
belongs to is still the one playing, and only when the incoming track is a
different one — a track cannot overlap itself, and repeat-one has one decoder
between both ends.

That leaves the wait. The mix is driven by the incoming track's packets and
there are none until it opens, so `pump_fade_out` plays the outgoing track on
its own meanwhile, straight to the sink at the gain the curve is currently
holding. The curve does not advance while it does, so the incoming track still
enters from silence and still gets the whole overlap; what the wait costs comes
off the far end, where the outgoing track drains early because it only ever had
a crossfade's worth of music left.

Both halves also carry the gain reduction the dynamic limiter was applying when
they parted. Without it the outgoing track loses several decibels of ducking at
the exact instant the fade starts, so it jumps up before it begins coming down.

### The patch: the device adopts what the player is already playing

`Spirc::adopt(AdoptRequest)` describes a track the player is on — the queue,
the track's place in it, the player's own id for the load, the position, and
whether it is making sound — and the device takes it as its own without
loading it again.

The owner of the player drives the player directly whenever there is no
device to go through: while the first handshake is being made, after a session
died under a song, with no network at all. When a device is up again the song
is in progress, and loading it through the device would start it over. After
an adoption the events the player goes on sending match the play request the
device now expects, so the end of the track, the preload of the next one and
every command arrive as if the device had loaded the song itself. Activates
the device, like a load does.

### The patch: every state update is bounded, and its answer kept

The loop's `notify` was bounded once, at one call site, and unbounded at the
rest: a disconnect, a transfer, the first update after connecting. Each is an
HTTP request awaited inside the only loop that reads the command channel, and
on a weak signal each held the buttons for as long as the request took. The
timeout now lives inside `notify` itself, with a backoff that grows after each
timeout so a link that has just failed is not asked again at once; letting go
of playback is bounded the same way, and the first update after connecting has
a longer bound of its own, past which the loop ends and the owner builds
another session.

The answer to a state update is the account's picture of every device — the
same `Cluster` the dealer pushes when another device changes something — and
upstream read it once, on connecting, and threw the rest away. It now goes to
a listener the owner sets with `Spirc::set_cluster_listener`, on every update,
which is what keeps the picture right when a push was missed. And
`Spirc::refresh_cluster` asks for one outright, with the reason the official
client gives when its device picker opens.

### The patch: is the loop listening yet?

`Spirc::is_established` mirrors `connect_established`. The loop does not read
its command channel until the account has acknowledged the device, which is a
round trip over the dealer after the handshake; a command sent before that
waits in the channel, and the owner could not tell waiting from lost. It asks
this first and goes around the device while the answer is no.
