package dev.pampa.fluidify.wear.ui.debug

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import dev.pampa.fluidify.wear.BuildConfig

/**
 * Slow frames in logcat, with what was on screen at the time — for checking on the watch itself
 * that a swipe to the Home or a page change no longer stutters.
 *
 * Built on the platform's frame metrics rather than a library: one listener, on its own thread,
 * only in builds that are not released. `adb logcat -s FluidifyFrames` shows lines like
 * "38 ms (budget 16) · swipe Home".
 */
object FrameLog {

    /** What the screen is doing, set by the navigation as it changes. */
    @Volatile
    var scene: String = "start"

    private var thread: HandlerThread? = null

    fun attach(activity: Activity) {
        if (BuildConfig.BUILD_TYPE == "release") return
        val worker = thread ?: HandlerThread("frame-log").also { it.start(); thread = it }
        val budgetNs = (1_000_000_000L / (activity.display?.refreshRate?.takeIf { it > 0f } ?: 60f)).toLong()
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (total > budgetNs * SLOW_FACTOR) {
                Log.i(TAG, "${total / 1_000_000} ms (budget ${budgetNs / 1_000_000}) · $scene")
            }
        }
        runCatching { activity.window.addOnFrameMetricsAvailableListener(listener, Handler(worker.looper)) }
    }

    private const val TAG = "FluidifyFrames"

    /** Twice the frame budget: one frame late is noise, two is a stutter you can see. */
    private const val SLOW_FACTOR = 2
}
