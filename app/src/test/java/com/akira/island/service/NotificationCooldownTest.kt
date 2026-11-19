package com.akira.island.service

import org.junit.Assert.*
import org.junit.Test

class NotificationCooldownTest {
    @Test fun zeroCooldownNeverHoldsAnythingBack() {
        val c = NotificationCooldown()
        assertTrue(c.allows("a", 0, 0)); assertTrue(c.allows("a", 1, 0))
    }

    @Test fun secondBannerFromTheSameAppWaits() {
        val c = NotificationCooldown()
        assertTrue(c.allows("a", 1_000, 5_000))
        assertFalse(c.allows("a", 3_000, 5_000))
        assertTrue(c.allows("a", 6_000, 5_000))
    }

    @Test fun appsDoNotShareACooldown() {
        val c = NotificationCooldown()
        assertTrue(c.allows("a", 1_000, 5_000))
        assertTrue(c.allows("b", 1_100, 5_000))
    }

    @Test fun aHeldBackBannerDoesNotExtendTheWait() {
        val c = NotificationCooldown()
        assertTrue(c.allows("a", 0, 5_000))
        assertFalse(c.allows("a", 4_000, 5_000))
        assertTrue(c.allows("a", 5_000, 5_000))
    }
}
