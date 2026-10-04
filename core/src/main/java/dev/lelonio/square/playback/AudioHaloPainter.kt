package dev.lelonio.square.playback

import android.graphics.*
import kotlin.math.*

/** Cached radial light texture and paints; no blur or per-frame bitmap allocation. */
class AudioHaloPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaints = Array(8) { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    private val oval = RectF()
    private var color = 0
    private var filter: ColorFilter? = null
    private val levels = FloatArray(8)
    private var lastNs = 0L
    private var ringShader: RadialGradient? = null
    private var ringTint = 0
    private var ringWidth = -1f
    /** The gradient spans the band, so neither its inside nor its outside has a hard edge. */
    fun drawRing(canvas: Canvas, diameter: Float, progress: Float, gapDegrees: Float, tint: Int, energy: Float, bass: Float) {
        if (diameter <= 0 || progress <= 0) return
        val radius = diameter / 2
        val width = diameter * (0.045f + 0.045f * bass)
        if (ringShader == null || ringTint != tint || abs(width - ringWidth) > 0.5f) {
            ringTint = tint; ringWidth = width
            val transparent = tint and 0xffffff
            ringShader = RadialGradient(radius, radius, radius,
                intArrayOf(transparent, transparent, tint or (0xff shl 24), transparent),
                floatArrayOf(0f, (1 - width / radius).coerceAtLeast(.01f), 1 - width / radius * .3f, 1f), Shader.TileMode.CLAMP)
        }
        paint.shader = ringShader; paint.alpha = (100 + 130 * energy).toInt().coerceIn(0, 255)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = width
        // Outer edge remains on the bezel; modulation only spreads inward.
        oval.set(width / 2, width / 2, diameter - width / 2, diameter - width / 2)
        canvas.drawArc(oval, -90 + gapDegrees / 2, (360 - gapDegrees) * progress, false, paint)
        paint.style = Paint.Style.FILL
    }
    fun reset() { levels.fill(0f); lastNs = 0 }
    fun draw(canvas: Canvas, width: Float, height: Float, frame: AudioLightFrame?, tint: Int,
        cover: RectF? = null, mini: Boolean = false, compact: Boolean = false, nowNs: Long = System.nanoTime(), bottomMix: Float = 0f) {
        if (width <= 0 || height <= 0) return
        val dt = if (lastNs == 0L) 1f / 60 else ((nowNs - lastNs) / 1e9f).coerceIn(0f, 0.1f)
        lastNs = nowNs
        if (filter == null || tint != color) {
            color = tint
            filter = PorterDuffColorFilter(tint or (0xff shl 24), PorterDuff.Mode.SRC_IN)
        }
        val time = (frame?.positionMs ?: 0) / 1000f
        for (i in 0..7) {
            val target = frame?.bands?.getOrNull(i) ?: 0f
            val tau = if (frame == null || frame.energy == 0f) .065f else if (i in 3..5) .16f else if (target > levels[i]) .035f else if (i >= 6) .08f else .22f
            levels[i] += (target - levels[i]) * (1 - exp(-dt / tau))
            val v = levels[i]
            if (v < 0.002f) continue
            val bass = if (i <= 2) frame?.bass ?: v else 0f
            var radiusX: Float; var radiusY: Float; var x: Float; var y: Float
            if (cover != null && !cover.isEmpty) {
                val along = if (i % 2 == 0) 0.28f else 0.72f
                x = when (i / 2) { 0 -> cover.left + cover.width() * along; 1 -> cover.right; 2 -> cover.left + cover.width() * along; else -> cover.left }
                y = when (i / 2) { 0 -> cover.top; 1 -> cover.top + cover.height() * along; 2 -> cover.bottom; else -> cover.top + cover.height() * along }
                radiusX = cover.width() * (0.16f + 0.24f * v + 0.12f * bass)
                radiusY = radiusX
            } else {
                x = width * (i + 0.5f) / 8 + if (mini) 0f else sin(time * 1.2f + i) * width * 0.025f * v
                y = height * if (mini) 1.04f else 1.02f
                radiusX = width * if (mini) 0.20f else 0.23f + 0.04f * bass
                radiusY = height * if (mini) (if (compact) .68f else .90f) * (.55f + .60f * v) else 0.18f + 0.24f * v + 0.12f * bass
            }
            if (cover != null && bottomMix > 0f) {
                x += (width * (i + .5f) / 8 - x) * bottomMix
                y += (height * 1.02f - y) * bottomMix
                radiusX += (width * (.23f + .04f * bass) - radiusX) * bottomMix
                radiusY += (height * (.18f + .24f * v + .12f * bass) - radiusY) * bottomMix
            }
            val haloPaint = haloPaints[i]
            haloPaint.colorFilter = filter
            haloPaint.alpha = (if (mini) (if (compact) .36f else .48f) * sqrt(v.coerceAtLeast(0f)) * 255
                else .46f * v * 255).toInt().coerceIn(0, 130)
            oval.set(x - radiusX, y - radiusY, x + radiusX, y + radiusY)
            canvas.drawBitmap(haloTexture, null, oval, haloPaint)
        }
    }
    companion object {
        private val haloTexture by lazy {
            val pixels = IntArray(128 * 128) { index ->
                val x = (index % 128 + .5f - 64f) / 64f
                val y = (index / 128 + .5f - 64f) / 64f
                val alpha = ((1f - sqrt(x * x + y * y)).coerceIn(0f, 1f) * 255).toInt()
                (alpha shl 24) or 0xffffff
            }
            Bitmap.createBitmap(pixels, 128, 128, Bitmap.Config.ARGB_8888)
        }
    }
}
