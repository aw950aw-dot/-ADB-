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

/** Completes pairing and local ADB connection without bringing an Activity above Android Settings. */
class PairingService : Service() {
    companion object {
        const val EXTRA_PAIRING_CODE = "pairing_code"
        private const val TAG = "PairingService"
    }

    @Volatile private var workStarted = false
    @Volatile private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getStringExtra(EXTRA_PAIRING_CODE)
            ?.filter { it in '0'..'9' }
            .orEmpty()
        if (code.length != 6) {
            PairingNotification.show(this, "קוד השיוך אינו תקין. יש להזין 6 ספרות.")
            if (!workStarted) stopSelfResult(startId)
            return START_NOT_STICKY
        }

        latestStartId = startId
        if (!workStarted) {
            workStarted = true
            try {
                startForeground(
                    PairingNotification.WORKING_NOTIFICATION_ID,
                    PairingNotification.workingNotification(this)
                )
            } catch (error: Exception) {
                workStarted = false
                Log.e(TAG, "Could not start pairing foreground service", error)
                PairingNotification.show(this, "Android לא אפשר להפעיל את שירות השיוך. פתח את האפליקציה ונסה שוב.")
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            Thread {
                runPairing(code)
            }.apply { isDaemon = true; name = "WirelessPairingService" }.start()
        }
        return START_NOT_STICKY
    }

    private fun runPairing(code: String) {
        try {
            val port = PairingPortDiscovery(applicationContext).findPairingPort(25_000L)
                ?: error("לא נמצא פורט שיוך. השאר את חלון „שיוך באמצעות קוד” פתוח ונסה שוב.")
            val adb = ADB.getInstance(applicationContext)
            if (!adb.pair(port.toString(), code)) {
                error("השיוך נכשל. בדוק שהקוד עדיין בתוקף ונסה שוב.")
            }

            PreferenceManager.getDefaultSharedPreferences(applicationContext).edit {
                putBoolean(getString(R.string.paired_key), true)
            }

            val nsd = getSystemService(NSD_SERVICE) as NsdManager
            DnsDiscover.getInstance(applicationContext, nsd).scanAdbPorts()
            val connected = adb.initServer(skipWirelessReset = true)
            if (connected) adb.ensureShellDeathMonitor()

            PairingNotification.showResult(
                applicationContext,
                true,
                if (connected) "השיוך והחיבור ל־ADB הושלמו. אפשר לחזור לאפליקציה."
                else "השיוך הצליח. החיבור עדיין לא הושלם; חזור לאפליקציה ונסה להתחבר."
            )
        } catch (error: Exception) {
            Log.e(TAG, "Wireless Debugging pairing failed", error)
            PairingNotification.show(
                applicationContext,
                error.message ?: "השיוך נכשל. אפשר לנסות שוב מתוך ההתראה."
            )
        } finally {
            PairingNotification.cancelWorking(applicationContext)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(latestStartId)
        }
    }
}
