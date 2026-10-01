package com.majidhajizade.messages

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView

class NewMessageActivity : Activity() {

    private val blue = Color.rgb(0, 122, 255)
    private val background = Color.WHITE
    private val secondaryText = Color.rgb(110, 110, 115)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        setContentView(createNewMessageScreen())
    }

    private fun createNewMessageScreen(): View {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
            setPadding(dp(20), dp(12), dp(20), dp(20))
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val cancel = TextView(this).apply {
            text = "Cancel"
            textSize = 17f
            setTextColor(blue)
            gravity = Gravity.CENTER_VERTICAL

            setOnClickListener {
                finish()
            }
        }

        header.addView(
            cancel,
            LinearLayout.LayoutParams(
                dp(80),
                dp(52)
            )
        )

        val title = TextView(this).apply {
            text = "New Message"
            textSize = 20f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        header.addView(
            title,
            LinearLayout.LayoutParams(
                0,
                dp(52),
                1f
            )
        )

        val spacer = Space(this)

        header.addView(
            spacer,
            LinearLayout.LayoutParams(
                dp(80),
                dp(52)
            )
        )

        root.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        )

        // Recipient label
        val recipientLabel = TextView(this).apply {
            text = "To:"
            textSize = 16f
            setTextColor(secondaryText)
        }

        root.addView(
            recipientLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(30)
            ).apply {
                topMargin = dp(25)
            }
        )

        // Phone number
        val phoneNumber = EditText(this).apply {
            hint = "Phone number"
            textSize = 18f
            setTextColor(Color.BLACK)
            setSingleLine(true)
            inputType =
                android.text.InputType.TYPE_CLASS_PHONE
            setPadding(dp(16), 0, dp(16), 0)
            setBackgroundColor(Color.rgb(242, 242, 247))
        }

        root.addView(
            phoneNumber,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50)
            )
        )

        // Message
        val message = EditText(this).apply {
            hint = "Message"
            textSize = 17f
            setTextColor(Color.BLACK)
            gravity = Gravity.TOP
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(Color.rgb(242, 242, 247))
        }

        root.addView(
            message,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(120)
            ).apply {
                topMargin = dp(16)
            }
        )

        // Send button
        val send = TextView(this).apply {
            text = "Send"
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setBackgroundColor(blue)

            setOnClickListener {
                // مرحله بعد:
                // ارسال واقعی SMS با شماره سیم‌کارت
            }
        }

        root.addView(
            send,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {
                topMargin = dp(20)
            }
        )

        return root
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
