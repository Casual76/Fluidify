package dev.lelonio.square.playback

import android.app.Application
import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.math.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.*

/** Host measurements, deliberately not presented as a phone/watch battery benchmark. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=Application::class)
class AudioLightBudgetTest {
    @Test fun recordHostCPUAndDrawingCostsWithVisualsOnAndOff() {
        val bean=Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val cpuTime=Class.forName("java.lang.management.ThreadMXBean").getMethod("getCurrentThreadCpuTime")
        fun measure(block:()->Unit):Double {
            repeat(100){block()}; val start=cpuTime.invoke(bean) as Long
            repeat(1000){block()}; return ((cpuTime.invoke(bean) as Long)-start)/1e9
        }
        val signal=FloatArray(2048){(.3*sin(2*PI*80*it/48000)+.1*sin(2*PI*1300*it/48000)).toFloat()}
        val fft=AudioSpectrum();val light=AudioSpectrum()
        val richMs=measure{fft.measure(signal,2048,48000,false)}
        val lightMs=measure{light.measure(signal,2048,48000,true)}
        val bitmap=Bitmap.createBitmap(824,1784,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap)
        val frame=fft.measure(signal,2048,48000,false);val painter=AudioHaloPainter()
        val cover=RectF(107f,300f,717f,910f)
        val drawMs=measure{painter.draw(canvas,824f,1784f,frame,0xffef88ab.toInt(),cover)}
        painter.reset()
        val offMs=measure{painter.draw(canvas,824f,1784f,null,0xffef88ab.toInt(),cover)}
        val miniMs=measure{painter.draw(canvas,744f,112f,frame,0xffef88ab.toInt(),mini=true)}
        val ringMs=measure{painter.drawRing(canvas,480f,.38f,45f,0xffef88ab.toInt(),.7f,.9f)}
        val report="""{"environment":"Windows JBR/Robolectric native software canvas; host CPU only","unit":"ms CPU per call, 1000 warmed iterations","fft2048":$richMs,"lightNoFFT":$lightMs,"phoneOn":$drawMs,"phoneOff":$offMs,"miniOn":$miniMs,"watchRingOn":$ringMs}"""
        File("build/audio-light-host-performance.json").apply{parentFile?.mkdirs();writeText(report)}
        println(report)
    }
}
