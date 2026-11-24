package com.aw950aw.smartbackup.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aw950aw.smartbackup.backup.RestoreSelection
import com.aw950aw.smartbackup.jobs.JobRequest
import com.aw950aw.smartbackup.jobs.JobService

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current

    fun notifyBusy() {
        Toast.makeText(context, "גיבוי או שחזור אחר עדיין רץ. המתן לסיומו.", Toast.LENGTH_LONG).show()
    }

    fun startBackup(destination: Uri) {
        val app = vm.selected ?: return
        if (!JobService.start(context, JobRequest.Backup(app.packageName, vm.options, destination))) notifyBusy()
    }

    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) startBackup(uri)
    }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) vm.inspectRestore(uri)
    }

    BackHandler(enabled = vm.screen != Screen.HOME) { vm.goBack() }

    Box(Modifier.fillMaxSize()) {
        when (vm.screen) {
            Screen.HOME -> HomeScreen(vm)
            Screen.PICKER -> PickerScreen(vm)
            Screen.OPTIONS -> OptionsScreen(vm) { suggestedName -> createLauncher.launch(suggestedName) }
            Screen.RESTORE -> RestoreScreen(
                vm,
                onPick = { openLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                onStart = {
                    val file = vm.restoreFile
                    val manifest = vm.restoreManifest
                    if (file != null && manifest != null) {
                        val selection = RestoreSelection(vm.restoreInstallApk, vm.restoreParts)
                        if (JobService.start(context, JobRequest.Restore(file, manifest, selection))) {
                            // the job now owns the extracted ZIP and deletes it when it ends
                            vm.restoreManifest = null
                            vm.restoreFile = null
                        } else {
                            notifyBusy()
                        }
                    }
                }
            )
        }
        JobHost()
    }
}
