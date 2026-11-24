package com.aw950aw.smartbackup.shell

import com.aw950aw.smartbackup.wireless.WirelessLink
import com.aw950aw.smartbackup.wireless.WirelessState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** The one place that answers "which privileged shell can I use right now?". */
object Privileges {
    val rootShell = MutableStateFlow<RootShell?>(null)
    val rootChecking = MutableStateFlow(false)
    val rootMessage = MutableStateFlow<String?>(null)

    /** Root wins when available (it can read private app data); otherwise Shizuku. */
    fun currentShell(): PrivilegedShell? {
        val root = rootShell.value
        if (root != null) return root
        val wireless = (WirelessLink.state.value as? WirelessState.Ready)?.shell
        if (wireless != null) return wireless
        return (ShizukuLink.state.value as? ShizukuState.Ready)?.shell
    }

    suspend fun probeRoot() {
        rootChecking.value = true
        rootMessage.value = null
        try {
            val found = withContext(Dispatchers.IO) { RootShell.probe() }
            rootShell.value = found
            if (found == null) rootMessage.value = "לא נמצאה גישת Root במכשיר, או שהבקשה נדחתה."
        } finally {
            rootChecking.value = false
        }
    }

    fun dropRoot() {
        rootShell.value = null
    }
}
