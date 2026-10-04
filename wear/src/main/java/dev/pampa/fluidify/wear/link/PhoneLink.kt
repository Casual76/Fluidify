package dev.pampa.fluidify.wear.link

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import dev.pampa.fluidify.wear.BuildConfig
import dev.pampa.fluidify.wear.protocol.Command
import dev.pampa.fluidify.wear.protocol.CommandAck
import dev.pampa.fluidify.wear.protocol.CommandEnvelope
import dev.pampa.fluidify.wear.protocol.Compatibility
import dev.pampa.fluidify.wear.protocol.Hello
import dev.pampa.fluidify.wear.protocol.PlaybackSnapshot
import dev.pampa.fluidify.wear.protocol.Role
import dev.pampa.fluidify.wear.protocol.RpcMethod
import dev.pampa.fluidify.wear.protocol.RpcRequest
import dev.pampa.fluidify.wear.protocol.RpcResponse
import kotlinx.serialization.KSerializer
import dev.pampa.fluidify.wear.protocol.WearCodec
import dev.pampa.fluidify.wear.protocol.WearPaths
import dev.pampa.fluidify.wear.protocol.compatibility
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Why a request to the phone did not produce an answer. */
class RpcFailure(val reason: String) : Exception(reason)

/** What the controls need from a link: where it stands, and a way to send a command. */
interface CommandChannel {
    val status: StateFlow<LinkStatus>

    /** Sends [command] and returns the phone's acknowledgement, or null when there was none. */
    suspend fun send(command: Command): CommandAck?

    /** [send], waiting up to [timeoutMs] for the answer: for commands the phone takes a while over. */
    suspend fun send(command: Command, timeoutMs: Long): CommandAck? = send(command)
}

/** Where the conversation with the phone stands. */
enum class LinkStatus {
    /** Not looked yet. */
    UNKNOWN,

    /** The phone answered and speaks the same protocol. */
    CONNECTED,

    /** The phone app is installed but out of Bluetooth range, or Bluetooth is off. */
    UNREACHABLE,

    /** No phone with Fluidify was found at all. */
    NOT_FOUND,

    /** The phone is there but never answered: usually two builds signed with different keys. */
    NO_ANSWER,

    /** The two sides were signed with different keys. */
    SIGNATURE_MISMATCH,

    /** The phone app is older than this watch app and must be updated. */
    PHONE_OUTDATED,

    /** This watch app is older than the phone app. */
    WATCH_OUTDATED,
}

/**
 * The watch's end of the Wearable Data Layer.
 *
 * Finds the phone (the node advertising [WearPaths.CAPABILITY_PHONE], nearest
 * first), says hello, sends commands and matches their acknowledgements. No
 * polling and no heartbeat: the phone pushes state when it changes, and this
 * side only speaks when the person wearing the watch does something.
 */
