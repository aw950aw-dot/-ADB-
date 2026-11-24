package com.aw950aw.smartbackup.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aw950aw.smartbackup.backup.AppDetail
import com.aw950aw.smartbackup.backup.AppEntry
import com.aw950aw.smartbackup.backup.AppScanner
import com.aw950aw.smartbackup.backup.BackupManifest
import com.aw950aw.smartbackup.backup.BackupOptions
import com.aw950aw.smartbackup.backup.RestoreEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

enum class Screen { HOME, PICKER, OPTIONS, RESTORE }

class MainViewModel(application: Application) : AndroidViewModel(application) {

    var screen by mutableStateOf(Screen.HOME)

    // ---- app picker ------------------------------------------------------------------------
    var apps by mutableStateOf<List<AppEntry>>(emptyList())
    var appsLoading by mutableStateOf(false)
    var query by mutableStateOf("")
    var showSystem by mutableStateOf(false)

    // ---- backup options ----------------------------------------------------------------------
    var selected by mutableStateOf<AppEntry?>(null)
    var detail by mutableStateOf<AppDetail?>(null)
    var detailError by mutableStateOf<String?>(null)
    var options by mutableStateOf(BackupOptions())

    // ---- restore -----------------------------------------------------------------------------
    var restoreBusy by mutableStateOf(false)
    var restoreError by mutableStateOf<String?>(null)
    var restoreManifest by mutableStateOf<BackupManifest?>(null)
    var restoreFile by mutableStateOf<File?>(null)
    var restoreInstallApk by mutableStateOf(true)
    var restoreParts by mutableStateOf<Set<String>>(emptySet())
    var restoreInstalledVersion by mutableStateOf<String?>(null)

    fun openPicker() {
        screen = Screen.PICKER
        if (apps.isEmpty()) loadApps()
    }

    fun loadApps() {
        if (appsLoading) return
        appsLoading = true
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    AppScanner.load(getApplication<Application>())
                } catch (_: Exception) {
                    emptyList()
                }
            }
            apps = loaded
            appsLoading = false
        }
    }

    fun select(app: AppEntry) {
        selected = app
        detail = null
        detailError = null
        options = BackupOptions()
        screen = Screen.OPTIONS
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AppScanner.detail(getApplication<Application>(), app.packageName) }
            }
            detail = result.getOrNull()
            detailError = result.exceptionOrNull()?.message
        }
    }

    fun openRestore() {
        screen = Screen.RESTORE
    }

    fun goBack() {
        screen = when (screen) {
            Screen.OPTIONS -> Screen.PICKER
            else -> Screen.HOME
        }
    }

    /** Copies the chosen ZIP into the cache and reads its manifest, so the user sees what it contains. */
    fun inspectRestore(uri: Uri) {
        restoreBusy = true
        restoreError = null
        restoreManifest = null
        restoreFile?.parentFile?.deleteRecursively()
        restoreFile = null
        val context = getApplication<Application>()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val dir = File(context.cacheDir, "restore").apply {
                        deleteRecursively()
                        mkdirs()
                    }
                    val file = File(dir, "backup.zip")
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("לא ניתן לפתוח את הקובץ שנבחר.")
                    input.use { source ->
                        FileOutputStream(file).use { target -> source.copyTo(target, 256 * 1024) }
                    }
                    val manifest = RestoreEngine.readManifest(file)
                    val installedVersion = try {
                        AppScanner.packageInfo(context.packageManager, manifest.packageName).versionName ?: ""
                    } catch (_: Exception) {
                        null
                    }
                    Triple(file, manifest, installedVersion)
                } catch (error: Exception) {
                    error
                }
            }
            restoreBusy = false
            if (result is Triple<*, *, *>) {
                val file = result.first as File
                val manifest = result.second as BackupManifest
                restoreFile = file
                restoreManifest = manifest
                restoreInstalledVersion = result.third as String?
                restoreInstallApk = manifest.apks.isNotEmpty()
                restoreParts = manifest.parts.map { it.entry }.toSet()
            } else {
                restoreError = (result as? Exception)?.message ?: "קובץ הגיבוי אינו תקין."
            }
        }
    }

    fun clearRestore() {
        restoreFile?.parentFile?.deleteRecursively()
        restoreFile = null
        restoreManifest = null
        restoreError = null
    }
}
