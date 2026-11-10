package dev.lelonio.square.data

/** Cache entries and continuations belong to the original capability, even on A -> B -> A. */
class AccountRequestCache<T>(private val accounts: AccountLocalDataCoordinator) {
    private val values = LinkedHashMap<Pair<AccountLocalDataCoordinator.Access, String>, T>()
    fun get(access: AccountLocalDataCoordinator.Access, key: String): T? = accounts.use(access) {
        values[access to key]
    }
    fun put(access: AccountLocalDataCoordinator.Access, key: String, value: T) = accounts.use(access) {
        values.keys.removeAll { it.first !== access }
        if (values.size >= 100) values.remove(values.keys.first())
        values[access to key] = value
    }
    fun remove(access: AccountLocalDataCoordinator.Access, key: String) = accounts.use(access) {
        values.remove(access to key)
    }
    suspend fun answer(access: AccountLocalDataCoordinator.Access, online: Boolean = false,
        fetch: suspend () -> T): T {
        if (online) accounts.requireOnline(access) else accounts.use(access) { Unit }
        val result = fetch()
        return accounts.use(access) {
            if (online) accounts.requireOnline(access)
            result
        }
    }
}
