package dev.lelonio.square.ui.navigation

import android.content.Context
import android.content.ContextWrapper
import dev.lelonio.square.data.AccountLocalDataCoordinator
import dev.lelonio.square.data.ContextCacheStore
import java.io.File

/**
 * A track cache belongs to an authority, never to a Navigation entry. Legacy shared files have
 * no account provenance and are intentionally not imported. This changes no online/offline gate.
 * An old/non-cooperative write can only touch its old namespace, even after logout/re-login.
 */
internal class PlaylistContextCaches(private val context: Context) {
    fun memoryKey(uri: String, access: AccountLocalDataCoordinator.Access) = "${access.binding.lineage}:$uri"

    // ContextCacheStore uses a 32-bit filename hash. A collision is a cache miss, never another page.
    suspend fun read(uri: String, access: AccountLocalDataCoordinator.Access): ContextCacheStore.Entry? =
        disk(access).read(uri)?.takeIf { it.uri == uri }

    fun disk(access: AccountLocalDataCoordinator.Access): ContextCacheStore {
        val directory = File(context.applicationContext.cacheDir, "playlist-authorities/${access.binding.lineage}")
        val scoped = object : ContextWrapper(context.applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = directory
        }
        return ContextCacheStore(scoped)
    }
}
