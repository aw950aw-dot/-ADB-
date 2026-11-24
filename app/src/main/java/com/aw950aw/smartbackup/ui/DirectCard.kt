package com.aw950aw.smartbackup.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aw950aw.smartbackup.wireless.PairingService
import com.aw950aw.smartbackup.wireless.WirelessLink
import com.aw950aw.smartbackup.wireless.WirelessState
import kotlin.concurrent.thread

private fun openDeveloperSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (_: Exception) {
        }
    }
}

private fun copyLog(context: Context, text: String) {
    try {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("log", text))
    } catch (_: Exception) {
    }
}

/** Connects to the device by itself (pairing code once, then a normal connection), like Shizuku does. */
@Composable
fun DirectConnectionCard(state: WirelessState) {
    val context = LocalContext.current
    val log by WirelessLink.log.collectAsStateWithLifecycle()
    var port by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var showLog by rememberSaveable { mutableStateOf(false) }
    var showManual by rememberSaveable { mutableStateOf(false) }

    SectionCard(title = "חיבור ישיר למכשיר") {
        if (!WirelessLink.backendAvailable()) {
            Text(
                "גרסה זו נבנתה בלי רכיב החיבור הישיר. השתמש ב-Shizuku או ב-Root, או בנה מחדש.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            when (state) {
                WirelessState.Idle -> {
                    if (WirelessLink.isPaired()) {
                        Text(
                            "המכשיר כבר הותאם. כשניפוי באגים אלחוטי דלוק והמכשיר ב-Wi-Fi, לחץ \"התחבר\".",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { thread(isDaemon = true) { WirelessLink.connect() } }) { Text("התחבר") }
                            OutlinedButton(onClick = { PairingService.startSearch(context) }) { Text("התאמה מחדש") }
                        }
                    } else {
                        Text(
                            "האפליקציה מתחברת בעצמה, בלי Root ובלי אפליקציות נוספות. צריך התאמה אחת עם קוד, ואחר כך החיבור אוטומטי.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        NumberedSteps(
                            listOf(
                                "הפעל Wi-Fi והפעל \"ניפוי באגים אלחוטי\" באפשרויות מפתח.",
                                "לחץ \"התחל התאמה\" כאן.",
                                "בהגדרות בחר \"התאמת מכשיר באמצעות קוד\". תופיע התראה, והקוד מוקלד בה."
                            )
                        )
                        Button(onClick = { PairingService.startSearch(context) }) { Text("התחל התאמה") }
                    }
                }

                WirelessState.WaitingForPairingDialog -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text("מחכה לחלון ההתאמה…")
                    }
                    NumberedSteps(
                        listOf(
                            "פתח הגדרות מפתחים ← ניפוי באגים אלחוטי.",
                            "בחר \"התאמת מכשיר באמצעות קוד\".",
                            "כשהחלון נפתח, תופיע התראה עם שדה להקלדת הקוד."
                        )
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { openDeveloperSettings(context) }) { Text("פתח הגדרות מפתחים") }
                        OutlinedButton(onClick = { PairingService.stop(context) }) { Text("בטל") }
                    }
                }

                is WirelessState.WaitingForCode -> {
                    Text(
                        "נמצא חלון התאמה (${state.host}:${state.port}). הקלד את הקוד בהתראה, או כאן:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter { ch -> ch.isDigit() }.take(6) },
                        label = { Text("קוד התאמה (6 ספרות)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { PairingService.pairManually(context, state.port, code) },
                            enabled = code.length == 6
                        ) { Text("שייך") }
                        OutlinedButton(onClick = { PairingService.stop(context) }) { Text("בטל") }
                    }
                }

                is WirelessState.Busy -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text(state.message)
                    }
                }

                is WirelessState.Ready -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusPill("מחובר", good = true)
                        Text(state.shell.label, style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedButton(onClick = { WirelessLink.disconnect() }) { Text("נתק") }
                }

                is WirelessState.Failed -> {
                    StatusPill("נכשל", good = false)
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (WirelessLink.isPaired()) {
                            Button(onClick = { thread(isDaemon = true) { WirelessLink.connect() } }) { Text("נסה להתחבר שוב") }
                        }
                        OutlinedButton(onClick = { PairingService.startSearch(context) }) { Text("התאמה מחדש") }
                    }
                }
            }

            // Manual pairing, for when the notification flow is not convenient.
            if (state == WirelessState.Idle || state is WirelessState.Failed) {
                TextButton(onClick = { showManual = !showManual }) {
                    Text(if (showManual) "הסתר הזנה ידנית" else "הזנה ידנית של פורט וקוד")
                }
                if (showManual) {
                    Text(
                        "הפורט והקוד מוצגים בחלון \"התאמת מכשיר באמצעות קוד\" (פיצול מסך עוזר להשאיר אותו פתוח).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter { ch -> ch.isDigit() }.take(5) },
                        label = { Text("פורט ההתאמה") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter { ch -> ch.isDigit() }.take(6) },
                        label = { Text("קוד התאמה (6 ספרות)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { PairingService.pairManually(context, port.toIntOrNull() ?: 0, code) },
                        enabled = port.isNotEmpty() && code.length == 6
                    ) { Text("שייך והתחבר") }
                }
            }

            if (WirelessLink.isPaired()) {
                TextButton(onClick = { WirelessLink.forgetPairing() }) { Text("מחק התאמה") }
            }
        }

        // The log tells exactly where a connection stopped.
        TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "הסתר יומן חיבור" else "הצג יומן חיבור") }
        if (showLog) {
            val text = if (log.isEmpty()) "אין עדיין רשומות." else log.takeLast(25).joinToString("\n")
            Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { copyLog(context, log.joinToString("\n")) }) { Text("העתק יומן") }
                OutlinedButton(onClick = { WirelessLink.clearLog() }) { Text("נקה") }
            }
        }
    }
}
