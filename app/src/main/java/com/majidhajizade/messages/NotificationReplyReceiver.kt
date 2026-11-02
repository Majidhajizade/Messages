package com.majidhajizade.messages

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.RemoteInput

class NotificationReplyReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotificationReply"
        private const val REPLY_KEY = "reply_text"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val phone = intent.getStringExtra("phone")
            ?.trim()
            .orEmpty()

        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(REPLY_KEY)
            ?.toString()
            ?.trim()

        if (phone.isBlank()) {
            Log.e(TAG, "Reply failed: destination phone is empty")
            showToast(context, "شماره مخاطب مشخص نیست")
            return
        }

        if (reply.isNullOrBlank()) {
            Log.w(TAG, "Reply ignored: empty message")
            return
        }

        // Prevent notification quick-reply from bypassing an active lock.
        if (
            AppSecurity.isEnabled(context) &&
            (
                AppSecurity.isAutoLockPending(context) ||
                AppSecurity.isChatLocked(context, phone)
            )
        ) {
            Log.w(TAG, "Reply blocked while app or conversation is locked")
            showToast(context, "برای پاسخ‌دادن ابتدا قفل Messages را باز کنید")
            return
        }

        if (
            context.checkSelfPermission(Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Reply failed: SEND_SMS permission is not granted")
            showToast(context, "مجوز ارسال پیامک فعال نیست")
            return
        }

        val normalizedPhone = normalizePhoneNumber(phone)

        if (normalizedPhone.isBlank()) {
            Log.e(TAG, "Reply failed: invalid destination phone")
            showToast(context, "شماره مخاطب معتبر نیست")
            return
        }

        // Prevent notification quick-reply from bypassing blocked contacts.
        val blockedNumbers = context.getSharedPreferences(
            "messages_settings",
            Context.MODE_PRIVATE
        ).getStringSet("blocked_numbers", emptySet()) ?: emptySet()

        val isBlocked = blockedNumbers.any { blockedNumber ->
            normalizePhoneNumber(blockedNumber) == normalizedPhone
        }

        if (isBlocked) {
            Log.w(TAG, "Reply blocked: destination is in blocked_numbers")
            showToast(context, "این مخاطب مسدود شده است؛ ابتدا او را از مسدودی خارج کنید")
            return
        }

        try {
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(reply)

            if (parts.isNullOrEmpty()) {
                Log.e(TAG, "Reply failed: SMS message could not be divided")
                showToast(context, "متن پیام قابل ارسال نیست")
                return
            }

            if (parts.size == 1) {
                smsManager.sendTextMessage(
                    normalizedPhone,
                    null,
                    parts[0],
                    null,
                    null
                )
            } else {
                smsManager.sendMultipartTextMessage(
                    normalizedPhone,
                    null,
                    parts,
                    null,
                    null
                )
            }

            Log.i(
                TAG,
                "Notification reply submitted to SmsManager; parts=${parts.size}"
            )
            showToast(context, "پاسخ برای ارسال ثبت شد")

        } catch (e: SecurityException) {
            Log.e(TAG, "Reply failed: SMS permission/security error", e)
            showToast(context, "اجازه ارسال پیامک داده نشد")

        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Reply failed: invalid phone number or message", e)
            showToast(context, "شماره یا متن پیام نامعتبر است")

        } catch (e: Exception) {
            Log.e(TAG, "Reply failed unexpectedly", e)
            showToast(context, "ارسال پاسخ ناموفق بود")
        }
    }

    private fun showToast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun normalizePhoneNumber(value: String): String {
        val digits = StringBuilder()
        var invalid = false

        for (char in value.trim()) {
            when {
                char in '۰'..'۹' ->
                    digits.append(('0'.code + char.code - '۰'.code).toChar())

                char in '٠'..'٩' ->
                    digits.append(('0'.code + char.code - '٠'.code).toChar())

                char in '0'..'9' || char == '+' ->
                    digits.append(char)

                char.isWhitespace() || char in "-()." ->
                    Unit

                else -> invalid = true
            }
        }

        if (invalid) return ""

        val number = digits.toString()

        val canonical = when {
            number.startsWith("0098") -> "+" + number.substring(2)
            number.startsWith("98") -> "+" + number
            number.startsWith("09") -> "+98" + number.substring(1)
            else -> number
        }

        return if (Regex("""\+?[0-9]{3,20}""").matches(canonical)) {
            canonical
        } else {
            ""
        }
    }
}
