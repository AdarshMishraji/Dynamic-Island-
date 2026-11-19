package com.akira.island.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.provider.AlarmClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.akira.island.core.ChronoInfo
import com.akira.island.core.Controls
import com.akira.island.core.IslandBus
import com.akira.island.core.trf
import com.akira.island.data.SettingsRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.abs

/** Clock apps: those that can set a timer, plus ones known by package. */
object ClockApps {
    private val known = setOf(
        "com.google.android.deskclock",
        "com.android.deskclock",
        "com.sec.android.app.clockpackage",
        "com.oneplus.deskclock",
        "com.coloros.alarmclock",
        "com.oplus.alarmclock",
        "com.huawei.deskclock",
        "com.motorola.timeweatherwidget",
    )
    private var found: Set<String>? = null

    fun packages(context: Context): Set<String> = found ?: (known + runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.queryIntentActivities(Intent(AlarmClock.ACTION_SET_TIMER), 0).map { it.activityInfo.packageName }
    }.getOrDefault(emptyList()) - context.packageName).also { found = it }

    fun isClock(context: Context, pkg: String) = pkg in packages(context)

    private var alarmApps: Set<String>? = null

    /** "Clock" and alarm apps (Alarmy, Sleep as Android...): those that set alarms or show their list */
    fun isAlarmApp(context: Context, pkg: String): Boolean = pkg in (alarmApps ?: (packages(context) +
        listOf(AlarmClock.ACTION_SET_ALARM, AlarmClock.ACTION_SHOW_ALARMS).flatMap { action ->
            runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentActivities(Intent(action), 0).map { it.activityInfo.packageName }
            }.getOrDefault(emptyList())
        }).also { alarmApps = it })
}

/** What a Clock button does. */
enum class ClockAction { START, PAUSE, RESET }

/** Clock button labels: for taps on screen, notification buttons and finding the button the island will press. */
object ClockWords {
    private val start = listOf("старт", "start", "начать", "пуск", "запуст", "продолж", "возобнов", "resume")
    private val pause = setOf("пауза", "pause", "стоп", "stop")
    private val reset = listOf("сброс", "reset", "удал", "delete", "отмен", "cancel", "очист", "clear")
    private val nonLetters = Regex("[^\\p{L}]+")

    /** Buttons on the Clock screen that the island looks for and presses itself: the full label. */
    val labels = mapOf(
        ClockAction.START to listOf("Старт", "Начать", "Продолжить", "Пуск", "Возобновить", "Start", "Resume"),
        ClockAction.PAUSE to listOf("Пауза", "Стоп", "Pause", "Stop"),
        ClockAction.RESET to listOf("Сбросить", "Сброс", "Удалить", "Reset", "Delete"),
    )

    /** By the start of the word; "stop" only as a whole word, otherwise "Stopwatch" would match. */
    fun action(label: CharSequence?): ClockAction? {
        val words = label?.toString()?.lowercase()?.split(nonLetters)?.filter { it.isNotEmpty() } ?: return null
        return when {
            words.any { w -> reset.any { w.startsWith(it) } } -> ClockAction.RESET
            words.any { w -> w in pause || w.startsWith("приостанов") } -> ClockAction.PAUSE
            words.any { w -> start.any { w.startsWith(it) } } -> ClockAction.START
            else -> null
        }
    }

    /** Buttons from the Clock notification: pause, resume, reset. */
    fun buttons(actions: Array<android.app.Notification.Action>?): Map<ClockAction, android.app.PendingIntent> {
        val out = HashMap<ClockAction, android.app.PendingIntent>()
        actions?.forEach { a ->
            val intent = a.actionIntent ?: return@forEach
            val kind = action(a.title) ?: return@forEach
            if (kind !in out) out[kind] = intent
        }
        return out
    }
}

/** Parsing of Clock screen text. Separate from Android so it can be checked with examples. */
object ClockScreenParser {
    /** «4:59», «00:04:59», «01:05,23», «1:02:03.4» */
    private val time = Regex("""^(?:(\d{1,2}):)?(\d{1,2}):(\d{2})(?:[.,](\d{1,3}))?$""")

    private val anywhere = Regex("""(?:\d{1,2}:)?\d{1,2}:\d{2}(?:[.,]\d{1,3})?""")

