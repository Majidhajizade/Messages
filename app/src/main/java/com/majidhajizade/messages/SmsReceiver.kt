package com.majidhajizade.messages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

        for (sms in messages) {
            val sender = sms.displayOriginatingAddress ?: continue
            val body = sms.messageBody ?: continue
            val timestamp = sms.timestampMillis

            val prefs = context.getSharedPreferences(
                "messages",
                Context.MODE_PRIVATE
            )

            val oldMessages = prefs.getStringSet(
                "received_messages",
                emptySet()
            )?.toMutableSet() ?: mutableSetOf()

            oldMessages.add("$timestamp|$sender|$body")

            prefs.edit()
                .putStringSet("received_messages", oldMessages)
                .apply()
        }
    }
}
