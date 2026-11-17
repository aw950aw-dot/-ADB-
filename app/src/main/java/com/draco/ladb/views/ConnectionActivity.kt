package com.draco.ladb.views

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.RemoteInput
import androidx.lifecycle.lifecycleScope
import androidx.activity.viewModels
import com.draco.ladb.viewmodels.MainActivityViewModel
import com.draco.ladb.utils.PairingNotification
import com.draco.ladb.utils.PairingPortDiscovery
import com.draco.ladb.utils.UiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Wireless Debugging pairing screen. The notification supplies the code; the app discovers the pairing port. */
class ConnectionActivity : AppCompatActivity() {
    private val vm: MainActivityViewModel by viewModels()
    private val palette get() = UiTheme.palette(this)
    private lateinit var status: TextView
    private lateinit var code: EditText
    private lateinit var port: EditText
    private lateinit var pairPanel: LinearLayout
    private lateinit var pairButton: TextView
    private var pairing = false
    private var pendingSettingsLaunch = false
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                PairingNotification.show(this)
                if (pendingSettingsLaunch) {
                    pendingSettingsLaunch = false
                    launchDeveloperSettingsWindow()
                }
            } else {
                pendingSettingsLaunch = false
                Toast.makeText(this, "כדי להשתמש באישור מתוך ההתראה יש לאפשר התראות ל־ADB SMART.", Toast.LENGTH_LONG).show()
            }
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        UiTheme.applySavedMode(this)
        super.onCreate(savedInstanceState)
        buildScreen()
        vm.adb.running.observe(this) { connected ->
            if (connected == true) {
                PairingNotification.cancel(this)
                status.text = "●  מחובר ומוכן לפקודות"
                status.setTextColor(Color.rgb(25, 155, 105))
                pairButton.text = "מחובר"
                pairButton.isEnabled = false
            } else if (!pairing) {
                status.text = "○  ממתין לחיבור Wireless Debugging"
                status.setTextColor(palette.muted)
                pairButton.text = if (vm.needsToPair()) "אישור קוד וחיבור" else "התחבר ל־ADB"
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
            status.text = "נדרש שיוך ראשוני — אפשר להזין את הקוד בהתראה שמופיעה למעלה"
            showPairingNotification()
        } else {
            pairPanel.visibility = View.GONE
            startConnection()
        }
        handlePairingReply(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingReply(intent)
    }

    private fun handlePairingReply(intent: Intent?) {
        if (intent == null) return
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PairingNotification.INPUT_KEY)
            ?.toString()
            ?.filter { it.isDigit() }
            .orEmpty()
        if (reply.isNotBlank()) {
            code.setText(reply)
            pairWithCode(reply, port.text.toString().trim())
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
        header.addView(actionButton("‹", palette.surfaceAlt, palette.text) { finish() },
            LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(text("חיבור Wireless Debugging", 20, palette.text, true),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })
        content.addView(header)

        content.addView(text(
            "פותחים את מסך השיוך של Android ומשאירים אותו פתוח. הקוד יוזן דרך ההתראה — בלי מסך מפוצל ובלי להעתיק פורט.",
            13, palette.muted, false
        ).apply { setPadding(0, dp(10), 0, dp(16)) })

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(palette.surface, dp(17), palette.outline)
        }
        info.addView(text("איך מתחברים", 17, palette.text, true))
        info.addView(text(
            "1. הפעל ניפוי באגים אלחוטי באפשרויות למפתחים.\n" +
                "2. בחר „שיוך מכשיר באמצעות קוד”.\n" +
                "3. משוך את סרגל ההתראות, הקלד את קוד 6 הספרות בהתראה ולחץ „אישור אוטומטי”.\n" +
                "האפליקציה תאתר בעצמה את פורט השיוך ותבצע את החיבור.",
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
        pairPanel.addView(text("אפשרות להזנת קוד ידנית", 16, palette.text, true))
        code = inputField("קוד שיוך בן 6 ספרות", android.text.InputType.TYPE_CLASS_NUMBER)
        code.filters = arrayOf(InputFilter.LengthFilter(6))
        pairPanel.addView(code, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
        port = inputField("פורט שיוך (אופציונלי — לגיבוי ידני)", android.text.InputType.TYPE_CLASS_NUMBER)
        port.filters = arrayOf(InputFilter.LengthFilter(5))
        pairPanel.addView(port, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(9) })
        pairPanel.addView(text(
            "אפשר להשאיר את הפורט ריק לגילוי אוטומטי, או להזין אותו ידנית אם הגילוי האוטומטי נכשל.",
            11, palette.muted, false
        ).apply { setPadding(0, dp(8), 0, 0) })
        content.addView(pairPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(13) })

        pairButton = actionButton("אישור קוד וחיבור", palette.accent, Color.WHITE) { connectOrPair() }
        content.addView(pairButton, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(13) })

        content.addView(actionButton("פתח הגדרות מפתחים / Wireless Debugging", palette.surfaceAlt, palette.text) {
            openDeveloperSettings()
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(9) })

        content.addView(actionButton("הצג שוב את התראת השיוך", palette.surfaceAlt, palette.text) {
            pairPanel.visibility = View.VISIBLE
            showPairingNotification()
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })

        page.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(page)
    }


    private fun showPairingNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            PairingNotification.show(this)
        }
    }

    private fun openDeveloperSettings() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSettingsLaunch = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchDeveloperSettingsWindow()
    }

    private fun launchDeveloperSettingsWindow() {
        PairingNotification.show(this)
        try {
            val settingsIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                // Request a separate Settings task and an adjacent window where the Android version allows it.
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
            }
            startActivity(settingsIntent)
            Handler(Looper.getMainLooper()).postDelayed({
                PairingNotification.show(this)
            }, 900L)
        } catch (_: Exception) {
            Toast.makeText(this, "לא ניתן לפתוח את הגדרות המפתחים במכשיר הזה.", Toast.LENGTH_LONG).show()
        }
    }

    private fun connectOrPair() {
        if (pairing || vm.adb.running.value == true) return
        if (pairPanel.visibility == View.VISIBLE || vm.needsToPair()) {
            pairWithCode(code.text.toString().trim(), port.text.toString().trim())
        } else {
            startConnection()
        }
    }

    private fun pairWithCode(pairingCode: String, manualPort: String = port.text.toString().trim()) {
        if (pairing || vm.adb.running.value == true) return
        if (pairingCode.length != 6 || pairingCode.any { !it.isDigit() }) {
            Toast.makeText(this, "יש להזין קוד שיוך בן 6 ספרות.", Toast.LENGTH_LONG).show()
            showPairingNotification()
            return
        }
        pairing = true
        pairButton.isEnabled = false
        pairButton.text = "מאתר פורט ומשייך…"
        status.text = "מאתר אוטומטית את פורט השיוך ומבצע שיוך…"
        PairingNotification.cancel(this)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val pairingPort = if (manualPort.isNotBlank()) {
                        manualPort.toIntOrNull()?.takeIf { it in 1..65535 }
                            ?: error("הפורט חייב להיות מספר בין 1 ל־65535.")
                    } else {
                        PairingPortDiscovery(this@ConnectionActivity).findPairingPort()
                            ?: error("לא נמצא פורט שיוך. ודא שחלון „שיוך באמצעות קוד” עדיין פתוח, או הזן את הפורט ידנית.")
                    }
                    if (!vm.adb.pair(pairingPort.toString(), pairingCode)) {
                        error("השיוך נכשל. ודא שהקוד עדיין בתוקף ונסה שוב.")
                    }
                    true
                }
            }
            pairing = false
            if (result.isSuccess) {
                vm.setPairedBefore(true)
                pairPanel.visibility = View.GONE
                status.text = "השיוך הצליח. מתחבר ל־ADB…"
                pairButton.text = "מתחבר…"
                startConnection()
            } else {
                pairButton.isEnabled = true
                pairButton.text = "נסה שוב"
                status.text = result.exceptionOrNull()?.message ?: "השיוך נכשל."
                showPairingNotification()
                Toast.makeText(this@ConnectionActivity, status.text, Toast.LENGTH_LONG).show()
            }
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
                    PairingNotification.cancel(this)
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
