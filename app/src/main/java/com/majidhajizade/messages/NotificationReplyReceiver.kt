package com.majidhajizade.messages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import androidx.core.app.RemoteInput

class NotificationReplyReceiver : BroadcastReceiver() {

    companion object {
        private const val REPLY_KEY = "reply_text"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val phone = intent.getStringExtra("phone") ?: return
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(REPLY_KEY)
            ?.toString()
            ?.trim()

        if (reply.isNullOrBlank()) return

        if (context.checkSelfPermission(
                android.Manifest.permission.SEND_SMS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        runCatching {
            val normalized = normalizePhoneNumber(phone)
            SmsManager.getDefault().sendTextMessage(
                normalized,
                null,
                reply,
                null,
                null
            )
        }
    }

    private fun normalizePhoneNumber(value: String): String {
        val number = value.trim()
            .replace(" ", "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "")

        return when {
            number.startsWith("+98") -> number
            number.startsWith("0098") -> "+" + number.substring(2)
            number.startsWith("98") -> "+" + number
            number.startsWith("09") -> "+98" + number.substring(1)
            else -> number
        }
    }
}
