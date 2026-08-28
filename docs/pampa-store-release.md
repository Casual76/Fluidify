# Publishing Fluidify on the Pampa Store

Fluidify cannot go on Google Play — it reimplements a protocol whose terms
forbid it — so it ships through the Pampa Store, the author's own Android
store. This is what that means in practice, and what has to happen for the
first release, `1.0.0`, to exist.

## What the store actually is

Three files and a GitHub release, no server:

- **The index**, `manifest.json` in `Casual76/Pampa-store`. A list of apps, each
  one a pointer: `id`, `repoOwner`, `repoName`, `manifestPath`. The store app
  reads it to know what exists. Fluidify is not in it yet.
- **The app manifest**, `manifest.json` at the root of `Casual76/Fluidify`. The
  app's own entry: name, description, icon, and the two release lines, `stable`
  and `beta`, each holding a version, a changelog, a release tag, an asset name
  and a size. This repository already has one, with everything except the
  release lines — publishing adds those.
- **The APK**, a GitHub release asset on `Casual76/Fluidify`. The tag is
  `stable-fluidify-v<version>`, the asset is `fluidify-<version>.apk`. Both are
  derived from the manifest, never typed anywhere.

`Updater` reads the app manifest directly, at
`raw.githubusercontent.com/Casual76/Fluidify/master/manifest.json`, and builds
the download URL from the same fields the store uses. That is the point of the
arrangement: the store and the copy on the phone cannot disagree about what the
latest version is, because they are reading the same sentence.

## What is already wired up here

Nothing in the app needs changing to publish. For the record, so it can be
checked rather than trusted:

- `Updater.MANIFEST_URL` points at the app manifest above.
- `REQUEST_INSTALL_PACKAGES` arrives from `:engine-update`'s manifest, along
  with the receiver that hears back from `PackageInstaller`.
- The installer refuses an APK whose package name is not `dev.pampa.fluidify`
  or whose `versionName` is not the one the manifest advertised — so **do not
  publish with a `--version` different from `versionName` in
  `app/build.gradle.kts`**, or the update will download and then be rejected on
  the phone.
- `versionCode = 1`, `versionName = "1.0.0"`. Correct as they stand for a first
  release; leave them alone for `1.0.0`.

## Before the first release

These are the parts nobody else can do.

**1. Push the repository.** `origin/master` is still upstream Square's tree —
this branch is 31 commits ahead of it and none of them are on GitHub. Until it
is pushed, `raw.githubusercontent.com/Casual76/Fluidify/master/manifest.json`
returns 404, and so does `pathfinder-fluidify.json`, which the app reads for its
Spotify hashes. The publisher also refuses to run against a dirty tree or a HEAD
that is not in its upstream, so this is the first step whether you like it or
not:

```powershell
git add -A
git commit -m "..."
git push origin master
```

`engine/` is a submodule and it currently reads as modified, which is enough on
its own to fail the publisher's preflight — that check is `git status
--porcelain` being empty, and it does not care that the change is in a
submodule. Settle it first, in `C:\VibeCoded Projects\fluid-engine`, and commit
the new pointer here. Its `url` in `.gitmodules` is a path on this machine, so
the pushed repository will not clone anywhere else; that does not stand in the
way of a release, but it is the reason nobody can build Fluidify from the public
repository today.

Push the current `manifest.json` as part of this. The publisher reads the
*remote* copy and edits it in place; if the file is not there yet it writes a
fresh one, and the Italian description, the icon URL and the category in the
local copy are simply lost.

**2. Write `keystore.properties`.** It is git-ignored and belongs beside
`settings.gradle.kts`, at the root of this repository. The signing key for every
Pampa Store app is `C:\VibeCoded Projects\pampa.jks`, alias `pampa`:

```properties
storeFile=C:/VibeCoded Projects/pampa.jks
storePassword=...
keyAlias=pampa
keyPassword=...
```

Without this file the release build silently falls back to the debug key, which
every machine shares. Gradle now says so on every build, and the reason it
matters is worth repeating: Android refuses an update signed with a different
key than the installed copy, so a debug-signed `1.0.0` cannot be replaced by the
real `1.0.1` — everyone who installed it has to uninstall and lose their login.
The store will not catch it for you; its publisher checks only that the APK is
signed, not by whom.

**3. Have a GitHub token in the environment.** `repo` scope, and it needs write
access to **two** repositories: `Casual76/Fluidify`, for the release and the app
manifest, and `Casual76/Pampa-store`, for the index entry.

```powershell
$env:PAMPA_GH_TOKEN = "ghp_..."
```

**4. Write the changelog to a file.** Pass it with `--changelog-file` rather
than `--changelog`: PowerShell 5.1 mangles non-ASCII on the command line, and
this string ends up verbatim in the store listing. Save it as UTF-8, and follow
the other Pampa Store manifests in spelling accents as apostrophes — `e'`,
`piu'`, `perche'` — which is what they all do, so the listing does not become
the one entry with mojibake in it.

