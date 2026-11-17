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
 * Notification-only pairing flow.
 *
 * The reply PendingIntent targets a BroadcastReceiver and never launches an Activity.
 * Keeping the notification free of a content PendingIntent prevents tapping the
 * notification surface from unexpectedly bringing the app over Android Settings.
 */
object PairingNotification {
    const val INPUT_KEY = "wireless_pairing_code"
    const val ACTION_PAIR = "com.aw950aw.adbsmart.ACTION_PAIR_FROM_NOTIFICATION"
    private const val CHANNEL_ID = "wireless_debugging_pairing_v3"
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

    @SuppressLint("MissingPermission")
    fun show(
        context: Context,
        statusText: String = "הקלד את קוד 6 הספרות ולחץ „שלח קוד והתחבר”"
    ) {
        if (!allowed(context)) return
        try {
            ensureChannel(context)

            // Mutable is required by Android RemoteInput. It remains an explicit
            // broadcast PendingIntent, not an Activity PendingIntent.
            val replyIntent = Intent(context, PairingReplyReceiver::class.java).apply {
                action = ACTION_PAIR
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
                // Deliberately no content PendingIntent: the notification itself
                // must not navigate away from the Android Settings screen.
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(action)
                .build()

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            // Manual code/port fields remain available if notifications are blocked.
        }
    }

    fun workingNotification(context: Context): android.app.Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_adb_24)
            .setContentTitle("משייך את המכשיר ברקע")
            .setContentText("מסך ההגדרות נשאר פתוח. מאתר פורט ומבצע שיוך ל־ADB…")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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
                    if (success) "שיוך Wireless Debugging הושלם"
                    else "שיוך Wireless Debugging נכשל"
                )
                .setContentText(message)
                // No Activity intent here either. A result notification must not
                // pull the app in front of Settings when the background work ends.
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
