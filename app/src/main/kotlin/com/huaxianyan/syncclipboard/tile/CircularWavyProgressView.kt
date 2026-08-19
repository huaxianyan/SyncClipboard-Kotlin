package com.huaxianyan.syncclipboard.tile

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 磁贴快速路径使用的轻量波浪形进度指示器，不依赖 Compose 初始化。
 */
class CircularWavyProgressView(context: Context) : View(context) {
    var indicatorColor: Int = Color.BLACK
        set(value) {
            field = value
            invalidate()
        }

    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(STROKE_WIDTH_DP)
    }
    private var rotation = 0f
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = ROTATION_DURATION_MS
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            rotation = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val amplitude = dp(WAVE_AMPLITUDE_DP)
        val radius = min(width, height) / 2f - amplitude - paint.strokeWidth / 2f

        path.reset()
        for (step in 0..SEGMENTS) {
            val progress = step.toFloat() / SEGMENTS
            val angle = Math.toRadians((START_ANGLE + SWEEP_ANGLE * progress).toDouble())
            val waveRadius = radius + amplitude * sin(progress * WAVE_COUNT * 2f * PI.toFloat())
            val x = centerX + waveRadius * cos(angle).toFloat()
            val y = centerY + waveRadius * sin(angle).toFloat()
            if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        paint.color = indicatorColor
        canvas.save()
        canvas.rotate(rotation, centerX, centerY)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    private fun updateAnimation() {
        val shouldRun = isAttachedToWindow && visibility == VISIBLE && windowVisibility == VISIBLE
        if (shouldRun && !animator.isStarted) animator.start()
        if (!shouldRun && animator.isStarted) animator.cancel()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val ROTATION_DURATION_MS = 1_100L
        const val START_ANGLE = -90f
        const val SWEEP_ANGLE = 275f
        const val WAVE_COUNT = 7
        const val SEGMENTS = 112
        const val STROKE_WIDTH_DP = 4f
        const val WAVE_AMPLITUDE_DP = 2f
    }
}
