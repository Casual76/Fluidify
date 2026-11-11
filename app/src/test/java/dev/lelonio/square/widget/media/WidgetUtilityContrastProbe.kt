package dev.lelonio.square.widget.media

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.View
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

/** Independent native glyph coverage, not the production lens stencil or a simulation. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35], application=Application::class, qualifiers="w412dp-h892dp-xhdpi")
class WidgetUtilityContrastProbe {
    private val original=SceneWidgetTest()
    private fun call(name:String,vararg args:Any?):Any? {
        val method=SceneWidgetTest::class.java.declaredMethods.single { it.name==name && it.parameterTypes.size==args.size }.apply { isAccessible=true }
        return try { method.invoke(original,*args) } catch(e:InvocationTargetException) { throw e.targetException }
    }
    @Before fun attach() { original.attachLauncherFixture() }
    @After fun release() { original.releaseLauncherFixture() }

    @Test fun utilityGlyphsKeepNativeContrastAcrossCheckerThemesAndDirections() {
        val planText = System.getProperty("scene.utilityPlan")?.let { File(it).readText(Charsets.UTF_8) }
            ?: requireNotNull(javaClass.getResourceAsStream("/widget-v2/scene-canonical-inputs.json")) {
                "Missing canonical synthetic widget inputs"
            }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val plan=JSONObject(planText).getJSONArray("cases")
        val cases=(0 until plan.length()).map { plan.getJSONObject(it) }.filter { it.getString("fixture")=="checker-synthetic" && it.getInt("widthDp")==344 }
        assertEquals("RIG: four unchanged canonical cases",4,cases.size)
        val context=call("getContext") as Context
        val saved=Configuration(context.resources.configuration)
        @Suppress("UNCHECKED_CAST")
        val covers=call("comparisonCovers") as List<Pair<String,Bitmap>>
        val artwork=covers.single { it.first=="checker-synthetic" }.second
        val observed=JSONArray()
        val failures=mutableListOf<String>()
        try {
            for(input in cases) {
                val config=Configuration(saved).apply {
                    fontScale=input.getDouble("fontScale").toFloat()
                    setLayoutDirection(java.util.Locale(if(input.getInt("layoutDirection")==1)"ar" else "en"))
                }
                @Suppress("DEPRECATION") context.resources.updateConfiguration(config,context.resources.displayMetrics)
                val snapshot=(call("active",true,artwork) as MediaPlaybackSnapshot).copy(title=input.getString("title"),artist=input.getString("artist"),sourceLabel=input.getString("sourceLabel"))
                val theme=MediaWidgetTheme.valueOf(input.getString("theme"))
                val root=call("inflate",snapshot,input.getInt("widthDp"),input.getInt("heightDp"),theme,null) as View
                assertEquals("RIG: actual RTL",input.getInt("layoutDirection"),root.layoutDirection)
                for(id in listOf(R.id.media_widget_refresh,R.id.media_widget_open_media,R.id.media_widget_open_pampa)) {
                    val target=root.findViewById<View>(id)
                    assertTrue("RIG: utility is visible",target.isShown)
                    val sample=requireNotNull(call("nativeInk",root,target))
                    fun field(name:String):Any? = sample.javaClass.getDeclaredMethod(name).apply { isAccessible=true }.invoke(sample)
                    try {
                        val alpha=field("getAlpha") as IntArray
                        val underlay=field("getUnderlay") as Bitmap
                        assertTrue("RIG: nonempty native glyph",alpha.any { it>0 })
                        val mask=call("dilatedNativeInk",alpha,underlay.width,underlay.height,1) as BooleanArray
                        @Suppress("UNCHECKED_CAST")
                        val min=call("nativeMinimum",sample,mask,underlay) as Triple<Double,Int,Int>
                        val resource=context.resources.getResourceEntryName(id)
                        observed.put(JSONObject().apply {
                            put("input",input.getString("path"));put("resource",resource)
                            put("minimumContrast",min.first);put("requiredContrast",3.0)
                            put("x",min.second);put("y",min.third);put("nativeInkPixels",alpha.count { it>0 })
                        })
                        if(!min.first.isFinite() || min.first<3.0)failures+="${input.getString("path")}:$resource=${min.first}"
                    }finally { sample.javaClass.getDeclaredMethod("recycle").apply { isAccessible=true }.invoke(sample) }
                }
            }
            assertEquals("RIG: all three utility glyphs in all four cases",12,observed.length())
        }finally {
            File("build/outputs/roborazzi/widget-v2/utility-contrast-regression").apply { mkdirs() }
                .resolve("native-measurements.json").writeText(JSONObject().apply {
                    put("scope","Native glyph contrast only; original alignment and23 tests unchanged")
                    put("measurements",observed);put("failures",JSONArray(failures))
                }.toString(2),Charsets.UTF_8)
            @Suppress("DEPRECATION") context.resources.updateConfiguration(saved,context.resources.displayMetrics)
            for((_,cover)in covers)if(!cover.isRecycled)cover.recycle()
        }
        assertTrue("UTILITY_NATIVE_CONTRAST below3: ${failures.joinToString()}",failures.isEmpty())
    }
}
