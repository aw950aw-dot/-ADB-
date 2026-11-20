package com.draco.ladb.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.draco.ladb.R
import com.draco.ladb.backup.AppBackupManager
import com.draco.ladb.utils.ADB
import com.draco.ladb.views.AppTransferActivity

/**
 * Runs one app backup or restore as a foreground service. Long jobs therefore keep running when
 * the screen is rotated, split-screen is entered or the user switches to another app (which is
 * exactly what the Wireless Debugging flow requires).
 */
class BackupService : Service() {
    companion object {
        private const val ACTION_BACKUP = "com.aw950aw.adbsmart.action.BACKUP"
        private const val ACTION_RESTORE = "com.aw950aw.adbsmart.action.RESTORE"
        private const val EXTRA_PACKAGE = "package_name"
        private const val CHANNEL_ID = "backup_jobs"
        private const val ONGOING_ID = 7410
        private const val RESULT_ID = 7411

        /** @return false when another job is already running or the service could not start. */
        fun startBackup(context: Context, packageName: String, destination: Uri): Boolean =
            start(context, Intent(context, BackupService::class.java).apply {
                action = ACTION_BACKUP
                putExtra(EXTRA_PACKAGE, packageName)
                data = destination
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            })

        fun startRestore(context: Context, source: Uri): Boolean =
            start(context, Intent(context, BackupService::class.java).apply {
                action = ACTION_RESTORE
                data = source
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })

        private fun start(context: Context, intent: Intent): Boolean {
            if (BackupJobState.isRunning) return false
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private var lastNotificationText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val restore = intent?.action == ACTION_RESTORE
        val title = if (restore) "משחזר אפליקציה" else "יוצר גיבוי"
        // Must be called promptly after startForegroundService(), whatever happens next.
        ServiceCompat.startForeground(
            this, ONGOING_ID, ongoingNotification(title, "מתחיל…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        val uri = intent?.data
        if (intent == null || uri == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // A second request while a job runs is ignored; the running job keeps the service alive.
        if (!BackupJobState.tryStart(title)) return START_NOT_STICKY

        val packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        Thread {
            val wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "adbsmart:backup-job")
            runCatching { wakeLock.acquire(2L * 60L * 60L * 1000L) }
            val result = try {
                if (restore) runRestore(uri) else runBackup(packageName, uri)
            } catch (error: Throwable) {
                BackupJobState.Result(
                    false,
                    if (restore) "השחזור נכשל" else "הגיבוי נכשל",
                    friendlyError(error)
                )
            }
            runCatching { if (wakeLock.isHeld) wakeLock.release() }
            finishJob(result)
        }.apply { name = "BackupJob"; isDaemon = false; start() }
        return START_NOT_STICKY
    }

    private fun runBackup(packageName: String, uri: Uri): BackupJobState.Result {
        val title = "יוצר גיבוי"
        val adb = ADB.getInstance(applicationContext)
        var completed = false
        try {
            val summary = contentResolver.openOutputStream(uri, "wt")?.use { output ->
                AppBackupManager.createBackup(applicationContext, adb, packageName, output) { report(title, it) }
            } ?: error("לא ניתן לכתוב למיקום שנבחר.")
            completed = true
            return BackupJobState.Result(true, "הגיבוי הושלם", formatBackupSummary(summary))
        } finally {
            // Do not leave a half-written ZIP behind.
            if (!completed) runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
        }
    }

    private fun runRestore(uri: Uri): BackupJobState.Result {
        val title = "משחזר אפליקציה"
        val adb = ADB.getInstance(applicationContext)
        val text = contentResolver.openInputStream(uri)?.use { input ->
            AppBackupManager.restoreBackup(applicationContext, adb, input) { report(title, it) }
        } ?: error("לא ניתן לקרוא את קובץ הגיבוי.")
        return BackupJobState.Result(true, "תוצאות השחזור", text)
    }

    private fun formatBackupSummary(summary: AppBackupManager.BackupSummary): String = buildString {
        append("נוצר גיבוי עבור ${summary.appLabel}.\n\n")
        append(if (summary.rootAvailable) "Root זמין במכשיר המקור.\n" else "Root לא זמין במכשיר המקור.\n")
        append(if (summary.privateDataIncluded) "נתונים פרטיים: נכללו.\n" else "נתונים פרטיים: לא נכללו.\n")
        append("סוגי נתונים שנכללו: ${summary.payloadNames.joinToString().ifBlank { "אין" }}\n")
        if (summary.notes.isNotEmpty()) append("\nהערות:\n" + summary.notes.joinToString("\n"))
    }

    private fun friendlyError(error: Throwable): String {
        val raw = error.message ?: error.javaClass.simpleName
        return when {
            error is OutOfMemoryError -> "אין מספיק זיכרון לביצוע הפעולה."
            raw.contains("ENOSPC") || raw.contains("No space left", ignoreCase = true) ->
                "אין מספיק מקום פנוי באחסון. פנה מקום ונסה שוב."
            else -> raw
        }
    }

    private fun report(title: String, message: String) {
        BackupJobState.update(title, message)
        if (message == lastNotificationText) return
        lastNotificationText = message
        getSystemService(NotificationManager::class.java)
            .notify(ONGOING_ID, ongoingNotification(title, message))
    }

    private fun finishJob(result: BackupJobState.Result) {
        BackupJobState.finish(result)
        runCatching {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_baseline_adb_24)
                .setContentTitle(result.title)
                .setContentText(result.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty())
                .setStyle(NotificationCompat.BigTextStyle().bigText(result.text))
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build()
            getSystemService(NotificationManager::class.java).notify(RESULT_ID, notification)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ongoingNotification(title: String, message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_adb_24)
            .setContentTitle(title)
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(openAppIntent())
            .build()

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, AppTransferActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "גיבוי ושחזור", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
