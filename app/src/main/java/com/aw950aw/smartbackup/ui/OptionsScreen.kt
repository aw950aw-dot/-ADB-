package com.aw950aw.smartbackup.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aw950aw.smartbackup.backup.AppDetail
import com.aw950aw.smartbackup.backup.formatBytes
import com.aw950aw.smartbackup.shell.Privileges
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Suggested file name for the ZIP, safe for any storage provider. */
fun suggestedBackupName(label: String, versionName: String): String {
    val safeLabel = label.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('_').ifEmpty { "app" }
    val safeVersion = versionName.replace(Regex("[^A-Za-z0-9._-]+"), "_")
    val date = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    return if (safeVersion.isEmpty()) "$safeLabel-$date.zip" else "$safeLabel-$safeVersion-$date.zip"
}

@Composable
fun OptionsScreen(vm: MainViewModel, onStart: (String) -> Unit) {
    val app = vm.selected
    val detail = vm.detail
    val options = vm.options
    val shell = Privileges.currentShell()
    val privateAvailable = (shell != null && shell.isRoot) || (detail != null && detail.debuggable)

    ScreenScaffold(title = "אפשרויות גיבוי", onBack = { vm.goBack() }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (app != null) {
                SectionCard(title = null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        AppIcon(app.packageName, size = 56)
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (detail != null) {
                        DetailLines(detail)
                    } else if (vm.detailError != null) {
                        Text(vm.detailError ?: "", color = MaterialTheme.colorScheme.error)
                    } else {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
            }

            SectionCard(title = "מה לכלול בגיבוי") {
                OptionRow("קובצי APK של האפליקציה", options.apk, true) { vm.options = options.copy(apk = it) }
                OptionRow("נתוני Android/data", options.externalData, true) { vm.options = options.copy(externalData = it) }
                OptionRow("מדיה (Android/media)", options.media, true) { vm.options = options.copy(media = it) }
                OptionRow("קובצי OBB (משחקים)", options.obb, true) { vm.options = options.copy(obb = it) }
                OptionRow("נתונים פרטיים של האפליקציה", options.privateData && privateAvailable, privateAvailable) {
                    vm.options = options.copy(privateData = it)
                }
                if (!privateAvailable) {
                    Text(
                        "נתונים פרטיים (הגדרות, מסד נתונים, התקדמות במשחקים) נגישים רק עם Root, או באפליקציה במצב debug. בלי זה נגבה כל השאר.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val anything = options.apk || options.externalData || options.media || options.obb ||
                (options.privateData && privateAvailable)
            Button(
                onClick = {
                    if (app != null) onStart(suggestedBackupName(app.label, detail?.versionName ?: ""))
                },
                enabled = app != null && detail != null && anything,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("בחר יעד ושמור גיבוי")
            }
            Text(
                "הגיבוי רץ ברקע עם התראה, אפשר לעבור לאפליקציות אחרות בזמן שהוא מתבצע.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun DetailLines(detail: AppDetail) {
    val version = if (detail.versionName.isEmpty()) "${detail.versionCode}" else "${detail.versionName} (${detail.versionCode})"
    Text("גרסה: $version", style = MaterialTheme.typography.bodyMedium)
    Text(
        "גודל APK: ${formatBytes(detail.apkBytes)}" +
            if (detail.splitCount > 0) " · ${detail.splitCount} קובצי Split" else "",
        style = MaterialTheme.typography.bodyMedium
    )
    if (detail.debuggable) {
        Text("אפליקציית debug: אפשר לגבות גם נתונים פרטיים.", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun OptionRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onChange(it) }, enabled = enabled)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
