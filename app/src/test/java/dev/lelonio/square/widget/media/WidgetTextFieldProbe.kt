package dev.lelonio.square.widget.media

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import dev.lelonio.square.R
import java.io.File
import java.lang.reflect.InvocationTargetException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Text protection of the Scena widget, measured on native ink and on the produced bitmap.
 *
 * It reuses the ORIGINAL helpers of [SceneWidgetTest] by reflection (same native-ink-v1 coverage,
 * same 4.5 threshold) and never reads a production stencil, distance field or solver. Unlike the
 * original cases it does not stop at the first failing text: every measurement is archived.
 * The original 23 tests, their tolerances and the alignment gate are untouched and still required.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35], application=Application::class, qualifiers="w412dp-h892dp-xhdpi")
class WidgetTextFieldProbe {
    private val original=SceneWidgetTest()
    private fun call(name:String,vararg args:Any?):Any? {
        val method=SceneWidgetTest::class.java.declaredMethods.single { it.name==name && it.parameterTypes.size==args.size }.apply { isAccessible=true }
        return try { method.invoke(original,*args) } catch(e:InvocationTargetException) { throw e.targetException }
    }
    private fun field(sample:Any,name:String):Any? = sample.javaClass.getDeclaredMethod(name).apply { isAccessible=true }.invoke(sample)
    private fun recycle(sample:Any) { sample.javaClass.getDeclaredMethod("recycle").apply { isAccessible=true }.invoke(sample) }
    private val output=File("build/outputs/roborazzi/widget-v2/text-field-regression")

    @Before fun attach() { original.attachLauncherFixture() }
    @After fun release() { original.releaseLauncherFixture() }

    private class Variant(val name:String,val font:Float,val rtl:Boolean,val title:String?,val artist:String)
    private class Ink(val id:Int,val sample:Any,val alpha:IntArray,val underlay:Bitmap)

    private val textIds=listOf(R.id.media_widget_title,R.id.media_widget_artist,R.id.media_widget_status,
        R.id.media_widget_time,R.id.media_widget_permission)