## Publishing 1.0.0

Build first, and hand the publisher the APK. It can run Gradle itself, but here
that means twenty minutes of a Rust cross-build with its output captured and
invisible; and its own APK discovery skips every path containing a `build`
directory, which is the only place an Android APK is ever written. So:

```powershell
cd "C:\VibeCoded Projects\Fluidify"
.\gradlew.bat :app:assembleRelease
```

That drops `app\build\outputs\apk\release\app-release.apk`. It needs the NDK at
`28.2.13676358`, `cargo-ndk`, the `aarch64-linux-android` rustup target, and a
network for the Bungee clone. Check the APK is the signed one — if
`keystore.properties` was missing, Gradle warned about it near the top of the
build.

Then look at what the publisher thinks it is about to do:

```powershell
python "C:\VibeCoded Projects\Pampa-store-src\plugins\pampa-store-publisher\mcp\publisher.py" `
  probe --repo-root "C:\VibeCoded Projects\Fluidify"
```

`selectedModule` must be `app`, with `version` `1.0.0` and `applicationId`
`dev.pampa.fluidify`. `apkCandidates` will be empty; that is the discovery
problem above, not a missing build.

Rehearse the publish, which touches nothing:

```powershell
python "C:\VibeCoded Projects\Pampa-store-src\plugins\pampa-store-publisher\mcp\publisher.py" `
  publish --dry-run --channel stable `
  --repo-root "C:\VibeCoded Projects\Fluidify" `
  --app-id fluidify --app-name Fluidify `
  --apk-path "C:\VibeCoded Projects\Fluidify\app\build\outputs\apk\release\app-release.apk" `
  --changelog-file "C:\VibeCoded Projects\fluidify-1.0.0-changelog.txt"
```

Read `appManifestPreview` in the output: it is the file that will be committed
to this repository, and the description and `iconUrl` should be the ones already
there. `releaseTag` should be `stable-fluidify-v1.0.0` and the asset
`fluidify-1.0.0.apk`. Then run the same command without `--dry-run`.

Publishing to `stable` sets `beta` to the same release, deliberately: a beta
channel with nothing published falls back to stable rather than reporting "no
updates" forever.

## Checking it worked

- `https://raw.githubusercontent.com/Casual76/Fluidify/master/manifest.json`
  has an `app.stable` with version `1.0.0`. The CDN caches this for about five
  minutes, so an immediate check can still show the old file.
- `https://raw.githubusercontent.com/Casual76/Pampa-store/main/manifest.json`
  lists `"id": "fluidify"`.
- `https://github.com/Casual76/Fluidify/releases/download/stable-fluidify-v1.0.0/fluidify-1.0.0.apk`
  downloads. This is the exact URL `Updater` will build, character for
  character; if it 404s, the release exists but the asset name does not match
  the manifest.
- `git pull`. The publisher wrote `manifest.json` straight to `master` through
  the GitHub API, so the local clone is now behind on that file.

Then install it on a phone from the store app and open Settings: the update row
should say up to date. The honest end of this is the *second* release, which is
the first one anybody receives.

## Every release after this one

- Bump both `versionCode` and `versionName` in `app/build.gradle.kts`.
  `Updater` compares `versionName` only, so a forgotten `versionCode` still
  ships; it just gives up Android's own downgrade protection for nothing.
- Never reuse a version. The publisher refuses a tag that already exists, and
  refuses a version that is not newer than the current one on that channel.
- The same signing key, every time. There is no recovering from changing it.

## What this does not cover

- The APK carries **arm64 only** (`nativeAbis` in `app/build.gradle.kts`).
  Every phone made in the last decade is arm64, and each extra ABI is another
  full Rust build; but an older 32-bit device gets an install failure from the
  store rather than a message explaining itself.
- The app shows the version and the download size in the update prompt, not the
  changelog. The changelog you write is carried in the manifest and read by the
  store listing, and `Updater.State.Available` currently drops it.
- `Updater` follows the stable channel and offers no way to switch to beta. The
  engine supports it; nothing in this app asks for it.
