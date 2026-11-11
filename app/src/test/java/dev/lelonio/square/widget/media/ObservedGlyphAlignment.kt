package dev.lelonio.square.widget.media

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.text.MeasuredText
import android.graphics.text.PositionedGlyphs
import android.graphics.text.TextRunShaper
import android.view.View
import org.junit.Assert.assertEquals

/** Test-only replay of WHOLE contextual Canvas runs, never characters or fitted alpha pairs.
 * No visible/composited target bitmap is accepted by the reconstruction API.
 */
internal object ObservedGlyphAlignment {
    private data class Run(val paint: Paint, val matrix: Matrix, val clip: Rect,
        val glyphs: PositionedGlyphs)

    internal class Proof internal constructor(private val width: Int, private val ink: Int,
        private val background: IntArray, private val alpha: IntArray,
        private val overlap: BooleanArray, private val replay: IntArray) {
        fun overlaps(index: Int): Boolean = overlap[index]

        fun expected(index: Int, coverage: Int, color: Int, underlay: Int,
            ordinaryExpected: Int, label: String): Int {
            // Bind all operands: a changed mask or background cannot reuse a stale proof.
            if(alpha[index]!=coverage || ink!=color || background[index]!=underlay) {
                val where = "Native coverage alignment $label at (${index%width},${index/width})"
                assertEquals("$where observed alpha",alpha[index],coverage)
                assertEquals("$where observed ink",ink,color)
                assertEquals("$where observed underlay",background[index],underlay)
            }
            return if(overlap[index]) replay[index] else ordinaryExpected
        }
    }

    internal class Capture private constructor(private val bitmap: Bitmap,
        private val canvas: RunCanvas) {
        /** Null retains the ORIGINAL gate for paths whose shaping is not publicly observable.
         * Once reconstruction is attempted, ANY mismatch fails; it never downgrades to null.
         */
        fun reconstruct(originalIsolated: Bitmap, underlay: Bitmap, ink: Int): Proof? {
            try {
                if(canvas.unobserved || canvas.runs.isEmpty()) return null
                val original = pixels(originalIsolated)
                equalPixels("Observed Canvas vs original isolated draw",original,pixels(bitmap),bitmap.width)
                val union = Bitmap.createBitmap(bitmap.width,bitmap.height,Bitmap.Config.ARGB_8888)
                val one = Bitmap.createBitmap(bitmap.width,bitmap.height,Bitmap.Config.ARGB_8888)
                val replay = underlay.copy(Bitmap.Config.ARGB_8888,true)
                try {
                    val count = IntArray(original.size)
                    val onePixels = IntArray(original.size)
                    val uc=Canvas(union);val oc=Canvas(one);val rc=Canvas(replay)
                    for(run in canvas.runs) {
                        assertEquals("Observed run has the same opaque ink",ink,run.paint.color)
                        for(i in 0 until run.glyphs.glyphCount()) {
                            one.eraseColor(Color.TRANSPARENT)
                            glyph(oc,run,i)
                            one.getPixels(onePixels,0,one.width,0,0,one.width,one.height)
                            for(p in onePixels.indices) if(Color.alpha(onePixels[p])>0) count[p]++
                            glyph(uc,run,i);glyph(rc,run,i)
                        }
                    }
                    val unionPixels = pixels(union)
                    // Exact RGBA implies exact coverage, including zero-ink pixels and clipping.
                    // Bounding-box clip snapshots and public font replay qualify only if this holds.
                    equalPixels("Observed glyph union vs original isolated draw",original,unionPixels,bitmap.width)
                    return Proof(bitmap.width,ink,pixels(underlay),
                        IntArray(original.size) { Color.alpha(unionPixels[it]) },
                        BooleanArray(original.size) { count[it]>=2 },pixels(replay))
                } finally { union.recycle();one.recycle();replay.recycle() }
            } finally { bitmap.recycle() }
        }

        companion object {
            fun draw(root: View): Capture {
                val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
                val canvas=RunCanvas(bitmap)
                try { root.draw(canvas);return Capture(bitmap,canvas) }
                catch(t: Throwable) { bitmap.recycle();throw t }
            }
        }
    }

