package com.akira.island.island

import com.akira.island.data.IslandSettings
import com.akira.island.data.QuickAction
import org.junit.Assert.*
import org.junit.Test

class QuickLaunchTest {
    @Test fun recentsAreNewestFirstWithoutDuplicatesAndCapped() {
        RecentApps.packages.value = emptyList()
        listOf("a", "b", "a", "c").forEach(RecentApps::push)
        assertEquals(listOf("c", "a", "b"), RecentApps.packages.value)
        repeat(30) { RecentApps.push("p$it") }
        assertEquals(24, RecentApps.packages.value.size)
        assertEquals("p29", RecentApps.packages.value.first())
    }

    @Test fun launcherOnlyAppearsWhileOpenAndUnlocked() {
        val idle = primaryWithPendingAlarm(emptyList(), true, Act.Launcher.id)
        assertEquals(Act.Idle, idle)
        assertEquals(Act.Launcher, primaryWithPendingAlarm(listOf(Act.Launcher), true, Act.Launcher.id))
    }

    @Test fun defaultToolsSkipTheTimerStepper() {
        val tools = IslandSettings().launcherTools
        assertFalse(QuickAction.TIMER in tools)
        assertTrue(QuickAction.WIFI in tools && QuickAction.QUICK_SETTINGS in tools)
    }
}
