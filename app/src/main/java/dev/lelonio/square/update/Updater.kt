package dev.lelonio.square.update

import android.content.Context
import dev.antigravity.fluidengine.foundation.AppUpdater
import dev.antigravity.fluidengine.net.EngineHttp
import dev.antigravity.fluidengine.update.AndroidAppUpdateInstaller
import dev.antigravity.fluidengine.update.EngineAppUpdater
import dev.antigravity.fluidengine.update.UpdateSource
import dev.lelonio.square.BuildConfig

/**
 * Where the app's own releases come from.
 *
 * Fluidify cannot go on the Play Store -- it re-implements a protocol whose terms forbid it --
 * so its releases live where the Pampa Store publishes them: a `manifest.json` in the app's own
 * repository, with the APKs as release assets. The store and this updater read the same file,
 * so there is no version of events where the two disagree.
 *
 * Until 1.8 the app wrapped the engine's updater in a state machine of its own; the engine's
 * `FluidUpdateController` is that state machine now (see `rememberFluidUpdateController` in
 * SquareApp), and what is left here is only where to look.
 *
 * Nothing here has to verify signatures by hand. Android refuses an update signed with a
 * different key than the installed copy, and the engine's installer additionally refuses an
 * APK whose package or version is not the one the manifest advertised.
 */
object FluidifyUpdates {

    /**
     * The same file the Pampa Store reads to list the app. One source of truth: the store and
     * the in-app updater can never offer different versions.
     *
     * Pinned to `master` because a raw URL has to name a ref, and the one named here is baked
     * into every copy already installed: renaming the default branch would end updates for all
     * of them at once, with no release able to reach them to say so. Publishing writes the
     * release sections of this file -- docs/pampa-store-release.md is how.
     */
    const val MANIFEST_URL =
        "https://raw.githubusercontent.com/Casual76/Fluidify/master/manifest.json"

    fun create(context: Context): AppUpdater {
        val app = context.applicationContext
        val http = EngineHttp(userAgent = "Fluidify/${BuildConfig.VERSION_NAME}")
        return EngineAppUpdater(
            http = http,
            source = UpdateSource(manifestUrl = MANIFEST_URL, applicationId = app.packageName),
            installer = AndroidAppUpdateInstaller(app, http),
        )
    }

    /** The installed version as the manifest knows it: a debug build's suffix is not a release's. */
    val currentVersion: String get() = BuildConfig.VERSION_NAME.substringBefore("-")
}
