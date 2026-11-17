package com.draco.ladb.views

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import com.draco.ladb.utils.ADB
import com.draco.ladb.utils.UiTheme
import com.draco.ladb.utils.UiComponents

/** Redesigned landing screen. Existing LADB tools remain available as separate destinations. */
class DashboardActivity : AppCompatActivity() {
    private val adb by lazy { ADB.getInstance(this) }
    private val palette get() = UiTheme.palette(this)
    private lateinit var connection: TextView
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            showFirstRunGuideIfNeeded()
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        UiTheme.applySavedMode(this)
        super.onCreate(savedInstanceState)
        buildDashboard()
        adb.running.observe(this) { updateConnection(it == true) }
        requestNotificationsThenShowGuide()
    }

    override fun onResume() {
        super.onResume()
        updateConnection(adb.running.value == true)
    }

    private fun buildDashboard() {
        window.statusBarColor = palette.statusBar
        window.navigationBarColor = palette.navigationBar
        window.decorView.systemUiVisibility = if (palette.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundColor(palette.background)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(text("ADB SMART  /  CONTROL", 10, Color.rgb(97, 199, 226), true))
        brand.addView(text("מרכז שליטה לאנדרואיד", 23, palette.text, true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(settingsGearButton(), LinearLayout.LayoutParams(dp(48), dp(48)))
        content.addView(top)

        val tagline = text("חיבור אלחוטי, גיבוי אפליקציות וכלי מערכת — במקום אחד.", 13,
            palette.muted, false)
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
        hero.addView(text("גיבוי ושחזור\nאפליקציות ונתונים", 28, Color.WHITE, true).apply {
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
        content.addView(actionButton("התחברות / שיוך Wireless Debugging", palette.accent) {
            startActivity(Intent(this, ConnectionActivity::class.java))
        }, LinearLayout.LayoutParams(-1, dp(47)).apply { topMargin = dp(8) })

        content.addView(sectionTitle("כלי ADB נוספים"))
        val tools = listOf(
            Tool("ניהול אפליקציות", "הסרה, השבתה והפעלה", Color.rgb(46, 114, 167)) {
                startActivity(Intent(this, AppManagerHubActivity::class.java))
            },
            Tool("פקודות מוכנות", "ספריית פקודות לפי קטגוריות", Color.rgb(42, 132, 122)) {
                startActivity(Intent(this, PresetsActivity::class.java))
            },
            Tool("מסוף ADB + AI", "פקודות, סוכן חכם ופלט", Color.rgb(101, 84, 165)) {
                startActivity(Intent(this, MainActivity::class.java))
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
            background = rounded(palette.surface, dp(16), palette.outline)
        }
        note.addView(text("חשוב לגבי גיבוי נתונים פרטיים", 14, palette.text, true))
        note.addView(text(
            "ADB רגיל אינו עוקף את ארגז החול של Android. תיקיות פרטיות יועתקו רק עם הרשאת Root לאפליקציה, או עם run-as באפליקציה שמאפשרת זאת. בדוח הגיבוי יוצגו במדויק הנתונים שנכללו.",
            12, palette.muted, false
        ).apply { setPadding(0, dp(6), 0, 0) })
        content.addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        content.addView(text("ADB SMART  •  Wireless tools", 10, palette.muted, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        })
        page.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(page)
        UiComponents.applySystemBars(this, page, palette)
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
            background = UiComponents.surface(this@DashboardActivity, palette.surface, 17, palette.outline, clickable = true)
            isClickable = true
            isFocusable = true
            setOnClickListener { tool.action() }
        }
        card.addView(View(this).apply {
            background = rounded(tool.color, dp(8))
        }, LinearLayout.LayoutParams(dp(28), dp(5)))
        card.addView(text(tool.title, 15, palette.text, true).apply {
            setPadding(0, dp(12), 0, dp(4))
        })
        card.addView(text(tool.subtitle, 11, palette.muted, false))
        return card
    }

    private fun sectionTitle(title: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(20), 0, dp(5))
            addView(text(title, 18, palette.text, true))
        }
    }

    private fun settingsGearButton(): TextView =
        UiComponents.button(this, "⚙", palette.surface, palette.accent, palette.outline, 48, 1) {
            startActivity(Intent(this@DashboardActivity, SettingsActivity::class.java))
        }

    private fun requestNotificationsThenShowGuide() {
        val prefs = getSharedPreferences("first_run", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("notification_permission_requested", false)
        ) {
            prefs.edit().putBoolean("notification_permission_requested", true).apply()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            showFirstRunGuideIfNeeded()
        }
    }

    private fun showFirstRunGuideIfNeeded() {
        val prefs = getSharedPreferences("first_run", MODE_PRIVATE)
        if (prefs.getBoolean("guide_shown", false)) return
        val message = "כדי להתחבר בפעם הראשונה:\n\n" +
            "1. אם אפשרויות למפתחים עדיין לא מופעלות: פתח הגדרות Android ← אודות הטלפון והקש 7 פעמים על מספר Build.\n\n" +
            "2. פתח אפשרויות למפתחים והפעל ניפוי באגים אלחוטי.\n\n" +
            "3. בחר „שיוך מכשיר באמצעות קוד” והשאר את חלון הקוד פתוח.\n\n" +
            "4. משוך את סרגל ההתראות, הקלד שם את קוד 6 הספרות ולחץ „אישור אוטומטי”. אין צורך להעתיק פורט או להשתמש במסך מפוצל."
        AlertDialog.Builder(this)
            .setTitle("ברוך הבא ל־ADB חכם – מדריך התחברות")
            .setMessage(message)
            .setPositiveButton("הבנתי, המשך") { _, _ ->
                prefs.edit().putBoolean("guide_shown", true).apply()
            }
            .setNeutralButton("פתח אפשרויות למפתחים") { _, _ ->
                prefs.edit().putBoolean("guide_shown", true).apply()
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            }
            .setNegativeButton("פתח אודות הטלפון") { _, _ ->
                prefs.edit().putBoolean("guide_shown", true).apply()
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS)) }
            }
            .setCancelable(false)
            .show()
    }

    private fun updateConnection(connected: Boolean) {
        if (!::connection.isInitialized) return
        connection.text = if (connected) "●  ADB מחובר — המכשיר מוכן לפקודות"
            else "○  ADB ממתין לחיבור — פתח את מסך השיוך"
        connection.setTextColor(if (connected) Color.rgb(110, 231, 183) else Color.rgb(253, 186, 116))
        connection.background = rounded(
            if (connected) Color.rgb(17, 49, 48) else palette.surfaceAlt, dp(14), palette.outline
        )
    }

    private fun actionButton(label: String, color: Int, action: () -> Unit): TextView =
        UiComponents.button(this, label, color, Color.WHITE, null, 50, 2, action)

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
