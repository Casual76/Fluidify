package dev.pampa.fluidify.wear.update

import android.content.Context
import dev.antigravity.fluidengine.foundation.compareVersions
import dev.pampa.fluidify.wear.BuildConfig

/**
 * Noticing that the watch has just been updated, and keeping what the update brought until it
 * has been read.
 *
 * The notes travel with the phone's offer (see UpdateOffer.changelog) and are written down when
 * the offer is taken, because the version that installs them is a different process: by the time
 * it first runs, the old one is gone with everything it held in memory. So the notes go to the
 * preferences, tagged with the version they are for, and the new version, the first time it
 * starts, finds them there.
 *
 * All of this lives in the same preferences file as [WatchUpdater], and reads it directly: it is
 * called from the application's start, before anything has asked for the updater, which is
 * deliberately built only when needed.
 */
internal object WhatsNew {
    const val PREFS = "watch_update"

    /** The version that last started, to tell the first start of a new one. */
    const val KEY_LAST_RUN = "last_run_version"

    /** The notes of the last accepted offer, and the version they belong to. */
    const val KEY_NOTES_VERSION = "notes_version"
    const val KEY_NOTES = "notes"

    /** The notes of the version now running, until the person has read them. */
    const val KEY_NEWS_VERSION = "news_version"
    const val KEY_NEWS = "news"

    /**
     * Whether [current] is a newer version than the one that [lastRun] says ran before it.
     *
     * A watch that has never recorded a version ([lastRun] null: the first run of an install, or
     * of a build from before this was kept) is not "just updated": there is nothing to announce
     * to someone who has only just opened it. Nor is an older version (a development build put
     * back), whatever it replaced.
     */
    fun justUpdated(lastRun: String?, current: String): Boolean =
        lastRun != null && compareVersions(current, lastRun) > 0

    /**
     * Called as the process starts: if this is the first start of a newer version, writes down
     * what is new and says so in a quiet notification that opens the More page.
     */
    fun onStart(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = BuildConfig.VERSION_NAME
        val last = prefs.getString(KEY_LAST_RUN, null)
        if (last == current) return
        val edit = prefs.edit().putString(KEY_LAST_RUN, current)
        if (!justUpdated(last, current)) {
            edit.apply()
            return
        }
        // Only notes that were written for this very version: the ones of an offer that was
        // accepted and then superseded by another are not what this one brought.
        val notes = prefs.getString(KEY_NOTES, "").orEmpty()
            .takeIf { prefs.getString(KEY_NOTES_VERSION, null) == current }.orEmpty()
        edit.putString(KEY_NEWS_VERSION, current).putString(KEY_NEWS, notes).apply()
        WatchUpdateNotifications.showUpdated(context, current, hasNotes = notes.isNotBlank())
    }

    /** What the running version brought, if it has not been read yet and there is anything to say. */
    fun pending(context: Context): WatchUpdater.News? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = prefs.getString(KEY_NEWS_VERSION, null)?.takeIf { it == BuildConfig.VERSION_NAME } ?: return null
        val notes = prefs.getString(KEY_NEWS, "").orEmpty()
        return if (notes.isBlank()) null else WatchUpdater.News(version, notes)
    }
}
