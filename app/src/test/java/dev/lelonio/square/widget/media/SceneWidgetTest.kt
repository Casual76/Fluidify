package dev.lelonio.square.widget.media

import android.app.Application
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import dev.lelonio.square.R
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h892dp-xhdpi")
class SceneWidgetTest {
    private lateinit var activity: ActivityController<Activity>
    private val context: Context get() = activity.get()
    @Before fun attachLauncherFixture() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
    }
    @After fun releaseLauncherFixture() {
        activity.pause().stop().destroy()
    }
    private fun active(playing: Boolean = true, cover: Bitmap? = null) = MediaPlaybackSnapshot(
        MediaPlaybackAvailability.Active, title = "A long title across the night", artist = "Artist",
        packageName = context.packageName, mediaId = "fixture", isPlaying = playing,
        canPlayPause = true, canSkipPrevious = true, canSkipNext = true, artwork = cover,
        positionMs = 61000, durationMs = 182000,
    )
    private fun inflate(snapshot: MediaPlaybackSnapshot, w: Int = 344, h: Int = 360,
                        theme: MediaWidgetTheme = MediaWidgetTheme.DarkGlass, settings: AppSettings? = null): View {
        val views = MediaWidgetUpdater.buildRemoteViewsForTest(context, snapshot,
            settings ?: AppSettings(mediaWidgetTheme = theme), w, h)
        val host = FrameLayout(context).apply {
            // Activity was created before the configuration switch. Give BOTH candidates a
            // genuinely RTL host, rather than inheriting the old window's LTR direction.
            layoutDirection = context.resources.configuration.layoutDirection
        }
        activity.get().setContentView(host)
        return views.apply(context, host).also {
            host.addView(it)
            val density = context.resources.displayMetrics.density
            it.measure(View.MeasureSpec.makeMeasureSpec((w*density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((h*density).toInt(), View.MeasureSpec.EXACTLY))
            it.layout(0, 0, it.measuredWidth, it.measuredHeight)
        }
    }
    /** Draw the actual RemoteViews subtree at launcher dimensions, not the Activity window.
     * This is a native View/Canvas host fixture; OneUI composition remains a device gate. */
    private fun captureWidget(root: View, w: Int, h: Int, path: String) {
        val density = context.resources.displayMetrics.density
        val width = (w * density).toInt()
        val height = (h * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(image))
        File(path).apply { parentFile?.mkdirs() }.outputStream().use {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, bounds)
        assertEquals("Captured widget width", width, bounds.outWidth)
        assertEquals("Captured widget height", height, bounds.outHeight)
        image.recycle()
    }
    @Test fun geometryNeverShrinksTransportTargets() {
        for (w in listOf(110, 150, 184, 220, 250, 344, 460, 800)) {
            for (h in listOf(110, 150, 176, 210, 260, 360, 420)) {
                for (font in listOf(1f, 1.3f, 2f)) {
                    val spec = MediaWidgetLayoutSpec.fromDimensions(w, h, AppSettings(), font)
                    assertTrue(spec.playButtonDp >= 48)
                    assertTrue(spec.sideButtonDp >= 48)
                    if (spec.showSideControls) assertTrue("$w x $h font $font",
                        2*spec.contentPaddingHorizontalDp + 2*spec.sideButtonDp + spec.playButtonDp + 28 <= w)
                    if (spec.showExtraActions) assertTrue(2*spec.contentPaddingHorizontalDp + 3*48+28 <= w)
                }
            }
        }
    }
    @Test fun actionsAndStateAreNativeAndTruthful() {
        for (playing in listOf(true, false)) {
            val root = inflate(active(playing))
            val button = root.findViewById<ImageButton>(R.id.media_widget_play_pause)
            assertEquals(context.getString(if (playing) R.string.widget_v2_pause else R.string.widget_v2_play), button.contentDescription)
            assertTrue(button.isEnabled)
            assertFalse(root.findViewById<View>(R.id.media_widget_play_pause_container).hasOnClickListeners())
            assertEquals(View.GONE, root.findViewById<View>(R.id.media_widget_artwork_frame).visibility)
        }
        val disabled = inflate(active().copy(canPlayPause = false, canSkipNext = false, canSkipPrevious = false))
        for (id in listOf(R.id.media_widget_play_pause, R.id.media_widget_previous, R.id.media_widget_next)) {
            assertFalse(disabled.findViewById<View>(id).isEnabled)
        }
        val buffering = inflate(active().copy(isBuffering = true))
        assertEquals(context.getString(R.string.widget_v2_loading), buffering.findViewById<TextView>(R.id.media_widget_status).text.toString())
        val unknown = inflate(active().copy(positionKnown = false))
        assertEquals(View.GONE, unknown.findViewById<View>(R.id.media_widget_time).visibility)
        val empty = inflate(MediaPlaybackSnapshot(MediaPlaybackAvailability.NoSession))
        assertFalse(empty.findViewById<View>(R.id.media_widget_play_pause).isEnabled)
        assertEquals(context.getString(R.string.widget_v2_empty), empty.findViewById<TextView>(R.id.media_widget_title).text.toString())
        assertEquals(View.GONE, empty.findViewById<View>(R.id.media_widget_time).visibility)
    }
    @Test fun staleArtworkCannotCommitAfterTrackOrResize() {
        val epoch = WidgetArtworkEpoch()
        val oldTrack = epoch.next()
        val currentTrack = epoch.next()
        assertFalse(epoch.isCurrent(oldTrack))
        assertTrue(epoch.isCurrent(currentTrack))
        val resized = epoch.next()
        assertFalse(epoch.isCurrent(currentTrack))
        assertTrue(epoch.isCurrent(resized))
    }
    @Test fun bitmapSizingBoundsPayloadAndKeepsAspect() {
        for (density in listOf(1f, 2f, 4f)) {
            val size = widgetBackgroundBitmapSize(800, 400, density)
            assertTrue(size.width <= 384 && size.height <= 384)
            assertEquals(2f, size.width.toFloat()/size.height, 0.02f)
            assertTrue(estimatedRemoteViewsBitmapBytes(size)*4 < 2400000L)
        }
    }
    @Test fun readingRegionResistsWhiteBlackAndNoiseAndResize() {
        for ((name, cover) in comparisonCovers()) {
            for (theme in listOf(MediaWidgetTheme.DarkGlass, MediaWidgetTheme.LightGlass)) {
                for ((w, h) in listOf(220 to 176, 344 to 360, 500 to 150)) {
                    val root = inflate(active(cover = cover), w, h, theme)
                    assertNativeTextContrast(root, "$name $theme ${w}x$h")
                    val bitmap = background(root)
                    assertTrue(bitmap.width <= 384 && bitmap.height <= 384)
                    assertEquals(w.toFloat() / h, bitmap.width.toFloat() / bitmap.height, 0.03f)
                }
            }
        }
    }

    private fun background(root: View): Bitmap =
        (root.findViewById<ImageView>(R.id.media_widget_background).drawable as android.graphics.drawable.BitmapDrawable).bitmap

    // Contract native-ink-v1. This code NEVER reads a production stencil, text metric,
    // distance field, lens geometry or contrast solver. Coverage comes from View.draw.
    private data class NativeInk(val visible: Bitmap, val underlay: Bitmap, val alpha: IntArray, val color: Int,
        val observed: ObservedGlyphAlignment.Proof? = null) {
        fun recycle() { visible.recycle(); underlay.recycle() }
    }
    private val textIds = listOf(R.id.media_widget_title, R.id.media_widget_artist, R.id.media_widget_status,
        R.id.media_widget_time, R.id.media_widget_permission, R.id.media_widget_source)
    private val glyphIds = listOf(R.id.media_widget_previous, R.id.media_widget_next,
        R.id.media_widget_play_glyph, R.id.media_widget_pause_glyph, R.id.media_widget_refresh,
        R.id.media_widget_open_media, R.id.media_widget_open_pampa, R.id.media_widget_command_pending)

    private fun nativeFrame(root: View): Bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also {
        root.draw(android.graphics.Canvas(it))
    }

    private fun nativeInkColor(view: View): Int = when (view) {
        is TextView -> view.currentTextColor
        is ImageView -> requireNotNull(view.imageTintList).getColorForState(view.drawableState, view.imageTintList!!.defaultColor)
        is android.widget.ProgressBar -> requireNotNull(view.indeterminateTintList).defaultColor
        else -> error("Unsupported native ink ${view.javaClass}")
    }

    private fun <T> hideOnlyInk(view: View, block: () -> T): T = when (view) {
        is TextView -> {
            val saved = view.textColors
            try { view.setTextColor(Color.TRANSPARENT); block() } finally { view.setTextColor(saved) }
        }
        is ImageView -> {
            val saved = view.imageTintList
            try { view.imageTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT); block() }
            finally { view.imageTintList = saved }
        }
        is android.widget.ProgressBar -> {
            val saved = view.indeterminateTintList
            try { view.indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT); block() }
            finally { view.indeterminateTintList = saved }
        }
        else -> error("Unsupported ink view")
    }

    private fun nativeInk(root: View, target: View): NativeInk {
        val visible = nativeFrame(root)
        val underlay = hideOnlyInk(target) { nativeFrame(root) }
        val nodes = mutableListOf<View>()
        fun visit(view: View) { nodes += view; if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i)) }
        visit(root)
        val ancestors = mutableSetOf<View>()
        var ancestor: View? = target
        while (ancestor != null) { ancestors += ancestor; if (ancestor === root) break; ancestor = ancestor.parent as? View }
        data class State(val view: View, val alpha: Float, val background: android.graphics.drawable.Drawable?, val foreground: android.graphics.drawable.Drawable?)
        val saved = nodes.map { State(it,it.alpha,it.background,it.foreground) }
        val text = target as? TextView
        val shadow = text?.let { floatArrayOf(it.shadowRadius,it.shadowDx,it.shadowDy) }
        val shadowColor = text?.shadowColor
        val highlight = text?.highlightColor
        var observedCapture: ObservedGlyphAlignment.Capture? = null
        val isolated = try {
            // Keep the actual hierarchy, layout, text, fonts, bidi, ellipsis, matrices and
            // ancestor clipping. Alpha hides siblings WITHOUT a visibility/layout change.
            for (node in nodes) if (node in ancestors) { node.background = null; node.foreground = null }
                else node.alpha = 0f
            text?.setShadowLayer(0f,0f,0f,Color.TRANSPARENT)
            text?.highlightColor = Color.TRANSPARENT
            val originalFrame = nativeFrame(root)
            try {
                // Observe a second draw of the SAME isolated hierarchy. Reconstruction must
                // match the unchanged originalFrame exactly before it may replace any expected.
                if (text != null) observedCapture = ObservedGlyphAlignment.Capture.draw(root)
                originalFrame
            } catch (t: Throwable) { originalFrame.recycle(); throw t }
        } catch (t: Throwable) {
            visible.recycle(); underlay.recycle(); throw t
        } finally {
            for (state in saved) { state.view.background = state.background; state.view.foreground = state.foreground; state.view.alpha = state.alpha }
            if (text != null) { text.setShadowLayer(shadow!![0],shadow[1],shadow[2],shadowColor!!); text.highlightColor = highlight!! }
        }
        try {
            val pixels = IntArray(root.width*root.height)
            isolated.getPixels(pixels,0,root.width,0,0,root.width,root.height)
            val color = nativeInkColor(target)
            // Reconstruction receives isolated ink and underlay, NEVER visible target RGB.
            val observed = observedCapture?.reconstruct(isolated,underlay,color)
            return NativeInk(visible,underlay,IntArray(pixels.size) { Color.alpha(pixels[it]) },color,observed)
        } catch (t: Throwable) {
            visible.recycle(); underlay.recycle(); throw t
        } finally { isolated.recycle() }
    }

    /** Quantization tolerance is per CHANNEL, never a spatial tolerance or crop correction. */
    private fun assertNativeAlignment(sample: NativeInk, label: String, coverage: IntArray = sample.alpha) {
        assertEquals(sample.underlay.width*sample.underlay.height, coverage.size)
        assertTrue("Nonempty native ink $label", coverage.any { it > 0 })
        assertEquals("Opaque ink $label",255,Color.alpha(sample.color))
        for (y in 0 until sample.underlay.height) for (x in 0 until sample.underlay.width) {
            val i=y*sample.underlay.width+x
            val underlay=sample.underlay.getPixel(x,y)
            val ordinaryExpected=androidx.core.graphics.ColorUtils.compositeColors(
                androidx.core.graphics.ColorUtils.setAlphaComponent(sample.color,coverage[i]),underlay)
            val expected=sample.observed?.expected(i,coverage[i],sample.color,underlay,ordinaryExpected,label)
                ?: ordinaryExpected
            val actual=sample.visible.getPixel(x,y)
            val error=maxOf(kotlin.math.abs(Color.red(expected)-Color.red(actual)),
                kotlin.math.abs(Color.green(expected)-Color.green(actual)),kotlin.math.abs(Color.blue(expected)-Color.blue(actual)))
            assertTrue("Native coverage alignment $label at ($x,$y): $error",error<=2)
        }
    }

    private fun dilatedNativeInk(alpha: IntArray,w: Int,h: Int,radius: Int=1): BooleanArray {
        val mask=BooleanArray(alpha.size)
        for(y in 0 until h)for(x in 0 until w)if(alpha[y*w+x]>0) {
            for(dy in -radius..radius)for(dx in -radius..radius) {
                val xx=x+dx;val yy=y+dy
                if(xx in 0 until w && yy in 0 until h) mask[yy*w+xx]=true
            }
        }
        return mask
    }

    private fun nativeMinimum(sample: NativeInk, coverage: BooleanArray, background: Bitmap=sample.underlay): Triple<Double,Int,Int> {
        var minimum=Double.POSITIVE_INFINITY;var minX=-1;var minY=-1
        for(i in coverage.indices)if(coverage[i]) {
            val x=i%background.width;val y=i/background.width
            val contrast=androidx.core.graphics.ColorUtils.calculateContrast(sample.color,background.getPixel(x,y))
            if(contrast<minimum){minimum=contrast;minX=x;minY=y}
        }
        return Triple(minimum,minX,minY)
    }

    private fun requireNativeContrast(sample: NativeInk,threshold: Double,label: String,background: Bitmap=sample.underlay): Triple<Double,Int,Int> {
        val minimum=nativeMinimum(sample,dilatedNativeInk(sample.alpha,background.width,background.height),background)
        assertTrue("Native ink contrast $label: ${minimum.first} at (${minimum.second},${minimum.third})",
            minimum.first.isFinite() && minimum.first>=threshold)
        return minimum
    }

    private fun assertTextGeometry(root: View,text: TextView,label: String): Rect {
        val rect=nativeRect(root,text)
        assertTrue("Native text bounds $label: $rect",rect.left>=0 && rect.top>=0 && rect.right<=root.width && rect.bottom<=root.height && rect.width()>0 && rect.height()>0)
        assertTrue("Native text height $label",text.layout.height+text.compoundPaddingTop+text.compoundPaddingBottom<=text.height)
        var parent=text.parent as? ViewGroup
        while(parent!=null) {
            if(parent.clipChildren) {
                val clip=if(parent===root)Rect(0,0,root.width,root.height)else nativeRect(root,parent)
                if(parent.clipToPadding) { clip.left+=parent.paddingLeft;clip.top+=parent.paddingTop;clip.right-=parent.paddingRight;clip.bottom-=parent.paddingBottom }
                assertTrue("Native ancestor clipping $label: $rect / $clip",clip.contains(rect))
            }
            if(parent===root)break
            parent=parent.parent as? ViewGroup
        }
        return rect
    }

    private fun assertNativeTextContrast(root: View,label: String,diagnosticFile: File?=null) {
        val diagnostics=org.json.JSONArray()
        for(id in textIds) {
            val text=root.findViewById<TextView>(id)
            if(!text.isShown || text.text.isBlank())continue
            val rect=assertTextGeometry(root,text,label)
            val bitmap=background(root)
            // Keep the V3 rectangle/bitmap minimum ONLY as explicitly labelled diagnostic.
            val left=(rect.left.toLong()*bitmap.width/root.width).toInt()
            val top=(rect.top.toLong()*bitmap.height/root.height).toInt()
            val right=((rect.right.toLong()*bitmap.width+root.width-1)/root.width).toInt()
            val bottom=((rect.bottom.toLong()*bitmap.height+root.height-1)/root.height).toInt()
            var rectangle=Double.POSITIVE_INFINITY
            for(y in top until bottom.coerceAtMost(bitmap.height))for(x in left until right.coerceAtMost(bitmap.width))
                rectangle=minOf(rectangle,androidx.core.graphics.ColorUtils.calculateContrast(text.currentTextColor,bitmap.getPixel(x,y)))
            val sample=nativeInk(root,text)
            try {
                assertNativeAlignment(sample,"$label $id")
                val minimum=nativeMinimum(sample,dilatedNativeInk(sample.alpha,root.width,root.height))
                diagnostics.put(org.json.JSONObject().apply {
                    put("contract","native-ink-v1; full opaque ink vs final native underlay; all alpha>0 +1 native pixel")
                    put("resource",context.resources.getResourceEntryName(id));put("minimumContrast",minimum.first)
                    put("requiredContrast",4.5);put("nativeX",minimum.second);put("nativeY",minimum.third)
                    put("legacyBitmapRectangleMinimumDiagnosticOnly",rectangle)
                    put("nativeLeft",rect.left);put("nativeTop",rect.top);put("nativeRight",rect.right);put("nativeBottom",rect.bottom)
                    put("nativeWidth",root.width);put("nativeHeight",root.height)
                    put("textColor",sample.color);put("text",text.text.toString());put("lineCount",text.layout.lineCount)
                    put("ellipsizedCount",text.layout.getEllipsisCount(text.layout.lineCount-1));put("coveragePixels",sample.alpha.count{it>0})
                })
                diagnosticFile?.let { it.parentFile?.mkdirs();it.writeText(diagnostics.toString(2),Charsets.UTF_8) }
                requireNativeContrast(sample,4.5,"$label $id")
            } finally { sample.recycle() }
        }
    }

    private fun assertNativeTargets(root: View,label: String) {
        val density=context.resources.displayMetrics.density
        assertEquals("Actual root direction $label",context.resources.configuration.layoutDirection,root.layoutDirection)
        for(id in listOf(R.id.media_widget_play_pause,R.id.media_widget_previous,R.id.media_widget_next,
            R.id.media_widget_refresh,R.id.media_widget_open_media,R.id.media_widget_open_pampa)) {
            val button=root.findViewById<ImageButton>(id)
            if(!button.isShown)continue
            assertTrue("Native target $label $id",button.width>=48*density && button.height>=48*density)
            assertFalse("Accessible action $label $id",button.contentDescription.isNullOrBlank())
            val rect=nativeRect(root,button)
            assertTrue("Native target bounds $label $id: $rect",rect.left>=0 && rect.top>=0 && rect.right<=root.width && rect.bottom<=root.height)
        }
        val iconDiagnostics=org.json.JSONArray()
        for(id in glyphIds) {
            val glyph=root.findViewById<View>(id)
            if(!glyph.isShown)continue
            val sample=nativeInk(root,glyph)
            try {
                assertNativeAlignment(sample,"$label $id")
                val minimum=nativeMinimum(sample,dilatedNativeInk(sample.alpha,root.width,root.height))
                val rect=nativeRect(root,glyph).apply { left+=glyph.paddingLeft;top+=glyph.paddingTop;right-=glyph.paddingRight;bottom-=glyph.paddingBottom }
                var rectangle=Double.POSITIVE_INFINITY
                for(y in rect.top until rect.bottom)for(x in rect.left until rect.right)
                    rectangle=minOf(rectangle,androidx.core.graphics.ColorUtils.calculateContrast(sample.color,sample.underlay.getPixel(x,y)))
                iconDiagnostics.put(org.json.JSONObject().apply {
                    put("resource",context.resources.getResourceEntryName(id));put("minimumContrast",minimum.first);put("requiredContrast",3.0)
                    put("nativeX",minimum.second);put("nativeY",minimum.third);put("legacyPaddedRectangleMinimumDiagnosticOnly",rectangle)
                    put("coveragePixels",sample.alpha.count{it>0})
                })
                requireNativeContrast(sample,3.0,"$label $id")
            } finally { sample.recycle() }
        }
        // Matrix labels are safe file basenames; other regression labels stay in memory.
        if(label.endsWith(".png")) File("build/outputs/roborazzi/widget-v2/artwork-comparison/diagnostics/icons-${label.removeSuffix(".png")}.json").apply {
            parentFile?.mkdirs();writeText(iconDiagnostics.toString(2),Charsets.UTF_8)
        }
    }

    private fun nativeTexture(bitmap: Bitmap,coverage: BooleanArray): Double {
        var total=0L;var count=0
        for(y in 0 until bitmap.height)for(x in 0 until bitmap.width-1) {
            val i=y*bitmap.width+x
            if(!coverage[i] || !coverage[i+1])continue
            val a=bitmap.getPixel(x,y);val b=bitmap.getPixel(x+1,y)
            total+=kotlin.math.abs(Color.red(a)-Color.red(b))+kotlin.math.abs(Color.green(a)-Color.green(b))+kotlin.math.abs(Color.blue(a)-Color.blue(b));count++
        }
        assertTrue("Nonempty texture support",count>0)
        return total.toDouble()/count
    }

    private fun gateControlRecord(label: String,outcome: String,message: String) {
        val path=File("build/outputs/roborazzi/widget-v2/native-gate-controls/${label.replace(Regex("[^A-Za-z0-9]+"),"-")}.json")
        path.parentFile?.mkdirs()
        path.writeText(org.json.JSONObject().apply {
            put("contract","native-ink-v1");put("control",label);put("outcome",outcome);put("message",message)
            put("syntheticDiagnosticOnly",true);put("notAProductComparisonPng",true)
        }.toString(2),Charsets.UTF_8)
    }

    private fun expectGateRejection(label: String,expectedMessage: String="Native ink contrast",block: () -> Unit) {
        var rejection: AssertionError?=null
        try { block() } catch(expected: AssertionError) { rejection=expected }
        assertNotNull("Negative control MUST be rejected: $label",rejection)
        assertTrue("Correct gate rejected $label: ${rejection!!.message}",rejection.message.orEmpty().contains(expectedMessage))
        gateControlRecord(label,"rejected-as-required",rejection.message.orEmpty())
    }

    private fun isolatedTextFixture(value: String,rtl: Boolean=false): Pair<View,TextView> {
        val root=FrameLayout(context).apply { setBackgroundColor(Color.BLACK);layoutDirection=if(rtl)View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR }
        val text=TextView(context).apply {
            text=value;setTextColor(Color.WHITE);textSize=38f;includeFontPadding=true;maxLines=1
            ellipsize=android.text.TextUtils.TruncateAt.END;gravity=android.view.Gravity.TOP or android.view.Gravity.START
            layoutDirection=root.layoutDirection
        }
        root.addView(text,FrameLayout.LayoutParams(300,110).apply { leftMargin=12;topMargin=8 })
        activity.get().setContentView(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(330,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(140,View.MeasureSpec.EXACTLY))
        root.layout(0,0,330,140)
        return root to text
    }

    private fun rejectAdversarialPixel(sample: NativeInk,index: Int,label: String) {
        assertTrue("Selected pixel is in actual native ink $label",sample.alpha[index]>0)
        val adverse=sample.underlay.copy(Bitmap.Config.ARGB_8888,true)
        val prefix=File("build/outputs/roborazzi/widget-v2/native-gate-controls/${label.replace(Regex("[^A-Za-z0-9]+"),"-")}")
        prefix.parentFile?.mkdirs()
        File(prefix.path+"-native-positive.png").outputStream().use { assertTrue(sample.visible.compress(Bitmap.CompressFormat.PNG,100,it)) }
        try { adverse.setPixel(index%adverse.width,index/adverse.width,sample.color)
            File(prefix.path+"-synthetic-adverse-underlay.png").outputStream().use { assertTrue(adverse.compress(Bitmap.CompressFormat.PNG,100,it)) }
            expectGateRejection(label) { requireNativeContrast(sample,4.5,label,adverse) }
        } finally { adverse.recycle() }
    }

    @Test fun nativeGateRejectsLatinAccentAndEllipsisAdversary() {
        val(root,text)=isolatedTextFixture("É")
        val accent=nativeInk(root,text)
        val accentBounds=nativeRect(root,text);val accentBaseline=text.baseline
        text.text="E"
        // Re-layout the SAME native parent: do not assume an LTR child position in RTL.
        root.measure(View.MeasureSpec.makeMeasureSpec(330,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(140,View.MeasureSpec.EXACTLY))
        root.layout(0,0,330,140)
        assertEquals("Paired Latin fixture bounds",accentBounds,nativeRect(root,text))
        assertEquals("Paired Latin fixture baseline",accentBaseline,text.baseline)
        val base=nativeInk(root,text)
        try {
            assertNativeAlignment(accent,"Latin accent")
            val point=accent.alpha.indices.firstOrNull { accent.alpha[it]>0 && base.alpha[it]==0 }
            assertNotNull("Native accent has additional ink",point)
            rejectAdversarialPixel(accent,point!!,"Latin diacritic pixel")
        } finally { accent.recycle();base.recycle() }
        val(eroot,ellipsis)=isolatedTextFixture("Élévation très longue, déjà au-delà de la ville et de la lumière")
        val sample=nativeInk(eroot,ellipsis)
        try {
            assertTrue("Actual native ellipsis",ellipsis.layout.getEllipsisCount(0)>0)
            val start=ellipsis.layout.getEllipsisStart(0)
            val a=ellipsis.layout.getPrimaryHorizontal(start);val b=ellipsis.layout.getPrimaryHorizontal(start+ellipsis.layout.getEllipsisCount(0))
            val rect=nativeRect(eroot,ellipsis)
            val lo=(rect.left+minOf(a,b)).toInt();val hi=(rect.left+maxOf(a,b)).toInt()
            val point=sample.alpha.indices.firstOrNull { sample.alpha[it]>0 && it%eroot.width in lo..hi }
            assertNotNull("Ellipsis coverage is nonempty",point)
            rejectAdversarialPixel(sample,point!!,"last-line ellipsis pixel")
        } finally { sample.recycle() }
    }

    @Test fun nativeGateRejectsArabicDiacriticAdversary() {
        val(root,text)=isolatedTextFixture("بِ",true)
        val marked=nativeInk(root,text)
        val markedBounds=nativeRect(root,text);val markedBaseline=text.baseline
        text.text="ب"
        // Re-layout the SAME native parent: do not assume an LTR child position in RTL.
        root.measure(View.MeasureSpec.makeMeasureSpec(330,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(140,View.MeasureSpec.EXACTLY))
        root.layout(0,0,330,140)
        assertEquals("Paired Arabic fixture bounds",markedBounds,nativeRect(root,text))
        assertEquals("Paired Arabic fixture baseline",markedBaseline,text.baseline)
        val base=nativeInk(root,text)
        try {
            assertNativeAlignment(marked,"Arabic kasra")
            val point=marked.alpha.indices.firstOrNull { marked.alpha[it]>0 && base.alpha[it]==0 && it/root.width>text.baseline+8 }
            assertNotNull("Native Arabic diacritic is independently visible below baseline",point)
            rejectAdversarialPixel(marked,point!!,"Arabic diacritic pixel")
        } finally { marked.recycle();base.recycle() }
    }

    @Test fun nativeGateRejectsOnePixelMisalignment() {
        val(root,text)=isolatedTextFixture("ÉM…")
        val sample=nativeInk(root,text)
        try {
            assertNativeAlignment(sample,"aligned control")
            val shifted=IntArray(sample.alpha.size)
            for(y in 0 until root.height)for(x in 1 until root.width)shifted[y*root.width+x]=sample.alpha[y*root.width+x-1]
            expectGateRejection("one native pixel mask shift","Native coverage alignment") { assertNativeAlignment(sample,"shifted",shifted) }
        } finally { sample.recycle() }
    }

    @Test fun nativeGateDistinguishesBlankSpaceFromInk() {
        val(root,text)=isolatedTextFixture("M       M")
        val sample=nativeInk(root,text)
        try {
            val protected=dilatedNativeInk(sample.alpha,root.width,root.height)
            val rect=nativeRect(root,text)
            val blank=sample.alpha.indices.first { !protected[it] && it%root.width in rect.left until rect.right && it/root.width in rect.top until rect.bottom }
            val adverse=sample.underlay.copy(Bitmap.Config.ARGB_8888,true)
            try {
                adverse.setPixel(blank%root.width,blank/root.width,sample.color)
                requireNativeContrast(sample,4.5,"true empty pixel may pass",adverse)
                gateControlRecord("true empty pixel","accepted-as-required","Native ink >=4.5; rectangle pixel contrast=1.0")
                assertEquals("Same rectangle pixel has contrast 1",1.0,androidx.core.graphics.ColorUtils.calculateContrast(sample.color,adverse.getPixel(blank%root.width,blank/root.width)),0.0)
            } finally { adverse.recycle() }
        } finally { sample.recycle() }
    }

    @Test fun nativeGateRejectsUnprotectedChecker() {
        val(root,text)=isolatedTextFixture("Marea Marea")
        val sample=nativeInk(root,text)
        val raw=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
        try {
            for(y in 0 until raw.height)for(x in 0 until raw.width)raw.setPixel(x,y,if((x/8+y/8)%2==0)Color.WHITE else Color.BLACK)
            expectGateRejection("unprotected checker, fixed white ink") { requireNativeContrast(sample,4.5,"raw checker",raw) }
        } finally { sample.recycle();raw.recycle() }
    }

    @Test fun nativeGateRejectsPostCompositeIconReflection() {
        val root=inflate(active(cover=sceneCover()))
        val icon=root.findViewById<View>(R.id.media_widget_previous)
        val sample=nativeInk(root,icon)
        try {
            assertNativeAlignment(sample,"native icon")
            requireNativeContrast(sample,3.0,"unmodified icon")
            val pixel=sample.alpha.indices.first { sample.alpha[it]>0 }
            val reflection=sample.underlay.copy(Bitmap.Config.ARGB_8888,true)
            try {
                // An adverse reflection is injected into the FINAL diagnostic underlay,
                // not before the film, and never into a product comparison PNG.
                reflection.setPixel(pixel%root.width,pixel/root.width,sample.color)
                expectGateRejection("final icon reflection") { requireNativeContrast(sample,3.0,"adverse reflection",reflection) }
            } finally { reflection.recycle() }
        } finally { sample.recycle() }
    }

    @Test fun nativeGateRejectsEmptyOrTruncatedCoverage() {
        val(root,text)=isolatedTextFixture("É…")
        val sample=nativeInk(root,text)
        try {
            expectGateRejection("empty mask","Nonempty native ink") { assertNativeAlignment(sample,"empty",IntArray(sample.alpha.size)) }
            val clipped=sample.alpha.copyOf()
            val top=clipped.indices.filter { clipped[it]>0 }.minOf { it/root.width }
            for(i in clipped.indices)if(i/root.width<=top+2)clipped[i]=0
            expectGateRejection("truncated accent coverage","Native coverage alignment") { assertNativeAlignment(sample,"clipped",clipped) }
        } finally { sample.recycle() }
    }

    private fun alignmentError(a: Int,b: Int): Int = maxOf(
        kotlin.math.abs(Color.red(a)-Color.red(b)),kotlin.math.abs(Color.green(a)-Color.green(b)),
        kotlin.math.abs(Color.blue(a)-Color.blue(b)))

    private fun ordinaryAlignmentExpected(sample: NativeInk,index: Int): Int =
        androidx.core.graphics.ColorUtils.compositeColors(
            androidx.core.graphics.ColorUtils.setAlphaComponent(sample.color,sample.alpha[index]),
            sample.underlay.getPixel(index%sample.underlay.width,index/sample.underlay.width))

    private fun withObservedWhiteTitle(block: (NativeInk) -> Unit) {
        val resources=context.resources
        val original=android.content.res.Configuration(resources.configuration)
        val cover=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        try {
            val config=android.content.res.Configuration(original).apply {
                fontScale=2f;setLayoutDirection(java.util.Locale("ar"))
            }
            @Suppress("DEPRECATION")
            resources.updateConfiguration(config,resources.displayMetrics)
            val root=inflate(active(cover=cover).copy(title="\u0631\u062d\u0644\u0629 \u0637\u0648\u064a\u0644\u0629 \u0639\u0628\u0631 \u0627\u0644\u0644\u064a\u0644 \u0648\u0645\u0648\u0633\u064a\u0642\u0649 \u0628\u0644\u0627 \u0646\u0647\u0627\u064a\u0629",
                artist="Luma \u00b7 Artist with a long name",sourceLabel="Fluidify"),344,360,MediaWidgetTheme.DarkGlass)
            val sample=nativeInk(root,root.findViewById<TextView>(R.id.media_widget_title))
            try { assertNotNull("Real contextual runs must be observed",sample.observed);block(sample) }
            finally { sample.recycle() }
        } finally {
            @Suppress("DEPRECATION")
            resources.updateConfiguration(original,resources.displayMetrics)
            cover.recycle()
        }
    }

    @Test fun observedGlyphModelReconstructsOriginalWhiteRtlOverlap() = withObservedWhiteTitle { sample ->
        val width=sample.visible.width
        val originalFailures=sample.alpha.indices.filter { i ->
            alignmentError(ordinaryAlignmentExpected(sample,i),sample.visible.getPixel(i%width,i/width))>2
        }
        assertEquals("Pinned white RTL fixture reproduces three original errors",3,originalFailures.size)
        for(i in originalFailures) assertTrue("Every original error has measured glyph overlap",sample.observed!!.overlaps(i))
        assertNativeAlignment(sample,"observed whole-run white RTL")
    }

    @Test fun observedGlyphModelRejectsCoverageAndRgbCorruption() = withObservedWhiteTitle { sample ->
        val width=sample.visible.width;val height=sample.visible.height;val proof=sample.observed!!
        assertNativeAlignment(sample,"observed positive")
        val shifted=IntArray(sample.alpha.size)
        for(y in 0 until height)for(x in 1 until width)shifted[y*width+x]=sample.alpha[y*width+x-1]
        expectGateRejection("observed one pixel shift","Native coverage alignment") {
            assertNativeAlignment(sample,"observed shifted",shifted)
        }
        val clipped=sample.alpha.copyOf()
        val top=clipped.indices.filter { clipped[it]>0 }.minOf { it/width }
        for(i in clipped.indices)if(i/width<=top+2)clipped[i]=0
        expectGateRejection("observed truncated coverage","Native coverage alignment") {
            assertNativeAlignment(sample,"observed clipped",clipped)
        }
        // Exercise ALL wrong alpha values at real overlap and nonoverlap pixels. No numeric
        // decomposition can absorb a local mask change once independent coverage is bound.
        for(overlap in listOf(false,true)) {
            val i=sample.alpha.indices.first { sample.alpha[it] in 1..254 && proof.overlaps(it)==overlap }
            val underlay=sample.underlay.getPixel(i%width,i/width)
            val ordinary=ordinaryAlignmentExpected(sample,i)
            for(alpha in 0..255)if(alpha!=sample.alpha[i]) {
                var rejected=false
                try { proof.expected(i,alpha,sample.color,underlay,ordinary,"local alpha adversary") }
                catch(e: AssertionError) {
                    assertTrue(e.message.orEmpty().contains("observed alpha"));rejected=true
                }
                assertTrue("Reject alpha $alpha on overlap=$overlap",rejected)
            }
            val expected=proof.expected(i,sample.alpha[i],sample.color,underlay,ordinary,"RGB adversary")
            val r=Color.red(expected);val corruptRed=if(r<=252)r+3 else r-3
            val corrupt=sample.visible.copy(Bitmap.Config.ARGB_8888,true)
            try {
                corrupt.setPixel(i%width,i/width,Color.rgb(corruptRed,Color.green(expected),Color.blue(expected)))
                expectGateRejection("observed RGB corruption overlap $overlap","Native coverage alignment") {
                    assertNativeAlignment(sample.copy(visible=corrupt),"observed RGB adversary")
                }
            } finally { corrupt.recycle() }
        }
        // Six actual local neighbour substitutions, selected by data, never acceptance coordinates.
        val local=sample.alpha.indices.filter { it+1<sample.alpha.size && it%width<width-1 &&
            sample.alpha[it]>0 && sample.alpha[it]!=sample.alpha[it+1] }.take(6)
        assertEquals("Six nontrivial local coverage controls",6,local.size)
        for((n,i) in local.withIndex()) {
            val mutated=sample.alpha.copyOf().also { it[i]=it[i+1] }
            expectGateRejection("observed neighbour mask $n","Native coverage alignment") {
                assertNativeAlignment(sample,"observed neighbour mask $n",mutated)
            }
        }
    }

    @Test fun nativeGateRetainsIndependentRgbAndMaskCounterexamples() {
        // Archived V3 examples from widget-alignment-check/independent-evidence.json.
        // These are explicit 1-pixel mathematical controls of the unchanged ordinary branch,
        // NOT native captures or fabricated evidence that V3 had no glyph overlap.
        val rgb=listOf(
            intArrayOf(47,119,135,142,100,113,118,97,111,117),
            intArrayOf(69,119,135,142,90,102,108,89,100,107),
            intArrayOf(60,120,135,142,95,107,112,93,105,111),
            intArrayOf(43,119,135,142,102,115,121,100,112,120),
            intArrayOf(240,119,135,142,23,24,26,22,23,24))
        val mask=listOf(
            intArrayOf(94,119,135,141,81,91,96,89),
            intArrayOf(86,118,135,141,84,95,99,82),
            intArrayOf(86,122,135,141,87,95,99,82),
            intArrayOf(78,118,135,141,87,98,104,74),
            intArrayOf(78,121,135,141,89,98,104,74))
        fun fixture(v: IntArray): NativeInk {
            val visible=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888).apply { setPixel(0,0,Color.rgb(v[4],v[5],v[6])) }
            val underlay=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888).apply { setPixel(0,0,Color.rgb(v[1],v[2],v[3])) }
            return NativeInk(visible,underlay,intArrayOf(v[0]),Color.rgb(18,18,20))
        }
        for((n,v) in rgb.withIndex()) {
            val sample=fixture(v)
            try {
                assertNativeAlignment(sample,"archived RGB baseline $n")
                sample.visible.setPixel(0,0,Color.rgb(v[7],v[8],v[9]))
                expectGateRejection("archived independent RGB $n","Native coverage alignment") {
                    assertNativeAlignment(sample,"archived RGB mutation $n")
                }
            } finally { sample.recycle() }
        }
        for((n,v) in mask.withIndex()) {
            val sample=fixture(v)
            try {
                assertNativeAlignment(sample,"archived mask baseline $n")
                expectGateRejection("archived independent mask $n","Native coverage alignment") {
                    assertNativeAlignment(sample,"archived mask mutation $n",intArrayOf(v[7]))
                }
            } finally { sample.recycle() }
        }
    }

    private fun nativeRect(root: View, view: View): Rect = Rect().also {
        view.getDrawingRect(it)
        (root as ViewGroup).offsetDescendantRectToMyCoords(view, it)
    }

    /** Adjacent-pixel variation in the ACTUAL production background under a native rectangle. */
    private fun texture(root: View, rect: Rect): Double {
        val bitmap = background(root)
        val left = (rect.left.toLong() * bitmap.width / root.width).toInt().coerceIn(0, bitmap.width - 2)
        val right = (rect.right.toLong() * bitmap.width / root.width).toInt().coerceIn(left + 2, bitmap.width)
        val top = (rect.top.toLong() * bitmap.height / root.height).toInt().coerceIn(0, bitmap.height - 1)
        val bottom = (rect.bottom.toLong() * bitmap.height / root.height).toInt().coerceIn(top + 1, bitmap.height)
        var sum = 0L
        var count = 0
        for (y in top until bottom) for (x in left until right - 1) {
            val a = bitmap.getPixel(x, y)
            val b = bitmap.getPixel(x + 1, y)
            sum += kotlin.math.abs(Color.red(a) - Color.red(b)) +
                kotlin.math.abs(Color.green(a) - Color.green(b)) + kotlin.math.abs(Color.blue(a) - Color.blue(b))
            count++
        }
        return sum.toDouble() / count
    }

    @Test fun localFrostRemovesCheckerEdgesBehindTextButKeepsHeroTexture() {
        val resources = context.resources
        val original = android.content.res.Configuration(resources.configuration)
        val cover = comparisonCovers().first { it.first == "checker-synthetic" }.second
        try {
            for (font in listOf(1f, 2f)) {
                val configuration = android.content.res.Configuration(original).apply {
                    fontScale = font
                    setLayoutDirection(java.util.Locale(if (font == 2f) "ar" else "en"))
                }
                @Suppress("DEPRECATION")
                resources.updateConfiguration(configuration, resources.displayMetrics)
                for (theme in listOf(MediaWidgetTheme.DarkGlass, MediaWidgetTheme.LightGlass)) {
                    for ((w, h) in listOf(220 to 176, 344 to 360)) {
                        val root = inflate(active(cover = cover).copy(title = "Marea", artist = "Luma"), w, h, theme)
                        val title = nativeRect(root, root.findViewById(R.id.media_widget_title))
                        val hero = Rect((root.width * 0.18f).toInt(), (root.height * (if (h > 200) 0.28f else 0.08f)).toInt(),
                            (root.width * 0.42f).toInt(), (root.height * (if (h > 200) 0.35f else 0.24f)).toInt())
                        val clearTexture = texture(root, hero)
                        val ink=nativeInk(root,root.findViewById(R.id.media_widget_title))
                        try {
                            val support=dilatedNativeInk(ink.alpha,root.width,root.height)
                            val readingTexture=nativeTexture(ink.underlay,support)
                            val heroSupport=BooleanArray(root.width*root.height) { i -> hero.contains(i%root.width,i/root.width) }
                            val nativeHeroTexture=nativeTexture(ink.underlay,heroSupport)
                            assertTrue("Hero retains texture $theme ${w}x$h font $font: $clearTexture",clearTexture>3.0)
                            assertTrue("Frost reduces edges under ACTUAL native ink, not blank box: $readingTexture / $nativeHeroTexture",
                                readingTexture<nativeHeroTexture*0.5)
                        } finally { ink.recycle() }
                        assertNativeTextContrast(root, "frost $theme ${w}x$h font $font")
                        assertNativeTargets(root, "frost $theme ${w}x$h font $font")
                    }
                }
            }
        } finally {
            @Suppress("DEPRECATION")
            resources.updateConfiguration(original, resources.displayMetrics)
        }
    }

    @Test fun vignetteHasSoftEdgesWithoutCoveringTheHeroWithAUniformFilm() {
        val cover = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(160, 160, 160)) }
        for (theme in listOf(MediaWidgetTheme.DarkGlass, MediaWidgetTheme.LightGlass)) {
            val bitmap = background(inflate(active(cover = cover).copy(title = "Marea", artist = "Luma"), theme = theme))
            val y = (bitmap.height * 0.28f).toInt()
            val centre = bitmap.getPixel(bitmap.width / 2, y)
            val edge = bitmap.getPixel((bitmap.width * 0.01f).toInt(), y)
            assertTrue("Vignette edge $theme", androidx.core.graphics.ColorUtils.calculateLuminance(centre) >
                androidx.core.graphics.ColorUtils.calculateLuminance(edge) + 0.035)
            var previous = Color.red(centre)
            for (step in 1..16) {
                val x = (bitmap.width * (0.5f - step * 0.0275f)).toInt()
                val value = Color.red(bitmap.getPixel(x, y))
                assertTrue("Soft vignette $theme step $step", kotlin.math.abs(value - previous) < 20)
                previous = value
            }
        }
    }

    @Test fun longerMetadataMovesTheFrostWithTheNativeTextOnTheSameArtwork() {
        val cover = comparisonCovers().first { it.first == "checker-synthetic" }.second
        val short = inflate(active(cover = cover).copy(title = "Marea", artist = "Luma"))
        assertNativeTextContrast(short, "short metadata")
        val shortTitle = short.findViewById<TextView>(R.id.media_widget_title)
        assertTrue("Short title uses localized native width", shortTitle.width < short.width * 0.6f)
        assertTrue("Artist halo follows natural native width", short.findViewById<TextView>(R.id.media_widget_artist).width < short.width * 0.6f)
        val longer = inflate(active(cover = cover).copy(
            title = "A much longer title across the night and the city, with more words than fit on one line", artist = "Luma"))
        val title = longer.findViewById<TextView>(R.id.media_widget_title)
        assertEquals("Fixture exercises two actual native title lines", 2, title.layout.lineCount)
        val firstLine = nativeRect(longer, title).apply { bottom = top + title.layout.getLineBottom(0) }
        val longInk=nativeInk(longer,title)
        val shortFrame=nativeFrame(short)
        try {
            val firstLineCoverage=dilatedNativeInk(longInk.alpha,longer.width,longer.height).also { mask ->
                for(i in mask.indices)mask[i]=mask[i] && firstLine.contains(i%longer.width,i/longer.width)
            }
            assertTrue("Metadata moves protection to the actual first-line ink",
                nativeTexture(longInk.underlay,firstLineCoverage)<nativeTexture(shortFrame,firstLineCoverage)*0.65)
        } finally { longInk.recycle();shortFrame.recycle() }
        assertNativeTextContrast(longer, "long metadata")
        assertNativeTargets(longer, "long metadata")
        // Additional fixture, not a replacement for the existing long-title or RTL matrix.
        val accentedTitle = "L’orizzonte più lontano: città, perché c’è ancora musica nell’oscurità e una melodia che non finisce mai"
        val accented = inflate(active(cover = cover).copy(title = accentedTitle, artist = "Élodie · già qui"),
            theme = MediaWidgetTheme.LightGlass)
        captureWidget(accented, 344, 360, "build/outputs/roborazzi/widget-v2/accents-long-metadata.png")
        val accentedView = accented.findViewById<TextView>(R.id.media_widget_title)
        assertEquals("UTF-8 accented title retained", accentedTitle, accentedView.text.toString())
        assertEquals("Accented long title retains native line cap", 2, accentedView.layout.lineCount)
        assertTrue("Accented long title retains native ellipsis",
            accentedView.layout.getEllipsisCount(accentedView.layout.lineCount - 1) > 0)
        assertNativeTextContrast(accented, "accented long metadata")
        assertNativeTargets(accented, "accented long metadata")
    }

    @Test fun artworkKeepsDetailInReadingAreaAndBetweenUtilityButtons() {
        // Both halves already support light text. Their distinction should survive the film.
        val cover = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) for (x in 0 until width)
                setPixel(x, y, if (x < width / 2) Color.rgb(5, 35, 65) else Color.rgb(5, 100, 110))
        }
        for ((w, h) in listOf(220 to 176, 344 to 360)) {
            val bitmap = background(inflate(active(cover = cover), w, h))
            for (fraction in listOf(0.12f, 0.72f)) {
                val y = (bitmap.height * fraction).toInt()
                val a = bitmap.getPixel(bitmap.width / 4, y)
                val b = bitmap.getPixel(bitmap.width * 3 / 4, y)
                val detail = kotlin.math.abs(Color.red(a) - Color.red(b)) +
                    kotlin.math.abs(Color.green(a) - Color.green(b)) +
                    kotlin.math.abs(Color.blue(a) - Color.blue(b))
                assertTrue("Artwork detail ${w}x$h at $fraction: $detail", detail > 50)
            }
        }
    }
    @Test fun elevatedFontAndRtlKeepPrimaryTarget() {
        val resources = context.resources
        val original = android.content.res.Configuration(resources.configuration)
        val enlarged = android.content.res.Configuration(original).apply {
            fontScale = 2f
            setLayoutDirection(java.util.Locale("ar"))
        }
        try {
            @Suppress("DEPRECATION")
            resources.updateConfiguration(enlarged, resources.displayMetrics)
            for ((w,h) in listOf(110 to 110, 220 to 176, 344 to 360)) {
                val root = inflate(active(), w, h)
                val button = root.findViewById<View>(R.id.media_widget_play_pause)
                assertTrue(button.width >= 48*resources.displayMetrics.density)
                assertTrue(button.height >= 48*resources.displayMetrics.density)
                if (root.findViewById<View>(R.id.media_widget_title).isShown) {
                    val title = nativeRect(root, root.findViewById(R.id.media_widget_title))
                    val primary = nativeRect(root, button)
                    assertTrue("Real separation at elevated font ${w}x$h", primary.top - title.bottom >=
                        (6f * resources.displayMetrics.density).toInt())
                }
                assertNativeTextContrast(root, "large RTL ${w}x$h")
                assertNativeTargets(root, "large RTL ${w}x$h")
                File("build/outputs/roborazzi/widget-v2").mkdirs()
                captureWidget(root, w, h, "build/outputs/roborazzi/widget-v2/rtl-font2-${w}x${h}.png")
            }
        } finally {
            @Suppress("DEPRECATION")
            resources.updateConfiguration(original, resources.displayMetrics)
        }
    }
    @Test fun onArtworkPolarityAndClockDoNotFollowChromeOrSeconds() {
        val black=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLACK)}
        val white=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.WHITE)}
        val colors=mutableMapOf<String,Int>()
        for(theme in listOf(MediaWidgetTheme.DarkGlass,MediaWidgetTheme.LightGlass))for((name,cover)in listOf("black" to black,"white" to white)) {
            val snapshot=active(cover=cover).copy(title="Marea",artist="Luma")
            val root=inflate(snapshot,theme=theme)
            val foreground=root.findViewById<TextView>(R.id.media_widget_title).currentTextColor
            colors["$theme-$name"]=foreground
            assertNativeTextContrast(root,"onArtwork $theme $name")
            assertNativeTargets(root,"onArtwork $theme $name")
            val tick=inflate(snapshot.copy(positionMs=62000),theme=theme)
            assertSame("No bitmap per clock tick",background(root),background(tick))
            val pause=inflate(snapshot.copy(isPlaying=false),theme=theme)
            assertEquals("Polarity frozen across play/pause",foreground,pause.findViewById<TextView>(R.id.media_widget_title).currentTextColor)
        }
        for(name in listOf("black","white"))assertEquals("Artwork polarity independent of chrome $name",
            colors["${MediaWidgetTheme.DarkGlass}-$name"],colors["${MediaWidgetTheme.LightGlass}-$name"])
        assertTrue("Opposite ink on uniform artwork",androidx.core.graphics.ColorUtils.calculateLuminance(colors["${MediaWidgetTheme.LightGlass}-black"]!!)>
            androidx.core.graphics.ColorUtils.calculateLuminance(colors["${MediaWidgetTheme.LightGlass}-white"]!!))
    }

    @Test fun sourcePillFallbackAndDisabledKeepTheirOwnNativeRoles() {
        val black=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLACK)}
        val root=inflate(active(cover=black).copy(sourceLabel="Fluidify"),theme=MediaWidgetTheme.LightGlass,
            settings=AppSettings(mediaWidgetTheme=MediaWidgetTheme.LightGlass,mediaWidgetShowSource=true))
        assertTrue(root.findViewById<View>(R.id.media_widget_source).isShown)
        assertNotEquals("Pill owns its ink, independent from photo",root.findViewById<TextView>(R.id.media_widget_title).currentTextColor,
            root.findViewById<TextView>(R.id.media_widget_source).currentTextColor)
        assertNativeTextContrast(root,"source own surface")
        val disabled=inflate(active(cover=black).copy(canPlayPause=false,canSkipNext=false,canSkipPrevious=false))
        assertNativeTargets(disabled,"disabled opaque ink")
        assertFalse(disabled.findViewById<View>(R.id.media_widget_play_pause).isEnabled)
        val dark=inflate(active(),theme=MediaWidgetTheme.DarkGlass)
        val light=inflate(active(),theme=MediaWidgetTheme.LightGlass)
        assertNotEquals("No-artwork foreground follows existing theme",dark.findViewById<TextView>(R.id.media_widget_title).currentTextColor,
            light.findViewById<TextView>(R.id.media_widget_title).currentTextColor)
        assertNativeTextContrast(dark,"fallback dark");assertNativeTextContrast(light,"fallback light")
    }

    @Test fun shapedFieldLeavesTrueAirBetweenNativeGlyphGroups() {
        val cover=comparisonCovers().first{it.first=="checker-synthetic"}.second
        val root=inflate(active(cover=cover).copy(title="M                  M",artist=""))
        val title=root.findViewById<TextView>(R.id.media_widget_title)
        val sample=nativeInk(root,title)
        try {
            assertNativeAlignment(sample,"air between glyph groups")
            requireNativeContrast(sample,4.5,"air fixture")
            val near=dilatedNativeInk(sample.alpha,root.width,root.height,(18f*context.resources.displayMetrics.density).toInt())
            val box=nativeRect(root,title)
            val air=BooleanArray(near.size){i-> !near[i] && box.contains(i%root.width,i/root.width)}
            assertTrue("True empty support remains",air.any{it})
            assertTrue("Checker detail survives away from actual ink",nativeTexture(sample.underlay,air)>3.0)
        } finally { sample.recycle() }
    }

    private fun sceneCover(): Bitmap {
        // Generated engineering fixture, never shipped as a real album or runtime snapshot.
        val art = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(art)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, 512f,
            Color.rgb(13, 74, 82), Color.rgb(3, 25, 42), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 512f, 512f, paint)
        paint.shader = null
        paint.color = Color.rgb(255, 146, 99)
        canvas.drawCircle(328f, 154f, 58f, paint)
        val wave = android.graphics.Path().apply {
            moveTo(0f, 345f)
            cubicTo(110f, 250f, 170f, 465f, 300f, 349f)
            cubicTo(390f, 285f, 446f, 390f, 512f, 340f)
        }
        paint.color = Color.rgb(168, 214, 206)
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 12f
        canvas.drawPath(wave, paint)
        return art
    }

    @Test fun captureApprovedSceneWithDeterministicArtwork() {
        val art = sceneCover()
        val snapshot = active(cover = art).copy(title = "Marea", artist = "Luma")
        File("build/outputs/roborazzi/widget-v2").mkdirs()
        for ((w, h) in listOf(220 to 176, 344 to 360)) {
            val root = inflate(snapshot, w, h)
            assertEquals("Marea", root.findViewById<TextView>(R.id.media_widget_title).text.toString())
            captureWidget(root, w, h, "build/outputs/roborazzi/widget-v2/scena-approved-${w}x${h}.png")
        }
    }
    @Test fun captureSceneMatrix() {
        File("build/outputs/roborazzi/widget-v2").mkdirs()
        val art = Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) for (x in 0 until width)
                setPixel(x,y,if ((x/12+y/12)%2 == 0) Color.WHITE else Color.BLACK)
        }
        for ((w,h) in listOf(110 to 110, 220 to 176, 280 to 210, 344 to 360, 460 to 420, 500 to 150)) {
            captureWidget(inflate(active(cover = art),w,h), w, h,
                "build/outputs/roborazzi/widget-v2/scene-${w}x${h}.png")
        }
    }

    private fun comparisonCovers(): List<Pair<String, Bitmap>> = listOf(
        "scene-synthetic" to sceneCover(),
        "white-synthetic" to Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) },
        "black-synthetic" to Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) },
        "magenta-synthetic" to Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) },
        "checker-synthetic" to Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) for (x in 0 until width)
                setPixel(x, y, if ((x / 12 + y / 12) % 2 == 0) Color.WHITE else Color.BLACK)
        },
    )

    /** Use this SAME test source on the base and candidate, archiving each output directory.
     * Only RemoteViews.apply + native View.draw produces these PNGs. No reference PNG is an input.
     * The manifest identifies synthetic artwork and records all comparison inputs. */
    @Test fun captureArtworkComparisonMatrix() {
        val output = File("build/outputs/roborazzi/widget-v2/artwork-comparison").apply { mkdirs() }
        val manifest = org.json.JSONArray()
        val observed = org.json.JSONArray()
        val resources = context.resources
        val original = android.content.res.Configuration(resources.configuration)
        val covers = comparisonCovers()
        try {
            for ((w, h) in listOf(220 to 176, 344 to 360)) {
                for (largeRtl in listOf(false, true)) {
                    val configuration = android.content.res.Configuration(original).apply {
                        fontScale = if (largeRtl) 2f else 1f
                        setLayoutDirection(java.util.Locale(if (largeRtl) "ar" else "en"))
                    }
                    @Suppress("DEPRECATION")
                    resources.updateConfiguration(configuration, resources.displayMetrics)
                    for ((name, cover) in covers) {
                        val pixels = java.nio.ByteBuffer.allocate(cover.byteCount)
                        cover.copyPixelsToBuffer(pixels)
                        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(pixels.array())
                            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                        val snapshot = active(cover = cover).copy(
                            title = if (largeRtl) "رحلة طويلة عبر الليل وموسيقى بلا نهاية" else "Marea",
                            artist = if (largeRtl) "Luma · Artist with a long name" else "Luma",
                            sourceLabel = "Fluidify",
                        )
                        for (theme in listOf(MediaWidgetTheme.DarkGlass, MediaWidgetTheme.LightGlass)) {
                            val path = "$name-${w}x$h-${if (largeRtl) "font2-rtl" else "font1-ltr"}-$theme.png"
                            val root = inflate(snapshot, w, h, theme)
                            captureWidget(root, w, h, File(output, path).path)
                            observed.put(org.json.JSONObject().apply {
                                put("path", path); put("actualRootLayoutDirection", root.layoutDirection)
                                put("widthPx", root.width); put("heightPx", root.height)
                                put("backgroundWidthPx", background(root).width); put("backgroundHeightPx", background(root).height)
                                put("backgroundPixelBytes", background(root).byteCount)
                                put("targets", org.json.JSONArray().apply {
                                    for (id in listOf(R.id.media_widget_previous, R.id.media_widget_play_pause, R.id.media_widget_next,
                                        R.id.media_widget_refresh, R.id.media_widget_open_media, R.id.media_widget_open_pampa)) {
                                        val target = root.findViewById<View>(id)
                                        if (!target.isShown) continue
                                        val rect = nativeRect(root, target)
                                        put(org.json.JSONObject().apply {
                                            put("id", resources.getResourceEntryName(id)); put("left", rect.left); put("top", rect.top)
                                            put("right", rect.right); put("bottom", rect.bottom); put("enabled", target.isEnabled)
                                        })
                                    }
                                })
                            })
                            manifest.put(org.json.JSONObject().apply {
                                put("path", path); put("fixture", name); put("synthetic", true)
                                put("artworkPixelSha256", hash); put("artworkWidth", cover.width); put("artworkHeight", cover.height)
                                put("widthDp", w); put("heightDp", h); put("density", resources.displayMetrics.density)
                                put("fontScale", configuration.fontScale); put("layoutDirection", configuration.layoutDirection)
                                put("theme", theme.name); put("title", snapshot.title); put("artist", snapshot.artist)
                                put("sourceLabel", snapshot.sourceLabel); put("positionMs", snapshot.positionMs)
                                put("durationMs", snapshot.durationMs); put("isPlaying", snapshot.isPlaying)
                                put("availability", snapshot.availability.name); put("packageName", snapshot.packageName)
                                put("mediaId", snapshot.mediaId); put("canPlayPause", snapshot.canPlayPause)
                                put("canSkipPrevious", snapshot.canSkipPrevious); put("canSkipNext", snapshot.canSkipNext)
                            })
                            // Incremental metadata is explicitly incomplete until all 40 gates pass.
                            File(output, "inputs-progress.json").writeText(org.json.JSONObject().apply {
                                put("plannedCases", 40); put("cases", manifest)
                            }.toString(2), Charsets.UTF_8)
                            File(output, "native-observations.json").writeText(observed.toString(2), Charsets.UTF_8)
                            assertEquals(snapshot.title, root.findViewById<TextView>(R.id.media_widget_title).text.toString())
                            assertNativeTextContrast(root, path, File(output, "diagnostics/${path.removeSuffix(".png")}.json"))
                            assertNativeTargets(root, path)
                        }
                    }
                }
            }
            File(output, "inputs.json").writeText(org.json.JSONObject().apply {
                put("renderer", "RemoteViews.apply + native View.draw; API 35; no launcher/GPU evidence")
                put("artwork", "Synthetic engineering fixtures only; never a production album or the concept PNG")
                put("cases", manifest)
            }.toString(2))
            File(output, "native-observations.json").writeText(observed.toString(2))
            assertEquals(40, manifest.length())
        } finally {
            @Suppress("DEPRECATION")
            resources.updateConfiguration(original, resources.displayMetrics)
        }
    }
}
