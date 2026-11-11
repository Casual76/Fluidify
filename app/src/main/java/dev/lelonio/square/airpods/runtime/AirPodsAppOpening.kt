// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods.runtime

/** Activity-local, one-shot opening signal. Visibility and activity-result delivery are not
 * openings. MainActivity reports user departures separately from programmatic launches, so
 * Home/Recents can restore an opted-in session without treating a permission/settings return
 * or configuration recreation as another request. This class grants no consent or admission. */
internal class AirPodsAppOpening {
    private var pending = false
    private var userLeft = false
    private var externalLaunch = false

    fun created(restored: Boolean, ordinaryIntent: Boolean, savedUserOpening: Boolean = false) {
        pending = ordinaryIntent && (!restored || savedUserOpening)
    }

    fun newIntent(ordinaryIntent: Boolean) {
        pending = ordinaryIntent
        userLeft = false
        externalLaunch = !ordinaryIntent
    }

    fun externalActivityLaunched() {
        pending = false
        userLeft = false
        externalLaunch = true
    }

    fun userLeaveHint() { if (!externalLaunch) userLeft = true }

    /** A permission dialog may pause without starting an Activity through our public hooks.
     * Clear an unconsumed cold opening before it can be saved/restored across that dialog. */
    fun paused() {
        if (!userLeft) { pending = false; externalLaunch = true }
    }

    fun stopped(changingConfigurations: Boolean) {
        if (userLeft && !externalLaunch && !changingConfigurations) pending = true
        userLeft = false
    }

    /** Saved before/after onStop: ordinary background restoration retains its opening signal;
     * external result restoration and rotation do not gain one. */
    fun saveUserOpening(): Boolean = (pending || userLeft) && !externalLaunch

    fun resumed(): Boolean {
        val opened = pending && !externalLaunch
        pending = false
        userLeft = false
        externalLaunch = false
        return opened
    }
}
