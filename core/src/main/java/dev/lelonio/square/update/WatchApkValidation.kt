package dev.lelonio.square.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** Same checks on both ends of the transfer and before an initial ADB install. */
object WatchApkValidation {
    const val MARKER = "dev.pampa.fluidify.WEAR_APK"
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** What a SHA-256 checksum looks like, written out. */
    val SHA256_HEX = Regex("[a-fA-F0-9]{64}")

    /**
     * Why [file] is not the watch APK for [version] with [checksum], or `null` when it is.
     *
     * [actualSha256] is the file's hash when the caller has just computed it, so a large APK is
     * not read through a second time for the same answer.
     */
    @Suppress("DEPRECATION")
    fun reject(
        context: Context,
        file: File,
        version: String,
        checksum: String,
        actualSha256: String? = null,
    ): String? {
        if (!checksum.matches(SHA256_HEX)) return "missing-checksum"
        if (!checksum.equals(actualSha256 ?: sha256(file), ignoreCase = true)) return "checksum"
        val signatureFlags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, signatureFlags or PackageManager.GET_META_DATA)
            ?: return "not-an-apk"
        if (info.packageName != context.packageName) return "wrong-package"
        if (info.versionName != version) return "wrong-version"
        if (info.applicationInfo?.metaData?.getBoolean(MARKER) != true) return "not-a-watch-apk"
        fun certificates(pkg: PackageInfo): Set<String> {
            val signers = if (Build.VERSION.SDK_INT >= 28) pkg.signingInfo?.apkContentsSigners else pkg.signatures
            return signers.orEmpty().map { signer ->
                MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).joinToString("") { "%02x".format(it) }
            }.toSet()
        }
        val ours = certificates(context.packageManager.getPackageInfo(context.packageName, signatureFlags))
        val theirs = certificates(info)
        if (ours.isEmpty() || theirs.isEmpty() || ours != theirs) return "wrong-signature"
        return null
    }
}
