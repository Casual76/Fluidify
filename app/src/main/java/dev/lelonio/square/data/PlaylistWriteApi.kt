package dev.lelonio.square.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

internal const val PLAYLIST_EDIT_SCOPES = "playlist-modify-private,playlist-modify-public"

/** One captured credential per write. A refused/ambiguous response is never replayed or redirected. */
internal fun playlistWriteApi(token: String, fence: () -> Unit): SpotifyApi =
    playlistWriteApi(token, SpotifyNetworkPolicy.capture(), fence)

internal fun playlistWriteApi(token: String, permit: SpotifyNetworkPolicy.Permit, fence: () -> Unit): SpotifyApi = Retrofit.Builder()
    .baseUrl("https://api.spotify.com/")
    .client(playlistWriteClient(token, permit, fence))
    .addConverterFactory(Json { ignoreUnknownKeys = true; coerceInputValues = true }
        .asConverterFactory("application/json".toMediaType()))
    .build().create(SpotifyApi::class.java)

internal fun playlistWriteClient(token: String, fence: () -> Unit): OkHttpClient =
    playlistWriteClient(token, SpotifyNetworkPolicy.capture(), fence)

internal fun playlistWriteClient(token: String, permit: SpotifyNetworkPolicy.Permit, fence: () -> Unit): OkHttpClient = OkHttpClient.Builder()
    .retryOnConnectionFailure(false)
    .followRedirects(false).followSslRedirects(false)
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(45, TimeUnit.SECONDS)
    .addInterceptor { chain ->
        // Original operation lease, before auth/DNS/connection and without a new capture.
        permit.check()
        val tagged = chain.request().newBuilder().tag(SpotifyNetworkPolicy.Permit::class.java, permit).build()
        chain.proceed(tagged)
    }
    .addInterceptor(SpotifyNetworkInterceptor())
    .addInterceptor { chain ->
        val request = chain.request().newBuilder().removeHeader(ApiFactory.SESSION_AUTH)
            .header("Authorization", "Bearer $token").build()
        chain.proceed(request)
    }
    .addNetworkInterceptor { chain ->
        // Checked at dispatch as well as receipt, after connection/token work has completed.
        fun checkAuthority() {
            permit.check()
            try { fence() }
            catch (failure: Exception) { throw IOException("Playlist account authority changed", failure) }
        }
        checkAuthority()
        val response = chain.proceed(chain.request())
        try { checkAuthority() }
        catch (failure: IOException) { response.close(); throw failure }
        // OkHttp retries a 503 with Retry-After: 0 even when connection retries are off.
        // A write must retain the first status/receipt, so suppress only that automatic hint.
        if (response.code == 503) response.newBuilder().removeHeader("Retry-After").build() else response
    }.build()
