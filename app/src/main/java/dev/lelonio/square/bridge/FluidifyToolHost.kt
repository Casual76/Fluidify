package dev.lelonio.square.bridge

import dev.antigravity.fluidengine.ai.bridge.*
import dev.antigravity.fluidengine.ai.tools.*
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.security.MessageDigest

class FluidifyToolHost : AiToolHostProvider<FluidifyBridgeContext>() {
    private val app get() = context!!.applicationContext as SquareApplication
    private val pager = SnapshotPager()
    private val fragments = ResultFragments()
    private val operations by lazy { PlaylistOperations(DurableOperationStore(app)) }
    private val tools by lazy { fluidifyRegistry("${context!!.packageName}.ai.tools") }
    override fun registry() = tools
    override fun domain() = "fluidify"
    override fun appLabel() = "Fluidify"
    override fun routerHint() = "Storia locale Spotify con copertura limitata; lettura playlist, creazione privata, rinomina e aggiunta brani confermate. Playback via MediaSession."
    override suspend fun context(call: RemoteCall) = FluidifyBridgeContext(app, call, pager, operations, fragments)
}

class FluidifyBridgeContext internal constructor(
    internal val app: SquareApplication, internal val call: RemoteCall,
    internal val pager: SnapshotPager, internal val operations: PlaylistOperations,
    internal val fragments: ResultFragments = ResultFragments(),
)

internal object MusicGroup : AiToolGroup {
    override val id = "music_data"
    override val statusKey = "music_data"
    override val hint = "Storia e playlist Spotify"
    override val toolTimeoutMillis = 20_000L
}

internal fun fluidifyRegistry(authority: String = "dev.pampa.fluidify.ai.tools"): ToolRegistry<FluidifyBridgeContext> {
    val scope = mapOf("account" to Schema.str("ID Spotify attualmente autenticato; non cambia account"))
    val page = mapOf("cursor" to Schema.str("Cursore snapshot restituito"), "limit" to Schema.int("Dimensione pagina", 1, 100))
    val period = mapOf("from" to Schema.str("Epoch millisecondi inclusivo"), "until" to Schema.str("Epoch millisecondi esclusivo"))
    fun tool(name: String, fields: Map<String, JsonObject>, required: List<String> = emptyList(), write: Boolean = false) =
        MusicTool(name, Schema.obj((if (name == "capabilities") emptyMap() else scope) + fields, required + (if (name == "capabilities") emptyList() else listOf("account"))), write)
    val tools = listOf(
        tool("capabilities", emptyMap()),
        tool("result_fragment", mapOf("snapshot" to Schema.str("Snapshot restituito"), "hash" to Schema.str("SHA256 contenuto"), "queryHash" to Schema.str("SHA256 query originale"), "offset" to Schema.int("Offset restituito", 0, Int.MAX_VALUE)), listOf("snapshot", "hash", "queryHash", "offset")),
        tool("history_records", period + page, listOf("from", "until")),
        tool("history_aggregate", period, listOf("from", "until")),
        tool("history_track_counts", period + page, listOf("from", "until")),
        tool("history_artist_counts", period + page, listOf("from", "until")),
        tool("history_coverage", page),
        tool("playlists", page),
        tool("playlist_details", mapOf("playlistId" to Schema.str("ID playlist Spotify")), listOf("playlistId")),
        tool("playlist_tracks", page + mapOf("playlistId" to Schema.str("ID playlist Spotify")), listOf("playlistId")),
        tool("playlist_append", mapOf("playlistId" to Schema.str("ID playlist propria"),
            "expectedSnapshotId" to Schema.str("Snapshot ID letto da playlist_details"),
            "expectedTrackCount" to Schema.int("Conteggio atteso letto dai dettagli", 0, 2000),
            "uris" to Schema.strArray("Da 1 a 50 URI spotify:track; ordine conservato")),
            listOf("playlistId", "expectedSnapshotId", "expectedTrackCount", "uris"), true),
        tool("playlist_create", mapOf("name" to Schema.str("Nome, 1..100 caratteri")), listOf("name"), true),
        tool("playlist_rename", mapOf("name" to Schema.str("Nuovo nome, 1..100 caratteri"), "playlistId" to Schema.str("ID playlist di proprieta")), listOf("name", "playlistId"), true),
    )
    return SourceProofRegistry(ToolRegistry(tools, listOf(MusicGroup)), "fluidify", authority,
        setOf("history_records", "history_aggregate", "history_track_counts", "history_artist_counts", "history_coverage",
            "playlists", "playlist_details", "playlist_tracks"), state = { ctx ->
            val history = ctx.app.listeningHistory.session.value
            val access = history.access
            val generation = ctx.app.webApi.tokens.generation
            if (access == null || !history.recording) null else ProofState(access.account.id, null,
                "${access.generation}:$generation:${history.recording}")
        }, caller = { it.call.callingPackage }).registry()
}

