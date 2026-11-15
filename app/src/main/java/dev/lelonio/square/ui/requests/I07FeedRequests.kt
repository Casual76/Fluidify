package dev.lelonio.square.ui.requests

import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.SearchItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class I07FeedSection { RELEASES, ARTISTS, TOP_TRACKS, ALL_TIME, FROM_ARTISTS, RECENT_CONTEXTS }
enum class I07FeedStatus { NOT_REQUESTED, LOADING, READY, FAILED }
enum class I07FeedAvailability { AVAILABLE, NEEDS_SETUP, OFFLINE }

/** READY includes a successful empty response. FAILED never counts as a complete feed. */
internal data class I07FeedSnapshot(
    val newReleases: List<SearchItem> = emptyList(),
    val topArtists: List<SearchItem> = emptyList(),
    val topTracks: List<CatalogTrack> = emptyList(),
    val allTimeTracks: List<CatalogTrack> = emptyList(),
    val fromYourArtists: List<SearchItem> = emptyList(),
    val jumpBackIn: List<SearchItem> = emptyList(),
    val sections: Map<I07FeedSection, I07FeedStatus> = emptyMap(),
    val availability: I07FeedAvailability = I07FeedAvailability.AVAILABLE,
) {
    val loading get() = sections.values.any { it == I07FeedStatus.LOADING }
}

/** Sequential, bounded readers; a failed reader does not prevent its independent siblings. */
internal class I07FeedRequests(
    private val scope: CoroutineScope,
    private val publish: (I07FeedSnapshot) -> Unit,
    private val now: () -> Long,
    private val freshnessMs: Long = 15 * 60_000L,
    private val readTimeoutMs: Long = 20_000L,
) {
    class Readers(
        val releases: suspend () -> List<SearchItem>,
        val artists: suspend () -> List<SearchItem>,
        val topTracks: suspend () -> List<CatalogTrack>,
        val allTime: suspend () -> List<CatalogTrack>,
        val fromArtists: suspend (List<SearchItem>) -> List<SearchItem>,
        val recentContexts: suspend () -> List<SearchItem>,
        val verify: suspend () -> Unit = {},
    )
    private val ownership = RequestOwnership()
    private var job: Job? = null
    private var owner: Any? = null
    private var completedAt: Long? = null
    private var state = I07FeedSnapshot()
    private fun emit(value: I07FeedSnapshot) { state = value; publish(value) }

    fun invalidate() {
        ownership.invalidate()
        job?.cancel()
        job = null
        owner = null
        completedAt = null
        emit(I07FeedSnapshot())
    }

    fun unavailable(availability: I07FeedAvailability) {
        ownership.invalidate()
        job?.cancel()
        job = null
        emit(state.copy(availability = availability, sections = state.sections.mapValues {
            if (it.value == I07FeedStatus.LOADING) I07FeedStatus.NOT_REQUESTED else it.value
        }))
    }

    fun load(proof: I07RequestProof, readers: Readers, force: Boolean = false): Job? {
        proof.ensureCurrent()
        if (owner !== proof.owner) { invalidate(); owner = proof.owner }
        if (job?.isActive == true) return job
        val complete = I07FeedSection.entries.all { state.sections[it] == I07FeedStatus.READY }
        val age = completedAt?.let { now() - it }
        if (!force && complete && age != null && age in 0 until freshnessMs &&
            state.availability == I07FeedAvailability.AVAILABLE) return null
        val ticket = ownership.begin()
        proof.publish { emit(state.copy(availability = I07FeedAvailability.AVAILABLE,
            sections = I07FeedSection.entries.associateWith { I07FeedStatus.LOADING })) }
        fun update(section: I07FeedSection, status: I07FeedStatus, change: (I07FeedSnapshot) -> I07FeedSnapshot = { it }) {
            proof.publish {
                ticket.ensureCurrent()
                emit(change(state).let { it.copy(sections = it.sections + (section to status)) })
            }
        }
        return scope.launch {
            suspend fun <T> read(section: I07FeedSection, reader: suspend () -> T, merge: (I07FeedSnapshot, T) -> I07FeedSnapshot) {
                try {
                    currentCoroutineContext().ensureActive()
                    ticket.ensureCurrent()
                    proof.ensureCurrent()
                    val answer = withTimeout(readTimeoutMs) { reader() }
                    currentCoroutineContext().ensureActive()
                    update(section, I07FeedStatus.READY) { merge(it, answer) }
                } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    update(section, I07FeedStatus.FAILED)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    update(section, I07FeedStatus.FAILED)
                }
            }
            try {
                try {
                    ticket.ensureCurrent()
                    proof.ensureCurrent()
                    withTimeout(readTimeoutMs) { readers.verify() }
                    currentCoroutineContext().ensureActive()
                    proof.ensureCurrent()
                } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    I07FeedSection.entries.forEach { update(it, I07FeedStatus.FAILED) }
                    return@launch
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    I07FeedSection.entries.forEach { update(it, I07FeedStatus.FAILED) }
                    return@launch
                }
                read(I07FeedSection.RELEASES, readers.releases) { s, v -> s.copy(newReleases = v) }
                read(I07FeedSection.ARTISTS, readers.artists) { s, v -> s.copy(topArtists = v) }
                read(I07FeedSection.TOP_TRACKS, readers.topTracks) { s, v -> s.copy(topTracks = v) }
                read(I07FeedSection.ALL_TIME, readers.allTime) { s, v -> s.copy(allTimeTracks = v) }
                if (state.sections[I07FeedSection.ARTISTS] == I07FeedStatus.READY) {
                    read(I07FeedSection.FROM_ARTISTS, { readers.fromArtists(state.topArtists) }) { s, v -> s.copy(fromYourArtists = v) }
                } else update(I07FeedSection.FROM_ARTISTS, I07FeedStatus.FAILED)
                read(I07FeedSection.RECENT_CONTEXTS, readers.recentContexts) { s, v -> s.copy(jumpBackIn = v) }
                proof.publish { ticket.ensureCurrent(); completedAt = now() }
            } finally {
                if (ticket.isCurrent()) runCatching {
                    proof.publish {
                        ticket.ensureCurrent()
                        emit(state.copy(sections = state.sections.mapValues {
                            if (it.value == I07FeedStatus.LOADING) I07FeedStatus.NOT_REQUESTED else it.value
                        }))
                    }
                }.onFailure {
                    // A neutral reset contains no personal data and cannot outlive a successor.
                    if (ticket.isCurrent()) emit(I07FeedSnapshot(availability = I07FeedAvailability.OFFLINE))
                }
            }
        }.also { job = it }
    }
}
