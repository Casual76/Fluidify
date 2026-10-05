package dev.lelonio.square.playback

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the music light costs and whether it stays within bounds, measured on the host.
 *
 * The budgets are deliberately loose — a desktop JVM through Robolectric's software canvas is not
 * a phone — so they catch an order-of-magnitude regression (an allocation per sample, a bitmap per
 * frame) rather than tuning noise. The figures go to `build/audio-light-host-performance.json` for
 * anyone comparing two builds; they are not a battery benchmark.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class AudioLightBudgetTest {

    private val signal = FloatArray(2048) {
        (.3 * sin(2 * PI * 80 * it / 48000) + .1 * sin(2 * PI * 1300 * it / 48000)).toFloat()
    }

    @Test fun measurementsStayInRange() {
        val frame = AudioSpectrum().measure(signal, 2048, 48000, light = false)
        assertEquals(8, frame.bands.size)
        for (value in frame.bands + listOf(frame.energy, frame.bass, frame.mids, frame.treble)) {
            assertTrue("$value out of range", value in 0f..1f)
        }
        // An 80 Hz tone at three times the level of the 1.3 kHz one: the bass leads.
        assertTrue(frame.bass > frame.treble)

        val light = AudioSpectrum().measure(signal, 2048, 48000, light = true)
        assertTrue(light.energy > 0f)
        assertTrue("the light mode skips the transform", light.bands.all { it == 0f })

        val silence = AudioSpectrum().measure(FloatArray(2048), 2048, 48000, light = false)
        assertEquals(0f, silence.energy)
        assertTrue(silence.bands.all { it == 0f })
    }

    @Test fun nothingIsDrawnWithoutAFrame() {
        val bitmap = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)
        AudioHaloPainter().draw(Canvas(bitmap), 200f, 400f, null, 0xffef88ab.toInt())
        val pixels = IntArray(200 * 400)
        bitmap.getPixels(pixels, 0, 200, 0, 0, 200, 400)
        assertTrue(pixels.all { it == 0 })
    }

    @Test fun analysisOnlyRunsWhileSomeoneShowsIt() {
        val owner = Any()
        AudioReactive.source("spotify:track:test", playing = true, positionMs = 0)
        assertNull("nobody holds the light", AudioReactive.frame())

        AudioReactive.acquire(owner)
        try {
            val pcm = ByteBuffer.allocate(2048 * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in signal) {
                val value = (sample * Short.MAX_VALUE).toInt().toShort()
                pcm.putShort(value)
                pcm.putShort(value)
            }
            pcm.flip()
            AudioReactive.capture(pcm, pcm.remaining(), 48000, 2)
            assertEquals("the caller's buffer is left as it was", 0, pcm.position())

            var frame: AudioLightFrame? = null
            val deadline = System.nanoTime() + 2_000_000_000L
            while (frame == null && System.nanoTime() < deadline) {
                Thread.sleep(10)
                frame = AudioReactive.frame()
            }
            assertNotNull("the analysis thread answered", frame)
        } finally {
            AudioReactive.release(owner)
            AudioReactive.source("", playing = false, positionMs = 0)
        }
        assertNull("released, there is nothing to show", AudioReactive.frame())
    }

    @Test fun hostCostsStayWithinLooseBudgets() {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val cpuTime = Class.forName("java.lang.management.ThreadMXBean").getMethod("getCurrentThreadCpuTime")
        fun measureMs(block: () -> Unit): Double {
            repeat(100) { block() }
            val start = cpuTime.invoke(bean) as Long
            repeat(1000) { block() }
            return ((cpuTime.invoke(bean) as Long) - start) / 1e9
        }

        val fft = AudioSpectrum()
        val cheap = AudioSpectrum()
        val richMs = measureMs { fft.measure(signal, 2048, 48000, false) }
        val lightMs = measureMs { cheap.measure(signal, 2048, 48000, true) }

        val bitmap = Bitmap.createBitmap(824, 1784, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val frame = fft.measure(signal, 2048, 48000, false)
        val painter = AudioHaloPainter()
        val cover = RectF(107f, 300f, 717f, 910f)
        val drawMs = measureMs { painter.draw(canvas, 824f, 1784f, frame, 0xffef88ab.toInt(), cover) }
        painter.reset()
        val offMs = measureMs { painter.draw(canvas, 824f, 1784f, null, 0xffef88ab.toInt(), cover) }
        val miniMs = measureMs { painter.draw(canvas, 744f, 112f, frame, 0xffef88ab.toInt(), mini = true) }
        val ringMs = measureMs { painter.drawRing(canvas, 480f, .38f, 45f, 0xffef88ab.toInt(), .7f, .9f) }

        val report = """{"environment":"host JVM, Robolectric native software canvas","unit":"ms CPU per call",""" +
            """"fft2048":$richMs,"lightNoFFT":$lightMs,"phoneOn":$drawMs,"phoneOff":$offMs,""" +
            """"miniOn":$miniMs,"watchRingOn":$ringMs}"""
        File("build/audio-light-host-performance.json").apply {
            parentFile?.mkdirs()
            writeText(report)
        }

        assertTrue("fft $richMs ms", richMs < 5.0)
        assertTrue("light mode $lightMs ms should be cheaper than the fft $richMs ms", lightMs < richMs)
        assertTrue("halo off $offMs ms should cost almost nothing", offMs < 1.0)
        assertTrue("halo $drawMs ms", drawMs < 25.0)
        assertTrue("pill $miniMs ms", miniMs < 10.0)
        assertTrue("ring $ringMs ms", ringMs < 25.0)
    }
}
