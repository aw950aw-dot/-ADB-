package com.draco.ladb.utils

import android.Manifest
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
import com.draco.ladb.views.ConnectionActivity

/** Inline Wireless Debugging pairing reply. The reply is handled by a receiver, never by opening the app. */
object PairingNotification {
    const val INPUT_KEY = "wireless_pairing_code"
    const val ACTION_PAIR = "com.aw950aw.adbsmart.ACTION_PAIR_FROM_NOTIFICATION"
    private const val CHANNEL_ID = "wireless_debugging_pairing_v2"
    private const val NOTIFICATION_ID = 4106

    private fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "שיוך Wireless Debugging",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "הזנת קוד השיוך של Android מתוך ההתראה"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 120, 250)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, ConnectionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context, NOTIFICATION_ID + 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun show(context: Context, statusText: String = "הקלד את קוד 6 הספרות ולחץ „אישור אוטומטי”") {
        if (!allowed(context)) return
        try {
            ensureChannel(context)
            val replyIntent = Intent(context, PairingReplyReceiver::class.java).apply { action = ACTION_PAIR }
            val replyPendingIntent = PendingIntent.getBroadcast(
                context, NOTIFICATION_ID, replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            val remoteInput = RemoteInput.Builder(INPUT_KEY)
                .setLabel("קוד שיוך בן 6 ספרות")
                .build()
            val action = NotificationCompat.Action.Builder(
                R.drawable.ic_baseline_adb_24,
                "אישור אוטומטי",
                replyPendingIntent
            ).addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(false)
                .setShowsUserInterface(false)
                .build()

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_baseline_adb_24)
                .setContentTitle("שיוך Wireless Debugging")
                .setContentText(statusText)
                .setContentIntent(openAppIntent(context))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(false)
                .setOngoing(true)
                .setOnlyAlertOnce(false)
                .addAction(action)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            // The manual code and port fields remain available if notifications are blocked.
        }
    }

    fun showResult(context: Context, success: Boolean, message: String) {
        if (!allowed(context)) return
        try {
            ensureChannel(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_baseline_adb_24)
                .setContentTitle(if (success) "שיוך Wireless Debugging הצליח" else "שיוך Wireless Debugging נכשל")
                .setContentText(message)
                .setContentIntent(openAppIntent(context))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(false)
                .setOnlyAlertOnce(false)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            // Do not interrupt the system Settings screen.
        }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }
}
