package com.akira.island.island

import com.akira.island.data.IslandSettings
import com.akira.island.data.SwipeAction
import org.junit.Assert.*
import org.junit.Test

class SwipeActionsTest {
    @Test fun directionNeedsTheRightThresholdForItsAxis() {
        assertEquals(SwipeDir.UP, swipeDirection(2f, -50f, 40f, 60f))
        assertEquals(SwipeDir.DOWN, swipeDirection(2f, 50f, 40f, 60f))
        assertEquals(SwipeDir.LEFT, swipeDirection(-70f, 3f, 40f, 60f))
        assertEquals(SwipeDir.RIGHT, swipeDirection(70f, 3f, 40f, 60f))
        // 50 is enough sideways only with the vertical threshold; sideways needs 60
        assertNull(swipeDirection(50f, 3f, 40f, 60f))
        assertNull(swipeDirection(3f, -20f, 40f, 60f))
    }

    @Test fun anAmbiguousDiagonalGoesToTheLongerAxis() {
        assertEquals(SwipeDir.UP, swipeDirection(30f, -55f, 40f, 60f))
        assertEquals(SwipeDir.RIGHT, swipeDirection(90f, -55f, 40f, 60f))
    }

    @Test fun defaultsKeepTheOldBehaviour() {
        val s = IslandSettings()
        SwipeDir.entries.forEach { assertEquals(SwipeAction.DEFAULT, s.swipeActionFor(it, expanded = false)) }
        assertEquals(SwipeAction.DEFAULT, s.swipeActionFor(SwipeDir.DOWN, expanded = true))
    }

    @Test fun eachDirectionHasItsOwnMapping() {
        val s = IslandSettings(swipeUp = SwipeAction.SHADE, swipeLeft = SwipeAction.NEXT_TRACK, expandedSwipeDown = SwipeAction.QUICK_LAUNCH)
        assertEquals(SwipeAction.SHADE, s.swipeActionFor(SwipeDir.UP, expanded = false))
        assertEquals(SwipeAction.NEXT_TRACK, s.swipeActionFor(SwipeDir.LEFT, expanded = false))
        assertEquals(SwipeAction.DEFAULT, s.swipeActionFor(SwipeDir.RIGHT, expanded = false))
        // the collapsed mappings don't leak into the expanded card
        assertEquals(SwipeAction.DEFAULT, s.swipeActionFor(SwipeDir.UP, expanded = true))
        assertEquals(SwipeAction.QUICK_LAUNCH, s.swipeActionFor(SwipeDir.DOWN, expanded = true))
    }
}
