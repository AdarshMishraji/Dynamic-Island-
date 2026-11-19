package com.akira.island.island

import com.akira.island.core.AlarmInfo
import org.junit.Assert.*
import org.junit.Test

class PendingAlarmPresentationTest {
    private val alarm = Act.AlarmA(AlarmInfo(123456L, null, null))
    private val other = Act.InboxA(emptyList())

    @Test fun alarmAloneLeavesTheCapsuleIdleAndUsesTheBubble() {
        assertEquals(Act.Idle, primaryWithPendingAlarm(listOf(alarm), false, null))
        assertEquals(alarm, secondaryWithPendingAlarm(listOf(alarm), Act.Idle))
    }
    @Test fun explicitAlarmSelectionOpensItsCardUntilCollapse() {
        assertEquals(alarm, primaryWithPendingAlarm(listOf(alarm), true, "alarm"))
        assertEquals(Act.Idle, primaryWithPendingAlarm(listOf(alarm), false, "alarm"))
        assertEquals(Act.Idle, primaryWithPendingAlarm(listOf(alarm), true, "idle"))
    }
    @Test fun otherActivitiesKeepTheirPriorityAndAreNotDuplicated() {
        assertEquals(other, primaryWithPendingAlarm(listOf(alarm, other), false, null))
        assertEquals(alarm, secondaryWithPendingAlarm(listOf(alarm, other), other))
        assertNull(secondaryWithPendingAlarm(listOf(other), other))
    }
    @Test fun noAlarmPreservesTheExistingFallback() {
        assertEquals(Act.Idle, primaryWithPendingAlarm(emptyList(), false, null))
        assertEquals(other, primaryWithPendingAlarm(listOf(other), false, null))
        assertEquals(other, secondaryWithPendingAlarm(listOf(alarm, other), alarm))
    }
}

class ThirdActivityTest {
    private val timer = Act.TimerA(com.akira.island.core.TimerState(60_000, 0))
    private val stopwatch = Act.StopwatchA(com.akira.island.core.StopwatchState(null, 0))
    private val inbox = Act.InboxA(emptyList())

    @Test fun thirdIsTheNextActivityAfterMainAndSecond() {
        val acts = listOf(timer, stopwatch, inbox)
        assertEquals(inbox, thirdActivity(acts, timer, stopwatch))
    }

    @Test fun noSecondMeansNoThird() = assertNull(thirdActivity(listOf(timer, stopwatch, inbox), timer, null))

    @Test fun twoActivitiesLeaveNoThird() = assertNull(thirdActivity(listOf(timer, stopwatch), timer, stopwatch))

    @Test fun transientCardsNeverFillAPill() {
        val notice = Act.NoticeA(com.akira.island.core.Notice("x", null, androidx.compose.ui.graphics.vector.ImageVector.Builder(
            "t", androidx.compose.ui.unit.Dp(1f), androidx.compose.ui.unit.Dp(1f), 1f, 1f).build(), androidx.compose.ui.graphics.Color.White))
        assertNull(thirdActivity(listOf(timer, stopwatch, notice), timer, stopwatch))
    }
}