class PhoneLink(
    private val context: Context,
    private val scope: CoroutineScope,
    private val state: WatchState,
    private val art: ArtStore,
) : CommandChannel {
    private val capabilities by lazy { Wearable.getCapabilityClient(context) }
    private val messages by lazy { Wearable.getMessageClient(context) }
    private val data by lazy { Wearable.getDataClient(context) }

    private val _status = MutableStateFlow(LinkStatus.UNKNOWN)
    override val status: StateFlow<LinkStatus> = _status.asStateFlow()

    private val _phone = MutableStateFlow<Hello?>(null)
    val phone: StateFlow<Hello?> = _phone.asStateFlow()

    private val ids = AtomicLong(System.currentTimeMillis())
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<CommandAck>>()
    private val rpcPending = ConcurrentHashMap<Long, CompletableDeferred<RpcResponse>>()
    private val authPending = ConcurrentHashMap<Long, CompletableDeferred<dev.pampa.fluidify.wear.protocol.AuthGrant>>()

    @Volatile private var nodeId: String? = null
    private var answerWatch: Job? = null

    /**
     * Finds the phone, introduces this watch and catches up on what the Data
     * Layer already holds. Called when a screen comes up.
     */
    fun connect() {
        scope.launch {
            val node = findPhone()
            if (node == null) {
                _status.value = if (findPhone(reachableOnly = false) != null) LinkStatus.UNREACHABLE else LinkStatus.NOT_FOUND
            } else {
                sayHello(node.id)
            }
            catchUp()
        }
    }

    /**
     * The phone said something (a new state, an answer): it is there. Before, the status was only
     * ever set by a hello or a failed send, so "phone unreachable" stayed in the title long after the
     * phone was back and its music was showing underneath, and an ack cleared only some of it.
     * The statuses that say something about the builds (outdated, other keys) are kept.
     */
    fun onPhoneHeard() {
        when (_status.value) {
            LinkStatus.UNKNOWN, LinkStatus.UNREACHABLE, LinkStatus.NOT_FOUND, LinkStatus.NO_ANSWER -> _status.value = LinkStatus.CONNECTED
            else -> Unit
        }
    }

    private val capabilityListener = CapabilityClient.OnCapabilityChangedListener { info ->
        val reachable = info.nodes.isNotEmpty()
        when {
            // Back in range: say hello again, which also settles whether the builds agree.
            reachable && _status.value != LinkStatus.CONNECTED -> connect()
            !reachable && _status.value == LinkStatus.CONNECTED -> _status.value = LinkStatus.UNREACHABLE
        }
    }

    /** Follows the phone coming and going while a screen is up; see [onPhoneHeard]. */
    fun watchReachability() {
        runCatching { capabilities.addListener(capabilityListener, WearPaths.CAPABILITY_PHONE) }
    }

    fun unwatchReachability() {
        runCatching { capabilities.removeListener(capabilityListener, WearPaths.CAPABILITY_PHONE) }
    }

    /** The phone's node when it is in reach, for a channel of one's own (the download transfers). */
    suspend fun reachablePhone(): String? = findPhone()?.id

    private suspend fun findPhone(reachableOnly: Boolean = true): Node? = runCatching {
        val filter = if (reachableOnly) CapabilityClient.FILTER_REACHABLE else CapabilityClient.FILTER_ALL
        val nodes = capabilities.getCapability(WearPaths.CAPABILITY_PHONE, filter).await().nodes
        (nodes.firstOrNull { it.isNearby })?.also { if (reachableOnly) nodeId = it.id }
    }.onFailure { Log.i(TAG, "phone lookup failed: ${it.message}") }.getOrNull()

    private suspend fun sayHello(node: String) {
        val sent = send(node, WearPaths.HELLO, WearCodec.encode(Hello.serializer(), ownHello()))
        if (!sent) {
            _status.value = LinkStatus.UNREACHABLE
            return
        }
        answerWatch?.cancel()
        answerWatch = scope.launch {
            delay(HELLO_ANSWER_MS)
            if (_phone.value == null) _status.value = LinkStatus.NO_ANSWER
        }
    }

    /** The phone answered a hello (or introduced itself). */
    fun onHello(hello: Hello, fromNode: String) {
        if (hello.role != Role.PHONE) return
        state.observeClock(hello.sentAtEpochMs)
        nodeId = fromNode
        _phone.value = hello
        // The phone handles updates while it is around; see WatchSelfUpdateWorker.
        context.getSharedPreferences("phone_link", Context.MODE_PRIVATE).edit()
            .putLong(dev.pampa.fluidify.wear.update.WatchSelfUpdateWorker.KEY_PHONE_SEEN, System.currentTimeMillis())
            .apply()
        answerWatch?.cancel()
        _status.value = when (compatibility(ownHello(), hello)) {
            Compatibility.OK -> LinkStatus.CONNECTED
            Compatibility.PEER_OUTDATED -> LinkStatus.PHONE_OUTDATED
            Compatibility.SELF_OUTDATED -> LinkStatus.WATCH_OUTDATED
            Compatibility.SIGNATURE_MISMATCH -> LinkStatus.SIGNATURE_MISMATCH
        }
        if (hello.wantsReply) {
            scope.launch {
                send(fromNode, WearPaths.HELLO, WearCodec.encode(Hello.serializer(), ownHello(wantsReply = false)))
            }
        }
    }

    /**
     * Sends [command] and waits for the phone's acknowledgement. Null when the
     * phone could not be reached or did not answer in time.
     */
    override suspend fun send(command: Command): CommandAck? = send(command, ACK_TIMEOUT_MS)

    override suspend fun send(command: Command, timeoutMs: Long): CommandAck? {
        val node = nodeId ?: findPhone()?.id ?: run {
            _status.value = LinkStatus.UNREACHABLE
            return null
        }
        val id = ids.incrementAndGet()
        val waiter = CompletableDeferred<CommandAck>()
        pending[id] = waiter
        val bytes = WearCodec.encode(CommandEnvelope.serializer(), CommandEnvelope(id, command))
        if (!send(node, WearPaths.COMMAND, bytes)) {
            pending.remove(id)
            nodeId = null
            _status.value = LinkStatus.UNREACHABLE
            return null
        }
        return withTimeoutOrNull(timeoutMs) { waiter.await() }.also { pending.remove(id) }
    }

    /**
     * Asks the phone something and decodes the answer with [serializer].
     *
     * A failure says why: unreachable, no answer in time, or the phone's own error.
     */
    suspend fun <T> request(method: RpcMethod, serializer: KSerializer<T>, timeoutMs: Long = RPC_TIMEOUT_MS): Result<T> {
        val node = nodeId ?: findPhone()?.id ?: return Result.failure(RpcFailure("unreachable"))
        val id = ids.incrementAndGet()
        val waiter = CompletableDeferred<RpcResponse>()
        rpcPending[id] = waiter
        if (!send(node, WearPaths.RPC, WearCodec.encode(RpcRequest.serializer(), RpcRequest(id, method)))) {
            rpcPending.remove(id)
            nodeId = null
            _status.value = LinkStatus.UNREACHABLE
            return Result.failure(RpcFailure("unreachable"))
        }
        val response = withTimeoutOrNull(timeoutMs) { waiter.await() }
        rpcPending.remove(id)
        if (response == null) return Result.failure(RpcFailure("timeout"))
        if (!response.ok) return Result.failure(RpcFailure(response.error ?: "error"))
        val payload = response.payload ?: return Result.failure(RpcFailure("empty"))
        return runCatching { WearCodec.json.decodeFromJsonElement(serializer, payload) }
    }

    /**
     * Asks the phone for something to sign the watch's engine in with; see
     * [dev.pampa.fluidify.wear.protocol.AuthRequest]. Null when the phone could
     * not be reached or did not answer.
     */
    suspend fun requestAuth(
        reason: dev.pampa.fluidify.wear.protocol.AuthReason,
        timeoutMs: Long = AUTH_TIMEOUT_MS,
    ): dev.pampa.fluidify.wear.protocol.AuthGrant? {
        val node = nodeId ?: findPhone()?.id ?: return null
        val id = ids.incrementAndGet()
        val waiter = CompletableDeferred<dev.pampa.fluidify.wear.protocol.AuthGrant>()
        authPending[id] = waiter
        val request = dev.pampa.fluidify.wear.protocol.AuthRequest(id, reason)
        if (!send(node, WearPaths.AUTH_REQUEST, WearCodec.encode(dev.pampa.fluidify.wear.protocol.AuthRequest.serializer(), request))) {
            authPending.remove(id)
            nodeId = null
            return null
        }
        return withTimeoutOrNull(timeoutMs) { waiter.await() }.also { authPending.remove(id) }
    }

    fun onAuthGrant(grant: dev.pampa.fluidify.wear.protocol.AuthGrant) {
        authPending.remove(grant.id)?.complete(grant)
    }

    fun onRpcReply(response: RpcResponse) {
        rpcPending.remove(response.id)?.complete(response)
    }

    /** A reply too big for a message, arriving gzipped over a channel. */
    suspend fun onRpcStream(channel: com.google.android.gms.wearable.ChannelClient.Channel) {
        val id = channel.path.removePrefix(WearPaths.RPC_STREAM_PREFIX).toLongOrNull() ?: return
        val channels = Wearable.getChannelClient(context)
        val bytes = runCatching {
            channels.getInputStream(channel).await().use { it.readBytes() }
        }.getOrNull()
        // Closed from this side too once read; see the phone's WearRpcHandler.stream.
        runCatching { channels.close(channel).await() }
        bytes ?: return
        onPhoneHeard()
        val response = runCatching { WearCodec.gunzip(bytes) }.getOrNull()
            ?.let { WearCodec.decodeOrNull(RpcResponse.serializer(), it) }
            ?: return
        if (response.id == id) onRpcReply(response)
    }

    fun onAck(ack: CommandAck) {
        state.observeClock(ack.sentAtEpochMs)
        onPhoneHeard()
        pending.remove(ack.id)?.complete(ack)
    }

    private suspend fun send(node: String, path: String, bytes: ByteArray): Boolean = runCatching {
        if (Wearable.getNodeClient(context).connectedNodes.await().none { it.id == node && it.isNearby }) return false
        messages.sendMessage(node, path, bytes).await()
        true
    }.onFailure { Log.i(TAG, "send $path failed: ${it.message}") }.getOrDefault(false)

    /**
     * Reads what the Data Layer already holds: the last state the phone wrote
     * and its cover. Covers a watch that was asleep or out of range when they
     * were delivered, without asking the phone for anything.
     */
    private suspend fun catchUp() {
        runCatching {
            val items = data.getDataItems(Uri.Builder().scheme("wear").path(WearPaths.STATE).build()).await()
            items.forEach { item ->
                item.data?.let { bytes ->
                    WearCodec.decodeOrNull(PlaybackSnapshot.serializer(), bytes)?.let { state.accept(it) }
                }
            }
            items.release()
        }.onFailure { Log.i(TAG, "state catch-up failed: ${it.message}") }
        val key = state.current.value?.snapshot?.track?.artKey ?: return
        if (!art.has(key)) fetchArt(key)
    }

    /** Pulls the cover [key] from the Data Layer, when the phone put it there. */
    suspend fun fetchArt(key: String) {
        runCatching {
            val items = data.getDataItems(
                Uri.Builder().scheme("wear").path(WearPaths.art(key)).build(),
                DataClient.FILTER_LITERAL,
            ).await()
            val asset = items.firstOrNull()?.let { DataMapItem.fromDataItem(it) }?.dataMap?.getAsset(ASSET_KEY)
                ?: items.firstOrNull()?.assets?.get(ASSET_KEY)?.let { Asset.createFromRef(it.id) }
            items.release()
            if (asset != null) storeAsset(key, asset)
        }.onFailure { Log.i(TAG, "cover catch-up failed: ${it.message}") }
    }

    suspend fun storeAsset(key: String, asset: Asset) {
        val fd = data.getFdForAsset(asset).await()
        val bytes = fd.inputStream.use { it.readBytes() }
        art.store(key, bytes)
    }

    fun ownHello(wantsReply: Boolean = true): Hello = Hello(
        role = Role.WATCH,
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        features = WATCH_FEATURES,
        buildType = BuildConfig.BUILD_TYPE,
        certSha256 = certificateSha256(context),
        abis = Build.SUPPORTED_ABIS.toList(),
        sdk = Build.VERSION.SDK_INT,
        wantsReply = wantsReply,
        sentAtEpochMs = System.currentTimeMillis(),
    )

    companion object {
        private const val TAG = "PhoneLink"
        const val ASSET_KEY = "img"
        private const val ACK_TIMEOUT_MS = 3_000L
        private const val RPC_TIMEOUT_MS = 12_000L

        /** The phone may have to refresh its own session first, over its own network. */
        private const val AUTH_TIMEOUT_MS = 20_000L
        private const val HELLO_ANSWER_MS = 10_000L

        /** What this watch build can do. Grows with each milestone. */
        val WATCH_FEATURES: Set<String> = setOf(
            dev.pampa.fluidify.wear.protocol.Features.AUTH,
            dev.pampa.fluidify.wear.protocol.Features.DOWNLOADS,
            dev.pampa.fluidify.wear.protocol.Features.HANDOFF,
        )

        fun certificateSha256(context: Context): String = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val first = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return ""
            MessageDigest.getInstance("SHA-256").digest(first.toByteArray()).joinToString(":") { "%02X".format(it) }
        }.getOrDefault("")
    }
}
