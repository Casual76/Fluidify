# LibrePods AACP attribution and integration handoff

The Fluidify AirPods client is derived from protocol, model tables, socket-constructor selection
and JNI platform access in LibrePods. Copyright (C) 2025 LibrePods contributors.
Licensed under **GPL-3.0-or-later**, without warranty. The GPL text is included in the repository's
root `LICENSE`; retain that license and corresponding source when distributing this code.

Pinned upstream: <https://github.com/librepods-org/librepods/tree/89356c08cea3db0b6b54792d9329d09a87cc85fa>.
Sources inspected (all at that commit):

- `android/app/src/main/java/me/kavishdevar/librepods/bluetooth/BluetoothConnectionManager.kt`
- `android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt`
- `android/app/src/main/java/me/kavishdevar/librepods/services/AirPodsService.kt`
- `android/app/src/main/java/me/kavishdevar/librepods/data/AirPods.kt`
- `android/app/src/main/java/me/kavishdevar/librepods/data/Packets.kt` (battery and conversation activity layouts)
- `android/app/src/main/cpp/bluetooth_socket.cpp`

## Full integration implementation (F1, in progress)

The approved full-integration plan extends the original POC with the applicable AACP controls,
EQ, rename, model information, ear/stem/head events and both tracking packet formats. The host
automation policy adapts semantics from `utils/MediaController.kt` and the ear/conversation
handlers in `services/AirPodsService.kt`, with explicit consent and conservative ownership.
The complete reference inventory, source paths and pinned blob hashes are recorded in
`build/airpods-full-integration/catalog/sources-manifest.json`; the semantic coverage and
exclusions are documented in `docs/airpods-full-integration-coverage.md`.

New derived protocol/automation files retain GPL-3.0-or-later notices. No upstream graphical
asset has been imported by this backend wave. Android service/UI wiring and physical validation
are still pending; source presence does not establish hardware support or coexistence.

The sections below describe the historical minimal POC and its integration handoff. Their
original scope exclusions and 90-second diagnostic/player lease are not the contract for the
new opt-in standalone service; the current F1 plan and contracts take precedence.

## Historical minimal POC

Adaptations: isolated transport, lazy checked JNI, no JNI_OnLoad effects, safe unknown/truncation handling,
observed state separated from local requests, generations, cancellable sessions, monotonic lease and debug-only entry.
No proximity keys, ownership/takeover, head tracking, gesture configuration, ear automation, local volume or playback automation.

## Root integration required before POC

No production application/service or build/manifest hooks were added by the implementation agent.
Keep engine 3.0.5 and dependency versions. Existing coroutines, Activity Compose and Material3 dependencies suffice.

1. In app CMake add a **separate** shared target named `airpods_socket` from `airpods_socket.cpp`, with C++11 or newer.
   Kotlin loads `libairpods_socket.so` only on `AirPodsSocket.create`. Do not add a startup load or JNI_OnLoad exemption.
   JNI symbol is `Java_dev_lelonio_square_airpods_AirPodsSocket_enableExemptions`.
   Retain `dev.lelonio.square.airpods.AirPodsSocket` and its native member names if minifying a variant that uses them.
2. In the **debug source-set manifest only**, register:
   `<activity android:name="dev.lelonio.square.airpods.AirPodsDiagnosticActivity" android:exported="false" />`.
   The activity is deliberately in `app/src/debug`, absent from `dev`/`release` variants.
   Add `BLUETOOTH_CONNECT` permission (API31+) and legacy Bluetooth permissions if testing API26-30;
   permission prompting occurs inside the diagnostic Activity, not general onboarding.
3. From a debug-only internal app action call
   `startActivity(dev.lelonio.square.airpods.AirPodsDiagnosticActivity.intent(context))`.
   No intent filter, exported component or broadcast is required. An external shell activity launch cannot access
   this non-exported Activity. No instrumentation dependency is needed with the internal action.
4. Select a bonded candidate explicitly; press Construct, Connect, Handshake, then Subscribe.
   The optional upstream feature-flags button is separate and must precede Subscribe if needed.
   The UI labels HANDSHAKE_SENT honestly: write success is not a peer acknowledgement or compatibility proof.
   Commands stay disabled until a complete received information packet identifies a supported model.
   All Pro3 identifiers A3063/A3064/A3065 are mapped. Unrecognized models/layouts stay Unknown.
   If firmware does not send the expected information layout, capture the limitation as NV; do not infer from name.
5. Read mode/CA/battery/activity and pending outcomes in the Activity. Unknown packets show only opcode, byte count,
   reason; no serial numbers, MAC addresses or raw proximity keys are logged. Activity exit closes the session.
   The root added an explicit 90-second background diagnostic window to compare the LibrePods UI while receiving
   notifications. Without arming that window, onStop closes immediately. This is a bounded test harness, not proof
   of the future service-owned production lifecycle. The debug About page provides the internal entry.
   Closing after connection interrupts its blocking reader; a 10s connection deadline also closes the socket.
   Ordinary reads intentionally have no idle timeout: hardware may be silent until an event; session closure interrupts them.

## Internal contracts for later host integration

- `AirPodsRepository(scope, AirPodsClock { SystemClock.elapsedRealtime() })`: StateFlow, explicit staged actions,
  setMode/setConversation, close. The host **must close before cancelling its scope**. It owns permissions and route observations.
- `AirPodsSessionOwner`: call only on a serialized lifecycle thread; configure opt-in/permission/bond validity,
  setUiVisible with actual RESUMED visibility, localPlayback, transferredToRemote, disconnectOrLogout,
  explicitConnectOrNewUserSession, real deduplicated profileConnected transitions and close.
  Acquire callback inspects actual profile/BT state and attempts at most once, ignoring an already pending/active connection.
  No current app hooks, route claim, A2DP reflection adapter, FGS or persistent notification are included.
- `AirPodsPreferences`: OFF default; persist verified pair only from received recognized identity, never a candidate name.
  Platform probe fingerprint is separate from model identity and never proves physical coexistence.
- `AirPodsState.liveBattery(component, nowMs)`: current generation/connection only, five-minute freshness;
  historical nullable data and monotonic timestamps remain in state for diagnostics.
- Codec consumes L2CAP SDUs, not arbitrary TCP chunks. Known fixed frames can be concatenated in an SDU.
  No unsafe header scanning or invented length is used for variable/unknown frames. Information requires all eleven
  upstream fields terminated; layouts with omitted/empty fields may remain Unknown and require a real capture before extension.

## Validation handed to root (NOT executed by this agent)

`./gradlew.bat :app:testDebugUnitTest --tests "dev.lelonio.square.airpods.*"`

`./gradlew.bat :app:assembleDebug`

Tests cover exact command bytes, unknown/truncated packets, multi-frame SDUs, battery nulls/freshness,
received identities including Pro3, pending isolation/last-wins/5s timeout, old generations, blocking connect close,
90s pause lease, UI lease, remote transfer, permission loss and suppressed reacquisition.
Repository fake I/O runs on real IO threads; timer scope uses a controlled test scheduler.
Physical S25 baseline, three bidirectional mode/CA sessions and lifecycle cleanup under 2s remain **NV**.
No unit test can promote this gate to PASS. Tablet/OS platform access and dual-channel coexistence are distinct probes.
