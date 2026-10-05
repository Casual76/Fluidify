package dev.lelonio.square.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.antigravity.fluidengine.ui.fluid.FluidIndeterminateBar
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.lelonio.square.R
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.wear.install.WatchInstaller

/**
 * Installing the watch app from the phone, for a watch that does not have it yet.
 *
 * Folded away until asked for: it is needed once. The instructions say what to
 * switch on on the watch; the address and code fill themselves in when the
 * phone can see the watch on the network, and can be typed when it cannot.
 */
@Composable
internal fun WatchInstallRows(app: SquareApplication) {
    var open by rememberSaveable { mutableStateOf(false) }
    RowDivider()
    if (!open) {
        ActionRow(stringResource(R.string.watch_install), destructive = false) { open = true }
        return
    }
    val model: WatchInstallViewModel = viewModel()
    val installer = model.installer
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, installer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> installer.startDiscovery()
                Lifecycle.Event.ON_STOP -> installer.stopDiscovery()
                else -> Unit
            }
        }
        // No explicit start here: an observer added to a lifecycle that is already started is
        // sent ON_START at once, and starting the discovery as well made it start twice.
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); installer.stopDiscovery() }
    }
    val found by installer.found.collectAsStateWithLifecycle()
    val step by installer.step.collectAsStateWithLifecycle()
    // Null until the person types in the field: what the phone heard on the network is shown
    // meanwhile. A blank string stood for "not typed", so a field emptied on purpose filled itself
    // again from the discovery and could never be cleared.
    var address by rememberSaveable { mutableStateOf<String?>(null) }
    var connectAddress by rememberSaveable { mutableStateOf<String?>(null) }
    var code by rememberSaveable { mutableStateOf("") }
    // What the last attempt to start found wrong with what was typed.
    var addressInvalid by rememberSaveable { mutableStateOf(false) }
    var codeInvalid by rememberSaveable { mutableStateOf(false) }
    var pickedApk by rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) pickedApk = uri }
    val shownAddress = address ?: found.host?.let { host -> found.pairingPort?.let { "$host:$it" } }.orEmpty()
    val shownConnect = connectAddress ?: found.host?.let { host -> found.connectPort?.let { "$host:$it" } }.orEmpty()

    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
        Text(
            stringResource(R.string.watch_install_steps),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The engine's field, which is the one the rest of the app's forms are made of: Material's
        // outlined box was the only one of its kind on these pages. Its label sits above the box
        // and the error line under it, as strings.
        FluidTextField(
            value = shownAddress,
            onValueChange = {
                address = it
                addressInvalid = false
            },
            label = stringResource(R.string.watch_install_address),
            isError = addressInvalid,
            supportingText = if (addressInvalid) stringResource(R.string.watch_install_not_found) else null,
            singleLine = true,
            modifier = Modifier.padding(top = 10.dp),
        )
        FluidTextField(
            value = code,
            onValueChange = {
                code = it.filter(Char::isDigit).take(6)
                codeInvalid = false
            },
            label = stringResource(R.string.watch_install_code),
            isError = codeInvalid,
            supportingText = if (codeInvalid) stringResource(R.string.watch_install_wrong_code) else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.padding(top = 10.dp),
        )
        // The other address on the watch: the main Wireless debugging screen's, which is where
        // the app is installed once paired. Filled by itself when the phone hears the watch;
        // typed when the network keeps it quiet.
        FluidTextField(
            value = shownConnect,
            onValueChange = { connectAddress = it },
            label = stringResource(R.string.watch_install_connect_address),
            supportingText = stringResource(R.string.watch_install_connect_hint),
            singleLine = true,
            modifier = Modifier.padding(top = 10.dp),
        )
        pickedApk?.let { uri ->
            Text(
                stringResource(R.string.watch_install_picked, uri.lastPathSegment?.substringAfterLast('/').orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Text(
            describe(step),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        // The download of the watch app is the one step with a size to report, and it was the
        // slowest to look at: a line of text that never changed. A bar that fills when the total
        // is known, and a travelling one when it is not.
        (step as? WatchInstaller.Step.Downloading)?.let { downloading ->
            val fraction = downloading.progress
            if (fraction != null) {
                FluidProgressBar(progress = { fraction }, modifier = Modifier.padding(top = 8.dp))
            } else {
                FluidIndeterminateBar(modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
    val busy = step !is WatchInstaller.Step.Idle && step !is WatchInstaller.Step.Done && step !is WatchInstaller.Step.Failed
    if (!busy) {
        RowDivider()
        ActionRow(stringResource(R.string.watch_install_go), destructive = false) {
            val host = shownAddress.substringBeforeLast(':').trim()
            val port = shownAddress.substringAfterLast(':', "").trim().toIntOrNull()
            // Said on the fields rather than silently doing nothing: the press that seemed to be
            // ignored left the person guessing which of the two was wrong.
            addressInvalid = host.isEmpty() || port == null
            codeInvalid = code.length != 6
            if (addressInvalid || codeInvalid || port == null) return@ActionRow
            // The connect port as shown: what was typed when something was, what the phone heard
            // on the network otherwise.
            val typedConnect = shownConnect.substringAfterLast(':', "").trim().toIntOrNull()
            val apk = pickedApk
            installer.install(host, port, code, typedConnect ?: found.connectPort) { progress ->
                if (apk != null) app.wearBridge.updates.apkFromUri(apk) else app.wearBridge.updates.latestApk(progress)
            }
        }
        RowDivider()
        ActionRow(stringResource(R.string.watch_install_pick), destructive = false) {
            pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
        }
    }
}

class WatchInstallViewModel(application: android.app.Application) : AndroidViewModel(application) {
    val installer = WatchInstaller(application)
    override fun onCleared() { installer.close() }
}

@Composable
private fun describe(step: WatchInstaller.Step): String = when (step) {
    WatchInstaller.Step.Idle -> stringResource(R.string.watch_install_idle)
    WatchInstaller.Step.Pairing -> stringResource(R.string.watch_install_pairing)
    WatchInstaller.Step.Connecting -> stringResource(R.string.watch_install_connecting)
    is WatchInstaller.Step.Downloading -> stringResource(R.string.watch_install_downloading)
    WatchInstaller.Step.Installing -> stringResource(R.string.watch_install_installing)
    WatchInstaller.Step.Finishing -> stringResource(R.string.watch_install_finishing)
    WatchInstaller.Step.Done -> stringResource(R.string.watch_install_done)
    is WatchInstaller.Step.Failed -> when (step.reason) {
        WatchInstaller.Reason.WRONG_CODE -> stringResource(R.string.watch_install_wrong_code)
        WatchInstaller.Reason.NETWORK -> stringResource(R.string.watch_install_network)
        WatchInstaller.Reason.TIMEOUT -> stringResource(R.string.watch_install_timeout)
        WatchInstaller.Reason.NOT_FOUND -> stringResource(R.string.watch_install_not_found)
        WatchInstaller.Reason.REFUSED -> stringResource(R.string.watch_install_refused)
        WatchInstaller.Reason.NO_APK -> stringResource(R.string.watch_install_no_apk)
        WatchInstaller.Reason.NO_RELEASE -> stringResource(R.string.watch_install_no_release)
        WatchInstaller.Reason.INSTALL_FAILED -> stringResource(R.string.watch_install_failed, step.detail)
    }
}
