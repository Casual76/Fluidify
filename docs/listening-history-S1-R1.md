# Source2 DATA FOUNDATION S1 correction R1/3 - Fluidify

Status: F1/F3 source corrections compiled; final data/bridge and core regressions PASS.
Full app suite was also attempted: 178 tests, 177 passed, one out-of-scope mini-player light assertion
failed. This is not a full app green result, installed runtime approval or Source2 review approval.
Root explicitly transferred the sole runner after CV release on 2026-11-07. AssembleDebug and app lint PASS. Additional core lint reports three out-of-scope opt-in errors.
All owned invocations have exited and the sole runner is RELEASED (details below).

## Production invariants

- PlaybackOccurrence is the producer seam called directly by LibrespotPlayer.applyEvent. Native
  end_of_track closes the current token even at 59,750/60,000ms; next playing creates a fresh token.
  Loading alone never starts a new occurrence (it can be prefetch). Position/paused/playing of the same
  active slot preserve identity. Explicit queue selection creates another token even for the same URI.
  Native remote state clears the token; the recorder also fences remote state when sampling.
- Producer state changes precede invalidateState; notification follows it. Native end publishes before
  its early return. Recorder ignores Media3 transition closure for an authoritative native source, so
  automatically generated Media3 transitions cannot close the same native boundary twice. Other players
  retain the Media3 transition path. Queue/index/URI rendering and native Spotify reporting are unchanged.
- SavedPlayback carries optional listeningOccurrenceId and historyContinuity. The token is restored only
  by the actual saved-queue restore path with the current persistent history fence. A URI is never proof
  of continuity. Old JSON defaults both fields to null. Trimming away the active queue slot clears its token.
- Playback snapshot handoff first reconciles the recorder with current access/recording/remote state.
  Disconnect, account replacement, recording changes and deletion rotate a synchronously committed UUID
  fence. Reauthentication after revocation cannot reuse a stale saved token even for the same account.
- Exact account-gated local:<token> lookup restores measured duration/qualification; no temporal coalescing.
  Sampler restores with no last monotonic tick and no audible origin. Downtime contributes zero. Existing
  intervals stay in Room; new intervals are appended idempotently, not recreated from elapsed wall time.
  Lookup cancellation/token/access checks reject stale results. Seek still only resets monotonic origin.
- Room remains schema v1, no schema migration/destructive fallback. Existing rows and imports are retained.
  No engine changes; HEAD a055257fc4b559bee54b9ce114f265bcd237adfd, ENGINE_VERSION 3.0.5.
  GPL notices and upstream attribution remain. No Rust sources were edited.

## Executed regression coverage (final scoped run)

- NativeListeningOccurrenceTest (2): 59,750ms native end/restart with same URI, repeat-one and consecutive
  duplicate URI; buffering/remote exclusion; position/pause/resume/prefetch retain identity; explicit same
  URI new selection and remote-to-local create distinct identity. No manual Media3 transition calls.
- ListeningMedia3OccurrenceTest (1): fake native producer feeds the SAME production mapper and real
  Media3 SimpleBasePlayer state invalidation. Repeat-one with subduration position plus next duplicate
  URI exercise automatic Media3 callbacks; assert three qualified occurrences and 93,000 measured ms.
- ListeningOccurrenceRestoreTest (4): recorder/repository recreation and real PlaybackStore roundtrip,
  20s + one-hour downtime + 20s yields one exact 40s qualified occurrence and zero downtime intervals;
  same-URI explicit new occurrence distinct; logout/relogin/account/recording/deletion fence handoff;
  old SavedPlayback JSON and queue roundtrip preserved without inventing continuity;
  missing or mismatching exact checkpoint starts a new independent token, preserving the old row.
  Final restore completion checks coroutine cancellation before applying a checkpoint or new token;
  a missing current item clears the restored token until a real native playing event starts another.
- Existing ListeningHistoryTest/ListeningRecorderTest/ListeningRepositoryTest/SpotifyHistoryImportTest
  also passed for seek/pause/buffering/remote, reconciliation, monotonic gaps and retained database data.
  The older manual transition test is consumer coverage only, not evidence of producer emission.

## Static evidence and limits

Scoped git diff --check passed. Static whitespace/NUL/UTF-8 checks passed for all ten source files.
The exported Room schema matches its S1 manifest hash. Engine version/HEAD were read and unchanged.
No credential, local.properties, keystore, auth store, user data or secret file was inspected or printed by the agent.
No devices, Spotify/provider calls, child agents, commits, push or publication. No other repository writes.
Unrelated README, PlayerPanel, PlayerScreen, DevicePicker, player UI and theme files were not edited.
LibrespotPlayer and PlaybackService contain preserved preexisting changes; hashes below are full current
file hashes, not claims that their entire git diff belongs to this correction.

Fixtures do not prove real Spotify behavior, native engine/device timing, installed MediaSession continuity,
Binder signing or process-death execution. No installed runtime correctness or review approval is claimed.
A missing/older playback handoff, failed exact-row lookup, revoked fence or unsupported player begins
without restored measurement. Abrupt kill may still lose the latest unsaved samples/queue boundary;
this does not claim continuous history coverage. Restore intentionally adds no unobserved time.

