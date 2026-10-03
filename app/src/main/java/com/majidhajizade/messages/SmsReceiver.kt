package com.majidhajizade.messages

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "incoming_messages"
        private const val REPLY_KEY = "reply_text"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION &&
            intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return

        val prefs = context.getSharedPreferences("messages", Context.MODE_PRIVATE)
        val oldMessages = prefs.getStringSet(
            "received_messages",
            emptySet()
        )?.toMutableSet() ?: mutableSetOf()

        var sender = ""
        var body = ""
        var timestamp = System.currentTimeMillis()

        for (sms in messages) {
            sender = sms.displayOriginatingAddress ?: sender
            body += sms.messageBody ?: ""
            timestamp = sms.timestampMillis

            if (sender.isNotBlank()) {
                oldMessages.add("$timestamp|$sender|${sms.messageBody ?: ""}")
            }
        }

        prefs.edit()
            .putStringSet("received_messages", oldMessages)
            .apply()

        if (sender.isNotBlank() && body.isNotBlank()) {
            showNotification(context, sender, body)
        }
    }

    private fun showNotification(
        context: Context,
        phone: String,
        body: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = context.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming SMS messages"
            }
            manager.createNotificationChannel(channel)
        }

        val name = findContactName(context, phone)
        val title = if (name.isNullOrBlank()) phone else name

        val openIntent = Intent(
            context,
            ConversationActivity::class.java
        ).apply {
            putExtra("phone", phone)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val openPendingIntent = PendingIntent.getActivity(
            context,
            phone.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )

        val replyIntent = Intent(
            context,
            NotificationReplyReceiver::class.java
        ).apply {
            putExtra("phone", phone)
        }

        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            phone.hashCode() + 500000,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )

        val remoteInput = androidx.core.app.RemoteInput.Builder(REPLY_KEY)
            .setLabel("Reply")
            .build()

        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        val notification = NotificationCompat.Builder(
            context,
            CHANNEL_ID
        )
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(body)
            )
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .addAction(replyAction)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        manager.notify(phone.hashCode(), notification)
    }

    private fun findContactName(
        context: Context,
        phone: String
    ): String? {
        if (context.checkSelfPermission(
                android.Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI
            .buildUpon()
            .appendPath(phone)
            .build()

        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        }

        return null
    }

    private fun immutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }
}
