package dev.lelonio.square.wear

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import dev.pampa.fluidify.wear.protocol.WearPaths
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest

/**
 * The phone's end of the Wearable Data Layer.
 *
 * Every call here is allowed to fail quietly: a phone with no watch, no Play
 * Services or no paired watch running this app is the ordinary case, and the
 * rest of Fluidify must not notice the difference. That is also why the watch is
 * looked up and cached rather than assumed: until a watch with the companion
 * installed is known to exist, nothing is written to the Data Layer at all.
 */
class WearLink(
    private val context: Context,
    private val connectedNodes: suspend () -> List<Node> = { Wearable.getNodeClient(context).connectedNodes.await() },
) {

    private val capabilities by lazy { Wearable.getCapabilityClient(context) }
    private val messages by lazy { Wearable.getMessageClient(context) }
    private val data by lazy { Wearable.getDataClient(context) }

    @Volatile private var installedCheckedAt = 0L
    @Volatile private var installed = false

    /** Every node with the watch app installed, reachable now or not. */
    suspend fun watchNodes(reachableOnly: Boolean = false): Set<Node> = catchingNonCancel {
        val filter = if (reachableOnly) CapabilityClient.FILTER_REACHABLE else CapabilityClient.FILTER_ALL
        capabilities.getCapability(WearPaths.CAPABILITY_WATCH, filter).await().nodes
    }.onFailure { Log.i(TAG, "no watch capability: ${it.message}") }.getOrDefault(emptySet())

    /**
     * Whether any watch has the companion installed. Cached for a few minutes:
     * asked on every service start, and the answer almost never changes.
     */
    suspend fun hasWatch(): Boolean {
        val now = System.currentTimeMillis()
        if (now - installedCheckedAt < INSTALLED_CACHE_MS) return installed
        val nodes = catchingNonCancel {
            capabilities.getCapability(WearPaths.CAPABILITY_WATCH, CapabilityClient.FILTER_ALL).await().nodes
        }.getOrElse {
            // A lookup that failed says nothing about the watch: not remembered as "none" for five
            // minutes, which stopped every state update for as long.
            Log.i(TAG, "no watch capability: ${it.message}")
            return installed
        }
        installed = nodes.isNotEmpty()
        installedCheckedAt = now
        return installed
    }

    /** A watch said hello, so one certainly exists. */
    fun noteWatchSeen() {
        installed = true
        installedCheckedAt = System.currentTimeMillis()
    }

    /** A hello already identifies the app. Check its live connection, not capability discovery again. */
    suspend fun awaitNearbyWatch(nodeId: String, timeoutMs: Long = 6_000): Boolean = withTimeoutOrNull(timeoutMs) {
        while (true) {
            val nodes = try { connectedNodes() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                Log.i(TAG, "connected watch lookup failed: ${error.javaClass.simpleName}")
                emptyList()
            }
            if (nodes.any { it.id == nodeId && it.isNearby }) return@withTimeoutOrNull true
            delay(300)
        }
        @Suppress("UNREACHABLE_CODE") false
    } ?: false

    suspend fun send(nodeId: String, path: String, bytes: ByteArray): Boolean = catchingNonCancel {
        messages.sendMessage(nodeId, path, bytes).await()
        true
    }.onFailure { Log.i(TAG, "message $path to $nodeId failed: ${it.message}") }.getOrDefault(false)

    /** Sends to every reachable watch. True when at least one took it. */
    suspend fun broadcast(path: String, bytes: ByteArray): Boolean {
        var any = false
        for (node in watchNodes(reachableOnly = true)) if (send(node.id, path, bytes)) any = true
        return any
    }

    suspend fun put(request: PutDataRequest): Boolean = catchingNonCancel {
        data.putDataItem(request).await()
        true
    }.onFailure { Log.i(TAG, "data ${request.uri} failed: ${it.message}") }.getOrDefault(false)

    suspend fun delete(path: String) {
        catchingNonCancel {
            data.deleteDataItems(android.net.Uri.Builder().scheme("wear").path(path).build()).await()
        }
    }

    companion object {
        private const val TAG = "WearLink"
        private const val INSTALLED_CACHE_MS = 5 * 60_000L

        /**
         * SHA-256 of this app's signing certificate, colon-separated. The watch
         * compares it with its own: the Data Layer drops traffic between two
         * builds signed with different keys without saying so.
         */
        fun certificateSha256(context: Context): String = runCatching {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                if (android.os.Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES,
            )
            @Suppress("DEPRECATION")
            val signers = if (android.os.Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners.orEmpty() else info.signatures.orEmpty()
            val first = signers.firstOrNull() ?: return ""
            MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
                .joinToString(":") { "%02X".format(it) }
        }.getOrDefault("")
    }
}