    /** A time anywhere in the text: "Осталось 4:59" -> 4:59. */
    fun find(text: CharSequence?): Long? = text?.let { anywhere.find(it)?.value }?.let { parse(it) }

    fun parse(text: String): Long? {
        val m = time.matchEntire(text.filterNot { it.isWhitespace() }) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: 0L
        val min = m.groupValues[2].toLong()
        val sec = m.groupValues[3].toLong()
        if (sec >= 60 || (m.groupValues[1].isNotEmpty() && min >= 60)) return null
        val frac = m.groupValues[4].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.toLong() ?: 0L
        return ((h * 60 + min) * 60 + sec) * 1000 + frac
    }
}

/**
 * The Clock app's timer and stopwatch, from its screen. If Clock has notifications turned off, Android
 * gives them to no one, and this is the only way to learn about the timer. While Clock is open, the island remembers
 * the time labels and updates them four times a second, and also receives screen changes and taps:
 * "Start" on the "Timer" tab puts the timer in the island at once, "Pause" freezes it, "Reset" removes it.
 * Without a tap, a timer is recognized by the time moving: decreasing is a timer, increasing is a stopwatch.
 * When the app is closed, the island keeps counting itself. Other apps can't be read this way.
 */
class ClockScreenReader(private val service: AccessibilityService, private val scope: CoroutineScope) {
    private enum class Kind { TIMER, STOPWATCH }

    /** A time label on screen; [key] is the view id or position, [height] marks the main label as larger */
    private class Display(val key: String, val node: AccessibilityNodeInfo, val height: Int)

    /** What was last seen in the label: the value, when it changed and how many changes in a row ticked "like a clock" */
    private class Seen(var value: Long, var changedAt: Long, var kind: Kind? = null, var streak: Int = 0, var fine: Boolean = false)

    /**
     * What the island shows: [base] is the wall-clock time of the timer's end or the stopwatch's start,
     * [pausedAt] is when it was paused (after [INACTIVE_MS] on pause the banner is removed)
     */
    private class Track(val key: String, val base: Long, val pausedMs: Long?, val pausedAt: Long = 0L)

    private fun pausedTrack(t: Track, value: Long) = Track(t.key, t.base, value, SystemClock.elapsedRealtime())

    val active: Boolean get() = current != null

    private var current: String? = null
    private var loop: Job? = null
    private var endJob: Job? = null
    private var displays: List<Display> = emptyList()
    private var tab: Kind? = null
    private var scannedAt = 0L
    private var lastTick = 0L
    private var needRescan = true
    private val seen = HashMap<String, Seen>()
    private var hint: ClockAction? = null
    /** What a tap refers to: the open tab or what the island itself pressed */
    private var hintKind: Kind? = null
    private var hintAt = 0L
    private var timer: Track? = null
    private var stopwatch: Track? = null
    private var timerMisses = 0
    private var stopwatchMisses = 0
    private val labels = HashMap<String, String>()

    /** The island presses a button itself: which one, for timer or stopwatch, until what time to wait, and whether it opened Clock itself */
    private class Pending(val action: ClockAction, val timer: Boolean, val until: Long, val opened: Boolean)
    private var pending: Pending? = null
    /** When Clock opened: how long the island has been watching its digits and knows whether they are running */
    private var openedAt = 0L
    /** When the island itself pressed a Clock button: Clock's echo "pressed" must not erase what it knows */
    private var ownClickAt = 0L
    private var cleanupJob: Job? = null

    fun onForeground(pkg: String) {
        if (!ClockApps.isClock(service, pkg)) {
            // On leaving Clock, we read it one last time: the window is still on screen, and a timer started
            // right before leaving will not be lost
            val left = current
            if (left != null && SettingsRepo.current.cardTimer) {
                runCatching { tick() }
                // Left the tab where it no longer exists (reset or deleted): the banner is not needed
                if (tab == Kind.TIMER && displays.none { it.key == timer?.key }) timer = null
                if (tab == Kind.STOPWATCH && displays.none { it.key == stopwatch?.key }) stopwatch = null
                publish(left)
            }
            stopReading()
            return
        }
        if (pkg == current) return
        stopReading()
        current = pkg
        openedAt = SystemClock.elapsedRealtime()
        loop = scope.launch {
            visiblePolling(IslandBus.screenOn, SettingsRepo.state.map { it.cardTimer }.distinctUntilChanged(), 250, { tick() })
        }
    }

