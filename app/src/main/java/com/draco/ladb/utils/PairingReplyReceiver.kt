package com.draco.ladb.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.draco.ladb.R

/** Processes the notification reply in the background without bringing ConnectionActivity over Android Settings. */
class PairingReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PairingNotification.ACTION_PAIR) return
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PairingNotification.INPUT_KEY)
            ?.toString()
            ?.filter { it.isDigit() }
            .orEmpty()

        if (code.length != 6) {
            PairingNotification.show(context, "יש להזין קוד שיוך בן 6 ספרות ולנסות שוב")
            return
        }

        val pending = goAsync()
        Thread {
            try {
                val port = PairingPortDiscovery(context).findPairingPort(25_000L)
                    ?: error("לא נמצא פורט שיוך. השאר את חלון „שיוך באמצעות קוד” פתוח ונסה שוב.")
                val adb = ADB.getInstance(context)
                if (!adb.pair(port.toString(), code)) {
                    error("השיוך נכשל. בדוק שהקוד עדיין בתוקף ונסה שוב.")
                }

                PreferenceManager.getDefaultSharedPreferences(context).edit {
                    putBoolean(context.getString(R.string.paired_key), true)
                }
                context.getSharedPreferences("pairing_state", Context.MODE_PRIVATE).edit {
                    putBoolean("connect_when_resumed", true)
                }
                PairingNotification.showResult(
                    context,
                    true,
                    "הקוד התקבל. חזור לאפליקציית ADB SMART כדי להשלים את החיבור."
                )
            } catch (error: Exception) {
                PairingNotification.show(
                    context,
                    error.message ?: "השיוך נכשל. אפשר לנסות שוב מתוך ההתראה."
                )
            } finally {
                pending.finish()
            }
        }.apply { isDaemon = true; name = "WirelessPairingReply" }.start()
    }
}
