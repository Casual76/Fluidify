package dev.lelonio.square.data

import dev.lelonio.square.auth.TokenStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Builds the Web API client.
 *
 * The auth interceptor is where most third-party clients go wrong: they attach a
 * token captured at construction time and never notice it expiring. Here every
 * request asks [TokenStore] for a token, and [TokenStore] refreshes under a
 * mutex, so a burst of parallel requests around expiry produces exactly one
 * refresh and no lost rotation.
 */
object ApiFactory {

    private const val BASE_URL = "https://api.spotify.com/"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /**
     * The header a call carries to say it wants the playing session's own
     * credential rather than the listener's registered application. Its value is
     * the comma-separated scopes to mint that token for. Stripped before the
     * request leaves.
     */
    const val SESSION_AUTH = "X-Fluidify-Session-Scopes"

    /**
     * @param sessionToken mints a Web API token from the librespot session, or
     *   returns null when the engine is not up. Only consulted for calls marked
     *   with [SESSION_AUTH].
     */
    fun create(
        tokens: TokenStore,
        sessionToken: (String) -> String? = { null },
        debug: Boolean = false,
    ): SpotifyApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokens, sessionToken))
            .addInterceptor(RateLimitInterceptor())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .apply {
                if (debug) {
                    addInterceptor(
                        okhttp3.logging.HttpLoggingInterceptor().setLevel(
                            okhttp3.logging.HttpLoggingInterceptor.Level.BASIC,
                        ),
                    )
                }
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SpotifyApi::class.java)
    }

    /**
     * Waits out a single 429 when Spotify says how long to wait.
     *
     * Spotify rate-limits per app over a rolling window and answers with
     * `Retry-After` in seconds. Retrying once, only when the wait is short
     * enough to be worth blocking on, turns the common brief throttle into a
     * slow request instead of a visible error — while a long back-off is still
     * surfaced rather than silently stalling the UI.
     */
    private class RateLimitInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            if (response.code != 429) return response

            val retryAfter = response.header("Retry-After")?.toLongOrNull()
            android.util.Log.w(TAG, "rate limited, Retry-After=${retryAfter ?: "absent"}")
            if (retryAfter == null || retryAfter > MAX_WAIT_SECONDS) return response

            response.close()
            try {
                // +1s: Retry-After is whole seconds, so waiting exactly that
                // long can land a hair inside the window and burn the retry.
                Thread.sleep((retryAfter + 1) * 1000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("interrupted while waiting out a rate limit", e)
            }
            return chain.proceed(chain.request())
        }

        private companion object {
            /**
             * Spotify's back-offs on this client id run to tens of seconds.
             * Blocking a background OkHttp thread that long is cheaper than
             * showing an error the user can only answer by tapping retry —
             * which costs another request against the same quota.
             */
            const val MAX_WAIT_SECONDS = 90L
            const val TAG = "SquareApi"
        }
    }

    /**
     * Attaches a credential, and knows there are two of them.
     *
     * The registered application is the default and covers everything the app
     * reads. What it cannot do is write to playlists: an application in
     * Development Mode — which every listener's own registration is — is refused
     * `POST /v1/playlists/{id}/tracks` outright, with a bare "Forbidden" and no
     * hint that the application rather than the request is the problem. See
     * `engine::web_token` for the measurement.
     *
     * So a call may ask for the playing session's credential instead, which is
     * the official client's and under no such restriction. It is a preference
     * rather than a switch: with no engine running there is no session token to
     * be had, and a session token can itself be refused, so the registered
     * application stays as the fallback in both directions. Nothing that works
     * today can be broken by asking.
     */
    private class AuthInterceptor(
        private val tokens: TokenStore,
        private val sessionToken: (String) -> String?,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val scopes = original.header(SESSION_AUTH)
            val stripped = original.newBuilder().removeHeader(SESSION_AUTH).build()

            val session = scopes?.let { runCatching { sessionToken(it) }.getOrNull() }
            if (session != null) {
                val response = chain.proceed(send(stripped, session))
                // Refused as the official client too, or the token had gone
                // stale between minting and sending. Either way the registered
                // application is the only other thing to try, and trying it
                // costs one request on a path that has already failed.
                if (response.code != 401 && response.code != 403) return response
                response.close()
            }

            return chain.proceed(send(stripped, stored()))
        }

        private fun send(request: okhttp3.Request, token: String) =
            request.newBuilder().header("Authorization", "Bearer $token").build()

        /**
         * OkHttp interceptors are blocking by contract and always run on a
         * background thread, so bridging into the suspending token store with
         * runBlocking is safe here.
         *
         * Everything must leave as an IOException: OkHttp's async dispatcher
         * only routes IOException to onFailure and rethrows anything else on
         * its own thread, which takes the whole process down instead of failing
         * the one call.
         */
        private fun stored(): String = try {
            runBlocking { tokens.validAccessToken() }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("could not obtain a Spotify access token", e)
        }
    }
}
