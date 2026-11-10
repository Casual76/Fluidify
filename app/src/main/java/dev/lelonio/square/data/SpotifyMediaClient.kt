package dev.lelonio.square.data

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.ForwardingSource
import okio.buffer

/** Images have no auth fallback. Each redirect is an explicit admission on one original permit. */
internal object SpotifyMediaClient {
    fun create(): OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .addInterceptor(SpotifyNetworkInterceptor())
        .addInterceptor { chain ->
            val permit = checkNotNull(chain.request().tag(SpotifyNetworkPolicy.Permit::class.java))
            var request = chain.request()
            var answer: okhttp3.Response? = null
            for (hop in 0..5) {
                permit.check()
                val response = chain.proceed(request)
                if (response.code !in listOf(301, 302, 303, 307, 308)) { answer = response; break }
                val location = response.header("Location")
                val url = location?.let { request.url.resolve(it) }
                response.close()
                if (url == null || hop == 5 || (request.url.isHttps && !url.isHttps)) throw IOException("Invalid media redirect")
                val next = request.newBuilder().url(url)
                if (request.url.host != url.host || request.url.port != url.port || request.url.scheme != url.scheme) next.removeHeader("Authorization")
                request = next.build()
            }
            val response = checkNotNull(answer)
            val body = response.body
            if (body == null) response else response.newBuilder().body(object : ResponseBody() {
                private val guarded = object : ForwardingSource(body.source()) {
                    override fun read(sink: okio.Buffer, byteCount: Long): Long {
                        permit.check()
                        val count = super.read(sink, byteCount)
                        permit.check()
                        return count
                    }
                }.buffer()
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = guarded
            }).build()
        }
        .addNetworkInterceptor(SpotifyNetworkInterceptor(networkAttempt = true))
        .build()
}
