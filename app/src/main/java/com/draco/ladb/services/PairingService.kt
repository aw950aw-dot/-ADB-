package com.draco.ladb.services

import android.app.Service
import android.content.Intent
import android.net.nsd.NsdManager
import android.os.IBinder
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.draco.ladb.R
import com.draco.ladb.utils.ADB
import com.draco.ladb.utils.DnsDiscover
import com.draco.ladb.utils.PairingNotification
import com.draco.ladb.utils.PairingPortDiscovery

/**
 * Performs pairing and ADB startup without ever opening or bringing an Activity
 * to the foreground. Both notification and manual-entry flows use this service.
 */
class PairingService : Service() {
    companion object {
        const val EXTRA_PAIRING_CODE = "pairing_code"
        const val EXTRA_PAIRING_PORT = "pairing_port"
        private const val TAG = "PairingService"
        private const val STATE_PREFS = "pairing_state"
    }

    @Volatile private var workStarted = false
    @Volatile private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    @Synchronized
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val code = intent?.getStringExtra(EXTRA_PAIRING_CODE)
            ?.filter { it in '0'..'9' }
            .orEmpty()
        val rawPort = intent?.getStringExtra(EXTRA_PAIRING_PORT).orEmpty()
        val requestedPort = rawPort.takeIf { it.isNotBlank() }?.toIntOrNull()

        if (code.length != 6) {
            PairingNotification.show(this, "קוד השיוך אינו תקין. יש להזין בדיוק 6 ספרות.")
            if (!workStarted) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (rawPort.isNotBlank() && requestedPort !in 1..65535) {
            PairingNotification.show(this, "הפורט שהוזן אינו תקין. יש להזין מספר בין 1 ל־65535.")
            if (!workStarted) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (workStarted) {
            PairingNotification.show(this, "תהליך שיוך כבר פועל ברקע. השאר את מסך ההגדרות פתוח.")
            return START_NOT_STICKY
        }

        workStarted = true
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit {
            putBoolean("working", true)
            putLong("started_at", System.currentTimeMillis())
        }

        try {
            startForeground(
                PairingNotification.WORKING_NOTIFICATION_ID,
                PairingNotification.workingNotification(this)
            )
        } catch (error: Exception) {
            workStarted = false
            getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit {
                putBoolean("working", false)
                putBoolean("last_success", false)
                putString("last_result", "Android לא אפשר להפעיל שירות שיוך ברקע: " +
                    (error.message ?: "שגיאת הרשאה"))
                putLong("updated_at", System.currentTimeMillis())
            }
            Log.e(TAG, "Could not start pairing foreground service", error)
            PairingNotification.show(
                this,
                "Android לא אפשר להפעיל את שירות השיוך. בדוק הרשאת התראות ונסה שוב."
            )
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        Thread {
            runPairing(code, requestedPort)
        }.apply {
            isDaemon = true
            name = "WirelessPairingService"
        }.start()

        return START_NOT_STICKY
    }

    private fun runPairing(code: String, requestedPort: Int?) {
        var paired = false
        var resultMessage = "השיוך נכשל. בדוק שהקוד עדיין בתוקף ונסה שוב."
        try {
            val port = requestedPort
                ?: PairingPortDiscovery(applicationContext).findPairingPort(35_000L)
                ?: error(
                    "לא נמצא פורט שיוך. השאר את חלון „שיוך באמצעות קוד” פתוח, " +
                        "או חזור לאפליקציה והזן את הפורט ידנית."
                )

            val adb = ADB.getInstance(applicationContext)
            if (!adb.pair(port.toString(), code)) {
                error("השיוך נכשל. בדוק שהקוד עדיין בתוקף ונסה שוב.")
            }
            paired = true

            PreferenceManager.getDefaultSharedPreferences(applicationContext).edit {
                putBoolean(getString(R.string.paired_key), true)
            }

            val nsd = getSystemService(NSD_SERVICE) as NsdManager
            DnsDiscover.getInstance(applicationContext, nsd).scanAdbPorts()
            val connected = adb.initServer(skipWirelessReset = true)
            if (connected) adb.ensureShellDeathMonitor()

            resultMessage = if (connected) {
                "השיוך והחיבור ל־ADB הושלמו. אפשר לחזור לאפליקציה כשנוח לך."
            } else {
                "השיוך הצליח, אבל החיבור ל־ADB עדיין לא הושלם. אפשר לנסות להתחבר מתוך האפליקציה."
            }
            PairingNotification.showResult(applicationContext, true, resultMessage)
        } catch (error: Exception) {
            Log.e(TAG, "Wireless Debugging pairing failed", error)
            resultMessage = error.message
                ?: "השיוך נכשל. בדוק שהקוד והפורט עדיין בתוקף ונסה שוב."
            PairingNotification.show(applicationContext, resultMessage)
        } finally {
            getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit {
                putBoolean("working", false)
                putBoolean("last_success", paired)
                putString("last_result", resultMessage)
                putLong("updated_at", System.currentTimeMillis())
            }
            PairingNotification.cancelWorking(applicationContext)
            val stopId = synchronized(this) {
                workStarted = false
                latestStartId
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(stopId)
        }
    }
}
