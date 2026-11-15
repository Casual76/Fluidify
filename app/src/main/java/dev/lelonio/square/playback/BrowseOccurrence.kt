package dev.lelonio.square.playback

import java.security.MessageDigest

/** A row is an occurrence in one ordered shelf, including repeated URIs. */
internal data class BrowseOccurrence(val first: String, val second: String,
    val index: Int? = null, val fingerprint: String? = null) {
    fun resolve(uris: List<String>): Int? {
        if (index != null) return index.takeIf {
            fingerprint == digest(uris) && uris.getOrNull(it) == second
        }
        return uris.indices.singleOrNull { uris[it] == second }
    }
    companion object {
        fun digest(uris: List<String>): String = MessageDigest.getInstance("SHA-256")
            .digest(uris.joinToString("\u0000").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        fun parse(id: String, prefix: String): BrowseOccurrence? {
            if (!id.startsWith(prefix)) return null
            var body = id.removePrefix(prefix)
            var index: Int? = null
            var digest: String? = null
            if (body.startsWith("@")) {
                val header = body.substringBefore('/')
                index = header.substringAfter('@').substringBefore(':').toIntOrNull()
                    ?.takeIf { it >= 0 } ?: return null
                digest = header.substringAfter(':', "").takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                    ?: return null
                body = body.substringAfter('/', "")
            }
            val cut = body.lastIndexOf('/')
            if (cut <= 0 || cut == body.lastIndex) return null
            return BrowseOccurrence(body.take(cut), body.substring(cut + 1), index, digest)
        }
    }
}
