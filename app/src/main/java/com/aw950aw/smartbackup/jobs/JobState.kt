package com.aw950aw.smartbackup.jobs

import android.net.Uri
import com.aw950aw.smartbackup.backup.BackupManifest
import com.aw950aw.smartbackup.backup.BackupOptions
import com.aw950aw.smartbackup.backup.RestoreSelection
import com.aw950aw.smartbackup.shell.CancelToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

sealed interface JobRequest {
    data class Backup(val packageName: String, val options: BackupOptions, val destination: Uri) : JobRequest
    data class Restore(val zipFile: File, val manifest: BackupManifest, val selection: RestoreSelection) : JobRequest
}

data class JobProgress(val title: String, val message: String)

data class JobOutcome(val success: Boolean, val title: String, val text: String)

/** The single running job, observed by the screens. Lives in the app process, so it survives screen changes. */
object JobState {
    private val progressFlow = MutableStateFlow<JobProgress?>(null)
    val progress: StateFlow<JobProgress?> = progressFlow

    private val outcomeFlow = MutableStateFlow<JobOutcome?>(null)
    val outcome: StateFlow<JobOutcome?> = outcomeFlow

    @Volatile
    private var pending: JobRequest? = null

    @Volatile
    private var token: CancelToken? = null

    val isRunning: Boolean get() = progressFlow.value != null || pending != null

    /** Returns false if a job is already running. */
    @Synchronized
    fun offer(request: JobRequest): Boolean {
        if (isRunning) return false
        pending = request
        outcomeFlow.value = null
        return true
    }

    @Synchronized
    fun take(title: String): Pair<JobRequest, CancelToken>? {
        val request = pending ?: return null
        pending = null
        val newToken = CancelToken()
        token = newToken
        progressFlow.value = JobProgress(title, "מתחיל…")
        return request to newToken
    }

    fun update(title: String, message: String) {
        if (progressFlow.value != null) progressFlow.value = JobProgress(title, message)
    }

    fun cancel() {
        token?.cancel()
    }

    @Synchronized
    fun finish(outcome: JobOutcome) {
        token = null
        progressFlow.value = null
        outcomeFlow.value = outcome
    }

    fun dismissOutcome() {
        outcomeFlow.value = null
    }
}
