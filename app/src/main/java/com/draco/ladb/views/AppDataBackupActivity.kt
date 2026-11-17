package com.draco.ladb.views

import android.app.ProgressDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.drawerlayout.widget.DrawerLayout
import com.draco.ladb.backup.AppDataBackupManager
import com.draco.ladb.utils.AppDrawer
import com.draco.ladb.utils.UiTheme
import com.draco.ladb.utils.UiComponents
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
        if (uri != null) promptRestorePassword(uri)
    }

    private fun dp(value: Int) = UiComponents.dp(this, value)

    override fun onCreate(savedInstanceState: Bundle?) {
        UiTheme.applySavedMode(this)
        super.onCreate(savedInstanceState)

        val drawer = AppDrawer.create(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(14), dp(18), dp(24))
            setBackgroundColor(palette.background)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(AppDrawer.menuButton(this, drawer),
            LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(text("גיבוי נתוני ADB SMART", 21, palette.text, true),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })
        content.addView(header)
        content.addView(text(
            "בחר פעולה. בשחזור בוחרים קודם את קובץ הגיבוי, ורק אחר כך מזינים את הסיסמה שלו.",
            13, palette.muted, false
        ).apply { setPadding(0, dp(9), 0, dp(17)) })

        val createCard = card()
        createCard.addView(text("יצירת גיבוי מוצפן", 17, palette.text, true))
        createCard.addView(text(
            "בחר סיסמה חדשה לקובץ הגיבוי. הסיסמה אינה נשמרת בתוך הקובץ — היא משמשת להצפנה.",
            12, palette.muted, false
        ).apply { setPadding(0, dp(5), 0, dp(5)) })
        password = field("סיסמה חדשה — לפחות 8 תווים")
        createCard.addView(password, LinearLayout.LayoutParams(-1, dp(52)).apply {
            topMargin = dp(7)
        })
        createCard.addView(text("אימות סיסמה", 12, palette.muted, false).apply {
            setPadding(0, dp(9), 0, dp(3))
        })
        confirmation = field("הקלד שוב את הסיסמה")
        createCard.addView(confirmation, LinearLayout.LayoutParams(-1, dp(52)))
        createCard.addView(button("בחר מיקום ושמור קובץ גיבוי") {
            val pass = password.text.toString()
            if (pass.length < 8 || confirmation.text.toString() != pass) {
                ToastMessage.show(this, "בחר סיסמה בת 8 תווים לפחות ואמת אותה.")
            } else {
                createDocument.launch("ADB-SMART-backup.adbbackup")
            }
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(13) })
        content.addView(createCard)

        val restoreCard = card()
        restoreCard.addView(text("שחזור מגיבוי קיים", 17, palette.text, true))
        restoreCard.addView(text(
            "אין צורך להזין סיסמה מראש. בחר תחילה את קובץ ‎.adbbackup; לאחר בחירתו תופיע בקשה לסיסמה המתאימה לקובץ הזה.",
            12, palette.muted, false
        ).apply { setPadding(0, dp(5), 0, dp(5)) })
        restoreCard.addView(button("בחר קובץ גיבוי לשחזור") {
            openDocument.launch(arrayOf("*/*"))
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) })
        content.addView(restoreCard, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12)
        })

        val warning = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = UiComponents.surface(this@AppDataBackupActivity, palette.surfaceAlt, 16, palette.outline)
        }
        warning.addView(text("חשוב מאוד", 14, palette.text, true))
        warning.addView(text(
            "בלי הסיסמה שבה הוצפן הקובץ לא ניתן לשחזר אותו. שמור את הסיסמה במקום בטוח. אחרי שחזור יש לסגור את ADB SMART לגמרי ולפתוח אותה מחדש כדי לטעון את כל ההגדרות והמפתחות.",
            12, palette.muted, false
        ).apply { setPadding(0, dp(6), 0, 0) })
        content.addView(warning, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(14)
        })

        page.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        AppDrawer.attach(this, drawer, page)
        setContentView(drawer)
        UiComponents.applySystemBars(this, drawer, palette)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = UiComponents.surface(this@AppDataBackupActivity, palette.surface, 19, palette.outline)
    }

    private fun createBackup(uri: Uri) {
        val pass = password.text.toString()
        val progress = ProgressDialog.show(
            this,
            "יוצר גיבוי",
            "אוסף ומצפין את נתוני האפליקציה…",
            true,
            false
        )
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
                .setMessage(
                    if (result.isSuccess) "קובץ הגיבוי המוצפן נשמר בהצלחה."
                    else (result.exceptionOrNull()?.message ?: "שגיאה לא ידועה")
                )
                .setPositiveButton("סגור", null)
                .show()
        }
    }

    private fun promptRestorePassword(uri: Uri) {
        val input = field("סיסמת הקובץ שנבחר")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(2))
            addView(text(
                "הקלד את הסיסמה שבה נוצר קובץ הגיבוי שבחרת.",
                13,
                palette.muted,
                false
            ))
            addView(input, LinearLayout.LayoutParams(-1, dp(52)).apply {
                topMargin = dp(10)
            })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("סיסמת קובץ הגיבוי")
            .setView(box)
            .setNegativeButton("ביטול", null)
            .setPositiveButton("המשך לשחזור", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pass = input.text.toString()
                if (pass.length < 8) {
                    input.error = "יש להזין סיסמה בת 8 תווים לפחות"
                    return@setOnClickListener
                }
                dialog.dismiss()
                restoreBackup(uri, pass)
            }
        }
        dialog.show()
    }

    private fun restoreBackup(uri: Uri, pass: String) {
        val progress = ProgressDialog.show(
            this,
            "משחזר נתונים",
            "מפענח ובודק את קובץ הגיבוי…",
            true,
            false
        )
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
                    if (result.isSuccess) {
                        "הנתונים שוחזרו. סגור את ADB SMART לגמרי ופתח אותה מחדש כדי לטעון את כל ההגדרות והמפתחות."
                    } else {
                        result.exceptionOrNull()?.message ?: "לא ניתן לשחזר את הקובץ."
                    }
                )
                .setPositiveButton("הבנתי", null)
                .show()
        }
    }

    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        setSingleLine(true)
        textDirection = View.TEXT_DIRECTION_LTR
        gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
        setPadding(dp(12), 0, dp(12), 0)
        setTextColor(palette.text)
        setHintTextColor(palette.muted)
        background = UiComponents.shape(this@AppDataBackupActivity, palette.inputBackground, 12, palette.outline)
    }

    private fun button(label: String, action: () -> Unit): TextView =
        UiComponents.button(this, label, palette.accent, Color.WHITE, null, 48, 2, action)

    private fun text(value: String, size: Int, color: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        gravity = Gravity.RIGHT
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
}

/** Keeps this screen independent from Toast implementation imports and layout details. */
private object ToastMessage {
    fun show(context: android.content.Context, message: String) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
    }
}
