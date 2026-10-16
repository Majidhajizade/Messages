package com.majidhajizade.messages

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SmsDeliveredReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_DELIVERED) return
        if (resultCode != Activity.RESULT_OK) return

        val messageId = intent.getStringExtra("message_id")
            ?.takeIf { it.isNotBlank() }
            ?: return

        val prefs = context.getSharedPreferences(
            "messages_settings",
            Context.MODE_PRIVATE
        )

        val deliveredIds = prefs.getStringSet(
            PREF_DELIVERED_MESSAGE_IDS,
            emptySet()
        )?.toMutableSet() ?: mutableSetOf()

        deliveredIds.add(messageId)

        val editor = prefs.edit()
            .putStringSet(PREF_DELIVERED_MESSAGE_IDS, deliveredIds)

        val rowId = prefs.getString("sms_row_for_message_$messageId", null)
        if (!rowId.isNullOrBlank()) {
            val deliveredRows = prefs.getStringSet(
                PREF_DELIVERED_ROW_IDS,
                emptySet()
            )?.toMutableSet() ?: mutableSetOf()

            deliveredRows.add(rowId)
            editor.putStringSet(PREF_DELIVERED_ROW_IDS, deliveredRows)
        }

        editor.apply()

        context.sendBroadcast(
            Intent(ACTION_DELIVERED_UI).apply {
                setPackage(context.packageName)
                putExtra("message_id", messageId)
            }
        )
    }

    companion object {
        const val ACTION_DELIVERED =
            "com.majidhajizade.messages.SMS_DELIVERED"

        const val ACTION_DELIVERED_UI =
            "com.majidhajizade.messages.SMS_DELIVERED_UI"

        const val PREF_DELIVERED_MESSAGE_IDS =
            "sms_delivered_message_ids"

        const val PREF_DELIVERED_ROW_IDS =
            "sms_delivered_row_ids"
    }
}
