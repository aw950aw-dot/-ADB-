package com.aw950aw.smartbackup.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aw950aw.smartbackup.shell.PrivilegedShell
import com.aw950aw.smartbackup.shell.Privileges
import com.aw950aw.smartbackup.shell.ShizukuLink
import com.aw950aw.smartbackup.shell.ShizukuState
import kotlinx.coroutines.launch

private const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/"

private fun openShizuku(context: Context) {
    try {
        val intent = context.packageManager.getLaunchIntentForPackage(ShizukuLink.SHIZUKU_PACKAGE)
        if (intent != null) context.startActivity(intent)
    } catch (_: Exception) {
    }
}

private fun openDownloadPage(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_DOWNLOAD_URL)))
    } catch (_: Exception) {
    }
}

@Composable
fun HomeScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shizuku by ShizukuLink.state.collectAsStateWithLifecycle()
    val root by Privileges.rootShell.collectAsStateWithLifecycle()
    val rootChecking by Privileges.rootChecking.collectAsStateWithLifecycle()
    val rootMessage by Privileges.rootMessage.collectAsStateWithLifecycle()
    val shell: PrivilegedShell? = root ?: (shizuku as? ShizukuState.Ready)?.shell

    ScreenScaffold(title = "גיבוי חכם", onBack = null) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "גיבוי ושחזור של אפליקציות עם הנתונים שלהן, ישירות מהמכשיר.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ---- connection --------------------------------------------------------------
            SectionCard(title = "חיבור למערכת") {
                if (shell != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusPill("מחובר", good = true)
                        Text(shell.label, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        if (shell.isRoot) {
                            "יש גישת Root, ולכן אפשר לגבות גם נתונים פרטיים של האפליקציות."
                        } else {
                            "נתוני Android/data, מדיה, OBB וקובצי ה-APK זמינים. נתונים פרטיים זמינים רק עם Root או באפליקציות debug."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    when (val state = shizuku) {
                        ShizukuState.Unknown, ShizukuState.Binding -> {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                Text("בודק חיבור ל-Shizuku…")
                            }
                        }
                        ShizukuState.NotInstalled -> {
                            StatusPill("Shizuku לא מותקן", good = false)
                            Text(
                                "האפליקציה מקבלת הרשאות מערכת דרך Shizuku, בלי Root. Shizuku מטפל בעצמו בכל החיבור האלחוטי, ולכן אין צורך בשיוך בתוך האפליקציה הזו.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            NumberedSteps(
                                listOf(
                                    "הורד והתקן את Shizuku (הכפתור למטה).",
                                    "פתח את Shizuku ובחר \"הפעלה באמצעות ניפוי באגים אלחוטי\".",
                                    "בהגדרות המפתח הפעל \"ניפוי באגים אלחוטי\" ובצע \"התאמה באמצעות קוד\". את הקוד מזינים בהתראה של Shizuku.",
                                    "חזור לכאן ולחץ \"בדוק שוב\"."
                                )
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { openDownloadPage(context) }) { Text("הורד Shizuku") }
                                OutlinedButton(onClick = { ShizukuLink.refresh() }) { Text("בדוק שוב") }
                            }
                        }
                        ShizukuState.NotRunning -> {
                            StatusPill("Shizuku לא פועל", good = false)
                            Text(
                                "Shizuku מותקן אך כבוי. הוא נעצר בכל הפעלה מחדש של המכשיר, ויש להפעיל אותו שוב.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            NumberedSteps(
                                listOf(
                                    "פתח את Shizuku (הכפתור למטה).",
                                    "בחר \"הפעלה באמצעות ניפוי באגים אלחוטי\" ובצע את ההתאמה אם מתבקש.",
                                    "כשמופיע \"Shizuku פועל\", חזור לכאן ולחץ \"בדוק שוב\"."
                                )
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { openShizuku(context) }) { Text("פתח Shizuku") }
                                OutlinedButton(onClick = { ShizukuLink.refresh() }) { Text("בדוק שוב") }
                            }
                        }
                        ShizukuState.NeedsPermission -> {
                            StatusPill("נדרש אישור", good = false)
                            Text("Shizuku פועל. נשאר לאשר לאפליקציה הזו להשתמש בו.", style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { ShizukuLink.requestPermission() }) { Text("אשר גישה") }
                        }
                        ShizukuState.PermissionDenied -> {
                            StatusPill("הגישה נדחתה", good = false)
                            Text(
                                "כדי לאשר: פתח את Shizuku, היכנס לרשימת האפליקציות המורשות ואפשר את \"גיבוי חכם\". אחר כך חזור ולחץ \"בדוק שוב\".",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { openShizuku(context) }) { Text("פתח Shizuku") }
                                OutlinedButton(onClick = { ShizukuLink.requestPermission() }) { Text("נסה שוב") }
                            }
                        }
                        is ShizukuState.Failed -> {
                            StatusPill("שגיאה", good = false)
                            Text(state.message, style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = { ShizukuLink.refresh() }) { Text("בדוק שוב") }
                        }
                        is ShizukuState.Ready -> {
                            // handled above through [shell]
                        }
                    }
                }
            }

            // ---- actions -----------------------------------------------------------------
            ActionCard(
                title = "גיבוי אפליקציה",
                description = "בחר אפליקציה ושמור אותה, עם הנתונים שלה, בקובץ ZIP אחד.",
                enabled = shell != null,
                onClick = { vm.openPicker() }
            )
            ActionCard(
                title = "שחזור מקובץ",
                description = "בחר קובץ גיבוי ושחזר את האפליקציה והנתונים.",
                enabled = shell != null,
                onClick = { vm.openRestore() }
            )

            // ---- root --------------------------------------------------------------------
            SectionCard(title = "יש לך Root?") {
                Text(
                    "אם המכשיר עם Root אפשר להשתמש בו במקום Shizuku, וכך לגבות גם נתונים פרטיים של אפליקציות רגילות.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (rootChecking) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text("ממתין לאישור Root…")
                    }
                } else if (root != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusPill("Root פעיל", good = true)
                        OutlinedButton(onClick = { Privileges.dropRoot() }) { Text("נתק") }
                    }
                } else {
                    OutlinedButton(onClick = { scope.launch { Privileges.probeRoot() } }) { Text("בדוק Root") }
                }
                if (rootMessage != null) {
                    Text(rootMessage ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ActionCard(title: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val container = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val content = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    SectionCard(
        title = null,
        container = container,
        modifier = Modifier
            .clickable(enabled = enabled) { onClick() }
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = content)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = content)
        if (!enabled) {
            Text("זמין אחרי החיבור", style = MaterialTheme.typography.labelMedium, color = content)
        }
    }
}
