package dev.lelonio.square.backend

import kotlinx.coroutines.CancellationException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/** Unknown JNI messages deliberately fail closed. */
class SearchFailureTest {
    @Test fun transportPortsAndDurationsAreNotHttpStatusCodes() {
        for (message in listOf(
            "connection refused at example.invalid:443",
            "operation timed out after 500 milliseconds",
            "connection timed out connecting to port 503",
            "failed to lookup address parse.example.invalid",
        )) assertEquals(message, SearchFailureKind.NETWORK,
            searchFailure(IllegalStateException("searchDesktop failed: $message")).kind)

        for (message in listOf(
            "HTTP 500: operation timed out",
            "HTTP/1.1 503 connection timed out",
            "HTTP error: 429, connection refused",
            "response status: 401, operation timed out",
            "status code 500 (operation timed out)",
            "invalid response: dns error",
            "parse failed: operation timed out",
            "decode error: connection refused",
        )) assertNotEquals(message, SearchFailureKind.NETWORK,
            searchFailure(IllegalStateException("searchDesktop failed: $message")).kind)
    }

    @Test fun knownTransportIsNetworkButHttpAndMalformedAndGenericIoAreNot() {
        assertEquals(SearchFailureKind.NETWORK, searchFailure(java.net.UnknownHostException()).kind)
        assertEquals(SearchFailureKind.NETWORK, searchFailure(java.net.SocketTimeoutException()).kind)
        assertEquals(SearchFailureKind.NETWORK, searchFailure(IllegalStateException("searchDesktop failed: dns error")).kind)
        for (status in listOf(401, 403, 429, 500)) {
            val failure = searchFailure(HttpException(Response.error<Unit>(status, "error".toResponseBody())))
            assertEquals(if (status == 401 || status == 403) SearchFailureKind.AUTH else SearchFailureKind.RESPONSE, failure.kind)
        }
        for (error in listOf(
            IllegalArgumentException("malformed response"), java.io.IOException("decode failed"),
            IllegalStateException("searchDesktop failed: Response status code: 401, connection timed out"),
            IllegalStateException("searchDesktop failed: invalid certificate, dns error"),
            IllegalStateException("searchDesktop failed: request failed"),
        )) assertNotEquals(SearchFailureKind.NETWORK, searchFailure(error).kind)
    }
    @Test fun wrappedCancellationPropagates() {
        val cancel = CancellationException("cancel")
        try { searchFailure(IllegalStateException(cancel)); fail("cancellation swallowed") }
        catch (caught: CancellationException) { assertSame(cancel, caught) }
    }
}
