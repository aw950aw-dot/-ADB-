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
import com.draco.ladb.utils.PairingNotification
import com.draco.ladb.utils.AppDrawer
import androidx.drawerlayout.widget.DrawerLayout

/** Redesigned landing screen. Existing LADB tools remain available as separate destinations. */
class DashboardActivity : AppCompatActivity() {
    private val adb by lazy { ADB.getInstance(this) }
    private val palette get() = UiTheme.palette(this)
    private lateinit var connection: TextView
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            showPairingNotificationIfNeeded()
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
        window.decorView.systemUiVisibility =
            if (palette.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val drawer = AppDrawer.create(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(14), dp(18), dp(22))
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
        brand.addView(text("ADB SMART  •  DEVICE CONTROL", 9, palette.accent, true))
        brand.addView(text("מרכז שליטה למכשיר", 24, palette.text, true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(AppDrawer.menuButton(this, drawer), LinearLayout.LayoutParams(dp(48), dp(48)))
        content.addView(top)
        content.addView(text(
            "כלי חיבור, גיבוי וניהול Android — במסך אחד.",
            13, palette.muted, false
        ).apply { setPadding(0, dp(6), 0, dp(16)) })

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(19), dp(19), dp(19), dp(19))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(26, 103, 166), Color.rgb(20, 70, 123), Color.rgb(12, 39, 72))
            ).apply { cornerRadius = dp(24).toFloat() }
            elevation = dp(3).toFloat()
        }
        hero.addView(text("BACKUP CENTER  /  01", 10, Color.rgb(143, 219, 255), true))
        hero.addView(text("גיבוי ושחזור\nאפליקציות ונתונים", 27, Color.WHITE, true).apply {
            setPadding(0, dp(9), 0, dp(7))
        })
        hero.addView(text(
            "צור קובץ ZIP להעברה בין מכשירים, כולל APK, קובצי Split ונתונים שהמערכת מאפשרת לגבות.",
            13, Color.rgb(220, 236, 251), false
        ))
        hero.addView(actionButton("פתח מרכז גיבוי ושחזור  ←", Color.rgb(8, 29, 52)) {
            startActivity(Intent(this, AppTransferActivity::class.java))
        }, LinearLayout.LayoutParams(-1, dp(51)).apply { topMargin = dp(16) })
        content.addView(hero)

        val statusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = UiComponents.surface(this@DashboardActivity, palette.surface, 19, palette.outline)
            elevation = dp(2).toFloat()
        }
        val statusHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusHeader.addView(text("WIRELESS DEBUGGING", 9, palette.accent, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        statusHeader.addView(text("●  חיבור", 11, palette.muted, true))
        statusPanel.addView(statusHeader)
        connection = text("בודק חיבור ADB…", 13, palette.text, true).apply {
            setPadding(dp(11), dp(12), dp(11), dp(12))
            background = rounded(palette.surfaceAlt, dp(12), palette.outline)
        }
        statusPanel.addView(connection, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
        statusPanel.addView(actionButton("התחברות / שיוך Wireless Debugging", palette.accent) {
            startActivity(Intent(this, ConnectionActivity::class.java))
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        content.addView(statusPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        content.addView(sectionTitle("כלי עבודה"))
        val tools = listOf(
            Tool("ניהול אפליקציות", "הסרה, השבתה והפעלה", Color.rgb(42, 126, 190)) {
                startActivity(Intent(this, AppManagerHubActivity::class.java))
            },
            Tool("פקודות מוכנות", "פעולות מערכת מסודרות", Color.rgb(25, 145, 130)) {
                startActivity(Intent(this, PresetsActivity::class.java))
            },
            Tool("מסוף ADB + AI", "פקודות, סוכן חכם ופלט", Color.rgb(111, 94, 188)) {
                startActivity(Intent(this, MainActivity::class.java))
            },
            Tool("עזרה והדרכה", "הוראות שימוש וחיבור", Color.rgb(194, 124, 48)) {
                startActivity(Intent(this, HelpActivity::class.java))
            }
        )
        tools.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
            }
            pair.forEach { tool ->
                row.addView(toolCard(tool), LinearLayout.LayoutParams(0, dp(126), 1f).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                })
            }
            content.addView(row)
        }

        val note = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = UiComponents.surface(this@DashboardActivity, palette.surface, 17, palette.outline)
        }
        note.addView(text("גיבוי נתונים פרטיים", 14, palette.text, true))
        note.addView(text(
            "נתונים פרטיים של אפליקציות רגילות מחייבים הרשאת Root. ללא Root ניתן לגבות APK ונתונים משותפים שהמערכת מתירה לקרוא. דוח הגיבוי מפרט מה נכלל ומה לא.",
            12, palette.muted, false
        ).apply { setPadding(0, dp(6), 0, 0) })
        content.addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(13) })

        content.addView(text("ADB SMART  •  Wireless tools", 10, palette.muted, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        })
        page.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        AppDrawer.attach(this, drawer, page)
        setContentView(drawer)
        UiComponents.applySystemBars(this, drawer, palette)
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
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = UiComponents.surface(this@DashboardActivity, palette.surface, 19, palette.outline, clickable = true)
            elevation = dp(2).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { tool.action() }
        }
        val glyph = when (tool.title) {
            "ניהול אפליקציות" -> "▦"
            "פקודות מוכנות" -> "⌘"
            "מסוף ADB + AI" -> "›_"
            else -> "?"
        }
        card.addView(TextView(this).apply {
            text = glyph
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(tool.color, dp(12))
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        card.addView(text(tool.title, 15, palette.text, true).apply {
            setPadding(0, dp(10), 0, dp(3))
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

    private fun requestNotificationsThenShowGuide() {
        val prefs = getSharedPreferences("first_run", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("notification_permission_requested", false)
        ) {
            prefs.edit().putBoolean("notification_permission_requested", true).apply()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            showPairingNotificationIfNeeded()
            showFirstRunGuideIfNeeded()
        }
    }

    private fun showPairingNotificationIfNeeded() {
        // Do not leave an idle, persistent notification in the shade.
        // The connection screen starts discovery only when the user opens pairing settings.
        if (adb.running.value != true) PairingNotification.cancel(this)
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
            .setNeutralButton("פתח מסך חיבור") { _, _ ->
                prefs.edit().putBoolean("guide_shown", true).apply()
                startActivity(Intent(this@DashboardActivity, ConnectionActivity::class.java))
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
            else "○  ADB ממתין לחיבור — אפשר להזין קוד מההתראה"
        connection.setTextColor(if (connected) Color.rgb(110, 231, 183) else Color.rgb(253, 186, 116))
        connection.background = rounded(
            if (connected) Color.rgb(17, 49, 48) else palette.surfaceAlt, dp(14), palette.outline
        )
        if (connected) PairingNotification.cancel(this) else showPairingNotificationIfNeeded()
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
