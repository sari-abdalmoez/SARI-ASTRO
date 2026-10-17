package com.sari.astro.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** SARI Astro visual language: deep navy surfaces, cyan primary accent, violet for labels and selection. */
object SariPalette {
    val BG: Int = Color.parseColor("#060A14")
    val SURFACE: Int = Color.parseColor("#0C1322")
    val SURFACE_RAISED: Int = Color.parseColor("#131C30")
    val STROKE: Int = Color.parseColor("#1F2B45")
    val TEXT: Int = Color.parseColor("#E9EEF8")
    val TEXT_MUTED: Int = Color.parseColor("#8B98B3")
    val ACCENT: Int = Color.parseColor("#4FD8F2")
    val VIOLET: Int = Color.parseColor("#A393FF")
    val WARNING: Int = Color.parseColor("#FBBF24")
    val DANGER: Int = Color.parseColor("#F87171")
}

/** Small set of reusable, dependency-free view builders used by the SARI screens. */
class SariKit(private val ctx: Context) {
    private val density: Float = ctx.resources.displayMetrics.density

    fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    fun surface(fill: Int = SariPalette.SURFACE, radiusDp: Int = 16, stroke: Int? = SariPalette.STROKE): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(fill)
            if (stroke != null) setStroke(dp(1), stroke)
        }

    fun text(value: String, sizeSp: Float, color: Int = SariPalette.TEXT, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    /** Small uppercase label used as a section heading inside cards. */
    fun section(value: String): TextView =
        text(value.uppercase(), 11f, SariPalette.VIOLET, bold = true).apply { letterSpacing = 0.12f }

    fun card(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = surface()
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    fun button(label: String, primary: Boolean = false): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(12), 0, dp(12), 0)
        setTextColor(if (primary) SariPalette.BG else SariPalette.TEXT)
        background = surface(
            if (primary) SariPalette.ACCENT else SariPalette.SURFACE_RAISED,
            12,
            if (primary) null else SariPalette.STROKE
        )
    }

    /** Segmented control: one selected segment filled with the accent colour. */
    fun segmented(labels: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = surface(SariPalette.SURFACE, 14, SariPalette.STROKE)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            labels.forEachIndexed { index, label ->
                val on = index == selected
                val segment = Button(ctx).apply {
                    text = label
                    isAllCaps = false
                    textSize = 13f
                    minHeight = 0
                    minimumHeight = 0
                    setTextColor(if (on) SariPalette.BG else SariPalette.TEXT_MUTED)
                    background = surface(if (on) SariPalette.ACCENT else Color.TRANSPARENT, 10, null)
                    setOnClickListener { onPick(index) }
                }
                addView(
                    segment,
                    LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (index > 0) leftMargin = dp(4) }
                )
            }
        }

    fun progressBar(): ProgressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progress = 0
        progressTintList = ColorStateList.valueOf(SariPalette.ACCENT)
        progressBackgroundTintList = ColorStateList.valueOf(SariPalette.SURFACE_RAISED)
    }
}
