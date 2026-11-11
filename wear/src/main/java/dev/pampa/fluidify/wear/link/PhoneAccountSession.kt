package dev.pampa.fluidify.wear.link

import android.content.Context
import android.content.SharedPreferences
import dev.pampa.fluidify.wear.protocol.AccountState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Phone-issued correlation only. It cannot authenticate Spotify or choose a phone account. */
class PhoneAccountSession internal constructor(private val prefs: SharedPreferences) {
    data class Scope(val namespace: String, val session: String)
    private val gate = Any()
    private val mutable = MutableStateFlow(read())
    val scope = mutable.asStateFlow()
    fun capture(): Scope? = synchronized(gate) { mutable.value }
    fun isCurrent(value: Scope): Boolean = synchronized(gate) { mutable.value == value }
    fun <T> use(value: Scope, block: () -> T): T = synchronized(gate) {
        if (mutable.value != value) throw kotlinx.coroutines.CancellationException("Phone account changed")
        block()
    }
    fun useIfCurrent(value: Scope?, block: () -> Unit): Boolean = synchronized(gate) {
        if (mutable.value != value) return@synchronized false
        block()
        true
    }
    fun accept(account: AccountState) = synchronized(gate) {
        val previousAt = prefs.getLong("changed-at", 0)
        if (account.changedAtEpochMs > 0 && account.changedAtEpochMs < previousAt) return@synchronized false
        val namespace = account.localDataNamespace
        val session = account.localDataSession
        val next = if (account.signedIn && !namespace.isNullOrBlank() &&
            !session.isNullOrBlank()) Scope(namespace, session) else null
        check(prefs.edit().putString("namespace", next?.namespace).putString("session", next?.session)
            .putLong("changed-at", maxOf(previousAt, account.changedAtEpochMs)).commit())
        mutable.value = next
        true
    }
    fun clear() = synchronized(gate) {
        check(prefs.edit().remove("namespace").remove("session").commit())
        mutable.value = null
    }
    private fun read(): Scope? {
        val namespace = prefs.getString("namespace", null) ?: return null
        val session = prefs.getString("session", null) ?: return null
        return Scope(namespace, session)
    }
    companion object {
        private val instances = mutableMapOf<String, PhoneAccountSession>()
        fun forContext(context: Context): PhoneAccountSession = synchronized(instances) {
            val app = context.applicationContext
            instances.getOrPut(app.filesDir.absolutePath) {
                PhoneAccountSession(app.getSharedPreferences("phone-account-session", Context.MODE_PRIVATE))
            }
        }
    }
}