    /** The Clock screen changed: read at once, without waiting for the next quarter second. */
    fun onContentChanged(event: AccessibilityEvent) {
        if (event.packageName?.toString() != current) return
        if (event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE != 0) needRescan = true
        if (SystemClock.elapsedRealtime() - lastTick >= 80) runCatching { tick() }
    }

    /** A button was pressed in Clock: "Start", "Pause", "Reset". The screen is about to change, so we read it several times. */
    fun onClicked(event: AccessibilityEvent) {
        if (event.packageName?.toString() != current) return
        // This is the echo of the island's own tap; it already knows what was pressed (the label in the echo may be empty)
        if (SystemClock.elapsedRealtime() - ownClickAt < 1_500) return
        val label = event.text.joinToString(" ") + " " + (event.contentDescription ?: "")
        // Label not recognized: we look at the button id; the round button in Google Clock sometimes starts and sometimes pauses
        val id = runCatching { event.source?.viewIdResourceName }.getOrNull().orEmpty().lowercase()
        val running = if (tab == Kind.STOPWATCH) stopwatch?.pausedMs == null && stopwatch != null else timer?.pausedMs == null && timer != null
        val action = ClockWords.action(label) ?: when {
            "reset" in id || "delete" in id -> ClockAction.RESET
            "pause" in id || ("stop" in id && "stopwatch" !in id) -> ClockAction.PAUSE
            "start" in id || "resume" in id || "play" in id -> ClockAction.START
            "fab" in id -> if (running) ClockAction.PAUSE else ClockAction.START
            else -> null
        }
        // Something else was pressed (a tab, "Lap"): we leave what is known about the previous press untouched
        if (action != null) {
            hintKind = tab
            hint = action
            hintAt = SystemClock.elapsedRealtime()
        }
        // After a tap the screen changes: we reread it at once, without waiting
        needRescan = true
        scannedAt = 0L
        scope.launch {
            for (wait in longArrayOf(60, 140, 250, 350)) {
                delay(wait)
                runCatching { tick() }
            }
        }
    }

