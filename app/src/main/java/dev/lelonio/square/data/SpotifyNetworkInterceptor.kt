package dev.lelonio.square.data

import okhttp3.Interceptor
import okhttp3.Response

/** Application admission precedes auth; network admission also covers retries and redirects. */
internal class SpotifyNetworkInterceptor(private val networkAttempt: Boolean = false) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val permit = request.tag(SpotifyNetworkPolicy.Permit::class.java)
            ?: SpotifyNetworkPolicy.capture()
        permit.check()
        if (networkAttempt) return chain.proceed(request)

        val revocation = SpotifyNetworkPolicy.observe { offline ->
            if (offline) chain.call().cancel()
        }
        try {
            permit.check()
            return chain.proceed(request.newBuilder()
                .tag(SpotifyNetworkPolicy.Permit::class.java, permit).build())
                .also { response ->
                    try { permit.check() }
                    catch (failure: SpotifyNetworkPolicy.Offline) { response.close(); throw failure }
                }
        } finally {
            revocation.close()
        }
    }
}
