package com.akira.island.service

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class HourTicksTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(h: Int, m: Int, day: Int = 1): Long = Calendar.getInstance(utc).apply {
        clear(); set(2026, Calendar.NOVEMBER, day, h, m, 0)
    }.timeInMillis

    @Test fun nothingWhenBothAreOff() = assertNull(HourTicks.next(at(10, 15), false, false, utc))

    @Test fun hourlyIsTheNextFullHour() {
        val t = HourTicks.next(at(10, 15), true, false, utc)!!
        assertEquals(at(11, 0), t.at)
        assertNull(t.moment)
    }

    @Test fun exactlyOnTheHourWaitsForTheNextOne() = assertEquals(at(11, 0), HourTicks.next(at(10, 0), true, false, utc)!!.at)

    @Test fun momentsAloneSkipToTheNextOne() {
        val t = HourTicks.next(at(13, 0), false, true, utc)!!
        assertEquals(at(17, 30), t.at)
        assertEquals(DayMoment.SUNSET, t.moment)
    }

    @Test fun afterMidnightMomentRollsToTomorrowsSunrise() {
        val t = HourTicks.next(at(23, 30), false, true, utc)!!
        assertEquals(at(6, 0, day = 2), t.at)
        assertEquals(DayMoment.SUNRISE, t.moment)
    }

    @Test fun aMomentOnTheHourIsReportedAsThatMoment() {
        val t = HourTicks.next(at(11, 30), true, true, utc)!!
        assertEquals(at(12, 0), t.at)
        assertEquals(DayMoment.AFTERNOON, t.moment)
    }

    @Test fun aMomentOnTheHourIsPlainWhenMomentsAreOff() {
        assertNull(HourTicks.next(at(11, 30), true, false, utc)!!.moment)
    }

    @Test fun halfPastMomentBeatsTheNextHourWhenEarlier() {
        val t = HourTicks.next(at(17, 10), true, true, utc)!!
        assertEquals(at(17, 30), t.at)
        assertEquals(DayMoment.SUNSET, t.moment)
    }
}
