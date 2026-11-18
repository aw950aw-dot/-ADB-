package com.draco.ladb.views

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.draco.ladb.utils.UiTheme
import com.draco.ladb.utils.UiComponents

/** User chooses the intended app-management action before the app list is opened. */
class AppManagerHubActivity : AppCompatActivity() {
    private val palette get() = UiTheme.palette(this)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        UiTheme.applySavedMode(this)
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(14), dp(18), dp(20))
            setBackgroundColor(palette.background)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(button("‹", palette.surfaceAlt, palette.text) { finish() },
            LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(text("ניהול אפליקציות", 21, palette.text, true),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })
        content.addView(header)
        content.addView(text("בחר איזו פעולה לבצע. לאחר מכן תיפתח רשימת האפליקציות המתאימה.", 13, palette.muted, false)
            .apply { setPadding(0, dp(10), 0, dp(18)) })

        val options = listOf(
            Triple("הסרת אפליקציה", "הסרה מהמכשיר עבור המשתמש הנוכחי בלבד", "remove"),
            Triple("השבתת אפליקציה", "השבתת אפליקציית מערכת בלי למחוק אותה", "disable"),
            Triple("הפעלת אפליקציה", "החזרת אפליקציה שהושבתה לפעולה", "enable")
        )
        options.forEachIndexed { index, option ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = UiComponents.surface(this@AppManagerHubActivity, palette.surface, 17, palette.outline)
            }
            card.addView(text("0${index + 1}", 11, palette.accent, true))
            card.addView(text(option.first, 18, palette.text, true).apply {
                setPadding(0, dp(6), 0, dp(4))
            })
            card.addView(text(option.second, 12, palette.muted, false))
            card.addView(button("פתח רשימה", palette.accent, Color.WHITE) {
                startActivity(Intent(this, AppManagerActivity::class.java).putExtra("mode", option.third))
            }, LinearLayout.LayoutParams(-1, dp(45)).apply { topMargin = dp(12) })
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(11) })
        }
        val permissionsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = UiComponents.surface(this@AppManagerHubActivity, palette.surface, 17, palette.outline)
        }
        permissionsCard.addView(text("04", 11, palette.accent, true))
        permissionsCard.addView(text("ניהול הרשאות אפליקציות", 18, palette.text, true).apply {
            setPadding(0, dp(6), 0, dp(4))
        })
        permissionsCard.addView(text(
            "הצג הרשאות Android רגילות שכל אפליקציה ביקשה, והענק או בטל אותן דרך ADB או Shizuku. הרשאות מיוחדות נשארות בשליטת הגדרות Android.",
            12, palette.muted, false
        ))
        permissionsCard.addView(button("פתח מנהל הרשאות", palette.accent, Color.WHITE) {
            startActivity(Intent(this, AppPermissionActivity::class.java))
        }, LinearLayout.LayoutParams(-1, dp(45)).apply { topMargin = dp(12) })
        content.addView(permissionsCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(11) })

        page.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(page)
        UiComponents.applySystemBars(this, page, palette)
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean) =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            gravity = Gravity.RIGHT
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun button(value: String, bg: Int, fg: Int, action: () -> Unit): TextView =
        UiComponents.button(this, value, bg, fg,
            if (bg == palette.surfaceAlt) palette.outline else null, 48, 2, action)

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }
}
