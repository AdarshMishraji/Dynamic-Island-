package com.akira.island.service

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.akira.island.R
import com.akira.island.core.AlarmInfo
import com.akira.island.core.IslandBus
import com.akira.island.core.Notice
import com.akira.island.core.NoticeStyle
import com.akira.island.core.Tints
import com.akira.island.core.tr
import com.akira.island.data.SettingsRepo
import com.akira.island.island.Ios

/**
 * The nearest alarm. The system itself knows it (AlarmManager.getNextAlarmClock), so Clock notifications
 * are not needed for this. When a new alarm is set, the island shows a banner with the wake-up time;
 * the next one on the schedule (the previous rang or was snoozed) shows silently.
 * Calendars, reminders and sleep trackers also use the system alarm; the user did not set an "alarm" with them,
 * so we take only those set by an alarm app ("Clock", Alarmy, etc.).
 */
class AlarmMonitor(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = refresh(announce = true)
    }

    fun start() {
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
        refresh(announce = false)
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
        IslandBus.nextAlarm.value = null
    }

    private fun refresh(announce: Boolean) {
        val info = runCatching { alarms?.nextAlarmClock }.getOrNull()
        val next = info?.takeIf { a -> a.showIntent?.creatorPackage?.let { ClockApps.isAlarmApp(context, it) } == true }
            ?.let { AlarmInfo(it.triggerTime, it.showIntent?.creatorPackage, it.showIntent) }
        val prev = IslandBus.nextAlarm.value
        IslandBus.nextAlarm.value = next
        if (!announce || next == null || prev?.triggerAt == next.triggerAt) return
        val now = System.currentTimeMillis()
        if (next.triggerAt <= now) return
        // The previous alarm already rang or was snoozed; this is not new but the next one on the schedule
        if (prev != null && prev.triggerAt <= now + 60_000) return
        val s = SettingsRepo.current
        if (!s.enabled || !s.evAlarmSet) return
        IslandBus.post(
            Notice(
                title = tr("Будильник", "Alarm"),
                icon = Ios.load(context, R.drawable.ios_alarm),
                tint = Tints.Orange,
                style = NoticeStyle.ALARM,
                alarm = next,
                extraMs = 2_000,
            ),
        )
    }
}