    /**
     * The island's button, when the Clock notification button can't be pressed (notifications are off or it has no such button
     * in it): the island shows the result at once, opens Clock, presses the needed button itself and, if
     * it opened Clock itself, takes you back. [own] means the timer was read from the screen and this class keeps it going.
     */
    fun control(pkg: String, isTimer: Boolean, action: ClockAction, own: Boolean) {
        if (own) {
            val wall = System.currentTimeMillis()
            val next = (if (isTimer) timer else stopwatch)?.let { apply(it, isTimer, action, wall) }
            if (isTimer) timer = next else stopwatch = next
            publish(pkg)
        }
        val opened = current != pkg
        pending = Pending(action, isTimer, SystemClock.elapsedRealtime() + 4_000, opened)
        if (!opened) {
            scope.launch {
                delay(60)
                runCatching { tick() }
            }
            return
        }
        val intents = listOfNotNull(
            if (isTimer) Intent(AlarmClock.ACTION_SHOW_TIMERS).setPackage(pkg) else null,
            service.packageManager.getLaunchIntentForPackage(pkg),
        )
        val started = intents.any { intent ->
            runCatching { service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        }
        if (!started) pending = null
    }

    private fun apply(t: Track, isTimer: Boolean, action: ClockAction, wall: Long): Track? {
        val value = t.pausedMs ?: if (isTimer) (t.base - wall).coerceAtLeast(0) else wall - t.base
        return when (action) {
            ClockAction.PAUSE -> pausedTrack(t, value)
            ClockAction.START -> Track(t.key, if (isTimer) wall + value else wall - value, null)
            ClockAction.RESET -> null
        }
    }

    /**
     * Clock opened: we go to the right tab and press the button. A button with a label ("Pause") is pressed
     * at once; the round toggle button only if the timer is currently in another state: first the island
     * checks whether the digits are running. Reset is not visible while running, so pause first. If we opened Clock ourselves, go back after.
     */
    private fun performPending() {
        val p = pending ?: return
        val now = SystemClock.elapsedRealtime()
        if (now > p.until) {
            pending = null
            return
        }
        val root = service.rootInActiveWindow ?: return
        if (root.packageName?.toString() != current) return
        val want = if (p.timer) Kind.TIMER else Kind.STOPWATCH
        if (tab != null && tab != want) {
            if (clickTab(root, if (p.timer) TIMER_TABS else STOPWATCH_TABS)) needRescan = true
            return
        }
        val main = displays.maxByOrNull { it.height }
        val s = main?.let { seen[it.key] }
        val running = s != null && s.kind != null && now - s.changedAt < 1_200
        val known = running || now - openedAt > 1_300
        when (p.action) {
            ClockAction.PAUSE, ClockAction.START -> {
                val button = findButton(root, p.action) ?: return
                if (button.toggle) {
                    if (!known) return
                    val needed = if (p.action == ClockAction.PAUSE) running else !running
                    if (needed && !click(button.node)) return
                } else if (!click(button.node)) {
                    return
                }
            }
            ClockAction.RESET -> {
                val button = findButton(root, ClockAction.RESET)
                if (button == null) {
                    if (known && running) findButton(root, ClockAction.PAUSE)?.let { click(it.node) }
                    return
                }
                if (!click(button.node)) return
            }
        }
        // We know what was pressed ourselves; we do not wait for a Clock message about the tap (Compose does not send it)
        hint = p.action
        hintKind = want
        hintAt = now
        ownClickAt = now
        pending = null
        if (p.opened) {
            scope.launch {
                // We let Clock settle and the island see it, then go back
                delay(600)
                runCatching { tick() }
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
        }
    }

    /** A button on the Clock screen; [toggle] is one button for both "Start" and "Pause" (the round one in Google Clock). */
    private class Button(val node: AccessibilityNodeInfo, val toggle: Boolean)

    /** Looks for the button by label (more precise), otherwise by view id. Does not touch tabs or time labels. */
    private fun findButton(root: AccessibilityNodeInfo, action: ClockAction): Button? {
        var byLabel: AccessibilityNodeInfo? = null
        var byId: AccessibilityNodeInfo? = null
        var byIdToggle = false
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (byLabel != null || depth > 40) return
            if (n.isVisibleToUser && !n.isSelected) {
                val label = listOfNotNull(n.text, n.contentDescription).joinToString(" ").trim()
                if (label.isNotEmpty() && label.length <= 40 && ClockScreenParser.parse(label) == null &&
                    ClockWords.action(label) == action
                ) {
                    byLabel = n
                    return
                }
                val id = n.viewIdResourceName.orEmpty().lowercase()
                if (byId == null && id.isNotEmpty()) {
                    val toggle = "fab" in id || ("play" in id && "pause" in id) || ("start" in id && "stop" in id)
                    val match = when (action) {
                        ClockAction.PAUSE -> toggle || "pause" in id || ("stop" in id && "stopwatch" !in id)
                        ClockAction.START -> toggle || "start" in id || "resume" in id || "play" in id
                        ClockAction.RESET -> "reset" in id || "delete" in id || "clear" in id
                    }
                    if (match) {
                        byId = n
                        byIdToggle = toggle
                    }
                }
            }
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }
        walk(root, 0)
        byLabel?.let { return Button(it, false) }
        return byId?.let { Button(it, byIdToggle) }
    }

    /** The "Timer"/"Stopwatch" tab by its full label. */
    private fun clickTab(root: AccessibilityNodeInfo, labels: List<String>): Boolean {
        var found: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (found != null || depth > 40) return
            val label = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (n.isVisibleToUser && labels.any { it.equals(label, ignoreCase = true) }) {
                found = n
                return
            }
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }
        walk(root, 0)
        return found?.let { click(it) } ?: false
    }

