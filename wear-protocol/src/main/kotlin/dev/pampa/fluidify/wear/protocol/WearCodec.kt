package dev.pampa.fluidify.wear.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * How every payload is turned into bytes and back.
 *
 * One [Json] for both sides, configured so that the two can be a version apart:
 * an unknown field from a newer peer is ignored rather than fatal, and a field
 * left at its default is not written at all, which keeps the state message well
 * under a Bluetooth packet's worth for an ordinary track.
 */
object WearCodec {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        classDiscriminator = "t"
        coerceInputValues = true
    }

    fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray =
        json.encodeToString(serializer, value).encodeToByteArray()

    fun <T> decode(serializer: KSerializer<T>, bytes: ByteArray): T =
        json.decodeFromString(serializer, bytes.decodeToString())

    /** Like [decode], but a malformed or foreign payload is `null` instead of an exception. */
    fun <T> decodeOrNull(serializer: KSerializer<T>, bytes: ByteArray): T? =
        runCatching { decode(serializer, bytes) }.getOrNull()

    fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 3 + 64)
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
}
