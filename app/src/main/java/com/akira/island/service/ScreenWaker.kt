package com.akira.island.service

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Turns on a screen that is off when a timer rings or an incoming call arrives. The main way is the flag of the island
 * window (IslandService.setAlarmScreen); these are fallbacks: a screen alarm from the system, and if that
 * doesn't work either (the firmware doesn't allow it), a transparent app screen over the lock screen.
 */
object ScreenWaker {
    fun wake(context: Context, scope: CoroutineScope, stillNeeded: () -> Boolean) {
        val power = context.getSystemService(PowerManager::class.java) ?: return
        if (power.isInteractive) return
        scope.launch {
            @Suppress("DEPRECATION")
            val wakeLock = runCatching {
                power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "DynamicIsland:alert")
                    .apply { acquire(3_000) }
            }.getOrNull()
            try {
                delay(800)
                if (power.isInteractive || !stillNeeded()) return@launch
                runCatching {
                    context.startActivity(Intent(context, WakeActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
                }
            } finally {
                runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
            }
        }
    }
}
