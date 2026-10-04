package dev.pampa.fluidify.wear.protocol

import org.junit.Assert.*
import org.junit.Test

class AudioLightTest {
    private fun packet(seq: Long=1,generation:Long=1) = AudioLightTelemetry("a",seq,"track",generation,1000,.4f,.3f,.2f,.1f,10000)
    @Test fun rejectsOtherTrackSubscriptionDuplicatesAndOutOfOrderGenerations() {
        val receiver=AudioLightReceiver()
        assertFalse(receiver.accept(packet(),"other","track",1000))
        assertFalse(receiver.accept(packet(),"a","other",1000))
        assertTrue(receiver.accept(packet(),"a","track",1000))
        assertFalse(receiver.accept(packet(),"a","track",1000))
        assertTrue(receiver.accept(packet(3,2),"a","track",1000))
        assertFalse(receiver.accept(packet(4,1),"a","track",1000))
        assertFalse(receiver.accept(packet(2,2),"a","track",1000))
    }
    @Test fun rejectsStaleInvalidAndMisalignedFramesUsingHelloClockOffset() {
        val receiver=AudioLightReceiver()
        assertFalse(receiver.accept(packet(),"a","track",3000))
        assertFalse(receiver.accept(packet().copy(bass=Float.NaN),"a","track",1000))
        assertFalse(receiver.accept(packet().copy(energy=2f),"a","track",1000))
        assertFalse(receiver.accept(packet(),"a","track",1000,12001,1000))
        assertTrue(receiver.accept(packet(),"a","track",1000,11000,1000))
    }
    @Test fun renewExpiryNearbyOnlyAndOldUnsubscribeCannotCancelNewSubscription() {
        val leases=AudioLightLeases()
        leases.request("node",AudioLightSubscription("a",true),false,0)
        assertTrue(leases.isEmpty())
        leases.request("node",AudioLightSubscription("a",true),true,0)
        assertEquals(mapOf("node" to "a"),leases.recipients(4999,setOf("node")))
        assertTrue(leases.recipients(5000,setOf("node")).isEmpty())
        leases.request("node",AudioLightSubscription("a",true),true,0)
        leases.request("node",AudioLightSubscription("b",true),true,4000)
        leases.request("node",AudioLightSubscription("a",false),true,4500)
        assertEquals(mapOf("node" to "b"),leases.recipients(6000,setOf("node")))
        assertTrue(leases.recipients(6001,emptySet()).isEmpty())
        leases.request("node",AudioLightSubscription("c",true),true,7000)
        leases.request("node",AudioLightSubscription("c",false),false,7001)
        assertTrue(leases.isEmpty())
    }
    @Test fun optionalFeatureIsAbsentOnOlderHelloAndFramesCannotWakePersistentService() {
        val old=WearCodec.decodeOrNull(Hello.serializer(),"""{"role":"PHONE","versionName":"1.5.3","versionCode":11}""".toByteArray())!!
        assertFalse(Features.AUDIO_LIGHT in old.features)
        assertFalse(WearPaths.AUDIO_LIGHT_FRAME.startsWith(WearPaths.PREFIX))
        assertTrue(WearPaths.AUDIO_LIGHT_SUBSCRIBE.startsWith(WearPaths.PREFIX))
        assertEquals(packet(),WearCodec.decodeOrNull(AudioLightTelemetry.serializer(),WearCodec.encode(AudioLightTelemetry.serializer(),packet())))
    }
}
