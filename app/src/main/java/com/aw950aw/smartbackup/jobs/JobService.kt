package com.aw950aw.smartbackup.jobs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aw950aw.smartbackup.backup.BackupEngine
import com.aw950aw.smartbackup.backup.RestoreEngine
import com.aw950aw.smartbackup.backup.formatBytes
import com.aw950aw.smartbackup.shell.Privileges
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.concurrent.thread

/**
 * Runs a backup or a restore as a foreground service, so it keeps going when the screen turns off
 * or the user switches to another app.
 */
class JobService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        // Must happen right after startForegroundService(), whatever comes next.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification("גיבוי חכם", "מתחיל…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        val taken = JobState.take("עובד…")
        if (taken == null) {
            // Nothing to do (for example a second tap while a job runs).
            if (!JobState.isRunning) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        val request = taken.first
        val cancel = taken.second
        val title = if (request is JobRequest.Backup) "גיבוי" else "שחזור"

        thread(name = "backup-job", isDaemon = false) {
            val wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smartbackup:job")
            try {
                wakeLock.acquire(3L * 60L * 60L * 1000L)
            } catch (_: Exception) {
            }

            val report: (String) -> Unit = { message ->
                JobState.update(title, message)
                postNotification(buildNotification(title, message))
            }

            val outcome = try {
                val shell = Privileges.currentShell()
                    ?: throw IllegalStateException("אין חיבור פעיל ל-Shizuku או ל-Root. חזור למסך הראשי והתחבר.")
                when (request) {
                    is JobRequest.Backup -> runBackup(request, shell, cancel, report)
                    is JobRequest.Restore -> runRestore(request, shell, cancel, report)
                }
            } catch (error: CancellationException) {
                JobOutcome(false, "$title בוטל", "הפעולה בוטלה לפי בקשתך.")
            } catch (error: Throwable) {
                JobOutcome(false, "$title נכשל", friendlyError(error))
            }

            try {
                if (wakeLock.isHeld) wakeLock.release()
            } catch (_: Exception) {
            }
            JobState.finish(outcome)
            postNotification(
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle(outcome.title)
                    .setContentText(outcome.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty())
                    .setStyle(NotificationCompat.BigTextStyle().bigText(outcome.text))
                    .setAutoCancel(true)
                    .setContentIntent(openAppIntent())
                    .build(),
                RESULT_ID
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun runBackup(
        request: JobRequest.Backup,
        shell: com.aw950aw.smartbackup.shell.PrivilegedShell,
        cancel: com.aw950aw.smartbackup.shell.CancelToken,
        report: (String) -> Unit
    ): JobOutcome {
        var completed = false
        try {
            val result = contentResolver.openOutputStream(request.destination, "wt")?.use { output ->
                BackupEngine.run(
                    applicationContext, shell, request.packageName, request.options, output, cancel, report
                )
            } ?: throw IllegalStateException("לא ניתן לכתוב למיקום שנבחר.")
            completed = true
            val text = buildString {
                append("נשמר גיבוי של ${result.label}.\n")
                append("גודל כולל: ${formatBytes(result.totalBytes)}\n")
                append("נכלל: ${result.included.joinToString(", ")}")
                if (result.notes.isNotEmpty()) append("\n\nהערות:\n" + result.notes.joinToString("\n") { "• $it" })
            }
            return JobOutcome(true, "הגיבוי הושלם", text)
        } finally {
            if (!completed) {
                // never leave a half written ZIP behind
                try {
                    DocumentsContract.deleteDocument(contentResolver, request.destination)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun runRestore(
        request: JobRequest.Restore,
        shell: com.aw950aw.smartbackup.shell.PrivilegedShell,
        cancel: com.aw950aw.smartbackup.shell.CancelToken,
        report: (String) -> Unit
    ): JobOutcome {
        try {
            val text = RestoreEngine.run(
                applicationContext, shell, request.zipFile, request.manifest, request.selection, cancel, report
            )
            return JobOutcome(true, "השחזור הושלם", text)
        } finally {
            request.zipFile.parentFile?.let { dir: File -> dir.deleteRecursively() }
        }
    }

    private fun friendlyError(error: Throwable): String {
        val raw = error.message ?: error.javaClass.simpleName
        return when {
            error is OutOfMemoryError -> "אין מספיק זיכרון לביצוע הפעולה."
            raw.contains("ENOSPC") || raw.contains("No space left", ignoreCase = true) ->
                "אין מספיק מקום פנוי באחסון. פנה מקום ונסה שוב."
            error is android.os.DeadObjectException || raw.contains("DeadObject") ->
                "החיבור ל-Shizuku נותק באמצע הפעולה. הפעל את Shizuku מחדש ונסה שוב."
            else -> raw
        }
    }

    private fun buildNotification(title: String, message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(openAppIntent())
            .build()

    private fun postNotification(notification: Notification, id: Int = NOTIFICATION_ID) {
        getSystemService(NotificationManager::class.java).notify(id, notification)
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
                NotificationChannel(CHANNEL_ID, "גיבוי ושחזור", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "backup_jobs"
        private const val NOTIFICATION_ID = 7410
        private const val RESULT_ID = 7411

        /** Starts the job. Returns false if another job is still running. */
        fun start(context: Context, request: JobRequest): Boolean {
            if (!JobState.offer(request)) return false
            ContextCompat.startForegroundService(context, Intent(context, JobService::class.java))
            return true
        }
    }
}
