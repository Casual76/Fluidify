package dev.lelonio.square.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
    val installer = remember { WatchInstaller(app) }
    DisposableEffect(Unit) {
        installer.startDiscovery()
        onDispose { installer.stopDiscovery() }
    }
    val found by installer.found.collectAsStateWithLifecycle()
    val step by installer.step.collectAsStateWithLifecycle()
    var address by rememberSaveable { mutableStateOf("") }
    var connectAddress by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var pickedApk by rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) pickedApk = uri }
    val shownAddress = address.ifBlank { found.host?.let { host -> found.pairingPort?.let { "$host:$it" } }.orEmpty() }
    val shownConnect = connectAddress.ifBlank { found.host?.let { host -> found.connectPort?.let { "$host:$it" } }.orEmpty() }

    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp)) {
        Text(
            stringResource(R.string.watch_install_steps),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = shownAddress,
            onValueChange = { address = it },
            label = { Text(stringResource(R.string.watch_install_address)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(6) },
            label = { Text(stringResource(R.string.watch_install_code)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        // The other address on the watch: the main Wireless debugging screen's, which is where
        // the app is installed once paired. Filled by itself when the phone hears the watch;
        // typed when the network keeps it quiet.
        OutlinedTextField(
            value = shownConnect,
            onValueChange = { connectAddress = it },
            label = { Text(stringResource(R.string.watch_install_connect_address)) },
            supportingText = { Text(stringResource(R.string.watch_install_connect_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
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
    }
    val busy = step !is WatchInstaller.Step.Idle && step !is WatchInstaller.Step.Done && step !is WatchInstaller.Step.Failed
    if (!busy) {
        RowDivider()
        ActionRow(stringResource(R.string.watch_install_go), destructive = false) {
            val host = shownAddress.substringBeforeLast(':').trim()
            val port = shownAddress.substringAfterLast(':', "").trim().toIntOrNull()
            if (host.isEmpty() || port == null || code.length != 6) return@ActionRow
            // The connect port as typed when it was, the one heard on the network otherwise.
            val typedConnect = connectAddress.substringAfterLast(':', "").trim().toIntOrNull()
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
        WatchInstaller.Reason.NOT_FOUND -> stringResource(R.string.watch_install_not_found)
        WatchInstaller.Reason.REFUSED -> stringResource(R.string.watch_install_refused)
        WatchInstaller.Reason.NO_APK -> stringResource(R.string.watch_install_no_apk)
        WatchInstaller.Reason.NO_RELEASE -> stringResource(R.string.watch_install_no_release)
        WatchInstaller.Reason.INSTALL_FAILED -> stringResource(R.string.watch_install_failed, step.detail)
    }
}
