package com.draco.ladb.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.draco.ladb.services.PairingService

/** Receives the inline code reply and passes the already-discovered pairing endpoint to ADB. */
class PairingReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PairingNotification.ACTION_PAIR) return

        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PairingNotification.INPUT_KEY)
            ?.toString()
            ?.filter { it in '0'..'9' }
            .orEmpty()

        if (code.length != 6) {
            PairingNotification.show(context, "הקוד לא תקין. יש להזין בדיוק 6 ספרות ולנסות שוב.")
            return
        }

        val serviceIntent = Intent(context, PairingService::class.java)
            .putExtra(PairingService.EXTRA_PAIRING_CODE, code)

        val pairingPort = intent.getIntExtra(PairingNotification.EXTRA_PAIRING_PORT, 0)
        val pairingHost = intent.getStringExtra(PairingNotification.EXTRA_PAIRING_HOST)
        if (pairingPort in 1..65535) {
            serviceIntent.putExtra(PairingService.EXTRA_PAIRING_PORT, pairingPort.toString())
        }
        if (!pairingHost.isNullOrBlank()) {
            serviceIntent.putExtra(PairingService.EXTRA_PAIRING_HOST, pairingHost)
        }

        try {
            // The code and discovered endpoint go directly to the pairing service.
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (error: Exception) {
            PairingNotification.show(
                context,
                error.message ?: "לא ניתן להתחיל את השיוך ברקע. פתח את האפליקציה ונסה שוב."
            )
        }
    }
}