    /** Gate contract (+1 native px) AND a registration margin of one more dp, on hostile artwork. */
    @Test fun nativeTextKeepsContrastWithARegistrationMarginOnHostileArtwork() {
        val context=call("getContext") as Context
        val resources=context.resources
        val saved=Configuration(resources.configuration)
        @Suppress("UNCHECKED_CAST")
        val covers=call("comparisonCovers") as List<Pair<String,Bitmap>>
        val variants=listOf(
            Variant("marea",1f,false,"Marea","Luma"),
            Variant("default-title",1f,false,null,""),
            Variant("air",1f,false,"M                  M",""),
            Variant("accented-long",1f,false,
                "L’orizzonte più lontano: città, perché c’è ancora musica nell’oscurità e una melodia che non finisce mai","Élodie · già qui"),
            Variant("arabic-font2",2f,true,"رحلة طويلة عبر الليل وموسيقى بلا نهاية","Luma · Artist with a long name"),
        )
        // The gate dilates by 1 native px. The margin adds one dp: the host may round the layout
        // and resample the <=384px bitmap, and protection must not end exactly at the glyph.
        val margin=1+kotlin.math.ceil(resources.displayMetrics.density.toDouble()).toInt()
        val observed=JSONArray()
        val failures=mutableListOf<String>()
        var inflated=0
        try {
            for(variant in variants) {
                val config=Configuration(saved).apply {
                    fontScale=variant.font
                    setLayoutDirection(java.util.Locale(if(variant.rtl)"ar" else "en"))
                }
                @Suppress("DEPRECATION") resources.updateConfiguration(config,resources.displayMetrics)
                for((coverName,cover) in covers) {
                    // Every variant on the checker; the two canonical metadata sets on every cover.
                    if(coverName!="checker-synthetic" && variant.name!="marea" && variant.name!="arabic-font2")continue
                    for(theme in listOf(MediaWidgetTheme.DarkGlass,MediaWidgetTheme.LightGlass))for((w,h) in listOf(220 to 176,344 to 360,500 to 150)) {
                        var snapshot=call("active",true,cover) as MediaPlaybackSnapshot
                        if(variant.title!=null)snapshot=snapshot.copy(title=variant.title,artist=variant.artist)
                        val root=call("inflate",snapshot,w,h,theme,null) as View
                        inflated++
                        assertEquals("RIG: actual direction",if(variant.rtl)View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR,root.layoutDirection)
                        val label="$coverName-${variant.name}-${w}x$h-$theme"
                        val inks=mutableListOf<Ink>()
                        try {
                            for(id in textIds) {
                                val text=root.findViewById<TextView>(id)
                                if(!text.isShown || text.text.isBlank())continue
                                val sample=requireNotNull(call("nativeInk",root,text))
                                inks+=Ink(id,sample,field(sample,"getAlpha") as IntArray,field(sample,"getUnderlay") as Bitmap)
                            }
                            for(ink in inks) {
                                val resource=resources.getResourceEntryName(ink.id)
                                val width=ink.underlay.width;val height=ink.underlay.height
                                if(ink.alpha.none { it>0 }) { failures+="$label:$resource=empty native ink";continue }
                                val gateMask=call("dilatedNativeInk",ink.alpha,width,height,1) as BooleanArray
                                val marginMask=call("dilatedNativeInk",ink.alpha,width,height,margin) as BooleanArray
                                // The underlay still shows the OTHER native texts: their ink is not artwork.
                                for(other in inks)if(other!==ink) {
                                    val foreign=call("dilatedNativeInk",other.alpha,width,height,1) as BooleanArray
                                    for(i in marginMask.indices)if(foreign[i] && !gateMask[i])marginMask[i]=false
                                }
                                @Suppress("UNCHECKED_CAST")
                                val gate=call("nativeMinimum",ink.sample,gateMask,ink.underlay) as Triple<Double,Int,Int>
                                @Suppress("UNCHECKED_CAST")
                                val guarded=call("nativeMinimum",ink.sample,marginMask,ink.underlay) as Triple<Double,Int,Int>
                                observed.put(JSONObject().apply {
                                    put("case",label);put("resource",resource);put("requiredContrast",4.5)
                                    put("gateMinimum",gate.first);put("gateX",gate.second);put("gateY",gate.third)
                                    put("marginNativePx",margin);put("marginMinimum",guarded.first)
                                    put("marginX",guarded.second);put("marginY",guarded.third)
                                    put("nativeInkPixels",ink.alpha.count { it>0 });put("inkColor",field(ink.sample,"getColor") as Int)
                                })
                                if(!gate.first.isFinite() || gate.first<4.5)failures+="$label:$resource gate=${gate.first}@(${gate.second},${gate.third})"
                                else if(!guarded.first.isFinite() || guarded.first<4.5)failures+="$label:$resource margin=${guarded.first}@(${guarded.second},${guarded.third})"
                            }
                        } finally { for(ink in inks)recycle(ink.sample) }
                    }
                }
            }
            assertEquals("RIG: 5 checker variants + 2 variants on 4 other covers, 2 themes, 3 sizes",(5+2*4)*2*3,inflated)
            assertTrue("RIG: native texts were measured",observed.length()>=inflated/2)
        } finally {
            output.mkdirs()
            output.resolve("native-text-measurements.json").writeText(JSONObject().apply {
                put("scope","Native text contrast only (native-ink-v1 coverage, original helpers); alignment and the 23 tests unchanged")
                put("measurements",observed);put("failures",JSONArray(failures))
            }.toString(2),Charsets.UTF_8)
            @Suppress("DEPRECATION") resources.updateConfiguration(saved,resources.displayMetrics)
            for((_,cover) in covers)if(!cover.isRecycled)cover.recycle()
        }
        assertTrue("TEXT_NATIVE_CONTRAST below 4.5: ${failures.joinToString()}",failures.isEmpty())
    }

