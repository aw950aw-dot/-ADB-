package com.aw950aw.smartbackup.wireless

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/**
 * Pairs with the device the way Shizuku does: it looks for the "pairing code" dialog in the
 * background and shows a notification with a text field. The user types the code into the
 * notification, so the Settings dialog can stay on screen (no split-screen needed).
 */
class PairingService : Service() {

    @Volatile private var pairingEndpoint: Endpoint? = null
    @Volatile private var searching = false
    @Volatile private var working = false
    @Volatile private var stopRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification("התאמה למכשיר", "מתחיל…", false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        WirelessLink.init(applicationContext)

        if (intent == null) {
            finish()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START -> startSearch()
            ACTION_CODE -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString().orEmpty().trim()
                val endpoint = pairingEndpoint
                if (endpoint == null) {
                    WirelessLink.log("התקבל קוד אבל עדיין לא נמצא חלון התאמה.")
                } else {
                    runPairing(endpoint.host, endpoint.port, code)
                }
            }
            ACTION_MANUAL -> {
                val port = intent.getIntExtra(EXTRA_PORT, 0)
                val code = intent.getStringExtra(EXTRA_CODE).orEmpty().trim()
                val host = MdnsDiscovery.wifiIpv4(applicationContext) ?: "127.0.0.1"
                runPairing(host, port, code)
            }
            ACTION_STOP -> {
                stopRequested = true
                WirelessLink.setState(WirelessState.Idle)
                finish()
            }
            else -> finish()
        }
        return START_NOT_STICKY
    }

    private fun startSearch() {
        if (searching || working) return
        searching = true
        stopRequested = false
        thread(name = "pairing-search", isDaemon = true) {
            WirelessLink.setState(WirelessState.WaitingForPairingDialog)
            WirelessLink.log("מחפש חלון התאמה. פתח בהגדרות: ניפוי באגים אלחוטי ← התאמת מכשיר באמצעות קוד")
            postNotification(buildNotification("התאמה למכשיר", "פתח בהגדרות את חלון \"התאמת מכשיר באמצעות קוד\"", false))
            val endpoint = MdnsDiscovery(applicationContext).find(MdnsDiscovery.PAIRING_TYPE, 180_000L) {
                WirelessLink.log(it)
            }
            searching = false
            if (stopRequested) return@thread
            if (endpoint == null) {
                WirelessLink.setState(WirelessState.Failed("לא נמצא חלון התאמה תוך 3 דקות."))
                WirelessLink.log("לא נמצא חלון התאמה.")
                finish()
                return@thread
            }
            // The pairing service is advertised on the Wi-Fi address shown in the dialog.
            pairingEndpoint = endpoint
            WirelessLink.log("נמצא חלון התאמה: ${endpoint.host}:${endpoint.port}")
            WirelessLink.setState(WirelessState.WaitingForCode(endpoint.host, endpoint.port))
            postNotification(buildNotification("הזן את קוד ההתאמה", "לחץ \"הזן קוד\" והקלד את 6 הספרות מהחלון", true))
        }
    }

    private fun runPairing(host: String, port: Int, code: String) {
        if (working) return
        if (port <= 0 || !code.matches(Regex("\\d{6}"))) {
            WirelessLink.setState(WirelessState.Failed("יש להזין פורט תקין וקוד בן 6 ספרות."))
            WirelessLink.log("קוד או פורט לא תקינים.")
            finish()
            return
        }
        working = true
        postNotification(buildNotification("התאמה למכשיר", "מבצע התאמה…", false))
        thread(name = "pairing-work", isDaemon = false) {
            val ok = WirelessLink.pairAndConnect(host, port, code)
            val text = if (ok) "המכשיר מחובר. אפשר לחזור לאפליקציה." else "ההתאמה או החיבור נכשלו. פרטים באפליקציה."
            postNotification(
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle(if (ok) "מחובר" else "נכשל")
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(openAppIntent())
                    .build(),
                RESULT_ID
            )
            working = false
            finish()
        }
    }

    private fun finish() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    private fun buildNotification(title: String, text: String, withInput: Boolean): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
        if (withInput) {
            val remoteInput = RemoteInput.Builder(KEY_CODE).setLabel("קוד התאמה (6 ספרות)").build()
            val intent = Intent(this, PairingService::class.java).setAction(ACTION_CODE)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getForegroundService(this, 1, intent, flags)
            val action = NotificationCompat.Action.Builder(android.R.drawable.ic_menu_edit, "הזן קוד", pending)
                .addRemoteInput(remoteInput)
                .build()
            builder.addAction(action)
        }
        return builder.build()
    }

    private fun postNotification(notification: Notification, id: Int = NOTIFICATION_ID) {
        try {
            getSystemService(NotificationManager::class.java).notify(id, notification)
        } catch (_: Exception) {
        }
    }

    private fun openAppIntent(): PendingIntent {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        return PendingIntent.getActivity(
            this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "התאמה למכשיר", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    companion object {
        private const val ACTION_START = "com.aw950aw.smartbackup.PAIR_START"
        private const val ACTION_CODE = "com.aw950aw.smartbackup.PAIR_CODE"
        private const val ACTION_MANUAL = "com.aw950aw.smartbackup.PAIR_MANUAL"
        private const val ACTION_STOP = "com.aw950aw.smartbackup.PAIR_STOP"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_CODE = "code"
        private const val KEY_CODE = "pairing_code"
        private const val CHANNEL_ID = "pairing"
        private const val NOTIFICATION_ID = 7420
        private const val RESULT_ID = 7421

        fun startSearch(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, PairingService::class.java).setAction(ACTION_START)
            )
        }

        fun pairManually(context: Context, port: Int, code: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PairingService::class.java)
                    .setAction(ACTION_MANUAL)
                    .putExtra(EXTRA_PORT, port)
                    .putExtra(EXTRA_CODE, code)
            )
        }

        fun stop(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, PairingService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
