package dev.lelonio.square.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioLightProcessorTest {
    @Test fun preservesPCMExactlyFor16BitAndFloatAcrossRatesAndChannels() {
        for(encoding in listOf(C.ENCODING_PCM_16BIT,C.ENCODING_PCM_FLOAT)) for(rate in listOf(32000,44100,48000)) for(channels in listOf(1,2,6)) {
            val processor=AudioLightProcessor()
            val format=AudioProcessor.AudioFormat(rate,channels,encoding)
            assertEquals(format,processor.configure(format)); assertTrue(processor.isActive()); processor.flush()
            val bytes=ByteArray(4096) { (it*37).toByte() }
            val input=ByteBuffer.allocateDirect(bytes.size+8).order(ByteOrder.nativeOrder())
            input.position(4); input.put(bytes); input.flip(); input.position(4)
            processor.queueInput(input); assertEquals(input.limit(),input.position())
            val out=processor.output; val actual=ByteArray(out.remaining()); out.get(actual); assertArrayEquals(bytes,actual)
            processor.queueEndOfStream(); assertTrue(processor.isEnded); processor.reset(); assertFalse(processor.isActive)
        }
    }
    @Test fun flushDropsOldOutputAndResetsEndAndTiming() {
        val p=AudioLightProcessor(); p.configure(AudioProcessor.AudioFormat(48000,2,C.ENCODING_PCM_16BIT)); p.flush()
        p.queuedMs=300; p.queueInput(ByteBuffer.allocateDirect(16)); p.queueEndOfStream()
        assertFalse(p.isEnded); p.flush(); assertFalse(p.isEnded); assertFalse(p.output.hasRemaining()); assertEquals(0,p.queuedMs)
    }
}
