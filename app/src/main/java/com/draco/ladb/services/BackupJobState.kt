package com.draco.ladb.services

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * Progress and result of the single backup / restore job that [BackupService] may be running.
 * Screens only observe this object, so a job survives rotation, split-screen and leaving the app.
 */
object BackupJobState {
    data class Progress(val running: Boolean, val title: String, val message: String)
    data class Result(val success: Boolean, val title: String, val text: String)

    private val progressData = MutableLiveData(Progress(false, "", ""))
    val progress: LiveData<Progress> = progressData

    private val resultData = MutableLiveData<Result?>(null)
    val result: LiveData<Result?> = resultData

    @Volatile
    var isRunning = false
        private set

    /** Returns false when another job is already running. */
    @Synchronized
    fun tryStart(title: String): Boolean {
        if (isRunning) return false
        isRunning = true
        resultData.postValue(null)
        progressData.postValue(Progress(true, title, "מתחיל…"))
        return true
    }

    fun update(title: String, message: String) {
        if (isRunning) progressData.postValue(Progress(true, title, message))
    }

    @Synchronized
    fun finish(result: Result) {
        isRunning = false
        progressData.postValue(Progress(false, "", ""))
        resultData.postValue(result)
    }

    /** Called by the screen once the user has seen (dismissed) the result. */
    fun consumeResult() {
        resultData.postValue(null)
    }
}