    /**
     * No hard silhouette and no opaque strip, read on the PRODUCTION bitmap itself.
     *
     * White artwork with one black band behind title and artist: the ink is dark, so the band
     * needs a strong light film exactly under the words. Above a flat cap top the artwork is a
     * uniform black, so the column is the film's own profile. It must hold next to the ink, take
     * at least 4dp to fall from 90% to 10% (a glyph-shaped sticker does it in about 2dp), be gone
     * at 18dp, and never continue sideways as a strip.
     */
    @Test fun filmFallsOffSoftlyAndLeavesTrueAirWhereTheArtworkNeedsIt() {
        val context=call("getContext") as Context
        val cover=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            for(y in 72 until 129)for(x in 0 until width)setPixel(x,y,Color.BLACK)
        }
        val observed=JSONArray()
        val failures=mutableListOf<String>()
        try {
            for(theme in listOf(MediaWidgetTheme.DarkGlass,MediaWidgetTheme.LightGlass)) {
                val snapshot=(call("active",true,cover) as MediaPlaybackSnapshot).copy(title="Marea",artist="Luma")
                val root=call("inflate",snapshot,344,360,theme,null) as View
                val title=root.findViewById<TextView>(R.id.media_widget_title)
                val bitmap=call("background",root) as Bitmap
                val sample=requireNotNull(call("nativeInk",root,title))
                try {
                    val alpha=field(sample,"getAlpha") as IntArray
                    val inkColor=field(sample,"getColor") as Int
                    assertTrue("RIG: dark ink on the mostly white artwork $theme",ColorUtils.calculateLuminance(inkColor)<0.2)
                    val nativeWidth=root.width;val nativeHeight=root.height
                    // Topmost solid ink row is the flat cap top of "M"; take the middle of its first run.
                    val top=(0 until nativeHeight).first { y -> (0 until nativeWidth).any { x -> alpha[y*nativeWidth+x]>=128 } }+1
                    val runStart=(0 until nativeWidth).first { x -> alpha[top*nativeWidth+x]>=128 }
                    var runEnd=runStart
                    while(runEnd+1<nativeWidth && alpha[top*nativeWidth+runEnd+1]>=128)runEnd++
                    assertTrue("RIG: a flat cap top, not a single antialiased pixel",runEnd-runStart>=3)
                    val column=((runStart+runEnd+1)/2f*bitmap.width/nativeWidth).toInt()
                    val inkRow=((top+0.5f)*bitmap.height/nativeHeight).toInt()
                    val pxPerDp=bitmap.height/360f
                    val far=kotlin.math.ceil(22f*pxPerDp).toInt()
                    assertTrue("RIG: the column stays inside the bitmap",inkRow-far>=0)
                    val profile=IntArray(far+1) { k -> Color.red(bitmap.getPixel(column,inkRow-k)) }
                    val rest=profile[far]
                    val near=maxOf(profile[1],profile[2])
                    val lift=near-rest
                    // Without a lift there is no profile to read: recorded below as a RIG failure.
                    val below90=if(lift>0)(1..far).first { profile[it]-rest<0.9f*lift } else 0
                    val below10=if(lift>0)(1..far).first { profile[it]-rest<0.1f*lift } else 0
                    val fallDp=(below10-below90)/pxPerDp
                    val at18=profile[kotlin.math.ceil(18f*pxPerDp).toInt()]
                    val contrastNear=ColorUtils.calculateContrast(inkColor,bitmap.getPixel(column,inkRow-1))
                    // Same band, level with the middle of the title, 40dp past the title's end edge.
                    val box=call("nativeRect",root,title) as Rect
                    val sideX=((box.right+40f*context.resources.displayMetrics.density)*bitmap.width/nativeWidth).toInt()
                    val sideY=(box.centerY().toFloat()*bitmap.height/nativeHeight).toInt()
                    assertTrue("RIG: side probe inside the bitmap",sideX in 0 until bitmap.width && sideY in 0 until bitmap.height)
                    val side=Color.red(bitmap.getPixel(sideX,sideY))
                    observed.put(JSONObject().apply {
                        put("theme",theme.name);put("bitmapColumn",column);put("bitmapInkRow",inkRow);put("bitmapPxPerDp",pxPerDp.toDouble())
                        put("profileRedFromInkUpwards",JSONArray(profile.toList()));put("untouchedRed",rest);put("nearRed",near)
                        put("contrastNextToInk",contrastNear);put("fall90to10Dp",fallDp.toDouble());put("requiredFallDp",4.0)
                        put("redAt18Dp",at18);put("redBesideTitle40Dp",side)
                    })
                    if(rest>40)failures+="$theme RIG: artwork 22dp above the ink is not the untouched band ($rest)"
                    if(lift<60)failures+="$theme RIG: the band did not need a film (lift $lift)"
                    if(contrastNear<4.5)failures+="$theme contrast next to ink $contrastNear"
                    if(lift>=60 && fallDp<4f)failures+="$theme hard edge: 90%->10% in ${fallDp}dp"
                    if(at18-rest>2)failures+="$theme film still present at 18dp (${at18-rest})"
                    if(side>40)failures+="$theme strip beside the title ($side)"
                } finally { recycle(sample) }
            }
        } finally {
            output.mkdirs()
            output.resolve("film-profile.json").writeText(JSONObject().apply {
                put("scope","Production bitmap profile above the native cap top; synthetic band fixture, not a product image")
                put("measurements",observed);put("failures",JSONArray(failures))
            }.toString(2),Charsets.UTF_8)
            if(!cover.isRecycled)cover.recycle()
        }
        assertTrue("TEXT_FIELD_SHAPE: ${failures.joinToString()}",failures.isEmpty())
    }
}
