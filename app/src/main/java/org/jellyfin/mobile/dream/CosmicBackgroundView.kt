package org.jellyfin.mobile.dream

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * The animated background of Cosmic: the dark of the app with glows of its colors drifting slowly and stars that
 * twinkle. Behind the backdrops of the screensaver, and alone when there are none (no server, or no network). Drawn
 * about 20 times a second, which is smooth for something this slow and light on the TV.
 */
class CosmicBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private class Glow(val color: Int, val radius: Float, val speedX: Float, val speedY: Float, val phase: Float)
    private class Star(val x: Float, val y: Float, val size: Float, val phase: Float)

    private val glows = listOf(
        Glow(Color.argb(GLOW_ALPHA, 0xA4, 0x63, 0xD6), radius = 0.55f, speedX = 0.000_11f, speedY = 0.000_07f, phase = 0f),
        Glow(Color.argb(GLOW_ALPHA, 0x00, 0xA4, 0xDC), radius = 0.6f, speedX = 0.000_08f, speedY = 0.000_12f, phase = 2f),
        Glow(Color.argb(GLOW_ALPHA / 2, 0x6C, 0x5C, 0xE7), radius = 0.45f, speedX = 0.000_05f, speedY = 0.000_09f, phase = 4f),
    )
    private val stars = Random(STAR_SEED).let { random ->
        List(STAR_COUNT) { Star(random.nextFloat(), random.nextFloat(), 0.6f + random.nextFloat() * 1.4f, random.nextFloat() * 6f) }
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val start = SystemClock.uptimeMillis()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(BACKGROUND)
        val w = width.toFloat()
        val h = height.toFloat()
        val t = (SystemClock.uptimeMillis() - start).toFloat()
        for (glow in glows) {
            val cx = w * (0.5f + 0.35f * sin(t * glow.speedX + glow.phase))
            val cy = h * (0.5f + 0.3f * cos(t * glow.speedY + glow.phase))
            val r = max(w, h) * glow.radius
            glowPaint.shader = RadialGradient(cx, cy, r, glow.color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, glowPaint)
        }
        val density = resources.displayMetrics.density
        for (star in stars) {
            val twinkle = 0.5f + 0.5f * sin(t * TWINKLE_SPEED + star.phase)
            starPaint.alpha = (MAX_STAR_ALPHA * (0.25f + 0.75f * twinkle)).toInt()
            canvas.drawCircle(star.x * w, star.y * h, star.size * density, starPaint)
        }
        postInvalidateDelayed(FRAME_MS)
    }

    private companion object {
        val BACKGROUND = Color.rgb(0x08, 0x08, 0x10)
        const val GLOW_ALPHA = 0x55
        const val STAR_COUNT = 70
        const val STAR_SEED = 7
        const val MAX_STAR_ALPHA = 200
        const val TWINKLE_SPEED = 0.0015f
        const val FRAME_MS = 50L
    }
}
