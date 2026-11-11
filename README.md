<h1 align="center">Fluidify</h1>
<p align="center">An unofficial Spotify player for Android and Wear OS, built with Fluid Engine.</p>

Fluidify plays music from Spotify Premium through a native [librespot](https://github.com/librespot-org/librespot) core. It combines your library, Spotify Connect, offline listening and audio effects in an interface built with Fluid Engine. A companion app brings playback controls and independent listening to Wear OS.

> Fluidify is an independent project and is not affiliated with Spotify. It uses an unofficial implementation of Spotify's protocol; availability can change and use may be subject to Spotify's terms. See the [license](LICENSE).

## Features

- **Your Spotify library:** browse playlists, albums, artists and liked songs; search the catalogue and manage the playback queue.
- **Playback and Connect:** play through the app, transfer playback between Spotify Connect devices, and use Android media controls, Bluetooth and Android Auto.
- **Offline listening:** download tracks and playlists, keep liked songs offline, choose download quality and restrict downloads to Wi-Fi. Offline mode plays downloaded music without requesting Spotify.
- **Player:** synchronized lyrics, Spotify Canvas, speed and pitch controls, reverb, presets and crossfade. Listen to local audio files on the phone too.
- **Fluid interface:** glass surfaces, animated navigation and haptic feedback, with a home-screen playback widget and optional audio-reactive lighting.
- **Wear OS:** control phone playback, browse and search the library, play independently on the watch, download playlists and albums, and use the tile and complication. See the [device QA checklist](docs/wear-qa-checklist.md).
- **Languages:** English, Italian, Spanish, French, German, Portuguese, Turkish and Hindi.

## Getting started

Spotify Premium is required for Spotify playback. Sign in through Spotify when prompted. For search and recommendations, the onboarding guide also asks you to create a Spotify developer application, register the redirect URI shown in Fluidify and enter its Client ID. A Client Secret is not needed.

The phone app requires Android 8.0 or later (API 26). The watch app requires Wear OS on Android API 36 or later. The watch can be installed from the phone app's settings; the installation flow provides instructions for wireless debugging and pairing.

## Building

Install Android Studio, Android SDK platform 37, NDK **28.2.13676358**, CMake 3.22 or later, Git, Rust and `cargo-ndk`. Use a JDK compatible with Android Gradle Plugin 8.13.2. The native crate declares Rust 1.81 as its minimum; dependencies may require a newer toolchain.

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
sdkmanager --install "platforms;android-37" "ndk;28.2.13676358"
./gradlew :app:assembleDebug :wear:assembleDebug
```

On Windows use `gradlew.bat` instead of `./gradlew`. The build fetches Bungee for audio processing and cross-compiles the Rust core with `:core:cargoBuild`, placing native libraries in `core/src/main/jniLibs`. By default the core builds ARM64, ARMv7 and x86-64; a phone-only build can narrow this with `-Pfluidify.coreAbis=arm64-v8a,x86_64`. Phone debug builds package ARM64 and x86-64; phone release builds package ARM64.

Release builds use the root `keystore.properties` when present, with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Otherwise they use the debug signing key. Keep the properties file and keystore private. Android requires the same signing key to update an existing installation.

## Project structure

- `app/` — phone UI, playback service, downloads, widget and integrations.
- `core/` — shared native bridge and playback components for phone and watch.
- `native/` — Rust playback core and vendored librespot components.
- `wear/` — Wear OS app, independent playback and phone integration.
- `wear-protocol/` — shared phone–watch communication protocol.
- `engine/` — Fluid Engine modules; the pinned version and module list are in [`engine.properties`](engine.properties).

## License

Fluidify is licensed under the [GNU General Public License v3.0](LICENSE). Third-party components retain their respective licenses.
