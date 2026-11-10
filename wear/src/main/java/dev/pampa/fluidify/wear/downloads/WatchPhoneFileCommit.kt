package dev.pampa.fluidify.wear.downloads

import dev.pampa.fluidify.wear.link.PhoneAccountSession
import dev.pampa.fluidify.wear.protocol.FileHeader

/** Final inbound-file admission, shared by the real channel path and synthetic-file regressions. */
internal class WatchPhoneFileCommit(
    private val sessions: PhoneAccountSession,
    private val phone: PhoneAccountSession.Scope,
    private val authority: WatchDownloadAuthority,
    private val local: WatchDownloadAuthority.Access,
    private val files: WatchDownloadFiles,
) {
    fun adopt(track: String, header: FileHeader): Boolean {
        if (!header.ok || header.accountSession != phone.session || phone.namespace != local.namespace ||
            !sessions.isCurrent(phone)) return false
        val sidecar = header.sidecar ?: return false
        return sessions.use(phone) { authority.use(local) {
            val part = files.partFile(track)
            if (part == null || !part.isFile || part.length() != header.totalBytes) false
            else files.adopt(track, sidecar)
        } }
    }
}
