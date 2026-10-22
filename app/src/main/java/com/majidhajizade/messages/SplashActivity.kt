package com.majidhajizade.messages

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View

class SplashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dark = (
            resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK
            ) == Configuration.UI_MODE_NIGHT_YES

        val backgroundColor = if (dark) Color.BLACK else Color.WHITE

        window.setBackgroundDrawable(ColorDrawable(backgroundColor))
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        window.decorView.systemUiVisibility = if (dark) {
            0
        } else {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        setContentView(View(this).apply {
            setBackgroundColor(backgroundColor)
        })

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            overridePendingTransition(0, 0)
        }, 180L)
    }
}
