package dev.lelonio.square.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.lelonio.square.R
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Authorization Code + PKCE against Spotify's accounts service.
 *
 * The redirect target is a loopback listener rather than a custom scheme or an
 * App Link. That is not a shortcut: the client id below is librespot's, and the
 * only redirect URIs registered for it are `http://127.0.0.1:{port}/login`, so
 * an app-owned scheme would be rejected by the authorize endpoint. Loopback
 * redirects for native apps are the pattern described in RFC 8252 §7.3, and the
 * port is chosen at runtime because Spotify ignores it for loopback hosts.
 *
 * Using librespot's client id is what makes streaming work at all — a client id
 * registered in the Spotify developer dashboard authenticates fine against the
 * Web API but is refused by the access point. It is also the point at which this
 * app stops being something Spotify's terms permit.
 */
object SpotifyOAuth {

    /**
     * librespot's "keymaster" client id.
     *
     * The session must be configured with this same id — see the cache note in
     * the native engine — because login5 issues the access point's HTTP token
     * against whichever id the session carries, and it has to match the one the
     * OAuth token was minted for.
     */
    const val CLIENT_ID = "65b708073fc0480ea92a077233ca87bd"

    /**
     * Loopback port registered for [CLIENT_ID].
     *
     * Fixed rather than ephemeral: Spotify rejects unregistered redirect URIs
     * with "redirect_uri: not matching configuration".
     */
    private const val REDIRECT_PORT = 5588

    /**
     * The exact URI the user must register in their own dashboard application.
     *
     * Shown in the settings screen: Spotify matches redirect URIs literally, so
     * a typed-from-memory variant is the likeliest way for the setup to fail.
     */
    const val REDIRECT_URI = "http://127.0.0.1:$REDIRECT_PORT/login"

    private const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"

    /**
     * Scopes librespot requests. `streaming` is the one the access point checks;
     * the rest back the library and playback-state screens in the UI.
     */
    private val SCOPES = listOf(
        "app-remote-control",
        "playlist-modify",
        "playlist-modify-private",
        "playlist-modify-public",
        "playlist-read",
        "playlist-read-collaborative",
        "playlist-read-private",
        "streaming",
        "user-follow-modify",
        "user-follow-read",
        "user-library-modify",
        "user-library-read",
        "user-modify",
        "user-modify-playback-state",
        "user-modify-private",
        "user-personalized",
        "user-read-currently-playing",
        "user-read-email",
        "user-read-play-history",
        "user-read-playback-position",
        "user-read-playback-state",
        "user-read-private",
        "user-read-recently-played",
        "user-top-read",
    )

    /**
     * What the user's own registered application is asked for.
     *
     * Here rather than at the call site because two things need to agree on it:
     * the sign-in that asks, and the check that notices a connected application
     * was granted less than this — which happens every time this list grows, to
     * everyone who connected before it did.
     */
    val WEB_API_SCOPES = listOf(
        // The home feed's "artisti che ascolti".
        "user-top-read",
        // Reading what the account has actually played, which is a
        // different permission from the top artists above.
        "user-read-recently-played",
        // The Connect device picker: one to list them, one to move
        // playback.
        "user-read-playback-state",
        "user-modify-playback-state",
        // The player's "add to playlist". Which of the two
        // applies is the playlist's own visibility, not the
        // caller's, so both are asked for.
        "playlist-modify-private",
        "playlist-modify-public",
        // Following artists, and the list of the ones followed.
        "user-follow-read",
        "user-follow-modify",
        // Liked Songs and saved albums: the library screen reads
        // them, and the same sheet that adds to a playlist is what
        // hearts a track. Asked for late — until this was written
        // the saved-albums row simply came back 403 and the shelf
        // stayed empty, which reads as "you have no albums".
        "user-library-read",
        "user-library-modify",
        // Reading the account's own playlists, which are private
        // more often than not.
        "playlist-read-private",
        "playlist-read-collaborative",
    )

    private const val TAG = "SpotOAuth"

    // Token rotation is single-attempt: do not retry/redirect a refresh after its lease is revoked.
    // An already admitted response is parsed so TokenStore can preserve a received rotation.
    private val http = tokenClient()

    internal fun tokenClient(): OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .addInterceptor { chain ->
            val permit = chain.request().tag(dev.lelonio.square.data.SpotifyNetworkPolicy.Permit::class.java)
                ?: dev.lelonio.square.data.SpotifyNetworkPolicy.capture()
            permit.check()
            chain.proceed(chain.request().newBuilder()
                .tag(dev.lelonio.square.data.SpotifyNetworkPolicy.Permit::class.java, permit).build())
        }
        .addNetworkInterceptor { chain ->
            checkNotNull(chain.request().tag(dev.lelonio.square.data.SpotifyNetworkPolicy.Permit::class.java)).check()
            val response = chain.proceed(chain.request())
            // RetryAndFollowUpInterceptor otherwise replays 503/Retry-After:0 independently
            // of retryOnConnectionFailure. Keep the response so an admitted rotation can be parsed.
            if (response.code == 503) response.newBuilder().removeHeader("Retry-After").build() else response
        }.build()
    private val random = SecureRandom()

