package com.draco.ladb.utils

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.draco.ladb.R

/**
 * Inline pairing-code notification. The reply is a broadcast and never launches an Activity.
 * The discovered endpoint is attached so ADB can pair immediately without a second mDNS scan.
 */
object PairingNotification {
    const val INPUT_KEY = "wireless_pairing_code"
    const val ACTION_PAIR = "com.aw950aw.adbsmart.ACTION_PAIR_FROM_NOTIFICATION"
    const val EXTRA_PAIRING_PORT = "pairing_port"
    const val EXTRA_PAIRING_HOST = "pairing_host"
    private const val CHANNEL_ID = "wireless_debugging_pairing_v3"
    private const val PROGRESS_CHANNEL_ID = "wireless_debugging_progress_v1"
    private const val NOTIFICATION_ID = 4106
    const val WORKING_NOTIFICATION_ID = 4107

    private fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "שיוך Wireless Debugging",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "הזנת קוד השיוך של Android מתוך ההתראה, בלי לעזוב את מסך ההגדרות"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 220, 120, 220)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun ensureProgressChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    PROGRESS_CHANNEL_ID,
                    "איתור וחיבור ADB",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "התראת מצב שקטה בזמן איתור שירות הצימוד או חיבור ADB"
                    setShowBadge(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                }
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun show(
        context: Context,
        statusText: String = "הקלד את קוד 6 הספרות ולחץ „שלח קוד והתחבר”",
        pairingPort: Int? = null,
        pairingHost: String? = null
    ) {
        if (!allowed(context)) return
        try {
            ensureChannel(context)

            // RemoteInput requires a mutable explicit broadcast PendingIntent.
            val replyIntent = Intent(context, PairingReplyReceiver::class.java).apply {
                action = ACTION_PAIR
                if (pairingPort != null) putExtra(EXTRA_PAIRING_PORT, pairingPort)
                if (!pairingHost.isNullOrBlank()) putExtra(EXTRA_PAIRING_HOST, pairingHost)
            }
            val replyPendingIntent = PendingIntent.getBroadcast(
                context,
                NOTIFICATION_ID,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            val remoteInput = RemoteInput.Builder(INPUT_KEY)
                .setLabel("קוד שיוך בן 6 ספרות")
                .setAllowFreeFormInput(true)
                .build()

            val action = NotificationCompat.Action.Builder(
                R.drawable.ic_baseline_adb_24,
                "שלח קוד והתחבר",
                replyPendingIntent
            )
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(false)
                .setShowsUserInterface(false)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .build()

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_baseline_adb_24)
                .setContentTitle("שיוך Wireless Debugging")
                .setContentText(statusText)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(false)
                // Allow swipe-to-dismiss; this is not a permanent foreground notification.
                .setOngoing(false)
                // Re-alert when the pairing service is discovered while Settings is open.
                .setOnlyAlertOnce(false)
                .addAction(action)
                .build()

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            // Manual code/port fields remain available if notifications are blocked.
        }
    }

    fun workingNotification(context: Context): android.app.Notification {
        ensureProgressChannel(context)
        return NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_adb_24)
            .setContentTitle("מאתר את שירות הצימוד")
            .setContentText("כשחלון הצימוד ייפתח, תופיע התראת הזנת קוד.")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // Foreground work continues even if the user swipes this notification away.
            .setOngoing(false)
            .setOnlyAlertOnce(false)
            .build()
    }

    @SuppressLint("MissingPermission")
    fun cancelWorking(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(WORKING_NOTIFICATION_ID) }
    }

    @SuppressLint("MissingPermission")
    fun showResult(context: Context, success: Boolean, message: String) {
        if (!allowed(context)) return
        try {
            ensureChannel(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_baseline_adb_24)
                .setContentTitle(
                    if (success) "ADB מחובר בהצלחה"
                    else "חיבור ADB לא הושלם"
                )
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setOngoing(false)
                .setOnlyAlertOnce(false)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            // Pairing runs independently of the Settings UI.
        }
    }

    @SuppressLint("MissingPermission")
    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }
}
