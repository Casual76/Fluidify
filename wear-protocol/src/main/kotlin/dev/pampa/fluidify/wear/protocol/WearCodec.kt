package dev.pampa.fluidify.wear.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
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

    /**
     * The `id` of a request that [decodeOrNull] could not read, or null when there is none to find.
     *
     * A watch a version ahead may send a command or a question this phone has never heard of: the
     * polymorphic `t` names a type nobody registered, and decoding fails as a whole. The id is
     * still there, and it is all the phone needs to answer "I cannot do that" (see
     * [AckErrors.UNSUPPORTED]) instead of leaving the watch to wait out its timeout and call the
     * phone unreachable. A lenient parse of the JSON tree, with no serializer involved, because
     * the serializer is exactly what failed.
     *
     * Only a number counts, and only at the top level: whatever else the payload holds is not
     * looked at, so a hostile or garbled message gets no answer rather than a wrong one.
     */
    fun peekId(bytes: ByteArray): Long? = runCatching {
        val root = json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        val id = root?.get("id") as? JsonPrimitive
        if (id == null || id.isString) null else id.longOrNull
    }.getOrNull()

    fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 3 + 64)
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /**
     * Unpacks [bytes], refusing to produce more than [maxBytes].
     *
     * A few kilobytes of gzip can stand for gigabytes of zeros, and the reader of a channel does
     * not choose what arrives on it: without a ceiling, one bad payload is an out-of-memory crash
     * of the whole process. The legitimate payloads (an RPC answer too big for a message) are well
     * under [MAX_GUNZIPPED_BYTES].
     *
     * @throws PayloadTooLargeException when the unpacked size would pass [maxBytes].
     */
    fun gunzip(bytes: ByteArray, maxBytes: Int = MAX_GUNZIPPED_BYTES): ByteArray {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val out = ByteArrayOutputStream(bytes.size.coerceAtMost(maxBytes))
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                // Checked before it is kept: the bomb is stopped at the ceiling, not after it.
                if (read > maxBytes - total) throw PayloadTooLargeException(maxBytes)
                total += read
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    /** The most [gunzip] unpacks by default: eight times what the largest answer needs. */
    const val MAX_GUNZIPPED_BYTES = 8 * 1024 * 1024
}

/** A payload that unpacks to more than the reader agreed to hold; see [WearCodec.gunzip]. */
class PayloadTooLargeException(val limit: Int) : IOException("payload larger than $limit bytes")