internal class MusicTool(override val name: String, override val parameters: JsonObject, private val write: Boolean) : AiTool<FluidifyBridgeContext> {
    override val group = MusicGroup
    override val description = when(name) {
        "capabilities" -> "Operazioni disponibili, account e limiti. Nessun accesso ad altri account."
        "result_fragment" -> "Legge solo frammenti di snapshot/ricevuta immutabile: usare snapshot, hash, queryHash e nextOffset restituiti. Concatenare fragment per offset; nextPageCursor disponibile solo alla fine. Non ripetere scritture."
        "history_records" -> "Eventi conservati in Fluidify: snapshot paginato, durate importate ignote, archivio incompleto."
        "history_aggregate" -> "Conteggi deterministici e soli millisecondi locali misurati nel periodo; non minuti importati."
        "history_coverage" -> "Ricevute degli import Spotify, paginabili; non prova di archivio completo."
        "playlist_create" -> "Crea playlist privata vuota con conferma, operationId durevole e rilettura. Esito incerto non riesegue."
        "playlist_rename" -> "Rinomina playlist propria senza cambiare visibilita; conferma, dedup e rilettura."
        else -> "Legge $name da Spotify; errori distinti dal vuoto, pagine snapshot quando presenti."
    }
    override val needsConfirmation get() = write
    override val isAction get() = write
    override suspend fun describe(args: JsonObject, ctx: FluidifyBridgeContext) = if (write) ConfirmationText("Fluidify: $name", args["name"]?.jsonPrimitive?.content) else null
    override suspend fun run(args: JsonObject, ctx: FluidifyBridgeContext): ToolOutput =
        execute(args, ctx, null)

    override suspend fun run(args: JsonObject, ctx: FluidifyBridgeContext, execution: ToolExecution): ToolOutput =
        execute(args, ctx, execution.operationId)

