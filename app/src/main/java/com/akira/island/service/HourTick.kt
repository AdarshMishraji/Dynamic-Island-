package com.akira.island.service

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.WbTwilight
import com.akira.island.core.IslandBus
import com.akira.island.core.Notice
import com.akira.island.core.Tints
import com.akira.island.core.tr
import com.akira.island.data.SettingsRepo
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Times of day the island marks with a glyph of its own. */
enum class DayMoment(val hour: Int, val minute: Int) {
    SUNRISE(6, 0), AFTERNOON(12, 0), SUNSET(17, 30), MIDNIGHT(23, 0);

    val label: String get() = when (this) {
        SUNRISE -> tr("Рассвет", "Sunrise")
        AFTERNOON -> tr("День", "Afternoon")
        SUNSET -> tr("Закат", "Sunset")
        MIDNIGHT -> tr("Полночь", "Midnight")
    }
}

/** When the next tick is due. Pure, so it can be tested without a clock. */
internal object HourTicks {
    data class Tick(val at: Long, val moment: DayMoment?)

    fun next(nowMs: Long, hourly: Boolean, moments: Boolean, zone: TimeZone = TimeZone.getDefault()): Tick? {
        if (!hourly && !moments) return null
        val cal = Calendar.getInstance(zone)
        val found = ArrayList<Tick>()
        if (hourly) {
            cal.timeInMillis = nowMs
            cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            cal.add(Calendar.HOUR_OF_DAY, 1)
            found += Tick(cal.timeInMillis, if (moments) momentAt(cal) else null)
        }
        if (moments) DayMoment.entries.forEach { m ->
            cal.timeInMillis = nowMs
            cal.set(Calendar.HOUR_OF_DAY, m.hour); cal.set(Calendar.MINUTE, m.minute)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            if (cal.timeInMillis <= nowMs) cal.add(Calendar.DAY_OF_YEAR, 1)
            found += Tick(cal.timeInMillis, m)
        }
        return found.minByOrNull { it.at }
    }

    private fun momentAt(cal: Calendar): DayMoment? =
        DayMoment.entries.firstOrNull { it.hour == cal.get(Calendar.HOUR_OF_DAY) && it.minute == cal.get(Calendar.MINUTE) }
}

/** A short banner on the hour, and at sunrise, afternoon, sunset and midnight; only while the screen is on. */
object HourTick {
    fun start(context: Context, scope: CoroutineScope): Job = scope.launch {
        combine(SettingsRepo.state.map { it.hourTick to it.dayMoments }.distinctUntilChanged(), IslandBus.screenOn) { cfg, on ->
            if (on) cfg else null
        }.collectLatest { cfg ->
            if (cfg == null) return@collectLatest
            while (isActive) {
                val now = System.currentTimeMillis()
                val tick = HourTicks.next(now, cfg.first, cfg.second) ?: return@collectLatest
                delay(tick.at - now)
                // A sleeping phone can wake long after the hour: a tick minutes late is no use
                if (abs(System.currentTimeMillis() - tick.at) < 60_000) IslandBus.post(notice(context, tick))
                delay(1_000)
            }
        }
    }

    private var demoStep = 0

    /** For "Try it": the plain hour, then sunrise, afternoon, sunset and midnight, one per call. */
    fun demo(context: Context) {
        val moment = listOf<DayMoment?>(null) + DayMoment.entries
        IslandBus.post(notice(context, HourTicks.Tick(System.currentTimeMillis(), moment[demoStep++ % moment.size])))
    }

    internal fun notice(context: Context, tick: HourTicks.Tick): Notice {
        val time = DateFormat.getTimeFormat(context).format(Date(tick.at))
        return when (tick.moment) {
            DayMoment.SUNRISE -> Notice(time, DayMoment.SUNRISE.label, Icons.Rounded.WbTwilight, Tints.Orange)
            DayMoment.AFTERNOON -> Notice(time, DayMoment.AFTERNOON.label, Icons.Rounded.WbSunny, Tints.Yellow)
            DayMoment.SUNSET -> Notice(time, DayMoment.SUNSET.label, Icons.Rounded.WbTwilight, Tints.Red)
            DayMoment.MIDNIGHT -> Notice(time, DayMoment.MIDNIGHT.label, Icons.Rounded.Bedtime, Tints.Purple)
            null -> Notice(time, null, Icons.Rounded.Schedule, Tints.Blue)
        }
    }
}
