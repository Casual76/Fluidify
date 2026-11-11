package dev.lelonio.square.ui.requests

import dev.lelonio.square.data.AccountLocalDataCoordinator
import kotlinx.coroutines.flow.collect

/**
 * One change of account access reaches a screen twice: through the coordinator's observer,
 * synchronously and on whichever thread made the change, and through its StateFlow, which
 * the main-thread collector answers by starting that access's work.
 *
 * Nothing orders the two. From another thread the observer comes first, because the
 * collector cannot capture the access until the change lets go of its lock. On the main
 * thread the StateFlow resumes the collector inline, inside the assignment, so the work is
 * already running when the observer arrives — and an observer that revokes whatever it
 * finds cancels it, with nothing left to start it again. An online verification made on
 * the main thread ended that way: the Home on "Connecting" for good.
 *
 * So the collector records the access it has answered and the observer is told whether its
 * own is that one. Identity only: the coordinator publishes each access object once, and
 * this neither captures, validates nor grants one.
 */
internal class AccountAccessTransitions(private val accounts: AccountLocalDataCoordinator) {
    @Volatile private var answered: AccountLocalDataCoordinator.Access? = null

    /**
     * [revoke] runs for every change, on the thread that made it. Its second argument is true
     * when the collector has already answered that very access: what it started is current
     * and must be left alone, while whatever the previous access owned still goes.
     */
    fun observe(
        revoke: (observed: AccountLocalDataCoordinator.Access?, answered: Boolean) -> Unit,
    ): AutoCloseable = accounts.observe { observed ->
        revoke(observed, observed != null && observed === answered)
    }

    /** Never returns. [answer] runs in the collecting coroutine, for an access and for its absence. */
    suspend fun collect(answer: suspend (AccountLocalDataCoordinator.Access?) -> Unit) {
        accounts.access.collect { access ->
            answered = access
            answer(access)
        }
    }
}
