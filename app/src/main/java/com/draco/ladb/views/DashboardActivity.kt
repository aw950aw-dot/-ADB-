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
import com.draco.ladb.utils.ADB

/** Redesigned landing screen. Existing LADB tools remain available as separate destinations. */
class DashboardActivity : AppCompatActivity() {
    private val adb by lazy { ADB.getInstance(this) }
    private lateinit var connection: TextView

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildDashboard()
        adb.running.observe(this) { updateConnection(it == true) }
    }

    override fun onResume() {
        super.onResume()
        updateConnection(adb.running.value == true)
    }

    private fun buildDashboard() {
        window.statusBarColor = Color.rgb(8, 14, 27)
        window.navigationBarColor = Color.rgb(8, 14, 27)
        window.decorView.systemUiVisibility = 0

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundColor(Color.rgb(8, 14, 27))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(text("ADB SMART  /  CONTROL", 10, Color.rgb(97, 199, 226), true))
        brand.addView(text("מרכז שליטה לאנדרואיד", 23, Color.WHITE, true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(actionButton("⚙", Color.rgb(26, 39, 60)) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        content.addView(top)

        val tagline = text("חיבור אלחוטי, גיבוי אפליקציות וכלי מערכת — במקום אחד.", 13,
            Color.rgb(142, 161, 188), false)
        tagline.setPadding(0, dp(7), 0, dp(16))
        content.addView(tagline)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(19), dp(19), dp(19), dp(19))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(31, 91, 145), Color.rgb(26, 62, 105), Color.rgb(23, 43, 75))
            ).apply { cornerRadius = dp(24).toFloat() }
        }
        val pill = text("הפעולה המרכזית", 11, Color.rgb(179, 230, 255), true).apply {
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = rounded(Color.rgb(31, 104, 154), dp(18))
        }
        hero.addView(pill)
        hero.addView(text("גיבוי ושחזור
אפליקציות ונתונים", 28, Color.WHITE, true).apply {
            setPadding(0, dp(10), 0, dp(6))
        })
        hero.addView(text(
            "יצירת ZIP להעברה בין מכשירים: APK, קובצי Split ונתונים שניתן לגשת אליהם באמצעות ADB או Root.",
            13, Color.rgb(212, 229, 247), false
        ))
        hero.addView(actionButton("פתח מרכז גיבוי ושחזור  →", Color.rgb(11, 21, 38)) {
            startActivity(Intent(this, AppTransferActivity::class.java))
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(16) })
        content.addView(hero)

        connection = text("בודק חיבור Wireless Debugging…", 13, Color.rgb(253, 186, 116), true).apply {
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = rounded(Color.rgb(40, 34, 34), dp(14))
        }
        content.addView(connection, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(13)
        })
        content.addView(actionButton("התחברות / שיוך Wireless Debugging", Color.rgb(33, 55, 83)) {
            startActivity(Intent(this, MainActivity::class.java))
        }, LinearLayout.LayoutParams(-1, dp(47)).apply { topMargin = dp(8) })

        content.addView(sectionTitle("כלי ADB נוספים", "כל היכולות הקיימות נשארות זמינות"))
        val tools = listOf(
            Tool("ניהול אפליקציות", "הסרה, השבתה והפעלה", Color.rgb(46, 114, 167)) {
                startActivity(Intent(this, AppManagerActivity::class.java).putExtra("mode", "remove"))
            },
            Tool("פקודות מוכנות", "ספריית פקודות לפי קטגוריות", Color.rgb(42, 132, 122)) {
                startActivity(Intent(this, PresetsActivity::class.java))
            },
            Tool("מסוף ADB + AI", "פקודות, סוכן חכם ופלט", Color.rgb(101, 84, 165)) {
                startActivity(Intent(this, MainActivity::class.java))
            },
            Tool("הגדרות", "חיבור, מודלים והעדפות", Color.rgb(150, 104, 52)) {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        )
        tools.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { tool ->
                row.addView(toolCard(tool), LinearLayout.LayoutParams(0, dp(112), 1f).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, dp(112), 1f))
            content.addView(row)
        }

        val note = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = rounded(Color.rgb(16, 27, 43), dp(16), Color.rgb(34, 52, 75))
        }
        note.addView(text("חשוב לגבי גיבוי נתונים פרטיים", 14, Color.rgb(225, 236, 249), true))
        note.addView(text(
            "ADB רגיל אינו עוקף את ארגז החול של Android. תיקיות פרטיות יועתקו רק עם Root, או עם run-as באפליקציה שמאפשרת זאת. בדוח הגיבוי יוצגו במדויק הנתונים שנכללו.",
            12, Color.rgb(148, 166, 190), false
        ).apply { setPadding(0, dp(6), 0, 0) })
        content.addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        content.addView(text("ADB SMART  •  Wireless tools", 10, Color.rgb(92, 112, 140), true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        })
        page.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(page)
    }

    private data class Tool(
        val title: String,
        val subtitle: String,
        val color: Int,
        val action: () -> Unit
    )

    private fun toolCard(tool: Tool): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = rounded(Color.rgb(18, 30, 48), dp(17), Color.rgb(40, 57, 81))
            isClickable = true
            isFocusable = true
            setOnClickListener { tool.action() }
        }
        card.addView(View(this).apply {
            background = rounded(tool.color, dp(8))
        }, LinearLayout.LayoutParams(dp(28), dp(5)))
        card.addView(text(tool.title, 15, Color.WHITE, true).apply {
            setPadding(0, dp(12), 0, dp(4))
        })
        card.addView(text(tool.subtitle, 11, Color.rgb(145, 164, 190), false))
        return card
    }

    private fun sectionTitle(title: String, subtitle: String): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(20), 0, dp(5))
        }
        wrap.addView(text(title, 18, Color.WHITE, true))
        wrap.addView(text(subtitle, 11, Color.rgb(133, 153, 179), false).apply {
            setPadding(0, dp(3), 0, 0)
        })
        return wrap
    }

    private fun updateConnection(connected: Boolean) {
        if (!::connection.isInitialized) return
        connection.text = if (connected) "●  ADB מחובר — המכשיר מוכן לפקודות"
            else "○  ADB ממתין לחיבור — פתח את מסך השיוך"
        connection.setTextColor(if (connected) Color.rgb(110, 231, 183) else Color.rgb(253, 186, 116))
        connection.background = rounded(
            if (connected) Color.rgb(17, 49, 48) else Color.rgb(40, 34, 34), dp(14)
        )
    }

    private fun actionButton(label: String, color: Int, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(color, dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun text(value: String, size: Int, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            gravity = Gravity.RIGHT
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }
}
