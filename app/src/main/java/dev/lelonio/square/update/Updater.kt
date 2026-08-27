package dev.lelonio.square.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.antigravity.fluidengine.foundation.AppUpdateInstallState
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.net.EngineHttp
import dev.antigravity.fluidengine.update.AndroidAppUpdateInstaller
import dev.antigravity.fluidengine.update.EngineAppUpdater
import dev.antigravity.fluidengine.update.UpdateSource
import dev.lelonio.square.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Updates the app from the Pampa Store's manifest.
 *
 * Fluidify cannot go on the Play Store — it re-implements a protocol whose
 * terms forbid it — so its releases live where the Pampa Store publishes them:
 * a `manifest.json` in the app's own repository, with the APKs as release
 * assets. The store and this updater read the same file, so there is no
 * version of events where the two disagree.
 *
 * The state machine is unchanged from the GitHub era on purpose: the settings
 * row and the launch prompt read it, and what changed is where releases come
 * from, not what checking for one looks like.
 *
 * Nothing here has to verify signatures by hand. Android refuses an update
 * signed with a different key than the installed copy, and the engine's
 * installer additionally refuses an APK whose package or version is not the
 * one the manifest advertised.
 */
class Updater(context: Context) {

    private val app = context.applicationContext

    private val http = EngineHttp(userAgent = "Fluidify/${BuildConfig.VERSION_NAME}")
    private val engine = EngineAppUpdater(
        http = http,
        source = UpdateSource(
            manifestUrl = MANIFEST_URL,
            applicationId = app.packageName,
        ),
        installer = AndroidAppUpdateInstaller(app, http),
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The manifest's own offer, kept so [install] has more than a version string. */
    private var resolved: AvailableAppUpdate? = null

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val version: String, val url: String, val bytes: Long) : State
        /** 0f..1f, or null while there is nothing to measure against. */
        data class Downloading(val progress: Float?) : State
        /** Handed to the system installer; the dialog is Android's, not ours. */
        data object Installing : State
        data class Failed(val reason: String) : State
    }

    /** Asks the manifest what the latest release is. */
    suspend fun check() {
        _state.value = State.Checking
        engine.check(BuildConfig.VERSION_NAME).fold(
            onSuccess = { update ->
                resolved = update
                _state.value = if (update == null) {
                    State.UpToDate
                } else {
                    State.Available(update.version, update.downloadUrl, update.sizeBytes)
                }
            },
            onFailure = {
                android.util.Log.w(TAG, "check failed: $it")
                _state.value = State.Failed(it.message ?: "network")
            },
        )
    }

    /**
     * Checks, and installs whatever it finds, as one action.
     *
     * Split in two internally but never in the interface: "there is an update"
     * is not a decision the user has anything to decide with — they pressed a
     * row that says update, and being asked again is a step, not a safeguard.
     * The safeguard is Android's own dialog, which no app can skip.
     *
     * @return the update it could not install without permission, so the caller
     *   can ask for it and come back here rather than starting over.
     */
    suspend fun checkAndInstall(): State.Available? {
        check()
        val update = _state.value as? State.Available ?: return null
        if (!canInstall()) {
            _state.value = State.Failed(REASON_PERMISSION)
            return update
        }
        install(update)
        return null
    }

    /**
     * Downloads the APK and hands it to the system installer.
     *
     * The engine's flow does the work — download, APK sanity checks, the
     * PackageInstaller session — and this collapses its states onto the ones
     * the rows already know how to show.
     */
    suspend fun install(update: State.Available) {
        if (!canInstall()) {
            _state.value = State.Failed(REASON_PERMISSION)
            return
        }
        val offer = resolved?.takeIf { it.version == update.version } ?: run {
            // Nothing resolved for this version: the state got here without a
            // check, which is a caller bug, not a network condition.
            _state.value = State.Failed("stale")
            return
        }

        engine.install(offer).collect { step ->
            _state.value = when (step) {
                is AppUpdateInstallState.Downloading ->
                    State.Downloading(step.progress.takeIf { it > 0f })
                // Pre-download preparation and post-download verification both
                // read fine as the fetch they bracket.
                is AppUpdateInstallState.Verifying -> State.Downloading(null)
                is AppUpdateInstallState.Installing -> State.Installing
                is AppUpdateInstallState.AwaitingUserAction -> State.Installing
                is AppUpdateInstallState.Installed -> State.Installing
                is AppUpdateInstallState.Error -> {
                    android.util.Log.w(TAG, "install failed: ${step.message}")
                    State.Failed(step.message)
                }
            }
        }
    }

    /** Whether the user has allowed this app to install packages at all. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || app.packageManager.canRequestPackageInstalls()

    /**
     * The settings page where that permission is granted.
     *
     * No `NEW_TASK` flag: it is started for a result, so the caller is told when
     * the user comes back and can carry on with the install they already asked
     * for rather than making them press the row a second time.
     */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${app.packageName}"))

    fun dismiss() {
        _state.value = State.Idle
    }

    companion object {
        private const val TAG = "Updater"

        /**
         * The same file the Pampa Store reads to list the app. One source of
         * truth: the store and the in-app updater can never offer different
         * versions.
         */
        const val MANIFEST_URL =
            "https://raw.githubusercontent.com/Casual76/Fluidify/master/manifest.json"

        /** Told apart from a network failure so the UI can offer the way out. */
        const val REASON_PERMISSION = "permission"
    }
}
