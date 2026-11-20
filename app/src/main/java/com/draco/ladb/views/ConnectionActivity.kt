package com.draco.ladb.views

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.draco.ladb.utils.LocalAdbServiceDiscovery
import com.draco.ladb.utils.ShizukuAccess
import com.draco.ladb.utils.UiComponents
import com.draco.ladb.utils.UiTheme
import com.draco.ladb.viewmodels.MainActivityViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Manual, foreground Wireless ADB pairing. No notification or pairing service is used. */
class ConnectionActivity : AppCompatActivity() {
    private val vm: MainActivityViewModel by viewModels()
    private val palette get() = UiTheme.palette(this)

    private lateinit var status: TextView
    private lateinit var pairingPort: EditText
    private lateinit var pairingCode: EditText
    private lateinit var pairingPanel: LinearLayout
    private lateinit var pairButton: TextView
    private lateinit var connectButton: TextView
    private lateinit var settingsButton: TextView
    private lateinit var shizukuButton: TextView

    @Volatile private var operationInProgress = false

    private companion object {
        const val STATE_PORT = "pairing_port"
        const val STATE_CODE = "pairing_code"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        UiTheme.applySavedMode(this)
        super.onCreate(savedInstanceState)
        ShizukuAccess.initialize(this)
        buildScreen()
        // Entering split-screen / a floating window must not wipe what the user already typed.
        savedInstanceState?.let {
            pairingPort.setText(it.getString(STATE_PORT, ""))
            pairingCode.setText(it.getString(STATE_CODE, ""))
        }

        vm.adb.running.observe(this) { connected ->
            if (connected == true) {
                showConnected("מחובר ומוכן לפקודות")
            } else if (!operationInProgress) {
                showDisconnected()
            }
        }

        if (vm.adb.running.value == true) {
            showConnected("מחובר ומוכן לפקודות")
        } else {
            showDisconnected()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::pairingPort.isInitialized) outState.putString(STATE_PORT, pairingPort.text.toString())
        if (::pairingCode.isInitialized) outState.putString(STATE_CODE, pairingCode.text.toString())
    }

    private fun showConnected(message: String) {
        status.text = "●  " + message
        status.setTextColor(Color.rgb(25, 155, 105))
        connectButton.text = "מחובר"
        connectButton.isEnabled = false
        pairButton.isEnabled = false
    }

    private fun showDisconnected() {
        status.text = if (vm.needsToPair()) {
            "נדרש שיוך ראשוני. השאר את חלון קוד הצימוד של Android פתוח והזן כאן את הפורט והקוד."
        } else {
            "לא מחובר. אפשר להתחבר שוב באמצעות השיוך השמור."
        }
        status.setTextColor(palette.muted)
        connectButton.text = "התחבר באמצעות השיוך השמור"
        connectButton.isEnabled = !vm.needsToPair()
        pairButton.isEnabled = true
        pairingPanel.visibility = if (vm.needsToPair()) View.VISIBLE else View.GONE
    }

    private fun setWorking(message: String) {
        operationInProgress = true
        status.text = message
        status.setTextColor(palette.text)
        pairButton.isEnabled = false
        connectButton.isEnabled = false
        settingsButton.isEnabled = false
        shizukuButton.isEnabled = false
        pairButton.text = "מתבצע…"
    }

    private fun finishWorking() {
        operationInProgress = false
        settingsButton.isEnabled = true
        shizukuButton.isEnabled = true
        pairButton.isEnabled = true
        connectButton.isEnabled = !vm.needsToPair()
        pairButton.text = "בצע שיוך וחיבור"
    }

    private fun openWirelessPairingSettings() {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
        val candidates = listOf(
            Intent("android.settings.WIRELESS_DEBUGGING_PAIRING_DIALOG"),
            Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"),
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        )

        for (candidate in candidates) {
            try {
                candidate.addFlags(flags)
                startActivity(candidate)
                status.text = "פתח Wireless Debugging ובחר „שיוך מכשיר באמצעות קוד”. השאר את החלון פתוח."
                return
            } catch (_: Exception) {
                // Continue with the next Android Settings entry point.
            }
        }
        Toast.makeText(this, "לא ניתן לפתוח את הגדרות Wireless Debugging במכשיר הזה.", Toast.LENGTH_LONG).show()
    }