## Exact source manifest (SHA-256)

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

Documentation changed: docs/listening-history-port.md and this report.

## Executed serialized validation, 2026-11-07

All four invocations ran sequentially from C:/VibeCoded Projects/Fluidify with:
`--no-daemon --no-watch-fs --max-workers=1 --no-build-cache
-Pkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false`.
No other runner/task was inspected or polled. No broad clean or process shutdown was used.

1. Initial full app/core request used the default Java 17. App compilation succeeded; app test XMLs
   report 144 tests with 5 class initialization failures because SDK 36 requires Java 21. This is an
   environment failure, not five source assertions. Core task was not reached. All initial six new
   regressions passed. Log: docs/validation-S1-R1-tests-initial.log.
2. Repeated full app/core request with process-local JAVA_HOME set to
   C:/Program Files/Android/Android Studio/jbr (OpenJDK 21.0.10). App XMLs: **178 tests, 177 passed,
   1 failure, 0 errors/skips**, across 34 classes. Core task was not reached after app failure.
   The one failure is AudioLightScreenshots.compactMiniPlayer at line 87:
   "The mini player must actually show and remove its light". The UI test/player/light source and
   its assertions were not edited. No baseline run proves when this failure originated; it is outside
   the authorized history correction scope and remains unresolved. Do not claim a green full suite.
   Log: docs/validation-S1-R1-tests-final.log (filename denotes the second full attempt, not a PASS).
   Exact class totals retained in docs/validation-S1-R1-full-tests-summary.json before scoped results
   overwrote the Gradle XML directory. This full run preceded the final restore cancellation guard.
3. Final-source scoped request on Java 21:
   `:app:testDebugUnitTest --tests dev.lelonio.square.data.* --tests dev.lelonio.square.bridge.*
   :core:testDebugUnitTest`.
   **App: 48 tests / 11 XMLs; core: 18 tests / 4 XMLs; zero failures/errors/skips.**
   Both test tasks executed, not UP-TO-DATE. App and core Kotlin test compilation succeeded.
   New occurrence/restore coverage is 7 tests: NativeListeningOccurrenceTest 2,
   ListeningMedia3OccurrenceTest 1, ListeningOccurrenceRestoreTest 4.
   Existing app classes: ListeningHistoryTest 5, ListeningRepositoryTest 8, ListeningRecorderTest 1,
   SpotifyHistoryImportTest 4, DownloadIndexPersistenceTest 3, AiToolHostProviderGuardTest 6,
   BridgeStateTest 9, FluidifyToolValidationTest 5. Core runs all four existing test classes.
   Log: docs/validation-S1-R1-scoped-tests.log; exact class totals:
   docs/validation-S1-R1-scoped-tests-summary.json. These are separate executions from the full attempt;
   do not add them to the earlier full count or to historical source validation counts.
4. Java 21 request: `:app:assembleDebug :app:lintDebug :core:lintDebug`.
   Log: docs/validation-S1-R1-assemble-lint.log. Final results and artifacts below.


Final build/lint outcome: `:app:assembleDebug` and `:app:lintDebug` **PASS**.
App lint: **0 errors, 214 warnings, 6 hints**; warnings remain visible, no suppression/baseline added.
The extra `:core:lintDebug` task **FAILED**, with **3 errors and 9 warnings**. All three are
UnsafeOptInUsageError in untouched source: AudioReactive.kt:428, PlayQueue.kt:404, QueueItems.kt:43.
They are outside this occurrence/restore correction and were not suppressed or modified. The combined
invocation exits 1 because core lint failed; do not describe its overall log as BUILD SUCCESSFUL.
Counts and exact errors: docs/validation-S1-R1-lint-summary.json. Lint reports:
app/build/reports/lint-results-debug.{xml,html}, core/build/reports/lint-results-debug.{xml,html}.
No claim that the failing UI/core checks passed before this correction; they remain follow-up findings.

Final APK: app/build/outputs/apk/debug/app-debug.apk, **69,823,107 bytes**.
SHA-256: `09a25e3a0861f101ab6c9607c938ed76e19865bea0ec09972dafab44c92db158`.
Not installed or published; no signing configuration/credential contents were inspected.
Exported Room schema still matches the frozen S1 manifest. All ten source hashes above were reverified
and scoped diff/text checks pass. The two hashes adjusted since source-only handoff are ListeningRecorder.kt
and ListeningOccurrenceRestoreTest.kt; the remaining eight declared source files are unchanged.

**RUNNER RELEASED** at 2026-11-07T18:18:27.721758+00:00. All four owned Gradle invocations have exited;
no further Gradle invocation is pending or running under this assignment. No other task/thread was polled,
no other process was stopped. Local F1/F3 checks pass; full app green, standalone core lint green,
installed native/MediaSession behavior and Source2 review approval remain explicitly unestablished.
