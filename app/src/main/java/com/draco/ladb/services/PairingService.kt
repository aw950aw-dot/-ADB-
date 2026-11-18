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
 * Performs pairing and ADB startup without bringing an Activity above Android Settings.
 * A successful pair is recorded separately from a verified live ADB connection.
 */
class PairingService : Service() {
    companion object {
        const val EXTRA_PAIRING_CODE = "pairing_code"
        const val EXTRA_PAIRING_PORT = "pairing_port"
        const val EXTRA_PAIRING_HOST = "pairing_host"
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
        val requestedHost = intent?.getStringExtra(EXTRA_PAIRING_HOST)

        if (code.length != 6) {
            PairingNotification.show(this, "קוד השיוך אינו תקין. יש להזין בדיוק 6 ספרות.")
            if (!workStarted) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (rawPort.isNotBlank() && (requestedPort == null || requestedPort !in 1..65535)) {
            PairingNotification.show(this, "הפורט שהוזן אינו תקין. יש להזין מספר בין 1 ל־65535.")
            if (!workStarted) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (workStarted) {
            PairingNotification.show(this, "תהליך שיוך כבר פועל ברקע. השאר את דף Wireless Debugging פתוח.")
            return START_NOT_STICKY
        }

        workStarted = true
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit {
            putBoolean("working", true)
            putBoolean("last_paired", false)
            putBoolean("last_success", false)
            putLong("started_at", System.currentTimeMillis())
        }

        PairingNotification.cancel(this)
        try {
            startForeground(
                PairingNotification.WORKING_NOTIFICATION_ID,
                PairingNotification.workingNotification(this)
            )
        } catch (error: Exception) {
            workStarted = false
            getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit {
                putBoolean("working", false)
                putBoolean("last_paired", false)
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
            runPairing(code, requestedPort, requestedHost)
        }.apply {
            isDaemon = true
            name = "WirelessPairingService"
        }.start()

        return START_NOT_STICKY
    }

    private fun runPairing(code: String, requestedPort: Int?, requestedHost: String?) {
        var paired = false
        var connected = false
        var resultMessage = "השיוך והחיבור לא הושלמו."
        try {
            val discovered = if (requestedPort == null) {
                PairingPortDiscovery(applicationContext).findPairingEndpoint(15_000L)
            } else null
            val pairingPort = requestedPort ?: discovered?.port ?: error(
                "לא נמצא פורט שיוך. פתח את חלון „שיוך באמצעות קוד” ונסה שוב."
            )
            val pairingHost = requestedHost ?: discovered?.host ?: "localhost"

            val adb = ADB.getInstance(applicationContext)
            if (!adb.pair(pairingPort.toString(), code, pairingHost)) {
                error("השיוך נכשל. בדוק שהקוד עדיין בתוקף ונסה שוב.")
            }
            paired = true

            PreferenceManager.getDefaultSharedPreferences(applicationContext).edit {
                putBoolean(getString(R.string.paired_key), true)
            }

            // The TLS connect advertisement can already exist before the temporary
            // pairing dialog opens. Preserve it instead of stopping and resetting mDNS.
            val nsd = getSystemService(NSD_SERVICE) as NsdManager
            val discovery = DnsDiscover.getInstance(applicationContext, nsd)
            val endpoint = DnsDiscover.currentEndpoint() ?: run {
                discovery.scanAdbPorts()
                waitForConnectEndpoint(15_000L)
            } ?: error(
                "השיוך הצליח, אך לא נמצאה כתובת שירות ADB לחיבור. " +
                    "השאר את Wireless Debugging פעיל ונסה שוב."
            )
            var activeEndpoint = endpoint
            Log.i(TAG, "Found ADB TLS connect endpoint ${activeEndpoint.serial} (${activeEndpoint.serviceName})")

            var initialized = adb.initServer(connectEndpointOverride = activeEndpoint)
            if (!initialized) {
                // One fresh mDNS attempt covers devices that rotate their TLS port
                // immediately after pairing. Do not restart the pairing session.
                discovery.stopScan()
                discovery.scanAdbPorts()
                val refreshed = waitForConnectEndpoint(10_000L)
                if (refreshed != null && refreshed.serial != activeEndpoint.serial) {
                    activeEndpoint = refreshed
                    initialized = adb.initServer(connectEndpointOverride = activeEndpoint)
                }
                if (!initialized) {
                    error(
                        "השיוך הצליח, אך החיבור ל־${refreshed?.serial ?: activeEndpoint.serial} נכשל. " +
                            "בדוק ש-Wireless Debugging עדיין פעיל ונסה שוב. " +
                            "פלט ADB אחרון: ${adb.outputBufferFile.readText().takeLast(900)}"
                    )
                }
            }

            val devices = adb.getDevices()
            val expectedSerial = activeEndpoint.serial
            val connectedDevice = devices.firstOrNull {
                expectedSerial != null && it.equals(expectedSerial, ignoreCase = true)
            } ?: devices.firstOrNull {
                it.startsWith("localhost:") || it.startsWith("127.0.0.1:")
            } ?: error(
                "השיוך הצליח, אך ADB לא דיווח על ההתקן שחובר. התקנים שאושרו: " +
                    devices.joinToString(", ").ifBlank { "אין" }
            )

            connected = true
            adb.ensureShellDeathMonitor()
            resultMessage = "השיוך והחיבור ל־ADB הושלמו: $connectedDevice"
            PairingNotification.showResult(applicationContext, true, resultMessage)
        } catch (error: Exception) {
            Log.e(TAG, "Wireless Debugging pairing/connection failed", error)
            resultMessage = error.message ?: "תהליך השיוך או החיבור נכשל."
            PairingNotification.showResult(
                applicationContext,
                false,
                if (paired) "השיוך הראשוני הצליח, אבל ADB לא התחבר: $resultMessage"
                else "השיוך נכשל: $resultMessage"
            )
        } finally {
            getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit {
                putBoolean("working", false)
                putBoolean("last_paired", paired)
                putBoolean("last_success", connected)
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

    private fun waitForConnectEndpoint(timeoutMillis: Long): com.draco.ladb.utils.AdbConnectEndpoint? {
        val deadline = System.currentTimeMillis() + timeoutMillis.coerceAtLeast(1_000L)
        while (System.currentTimeMillis() < deadline) {
            DnsDiscover.currentEndpoint()?.let { return it }
            try {
                Thread.sleep(200L)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return DnsDiscover.currentEndpoint()
    }
}
