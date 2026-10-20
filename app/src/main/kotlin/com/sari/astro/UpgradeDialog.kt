package com.sari.astro

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Presentation-only premium panel. There is no purchase or subscription backend in this project,
 * so the upgrade button does not start a purchase and does not unlock anything.
 */
object UpgradeDialog {
    private val ORANGE = Color.rgb(255, 140, 26)
    private val BODY = Color.rgb(196, 206, 220)

    private data class Feature(val icon: String, val title: String, val body: String)

    private val features = listOf(
        Feature("◉", "REAL-TIME ASTRO CAPTURE & LIVE STACKING",
            "Capture and stack astronomical frames in real time, with a live preview of the developing result."),
        Feature("◎", "PLANETARY IMAGING MODE 🪐",
            "A dedicated mode for capturing and processing planetary images."),
        Feature("✧", "AI-POWERED ASTRO IMAGE ENHANCEMENT",
            "Astronomy-focused AI enhancement designed to reveal more astronomical detail."),
        Feature("◈", "ADVANCED ASTRO PROCESSING",
            "More powerful image processing for improved astronomical results.")
    )

    fun show(activity: Activity) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + .5f).toInt()

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val panel = GradientDrawable().apply {
            setColor(Color.rgb(12, 18, 28))
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.rgb(42, 55, 72))
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = panel
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }

        // Header: title and a close button, so the panel can always be dismissed.
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = "SARI ASTRO PRO"; textSize = 20f; setTextColor(ORANGE); setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(activity).apply {
                text = "Take your astrophotography further"; textSize = 12f; setTextColor(Color.rgb(151, 165, 183))
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(activity).apply {
            text = "✕"; textSize = 16f; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.rgb(22, 29, 40)); cornerRadius = dp(12).toFloat() }
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(header)

        // Benefit list scrolls on small screens; the buttons below always stay visible.
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }
        for (f in features) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, dp(8))
            }
            row.addView(TextView(activity).apply {
                text = f.icon; textSize = 18f; setTextColor(ORANGE); gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }, LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(1) })
            row.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = f.title; textSize = 14f; setTextColor(ORANGE); setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(activity).apply {
                    text = f.body; textSize = 12.5f; setTextColor(BODY); setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            list.addView(row)
        }
        val scroll = ScrollView(activity).apply { isFillViewport = true; addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6) })

        // Status line: explains that purchasing is not available yet. It never claims an activation.
        val status = TextView(activity).apply {
            textSize = 12f; setTextColor(Color.rgb(151, 165, 183)); gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0); visibility = android.view.View.GONE
        }
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val upgrade = Button(activity).apply {
            text = "UPGRADE TO PRO"; isAllCaps = false; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply { setColor(Color.rgb(200, 110, 20)); cornerRadius = dp(13).toFloat() }
            setOnClickListener {
                status.text = "Purchasing will be available in a future update. No Pro features are active yet."
                status.visibility = android.view.View.VISIBLE
            }
        }
        root.addView(upgrade, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(12) })

        val later = Button(activity).apply {
            text = "NOT NOW"; isAllCaps = false; setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(22, 29, 40)); cornerRadius = dp(13).toFloat(); setStroke(dp(1), Color.rgb(42, 55, 72))
            }
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(later, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })

        dialog.setContentView(root)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            val metrics = activity.resources.displayMetrics
            setLayout((metrics.widthPixels * 0.92f).toInt(), (metrics.heightPixels * 0.78f).toInt())
        }
        Analytics.event(activity, "upgrade_panel_opened")
        dialog.show()
    }
}
