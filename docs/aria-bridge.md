# Fluidify signed Aria bridge

Source delivery followed by authorized local implementation and serialized validation on 2026-11-07. See validation results below. No device, live Spotify/provider calls, secrets, commits, push or publication. Existing player/theme/README and other concurrent changes were preserved.

## Manifest and transport

`app/src/main/AndroidManifest.xml` declares `.bridge.FluidifyToolHost`, authority `${applicationId}.ai.tools`, exported true, metadata `dev.antigravity.fluidengine.ai.TOOL_HOST=true`. No obsolete Android AI_TOOLS permission. The canonical AiToolHostProvider checks Binder UID signatures on every method before dispatch. All same-signer apps are trusted; no extra package allowlist. MediaSession playback remains the existing playback integration.

Canonical generic contracts copied selectively from PampAI/engine after comparing every existing selected file against its canonical committed baseline (all matched). Embedded engine HEAD remains a055257fc4b559bee54b9ce114f265bcd237adfd; ENGINE_VERSION unchanged. No whole-engine, provider or orchestrator copy. Selected source files: engine-ai/tools/{Tools,ToolResults,ToolArgumentValidator}.kt and engine-ai-bridge/{BridgeProtocol,AiToolClient,AiToolHostProvider,RemoteTools,TypedResultCodec}.kt under their existing packages. Selected tests: ToolContractsTest, RemoteToolTest, TypedProtocolTest. Canonical sample AiToolHostProviderGuardTest adapted into app test source with additional confirmation/malformed/no-effect assertions. This is an uncommitted embedded patch; root must preserve it when upgrading the engine.

Settings adds engine-ai and engine-ai-bridge modules; app depends on engine-ai-bridge. New host retains protocol 1 catalog/envelopes by default. Clients explicitly negotiate v2 with canonical `negotiate`; typed responses require protocol=2 plus typed_results capability on each request. App capabilities are obtained with `fluidify/capabilities`, independent of transport capabilities. There is no generic exactly-once transport capability.

## Tools and arguments

`capabilities` takes no arguments and returns only the currently authenticated history source identity (or typed authentication_required). Every data/write tool requires `account`, matching that current authenticated history access. Caller arguments never invoke connect/restore/account selection. Source readiness is reported as typed authentication_required rather than a false empty collection. Names in the host registry (client prefixes with fluidify):

- capabilities: actual supported operations and bounds.
- history_records: required from/until epoch milliseconds encoded as strings, [from,until); optional limit 1..100 and cursor. All retained events, not only qualified plays. Event IDs, track metadata, qualification, provenance, observed Spotify timestamp and nullable measured local duration.
- history_aggregate: same period; deterministic whole-period play counts, measured local milliseconds, imported-only counts, null imported duration, 100-row track/artist previews explicitly labeled and full count tools.
- history_track_counts / history_artist_counts: same period and paging, full aggregate count rows.
- history_coverage: paging over retained import receipts, including failures/partial outcomes and precision/ranges.
- playlists: paging over Web API library playlists.
- playlist_details: required playlistId; actual Spotify entity, owner, visibility, count and metadata.
- playlist_tracks: playlistId and paging. Preserves null/unavailable tracks as positioned entries. Snapshot ID is checked before/after source traversal; changing playlist fails with source_changed.
- playlist_create: required name (nonblank, max100). Creates a PRIVATE EMPTY playlist. Confirmation required.
- playlist_append: required playlistId, expectedSnapshotId and expectedTrackCount (from playlist_details), uris (1..50 Spotify track URIs). Owned playlists only, confirmation required. Validates baseline snapshot/count before sending; reconciles exact final count and appended tail under a stable post-write snapshot. The precondition is a client check, not a server atomic compare-and-set: concurrent edits can force uncertainty. Relinked/unavailable track URIs fail exact readback conservatively. Operation retries never append again.
- playlist_rename: playlistId, name. Only owned playlists. Confirmation required. Name-only DTO preserves visibility.

Remove/reorder/delete/description/visibility edits, imports, history deletion and account changes are not exposed. No YouTube history or background/profile work. No invented backend operations.

## Evidence, fences, bounds and errors

Repository bridgeSnapshot is an additive atomic account-gated Room query; bridgeCoverage retrieves bounded retained receipts. Both refuse >20,000 rows rather than truncate. They do not change recorder logic/schema. Aggregate uses existing deterministic repository query. SnapshotPager stores immutable pages for five minutes (maximum eight snapshots), bound to account AND revocation generation AND query. Live recordings cannot shift pages; process death/expiry/eviction yields expired_cursor. Changing account/generation/query rejects cursors. Requested page size can change safely. Cursor contains random snapshot identity and numeric offset, not arbitrary database offset. Stored snapshot rows remain as observed at capture time. Explicit history coverage always false: this is retained local evidence and limited Spotify observations, never a complete monthly archive or imported listening minutes. Imported measured duration remains JSON null. Freshness observedAt is bridge retrieval/snapshot time, not proof of server sync; syncedAt/stale remain unknown.

