package dev.lelonio.square.bridge

import dev.lelonio.square.data.ApiFactory
import dev.lelonio.square.data.SpotifyApi
import dev.lelonio.square.data.SpotifyNetworkPolicy
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Legacy callers also pin a host lease at construction; rapid ON -> OFF cannot renew it. */
internal fun bridgeSpotifyApi(token: String, fence: () -> Unit): SpotifyApi =
    bridgeSpotifyApi(token, BridgeNetworkOperation.capture(fence))

/** Credential, account and network authority are immutable for the entire invocation. */
internal fun bridgeSpotifyApi(token: String, operation: BridgeNetworkOperation): SpotifyApi {
    fun check() {
        try { operation.check() }
        catch (e: IOException) { throw e }
        catch (e: Exception) { throw IOException("Bridge authorization changed", e) }
    }
    val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            check()
            val call = chain.call()
            val observer = SpotifyNetworkPolicy.observe { manual -> if (manual) call.cancel() }
            var bodyOwnsObserver = false
            try {
                check() // Covers a revocation before observer registration.
                val request = chain.request().newBuilder().removeHeader(ApiFactory.SESSION_AUTH)
                    .tag(SpotifyNetworkPolicy.Permit::class.java, operation.permit)
                    .header("Authorization", "Bearer $token").build()
                val response = chain.proceed(request)
                try { check() } catch (e: Exception) { response.close(); throw e }
                val body = response.body
                if (body == null) response else {
                    val guarded = object : ResponseBody() {
                        override fun contentType() = body.contentType()
                        override fun contentLength() = body.contentLength()
                        private val guardedSource = object : ForwardingSource(body.source()) {
                            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                                check()
                                val count = super.read(sink, byteCount)
                                check()
                                return count
                            }
                            override fun close() { try { super.close() } finally { observer.close() } }
                        }.buffer()
                        override fun source(): BufferedSource = guardedSource
                    }
                    bodyOwnsObserver = true
                    response.newBuilder().body(guarded).build()
                }
            } finally { if (!bodyOwnsObserver) observer.close() }
        }
        .addNetworkInterceptor { chain ->
            try { operation.admit(chain.request().method) }
            catch (e: IOException) { throw e }
            catch (e: Exception) { throw IOException("Bridge authorization changed", e) }
            val response = chain.proceed(chain.request())
            try { check() } catch (e: Exception) { response.close(); throw e }
            // OkHttp can replay 503 + Retry-After: 0 even with connection retries off.
            if (response.code == 503) response.newBuilder().removeHeader("Retry-After").build() else response
        }.build()
    return Retrofit.Builder().baseUrl("https://api.spotify.com/").client(client)
        .addConverterFactory(Json { ignoreUnknownKeys = true; encodeDefaults = true }.asConverterFactory("application/json".toMediaType()))
        .build().create(SpotifyApi::class.java)
}
