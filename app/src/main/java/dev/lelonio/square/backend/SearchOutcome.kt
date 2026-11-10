package dev.lelonio.square.backend

import dev.lelonio.square.data.SearchResults
import kotlinx.coroutines.CancellationException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import retrofit2.HttpException

/** Immutable provenance belonging to one invocation, never a backend-wide flag. */
sealed interface SearchOutcome {
    data class Success(val results: SearchResults) : SearchOutcome
    data object NeedsSetup : SearchOutcome
    data class Failure(val kind: SearchFailureKind, val cause: Throwable) : SearchOutcome
}
enum class SearchFailureKind { NETWORK, AUTH, RESPONSE, OTHER }

fun searchFailure(error: Throwable): SearchOutcome.Failure {
    val causes = generateSequence(error) { it.cause }.take(16).toList()
    causes.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
    val http = causes.filterIsInstance<HttpException>().firstOrNull()
    val kind = when {
        http != null -> if (http.code() == 401 || http.code() == 403) SearchFailureKind.AUTH else SearchFailureKind.RESPONSE
        causes.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException || it is SocketTimeoutException } -> SearchFailureKind.NETWORK
        // JNI currently erases Rust error types. Accept only explicit transport
        // diagnostics from this operation; a generic "failed" is not network.
        causes.any { nativeSearchNetworkFailure(it) } -> SearchFailureKind.NETWORK
        else -> SearchFailureKind.OTHER
    }
    return SearchOutcome.Failure(kind, error)
}

private fun nativeSearchNetworkFailure(error: Throwable): Boolean {
    if (error !is IllegalStateException) return false
    val text = error.message.orEmpty().lowercase(java.util.Locale.ROOT)
    if (!text.startsWith("searchdesktop failed:")) return false
    // A transport diagnostic may contain a port (443) or a duration (500 ms).
    // Treat a number as an HTTP status only when its context identifies it.
    if (Regex("\\b(?:http(?:/\\d(?:\\.\\d)?)?(?:\\s+(?:response|error))?|(?:response\\s+)?status(?:\\s+code)?)\\s*[:=]?\\s*[45][0-9]{2}\\b").containsMatchIn(text) ||
        listOf("unauthorized", "forbidden", "status code", "certificate").any { it in text } ||
        // Reject payload/error markers, not DNS labels such as example.invalid.
        Regex("(?<![\\w.])(?:invalid|parse|decode)[\\w-]*(?![\\w.])").containsMatchIn(text)) return false
    return listOf("dns error", "failed to lookup address", "connection refused", "network is unreachable", "connection timed out", "operation timed out").any { it in text }
}
