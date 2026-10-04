package dev.lelonio.square.playback

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class AudioSpectrumTest {
    @Test fun pauseFadeCompletesIn250msEvenWithDroppedFrames() {
        val fade = AudioLightFade(1f)
        assertEquals(1f, fade.update(false, 1000), .001f)
        assertEquals(.5f, fade.update(false, 1125), .001f)
        assertEquals(0f, fade.update(false, 1250), .001f)
        assertEquals(0f, fade.update(false, 2000), .001f)
        assertTrue(fade.update(true, 2100) > 0f)
        val dropped = AudioLightFade(1f)
        dropped.update(false, 1000)
        assertEquals(0f, dropped.update(false, 1700), .001f)
    }
    private fun tone(hz: Int, amplitude: Float = .4f, rate: Int = 48000) = FloatArray(2048) { (sin(2 * PI * hz * it / rate) * amplitude).toFloat() }
    @Test fun logarithmicBandsSeparateSyntheticTonesAtDifferentRates() {
        for (rate in listOf(32000, 44100, 48000)) for ((band,hz) in listOf(0 to 60, 1 to 125, 2 to 280, 3 to 600, 4 to 1300, 5 to 3000, 6 to 6000, 7 to 11000)) {
            val f = AudioSpectrum().measure(tone(hz, rate = rate), 2048, rate, false)
            assertEquals("$hz Hz at $rate", band, f.bands.indices.maxBy { f.bands[it] })
            assertTrue(f.bands.all { it.isFinite() && it in 0f..1f })
        }
    }
    @Test fun silenceAndQuietPassagesAreNotAmplified() {
        val analyzer = AudioSpectrum()
        repeat(100) { assertEquals(0f, analyzer.measure(FloatArray(2048),2048,48000,false).energy) }
        assertTrue(analyzer.measure(tone(125,.003f),2048,48000,false).energy < .1f)
    }
    @Test fun lightModeRespondsToBassWithoutFFTAndResetsAfterImpulse() {
        val analyzer = AudioSpectrum()
        val bass = analyzer.measure(tone(70),2048,48000,true)
        analyzer.reset()
        val treble = analyzer.measure(tone(8000),2048,48000,true)
        assertTrue(bass.bass > treble.bass * 2)
        assertTrue(bass.energy > 0); assertEquals(List(8){0f},bass.bands)
        analyzer.reset(); assertEquals(0f,analyzer.measure(FloatArray(2048),2048,48000,true).bass)
    }
    @Test fun capturePreservesPCMAndWaitsForQueuedAudioAndClearsOnSeek() {
        val owner = Any(); AudioReactive.acquire(owner)
        try {
            AudioReactive.source("track",true,1000,speed=2f,discontinuity=true)
            val buffer = ByteBuffer.allocate(2048 * 4 + 8).order(ByteOrder.LITTLE_ENDIAN)
            buffer.position(4)
            tone(125).forEach { buffer.putShort((it*32767).toInt().toShort()); buffer.putShort((it*32767).toInt().toShort()) }
            buffer.flip(); buffer.position(4); buffer.order(ByteOrder.BIG_ENDIAN)
            val original = buffer.array().clone(); val position=buffer.position(); val limit=buffer.limit()
            val capturedAt = System.nanoTime()
            AudioReactive.capture(buffer,buffer.remaining(),48000,2,queuedMs=200)
            assertArrayEquals(original,buffer.array()); assertEquals(position,buffer.position()); assertEquals(limit,buffer.limit()); assertEquals(ByteOrder.BIG_ENDIAN,buffer.order())
            assertNull(AudioReactive.frame(capturedAt + 100_000_000))
            // Virtual playback time separates queue/expiry semantics from host
            // scheduling: R8 may otherwise starve this low-priority worker.
            val visibleAt = capturedAt + 600_000_000
            val timeout=System.nanoTime()+5_000_000_000
            while (AudioReactive.frame(visibleAt) == null && System.nanoTime()<timeout) Thread.sleep(5)
            val frame=AudioReactive.frame(visibleAt); assertNotNull(frame); assertTrue(frame!!.positionMs>=1400)
            assertNull(AudioReactive.frame(capturedAt + 2_000_000_000))
            AudioReactive.source("track",true,0,discontinuity=true); assertNull(AudioReactive.frame())
            AudioReactive.source("other",true,0); assertNull(AudioReactive.frame())
        } finally { AudioReactive.release(owner) }
        assertNull(AudioReactive.frame())
    }
    @Test fun floatMonoAndSplitWindowsProduceFullFFTWithFiniteValues() {
        val owner=Any(); AudioReactive.acquire(owner)
        try {
            AudioReactive.source("float",true,0,discontinuity=true)
            val data=tone(600)
            val visibleAt = System.nanoTime() + 600_000_000
            for (offset in listOf(0,1024)) {
                val b=ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
                repeat(1024) { b.putFloat(data[offset+it]) }; b.flip()
                AudioReactive.capture(b,b.remaining(),48000,1,floatPcm=true)
                assertEquals(0,b.position())
            }
            val timeout=System.nanoTime()+5_000_000_000
            while(AudioReactive.frame(visibleAt)==null && System.nanoTime()<timeout) Thread.sleep(5)
            val frame = AudioReactive.frame(visibleAt)!!
            assertEquals(3,frame.bands.indices.maxBy { frame.bands[it] })
        } finally { AudioReactive.release(owner) }
    }
    @Test fun oldSinkCannotSupplyFramesAfterSourceOrSpeedChange() {
        val owner = Any(); AudioReactive.acquire(owner)
        try {
            AudioReactive.source("local", true, 500, speed = .75f, discontinuity = true, kind = AudioLightSource.MEDIA3)
            val pcm = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
            tone(125).forEach { pcm.putShort((it * 32767).toInt().toShort()) }; pcm.flip()
            AudioReactive.capture(pcm, pcm.remaining(), 48000, 1, kind = AudioLightSource.NATIVE)
            Thread.sleep(50); assertNull(AudioReactive.frame())
            val visibleAt = System.nanoTime() + 600_000_000
            AudioReactive.capture(pcm, pcm.remaining(), 48000, 1, kind = AudioLightSource.MEDIA3)
            val timeout = System.nanoTime() + 5_000_000_000
            while (AudioReactive.frame(visibleAt) == null && System.nanoTime() < timeout) Thread.sleep(5)
            assertNotNull(AudioReactive.frame(visibleAt))
            AudioReactive.source("local", true, 500, speed = 1.5f, kind = AudioLightSource.MEDIA3)
            assertNull(AudioReactive.frame())
        } finally { AudioReactive.release(owner) }
    }
}