    /** Press the view itself or its nearest clickable parent. */
    private fun click(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        var up = 0
        while (target != null && !target.isClickable && up < 4) {
            target = target.parent
            up++
        }
        return target != null && target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** A timer or stopwatch paused longer than [INACTIVE_MS] is inactive, and the banner is removed. */
    private fun dropInactive(now: Long) {
        if (timer?.let { it.pausedMs != null && now - it.pausedAt > INACTIVE_MS } == true) timer = null
        if (stopwatch?.let { it.pausedMs != null && now - it.pausedAt > INACTIVE_MS } == true) stopwatch = null
    }

    /** The Clock notification comes back: the island takes the timer from it, and the one read from the screen is no longer needed. */
    fun onNotificationChrono(pkg: String) {
        if (timer == null && stopwatch == null) return
        timer = null
        stopwatch = null
        publish(pkg)
    }

    /** Left Clock: we stop reading, but the island keeps the timer and stopwatch going itself. */
    fun stopReading() {
        loop?.cancel()
        loop = null
        current = null
        displays = emptyList()
        seen.clear()
        hint = null
        needRescan = true
        scannedAt = 0L
    }

    fun stop() {
        stopReading()
        endJob?.cancel()
        timer = null
        stopwatch = null
        IslandBus.chrono.value = IslandBus.chrono.value - TIMER_KEY - STOPWATCH_KEY
    }

    private fun tick() {
        val pkg = current ?: return
        val now = SystemClock.elapsedRealtime()
        lastTick = now
        // A full screen walk is expensive: at most twice a second, and once every 3 seconds if nothing changed
        if ((needRescan && now - scannedAt > 500) || now - scannedAt > 3_000) rescan(pkg, now)
        val wall = System.currentTimeMillis()
        val hinted = hint?.takeIf { now - hintAt < HINT_MS }

        var bestTimer: Pair<Display, Long>? = null
        var bestStopwatch: Pair<Display, Long>? = null
        for (d in displays) {
            val alive = runCatching { d.node.refresh() && d.node.isVisibleToUser }.getOrDefault(false)
            if (!alive) {
                needRescan = true
                continue
            }
            val text = (d.node.text ?: d.node.contentDescription)?.toString() ?: continue
            if (isTimeOfDay(text, wall)) continue
            val v = ClockScreenParser.parse(text) ?: continue
            // A stopwatch with fractions of a second changes constantly, so its pause is seen almost at once
            seen[d.key]?.fine = text.contains('.') || text.contains(',')
            when (observe(d.key, v, now, hinted)) {
                Kind.TIMER -> if (d.height > (bestTimer?.first?.height ?: -1)) bestTimer = d to v
                Kind.STOPWATCH -> if (d.height > (bestStopwatch?.first?.height ?: -1)) bestStopwatch = d to v
                null -> Unit
            }
        }
        bestTimer?.let { (d, v) -> timer = running(timer, d.key, wall + v) }
        bestStopwatch?.let { (d, v) -> stopwatch = running(stopwatch, d.key, wall - v) }

        // Pressed "Start" on its own tab: we show it at once, without waiting for the digits to move
        val main = displays.maxByOrNull { it.height }
        val mainValue = main?.let { seen[it.key]?.value }
        if (hinted == ClockAction.START && main != null && mainValue != null) {
            val t = timer
            val w = stopwatch
            if (hintKind == Kind.TIMER && bestTimer == null && mainValue > 0 && (t == null || t.pausedMs != null)) {
                timer = Track(main.key, wall + mainValue, null)
                seen[main.key]?.changedAt = now
            }
            if (hintKind == Kind.STOPWATCH && bestStopwatch == null && (w == null || w.pausedMs != null)) {
                stopwatch = Track(main.key, wall - mainValue, null)
                seen[main.key]?.changedAt = now
            }
        }
        if (hinted == ClockAction.PAUSE && main != null && mainValue != null) {
            // We settle on the time Clock shows, as it is more accurate than what the island counted itself
            if (hintKind == Kind.TIMER) timer?.let { timer = Track(it.key, it.base, mainValue, it.pausedAt.takeIf { at -> at > 0 } ?: now) }
            if (hintKind == Kind.STOPWATCH) stopwatch?.let { stopwatch = Track(it.key, it.base, mainValue, it.pausedAt.takeIf { at -> at > 0 } ?: now) }
        }
        if (hinted == ClockAction.RESET) {
            if (hintKind == Kind.TIMER) timer = null
            if (hintKind == Kind.STOPWATCH) stopwatch = null
        }

        // Right after "Start" the digits have not moved yet; this is not a pause
        if (hinted != ClockAction.START) {
            timer = pauseIfStill(timer, now)
            stopwatch = pauseIfStill(stopwatch, now)
        }
        dropInactive(now)
        publish(pkg)
        performPending()
    }

    /**
     * A new label value. Returns what this is, a running timer or stopwatch, once that is clear:
     * two changes in a row at clock speed, or one if its own tab is open or "Start" was just pressed.
     */
    private fun observe(key: String, v: Long, now: Long, hinted: ClockAction?): Kind? {
        val s = seen[key]
        if (s == null) {
            seen[key] = Seen(v, now)
            return null
        }
        if (v == s.value) return null
        val dv = v - s.value
        val since = now - s.changedAt
        val paused = listOfNotNull(timer, stopwatch).firstOrNull { it.key == key && it.pausedMs != null }
        val kind = when {
            dv < 0 && -dv <= since + 1_100 && since <= -dv + 1_300 -> Kind.TIMER
            dv > 0 && dv <= since + 1_100 && since <= dv + 1_300 -> Kind.STOPWATCH
            // Resumed from pause: after a long standstill the digits moved by a step again
            paused != null && kotlin.math.abs(dv) <= 1_500 -> if (dv < 0) Kind.TIMER else Kind.STOPWATCH
            else -> null
        }
        s.streak = if (kind != null && kind == s.kind) s.streak + 1 else if (kind != null) 1 else 0
        s.kind = kind
        s.value = v
        s.changedAt = now
        // "Pause" was just pressed: the digits could move one more step before Clock stopped; this is not running
        if (hinted == ClockAction.PAUSE) return null
        if (kind == null) {
            // Jump: the timer was reset to its initial time or the stopwatch was zeroed
            if (timer?.key == key && dv > 1_500) timer = null
            if (v == 0L && stopwatch?.key == key) stopwatch = null
            return null
        }
        if (v == 0L) {
            if (timer?.key == key) timer = null
            return null
        }
        val sure = s.streak >= 2 || tab == kind || hinted == ClockAction.START || paused != null
        return if (sure) kind else null
    }

    /** The digits of the shown timer or stopwatch stand still for over a second and a half: paused. */
    private fun pauseIfStill(track: Track?, now: Long): Track? {
        track ?: return null
        if (track.pausedMs != null) return track
        val d = displays.firstOrNull { it.key == track.key } ?: return track
        val s = seen[d.key] ?: return track
        val visible = runCatching { d.node.isVisibleToUser }.getOrDefault(false)
        val still = if (s.fine) 450 else 1_500
        return if (visible && now - s.changedAt > still) pausedTrack(track, s.value) else track
    }

    /**
     * Running. The on-screen digits are rounded to the second, so the new estimate wanders by ±1 s; while it is close
     * to the previous one, we keep the previous one, otherwise the time in the island would jitter. If it shifted a lot (+1:00), we take the new one.
     */
    private fun running(old: Track?, key: String, base: Long): Track = when {
        old == null || old.pausedMs != null || abs(old.base - base) >= 1_500 -> Track(key, base, null)
        old.key == key -> old
        else -> Track(key, old.base, null)
    }

    /** All time labels on the Clock screen and the open tab. Expensive: only when necessary. */
    private fun rescan(pkg: String, now: Long) {
        scannedAt = now
        needRescan = false
        val root = service.rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg } ?: windowRoot(pkg) ?: return
        val found = ArrayList<Display>()
        val tabs = arrayOfNulls<Kind>(1)
        collect(root, found, tabs, 0, HashMap())
        tab = tabs[0]
        @Suppress("DEPRECATION")
        displays.forEach { runCatching { it.node.recycle() } }
        displays = found.sortedByDescending { it.height }.take(6)
        // Disappeared from the open "Timer"/"Stopwatch" tab: it was deleted
        timerMisses = if (tab == Kind.TIMER && timer != null && displays.none { it.key == timer?.key }) timerMisses + 1 else 0
        stopwatchMisses = if (tab == Kind.STOPWATCH && stopwatch != null && displays.none { it.key == stopwatch?.key }) stopwatchMisses + 1 else 0
        if (timerMisses >= 2) timer = null
        if (stopwatchMisses >= 2) stopwatch = null
    }

