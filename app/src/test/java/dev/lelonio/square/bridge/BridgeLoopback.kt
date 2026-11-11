// SPDX-License-Identifier: GPL-3.0-only
package dev.lelonio.square.bridge

import dev.lelonio.square.data.SpotifyApi
import dev.lelonio.square.data.SpotifyNetworkPolicy
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import retrofit2.Retrofit

/** Synthetic HTTP only. Retains all production Retrofit converters and OkHttp interceptors. */
internal class BridgeTestApi(original: SpotifyApi, url: String) : AutoCloseable {
    private val retrofit = Proxy.getInvocationHandler(original).let { handler ->
        handler.javaClass.declaredFields.single { Retrofit::class.java.isAssignableFrom(it.type) }
            .apply { isAccessible = true }.get(handler) as Retrofit
    }
    private val client = retrofit.callFactory() as OkHttpClient
    val api: SpotifyApi = retrofit.newBuilder().baseUrl(url).build().create(SpotifyApi::class.java)
    override fun close() { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
}

internal class BridgeLoopback(private val reply: (Request) -> Reply) : AutoCloseable {
    data class Request(val line: String, val headers: List<String>, val body: String, val manual: Boolean)
    data class Reply(val body: String = "", val status: String = "200 OK", val headers: String = "")
    private val server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${server.localPort}/"
    val requests = ConcurrentLinkedQueue<Request>()
    val errors = ConcurrentLinkedQueue<Throwable>()
    private val closing = AtomicBoolean()
    private val sockets = ConcurrentLinkedQueue<Socket>()
    private val workers = ConcurrentLinkedQueue<Thread>()
    private val acceptor = thread(isDaemon = true, name = "bridge-synthetic-accept") {
        try { while (!server.isClosed) {
            val socket = server.accept(); sockets.add(socket)
            workers.add(thread(isDaemon = true, name = "bridge-synthetic-response") {
                try { socket.use {
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val line = input.readLine() ?: return@use
                    val headers = mutableListOf<String>()
                    while (true) { val h = input.readLine() ?: return@use; if (h.isEmpty()) break; headers += h }
                    val length = headers.firstOrNull { it.startsWith("Content-Length:", true) }
                        ?.substringAfter(':')?.trim()?.toInt() ?: 0
                    val body = CharArray(length)
                    for (i in body.indices) body[i] = input.read().also { check(it >= 0) }.toChar()
                    val request = Request(line, headers, String(body), SpotifyNetworkPolicy.manual.value)
                    requests.add(request)
                    val response = reply(request)
                    val bytes = response.body.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().write(("HTTP/1.1 ${response.status}\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n${response.headers}\r\n").toByteArray(Charsets.US_ASCII))
                    socket.getOutputStream().write(bytes)
                } } catch (_: IOException) { /* Expected when the production call is revoked. */ }
                  catch (e: Throwable) { errors.add(e) }
                finally { sockets.remove(socket) }
            })
        } } catch (e: IOException) { if (!closing.get()) errors.add(e) }
    }
    override fun close() {
        closing.set(true); server.close(); sockets.forEach { it.close() }
        acceptor.join(2_000); workers.forEach { it.join(6_000) }
        check(!acceptor.isAlive && workers.none { it.isAlive }) { "Synthetic peer did not terminate" }
        check(errors.isEmpty()) { "Synthetic peer errors: $errors" }
    }
}