Spotify reads use a per-invocation pinned Web API token verified via me(). Both history lease and OAuth generation are checked around network dispatch/responses; no native-session fallback. This avoids authenticating one account and silently dispatching with another credential. Web API authorization/scopes are necessary: permissions or unavailable endpoints return errors. The bridge does not borrow the player's native token. The network client disables connection retries, redirects and app-level retries. Revocation rejects late responses. A mutation already sent before revocation may still apply remotely; it cannot be undone by revocation.

Playlist source collection bound is 2,000 rows. Library list traversal starts at offset ZERO and uses actual next; empty page with next is invalid_source_page. Library traversal is not a server transaction: concurrent library changes can affect this captured result; completeness stays false. Track traversal validates the server snapshot ID. A new cursor traversal authenticates again and requires the current lease. No source archive completeness claim. Error/source-unavailable is distinct from known empty; errors are typed with retryable=false, never raw transport/token content.

Writes require client confirmation (canonical host gate plus domain gate) and the parent-persisted ToolExecution.operationId transported as RemoteCall.requestId. The app overrides canonical three-argument AiTool.run. No operationId is advertised in model arguments. An optional legacy argument is accepted only when equal to the transport identity; conflict fails before account/network access, and an argument alone cannot supply identity. Two-argument app dispatch uses only the supplied RemoteCall.requestId. Missing/invalid identity fails closed. The generic host was minimally corrected to reject write/action requests without a nonblank string requestId instead of inventing a UUID; root must propagate this canonical host patch. RemoteTool two-argument dispatch also fails closed for action/confirmation tools rather than generating an ephemeral write ID; legacy reads retain their fallback. Root must propagate this RemoteTools patch and its updated regression tests too. Existing confirmation/action/long-job tests keep their assertions and now explicitly supply parent execution identity. Generic read fallback, v1 default/v2 negotiation and signature/confirmation checks remain compatible. The key is caller package/account/operationId; payload conflicts reject. SharedPreferences `aria-playlist-operations` commits the operation claim synchronously BEFORE mutation, and returned entity ID BEFORE readback. One provider-scoped mutex serializes ledger use. No pruning/automatic replay. Process restart reopens the same ledger. A claim with no returned create ID is permanently uncertain: no heuristic name search, no duplicate create. Known IDs reconcile via fresh matching ID/name/owner/visibility readback; only then COMPLETED with actual entity reference. A known rename target can reconcile after a lost response. If content later differs, reconciliation is uncertain; no claim of immutable remote state. Timeout/cancellation may return legacy transport error; the durable claim still prevents replay. Repeated completed operations read back current state, not a stale cached receipt. No remote idempotency guarantee is invented.

## Exact app implementation manifest

- app/src/main/java/dev/lelonio/square/bridge/FluidifyToolHost.kt
- app/src/main/java/dev/lelonio/square/bridge/BridgeState.kt
- app/src/main/java/dev/lelonio/square/bridge/BridgeSpotifyApi.kt
- app/src/main/java/dev/lelonio/square/data/ListeningRepository.kt (additive bounded snapshot/coverage queries only)
- app/src/main/java/dev/lelonio/square/data/SpotifyApi.kt (name-only rename endpoint/DTO; nullable public field for readback)
- app/src/main/AndroidManifest.xml
- app/build.gradle.kts
- settings.gradle.kts
- app/src/test/java/dev/lelonio/square/bridge/{BridgeStateTest,FluidifyToolValidationTest,AiToolHostProviderGuardTest}.kt
- app/src/test/java/dev/lelonio/square/data/ListeningRepositoryTest.kt (additive snapshot/revocation/unknown-duration regression)
- docs/aria-bridge.md

Bridge JSON explicitly encodes defaults, including public=false on creation; it does not rely on Spotify defaults. Append allows repeated URIs and checks all occurrences in order. No native credential access is performed by this bridge.

## Serialized validation handoff

Affected validation coverage: snapshot replay/expiry/account/query/generation isolation, known empty, duplicate operation/process recreation, lost response/no replay, readback uncertainty/reconciliation, operation conflicts, persistence failure/no dispatch, malformed tool/no effect, canonical signature checks and confirmation gate, repository imported unknown-duration snapshot and revocation. Copied generic tests cover v1/v2 contracts and typed payload roundtrip. Executed results are recorded below.

Root/shared validation owner runs serially from Fluidify:

```powershell
.\gradlew.bat --no-daemon --no-watch-fs --max-workers=1 :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :engine-ai:testDebugUnitTest :engine-ai-bridge:testDebugUnitTest
.\gradlew.bat --no-daemon --no-watch-fs --max-workers=1 :app:testDebugUnitTest --tests 'dev.lelonio.square.bridge.*' --tests 'dev.lelonio.square.data.ListeningRepositoryTest'
.\gradlew.bat --no-daemon --no-watch-fs --max-workers=1 :app:assembleDebug :app:lintDebug
```

Root must integrate PampAI discovery/tool gating, persist ToolExecution.operationId before dispatch and reuse it as requestId across explicit reconciliation, call signed fluidify/capabilities with {} before account-fenced tools, capability-aware typed cards, complete paging and error handling; confirm same signer deployment. Validate Spotify fixture/server schemas and permission rejection, process kill during dispatch/readback, logout while in flight, signed cross-app calls and MediaSession continuity in a later authorized environment. No live Spotify behavior or device quality was verified. Existing history tests/Room KSP schema validation remain part of the earlier history handoff.


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