    private class RunCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val runs=mutableListOf<Run>()
        var unobserved=false
            private set
        private var depth=0

        private fun unsupported(paint: Paint) {
            if(depth==0 && paint.alpha>0) unobserved=true
        }
        private fun record(text: CharArray, start: Int, count: Int, cs: Int, cc: Int,
            x: Float, y: Float, rtl: Boolean, paint: Paint) {
            if(depth!=0 || paint.alpha==0 || count==0) return
            val matrix=Matrix().also { getMatrix(it) }
            val v=FloatArray(9).also { matrix.getValues(it) }
            val supported=paint.textAlign==Paint.Align.LEFT && paint.shader==null &&
                paint.colorFilter==null && paint.maskFilter==null && paint.style==Paint.Style.FILL &&
                !paint.isUnderlineText && !paint.isStrikeThruText &&
                (paint.blendMode==null || paint.blendMode==BlendMode.SRC_OVER) &&
                v[0]==1f && v[1]==0f && v[3]==0f && v[4]==1f &&
                v[6]==0f && v[7]==0f && v[8]==1f
            if(!supported) { unobserved=true;return }
            val copy=text.copyOf();val p=Paint(paint)
            runs+=Run(p,matrix,Rect(clipBounds),
                TextRunShaper.shapeTextRun(copy,start,count,cs,cc,x,y,rtl,p))
        }

        override fun drawTextRun(text: CharArray, index: Int, count: Int, contextIndex: Int,
            contextCount: Int, x: Float, y: Float, isRtl: Boolean, paint: Paint) {
            record(text,index,count,contextIndex,contextCount,x,y,isRtl,paint)
            depth++
            try { super.drawTextRun(text,index,count,contextIndex,contextCount,x,y,isRtl,paint) }
            finally { depth-- }
        }
        override fun drawTextRun(text: CharSequence, start: Int, end: Int, contextStart: Int,
            contextEnd: Int, x: Float, y: Float, isRtl: Boolean, paint: Paint) {
            if(text is android.text.PrecomputedText) unsupported(paint)
            else record(CharArray(text.length) { text[it] },start,end-start,contextStart,
                contextEnd-contextStart,x,y,isRtl,paint)
            depth++
            try { super.drawTextRun(text,start,end,contextStart,contextEnd,x,y,isRtl,paint) }
            finally { depth-- }
        }
        override fun drawTextRun(text: MeasuredText, start: Int, end: Int, contextStart: Int,
            contextEnd: Int, x: Float, y: Float, isRtl: Boolean, paint: Paint) {
            unsupported(paint)
            depth++
            try { super.drawTextRun(text,start,end,contextStart,contextEnd,x,y,isRtl,paint) }
            finally { depth-- }
        }
        // BoringLayout may use drawText. Preserve its original renderer AND original gate;
        // do not guess bidi or reconstruct a replacement logical/visual string.
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            unsupported(paint);super.drawText(text,x,y,paint)
        }
        override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            unsupported(paint);super.drawText(text,start,end,x,y,paint)
        }
        override fun drawText(text: CharArray, index: Int, count: Int, x: Float, y: Float, paint: Paint) {
            unsupported(paint);super.drawText(text,index,count,x,y,paint)
        }
        override fun drawText(text: CharSequence, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            unsupported(paint);super.drawText(text,start,end,x,y,paint)
        }
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width*bitmap.height).also {
        bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height)
    }
    private fun equalPixels(label: String, expected: IntArray, actual: IntArray, width: Int) {
        assertEquals("$label size",expected.size,actual.size)
        for(i in expected.indices) if(expected[i]!=actual[i])
            assertEquals("$label at (${i%width},${i/width})",expected[i],actual[i])
    }
    private fun glyph(canvas: Canvas, run: Run, index: Int) {
        val save=canvas.save()
        try {
            canvas.setMatrix(run.matrix);canvas.clipRect(run.clip)
            canvas.drawGlyphs(intArrayOf(run.glyphs.getGlyphId(index)),0,
                floatArrayOf(run.glyphs.getGlyphX(index),run.glyphs.getGlyphY(index)),0,1,
                run.glyphs.getFont(index),Paint(run.paint))
        } finally { canvas.restoreToCount(save) }
    }
}
