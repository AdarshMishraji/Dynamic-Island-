package com.akira.island.ui

import android.app.Activity
import android.os.Bundle
import android.provider.AlarmClock
import com.akira.island.core.Controls
import com.akira.island.data.SettingsRepo

/**
 * "Set a timer for 5 minutes" from a voice assistant, a widget or another app: if you choose
 * "Dynamic Island", the timer starts right away in the island, without "Clock" and without delay, with its buttons.
 */
class SetTimerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsRepo.init(this)
        // Under Android rules a timer is from 1 second to a day
        val seconds = (intent?.getIntExtra(AlarmClock.EXTRA_LENGTH, 0) ?: 0).coerceAtMost(86_400)
        if (seconds > 0) Controls.startTimerSeconds(seconds) else Controls.startTimer(SettingsRepo.current.timerMinutes)
        Controls.expand("timer")
        finish()
    }
}
