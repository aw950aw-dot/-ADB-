package com.draco.ladb.viewmodels

import android.app.Application
import android.content.Context
import android.net.nsd.NsdManager
import android.os.Build
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.draco.ladb.R
import com.draco.ladb.utils.ADB
import com.draco.ladb.utils.DnsDiscover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class MainActivityViewModel(application: Application) : AndroidViewModel(application) {
    private val _outputText = MutableLiveData<String>()
    @Volatile private var outputStartOffset = 0L
    private val maxVisibleOutputBytes = 512 * 1024
    private var lastOutputSize = -1L
    private var lastOutputModified = -1L
    private var cachedOutput = ""
    val outputText: LiveData<String> = _outputText

    val isPairing = MutableLiveData<Boolean>()

    private val sharedPreferences = PreferenceManager
        .getDefaultSharedPreferences(application.applicationContext)

    val adb = ADB.getInstance(getApplication<Application>().applicationContext)
    val dnsDiscover =
        DnsDiscover.getInstance(
            application.applicationContext,
            application.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        )

    private val _viewModelHasStartedADB = MutableLiveData(false)
    val viewModelHasStartedADB: LiveData<Boolean> = _viewModelHasStartedADB
    private val startLock = Any()
    private var startInProgress = false
    private val startCallbacks = mutableListOf<(Boolean) -> Unit>()

    init {
        startOutputThread()
        dnsDiscover.scanAdbPorts()
    }


    fun startADBServer(callback: ((Boolean) -> Unit)? = null) {
        if (adb.running.value == true) {
            _viewModelHasStartedADB.postValue(true)
            callback?.invoke(true)
            return
        }
        val shouldStart = synchronized(startLock) {
            callback?.let { startCallbacks.add(it) }
            if (startInProgress) false
            else {
                startInProgress = true
                true
            }
        }
        if (!shouldStart) return

        viewModelScope.launch(Dispatchers.IO) {
            val success = runCatching { adb.initServer() }.getOrDefault(false)
            if (success) startShellDeathThread()
            _viewModelHasStartedADB.postValue(success)
            val callbacks = synchronized(startLock) {
                startInProgress = false
                startCallbacks.toList().also { startCallbacks.clear() }
            }
            callbacks.forEach { waiting -> runCatching { waiting(success) } }
        }
    }

    /**
     * Continuously update shell output
     */
    private fun startOutputThread() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val out = readOutputFile(adb.outputBufferFile)
                val currentText = _outputText.value
                if (out != currentText)
                    _outputText.postValue(out)
                delay(ADB.OUTPUT_BUFFER_DELAY_MS)
            }
        }
    }

    /**
     * Start a death listener to restart the shell once it dies
     */
    private fun startShellDeathThread() {
        adb.ensureShellDeathMonitor()
    }

    /**
     * Erase all shell text
     */
    fun clearOutputText() {
        synchronized(adb.outputBufferFile) {
            runCatching {
                java.io.RandomAccessFile(adb.outputBufferFile, "rw").use { it.setLength(0L) }
            }
            outputStartOffset = 0L
            lastOutputSize = -1L
            lastOutputModified = -1L
            cachedOutput = ""
        }
        _outputText.postValue("")
    }

    /**
     * Check if the user should be prompted to pair
     */
    fun needsToPair(): Boolean {
        val context = getApplication<Application>().applicationContext

        return !sharedPreferences.getBoolean(context.getString(R.string.paired_key), false) &&
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
    }

    fun setPairedBefore(value: Boolean) {
        val context = getApplication<Application>().applicationContext
        sharedPreferences.edit {
            putBoolean(context.getString(R.string.paired_key), value)
        }
    }

    /**
     * Read the content of the ABD output file
     */
    private fun readOutputFile(file: File): String {
        synchronized(file) {
            if (!file.exists()) {
                cachedOutput = ""
                outputStartOffset = 0L
                lastOutputSize = -1L
                lastOutputModified = -1L
                return ""
            }

            val size = file.length()
            val modified = file.lastModified()
            if (size == lastOutputSize && modified == lastOutputModified) return cachedOutput

            // The output file is periodically trimmed by ADB. Detect truncation/rotation
            // and rebuild the visible tail only when necessary.
            if (size < lastOutputSize || (size == lastOutputSize && modified != lastOutputModified)) {
                outputStartOffset = 0L
                cachedOutput = ""
            }

            val tailStart = (size - maxVisibleOutputBytes).coerceAtLeast(0L)
            val start = if (outputStartOffset < tailStart) tailStart else outputStartOffset
            val length = (size - start).coerceAtLeast(0L)
                .coerceAtMost(maxVisibleOutputBytes.toLong()).toInt()
            val bytes = ByteArray(length)
            if (length > 0) {
                java.io.RandomAccessFile(file, "r").use { input ->
                    input.seek(start)
                    input.readFully(bytes)
                }
            }

            val chunk = String(bytes, Charsets.UTF_8)
            cachedOutput = if (start > 0L && outputStartOffset == 0L) {
                "… הפלט הישן קוצר כדי לשמור על תגובה מהירה. …\n$chunk"
            } else {
                cachedOutput + chunk
            }
            if (cachedOutput.length > maxVisibleOutputBytes) {
                cachedOutput = "… תחילת הפלט הוסרה …\n" + cachedOutput.takeLast(maxVisibleOutputBytes)
            }

            outputStartOffset = size
            lastOutputSize = size
            lastOutputModified = modified
            return cachedOutput
        }
    }
}