package com.aw950aw.smartbackup.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aw950aw.smartbackup.jobs.JobState

/** Shows the running job (with a cancel button) and, afterwards, its result. Placed above every screen. */
@Composable
fun JobHost() {
    val progress by JobState.progress.collectAsStateWithLifecycle()
    val outcome by JobState.outcome.collectAsStateWithLifecycle()

    val running = progress
    if (running != null) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(running.title) },
            text = {
                Column {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(14.dp))
                    Text(running.message)
                }
            },
            confirmButton = {
                TextButton(onClick = { JobState.cancel() }) { Text("בטל") }
            }
        )
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
