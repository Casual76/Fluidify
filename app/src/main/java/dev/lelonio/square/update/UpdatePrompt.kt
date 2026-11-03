package dev.lelonio.square.update

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.antigravity.fluidengine.ui.fluid.FluidUpdateController
import dev.antigravity.fluidengine.ui.fluid.FluidUpdatePromptSheet
import dev.antigravity.fluidengine.ui.fluid.FluidUpdateUiState
import dev.antigravity.fluidengine.ui.fluid.release
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.ui.theme.rememberFluidifyUpdateStrings

/**
 * The one update controller of the app.
 *
 * The launch prompt and the About page read the same one, so a check made in either shows in
 * both, and an install started in one is the one the other reports on. It lives at the root of
 * the app (see SquareApp), so a download goes on while the pages change.
 */
val LocalUpdateController = staticCompositionLocalOf<FluidUpdateController> {
    error("No update controller: SquareApp provides one at its root")
}

/**
 * Looks for a new release at launch and, when there is one, offers it in a sheet.
 *
 * Install, Later or Skip this version: three answers, none of them a trap. Later hides it for
 * now and it comes back at a later check; Skip remembers the version, so the next release is
 * news again. A failure is said in the sheet, where the person who asked for the install is
 * looking -- the check itself is not something they asked for, so a failed *check* says nothing.
 *
 * At most one check every six hours, however often the app is opened.
 */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as SquareApplication }
    val prefs = app.preferences
    val update = LocalUpdateController.current
    val strings = rememberFluidifyUpdateStrings()
    var later by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheck() < UPDATE_CHECK_INTERVAL_MS) return@LaunchedEffect
        // Recorded before the call rather than after it: a phone with no network would
        // otherwise ask again at every launch, which is the one shape of this that would be a
        // nuisance.
        prefs.setLastUpdateCheck(now)
        update.check()
    }

    // Granting the permission in the system's settings and coming back carries on with the
    // install that was asked for, instead of leaving a row to press a second time.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (update.state is FluidUpdateUiState.NeedsPermission && canInstall(context)) update.install()
    }

    val version = update.state.release?.version
    // Already offered and turned down: the version is remembered rather than a flag.
    val shown = update.state.takeIf { version != null && version != prefs.skippedUpdate() && version != later }
    FluidUpdatePromptSheet(
        state = shown,
        onInstall = update::install,
        onLater = { later = version },
        onSkip = { version?.let(prefs::setSkippedUpdate) },
        strings = strings,
    )
}

/** Whether the user has allowed this app to install packages at all. */
private fun canInstall(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

/** At most one check every six hours, however often the app is opened. */
private const val UPDATE_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
