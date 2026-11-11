# LibrePods AirPods interface: what was taken, from where

The AirPods capsule, quick pane and settings pages reuse interface code and pictures from
LibrePods. Copyright (C) 2025 LibrePods contributors. Licensed under **GPL-3.0-or-later**, without
warranty. The GPL text is the repository's root `LICENSE`; keep that licence and the corresponding
source when distributing this code. This note adds to `LICENSES/LibrePods-AirPods.md`, whose
sentence "No upstream graphical asset has been imported by this backend wave" is about the
backend only and is no longer true of the app as a whole.

Pinned upstream: <https://github.com/librepods-org/librepods/tree/89356c08cea3db0b6b54792d9329d09a87cc85fa>

## Code

Paths are relative to `android/app/src/main/java/me/kavishdevar/librepods/`.

| In Fluidify (`app/src/main/java/dev/lelonio/square/ui/airpods/`) | From LibrePods | What is the same |
|---|---|---|
| `AirPodsBattery.kt` | `presentation/components/BatteryView.kt`, `presentation/components/BatteryIndicator.kt` | the two-column layout and its measures, the single indicator for a matched pair, the ring geometry, the 25 % threshold, the optimised-charging ring and its 80 % marker, the bolt |
| `AirPodsNoiseControl.kt` | `presentation/components/NoiseControlSettings.kt` (the `DesignSystem.Apple` branch), `presentation/components/NoiseControlButton.kt` | the pill, the slots and their order, the thumb, the dividers, the labels, the horizontal `draggable` with its clamp and nearest-slot release |
| `AirPodsArtwork.kt` | `data/AirPods.kt` | which pictures a model number shows |
| `AirPodsControls.kt` (structure only) | `presentation/screens/AirPodsSettingsScreen.kt`, `presentation/screens/PressAndHoldSettingsScreen.kt`, `presentation/components/PressAndHoldSettings.kt`, `AudioSettings.kt`, `ConnectionSettings.kt` | the order and grouping of the main page, the page behind Left and Right |

Each file says in its header what it changed. In short: colours, shapes and motion come from the
Fluid theme; the rows, switches, sheet and pages are the Engine's; no value is drawn that the
device has not reported; and the selector's drag is read on the pill rather than on the thumb.

No Apache-2.0 file was ported here. LibrePods' README credits Kyant0's AndroidLiquidGlass demo for
parts of its `Styled*` primitives; none of those files is among the ones above. (The Fluid Engine
carries its own attribution for what it ported: `engine/LICENSES/`.)

## Pictures

43 PNG files, **byte-identical** to the upstream blobs, installed in
`app/src/main/res/drawable-nodpi/`:

- 40 pictures of earbuds and cases, from `android/app/src/main/res-apple/drawable/`, under their
  upstream names (`airpods_1_buds.png` … `airpods_pro_3_right.png`);
- 3 listening-mode marks, from `android/app/src/main/res/drawable/`, renamed so a generic name
  cannot collide with another drawable of the app: `adaptive.png` → `airpods_mode_adaptive.png`,
  `noise_cancellation.png` → `airpods_mode_noise_cancellation.png`,
  `transparency.png` → `airpods_mode_transparency.png`.

`build/airpods-ui-reuse/assets/airpods-ui-assets.json` lists every installed file with its
upstream path, git blob id, SHA-256 and size; `example_appwidget_preview.png` was not installed.
Upstream stores these files in `drawable` (density-scaled at load); here they are `nodpi` and are
scaled only when drawn. No SF Pro font and no SF Symbols glyph was taken.

One mapping differs from upstream on purpose: "AirPods 5 with Wireless Charging Case" uses the
`airpods_5_wireless_*` set, which upstream ships and uses only in a preview (it points that model
at the `airpods_4_*` pictures).

AirPods, AirPods Pro and the product pictures are the property of Apple Inc. LibrePods and
Fluidify are not affiliated with or endorsed by Apple Inc.; the pictures identify the paired
device a control acts on.