    private fun pairAndConnect() {
        if (operationInProgress || vm.adb.running.value == true) return

        if (ShizukuAccess.isBinderAlive()) {
            status.text = "שירות Shizuku פעיל. אל תפעיל במקביל את שרת LADB. השתמש בכפתור Shizuku או עצור את השירות לפני שיוך Wireless ADB."
            return
        }

        val port = pairingPort.text.toString().trim().toIntOrNull()?.takeIf { it in 1..65535 }
        if (port == null) {
            pairingPort.error = "הזן את פורט השיוך שמופיע בחלון Android"
            return
        }

        val code = pairingCode.text.toString().trim()
        if (code.length != 6 || code.any { it !in '0'..'9' }) {
            pairingCode.error = "הקוד חייב להכיל 6 ספרות"
            return
        }

        setWorking("מבצע adb pair מול localhost:" + port + "…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    statusOnWorker("מאתר את כתובת שירות השיוך המקומי…")
                    // Short wait: the pairing code is only valid while Android's dialog is open,
                    // so we must not burn many seconds before the actual `adb pair`.
                    val pairingHost = LocalAdbServiceDiscovery(this@ConnectionActivity)
                        .findPairingHost(port, 2_500L) ?: "localhost"
                    statusOnWorker("מבצע שיוך מול " + pairingHost + ":" + port + "…")
                    val paired = vm.adb.pair(port.toString(), code, pairingHost)
                    if (!paired) {
                        val details = runCatching {
                            vm.adb.outputBufferFile.readText().takeLast(700)
                        }.getOrDefault("")
                        error(
                            "פקודת adb pair נכשלה. בדוק שהקוד והפורט עדיין בתוקף." +
                                if (details.isBlank()) "" else "\n" + details
                        )
                    }

                    vm.setPairedBefore(true)
                    statusOnWorker("השיוך הצליח. מאתר עכשיו פורט TLS חדש לחיבור…")
                    val endpoint = LocalAdbServiceDiscovery(this@ConnectionActivity)
                        .findConnectEndpoint(12_000L)
                        ?: error(
                            "השיוך הצליח, אבל Android לא פרסם שירות _adb-tls-connect. " +
                                "השאר Wireless Debugging פעיל ונסה להתחבר שוב."
                        )

                    statusOnWorker("נמצא פורט חיבור " + endpoint.port + ". מפעיל ADB ובודק shell…")
                    val connected = vm.adb.initServer(endpoint)
                    if (!connected) {
                        val details = runCatching {
                            vm.adb.outputBufferFile.readText().takeLast(900)
                        }.getOrDefault("")
                        error(
                            "השיוך הצליח, אבל פתיחת ADB shell נכשלה." +
                                if (details.isBlank()) "" else "\n" + details
                        )
                    }
                    true
                }
            }

