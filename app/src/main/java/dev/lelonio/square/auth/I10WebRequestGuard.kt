package dev.lelonio.square.auth

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

/** OkHttp preserves request tags across follow-ups. Recheck before every physical attempt. */
internal class I10WebRequestGuard(private val guard: () -> Unit) {
    fun check() {
        try { guard() }
        catch (error: Exception) { throw IOException("Web API identity changed", error) }
    }

    class NetworkInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val guard = chain.request().tag(I10WebRequestGuard::class.java)
            guard?.check()
            val response = chain.proceed(chain.request())
            try { guard?.check() }
            catch (error: IOException) { response.close(); throw error }
            return response
        }
    }
}