    data class Tokens(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAtMillis: Long,
        /**
         * What the account actually granted, space separated as Spotify sends it.
         *
         * Kept because asking for a permission and being given it are two
         * different things, and the difference is invisible until a call comes
         * back 403 with nothing to say about why. An application connected
         * before a permission was added to the list above goes on working for
         * everything else, so there is no moment at which the app would
         * otherwise notice; see MainViewModel.addToPlaylist, which uses this to
         * tell "this is not your playlist" from "reconnect the application".
         *
         * Empty for a token stored before this was recorded, which has to be
         * read as "unknown" rather than as "nothing".
         */
        val scope: String = "",
    )

    /**
     * The stored session is gone for good — revoked, or superseded by a newer
     * login. Distinguished from other failures because retrying can never
     * succeed: the only way out is a fresh OAuth flow, so callers must discard
     * the saved tokens rather than back off and try again.
     */
    class SessionExpired(message: String) : IllegalStateException(message)

    /**
     * Runs the full interactive flow: opens a Custom Tab, waits for Spotify to
     * redirect back to the loopback listener, then exchanges the code.
     *
     * Suspends until the user finishes or the listener times out. Cancelling the
     * calling coroutine closes the socket and aborts.
     */
    suspend fun authorize(
        context: Context,
        clientId: String = CLIENT_ID,
        scopes: List<String> = SCOPES,
    ): Tokens = withContext(Dispatchers.IO) {
        val permission = dev.lelonio.square.data.SpotifyNetworkPolicy.capture()
        val verifier = randomVerifier()
        val challenge = challengeFor(verifier)
        val state = randomState()

        val (code, redirectUri) = withOAuthLoopbackReceiver(
            REDIRECT_PORT, context.getString(R.string.browser_return),
        ) { receiver ->
            val redirectUri = receiver.redirectUri
            android.util.Log.i(TAG, "listening for OAuth redirect on $redirectUri")

            val authUri = Uri.parse(AUTHORIZE_URL).buildUpon()
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", redirectUri)
                .appendQueryParameter("scope", scopes.joinToString(" "))
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("state", state)
                .build()

            withContext(Dispatchers.Main) {
                permission.check()
                CustomTabsIntent.Builder().build().apply {
                    // The caller is a ViewModel, so this is the application
                    // context; starting an activity from one without
                    // FLAG_ACTIVITY_NEW_TASK throws. Launching the tab in its
                    // own task is also what we want — the redirect comes back
                    // over the loopback socket, not through an activity result,
                    // so the browser has no reason to sit on our stack.
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }.launchUrl(context, authUri)
            }

            val target = receiver.awaitRequest()
            val callback = Uri.parse("http://127.0.0.1$target")
            android.util.Log.i(TAG, "redirect received")
            check(callback.getQueryParameter("state") == state) { "OAuth state mismatch — request was not ours" }
            val code = callback.getQueryParameter("code")
                ?: error("authorization denied: ${callback.getQueryParameter("error")}")
            code to redirectUri
        }
        // The listener is released before the token HTTP exchange as well.
        exchangeCode(code, verifier, redirectUri, clientId, permission)
    }

    /** Trades a refresh token for a fresh access token. */
    suspend fun refresh(
        refreshToken: String,
        clientId: String = CLIENT_ID,
        permit: dev.lelonio.square.data.SpotifyNetworkPolicy.Permit = dev.lelonio.square.data.SpotifyNetworkPolicy.capture(),
    ): Tokens = refreshUsing(http, TOKEN_URL, refreshToken, clientId, permit)

    /** Same production request/parser with a synthetic transport endpoint for host contracts. */
    internal suspend fun refreshUsing(client: OkHttpClient, endpoint: String, refreshToken: String,
        clientId: String, permit: dev.lelonio.square.data.SpotifyNetworkPolicy.Permit): Tokens = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken).add("client_id", clientId).build()
        postToken(form, permit, client, endpoint).let { it.copy(refreshToken = it.refreshToken ?: refreshToken) }
    }

    internal fun exchangeCode(
        code: String,
        verifier: String,
        redirectUri: String,
        clientId: String,
        permit: dev.lelonio.square.data.SpotifyNetworkPolicy.Permit,
        client: OkHttpClient = http,
        endpoint: String = TOKEN_URL,
    ): Tokens {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", clientId)
            .add("code_verifier", verifier)
            .build()
        return postToken(form, permit, client, endpoint)
    }

    private fun postToken(form: FormBody, permit: dev.lelonio.square.data.SpotifyNetworkPolicy.Permit = dev.lelonio.square.data.SpotifyNetworkPolicy.capture(),
        client: OkHttpClient = http, endpoint: String = TOKEN_URL): Tokens {
        val request = Request.Builder().url(endpoint).post(form)
            .tag(dev.lelonio.square.data.SpotifyNetworkPolicy.Permit::class.java, permit).build()
        permit.check()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // `invalid_grant` is Spotify saying the refresh token is dead —
                // revoked, or replaced by a later login. Retrying is pointless.
                if (body.contains("invalid_grant")) {
                    throw SessionExpired("sessione scaduta o revocata: accedi di nuovo")
                }
                error("token request failed (${response.code}): $body")
            }
            val json = JSONObject(body)
            val expiresIn = json.optLong("expires_in", 3600)
            return Tokens(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token").takeIf { it.isNotEmpty() },
                expiresAtMillis = System.currentTimeMillis() + expiresIn * 1000,
                scope = json.optString("scope"),
            )
        }
    }

    private fun randomVerifier(): String = ByteArray(64)
        .also(random::nextBytes)
        .let(::base64Url)

    private fun randomState(): String = ByteArray(16)
        .also(random::nextBytes)
        .let(::base64Url)

    private fun challengeFor(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
