package dev.lelonio.square.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Synthetic authority and isolated Android storage. Never contacts Spotify or uses real identities. */
internal class AccountLocalDataFixture {
    var history: HistoryAccess? = null
    var epoch: Long? = null
    var nativeId = ""
    private var generation = 0L
    var receipt: String? = null
    var kept: dev.lelonio.square.auth.LocalSpotifyAuthority.KeptIdentity? = null
    var nativeReady = true
    val authority = dev.lelonio.square.auth.LocalSpotifyAuthority({ receipt }, { receipt = it }, { kept })
    val accounts = AccountLocalDataCoordinator({ history }, { epoch }, { nativeId }, authority, { nativeReady })

    suspend fun connect(id: String = "account-a"): AccountLocalDataCoordinator.Access {
        accounts.revoke()
        history = HistoryAccess(HistoryAccount(id), ++generation)
        kept = dev.lelonio.square.auth.LocalSpotifyAuthority.KeptIdentity(id, "synthetic-credential-$generation")
        nativeReady = true
        epoch = generation
        nativeId = id
        check(accounts.verifyNativeAccount())
        return checkNotNull(accounts.capture())
    }
    fun logout() { accounts.revoke(); history = null; epoch = null; nativeReady = false }
}

internal suspend fun testAccountAuthority(): AccountLocalDataCoordinator =
    AccountLocalDataFixture().also { it.connect() }.accounts

internal fun accountDataTestContext(): Context {
    val base = ApplicationProvider.getApplicationContext<Context>()
    val directory = Files.createTempDirectory(base.cacheDir.toPath(), "account-data-").toFile()
    val suffix = UUID.randomUUID().toString()
    return object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
        override fun getNoBackupFilesDir(): File = File(directory, "no-backup").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$name-$suffix", mode)
    }
}
