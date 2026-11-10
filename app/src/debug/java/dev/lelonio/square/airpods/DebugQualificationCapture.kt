// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods

import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun interface DebugCaptureFileWriter {
    fun write(file: File, bytes: ByteArray)
}

internal object DebugAtomicCaptureWriter : DebugCaptureFileWriter {
    override fun write(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }
}

/** Explicit debug gesture only. Neither snapshotting nor saving can start a TEST or acquire an owner.
 * Each save has its own private directory; receipt.json is the commit marker written last.
 * A failed save cannot overwrite a previous successful capture. No FileProvider or external URI.
 */
internal class DebugQualificationCapture(
    private val noBackupDirectory: () -> File,
    private val writer: DebugCaptureFileWriter = DebugAtomicCaptureWriter,
) {
    suspend fun save(evidence: DebugQualificationEvidence): DebugCaptureReceipt = withContext(Dispatchers.IO) {
        val snapshot = evidence.snapshot()
        val root = File(noBackupDirectory(), DIRECTORY)
        check(root.isDirectory || root.mkdirs()) { "CAPTURE_DIRECTORY_FAILED" }
        val id = UUID.randomUUID().toString()
        val directory = File(root, id)
        check(directory.mkdir()) { "CAPTURE_DIRECTORY_FAILED" }
        var published = false
        try {
            val journal = snapshot.journal.toByteArray(Charsets.UTF_8)
            val ear = snapshot.earProjection.toByteArray(Charsets.UTF_8)
            val receipt = DebugCaptureReceipt(id, snapshot.sequence, snapshot.rows,
                journal.size, sha256(journal), ear.size, sha256(ear))
            writeVerified(File(directory, JOURNAL), journal)
            writeVerified(File(directory, EAR), ear)
            writeVerified(File(directory, RECEIPT), receipt.json().toByteArray(Charsets.UTF_8))
            published = true
            receipt
        } finally {
            // Only the fresh app-generated directory is eligible for rollback.
            if (!published) directory.deleteRecursively()
        }
    }

    private fun writeVerified(file: File, bytes: ByteArray) {
        writer.write(file, bytes)
        check(file.readBytes().contentEquals(bytes)) { "CAPTURE_VERIFY_FAILED" }
    }

    companion object {
        const val DIRECTORY = "airpods_qualification_evidence_v1"
        const val JOURNAL = "journal.sanitized.txt"
        const val EAR = "ear.numeric.json"
        const val RECEIPT = "receipt.json"
        internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

internal data class DebugCaptureReceipt(
    val captureId: String, val snapshotSequence: Long, val rows: Int,
    val journalBytes: Int, val journalSha256: String, val earBytes: Int, val earSha256: String,
) {
    fun json(): String = """{"schema":1,"physical":"NV","productConformance":false,"captureId":"$captureId","snapshotSequence":$snapshotSequence,"rows":$rows,"journalBytes":$journalBytes,"journalSha256":"$journalSha256","earBytes":$earBytes,"earSha256":"$earSha256"}
"""
    fun summary(): String = "Salvato privato: no_backup/${DebugQualificationCapture.DIRECTORY}/$captureId\n" +
        "Snapshot=$snapshotSequence; righe=$rows; physical=NV\n" +
        "Journal: bytes=$journalBytes; sha256=$journalSha256\n" +
        "Ear: bytes=$earBytes; sha256=$earSha256"
}
