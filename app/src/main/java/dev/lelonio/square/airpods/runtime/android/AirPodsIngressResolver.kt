// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime.android

import dev.lelonio.square.BuildConfig
import android.content.Context
import android.os.Build
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/** Test provenance only. Root still freezes source/APK hash; this is not conformance. */
internal fun airPodsQualificationBuild(context: Context): String? = runCatching {
    val installed = context.packageManager.getPackageInfo(context.packageName, 0)
    "${Build.FINGERPRINT}/${BuildConfig.VERSION_CODE}/${installed.lastUpdateTime}"
}.getOrNull()

/** Opaque process object. Presentation never writes PairVerification or product conformance. */
class AirPodsFunctionalUiAuthority internal constructor(
    private val owner: AirPodsAndroidController,
    private val current: () -> Boolean,
) {
    fun permits(generation: Long): Boolean = BuildConfig.DEBUG && current() &&
        owner.state.value.generation == generation && owner.functionalUiAuthorized()
}

/** Selected once per UI composition, then rechecked at every dispatch and cold callback.
 * Identity prevents equal generation numbers in replacement scratch owners from matching. */
internal class AirPodsIngressSelection internal constructor(
    val owner: AirPodsAndroidController,
    val epoch: Long,
    val current: () -> Boolean,
    val hostVisible: (Boolean) -> Unit,
    val stop: () -> Unit,
    uiAllowed: () -> Boolean,
) {
    val authority = AirPodsFunctionalUiAuthority(owner) { current() && uiAllowed() }
}

/** User surfaces only. Background triggers continue to use their own explicit authority.
 * Release cannot publish; get() remains the production singleton. */
internal object AirPodsIngressResolver {
    private var nextEpoch = 0L
    private val mutable = MutableStateFlow<AirPodsIngressSelection?>(null)
    val selection = mutable.asStateFlow()
    @OptIn(ExperimentalCoroutinesApi::class)
    val presentation: Flow<AirPodsIngressSelection?> = selection.flatMapLatest { entry ->
        if (entry == null) flowOf(null)
        else combine(entry.owner.state, entry.owner.runtime) { _, _ -> entry.takeIf { it.current() } }
    }
    @Synchronized fun publish(owner: AirPodsAndroidController, entitled: () -> Boolean,
        visible: (Boolean) -> Unit, stop: () -> Unit, uiAllowed: () -> Boolean = { false }): AirPodsIngressSelection {
        check(BuildConfig.DEBUG && owner.isFunctionalTest && mutable.value == null)
        lateinit var entry: AirPodsIngressSelection
        entry = AirPodsIngressSelection(owner, ++nextEpoch,
            { mutable.value === entry && entitled() && owner.functionalIngressCurrent() }, visible, stop, uiAllowed)
        mutable.value = entry
        return entry
    }
    @Synchronized fun detach(entry: AirPodsIngressSelection?) {
        if (entry != null && mutable.value === entry) mutable.value = null
    }
    fun resolve(): AirPodsAndroidController? = mutable.value?.takeIf { it.current() }?.owner ?: AirPodsAndroidRuntime.get()
}

/** Implemented only in debug. Durable qualification opt-in is separate from any permit.
 * Each process issues a fresh permit, and C4 still reconciles before observation/acquisition. */
internal interface AirPodsQualificationLifecycleAuthority {
    fun valid(pair: dev.lelonio.square.airpods.runtime.storage.PairAddress, consent: Long): Boolean
    fun sealExplicitConsent(pair: dev.lelonio.square.airpods.runtime.storage.PairAddress, consent: Long): Boolean
    fun sealObservation(registration: dev.lelonio.square.airpods.runtime.lifecycle.ObservationRegistration?): Boolean = true
}

/** Same-execution observed setup only; no old permit or durable product verification. */
internal class AirPodsAudioSetupReceipt internal constructor(
    val pair: dev.lelonio.square.airpods.runtime.storage.PairAddress,
    val build: String,
    val observedGeneration: Long,
    val observedAt: Long,
    private val now: () -> Long,
) {
    @Volatile private var revoked = false
    val end get() = observedAt + AirPodsFunctionalTestPermit.MAX_WINDOW
    fun revoke() { revoked = true }
    fun valid(pair: dev.lelonio.square.airpods.runtime.storage.PairAddress, build: String): Boolean =
        !revoked && this.pair == pair && this.build == build && now() - observedAt in 0 until AirPodsFunctionalTestPermit.MAX_WINDOW
}
