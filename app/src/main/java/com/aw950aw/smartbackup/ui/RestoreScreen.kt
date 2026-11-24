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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aw950aw.smartbackup.backup.BackupManifest
import com.aw950aw.smartbackup.backup.formatBytes
import com.aw950aw.smartbackup.backup.partTitle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RestoreScreen(vm: MainViewModel, onPick: () -> Unit, onStart: () -> Unit) {
    val manifest = vm.restoreManifest

    ScreenScaffold(title = "שחזור מגיבוי", onBack = { vm.goBack() }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionCard(title = "קובץ גיבוי") {
                Text(
                    "בחר קובץ ZIP שנוצר באפליקציה הזו. לפני השחזור תראה מה הוא מכיל.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (vm.restoreBusy) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text("קורא את הקובץ…")
                    }
                } else {
                    OutlinedButton(onClick = onPick) {
                        Text(if (manifest == null) "בחר קובץ גיבוי" else "בחר קובץ אחר")
                    }
                }
                if (vm.restoreError != null) {
                    Text(vm.restoreError ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (manifest != null) {
                SummaryCard(manifest, vm.restoreInstalledVersion)

                SectionCard(title = "מה לשחזר") {
                    if (manifest.apks.isNotEmpty()) {
                        CheckRow("התקנת האפליקציה (${manifest.apks.size} קבצים)", vm.restoreInstallApk) {
                            vm.restoreInstallApk = it
                        }
                    }
                    for (part in manifest.parts) {
                        val checked = part.entry in vm.restoreParts
                        CheckRow("${partTitle(part.kind)} · ${formatBytes(part.size)}", checked) { on ->
                            vm.restoreParts = if (on) vm.restoreParts + part.entry else vm.restoreParts - part.entry
                        }
                    }
                    if (manifest.apks.isEmpty() && manifest.parts.isEmpty()) {
                        Text("הגיבוי ריק.", color = MaterialTheme.colorScheme.error)
                    }
                }

                val anything = vm.restoreInstallApk || vm.restoreParts.isNotEmpty()
                Button(onClick = onStart, enabled = anything, modifier = Modifier.fillMaxWidth()) {
                    Text("התחל שחזור")
                }
                Text(
                    "קובצי הגיבוי נבדקים מול סכומי בדיקה לפני שחזור, ואם הגיבוי פגום השחזור נעצר.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SummaryCard(manifest: BackupManifest, installedVersion: String?) {
    val created = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date(manifest.created))
    SectionCard(title = null) {
        Text(manifest.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(manifest.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val version = if (manifest.versionName.isEmpty()) "${manifest.versionCode}" else "${manifest.versionName} (${manifest.versionCode})"
        Text("גרסה בגיבוי: $version", style = MaterialTheme.typography.bodyMedium)
        Text("נוצר: $created", style = MaterialTheme.typography.bodyMedium)
        if (manifest.sourceLabel.isNotEmpty()) {
            Text("נוצר דרך: ${manifest.sourceLabel}", style = MaterialTheme.typography.bodyMedium)
        }
        if (installedVersion != null) {
            Text(
                "מותקנת במכשיר גרסה: ${installedVersion.ifEmpty { "לא ידועה" }}",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Text("האפליקציה אינה מותקנת כרגע במכשיר.", style = MaterialTheme.typography.bodyMedium)
        }
        if (manifest.notes.isNotEmpty()) {
            Text("הערות מהגיבוי:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            for (note in manifest.notes) {
                Text("• $note", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onChange(it) })
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
