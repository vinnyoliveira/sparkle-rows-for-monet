package com.ptvin.sparklerows

import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Views da tela inicial: configuracao na primeira vez e estado depois disso. */
object Ui {
    const val BG = "#0D0F14"
    const val TEXT = "#FFFFFF"
    const val MUTED = "#9AA6BF"
    const val ACCENT = "#B7C4FF"
    const val DANGER = "#FF8A8A"

    fun root(activity: android.app.Activity): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(72, 56, 72, 56)
            setBackgroundColor(Color.parseColor(BG))
        }

    fun title(activity: android.app.Activity) = TextView(activity).apply {
        text = activity.getString(R.string.app_name)
        textSize = 30f
        setTextColor(Color.WHITE)
    }

    fun text(activity: android.app.Activity, size: Float, color: String) =
        TextView(activity).apply {
            textSize = size
            setTextColor(Color.parseColor(color))
        }

    fun field(activity: android.app.Activity, hint: String): EditText =
        EditText(activity).apply {
            this.hint = hint
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor(MUTED))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setPadding(28, 20, 28, 20)
            background = activity.getDrawable(R.drawable.field)
        }

    fun button(activity: android.app.Activity, label: String, onClick: () -> Unit) =
        Button(activity).apply {
            text = label
            textSize = 15f
            setOnClickListener { onClick() }
        }

    fun spacer(activity: android.app.Activity, height: Int) =
        View(activity).apply { layoutParams = LinearLayout.LayoutParams(0, dp(activity, height)) }

    fun dp(activity: android.app.Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
