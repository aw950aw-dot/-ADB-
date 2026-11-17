package com.draco.ladb.views

import android.app.ProgressDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.draco.ladb.backup.AppDataBackupManager
import com.draco.ladb.utils.UiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Export/import of the app's own preferences, private files, databases and AI keys. */
class AppDataBackupActivity : AppCompatActivity() {
    private val palette get() = UiTheme.palette(this)
    private lateinit var password: EditText
    private lateinit var confirmation: EditText

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) createBackup(uri)
    }

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) restoreBackup(uri)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        UiTheme.applySavedMode(this)
        super.onCreate(savedInstanceState)
        window.statusBarColor = palette.statusBar
        window.navigationBarColor = palette.navigationBar
        window.decorView.systemUiVisibility =
            if (palette.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(16), dp(18), dp(24))
            setBackgroundColor(palette.background)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        content.addView(button("‹  חזרה") { finish() }, LinearLayout.LayoutParams(-1, dp(44)))
        content.addView(text("גיבוי נתוני ADB SMART", 24, palette.text, true).apply {
            setPadding(0, dp(12), 0, dp(6))
        })
        content.addView(text(
            "הגיבוי כולל הגדרות, מפתחות API, העדפות עיצוב, קבצים ומסדי נתונים של האפליקציה. אפשר לשחזר אותו אחרי התקנה מחדש.",
            13, palette.muted, false
        ).apply { setPadding(0, 0, 0, dp(14)) })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(palette.surface, dp(17), palette.outline)
        }
        card.addView(text("סיסמת הגיבוי", 15, palette.text, true))
        password = field("סיסמה — לפחות 8 תווים")
        card.addView(password, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
        card.addView(text("אימות סיסמה (נדרש רק ביצירת גיבוי)", 12, palette.muted, false).apply {
            setPadding(0, dp(10), 0, dp(3))
        })
        confirmation = field("הקלד שוב את הסיסמה")
        card.addView(confirmation, LinearLayout.LayoutParams(-1, dp(52)))
        card.addView(button("צור קובץ גיבוי מוצפן") {
            val pass = password.text.toString()
            if (pass.length < 8 || confirmation.text.toString() != pass) {
                Toast.makeText(this, "בחר סיסמה בת 8 תווים לפחות ואמת אותה.", Toast.LENGTH_LONG).show()
            } else {
                createDocument.launch("ADB-SMART-backup.adbbackup")
            }
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(14) })
        card.addView(button("שחזר מקובץ גיבוי") {
            if (password.text.toString().length < 8) {
                Toast.makeText(this, "הזן את סיסמת הגיבוי תחילה.", Toast.LENGTH_LONG).show()
            } else {
                openDocument.launch(arrayOf("*/*"))
            }
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(9) })
        content.addView(card)

        val warning = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = rounded(palette.surfaceAlt, dp(15), palette.outline)
        }
        warning.addView(text("חשוב מאוד", 14, palette.text, true))
        warning.addView(text(
            "קובץ הגיבוי מוצפן בסיסמה שבחרת. בלי הסיסמה לא ניתן לשחזר אותו. שמור את הקובץ ואת הסיסמה במקום בטוח. אחרי שחזור יש לסגור את ADB SMART לגמרי ולפתוח מחדש כדי שכל ההגדרות ייטענו.",
            12, palette.muted, false
        ).apply { setPadding(0, dp(6), 0, 0) })
        content.addView(warning, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        page.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(page)
    }

    private fun createBackup(uri: android.net.Uri) {
        val pass = password.text.toString()
        val progress = ProgressDialog.show(this, "יוצר גיבוי", "אוסף ומצפין את נתוני האפליקציה…", true, false)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        AppDataBackupManager.createBackup(this@AppDataBackupActivity, output, pass)
                    } ?: error("לא ניתן ליצור את קובץ הגיבוי.")
                }
            }
            progress.dismiss()
            AlertDialog.Builder(this@AppDataBackupActivity)
                .setTitle(if (result.isSuccess) "הגיבוי הושלם" else "יצירת הגיבוי נכשלה")
                .setMessage(if (result.isSuccess) "קובץ הגיבוי המוצפן נשמר בהצלחה." else (result.exceptionOrNull()?.message ?: "שגיאה לא ידועה"))
                .setPositiveButton("סגור", null)
                .show()
        }
    }

    private fun restoreBackup(uri: android.net.Uri) {
        val pass = password.text.toString()
        val progress = ProgressDialog.show(this, "משחזר נתונים", "מפענח ומשחזר את נתוני האפליקציה…", true, false)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        AppDataBackupManager.restoreBackup(this@AppDataBackupActivity, input, pass)
                    } ?: error("לא ניתן לקרוא את קובץ הגיבוי.")
                }
            }
            progress.dismiss()
            AlertDialog.Builder(this@AppDataBackupActivity)
                .setTitle(if (result.isSuccess) "השחזור הושלם" else "השחזור נכשל")
                .setMessage(
                    if (result.isSuccess) "הנתונים שוחזרו. סגור את ADB SMART לגמרי ופתח אותה מחדש כדי לטעון את כל ההגדרות והמפתחות."
                    else (result.exceptionOrNull()?.message ?: "לא ניתן לשחזר את הקובץ.")
                )
                .setPositiveButton("הבנתי", null)
                .show()
        }
    }

    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        setSingleLine(true)
        textDirection = View.TEXT_DIRECTION_LTR
        gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
        setPadding(dp(12), 0, dp(12), 0)
        setTextColor(palette.text)
        setHintTextColor(palette.muted)
        background = rounded(palette.inputBackground, dp(12), palette.outline)
    }

    private fun button(label: String, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(palette.text)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = rounded(palette.surfaceAlt, dp(13), palette.outline)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        gravity = Gravity.RIGHT
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }
}
