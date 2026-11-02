package dev.yusuf.falcontok

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** TikTok's loader: a cyan and a pink dot circling each other, the one in front a little larger. */
class LoadingDots(context: Context) : View(context) {
    private val cyan = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF25F4EE.toInt() }
    private val pink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFE2C55.toInt() }
    private var phase = 0f
    private val spin = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        spin.start()
    }

    override fun onDetachedFromWindow() {
        spin.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val a = phase * 2 * PI
        val r = height / 3.2f
        val reach = width / 2f - r * 1.2f
        val cx = width / 2f
        val cy = height / 2f
        // Depth: sin > 0 means the cyan dot is in front.
        val depth = sin(a).toFloat()
        val cyanX = cx - reach * cos(a).toFloat()
        val pinkX = cx + reach * cos(a).toFloat()
        val cyanR = r * (1 + 0.18f * depth)
        val pinkR = r * (1 - 0.18f * depth)
        if (depth > 0) {
            canvas.drawCircle(pinkX, cy, pinkR, pink)
            canvas.drawCircle(cyanX, cy, cyanR, cyan)
        } else {
            canvas.drawCircle(cyanX, cy, cyanR, cyan)
            canvas.drawCircle(pinkX, cy, pinkR, pink)
        }
    }
}
