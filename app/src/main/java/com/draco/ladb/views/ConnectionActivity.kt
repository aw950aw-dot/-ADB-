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
import android.util.Log
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
import androidx.activity.viewModels
import com.draco.ladb.viewmodels.MainActivityViewModel
import com.draco.ladb.utils.PairingNotification
import com.draco.ladb.services.PairingService
import com.draco.ladb.services.PairingDiscoveryService
import androidx.core.content.ContextCompat
import com.draco.ladb.utils.UiTheme
import com.draco.ladb.utils.UiComponents
import com.draco.ladb.utils.ConnectionBackupPrompt
import com.draco.ladb.utils.ShizukuAccess

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
    private var connectionRequested = false
    private var pendingSettingsLaunch = false
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                if (pendingSettingsLaunch) {
                    pendingSettingsLaunch = false
                    launchWirelessPairingWindow()
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
        ShizukuAccess.initialize(this)
        buildScreen()
        vm.adb.running.observe(this) { connected ->
            if (connected == true) {
                ConnectionBackupPrompt.maybeShow(this, vm.adb)
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
        } else if (ShizukuAccess.isBinderAlive() && ShizukuAccess.hasPermission()) {
            pairPanel.visibility = View.GONE
            startConnection()
        } else if (vm.needsToPair()) {
            pairPanel.visibility = View.VISIBLE
            status.text = "נדרש שיוך ראשוני. פתח את מסך הצימוד; ההתראה תופיע אוטומטית כששירות הצימוד יימצא."
        } else {
            pairPanel.visibility = View.GONE
            startConnection()
        }
        handlePairingReply(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!::status.isInitialized) return
        refreshPairingServiceState()
        if (vm.adb.running.value == true) {
            status.text = "●  מחובר ומוכן לפקודות"
            status.setTextColor(Color.rgb(25, 155, 105))
            pairButton.text = "מחובר"
            pairButton.isEnabled = false
            pairPanel.visibility = View.GONE
        } else if (!pairing &&
            ((ShizukuAccess.isBinderAlive() && ShizukuAccess.hasPermission()) || !vm.needsToPair())
        ) {
            pairPanel.visibility = View.GONE
            startConnection()
        } else if (!pairing) {
            status.text = "ממתין לפתיחת מסך הצימוד האלחוטי."
        }
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
            setPadding(dp(17), dp(12), dp(17), dp(20))
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
            LinearLayout.LayoutParams(dp(46), dp(46)))
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("ADB SMART  /  CONNECTION", 9, palette.accent, true))
            addView(text("חיבור Wireless Debugging", 20, palette.text, true))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(11) })
        content.addView(header)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(22, 91, 151), Color.rgb(19, 68, 113), Color.rgb(13, 43, 75))
            ).apply { cornerRadius = dp(22).toFloat() }
        }
        hero.addView(text("חיבור חד־פעמי. עבודה ברקע.", 21, Color.WHITE, true))
        hero.addView(text(
            "פותחים את חלון „שיוך מכשיר באמצעות קוד” ומשאירים אותו פתוח. האפליקציה תבצע את השיוך ברקע — בלי מסך מפוצל ובלי לחזור ממסך ההגדרות.",
            13, Color.rgb(221, 237, 251), false
        ).apply { setPadding(0, dp(8), 0, 0) })
        hero.addView(actionButton("פתח מסך צימוד אלחוטי", Color.rgb(8, 30, 54), Color.WHITE) {
            openWirelessPairingSettings()
        }, LinearLayout.LayoutParams(-1, dp(47)).apply { topMargin = dp(13) })
        content.addView(hero, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(13)
        })
        content.addView(actionButton("התחבר דרך Shizuku (למי שכבר משתמש בו)", palette.accent, Color.WHITE) {
            connectViaShizuku()
        }, LinearLayout.LayoutParams(-1, dp(49)).apply { topMargin = dp(10) })
        content.addView(text(
            "Shizuku מאפשר להשתמש בהרשאת shell שכבר אישרת שם, בלי לבצע שיוך Wireless Debugging נוסף. ההרשאה תתבקש מ-Shizuku עצמו.",
            11, palette.muted, false
        ).apply { setPadding(dp(3), dp(6), dp(3), dp(4)) })

        status = text("בודק מצב חיבור…", 13, palette.text, true).apply {
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = rounded(palette.surfaceAlt, dp(14), palette.outline)
        }
        content.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(13) })

        val steps = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(15), dp(15), dp(15))
            background = UiComponents.surface(this@ConnectionActivity, palette.surface, 19, palette.outline)
        }
        steps.addView(text("איך מתחברים", 17, palette.text, true))
        val stepItems = listOf(
            "01" to "הפעל ניפוי באגים אלחוטי באפשרויות למפתחים.",
            "02" to "בחר „שיוך מכשיר באמצעות קוד” והשאר את החלון פתוח.",
            "03" to "כשתופיע התראת ADB SMART מעל ההגדרות, הקלד בה את הקוד בן 6 הספרות ואשר."
        )
        stepItems.forEach { (number, description) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            row.addView(text(number, 12, palette.accent, true).apply {
                gravity = Gravity.CENTER
                background = rounded(palette.surfaceAlt, dp(9), palette.outline)
            }, LinearLayout.LayoutParams(dp(38), dp(35)))
            row.addView(text(description, 13, palette.muted, false),
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            steps.addView(row)
        }
        content.addView(steps, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        pairPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(15), dp(15), dp(15))
            background = UiComponents.surface(this@ConnectionActivity, palette.surface, 19, palette.outline)
        }
        pairPanel.addView(text("הזנה ידנית — חלופה להתראה", 16, palette.text, true))
        pairPanel.addView(text(
            "אפשר להזין כאן את אותם פרטים. גם במקרה הזה השיוך יתבצע בשירות רקע ולא יפתח מסך חדש.",
            12, palette.muted, false
        ).apply { setPadding(0, dp(5), 0, dp(3)) })
        code = inputField("קוד שיוך בן 6 ספרות", android.text.InputType.TYPE_CLASS_NUMBER)
        code.filters = arrayOf(InputFilter.LengthFilter(6))
        code.gravity = Gravity.CENTER
        code.textAlignment = View.TEXT_ALIGNMENT_CENTER
        pairPanel.addView(code, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(9) })
        port = inputField("פורט שיוך (אופציונלי)", android.text.InputType.TYPE_CLASS_NUMBER)
        port.filters = arrayOf(InputFilter.LengthFilter(5))
        pairPanel.addView(port, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(9) })
        pairPanel.addView(text(
            "אין צורך בפורט ברוב המכשירים — גילוי אוטומטי יאתר אותו. הזן פורט ידנית רק אם הגילוי נכשל.",
            11, palette.muted, false
        ).apply { setPadding(0, dp(7), 0, 0) })
        content.addView(pairPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        pairButton = actionButton("התחל שיוך וחיבור ברקע", palette.accent, Color.WHITE) {
            connectOrPair()
        }
        content.addView(pairButton, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })

        content.addView(actionButton("פתח מסך צימוד אלחוטי", palette.surfaceAlt, palette.text) {
            openWirelessPairingSettings()
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(9) })

        content.addView(text(
            "Android מנהל את חלון קוד הצימוד בעצמו. ברגע שהשיוך מאושר, המערכת סוגרת אותו. ADB SMART מאתרת מראש את שירות הצימוד ושולחת את הקוד מיד עם קבלתו.",
            11, palette.muted, false
        ).apply { setPadding(dp(3), dp(13), dp(3), 0) })

        page.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(page)
        UiComponents.applySystemBars(this, page, palette)
    }

    private fun connectViaShizuku() {
        status.text = "בודק את Shizuku ומבקש הרשאה אם צריך…"
        pairButton.isEnabled = false
        ShizukuAccess.connect(this) { success, message ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (success) {
                    runCatching { vm.adb.activateShizukuBackend() }
                        .onSuccess {
                            pairing = false
                            pairPanel.visibility = View.GONE
                            status.text = "●  $message"
                            status.setTextColor(Color.rgb(25, 155, 105))
                            pairButton.text = "מחובר דרך Shizuku"
                            pairButton.isEnabled = false
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                        }
                        .onFailure { error ->
                            status.text = error.message ?: "הפעלת Shizuku נכשלה."
                            pairButton.isEnabled = true
                        }
                } else {
                    status.text = message
                    pairButton.isEnabled = true
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openWirelessPairingSettings() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSettingsLaunch = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchWirelessPairingWindow()
    }

    /**
     * Starts mDNS discovery first. The RemoteInput notification is posted only
     * after Android's temporary _adb-tls-pairing service becomes visible.
     */
    private fun startPairingDiscovery() {
        PairingNotification.cancel(this)
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, PairingDiscoveryService::class.java)
            )
            status.text = "מאתר את שירות הצימוד. השאר את חלון הצימוד פתוח; ההתראה תופיע אוטומטית."
        } catch (error: Exception) {
            status.text = "לא ניתן להתחיל איתור צימוד ברקע: ${error.message ?: "שגיאה"}"
            Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
        }
    }

    private fun launchWirelessPairingWindow() {
        startPairingDiscovery()
        val directPairingIntent = Intent("android.settings.WIRELESS_DEBUGGING_PAIRING_DIALOG")
        try {
            // Some Android Settings builds expose a direct system entry point
            // for the Wireless Debugging pairing dialog. Use it when available.
            if (directPairingIntent.resolveActivity(packageManager) != null) {
                startActivity(directPairingIntent)
                status.text = "נפתח חלון הצימוד של Android. ההתראה תופיע ברגע ששירות הצימוד יזוהה."
                return
            }
        } catch (error: Exception) {
            Log.w("ConnectionActivity", "Direct wireless pairing dialog is unavailable", error)
        }

        try {
            // Some vendor Settings versions expose the Wireless Debugging page separately.
            val wirelessSettingsIntent = Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
            if (wirelessSettingsIntent.resolveActivity(packageManager) != null) {
                startActivity(wirelessSettingsIntent)
                status.text = "נפתח מסך Wireless Debugging. הפעל אותו ובחר „שיוך מכשיר באמצעות קוד”."
                return
            }
        } catch (error: Exception) {
            Log.w("ConnectionActivity", "Direct Wireless Debugging settings page is unavailable", error)
        }

        try {
            // Fallback for OEM Settings apps that do not expose a direct wireless entry point.
            val settingsIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
            }
            startActivity(settingsIntent)
            status.text = "פתח ניפוי באגים אלחוטי, הפעל אותו ובחר „שיוך מכשיר באמצעות קוד”. ההתראה תופיע אוטומטית."
        } catch (_: Exception) {
            Toast.makeText(this, "לא ניתן לפתוח את הגדרות הצימוד במכשיר הזה.", Toast.LENGTH_LONG).show()
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
            return
        }

        val parsedPort = if (manualPort.isBlank()) {
            null
        } else {
            manualPort.toIntOrNull()?.takeIf { it in 1..65535 }
                ?: run {
                    Toast.makeText(this, "הפורט חייב להיות מספר בין 1 ל־65535.", Toast.LENGTH_LONG).show()
                    return
                }
        }

        pairing = true
        pairButton.isEnabled = false
        pairButton.text = "משייך ברקע…"
        status.text = "תהליך השיוך פועל ברקע. אפשר להשאיר את מסך ההגדרות פתוח."
        val serviceIntent = Intent(this, PairingService::class.java)
            .putExtra(PairingService.EXTRA_PAIRING_CODE, pairingCode)
        if (parsedPort != null) {
            serviceIntent.putExtra(PairingService.EXTRA_PAIRING_PORT, parsedPort.toString())
        }

        try {
            ContextCompat.startForegroundService(this, serviceIntent)
            Toast.makeText(this, "השיוך התחיל ברקע. מסך ההגדרות לא אמור להיסגר.", Toast.LENGTH_LONG).show()
            monitorPairingService()
        } catch (error: Exception) {
            pairing = false
            pairButton.isEnabled = true
            pairButton.text = "נסה שוב"
            status.text = "לא ניתן להתחיל שיוך ברקע: " + (error.message ?: "שגיאה")
            Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshPairingServiceState() {
        val prefs = getSharedPreferences("pairing_state", MODE_PRIVATE)
        val working = prefs.getBoolean("working", false)
        if (working) {
            pairing = true
            pairButton.isEnabled = false
            pairButton.text = "משייך ברקע…"
            status.text = "השיוך מתבצע ברקע. השאר את מסך Wireless Debugging פתוח."
            return
        }

        val updatedAt = prefs.getLong("updated_at", 0L)
        val uiPrefs = getSharedPreferences("pairing_ui", MODE_PRIVATE)
        val seenAt = uiPrefs.getLong("seen_at", 0L)
        if (updatedAt > seenAt) {
            pairing = false
            val paired = prefs.getBoolean("last_paired", false)
            val connected = prefs.getBoolean("last_success", false)
            val message = prefs.getString("last_result", null)
            if (!message.isNullOrBlank()) status.text = message
            pairButton.isEnabled = vm.adb.running.value != true
            pairButton.text = when {
                connected -> "מחובר"
                paired -> "השיוך הצליח — החיבור נכשל"
                else -> "נסה שוב"
            }
            uiPrefs.edit().putLong("seen_at", updatedAt).apply()
        }
    }

    private fun monitorPairingService() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (!::status.isInitialized) return@postDelayed
            refreshPairingServiceState()
            if (pairing) monitorPairingService()
        }, 750L)
    }

    private fun startConnection() {
        if (vm.adb.running.value == true || connectionRequested) return
        connectionRequested = true
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
                    pairButton.isEnabled = false
                } else {
                    connectionRequested = false
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
        UiComponents.button(this, label, bg, fg,
            if (bg == palette.surfaceAlt) palette.outline else null, 48, 2, action)

    private fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }
}
