package com.akira.island.core

import com.akira.island.data.SettingsRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shared island state. Services write here; the capsule and app read.
 * Everything lives in one process, so a plain singleton is enough.
 */
object IslandBus {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val serviceRunning = MutableStateFlow(false)
    val expanded = MutableStateFlow(false)
    /** id of the activity the user picked (e.g. by tapping a bubble) */
    val focus = MutableStateFlow<String?>(null)

    val media = MutableStateFlow<MediaInfo?>(null)
    val lyrics = MutableStateFlow<Lyrics?>(null)
    /** How the lyrics search for the track with key first is going */
    val lyricsStatus = MutableStateFlow<Pair<String, LyricsStatus>?>(null)
    val progress = MutableStateFlow<Map<String, ProgressInfo>>(emptyMap())
    val chrono = MutableStateFlow<Map<String, ChronoInfo>>(emptyMap())
    val calls = MutableStateFlow<Map<String, CallInfo>>(emptyMap())
    /** Incoming call swiped away: it isn't in the island until it ends or becomes a conversation */
    val callHidden = MutableStateFlow(false)
    /** What only the accessibility service itself can do; without the service the command is lost */
    val commands = MutableSharedFlow<ServiceCommand>(extraBufferCapacity = 4)
    val live = MutableStateFlow<Map<String, LiveInfo>>(emptyMap())
    /** Phone payment screen is in view: "Payment · To terminal", until paid or left (PayMonitor) */
    val payReady = MutableStateFlow<PayReady?>(null)
    /** Next alarm from any "Clock" app */
    val nextAlarm = MutableStateFlow<AlarmInfo?>(null)
    /** Ringing alarms and timers of other apps ("Clock"): their notification keys */
    val ringingAlarms = MutableStateFlow<Set<String>>(emptySet())
    val torch = MutableStateFlow(TorchState())
    val timer = MutableStateFlow<TimerState?>(null)
    val stopwatch = MutableStateFlow<StopwatchState?>(null)
    val notice = MutableStateFlow<Notice?>(null)
    /** User expanded the compact notification to read it inside the island. */
    val messageDetail = MutableStateFlow(false)
    /** "Notification history": shown messages still waiting in the island */
    val inbox = MutableStateFlow<List<InboxItem>>(emptyList())
    /** A reply to a notification is being typed: the island window needs focus to show the keyboard */
    val reply = MutableStateFlow<ReplyState?>(null)
    /** Both tilt axes from -1 to 1, in island coordinates. */
    val glassTilt = MutableStateFlow(GlassTilt.Zero)
    /** Until when (elapsedRealtime) to show a sample activity: while the width slider is being dragged */
    val widthPreviewUntil = MutableStateFlow(0L)
    /** The person is reading or replying: the notification isn't removed by the timer */
    val noticeHold = MutableStateFlow(false)
    val battery = MutableStateFlow(BatteryInfo())
    /** Ringer mode (AudioManager.RINGER_MODE_*) and "Do Not Disturb": for the expanded island buttons */
    val ringer = MutableStateFlow(2)
    val dnd = MutableStateFlow(false)
    val foregroundPackage = MutableStateFlow<String?>(null)
    /** Expanded notification shade/quick settings detected by the accessibility service. */
    val shadeOpen = MutableStateFlow(false)
    val landscape = MutableStateFlow(false)
    /** Screen rotation for the "Near camera" island: −90 camera on the left, 90 on the right, 0 portrait */
    val displayRotation = MutableStateFlow(0)
    /** How many degrees the island is currently rotated in its window (0 is normal, at the top) */
    val contentRotation = MutableStateFlow(0)
    /** Outside the "Scheduled" hours the island isn't shown */
    val offSchedule = MutableStateFlow(false)
    /** Screen is on. While it's off, the tilt sensor and navigator screen reading don't work, to save battery */
    val screenOn = MutableStateFlow(true)
    /** Android power saving is on: with "Battery saver" the island has no live effects */
    val powerSave = MutableStateFlow(false)
    /** Phone is locked with a PIN, pattern or password: message texts and buttons are hidden */
    val locked = MutableStateFlow(false)
    /** The lock screen is showing (with or without PIN): the capsule shows a lock if enabled in "Events" */
    val keyguard = MutableStateFlow(false)
    /** The "Size and position" screen is open: the island is red so you can fit it to the camera */
    val calibrating = MutableStateFlow(false)
    /** That particular song is playing: the island celebrates (easter egg, Egg.kt) */
    val egg = MutableStateFlow(false)
    /** Last touch of the expanded island: so it isn't collapsed from under a finger */
    @Volatile
    var lastTouch = 0L

    private data class QueuedNotice(val notice: Notice, val revision: Long)
    // A burst of messages should not disappear behind a single long-lived card.
    private val queue = Channel<QueuedNotice>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val messageRevisions = object : LinkedHashMap<String, Long>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 256
    }

    init {
        scope.launch {
            for (queued in queue) {
                val n = queued.notice
                val key = n.message?.key
                fun removed() = key != null && (messageRevisions[key] ?: 0L) != queued.revision
                if (removed()) continue
                val s = SettingsRepo.current
                // "Appearance delay"; confirmations of what you just did (sent, paid) appear immediately
                if (s.noticeDelayMs > 0 && n.style != NoticeStyle.SENT && n.style != NoticeStyle.PAY_DONE) delay(s.noticeDelayMs.toLong())
                if (removed()) continue
                messageDetail.value = false
                noticeHold.value = false
                notice.value = n
                val message = n.style == NoticeStyle.MESSAGE
                val duration = if (message && s.messageSeconds > 0) s.messageSeconds * 1000L else s.noticeDurationMs + n.extraMs
                // A notification leaves on time, or earlier if swiped away by a click
                withTimeoutOrNull(duration) { notice.first { it?.id != n.id } }
                // "Until swiped": the banner waits until dismissed; when the next event arrives, it yields to it
                if (message && s.messageSeconds < 0 && notice.value?.id == n.id && waitingIsEmpty()) {
                    val seen = posted.value
                    combine(notice, posted) { cur, p -> cur?.id != n.id || p != seen }.first { it }
                }
                // While someone is replying or scrolling the conversation, the card stays; then a little longer
                if (notice.value?.id == n.id && noticeHold.value) {
                    combine(notice, noticeHold) { current, holding -> current?.id != n.id || !holding }.first { it }
                    if (notice.value?.id == n.id) withTimeoutOrNull(1500) { notice.first { it?.id != n.id } }
                }
                if (notice.value?.id == n.id) notice.value = null
                // A short pause between banners so they don't merge, but without a noticeable wait
                delay(110)
            }
        }
    }

    /** How many events have been sent to the queue: the "until swiped" banner uses it to know the next one arrived */
    private val posted = MutableStateFlow(0)

    fun post(n: Notice) {
        queue.trySend(QueuedNotice(n, n.message?.key?.let { messageRevisions[it] } ?: 0L))
        posted.value++
    }

    /** Invalidate queued copies too, so a notification dismissed in the shade cannot reappear later. */
    fun removeMessage(key: String) {
        messageRevisions[key] = (messageRevisions[key] ?: 0L) + 1L
        if (notice.value?.message?.key == key) {
            notice.value = null
            messageDetail.value = false
            noticeHold.value = false
            reply.value = null
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun waitingIsEmpty(): Boolean = queue.isEmpty
}

/** Commands to the service: screenshot without the island, lock, power off and scroll on an explicit gesture. */
enum class ServiceCommand { SCREENSHOT, LOCK, TURN_OFF, SCROLL_TOP, QUICK_SETTINGS, SHADE }
