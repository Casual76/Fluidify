package dev.pampa.fluidify.wear.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class CompatibilityTest {

    private fun hello(role: Role, major: Int = ProtocolVersion.MAJOR, cert: String = "", features: Set<String> = emptySet()) =
        Hello(role = role, versionName = "1.5.0", versionCode = 8, protoMajor = major, certSha256 = cert, features = features)

    @Test
    fun sameMajorIsCompatible() {
        assertEquals(Compatibility.OK, compatibility(hello(Role.PHONE), hello(Role.WATCH)))
    }

    @Test
    fun olderPeerShouldBeUpdated() {
        assertEquals(Compatibility.PEER_OUTDATED, compatibility(hello(Role.PHONE, major = 2), hello(Role.WATCH, major = 1)))
    }

    @Test
    fun olderSelfShouldBeUpdated() {
        assertEquals(Compatibility.SELF_OUTDATED, compatibility(hello(Role.WATCH, major = 1), hello(Role.PHONE, major = 2)))
    }

    @Test
    fun differentCertificatesWinOverEverything() {
        assertEquals(
            Compatibility.SIGNATURE_MISMATCH,
            compatibility(hello(Role.PHONE, major = 2, cert = "AA"), hello(Role.WATCH, major = 1, cert = "BB")),
        )
    }

    @Test
    fun certificateComparisonIgnoresCaseAndMissingValues() {
        assertEquals(Compatibility.OK, compatibility(hello(Role.PHONE, cert = "ab:cd"), hello(Role.WATCH, cert = "AB:CD")))
        assertEquals(Compatibility.OK, compatibility(hello(Role.PHONE, cert = "ab"), hello(Role.WATCH, cert = "")))
    }

    @Test
    fun onlySharedFeaturesAreUsable() {
        val phone = hello(Role.PHONE, features = setOf(Features.VOLUME, Features.QUEUE, Features.LIBRARY))
        val watch = hello(Role.WATCH, features = setOf(Features.VOLUME, Features.LIBRARY, Features.HANDOFF))
        assertEquals(setOf(Features.VOLUME, Features.LIBRARY), sharedFeatures(phone, watch))
    }
}
