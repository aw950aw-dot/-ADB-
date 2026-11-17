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

/** Inline notification reply for entering the Wireless Debugging pairing code. */
object PairingNotification {
    const val INPUT_KEY = "wireless_pairing_code"
    const val ACTION_PAIR = "com.aw950aw.adbsmart.ACTION_PAIR_FROM_NOTIFICATION"
    private const val CHANNEL_ID = "wireless_debugging_pairing"
    private const val NOTIFICATION_ID = 4106

    fun show(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "שיוך Wireless Debugging",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "הזנת קוד השיוך של Android מתוך ההתראה"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
                manager.createNotificationChannel(channel)
            }

            val openIntent = Intent(context, ConnectionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openPendingIntent = PendingIntent.getActivity(
                context, NOTIFICATION_ID + 1, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val replyIntent = Intent(context, ConnectionActivity::class.java).apply {
                action = ACTION_PAIR
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            // RemoteInput requires a mutable PendingIntent so Android can attach the typed reply.
            val replyPendingIntent = PendingIntent.getActivity(
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
                .build()

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_baseline_adb_24)
                .setContentTitle("שיוך Wireless Debugging")
                .setContentText("הקלד את קוד 6 הספרות ולחץ „אישור אוטומטי”")
                .setContentIntent(openPendingIntent)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(false)
                .setOngoing(false)
                .addAction(action)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // The app still provides a manual code field when notifications are disabled.
        } catch (_: Exception) {
            // Notification problems must not block the pairing screen.
        }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }
}
