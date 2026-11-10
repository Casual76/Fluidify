# Local listening history and Spotify observations

Implemented independently of the existing twenty distinct recent songs. Existing recent-feed calls
remain compatible (`recentlyPlayed()` still defaults to 30; `before` is optional). No old recent
entries are promoted to measured history. No version bump, engine changes, Rust reporting changes,
YouTube history, notifications, background imports, AI bridge, provider calls, or publication.

## Selective upstream attribution

Algorithms and behavioral regressions are adapted from Lelonio/Square, pinned revision
[8598e13347b9c20153898f2481083c3ffcd24fb2](https://github.com/Lelonio/Square/tree/8598e13347b9c20153898f2481083c3ffcd24fb2),
history introduced in [ba6657596b94470b7ff3e5c23dd9827c1f32ad71](https://github.com/Lelonio/Square/commit/ba6657596b94470b7ff3e5c23dd9827c1f32ad71).
License: GNU GPL version 3; see the existing root `LICENSE`. New implementation files carry SPDX
notices. Source files inspected at that exact raw GitHub revision:

- `app/src/main/java/dev/lelonio/square/data/ListeningHistory.kt`: elapsed-time sampling,
  qualification threshold, one-to-one reconciliation within 15 seconds, deterministic summaries.
- `ListeningStore.kt` in that data package: ownership and checkpoint semantics inspected; its
  SharedPreferences event JSON and destructive logout/account-switch behavior are intentionally
  replaced by indexed Room storage and immediate revocable account access.
- `app/src/main/java/dev/lelonio/square/playback/PlaybackService.kt`: service-owned acquisition,
  remote exclusion and periodic persistence adapted to this app's player lifetime.
- `app/src/main/java/dev/lelonio/square/ui/MainViewModel.kt`: up to five 50-item pages using the oldest
  `played_at` as `before`; replaced automatic syncing with explicit user action and coverage receipts.
- `app/src/test/java/dev/lelonio/square/data/ListeningHistoryTest.kt`: pause, qualification, imported
  unknown duration, time boundaries, deduplication and import-before-local-flush regressions adapted.

No upstream UI/player/backend merge was performed.

## Acquisition, identity and persistence

`SquareApplication.listeningHistory` owns `listening-history.db` (standalone Room schema v1).
Room 2.8.5 and KSP 2.3.13 are version-catalog dependencies; schema export is configured in
`app/schemas`. Schema generated and reviewed during authorized validation on 2026-11-07: app/schemas/dev.lelonio.square.data.ListeningDatabase/1.json. Three additive tables use service/account composite keys, indexed observation uniqueness, nullable imported duration, no destructive migration fallback. There is
no destructive migration fallback. History preferences contain controls, the last connected identity and a persistent revocation fence
only; individual events, intervals and import attempts live in indexed tables.

`ListeningRecorder` is owned by the actual `PlaybackService`. It follows player replacements and
listens for occurrence transitions, state changes and seeks. Repeat-one transitions close an
occurrence even when its URI remains identical. Monotonic elapsed time is sampled every second,
persisted every five seconds and on state/boundary changes. Paused/buffering samples contribute no
duration; seek position is never an elapsed-time input. Gaps over two seconds are discarded rather
than extrapolated. Qualification matches upstream: half the nominal duration capped at 240,000 ms,
or 30,000 ms for unknown duration. Unlike upstream's capped long gaps, unobserved long gaps add zero.
`LibrespotPlayer.isRemotePlayback` exposes existing Connect state solely for excluding remote audio.

Each occurrence has a UUID and each measured interval a composite primary key. Duplicate checkpoint
writes cannot duplicate duration; older snapshots cannot shrink it. Measurement intervals let queries
split duration at arbitrary half-open time boundaries, including month boundaries in a caller's zone.
S1 correction R1 adds an explicit playback occurrence handoff (source-only, validation pending).
After recreation, only a token explicitly restored with the saved queue and a matching persistent
history access fence may resume its exact Room checkpoint. Monotonic origin is reset, downtime adds
zero, and existing intervals remain in Room. Queues without this handoff start a distinct occurrence.
Up to the last five seconds can still be lost in an abrupt kill. There is no claim of continuous coverage.

History identity comes from the authenticated native engine's canonical Spotify username, never a
display name. The last authenticated identity can reconnect on service restart only while Spotify
credentials still exist. Login replacement and logout revoke history access immediately. Account
switches retain both accounts' database rows. Recording changes and deletion rotate the generation,
rejecting queued old checkpoints/imports. Explicit deletion removes only that account's events,
intervals and coverage. Logout removes the connected identity, never the historical rows. Recording
defaults on and is configurable per account; manual imports remain available when recording is off.

## Query contract for the later signed bridge

All repository methods require a current `HistoryAccess` from `session.value.access`. This binds both
service/account and revocation generation. A stale access fails; it does not return a false empty list.
The later signed bridge must authorize callers independently and must never accept an arbitrary
caller-provided account as a reason to call `connect`.

- `records(access, from, until, limit=100, offset=0)` returns deterministic descending timestamp/ID
  pages (limit 1..500), a next offset and recent coverage attempts. Events include track metadata,
  occurrence ID, nullable measured duration, qualification, Spotify observation timestamp and explicit
  `provenance`. Offset pagination assumes a stable dataset between calls; refreshes can shift pages.
- `aggregate(access, from, until)` returns deterministic counted plays, local qualified occurrences,
  imported-only observations, measured local milliseconds, track and artist counts, coverage and
  `complete=false`. Imported duration is always null. Reconciled records count once as local measured
  occurrences and retain Spotify provenance. Count time is the Spotify timestamp if reconciled,
  otherwise occurrence start. Duration uses actual overlapping intervals, regardless of qualification.
- `coverage(access, limit=100, offset=0)` pages retained import receipts; query results embed only the
  twenty latest receipts. Use the dedicated pagination API for a full audit.
- `record`, `importPage`, `saveCoverage` are acquisition boundaries; `deleteAccount` is the explicit
  destructive control. Calls are serialized and Room transactions keep reconciliation atomic.

Filters are epoch milliseconds `[from, until)`. Callers derive zoned month boundaries with
`YearMonth.atDay(1).atStartOfDay(zone)` and the following month, never fixed 30-day approximations.

`SpotifyHistoryImport.refresh` first verifies that `api.me().id` matches the engine account (the Web
API can be configured separately). Production manual import now pins a single Web API token and checks both repository access and OAuth generation before/after source responses, with no native token fallback. A regression replaces OAuth during a returned page and verifies that no foreign rows persist. The settings change is limited to constructing this authenticated importer. It requests at most 5 x 50 observations, preserves successful
pages on later failure, deduplicates song/timestamp pairs and reconciles each observation with at
most one local occurrence in the +/-15-second window. Imported-only duration remains unknown.
Receipts include requested and observed ranges, source, import time, timestamp precision, successful
pages, truncation, missing/malformed pages, error category and explicit incomplete status. Empty,
error and partial outcomes differ. Errors never persist token/response content. Spotify's recent
endpoint is never represented as a complete monthly archive.

## Validation handoff

The source wave was initially uncompiled; authorized serialized validation followed on 2026-11-07 (results below). No device or live API calls. Affected tests:
`ListeningHistoryTest`, `ListeningRepositoryTest`, `SpotifyHistoryImportTest`, `ListeningRecorderTest`.
The tests exercise actual Room reconciliation, account revocation, import failures/pagination and
service-recorder callbacks in addition to the pure sampler.

Run serially from `C:\VibeCoded Projects\Fluidify` after the shared runner is available:

```powershell
.\gradlew.bat --no-daemon --no-watch-fs --max-workers=1 :app:compileDebugKotlin :app:compileDebugUnitTestKotlin
.\gradlew.bat --no-daemon --no-watch-fs --max-workers=1 :app:testDebugUnitTest --tests 'dev.lelonio.square.data.ListeningHistoryTest' --tests 'dev.lelonio.square.data.ListeningRepositoryTest' --tests 'dev.lelonio.square.data.SpotifyHistoryImportTest' --tests 'dev.lelonio.square.data.ListeningRecorderTest'
```

Compilation resolved KSP/Room and generated the database implementation/schema. Equal-duration stale checkpoints now preserve the maximum endedAt and existing qualification; lower-duration checkpoints remain rejected. Runtime UI and
Connect handoff validation remain for a later authorized device checkpoint. The parent still owns
the signed AI bridge integration. README, engine submodule and native Rust/reporting remain untouched.


## Authorized validation, 2026-11-07

All Gradle invocations were serialized with `--no-daemon --no-watch-fs --max-workers=1`,
plus `--no-build-cache '-Pkotlin.compiler.execution.strategy=in-process' '-Pkotlin.incremental=false'`.
No output roots changed and no broad clean or daemon shutdown was used.

Final affected app results: **38 tests, 0 failures/errors/skips**:
ListeningHistoryTest 5; ListeningRepositoryTest 8; SpotifyHistoryImportTest 4;
ListeningRecorderTest 1; BridgeStateTest 9; FluidifyToolValidationTest 5;
AiToolHostProviderGuardTest 6. File-backed Room reopening, isolated in-memory databases,
unknown imported durations, account revocation/retention, equal-duration stale checkpoints,
OAuth replacement mid-page, durable SharedPreferences ledger recreation, payload conflicts,
lost response/no replay, supplied identity precedence/conflicts and confirmation/signature guards passed.
These are local JVM/Robolectric checks, not actual device/process-kill/Spotify evidence.

Engine results: engine-ai **242 passed**, engine-ai-bridge **24 passed**, no failures/errors/skips.
The final engine-ai task reused unchanged passing outputs; bridge tests reran after its identity patch.
`engine-doctor.ps1 -AppRoot .` passed; HEAD a055257fc4b559bee54b9ce114f265bcd237adfd,
ENGINE_VERSION and EngineBuild.VERSION remain 3.0.5. Its local-patch notice is expected.
Room schema v1 was generated and reviewed: three account/service-keyed tables, five indexes,
unique service/account/song/Spotify timestamp, nullable measuredMs/spotifyAt, no destructive fallback.

Initial lint found 13 scoped MissingTranslation errors for delivered history strings. Corrected with
13 real translations in each existing de/es/fr/hi/it/pt/tr locale (no suppression/baseline); also corrected
the delivered `Importing?` label to an ellipsis. No unrelated blocking source/compiler errors observed.
Existing AGP/compileSdk 37 support, SDK cmdline-tools location, deprecation and other lint warnings remain
visible; no unrelated player/theme/README code was changed by this validation implementation.

One retry failed at resource processing because another concurrent Fluidify Gradle test/assemble process
held R.jar. Preserved `docs/validation-file-lock.log`, waited for that run to end, then retried successfully
through resource processing. No other process was stopped. Exclusive runner ownership was temporarily
violated externally; the implementation itself never ran overlapping Gradle invocations.

Logs: `docs/validation-compile.log` (initial compile), `docs/validation-engine-tests.log` (initial engine suites),
`docs/validation-history-bridge-tests.log` (initial 37 affected tests), `docs/validation-assemble-lint.log`
(initial assemble and translation lint failure), `docs/validation-final.log` (final source validation),
`docs/validation-engine-doctor.log`, `docs/validation-diff-check.log` and `docs/validation-file-lock.log`.
Lint artifacts: `app/build/reports/lint-results-debug.{html,txt,xml}`.

Actual Spotify/device runtime, same-signer installed cross-app behavior, provider execution and process kill
remain unverified. No live API/device calls, secrets, commits, push or publication were performed.
PampAI and ClasseViva sources were never edited.


Final build result: `:app:compileDebugKotlin`, `:app:compileDebugUnitTestKotlin`, affected tests,
`:app:assembleDebug` and `:app:lintDebug` **PASS** (`docs/validation-final.log`, BUILD SUCCESSFUL).
Final lint: **0 errors, 213 warnings, 6 hints**. The 13 initial translation errors are resolved;
remaining warnings/hints are visible in the full report (including UseKtx, unused resources and dependency updates).
Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (69,806,723 bytes); not installed or published.
Focused final bridge test log: `docs/validation-bridge-final-tests.log`; the malformed-argument test
explicitly supplies parent execution identity, preserving validation coverage after the legacy write gate.

Final focused bridge rerun: **24 passed**, no failures/errors/skips. All owned Gradle processes have finished; runner released.


## Source2 S1 correction R1/3 - validated local handoff

F1/F3 source corrections are compiled and locally tested after root's explicit exclusive-runner
transfer. Final-source data/bridge app tests: **48 passed / 11 XMLs**; full core tests: **18 passed /
4 XMLs**, zero failures/errors/skips. Seven new occurrence/restore regressions passed, including native
subduration end/restart, real Media3 automatic callback coexistence, duplicate URI, exact saved playback
identity, 20s + 20s qualification with zero downtime, revocation and missing/mismatched checkpoint fallback.

The full app suite was attempted on Java 21: **178 tests, 177 passed, one failure**, zero errors/skips.
AudioLightScreenshots.compactMiniPlayer fails its mini-player light assertion; its UI/source/assertions
were left untouched. This broader attempt predates the final cancellation guard; final focused tests
cover the current source. Do not claim a green full app suite. Initial Java 17 full attempt failed five
SDK 36 sandbox initializations; rerunning on Android Studio JBR 21.0.10 resolved those environment errors.

`:app:assembleDebug` and `:app:lintDebug` **PASS**, with app lint 0 errors / 214 warnings / 6 hints.
Additional `:core:lintDebug` fails three UnsafeOptInUsageError findings in untouched AudioReactive.kt:428,
PlayQueue.kt:404 and QueueItems.kt:43 (plus 9 warnings); no suppressions or baseline were added.
The combined assemble/lint invocation therefore exits 1 even though assembly and app lint passed.
Logs, exact commands, class counts, source hashes and limitations: `docs/listening-history-S1-R1.md`.
These executions are separate from the historical results above; counts must not be added together.

The producer's PlaybackOccurrence mapper closes on native end_of_track and creates a new token on real
playing or explicit queue selection. Loading/prefetch, position and pause/resume do not manufacture
boundaries. Recorder consumes the token rather than independently closing again on Media3 callbacks.
SavedPlayback persists optional token and history-continuity fence. Actual queue restoration must match
that fence before exact account-gated checkpoint lookup; no URI-only or temporal coalescing. Missing or
mismatched checkpoint creates a new token. Coroutine cancellation fences late restore completion.
Monotonic origin is empty after restoration, so downtime contributes zero. Existing Room intervals remain
and new intervals append idempotently. Logout/account change, recording controls and deletion rotate the
persistent fence; queue saving reconciles recorder access before capturing the token/fence handoff.

Room schema remains v1 and matches its frozen S1 hash; no destructive migration, table or column change.
Old SavedPlayback JSON remains readable without inventing continuity. The engine remains 3.0.5/a055257;
Rust Spotify reporting and GPL attribution are preserved. No devices/provider/Spotify calls, secret file
inspection, commits, push, publication or other repository writes. Unrelated player/UI/theme/README
sources were not changed. Fixtures do not prove real Spotify, installed MediaSession/Binder or actual
process-death behavior; abrupt kills may still lose the latest unsaved checkpoint/queue boundary.

**Runner RELEASED** after all four owned invocations ended. No owned Gradle work remains active.

### Exact R1 source manifest (SHA-256)

- `core/src/main/java/dev/lelonio/square/playback/PlaybackOccurrence.kt`
  `4f8143ebfc6687fb589cd7ce5a3a623a88c8dadb95d1969a924fa6c538d775d6`
- `core/src/main/java/dev/lelonio/square/playback/LibrespotPlayer.kt`
  `75dc1c7e1c2b4f226ce42554ec70e1524c4fa26e112fdbd8cd0d3969b7d78893`
- `app/src/main/java/dev/lelonio/square/data/ListeningHistory.kt`
  `b8c3b328593e3dad7dace8e79ef46537fabb3afd06cfb4db0d5358e798c2ff20`
- `app/src/main/java/dev/lelonio/square/data/ListeningRepository.kt`
  `f4ac329f34dd2e676959cc1765f124d17cd073cb0c17132e867d93eaa3f6b6eb`
- `app/src/main/java/dev/lelonio/square/data/PlaybackStore.kt`
  `279def8e1eeb13b396436e8bbf800c903442d69cef48eb2c61db45a34d92d4ad`
- `app/src/main/java/dev/lelonio/square/playback/ListeningRecorder.kt`
  `abf1e3202cfd72e2de96a465d87fd9ca8c9127e5ce2b61a5533f5879ebdc1fa2`
- `app/src/main/java/dev/lelonio/square/playback/PlaybackService.kt`
  `d12ea3ce5e47b6f494370fc35e35cf687bffc6067abf00815e39075ad5a5b2d1`
- `app/src/test/java/dev/lelonio/square/data/NativeListeningOccurrenceTest.kt`
  `5c6dc2330ef06357349bdffc07bffc818ce55ced97812eb01fdf8eb9b80c9d74`
- `app/src/test/java/dev/lelonio/square/data/ListeningMedia3OccurrenceTest.kt`
  `4be9d577ba76f9c17a8015c384c2ab3ed995e7dd1da7cf01fabe306251d36942`
- `app/src/test/java/dev/lelonio/square/data/ListeningOccurrenceRestoreTest.kt`
  `fded36f82e641c9166a6b3289229482e59eef885954229f95a8b1734b57ee318`

## Source2 S2 correction R2/3 - owned restore integration completed locally

Same R2 assignment. Root identified that production saved restore itself loads a fresh native request,
so the earlier blanket fail-closed rule did not prove resumable history. Exact SavedPlayback/lease
continuity now travels as a one-command nonce through JNI, direct/Connect load commands and an explicit
player-generated request-ID acknowledgement. Only that request can resume its exact checkpoint;
untagged/sameURI/remote/revoked loads cannot reuse it. Narrow vendor extension attributed in
native/vendor/README.md; old untagged load APIs and Spotify reporting remain compatible.

Fresh app63/core18 plus3 native binding tests PASS; production LibrespotPlayer restore/handover/queue/
event-parser fixtures cover owned20+20 as one40s qualified row, competing sameURI loads, remote ack,
foreign nonce and revocation with old-row preservation. Fragment recoverability and reindex tests pass.
Three native ABIs rebuilt; shipped arm64/x86_64 JNI libraries match the new outputs byte-for-byte.
Assembly and app lint pass; standalone core lint retains the same3 errors, so combined exit1. Earlier
bridge26 is prior unchanged-source evidence, not another fresh run. No installed/device/gate claim.
Exact17 source hashes, commands/counts/ABI+APK hashes, limits and RUNNER RELEASE:
docs/listening-history-S2-R2.md and validation-S2-R2-restore-* artifacts. No S3 freeze/review initiated.
