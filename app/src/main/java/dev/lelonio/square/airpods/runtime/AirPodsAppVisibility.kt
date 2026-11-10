package dev.lelonio.square.airpods.runtime

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Source-side foreground fence. Writes belong to the existing lifecycle callbacks, not a
 * collector: false then true must invalidate queued user actions even when Boolean delivery
 * conflates them. This state registers nothing and grants no controller/session authority.
 */
class AirPodsForegroundState private constructor(
    private val mutable: MutableStateFlow<Boolean>,
) : StateFlow<Boolean> by mutable {
    constructor() : this(MutableStateFlow(false))
    private var epoch = 0L
    override var value: Boolean
        @Synchronized get() = mutable.value
        @Synchronized set(value) {
            if (mutable.value && !value) epoch++
            mutable.value = value
        }
    /** Explicit ownership transfer can retire queued actions while another authorized UI is visible. */
    @Synchronized fun invalidateForeground() { epoch++ }
    @Synchronized fun captureForeground(): Long? = if (mutable.value) epoch else null
    @Synchronized fun isCurrentForeground(captured: Long): Boolean = mutable.value && epoch == captured
}

/** Activity visibility only. Does not create a controller, open Bluetooth, grant consent,
 * clear suppression or start a service. A service-created process starts with false.
 */
class AirPodsAppVisibility : Application.ActivityLifecycleCallbacks {
    private var application: Application? = null
    private val resumed = Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
    private val mutable = AirPodsForegroundState()
    val active: StateFlow<Boolean> = mutable

    @Synchronized fun attach(value: Application) {
        if (application === value) return
        check(application == null) { "Visibility observer already attached" }
        application = value
        value.registerActivityLifecycleCallbacks(this)
    }

    @Synchronized fun detach() {
        application?.unregisterActivityLifecycleCallbacks(this)
        application = null
        resumed.clear()
        mutable.value = false
    }

    @Synchronized override fun onActivityResumed(activity: Activity) {
        if (application == null) return
        resumed.add(activity)
        mutable.value = true
    }

    @Synchronized override fun onActivityPaused(activity: Activity) {
        resumed.remove(activity)
        mutable.value = resumed.isNotEmpty()
    }

    @Synchronized override fun onActivityDestroyed(activity: Activity) {
        resumed.remove(activity)
        mutable.value = resumed.isNotEmpty()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
