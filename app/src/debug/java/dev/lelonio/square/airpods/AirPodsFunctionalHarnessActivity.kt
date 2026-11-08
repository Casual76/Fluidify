// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.lelonio.square.airpods.protocol.*
import dev.lelonio.square.airpods.runtime.storage.*
import dev.lelonio.square.ui.theme.SquareTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

/** The existing nonexported diagnostic Activity subclasses this debug-only screen.
 * It ignores ALL Intent extras and saved state. Entry itself grants no entitlement. */
open class AirPodsFunctionalHarnessActivity : ComponentActivity() {
    private val activityVisible = MutableStateFlow(false)
    private var session by mutableStateOf<AirPodsFunctionalHarness?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        session = AirPodsFunctionalHarness.existing()
        setContent { SquareTheme { Surface {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TEST AirPods · gate fisico NV", style = MaterialTheme.typography.titleLarge)
                Text("Conformance prodotto FALSE. Consensi e dati temporanei separati. Nessun esito PASS automatico.")
                var pairs by remember { mutableStateOf(emptyList<Pair<String, PairAddress>>()) }
                var selected by remember { mutableStateOf<PairAddress?>(null) }
                var message by remember { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }
                val current = session
                Text(message)
                if (current == null) {
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 104)
                        else if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 105)
                        else runCatching {
                            pairs = getSystemService(BluetoothManager::class.java).adapter?.bondedDevices.orEmpty().map {
                                (it.name ?: "Dispositivo abbinato") to PairAddress(it.address)
                            }
                        }.onFailure { message = "Permesso Bluetooth o elenco non disponibile" }
                    }) { Text("Permessi reali / elenca abbinati") }
                    pairs.forEach { (name, pair) ->
                        TextButton(onClick = { selected = pair }) { Text("${if (selected == pair) "? " else ""}$name") }
                    }
                    Button(enabled = selected != null && !busy, onClick = {
                        val pair = selected ?: return@Button
                        busy = true
                        lifecycleScope.launch {
                            try {
                                val (owner, result) = AirPodsFunctionalHarness.start(this@AirPodsFunctionalHarnessActivity, pair, activityVisible)
                                session = owner; message = result.toString()
                            } catch (_: Exception) { message = "Avvio negato; arrestare la sessione precedente o verificare Bluetooth" }
                            finally { busy = false }
                        }
                    }) { Text("Avvia TEST esplicito su questa coppia") }
                } else {
                    val runtime by current.controller.runtime.collectAsState()
                    val state by current.controller.state.collectAsState()
                    Text("${state.stage} · bootstrap/RX=${runtime.setupVerified} · media=${runtime.mediaAvailable} · ${runtime.failure ?: ""}")
                    Text("Identità RX: ${state.identityReceived?.value?.modelNumber ?: "unknown"}; batterie: ${state.batteries}")
                    Text("Pending: ${state.pending.keys}; esiti: ${state.outcomes}")
                    Text("Ears: ${state.ears}; testa: ${state.head}; formato attivo: ${state.activePacketFormat}")
                    val ready = runtime.active && runtime.setupVerified
                    Text("I comandi richiedono INFORMATION fresca, bootstrap e RX di base. Candidati e RX restano distinti.")
                    AirPodsProperties.controls.filterNot { it.internalDependency }.forEach { spec ->
                        var input by remember(spec.property) { mutableStateOf("") }
                        val value = input.toIntOrNull()?.let { PropertyValue.Number(it) }
                        OutlinedTextField(value = input, onValueChange = { input = it },
                            label = { Text("${spec.catalogId} (${spec.range})") }, singleLine = true)
                        if (spec.options.isNotEmpty()) Text(spec.options.toString())
                        Text("RX: ${state.propertyObserved(spec.property)?.value ?: "unknown / da verificare"}")
                        Button(enabled = ready && value != null && state.canAttemptWrite(spec.property, value), onClick = {
                            value?.let { message = "Richiesta: ${current.controller.setProperty(spec.property, it)}; conferma solo da RX" }
                        }) { Text("Invia ${spec.property}") }
                    }
                    var name by remember { mutableStateOf("") }
                    OutlinedTextField(name, { name = it }, label = { Text("Nome dispositivo (UTF-8)") })
                    Button(enabled = ready && state.canAttemptWrite(CommandProperty.NAME, PropertyValue.Name(name)), onClick = { current.controller.rename(name) }) { Text("Invia nome") }
                    var eqOn by remember { mutableStateOf(false) }
                    var eqLow by remember { mutableStateOf("50") }; var eqMid by remember { mutableStateOf("50") }; var eqHigh by remember { mutableStateOf("50") }
                    Switch(eqOn, { eqOn = it }); Text("EQ abilitato")
                    OutlinedTextField(eqLow, { eqLow = it }, label = { Text("EQ low 0..100") })
                    OutlinedTextField(eqMid, { eqMid = it }, label = { Text("EQ mid 0..100") })
                    OutlinedTextField(eqHigh, { eqHigh = it }, label = { Text("EQ high 0..100") })
                    val eq = runCatching { PropertyValue.Eq(eqOn, eqLow.toInt(), eqMid.toInt(), eqHigh.toInt()) }.getOrNull()
                    Button(enabled = ready && eq != null && state.canAttemptWrite(CommandProperty.EQ, eq), onClick = { eq?.let(current.controller::setEq) }) { Text("Invia EQ") }
                    Button(enabled = ready, onClick = { current.controller.requestInformation() }) { Text("Richiedi INFORMATION") }
                    val mediaVolume by current.controller.volume.collectAsState()
                    Text("Volume media osservato: $mediaVolume")
                    var userVolume by remember { mutableStateOf("") }
                    OutlinedTextField(userVolume, { userVolume = it }, label = { Text("Volume media Android (indice intero)") })
                    Button(enabled = ready && userVolume.toIntOrNull() != null, onClick = {
                        userVolume.toIntOrNull()?.let { message = "Volume richiesto: ${current.controller.setVolume(it)}" }
                    }) { Text("Imposta volume TEST") }
                    Button(onClick = { current.controller.diagnostics.startCollection() }) { Text("Avvia raccolta diagnostica TEST") }
                    Button(onClick = { current.controller.diagnostics.stopCollection() }) { Text("Arresta raccolta diagnostica TEST") }
                    Button(onClick = { message = current.controller.sanitizedDiagnostics() }) { Text("Snapshot diagnostico TEST (NV)") }
                    var pairSettings by remember { mutableStateOf(PairSettings()) }
                    fun configure(update: (PairSettings) -> PairSettings) {
                        lifecycleScope.launch {
                            current.controller.configure(update).onSuccess { pairSettings = update(pairSettings) }
                        }
                    }
                    Text("Consensi di TEST · tutti OFF inizialmente")
                    val host = pairSettings.host
                    TestToggle("Automazioni", host.automationsEnabled) { v -> configure { it.copy(host = it.host.copy(automationsEnabled = v)) } }
                    TestToggle("Ear PAUSE", host.earPause) { v -> configure { it.copy(host = it.host.copy(earPause = v)) } }
                    TestToggle("Ear ripresa", host.earResume) { v -> configure { it.copy(host = it.host.copy(earResume = v)) } }
                    TestToggle("CA attenuazione", host.conversationDuck) { v -> configure { it.copy(host = it.host.copy(conversationDuck = v)) } }
                    TestToggle("CA pausa", host.conversationPause) { v -> configure { it.copy(host = it.host.copy(conversationPause = v)) } }
                    TestToggle("CA volume relativo", host.conversationRelative) { v -> configure { it.copy(host = it.host.copy(conversationRelative = v)) } }
                    var caVolume by remember { mutableStateOf("40") }
                    OutlinedTextField(caVolume, { caVolume = it }, label = { Text("CA percentuale 10..85") })
                    Button(enabled = caVolume.toIntOrNull()?.let { it in 10..85 } == true, onClick = { configure { it.copy(host = it.host.copy(conversationVolumePercent = caVolume.toInt())) } }) { Text("Salva CA volume TEST") }
                    Text(getString(dev.lelonio.square.R.string.airpods_call_explanation))
                    Button(onClick = { requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS), 106) }) {
                        Text(getString(dev.lelonio.square.R.string.airpods_call_permission))
                    }
                    TestToggle("Gesti testa", host.headGestures) { v -> configure { it.copy(host = it.host.copy(headGestures = v)) } }
                    TestToggle("Pacchetti testa alternativi", pairSettings.headPacketAlternate) { v -> configure { it.copy(headPacketAlternate = v) } }
                    HeadAction.entries.forEach { action ->
                        TextButton(onClick = { configure { it.copy(nodAction = action) } }) { Text("Nod: $action ${if (pairSettings.nodAction == action) "?" else ""}") }
                        TextButton(onClick = { configure { it.copy(shakeAction = action) } }) { Text("Shake: $action ${if (pairSettings.shakeAction == action) "?" else ""}") }
                    }
                    LongPressAction.entries.forEach { left -> LongPressAction.entries.forEach { right ->
                        Button(enabled = ready, onClick = { lifecycleScope.launch { current.controller.setLongPress(left, right) } }) { Text("Long press L=$left R=$right") }
                    } }
                    Button(enabled = ready, onClick = { current.controller.setTracking(true) }) { Text("Avvia tracking TEST") }
                    Button(enabled = ready, onClick = { current.controller.setTracking(false) }) { Text("Arresta tracking TEST") }
                    Text("Altre app: BEST EFFORT C6. Route inferita; PAUSE manuale già PAUSED può restare ignota. Il mix volume può includere altro audio. Nessun ACK causale esterno.")
                    Button(onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Apri accesso notifiche NLS (consenso Android)") }
                    TestToggle("Consento altre app BEST EFFORT per questo TEST", host.otherAppsEnabled) { v -> configure { it.copy(host = it.host.copy(otherAppsEnabled = v)) } }
                    TestToggle("Consento automazioni in background TEST", host.fluidifyBackgroundEnabled) { v -> configure { it.copy(host = it.host.copy(fluidifyBackgroundEnabled = v)) } }
                    TestToggle("Disconnect unworn (fallback Bluetooth Settings)", host.disconnectUnworn) { v -> configure { it.copy(host = it.host.copy(disconnectUnworn = v)) } }
                    Text("Finestra manuale: almeno 10 min, massimo 15 min. Nessun wake lock di collaudo: usare schermo spento e pausa oltre 90 s. In sospensione Android può ritardare il cleanup; il permesso scade comunque e blocca nuove scritture. Al termine tornare qui e arrestare il TEST. Uscita senza finestra chiude; nessuna ripresa nel cleanup.")
                    listOf(10, 15).forEach { minutes -> Button(enabled = ready, onClick = { message = "Finestra $minutes min armata: ${current.armBackground(minutes)}" }) { Text("Arma background TEST $minutes min") } }
                    Button(onClick = { lifecycleScope.launch { current.close(); session = null } }) { Text("Arresta TEST") }
                }
                Button(onClick = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Apri Bluetooth Settings") }
                Button(onClick = { if (session?.backgroundArmed != true) session?.requestClose(); finish() }) { Text("Esci dal collaudo") }
            }
        } } }
    }
    override fun onStart() { super.onStart(); activityVisible.value = true; session?.visible?.value = true }
    override fun onStop() { activityVisible.value = false; session?.leftVisibleUi(); super.onStop() }
}

@Composable private fun TestToggle(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f)); Switch(checked = value, onCheckedChange = change)
    }
}
