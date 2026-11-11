# Source2 final correction R3/3

Source corrections and local validation completed. This does not declare runtime success or review approval. The adaptive UI implementer released the sole compiler runner; root then used it serially and has now released it.

## Confirmed findings and correction

- **S3 R1 P1, discovery:** `TOOL_HOST` metadata was boolean while the shared client accepts integer `1`. The app manifest now uses `android:value="1"`. `FluidifyDiscoveryTest` exercises the real merged manifest and `AiToolClient.discover()` through Robolectric PackageManager, without starting account/playback infrastructure. No generic engine change.
- **S3 R2 P2, transient remote ownership:** `showRemote(nonNull)` now immediately delivers the remote boundary to `PlaybackOccurrence`. This clears a pending owned ticket even before an occurrence ID exists, so a later return to local state cannot revive its saved checkpoint. `OwnedNativeRestoreIntegrationTest.transientRemoteBeforeAcknowledgementRevokesPendingOwnedRestore` crosses remote->local before delivering the exact owned acknowledgment, with no intervening native event. It asserts two separate unqualified20s occurrences rather than one qualified40s occurrence. Existing positive owned restore cases remain intact.

## Exact scope

- `app/src/main/AndroidManifest.xml`
- `core/src/main/java/dev/lelonio/square/playback/LibrespotPlayer.kt`
- `app/src/test/java/dev/lelonio/square/bridge/FluidifyDiscoveryTest.kt`
- `app/src/test/java/dev/lelonio/square/data/OwnedNativeRestoreIntegrationTest.kt`
- this report

No native ABI/source, Rust reporting, Room schema, generic engine, playlist policy or ClasseViva change. The existing native rebuilt-byte proof remains historical; a fresh packaged-APK comparison is required after assembly.

## Validation state

XML parsing and scoped `git diff --check` passed. Fresh final tests: **65 app +18 core**, zero failures/errors/skips, across14 and4 XML suites. Both test tasks executed; older run counts are not added. All eight actual producer owned-restore cases pass, including the transient remote ordering.

Initial discovery fixture failed because Robolectric4.17's `ShadowApplicationPackageManager.queryContentProviders` is an unconditional null stub (confirmed in the installed library's bytecode). The fixture now supplies only that platform query from PackageManager's actual merged-manifest ProviderInfo list; real metadata and `AiToolClient.discover()` filtering are exercised unchanged. The first65-test run had one failure; a discovery-only diagnostic run also failed. Both logs remain retained. The final full scoped run passes without weakening the host-discovery assertion. This remains a JVM/platform-query fixture, not installed Android discovery/Binder proof.

Final test log: `docs/validation-S3-R3-tests-final.log`, BUILD SUCCESSFUL41s. Diagnostic logs: `validation-S3-R3-tests.log`, `validation-S3-R3-discovery.log`. Serial JBR/Gradle flags match R2: no-daemon/no-watch-fs/max-workers1/no-build-cache/no-configuration-cache/in-process/nonincremental Kotlin. App assembly and app lint passed. Combined build/lint exited1 at core lint's **three known UnsafeOptInUsageError errors**, without suppression; stable log `docs/validation-S3-R3-build-lint.log` (1m51s). The earlier broader MiniPlayer failure remains historical unresolved evidence, with unproven baseline origin. No full-suite/full-lint green claim.

`aapt dump xmltree` confirms packaged TOOL_HOST has integer **type0x10/value0x1**, replacing the reviewed boolean0x12. APK SHA256 **4EFFC0574915CC9DCF7A32F98F4785165C4713407F2FA5C4EDAAA047CAC5E777**,80286021bytes. Packaged arm64 andx86_64 native libraries byte-match the R2 rebuilt outputs; native source unchanged, cargoBuild UP-TO-DATE, no fresh native test/build claim. Current source/APK/native hashes and archived JUnit/lint reports: `docs/validation-S3-R3/artifact-manifest.json`. R2's17-source manifest is historical; current LibrespotPlayer/owned-restore fixture hashes differ by these explicit R3 corrections.

Source2 runtime/auth/Binder/process-kill/native/MediaSession/accessibility checks remain NV, without a waiver. No source2 APK was installed in this root correction. No compiler/device process remains owned; no commit, push or publication.
