package com.akira.island.service

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

/**
 * A transparent screen with no content: it turns the display on when the island timer rings, and closes right away.
 * A fallback (see ScreenWaker); usually the island window itself turns the screen on.
 */
class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        // The screen turns on as soon as the window is shown; after that the island with the ringing timer holds it
        window.decorView.postDelayed({ finish() }, 600)
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
