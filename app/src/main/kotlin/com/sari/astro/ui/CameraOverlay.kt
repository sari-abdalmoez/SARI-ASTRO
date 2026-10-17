package com.sari.astro.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** Rule-of-thirds grid and a centre focus target. Purely visual: never intercepts touches. */
class CameraOverlay(ctx: Context) : View(ctx) {
    private val density = ctx.resources.displayMetrics.density
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        strokeWidth = density
    }
    private val target = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA4FD8F2.toInt()
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        for (i in 1..2) {
            canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, grid)
            canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, grid)
        }
        val cx = w / 2f
        val cy = h / 2f
        val r = 14f * density
        canvas.drawCircle(cx, cy, r, target)
        canvas.drawLine(cx - r * 1.6f, cy, cx - r * 0.5f, cy, target)
        canvas.drawLine(cx + r * 0.5f, cy, cx + r * 1.6f, cy, target)
        canvas.drawLine(cx, cy - r * 1.6f, cx, cy - r * 0.5f, target)
        canvas.drawLine(cx, cy + r * 0.5f, cx, cy + r * 1.6f, target)
    }
}
