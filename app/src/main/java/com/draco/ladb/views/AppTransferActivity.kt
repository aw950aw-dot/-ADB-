package com.draco.ladb.views

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.draco.ladb.backup.AppBackupManager
import com.draco.ladb.services.BackupService
import com.draco.ladb.utils.ADB
import com.draco.ladb.utils.JobProgressHost
import com.draco.ladb.utils.UiTheme
import com.draco.ladb.utils.UiComponents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Landing screen with exactly two clear actions: backup an installed app or restore a ZIP. */
class AppTransferActivity : AppCompatActivity() {
    private val adb by lazy { ADB.getInstance(this) }
    private val palette get() = UiTheme.palette(this)
    private lateinit var status: TextView
    private var pendingRestore: Uri? = null

    private val selectBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreFromUri(uri)
    }

    private lateinit var jobHost: JobProgressHost

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        UiTheme.applySavedMode(this)
        super.onCreate(savedInstanceState)
        buildScreen()
        jobHost = JobProgressHost(this).also { it.attach() }
        adb.running.observe(this) { updateStatus(it == true) }
    }

    override fun onDestroy() {
        jobHost.detach()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        updateStatus(adb.running.value == true)
        val pending = pendingRestore
        if (pending != null && adb.running.value == true) {
            pendingRestore = null
            restoreFromUri(pending)
        }
    }

    private fun buildScreen() {
        window.statusBarColor = palette.statusBar
        window.navigationBarColor = palette.navigationBar
        window.decorView.systemUiVisibility =
            if (palette.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(14), dp(18), dp(22))
            setBackgroundColor(palette.background)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(actionButton("‹", palette.surfaceAlt) { finish() },
            LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(text("גיבוי ושחזור אפליקציות", 20, palette.text, true),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })
        content.addView(header)

        content.addView(text(
            "בחר מה תרצה לעשות. כל פעולה נפתחת במסך ייעודי ופשוט.",
            13, palette.muted, false
        ).apply { setPadding(0, dp(10), 0, dp(18)) })

        val backupCard = actionCard(
            "01",
            "גיבוי אפליקציה",
            "בחירת אפליקציה מהמכשיר ויצירת קובץ ZIP עם קובצי ההתקנה והנתונים הנגישים.",
            "פתח רשימת אפליקציות",
            Color.rgb(35, 119, 190)
        ) {
            startActivity(Intent(this, AppBackupPickerActivity::class.java))
        }
        content.addView(backupCard, LinearLayout.LayoutParams(-1, -2))

        content.addView(actionCard(
            "02",
            "שחזור מקובץ ZIP",
            "דפדוף בקבצים במכשיר, בחירת גיבוי קיים והחזרת האפליקציה והנתונים.",
            "בחר קובץ ZIP",
            Color.rgb(25, 145, 130)
        ) {
            selectBackup.launch(arrayOf("application/zip", "application/octet-stream"))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(13) })

        status = text("בודק חיבור ADB…", 13, palette.muted, true).apply {
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = rounded(palette.surfaceAlt, dp(13), palette.outline)
        }
        content.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        content.addView(actionButton("התחבר ל־Wireless Debugging", palette.accent) {
            startActivity(Intent(this, ConnectionActivity::class.java))
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(9) })

        val note = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = rounded(palette.surface, dp(15), palette.outline)
        }
        note.addView(text("חשוב לדעת", 14, palette.text, true))
        note.addView(text(
            "נתונים פרטיים של אפליקציות רגילות מחייבים הרשאת Root. ללא Root ניתן לכלול APK, קובצי Split ונתונים משותפים שהמערכת מתירה לקרוא. דוח הגיבוי מפרט בדיוק מה נכלל.",
            12, palette.muted, false
        ).apply { setPadding(0, dp(6), 0, 0) })
        content.addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        page.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(page)
        UiComponents.applySystemBars(this, page, palette)
    }

    private fun actionCard(
        number: String,
        title: String,
        description: String,
        buttonLabel: String,
        accent: Int,
        action: () -> Unit
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(17), dp(17), dp(16))
            background = UiComponents.surface(this@AppTransferActivity, palette.surface, 19, palette.outline)
        }
        card.addView(text(number, 11, accent, true))
        card.addView(text(title, 21, palette.text, true).apply {
            setPadding(0, dp(6), 0, dp(5))
        })
        card.addView(text(description, 13, palette.muted, false))
        card.addView(actionButton(buttonLabel, accent, action),
            LinearLayout.LayoutParams(-1, dp(47)).apply { topMargin = dp(14) })
        return card
    }

    private fun restoreFromUri(uri: Uri) {
        if (adb.running.value != true) {
            pendingRestore = uri
            AlertDialog.Builder(this)
                .setTitle("נדרש חיבור Wireless Debugging")
                .setMessage("בחרת קובץ גיבוי. התחבר תחילה ל־Wireless Debugging; לאחר החיבור תוכל להמשיך בשחזור.")
                .setNegativeButton("ביטול") { _, _ -> pendingRestore = null }
                .setPositiveButton("פתח מסך חיבור") { _, _ ->
                    startActivity(Intent(this, ConnectionActivity::class.java))
                }.show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("אישור שחזור")
            .setMessage(
                "השחזור עשוי להתקין APK מתוך קובץ ה־ZIP ולכתוב נתונים במיקומים השייכים לאפליקציה. " +
                    "השתמש רק בגיבוי ממקור שאתה סומך עליו. קבצים קיימים בעלי אותו שם עשויים להידרס, " +
                    "ומפתחות Android Keystore אינם מובטחים להעברה.\n\nלהמשיך?"
            )
            .setNegativeButton("ביטול", null)
            .setPositiveButton("שחזר") { _, _ -> performRestore(uri) }
            .show()
    }

    private fun performRestore(uri: Uri) {
        JobProgressHost.ensureNotificationPermission(this)
        // The job runs in a foreground service and reads the ZIP straight from the chosen file.
        if (!BackupService.startRestore(this, uri)) {
            Toast.makeText(this, "גיבוי או שחזור אחר כבר רץ. המתן לסיומו.", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateStatus(connected: Boolean) {
        if (!::status.isInitialized) return
        status.text = if (connected) "●  ADB מחובר — אפשר לבצע גיבוי ושחזור"
            else "○  ADB לא מחובר — יש להתחבר לפני ביצוע פעולות"
        status.setTextColor(if (connected) Color.rgb(22, 155, 107) else Color.rgb(207, 137, 51))
    }

    private fun actionButton(label: String, color: Int, action: () -> Unit): TextView =
        UiComponents.button(this, label, color, Color.WHITE, null, 48, 2, action)

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
