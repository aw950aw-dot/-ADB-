package com.draco.ladb.services

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.draco.ladb.utils.PairingNotification
import com.draco.ladb.utils.PairingPortDiscovery

/**
 * Watches for Android's temporary ADB pairing service before asking for the code.
 * This makes the notification ready as soon as the system pairing dialog appears.
 */
class PairingDiscoveryService : Service() {
    companion object {
        private const val TAG = "PairingDiscovery"
        private const val DISCOVERY_TIMEOUT_MS = 60_000L
    }

    @Volatile private var scanStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    @Synchronized
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (scanStarted) return START_NOT_STICKY
        scanStarted = true

        try {
            startForeground(
                PairingNotification.WORKING_NOTIFICATION_ID,
                PairingNotification.workingNotification(this)
            )
        } catch (error: Exception) {
            Log.e(TAG, "Could not start pairing discovery foreground service", error)
            PairingNotification.show(
                this,
                "לא ניתן להפעיל איתור צימוד ברקע: ${error.message ?: "שגיאה"}"
            )
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        Thread {
            try {
                val endpoint = PairingPortDiscovery(applicationContext)
                    .findPairingEndpoint(DISCOVERY_TIMEOUT_MS)

                if (endpoint != null) {
                    Log.i(TAG, "Pairing service found at ${endpoint.host}:${endpoint.port}")
                    PairingNotification.show(
                        applicationContext,
                        "נמצא שירות הצימוד. הזן עכשיו את קוד 6 הספרות ולחץ „שלח קוד והתחבר”.",
                        endpoint.port,
                        endpoint.host
                    )
                } else {
                    PairingNotification.show(
                        applicationContext,
                        "לא נמצא חלון צימוד פעיל. פתח „שיוך מכשיר באמצעות קוד” והשאר אותו פתוח, ואז נסה שוב."
                    )
                }
            } catch (error: Exception) {
                Log.e(TAG, "Pairing service discovery failed", error)
                PairingNotification.show(
                    applicationContext,
                    "איתור שירות הצימוד נכשל: ${error.message ?: "שגיאה"}. נסה שוב."
                )
            } finally {
                PairingNotification.cancelWorking(applicationContext)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.apply {
            isDaemon = true
            name = "PairingPortDiscovery"
        }.start()

        return START_NOT_STICKY
    }
}
