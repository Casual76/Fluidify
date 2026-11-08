package dev.lelonio.square.bridge

import dev.lelonio.square.data.ApiFactory
import dev.lelonio.square.data.SpotifyApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** One authenticated credential per invocation. No native-session fallback or mutation retries. */
internal fun bridgeSpotifyApi(token: String, fence: () -> Unit): SpotifyApi {
    val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            try { fence() } catch (e: Exception) { throw java.io.IOException("Bridge authorization changed", e) }
            val request = chain.request().newBuilder().removeHeader(ApiFactory.SESSION_AUTH)
                .header("Authorization", "Bearer $token").build()
            val response = chain.proceed(request)
            try { fence() } catch (e: Exception) { response.close(); throw java.io.IOException("Bridge authorization changed", e) }
            response
        }.build()
    return Retrofit.Builder().baseUrl("https://api.spotify.com/").client(client)
        .addConverterFactory(Json { ignoreUnknownKeys = true; encodeDefaults = true }.asConverterFactory("application/json".toMediaType()))
        .build().create(SpotifyApi::class.java)
}