            finishWorking()
            if (result.isSuccess) {
                pairingPanel.visibility = View.GONE
                showConnected("מחובר ומוכן לפקודות")
                Toast.makeText(this@ConnectionActivity, "השיוך והחיבור הושלמו.", Toast.LENGTH_SHORT).show()
            } else {
                val message = result.exceptionOrNull()?.message ?: "השיוך או החיבור נכשלו."
                status.text = message
                status.setTextColor(Color.rgb(205, 90, 75))
                pairButton.text = "נסה שוב"
                connectButton.isEnabled = !vm.needsToPair()
                Toast.makeText(this@ConnectionActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun statusOnWorker(message: String) {
        runOnUiThread {
            if (!isFinishing && ::status.isInitialized) status.text = message
        }
    }

    private fun connectUsingSavedPairing() {
        if (operationInProgress || vm.adb.running.value == true) return
        if (vm.needsToPair()) {
            pairingPanel.visibility = View.VISIBLE
            status.text = "נדרש שיוך. הזן את פורט השיוך ואת הקוד שמופיעים בחלון Android."
            return
        }

        setWorking("מאתר שירות TLS עדכני ומתחבר…")
        vm.startADBServer { success ->
            runOnUiThread {
                finishWorking()
                if (success) {
                    showConnected("מחובר ומוכן לפקודות")
                } else {
                    status.text = "החיבור לא הושלם. בדוק ש-Wireless Debugging פועל ונסה שוב."
                    status.setTextColor(Color.rgb(205, 90, 75))
                    connectButton.isEnabled = true
                    connectButton.text = "נסה להתחבר שוב"
                }
            }
        }
    }

    /** Shizuku is a separate backend; never fall back to local ADB if its bind fails. */
    private fun connectViaShizuku() {
        if (operationInProgress || vm.adb.running.value == true) return
        setWorking("מבקש חיבור נפרד דרך Shizuku…")
        ShizukuAccess.connect(this) { success, message ->
            runOnUiThread {
                if (success) {
                    val activated = runCatching { vm.adb.activateShizukuBackend() }
                    finishWorking()
                    if (activated.isSuccess) {
                        pairingPanel.visibility = View.GONE
                        showConnected(message)
                    } else {
                        status.text = activated.exceptionOrNull()?.message ?: "לא ניתן להפעיל את שירות Shizuku."
                        status.setTextColor(Color.rgb(205, 90, 75))
                    }
                } else {
                    finishWorking()
                    status.text = message
                    status.setTextColor(Color.rgb(205, 90, 75))
                }
            }
        }
    }

    private fun buildScreen() {
        window.statusBarColor = palette.statusBar
        window.navigationBarColor = palette.navigationBar
        window.decorView.systemUiVisibility =
            if (palette.dark) 0 else
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(13), dp(9), dp(13), dp(12))
            setBackgroundColor(palette.background)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        scroll.addView(content)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(actionButton("‹", palette.surfaceAlt, palette.text) { finish() },
            LinearLayout.LayoutParams(dp(42), dp(42)))
        header.addView(
            text("חיבור Wireless ADB", 19, palette.text, true),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(9) }
        )
        content.addView(header)

        val instructions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = UiComponents.surface(this@ConnectionActivity, palette.surface, 15, palette.outline)
        }
        instructions.addView(text("תהליך שיוך ידני — בלי התראות ובלי שירותי רקע", 14, palette.text, true))
        instructions.addView(text(
            "1. פתח את חלון „שיוך מכשיר באמצעות קוד” ב־Android.\n" +
                "2. השאר אותו פתוח במסך מפוצל או בחלון צף, לצד ADB SMART.\n" +
                "3. העתק לכאן את פורט השיוך ואת הקוד בן 6 הספרות.\n" +
                "4. לחץ „בצע שיוך וחיבור”.",
            12, palette.muted, false
        ).apply { setPadding(0, dp(6), 0, 0) })
        content.addView(instructions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })

        settingsButton = actionButton("פתח הגדרות Wireless Debugging", palette.surfaceAlt, palette.text) {
            openWirelessPairingSettings()
        }
        content.addView(settingsButton, LinearLayout.LayoutParams(-1, dp(43)).apply { topMargin = dp(8) })

        status = text("ממתין לפעולה…", 12, palette.text, true).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(palette.surfaceAlt, dp(12), palette.outline)
        }
        content.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        pairingPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = UiComponents.surface(this@ConnectionActivity, palette.surface, 15, palette.outline)
        }
        pairingPanel.addView(text("נתוני השיוך", 15, palette.text, true))
        pairingPort = inputField("פורט השיוך (למשל 37123)", android.text.InputType.TYPE_CLASS_NUMBER).apply {
            filters = arrayOf(InputFilter.LengthFilter(5))
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }
        pairingPanel.addView(pairingPort, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })

        pairingCode = inputField("קוד בן 6 ספרות", android.text.InputType.TYPE_CLASS_NUMBER).apply {
            filters = arrayOf(InputFilter.LengthFilter(6))
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }
        pairingPanel.addView(pairingCode, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(7) })

        pairButton = actionButton("בצע שיוך וחיבור", palette.accent, Color.WHITE) {
            pairAndConnect()
        }
        pairingPanel.addView(pairButton, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(9) })
        content.addView(pairingPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        connectButton = actionButton("התחבר באמצעות השיוך השמור", palette.accent, Color.WHITE) {
            connectUsingSavedPairing()
        }
        content.addView(connectButton, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })

        shizukuButton = actionButton("התחבר דרך Shizuku (מסלול נפרד)", palette.surfaceAlt, palette.text) {
            connectViaShizuku()
        }
        content.addView(shizukuButton, LinearLayout.LayoutParams(-1, dp(43)).apply { topMargin = dp(7) })

        content.addView(text(
            "השיוך הראשוני דורש שחלון Android יישאר פתוח עד שהקוד מתקבל. חיבור חוזר לאחר מכן אינו דורש הזנת קוד.",
            10, palette.muted, false
        ).apply { setPadding(dp(3), dp(8), dp(3), 0) })

        page.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(page)
        UiComponents.applySystemBars(this, page, palette)
    }

    private fun inputField(hintValue: String, type: Int): EditText =
        EditText(this).apply {
            hint = hintValue
            inputType = type
            setSingleLine(true)
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
            setTextColor(palette.text)
            setHintTextColor(palette.muted)
            textSize = 15f
            setPadding(dp(11), 0, dp(11), 0)
            background = rounded(palette.inputBackground, dp(11), palette.outline)
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
        UiComponents.button(
            this, label, bg, fg,
            if (bg == palette.surfaceAlt) palette.outline else null,
            46, 2, action
        )

    private fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }
}
