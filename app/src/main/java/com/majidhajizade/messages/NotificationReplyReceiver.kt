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
        val number = value.trim()
            .replace(" ", "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "")

        return when {
            number.startsWith("+") -> number
            number.startsWith("0098") -> "+" + number.substring(2)
            number.startsWith("98") -> "+" + number
            number.startsWith("09") -> "+98" + number.substring(1)
            else -> number
        }
    }
}
