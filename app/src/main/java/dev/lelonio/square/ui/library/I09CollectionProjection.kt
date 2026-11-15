package dev.lelonio.square.ui.library

import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.DownloadStore
import java.text.Normalizer
import java.util.Locale

/** Name-only, local matching. Literal punctuation stays literal; Latin accents are optional. */
internal fun i09CollectionNameMatches(name: String, query: String): Boolean =
    i09FoldCollectionName(name).contains(i09FoldCollectionName(query.trim()))

private fun i09FoldCollectionName(text: String): String {
    val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    val result = StringBuilder()
    var latin = false
    decomposed.codePoints().forEach { point ->
        val mark = when (Character.getType(point)) {
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> true
            else -> false
        }
        if (!mark) latin = Character.UnicodeScript.of(point) == Character.UnicodeScript.LATIN
        if (!mark || !latin) result.appendCodePoint(point)
    }
    return Normalizer.normalize(result, Normalizer.Form.NFC)
}

/** Do not recheck capabilities here: the producer has already scoped the destinations/account. */
internal fun i09FilterCollectionsByName(rows: List<CatalogPlaylist>, query: String): List<CatalogPlaylist> {
    val needle = i09FoldCollectionName(query.trim())
    return if (needle.isEmpty()) rows else rows.filter { i09FoldCollectionName(it.name).contains(needle) }
}

/** An orphan kept on this phone opens from its index even when the online catalogue changed. */
internal fun i09ShouldOpenStoredCollection(
    uri: String, owners: Set<String>, known: Set<String>, artistWebApiReady: Boolean = true,
): Boolean = uri.startsWith("spotify:") && uri in owners &&
    (uri !in known || (uri.startsWith("spotify:artist:") && !artistWebApiReady))

/** A stored artist is reachable without the optional online artist reader. */
internal fun i09ArtistNeedsWebApi(uri: String, webApiReady: Boolean, owners: Set<String>): Boolean =
    uri.startsWith("spotify:artist:") && !webApiReady && uri !in owners

/** Retain real owner URIs (especially downloads:liked and legacy archives) for local opening. */
internal fun i09DownloadOwnerCollections(
    owners: Set<String>, labels: Map<String, DownloadStore.OwnerLabel>,
    fallbackName: String,
): List<CatalogPlaylist> = owners.filter { it != DownloadStore.SINGLES }.map { uri ->
    val label = labels[uri]
    CatalogPlaylist(uri, label?.name ?: fallbackName, label?.artworkUrl)
}

/** Metadata from the live library wins; ownership comes from the account-local index. */
internal fun i09DownloadedCollections(
    known: List<CatalogPlaylist>, owners: Set<String>,
    ownerRows: List<CatalogPlaylist>, singles: CatalogPlaylist?,
): List<CatalogPlaylist> {
    val metadata = known.associateBy { it.uri }
    return (listOfNotNull(singles) + ownerRows.filter { it.uri in owners }.map { metadata[it.uri] ?: it } +
        known.filter { it.uri in owners }).distinctBy { it.uri }
}
