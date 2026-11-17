package com.draco.ladb.views

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.activity.viewModels
import com.draco.ladb.viewmodels.MainActivityViewModel
import com.draco.ladb.utils.UiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Focused Wireless Debugging entry point. No ADB terminal or tools screen is opened. */
class ConnectionActivity : AppCompatActivity() {
    private val vm: MainActivityViewModel by viewModels()
    private val palette get() = UiTheme.palette(this)
    private lateinit var status: TextView
    private lateinit var port: EditText
    private lateinit var code: EditText
    private lateinit var pairPanel: LinearLayout
    private lateinit var pairButton: TextView
    private var pairing = false

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        UiTheme.applySavedMode(this)
        super.onCreate(savedInstanceState)
        buildScreen()
        vm.adb.running.observe(this) { connected ->
            if (connected == true) {
                status.text = "●  מחובר ומוכן לפקודות"
                status.setTextColor(Color.rgb(25, 155, 105))
                pairButton.text = "מחובר"
                pairButton.isEnabled = false
            } else if (!pairing) {
                status.text = "○  ממתין לחיבור Wireless Debugging"
                status.setTextColor(palette.muted)
                pairButton.text = if (vm.needsToPair()) "שיוך וחיבור" else "התחבר ל־ADB"
                pairButton.isEnabled = true
            }
        }

        if (vm.adb.running.value == true) {
            status.text = "●  מחובר ומוכן לפקודות"
            status.setTextColor(Color.rgb(25, 155, 105))
            pairButton.text = "מחובר"
            pairButton.isEnabled = false
        } else if (vm.needsToPair()) {
            pairPanel.visibility = View.VISIBLE
            status.text = "נדרש שיוך ראשוני עם קוד בן 6 ספרות"
        } else {
            pairPanel.visibility = View.GONE
            startConnection()
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
            setPadding(dp(18), dp(13), dp(18), dp(22))
            setBackgroundColor(palette.background)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(actionButton("‹", palette.surfaceAlt, Color.WHITE) { finish() },
            LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(text("חיבור Wireless Debugging", 20, palette.text, true),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })
        content.addView(header)

        content.addView(text(
            "המסך הזה מיועד רק לחיבור ולשיוך. לאחר החיבור אפשר לחזור לגיבוי או להיכנס למסוף ADB בנפרד.",
            13, palette.muted, false
        ).apply { setPadding(0, dp(10), 0, dp(16)) })

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(palette.surface, dp(17), palette.outline)
        }
        info.addView(text("כך מתחברים", 17, palette.text, true))
        info.addView(text(
            "1. פתח אפשרויות למפתחים והפעל ניפוי באגים אלחוטי.\n" +
                "2. בחר „שיוך מכשיר באמצעות קוד”.\n" +
                "3. השאר את חלון השיוך פתוח והזן את הפורט וקוד בן 6 ספרות כאן.",
            13, palette.muted, false
        ).apply { setPadding(0, dp(8), 0, 0) })
        content.addView(info)

        status = text("בודק מצב חיבור…", 13, palette.muted, true).apply {
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = rounded(palette.surfaceAlt, dp(13), palette.outline)
        }
        content.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(13) })

        pairPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(15), dp(15), dp(15))
            background = rounded(palette.surface, dp(17), palette.outline)
        }
        pairPanel.addView(text("פרטי השיוך", 16, palette.text, true))
        port = inputField("פורט שיוך (לדוגמה 39953)", android.text.InputType.TYPE_CLASS_NUMBER)
        code = inputField("קוד שיוך בן 6 ספרות", android.text.InputType.TYPE_CLASS_NUMBER)
        pairPanel.addView(port, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
        pairPanel.addView(code, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(9) })
        pairPanel.addView(text(
            "חשוב: זהו פורט השיוך מחלון הקוד, לא הפורט שמוצג במסך הראשי של Wireless Debugging.",
            11, palette.muted, false
        ).apply { setPadding(0, dp(8), 0, 0) })
        content.addView(pairPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(13) })

        pairButton = actionButton("התחבר ל־ADB", palette.accent, Color.WHITE) { connectOrPair() }
        content.addView(pairButton, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(13) })

        content.addView(actionButton("פתח הגדרות מפתחים / Wireless Debugging", palette.surfaceAlt, palette.text) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "לא ניתן לפתוח את הגדרות המפתחים במכשיר הזה.", Toast.LENGTH_LONG).show()
            }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(9) })

        content.addView(actionButton("שיוך מחדש", palette.surfaceAlt, palette.text) {
            pairPanel.visibility = View.VISIBLE
            port.requestFocus()
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })

        page.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(page)
    }

    private fun connectOrPair() {
        if (pairing || vm.adb.running.value == true) return
        if (pairPanel.visibility == View.VISIBLE || vm.needsToPair()) {
            val portValue = port.text.toString().trim()
            val codeValue = code.text.toString().trim()
            if (portValue.toIntOrNull() == null || codeValue.length != 6 || codeValue.any { !it.isDigit() }) {
                Toast.makeText(this, "יש להזין פורט תקין וקוד בן 6 ספרות.", Toast.LENGTH_LONG).show()
                return
            }
            pairing = true
            pairButton.isEnabled = false
            pairButton.text = "משייך את המכשיר…"
            status.text = "מבצע שיוך מול Wireless Debugging…"
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { vm.adb.pair(portValue, codeValue) }.getOrDefault(false)
                }
                pairing = false
                if (result) {
                    vm.setPairedBefore(true)
                    pairPanel.visibility = View.GONE
                    status.text = "השיוך הצליח. מתחבר ל־ADB…"
                    pairButton.text = "מתחבר…"
                    startConnection()
                } else {
                    pairButton.isEnabled = true
                    pairButton.text = "נסה שיוך שוב"
                    status.text = "השיוך נכשל. ודא שהחלון עדיין פתוח ושהקוד והפורט נכונים."
                }
            }
        } else {
            startConnection()
        }
    }

    private fun startConnection() {
        if (vm.adb.running.value == true) return
        status.text = "מתחבר ל־ADB… השאר את Wireless Debugging פעיל."
        pairButton.isEnabled = false
        pairButton.text = "מתחבר…"
        vm.startADBServer { success ->
            runOnUiThread {
                if (success) {
                    status.text = "●  מחובר ומוכן לפקודות"
                    status.setTextColor(Color.rgb(25, 155, 105))
                    pairButton.text = "מחובר"
                } else {
                    status.text = "החיבור לא הצליח. בדוק שניפוי באגים אלחוטי פעיל ונסה שוב."
                    pairButton.text = "נסה להתחבר שוב"
                    pairButton.isEnabled = true
                }
            }
        }
    }

    private fun inputField(hintValue: String, type: Int): EditText =
        EditText(this).apply {
            hint = hintValue
            inputType = type
            setSingleLine(true)
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
            setTextColor(palette.text)
            setHintTextColor(palette.muted)
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(palette.inputBackground, dp(12), palette.outline)
        }

    private fun text(value: String, size: Int, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            gravity = Gravity.RIGHT
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun actionButton(label: String, bg: Int, fg: Int, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(fg)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(bg, dp(13), if (bg == palette.surfaceAlt) palette.outline else null)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }
}
