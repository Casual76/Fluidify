package dev.lelonio.square.airpods.runtime

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Activity visibility only. Does not create a controller, open Bluetooth, grant consent,
 * clear suppression or start a service. A service-created process starts with false.
 */
class AirPodsAppVisibility : Application.ActivityLifecycleCallbacks {
    private var application: Application? = null
    private val resumed = Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
    private val mutable = MutableStateFlow(false)
    val active: StateFlow<Boolean> = mutable.asStateFlow()

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
