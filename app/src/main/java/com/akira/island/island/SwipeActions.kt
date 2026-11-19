package com.akira.island.island

import android.content.Context
import com.akira.island.core.Controls
import com.akira.island.core.IslandBus
import com.akira.island.data.IslandSettings
import com.akira.island.data.SwipeAction
import com.akira.island.service.NotifListener
import com.akira.island.service.PayMonitor
import kotlin.math.abs

enum class SwipeDir { UP, DOWN, LEFT, RIGHT }

/** Which way a drag went once it passed its threshold; vertical needs [threshold], horizontal needs [side]. */
internal fun swipeDirection(dx: Float, dy: Float, threshold: Float, side: Float): SwipeDir? {
    val vertical = abs(dy) > abs(dx)
    return when {
        vertical && dy > threshold -> SwipeDir.DOWN
        vertical && dy < -threshold -> SwipeDir.UP
        !vertical && dx > side -> SwipeDir.RIGHT
        !vertical && dx < -side -> SwipeDir.LEFT
        else -> null
    }
}

/** The action chosen for [dir], or [SwipeAction.DEFAULT] when the island should do what it always did. */
internal fun IslandSettings.swipeActionFor(dir: SwipeDir, expanded: Boolean): SwipeAction =
    if (expanded) { if (dir == SwipeDir.DOWN) expandedSwipeDown else SwipeAction.DEFAULT }
    else when (dir) {
        SwipeDir.UP -> swipeUp
        SwipeDir.DOWN -> swipeDown
        SwipeDir.LEFT -> swipeLeft
        SwipeDir.RIGHT -> swipeRight
    }

/** Runs a configured swipe. Returns false for [SwipeAction.DEFAULT] (and nothing happened), so the caller falls back. */
internal fun handleCustomSwipe(
    context: Context, s: IslandSettings, dir: SwipeDir, expanded: Boolean, held: Boolean,
    act: Act, activities: List<Act>, haptic: () -> Unit,
): Boolean {
    if (held && dir == SwipeDir.UP && s.holdSwipeClears && (expanded || act is Act.NoticeA)) {
        haptic()
        Controls.clearAllNotifications()
        return true
    }
    val action = s.swipeActionFor(dir, expanded)
    if (action == SwipeAction.DEFAULT) return false
    haptic()
    runSwipeAction(context, action, act, activities)
    return true
}

internal fun runSwipeAction(context: Context, action: SwipeAction, act: Act, activities: List<Act>) {
    val media = IslandBus.media.value != null
    when (action) {
        SwipeAction.DEFAULT, SwipeAction.NOTHING -> Unit
        SwipeAction.EXPAND -> if (!act.transient) Controls.expand(act.id)
        SwipeAction.NEXT_CARD, SwipeAction.PREV_CARD -> if (activities.size > 1) {
            val i = activities.indexOfFirst { it.id == act.id }.coerceAtLeast(0)
            val next = Math.floorMod(i + if (action == SwipeAction.NEXT_CARD) 1 else -1, activities.size)
            IslandBus.focus.value = activities[next].id
        }
        SwipeAction.NEXT_TRACK -> if (media) Controls.next()
        SwipeAction.PREV_TRACK -> if (media) Controls.prev()
        SwipeAction.PLAY_PAUSE -> if (media) Controls.playPause()
        SwipeAction.DISMISS -> when {
            act is Act.NoticeA -> {
                val key = act.n.message?.key
                if (key != null && NotifListener.canDismiss(key)) NotifListener.dismiss(key) else IslandBus.notice.value = null
            }
            act is Act.PayA -> PayMonitor.clearReady()
            act is Act.CallA && act.c.incoming -> Controls.hideIncomingCall()
            else -> Unit
        }
        SwipeAction.CLEAR_ALL -> Controls.clearAllNotifications()
        SwipeAction.QUICK_LAUNCH -> Controls.openQuickLaunch()
        SwipeAction.SHADE -> Controls.openShade()
        SwipeAction.SCREENSHOT -> Controls.screenshot()
    }
}
