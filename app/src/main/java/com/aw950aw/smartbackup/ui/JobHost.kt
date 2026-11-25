package com.aw950aw.smartbackup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aw950aw.smartbackup.jobs.JobState

/**
 * Shows the running job and, afterwards, its result. The progress dialog can be hidden: the job keeps
 * running in the background and a small bar at the bottom lets you show it again or cancel.
 */
@Composable
fun JobHost() {
    val progress by JobState.progress.collectAsStateWithLifecycle()
    val outcome by JobState.outcome.collectAsStateWithLifecycle()
    var hidden by rememberSaveable { mutableStateOf(false) }
    var cancelling by rememberSaveable { mutableStateOf(false) }

    val running = progress
    LaunchedEffect(running == null) {
        if (running == null) {
            hidden = false
            cancelling = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (running != null && !hidden) {
            AlertDialog(
                onDismissRequest = { hidden = true },
                title = { Text(running.title) },
                text = {
                    Column {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(14.dp))
                        Text(if (cancelling) "מבטל…\n${running.message}" else running.message)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { hidden = true }) { Text("הסתר") }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            cancelling = true
                            JobState.cancel()
                        },
                        enabled = !cancelling
                    ) { Text(if (cancelling) "מבטל…" else "בטל") }
                }
            )
        }

        if (running != null && hidden) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 6.dp
            ) {
                Row(
                    Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            running.title,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            running.message.lineSequence().lastOrNull().orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = { hidden = false }) { Text("הצג") }
                    TextButton(
                        onClick = {
                            cancelling = true
                            JobState.cancel()
                        },
                        enabled = !cancelling
                    ) { Text(if (cancelling) "מבטל…" else "בטל") }
                }
            }
        }
    }

    val finished = outcome
    if (finished != null && running == null) {
        AlertDialog(
            onDismissRequest = { JobState.dismissOutcome() },
            title = { Text(finished.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(finished.text)
                }
            },
            confirmButton = {
                TextButton(onClick = { JobState.dismissOutcome() }) { Text("סגור") }
            }
        )
    }
}