    private suspend fun execute(args: JsonObject, ctx: FluidifyBridgeContext, executionId: String?): ToolOutput {
        ToolArgumentValidator.error(args, parameters)?.let { return failure("invalid_arguments") }
        if (args.keys.any { it !in parameters["properties"]!!.jsonObject && !(write && it == "operationId") }) return failure("invalid_arguments")
        var dispatched = false
        var access: HistoryAccess? = null
        var operation: String? = null
        try {
            if (write) operation = durableWriteIdentity(executionId, ctx.call.requestId, args["operationId"])
            val history = ctx.app.listeningHistory
            val lease = history.session.value.access ?: throw BridgeFailure("authentication_required")
            access = lease
            val resultSourceGeneration = ctx.app.webApi.tokens.generation
            val account = currentAccount(lease, args["account"]?.jsonPrimitive?.content, name == "capabilities")
            fun fence() { if (history.session.value.access != lease) throw BridgeFailure("account_revoked") }
            var pinnedApi: SpotifyApi? = null
            suspend fun authenticate() {
                fence()
                if (pinnedApi!!.me().id != account) throw BridgeFailure("web_api_account_mismatch")
                fence()
            }
            val limit = if ("limit" in args) args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: throw BridgeFailure("invalid_arguments") else 50
            val cursor = args["cursor"]?.jsonPrimitive?.content
            val query = name + ":" + args.filterKeys { it != "cursor" && it != "limit" && it != "operationId" }.toSortedMap().toString()
            fun result(data: JsonObject, page: SnapshotPager.Page? = null, entity: String? = null, complete: Boolean? = null): ToolOutput {
                fence()
                if (ctx.app.webApi.tokens.generation != resultSourceGeneration) throw BridgeFailure("account_revoked")
                val details = ToolResultDetails(
                    if (page?.cursor != null) ToolOutcome.PARTIAL else ToolOutcome.COMPLETED,
                    data = data, entities = entity?.let { listOf(ToolEntityRef("spotify", "playlist", it, "spotify:playlist:$it")) } ?: emptyList(),
                    operation = operation?.let { ToolOperationRef(it, "fluidify") },
                    sources = listOf(ToolSourceRef(if (name.startsWith("history_")) "fluidify_listening_history" else "spotify", account = account)),
                    coverage = ToolCoverage(complete, args["from"]?.jsonPrimitive?.content, args["until"]?.jsonPrimitive?.content), freshness = ToolFreshness(observedAtMillis = page?.at ?: System.currentTimeMillis()),
                    pagination = page?.let { ToolPagination(it.cursor, it.cursor != null, it.total.toLong()) },
                )
                return ctx.fragments.output(lease, ctx.call.callingPackage, query, data, details, resultSourceGeneration)
            }
            suspend fun paged(load: suspend () -> List<JsonObject>): ToolOutput {
                val rows = if (cursor == null) load() else null
                fence()
                val p = ctx.pager.page(lease, query, cursor, limit, rows)
                return result(buildJsonObject { put("items", JsonArray(p.rows)) }, p, complete = false)
            }
            if (name == "result_fragment") {
                fence()
                return ctx.fragments.read(lease, ctx.call.callingPackage, args.string("snapshot"),
                    args.string("hash"), args.string("queryHash"), args["offset"]!!.jsonPrimitive.content.toInt(), ctx.app.webApi.tokens.generation)
            }
            if (name == "capabilities") return result(buildJsonObject {
                put("account", account); put("source", "spotify"); put("historyArchiveComplete", false)
                put("historyImportedDurationKnown", false); put("snapshotTtlMillis", 300000)
                put("snapshotMaxRows", 20000); put("playlistMaxRows", 2000)
                put("supportedWrites", JsonArray(listOf("private_empty_create", "rename_owned", "append_owned_with_precondition").map(::JsonPrimitive)))
                put("unsupportedWrites", JsonArray(listOf("remove", "reorder", "delete", "visibility", "description").map(::JsonPrimitive)))
                put("accountSwitch", false); put("playback", "MediaSession")
            }, complete = false)
            if (name.startsWith("history_")) {
                val from = args["from"]?.jsonPrimitive?.content?.toLongOrNull()
                val until = args["until"]?.jsonPrimitive?.content?.toLongOrNull()
                if (name != "history_coverage" && (from == null || until == null || from >= until)) throw BridgeFailure("invalid_period")
                return when (name) {
                    "history_records" -> paged { history.bridgeSnapshot(lease, from!!, until!!).records.map { e -> buildJsonObject {
                        put("id", e.id); put("track", Json.encodeToJsonElement(CatalogTrack.serializer(), e.track))
                        put("startedAt", e.startedAt); put("endedAt", e.endedAt); put("spotifyAt", e.spotifyAt?.let(::JsonPrimitive) ?: JsonNull)
                        put("measuredLocalMs", e.listenedMs?.let(::JsonPrimitive) ?: JsonNull); put("qualified", e.qualified); put("provenance", e.provenance)
                    } } }
                    "history_coverage" -> paged { history.bridgeCoverage(lease).map { it.bridgeJson() } }
                    "history_track_counts", "history_artist_counts" -> paged {
                        val a = history.aggregate(lease, from!!, until!!)
                        if (name == "history_track_counts") a.tracks.map { t -> buildJsonObject {
                            put("track", Json.parseToJsonElement(t.trackJson)); put("plays", t.plays)
                            put("localPlays", t.localPlays); put("importedOnlyPlays", t.importedPlays)
                        } } else a.artists.map { (artist, count) -> buildJsonObject { put("artist", artist); put("plays", count) } }
                    }
                    else -> {
                        val a = history.aggregate(lease, from!!, until!!)
                        result(buildJsonObject {
                            put("from", from); put("until", until); put("countedPlays", a.countedPlays)
                            put("localCountedPlays", a.localCountedPlays); put("importedOnlyPlays", a.importedOnlyPlays)
                            put("measuredLocalMs", a.measuredLocalMs); put("importedDurationMs", JsonNull); put("durationPrecision", a.durationPrecision)
                            put("trackCount", a.tracks.size); put("artistCount", a.artists.size)
                            put("previewLimit", 100); put("fullCountsTools", "history_track_counts,history_artist_counts")
                            put("tracks", JsonArray(a.tracks.take(100).map { t -> buildJsonObject { put("track", Json.parseToJsonElement(t.trackJson)); put("plays", t.plays) } }))
                            put("artists", JsonArray(a.artists.take(100).map { (artist, count) -> buildJsonObject { put("artist", artist); put("plays", count) } }))
                            put("recentCoverage", JsonArray(a.coverage.map { it.bridgeJson() }))
                        }, complete = false)
                    }
                }
            }
            val tokenGeneration = ctx.app.webApi.tokens.generation
            val token = ctx.app.webApi.tokens.validAccessToken()
            fun networkFence() {
                fence()
                if (ctx.app.webApi.tokens.generation != tokenGeneration) throw BridgeFailure("account_revoked")
            }
            networkFence()
            val api = bridgeSpotifyApi(token, ::networkFence)
            pinnedApi = api
            authenticate()
            val id = args["playlistId"]?.jsonPrimitive?.content
            if (id != null && !id.matches(Regex("[A-Za-z0-9]{1,100}"))) throw BridgeFailure("invalid_playlist_id")
            if (name == "playlists") return paged {
                val rows = mutableListOf<JsonObject>(); var offset = 0
                while (true) {
                    fence(); val p = api.playlists(50, offset); fence()
                    rows += p.items.map { Json.encodeToJsonElement(PlaylistDto.serializer(), it).jsonObject }
                    if (rows.size > 2000) throw BridgeFailure("snapshot_limit")
                    if (p.next == null) break
                    if (p.items.isEmpty()) throw BridgeFailure("invalid_source_page")
                    offset += p.items.size
                }
                rows
            }
            if (name == "playlist_details") {
                val p = api.playlist(id ?: throw BridgeFailure("playlist_id_required"), "id,uri,name,images,description,owner,public,tracks(total)")
                val snapshot = api.playlistSnapshot(p.id)
                return result(buildJsonObject {
                    put("playlist", Json.encodeToJsonElement(PlaylistDto.serializer(), p))
                    put("snapshotId", snapshot.snapshotId?.let(::JsonPrimitive) ?: JsonNull)
                    put("trackCount", snapshot.tracks?.total?.let(::JsonPrimitive) ?: JsonNull)
                }, entity = p.id)
            }
            if (name == "playlist_tracks") return paged {
                val playlistId = id ?: throw BridgeFailure("playlist_id_required")
                val before = api.playlistSnapshot(playlistId).snapshotId ?: throw BridgeFailure("snapshot_unavailable")
                val rows = mutableListOf<JsonObject>(); var offset = 0
                while (true) {
                    fence(); val p = api.playlistTracks(playlistId, 100, offset); fence()
                    rows += p.items.mapIndexed { index, item -> buildJsonObject {
                        put("position", offset + index); put("item", Json.encodeToJsonElement(PlaylistTrackDto.serializer(), item))
                    } }
                    if (rows.size > 2000) throw BridgeFailure("snapshot_limit")
                    if (p.next == null) break
                    if (p.items.isEmpty()) throw BridgeFailure("invalid_source_page")
                    offset += p.items.size
                }
                if (api.playlistSnapshot(playlistId).snapshotId != before) throw BridgeFailure("source_changed")
                rows
            }
            if (!write) throw BridgeFailure("unsupported_operation")
            if (!ctx.call.confirmed) throw BridgeFailure("confirmation_required")
            val op = operation ?: throw BridgeFailure("operation_identity_required")
            val append = name == "playlist_append"
            val uris = if (append) args["uris"]!!.jsonArray.map { it.jsonPrimitive.content } else emptyList()
            val baseCount = if (append) args["expectedTrackCount"]!!.jsonPrimitive.content.toIntOrNull()
                ?: throw BridgeFailure("invalid_arguments") else 0
            if (append && (uris.size !in 1..50 || uris.any { !it.matches(Regex("spotify:track:[A-Za-z0-9]{1,100}")) })) throw BridgeFailure("invalid_tracks")
            val title = if (append) "append" else args.string("name")
            if (title.isBlank() || title.length > 100) throw BridgeFailure("invalid_name")
            if (name == "playlist_rename" || append) {
                val owned = api.playlist(id ?: throw BridgeFailure("playlist_id_required"), "id,uri,name,images,description,owner,public,tracks(total)")
                if (owned.owner?.id != account) throw BridgeFailure("not_owner")
            }
            fence()
            val key = digest("${ctx.call.callingPackage}:$account:$op")
            val fingerprint = digest(query)
            val actual = ctx.operations.run(key, fingerprint, name == "playlist_create", id, dispatch = {
                authenticate(); fence(); dispatched = true
                if (name == "playlist_create") api.createPlaylist(account, PlaylistDetailsDto(title)).id
                else if (append) {
                    val snapshot = api.playlistSnapshot(id!!)
                    if (snapshot.snapshotId != args.string("expectedSnapshotId") || snapshot.tracks?.total != baseCount) throw BridgeFailure("precondition_failed")
                    fence()
                    api.addToPlaylist(id, AddTracksRequestDto(uris)); id!!
                } else { api.renamePlaylist(id!!, PlaylistNameDto(title)); id!! }
            }, readback = { entity ->
                authenticate(); val p = api.playlist(entity, "id,uri,name,images,description,owner,public,tracks(total)"); fence()
                val contentMatches = if (append) {
                    val snapshot = api.playlistSnapshot(entity)
                    val tail = api.playlistTracks(entity, 100, baseCount)
                    val after = api.playlistSnapshot(entity)
                    appendReadbackMatches(snapshot.snapshotId, after.snapshotId, snapshot.tracks?.total, baseCount, uris, tail.items.map { it.track?.uri })
                } else p.name == title
                p.id == entity && contentMatches && p.owner?.id == account &&
                    (name != "playlist_create" || p.isPublic == false)
            })
            val verified = api.playlist(actual, "id,uri,name,images,description,owner,public,tracks(total)")
            if ((!append && verified.name != title) || verified.owner?.id != account || (name == "playlist_create" && verified.isPublic != false)) throw BridgeFailure("readback_uncertain")
            return result(Json.encodeToJsonElement(PlaylistDto.serializer(), verified).jsonObject, entity = actual)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            val code = if (access != null && ctx.app.listeningHistory.session.value.access != access) "account_revoked"
                else ((e as? BridgeFailure) ?: (e.cause as? BridgeFailure))?.code ?: if (dispatched) "write_uncertain" else when ((e as? retrofit2.HttpException)?.code()) {
                    401 -> "authentication_failed"
                    403 -> "permission_denied"
                    404 -> "source_not_found"
                    429 -> "rate_limited"
                    else -> "source_unavailable"
                }
            return failure(code, operation, dispatched || code.endsWith("uncertain"))
        }
    }
}
private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.content ?: throw BridgeFailure("invalid_arguments")
private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
private fun failure(code: String, operation: String? = null, uncertain: Boolean = false) = ToolOutput(
    "Fluidify: $code", result = ToolResultDetails(if (uncertain) ToolOutcome.UNCERTAIN else ToolOutcome.ERROR,
        operation = operation?.let { ToolOperationRef(it, "fluidify") }, error = ToolError(code, code, retryable = false)))

private fun HistoryCoverage.bridgeJson() = buildJsonObject {
    put("id", id); put("service", service); put("account", account); put("source", source)
    put("requestedFrom", requestedFrom?.let(::JsonPrimitive) ?: JsonNull); put("requestedUntil", requestedUntil)
    put("observedFrom", observedFrom?.let(::JsonPrimitive) ?: JsonNull); put("observedUntil", observedUntil?.let(::JsonPrimitive) ?: JsonNull)
    put("importedAt", importedAt); put("pages", pages); put("truncated", truncated); put("missingPages", missingPages)
    put("status", status); put("error", error?.let(::JsonPrimitive) ?: JsonNull); put("precision", precision); put("complete", false)
}
