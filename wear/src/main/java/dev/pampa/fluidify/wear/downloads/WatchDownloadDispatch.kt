package dev.pampa.fluidify.wear.downloads

import java.io.File

/** Production native-dispatch boundary. The engine owns serialization and joins the real return. */
internal class WatchDownloadDispatch(
    private val authority: WatchDownloadAuthority,
    private val ready: () -> Boolean,
    private val canonical: () -> String,
    private val root: (String) -> Unit,
    private val filesDir: File,
) {
    fun run(access: WatchDownloadAuthority.Access, download: () -> Unit) {
        authority.use(access) { Unit }
        check(ready() && canonical() == access.canonical) { "No online watch authority" }
        authority.use(access) {
            root(File(filesDir, "downloads/accounts/${access.namespace}").absolutePath)
        }
        // The engine's mutex prevents another account/root or shutdown until this returns.
        authority.use(access) { Unit }
        download()
        authority.use(access) { Unit }
    }
}
