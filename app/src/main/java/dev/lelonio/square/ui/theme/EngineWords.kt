package dev.lelonio.square.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidLabels
import dev.antigravity.fluidengine.ui.fluid.FluidUpdateStrings
import dev.antigravity.fluidengine.ui.fluid.LocalFluidLabels
import dev.lelonio.square.R

/**
 * The words the engine's components say, in the app's language.
 *
 * The engine ships Italian and English; Fluidify is in eight languages. Where the app already
 * had a translated word for the same thing (back, close, clear, the update phrases) it is
 * used, so those stay in the listener's language; the few the app never wrote (a section index,
 * a notification's accessibility names) fall back to English, or Italian for an Italian
 * phone, rather than to the wrong language at random.
 */
@Composable
internal fun EngineWords(content: @Composable () -> Unit) {
    val italian = LocalConfiguration.current.locales[0].language == "it"
    val back = stringResource(R.string.back)
    val close = stringResource(R.string.close)
    val clear = stringResource(R.string.clear)
    val labels = remember(italian, back, close, clear) {
        (if (italian) FluidLabels() else FluidLabels.english())
            .copy(back = back, close = close, notificationDismiss = close, clear = clear)
    }
    CompositionLocalProvider(LocalFluidLabels provides labels, content = content)
}

/** The update section's and prompt's words: the app's own translations over the engine's base. */
@Composable
internal fun rememberFluidifyUpdateStrings(): FluidUpdateStrings {
    val italian = LocalConfiguration.current.locales[0].language == "it"
    val whatsNew = stringResource(R.string.update_whats_new)
    val install = stringResource(R.string.update_install)
    val retry = stringResource(R.string.retry)
    val later = stringResource(R.string.update_later)
    val close = stringResource(R.string.close)
    val checking = stringResource(R.string.update_checking)
    val upToDate = stringResource(R.string.update_none)
    val downloading = stringResource(R.string.update_downloading)
    val awaitingUser = stringResource(R.string.update_installing)
    val stable = stringResource(R.string.update_channel_stable)
    val beta = stringResource(R.string.update_channel_beta)
    val checkNow = stringResource(R.string.check_for_updates)
    val sectionTitle = stringResource(R.string.update_section_title)
    val availableText = stringResource(R.string.update_available, "%s")
    return remember(
        italian, whatsNew, install, retry, later, close, checking, upToDate, downloading,
        awaitingUser, stable, beta, checkNow, sectionTitle, availableText,
    ) {
        val base = if (italian) FluidUpdateStrings() else FluidUpdateStrings.english()
        base.copy(
            whatsNew = whatsNew,
            install = install,
            retry = retry,
            later = later,
            close = close,
            checking = checking,
            upToDate = upToDate,
            awaitingUser = awaitingUser,
            channelStable = stable,
            channelBeta = beta,
            checkNow = checkNow,
            sectionTitle = sectionTitle,
            available = { version -> availableText.replace("%s", version) },
            downloading = { percent, downloaded, total ->
                if (percent == null) downloading else base.downloading(percent, downloaded, total)
            },
        )
    }
}