    /** The Clock window, even if another app is already opening on top. */
    private fun windowRoot(pkg: String): AccessibilityNodeInfo? = runCatching {
        service.windows.firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName?.toString() == pkg } }
    }.getOrNull()

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<Display>, tabs: Array<Kind?>, depth: Int, ids: HashMap<String, Int>) {
        if (out.size > 20 || depth > 40) return
        val text = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
        var keep = false
        if (text.isNotEmpty() && node.isVisibleToUser) {
            if (ClockScreenParser.parse(text) != null) {
                val b = Rect()
                node.getBoundsInScreen(b)
                // Several timers in the list have the same id; we tell them apart by order
                val id = node.viewIdResourceName?.takeIf { it.isNotEmpty() } ?: "@${b.left},${b.top}"
                val n = (ids[id] ?: 0) + 1
                ids[id] = n
                out += Display("$id#$n", node, b.height())
                keep = true
            }
            if (node.isSelected && tabs[0] == null) {
                val t = text.lowercase()
                tabs[0] = when {
                    "секундомер" in t || "stopwatch" in t -> Kind.STOPWATCH
                    "таймер" in t || "timer" in t -> Kind.TIMER
                    else -> null
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, out, tabs, depth + 1, ids)
        }
        if (!keep && depth > 0) {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    /** The time of day on the "Clock" tab and in world time also looks like "h:mm"; we do not confuse it with a timer. */
    private fun isTimeOfDay(text: String, wall: Long): Boolean {
        val t = text.filterNot { it.isWhitespace() }
        return (-1..1).any { m ->
            val c = Calendar.getInstance().apply { timeInMillis = wall + m * 60_000L }
            val h24 = c.get(Calendar.HOUR_OF_DAY)
            val h12 = (h24 + 11) % 12 + 1
            val mm = "%02d".format(c.get(Calendar.MINUTE))
            t.startsWith("$h24:$mm") || t.startsWith("%02d:$mm".format(h24)) || t.startsWith("$h12:$mm") || t.startsWith("%02d:$mm".format(h12))
        } && t.length <= 8 && seenAsClock(t)
    }

    /** "H:mm" without fractions of a second is a clock format; a stopwatch always has fractions or has not yet reached an hour. */
    private fun seenAsClock(t: String): Boolean = !t.contains('.') && !t.contains(',')

    private fun publish(pkg: String) {
        // If Clock notifications are on, the island takes the timer from them; a second one is not needed
        val fromNotification = IslandBus.chrono.value.any { (k, v) -> v.pkg == pkg && k != TIMER_KEY && k != STOPWATCH_KEY }
        val map = IslandBus.chrono.value.toMutableMap()
        val label = labels.getOrPut(pkg) { NotificationParser.appLabel(service, pkg) }
        val icon = NotificationParser.icon(service, pkg)
        val t = timer?.takeUnless { fromNotification }
        val w = stopwatch?.takeUnless { fromNotification }
        if (t != null) {
            val old = map[TIMER_KEY]
            map[TIMER_KEY] = ChronoInfo(TIMER_KEY, pkg, trf("Таймер · {0}", "Timer · {0}", label), icon, t.base, true,
                old?.postTime ?: System.currentTimeMillis(), pausedMs = t.pausedMs)
        } else {
            map.remove(TIMER_KEY)
        }
        if (w != null) {
            val old = map[STOPWATCH_KEY]
            map[STOPWATCH_KEY] = ChronoInfo(STOPWATCH_KEY, pkg, trf("Секундомер · {0}", "Stopwatch · {0}", label), icon, w.base, false,
                old?.postTime ?: System.currentTimeMillis(), pausedMs = w.pausedMs)
        } else {
            map.remove(STOPWATCH_KEY)
        }
        if (map != IslandBus.chrono.value) IslandBus.chrono.value = map
        scheduleEnd()
        scheduleCleanup(pkg)
    }

    /** Remove an inactive (paused) timer also after leaving Clock, when reading is no longer running. */
    private fun scheduleCleanup(pkg: String) {
        cleanupJob?.cancel()
        val first = listOfNotNull(timer, stopwatch).filter { it.pausedMs != null }.minOfOrNull { it.pausedAt } ?: return
        cleanupJob = scope.launch {
            delay((first + INACTIVE_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 50)
            dropInactive(SystemClock.elapsedRealtime())
            publish(pkg)
        }
    }

    /** The timer reached zero: Clock itself rings from here, and the island removes it. */
    private fun scheduleEnd() {
        endJob?.cancel()
        val t = timer ?: return
        if (t.pausedMs != null) return
        endJob = scope.launch {
            delay((t.base - System.currentTimeMillis()).coerceAtLeast(0) + 1_500)
            if (timer === t) {
                timer = null
                IslandBus.chrono.value = IslandBus.chrono.value - TIMER_KEY
            }
        }
    }

    companion object {
        /** Keys of the timer and stopwatch read from the screen, unlike notification keys */
        const val KEY_PREFIX = "clock-screen:"
        /** How long after pressing "Start", "Pause" or "Reset" the island trusts the tap more than the digits */
        private const val HINT_MS = 2_000L
        /** How long an inactive (paused) timer or stopwatch stays visible in the island, like any event ("Event display") */
        val INACTIVE_MS: Long get() = SettingsRepo.current.noticeDurationMs.toLong()
        private const val TIMER_KEY = KEY_PREFIX + "timer"
        private const val STOPWATCH_KEY = KEY_PREFIX + "stopwatch"
        private val TIMER_TABS = listOf("Таймер", "Timer")
        private val STOPWATCH_TABS = listOf("Секундомер", "Stopwatch")
    }
}

/**
 * Island buttons for the Clock app's timer and stopwatch are the same as for our own: pause or resume and reset.
 * If the Clock notification has the button, the island presses it without opening the app. If not (notifications
 * are off), it presses the button on the Clock screen itself. The result is visible in the island at once.
 */
object ClockControl {
    var reader: ClockScreenReader? = null

    fun toggle(context: Context, c: ChronoInfo) = act(context, c, if (c.pausedMs != null) ClockAction.START else ClockAction.PAUSE)

    fun reset(context: Context, c: ChronoInfo) = act(context, c, ClockAction.RESET)

    private fun act(context: Context, c: ChronoInfo, action: ClockAction) {
        val own = c.key.startsWith(ClockScreenReader.KEY_PREFIX)
        val intent = when (action) {
            ClockAction.START -> c.resume
            ClockAction.PAUSE -> c.pause
            ClockAction.RESET -> c.reset
        }
        if (!own && intent != null && SettingsRepo.current.clockAutoTap && Controls.send(context, intent)) {
            optimistic(c, action)
            return
        }
        val r = reader
        // "Buttons for Clock" is off: the island buttons just open the app
        if (r == null || !SettingsRepo.current.clockAutoTap || !ClockApps.isClock(context, c.pkg)) {
            Controls.openApp(context, c.pkg)
            return
        }
        if (!own) optimistic(c, action)
        r.control(c.pkg, c.countDown, action, own)
    }

    /** Show the tap result at once; a new Clock notification will confirm it a moment later. */
    private fun optimistic(c: ChronoInfo, action: ClockAction) {
        val now = System.currentTimeMillis()
        val value = c.elapsed(now)
        val next = when (action) {
            ClockAction.PAUSE -> c.copy(pausedMs = value)
            ClockAction.START -> c.copy(pausedMs = null, base = if (c.countDown) now + value else now - value)
            ClockAction.RESET -> null
        }
        IslandBus.chrono.value = if (next == null) IslandBus.chrono.value - c.key else IslandBus.chrono.value + (c.key to next)
        if (next?.pausedMs != null) dropPausedLater(c.key)
    }

    /** Paused Clock notifications that the island already removed: key -> frozen time */
    private val dismissed = HashMap<String, Long>()

    /**
     * A timer or stopwatch from a notification went on pause: after a few seconds the banner is removed:
     * an inactive timer does not hang in the island. The same notification on the same pause is not shown again.
     */
    fun dropPausedLater(key: String) {
        IslandBus.scope.launch {
            delay(ClockScreenReader.INACTIVE_MS)
            val c = IslandBus.chrono.value[key] ?: return@launch
            val paused = c.pausedMs ?: return@launch
            dismissed[key] = paused
            IslandBus.chrono.value = IslandBus.chrono.value - key
        }
    }

    /** The island already removed this notification on this pause. */
    fun wasDismissed(key: String, pausedMs: Long?): Boolean {
        if (pausedMs == null) {
            dismissed.remove(key)
            return false
        }
        return dismissed[key] == pausedMs
    }

    fun forget(key: String) {
        dismissed.remove(key)
    }
}
