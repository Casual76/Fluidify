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
    private var ringShader: Shader? = null
    private var ringTint = 0
    private var ringWidth = -1f
    private var ringDiameter = -1f
    private var ringSweep = -1f
    private var ringGap = -1f
    private val ringMatrix = Matrix()
    /** A light field with radial and angular falloff; the arc geometry only bounds its shading. */
    fun drawRing(canvas: Canvas, diameter: Float, progress: Float, gapDegrees: Float, tint: Int, energy: Float, bass: Float) {
        if (diameter <= 0 || progress <= 0) return
        val radius = diameter / 2
        val width = diameter * (0.13f + 0.07f * bass.coerceIn(0f, 1f))
        val sweep = (360 - gapDegrees).coerceIn(0f, 360f) * progress.coerceIn(0f, 1f)
        if (sweep <= 0f) return
        if (ringShader == null || ringTint != tint || abs(width - ringWidth) > 1f ||
            ringDiameter != diameter || abs(sweep - ringSweep) > .35f || ringGap != gapDegrees) {
            ringTint = tint; ringWidth = width; ringDiameter = diameter; ringSweep = sweep; ringGap = gapDegrees
            val transparent = tint and 0xffffff
            fun shade(alpha: Int) = transparent or (alpha shl 24)
            val inner = 1 - width / radius
            val radial = RadialGradient(radius, radius, radius,
                intArrayOf(transparent, transparent, shade(12), shade(45), shade(105), shade(170), shade(100), transparent),
                floatArrayOf(0f, inner, inner + .22f * (1 - inner), inner + .45f * (1 - inner),
                    inner + .68f * (1 - inner), inner + .84f * (1 - inner), inner + .94f * (1 - inner), 1f), Shader.TileMode.CLAMP)
            // Keep the time endpoint exact, but let light dissolve before it instead of ending in a cut.
            val end = (sweep / 360f).coerceAtMost(.9999f)
            val feather = min(14f / 360f, end * .45f)
            val angular = SweepGradient(radius, radius,
                intArrayOf(0x00ffffff, -1, -1, 0x00ffffff, 0x00ffffff),
                floatArrayOf(0f, feather, end - feather, end, 1f))
            ringMatrix.setRotate(-90 + gapDegrees / 2, radius, radius)
            angular.setLocalMatrix(ringMatrix)
            ringShader = ComposeShader(radial, angular, PorterDuff.Mode.DST_IN)
        }
        paint.shader = ringShader; paint.alpha = (120 + 135 * energy).toInt().coerceIn(0, 255)
        // Skip the transparent centre and unused arc. Both edges of this geometry have zero
        // shader alpha, so this does not introduce an outline or a cut at either endpoint.
        paint.style = Paint.Style.STROKE; paint.strokeWidth = width
        oval.set(width / 2, width / 2, diameter - width / 2, diameter - width / 2)
        canvas.drawArc(oval, -90 + gapDegrees / 2, sweep, false, paint)
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
            // Dark artwork colours need emitted-light luminance to remain visible behind glass.
            val hsv = FloatArray(3)
            Color.colorToHSV(tint, hsv)
            hsv[1] = hsv[1].coerceAtMost(.72f)
            hsv[2] = hsv[2].coerceAtLeast(.88f)
            filter = PorterDuffColorFilter(Color.HSVToColor(hsv), PorterDuff.Mode.SRC_IN)
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
                radiusX = cover.width() * (0.20f + 0.28f * v + 0.13f * bass)
                radiusY = radiusX
            } else {
                x = width * (i + 0.5f) / 8 + if (mini) 0f else sin(time * 1.2f + i) * width * 0.025f * v
                y = height * if (mini) .98f else 1.02f
                radiusX = width * if (mini) 0.20f else 0.23f + 0.04f * bass
                radiusY = height * if (mini) (if (compact) .82f else 1.15f) * (.60f + .65f * v) else 0.18f + 0.24f * v + 0.12f * bass
            }
            if (cover != null && bottomMix > 0f) {
                x += (width * (i + .5f) / 8 - x) * bottomMix
                y += (height * 1.02f - y) * bottomMix
                radiusX += (width * (.23f + .04f * bass) - radiusX) * bottomMix
                radiusY += (height * (.18f + .24f * v + .12f * bass) - radiusY) * bottomMix
            }
            // Keep the emitted light strong, but close to the cover or the bottom of the pill.
            radiusY *= if (mini) .70f else .65f
            val haloPaint = haloPaints[i]
            haloPaint.colorFilter = filter
            haloPaint.alpha = ((if (mini) (if (compact) .80f else 1.05f) else .90f) * sqrt(v.coerceAtLeast(0f)) * 255)
                .toInt().coerceIn(0, 245)
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
