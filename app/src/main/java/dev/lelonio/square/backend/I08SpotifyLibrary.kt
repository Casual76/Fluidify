package dev.lelonio.square.backend

import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.PageDto
import dev.lelonio.square.data.PlaylistDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Reads an offset-paged source completely, or throws without publishing its prefix as Ready. */
internal suspend fun <T> readCompleteLibraryPages(
    checkCurrent: () -> Unit,
    page: suspend (offset: Int) -> PageDto<T>,
): List<T> {
    val loaded = mutableListOf<T>()
    var offset = 0
    var expected = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        checkCurrent()
        val response = page(offset)
        currentCoroutineContext().ensureActive()
        checkCurrent()
        check(response.total >= 0) { "Invalid library total" }
        val nextOffset = offset.toLong() + response.items.size
        check(nextOffset <= Int.MAX_VALUE) { "Library offset overflow" }
        loaded += response.items
        expected = maxOf(expected, response.total)
        val more = response.next != null || nextOffset < expected
        if (!more) return loaded
        check(response.items.isNotEmpty()) { "Library pagination stopped before completion" }
        offset = nextOffset.toInt()
    }
}

/** Existing transports only; setup is availability to TRY the Web API, never proof of access. */
internal suspend fun readSpotifyRootlist(
    accountId: String,
    checkCurrent: () -> Unit,
    primary: suspend () -> List<CatalogPlaylist>,
    fallbackReady: () -> Boolean,
    fallbackPage: suspend (offset: Int) -> PageDto<PlaylistDto>,
    fallbackUsed: (Exception) -> Unit = {},
): List<CatalogPlaylist> {
    try {
        currentCoroutineContext().ensureActive()
        checkCurrent()
        val rows = primary()
        currentCoroutineContext().ensureActive()
        checkCurrent()
        return rows
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        currentCoroutineContext().ensureActive()
        checkCurrent()
        check(fallbackReady()) { failure.message ?: "rootlist non disponibile" }
        fallbackUsed(failure)
        return readCompleteLibraryPages(checkCurrent, fallbackPage).map { dto ->
            CatalogPlaylist(dto.uri, dto.name, dto.images.firstOrNull()?.url,
                owner = dto.owner?.id,
                // The existing DTO does not carry collaborative rights. Unknown stays unknown.
                editable = if (dto.owner?.id == accountId) true else null)
        }.distinctBy { it.uri }
    }
}

/** A confirmed creation under this account is immediately writable, before another rootlist. */
internal fun createdSpotifyPlaylist(dto: PlaylistDto, accountId: String) = CatalogPlaylist(
    dto.uri, dto.name, dto.images.firstOrNull()?.url, owner = accountId, editable = true,
)
