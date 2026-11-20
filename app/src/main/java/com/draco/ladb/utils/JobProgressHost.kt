package com.draco.ladb.utils

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.draco.ladb.services.BackupJobState

/**
 * Shows the progress and the final result of the backup/restore job that runs in BackupService.
 * Attach it in onCreate and detach it in onDestroy; it re-appears by itself after rotation or
 * after the user comes back to the screen while a job is still running.
 */
class JobProgressHost(private val activity: AppCompatActivity) {
    private var progressDialog: AlertDialog? = null
    private var messageView: TextView? = null
    private var resultDialog: AlertDialog? = null
    private var userHidden = false

    fun attach() {
        BackupJobState.progress.observe(activity) { render(it) }
        BackupJobState.result.observe(activity) { showResult(it) }
    }

    fun detach() {
        runCatching { progressDialog?.dismiss() }
        progressDialog = null
        messageView = null
        resultDialog?.setOnDismissListener(null) // a rotation must not "consume" the result
        runCatching { resultDialog?.dismiss() }
        resultDialog = null
    }

    private fun render(progress: BackupJobState.Progress) {
        if (!progress.running) {
            userHidden = false
            runCatching { progressDialog?.dismiss() }
            progressDialog = null
            messageView = null
            return
        }
        if (userHidden || activity.isFinishing || activity.isDestroyed) return
        if (progressDialog == null) {
            val palette = UiTheme.palette(activity)
            val pad = UiComponents.dp(activity, 20)
            val message = TextView(activity).apply {
                textSize = 14f
                setTextColor(palette.text)
                gravity = Gravity.START
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, UiComponents.dp(activity, 6))
                addView(ProgressBar(activity).apply { isIndeterminate = true })
                addView(message, LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = UiComponents.dp(activity, 12)
                })
            }
            messageView = message
            progressDialog = AlertDialog.Builder(activity)
                .setTitle(progress.title)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("הסתר") { dialog, _ ->
                    userHidden = true
                    dialog.dismiss()
                    progressDialog = null
                    messageView = null
                }
                .show()
        }
        progressDialog?.setTitle(progress.title)
        messageView?.text = progress.message
    }

    private fun showResult(result: BackupJobState.Result?) {
        if (result == null || activity.isFinishing || activity.isDestroyed) return
        runCatching { resultDialog?.setOnDismissListener(null); resultDialog?.dismiss() }
        resultDialog = AlertDialog.Builder(activity)
            .setTitle(result.title)
            .setMessage(result.text)
            .setPositiveButton("סגור", null)
            .setOnDismissListener { BackupJobState.consumeResult() }
            .show()
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST = 7402

        /** The job runs even if this is denied; the notification just will not be visible. */
        fun ensureNotificationPermission(activity: Activity) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST
                )
            }
        }
    }
}
