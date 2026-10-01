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

class MainActivity : Activity() {

    private val blue = Color.rgb(0, 122, 255)
    private val background = Color.WHITE
    private val secondaryText = Color.rgb(110, 110, 115)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        setContentView(createHomeScreen())
    }

    private fun createHomeScreen(): View {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "Messages"
            textSize = 34f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
        }

        header.addView(
            title,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val composeButton = TextView(this).apply {
            text = "✎"
            textSize = 30f
            setTextColor(blue)
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(4), 0)

            setOnClickListener {
                openNewMessage()
            }
        }

        header.addView(
            composeButton,
            LinearLayout.LayoutParams(
                dp(50),
                dp(50)
            )
        )

        root.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(58)
            )
        )

        // Search
        val search = EditText(this).apply {
            hint = "Search"
            textSize = 16f
            setSingleLine(true)
            setPadding(dp(16), 0, dp(16), 0)
            setBackgroundColor(Color.rgb(242, 242, 247))
        }

        root.addView(
            search,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(20)
            }
        )

        // Empty state
        val emptyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        val emptyTitle = TextView(this).apply {
            text = "No Messages"
            textSize = 22f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val emptySubtitle = TextView(this).apply {
            text = "Start a new conversation"
            textSize = 16f
            setTextColor(secondaryText)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }

        emptyContainer.addView(emptyTitle)
        emptyContainer.addView(emptySubtitle)

        root.addView(
            emptyContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        // Bottom new message button
        val newMessage = TextView(this).apply {
            text = "+  New Message"
            textSize = 17f
            setTextColor(blue)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setOnClickListener {
                openNewMessage()
            }
        }

        root.addView(
            newMessage,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(58)
            )
        )

        return root
    }

    private fun openNewMessage() {
        // مرحله بعد:
        // انتخاب مخاطب + وارد کردن شماره + ارسال SMS
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
