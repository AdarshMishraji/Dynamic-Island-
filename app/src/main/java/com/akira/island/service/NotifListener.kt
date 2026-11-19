package com.akira.island.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.os.Build
import android.content.Context
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.akira.island.core.CallInfo
import com.akira.island.core.ChronoInfo
import com.akira.island.core.IslandBus
import com.akira.island.core.LiveKind
import com.akira.island.core.ProgressInfo
import com.akira.island.data.SettingsRepo

/**
 * Notification access gives two things: media sessions of all players
 * and notifications with progress or a stopwatch (downloads, Clock, calls).
 */
class NotifListener : NotificationListenerService() {
    private var media: MediaMonitor? = null
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0
    private val reconnect = Runnable {
        requestReconnect(this)
    }

    override fun onListenerConnected() {
        SettingsRepo.init(this)
        handler.removeCallbacks(reconnect)
        reconnectAttempts = 0
        connected = this
        media?.stop()
        media = MediaMonitor(applicationContext).also { it.start() }
        // Notifications already posted are parsed without showing messages, since they are not new
        val existing = runCatching { activeNotifications }.getOrNull()
        runCatching {
            if (existing != null) HotspotMonitor.rescan(this, existing.toList())
            else HotspotMonitor.notificationsUnavailable()
        }
        existing?.forEach { postSafely(it, initial = true) }
    }

    override fun onListenerDisconnected() {
        if (connected === this) HotspotMonitor.notificationsUnavailable()
        if (connected === this) connected = null
        media?.stop()
        media = null
        IslandBus.progress.value = emptyMap()
        IslandBus.chrono.value = emptyMap()
        IslandBus.calls.value = emptyMap()
        IslandBus.live.value = emptyMap()
        IslandBus.ringingAlarms.value = emptySet()
        // The platform may unbind the listener after pressure or a package update. Rebind without
        // revoking access or asking the user to reinstall; cap retries to avoid a restart loop.
        if (reconnectAttempts < 3) {
            reconnectAttempts++
            handler.postDelayed(reconnect, reconnectAttempts * 2_000L)
        }
    }

    override fun onDestroy() {
        if (connected === this) HotspotMonitor.notificationsUnavailable()
        if (connected === this) connected = null
        handler.removeCallbacksAndMessages(null)
        media?.stop()
        media = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var connected: NotifListener? = null
        private var lastReconnectAt = -30_000L

        val isConnected: Boolean get() = connected != null

        /** Called by keep-alive/on-resume only while notification access is already granted. */
        @Synchronized
        fun requestReconnect(context: Context): Boolean {
            if (isConnected) return false
            val now = SystemClock.elapsedRealtime()
            if (now - lastReconnectAt < 30_000L) return false
            lastReconnectAt = now
            return runCatching {
                requestRebind(ComponentName(context, NotifListener::class.java))
                true
            }.getOrDefault(false)
        }

        /** Never offer cancellation for foreground services, calls, or other non-clearable entries. */
        fun canDismiss(key: String): Boolean = runCatching {
            connected?.getActiveNotifications(arrayOf(key))?.any { it.key == key && it.isClearable } == true
        }.getOrDefault(false)

        private val actions = NotificationActions(
            isClearable = ::canDismiss,
            cancel = { key ->
                val service = connected ?: error("Notification listener is disconnected")
                service.cancelNotification(key)
                IslandBus.removeMessage(key)
                Inbox.remove(key)
            },
        )

        /** Android remains the authority: cancellation may be refused by system policy. */
        fun dismiss(key: String): Boolean = actions.dismiss(key)

        fun dismissAll(keys: List<String>): Int = actions.dismissAll(keys)

        fun dismissEverything() {
            runCatching { connected?.cancelAllNotifications() }
            Inbox.clear()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        postSafely(sbn)
    }

    private fun postSafely(sbn: StatusBarNotification, initial: Boolean = false) {
        runCatching { HotspotMonitor.onPosted(this, sbn) }
        // Third-party extras/icons can be malformed. One bad notification must not take down
        // the notification listener and the accessibility overlay sharing this process.
        runCatching { media?.onNotificationPosted(sbn) }
        runCatching { NotificationParser.onPosted(this, sbn, initial) }.onFailure {
            NotifDebug.add(sbn.packageName, com.akira.island.core.tr("Не удалось прочитать уведомление", "Could not read notification"))
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        HotspotMonitor.onRemoved(sbn.key)
        runCatching { media?.onNotificationRemoved(sbn.key) }
        runCatching { NotificationParser.onRemoved(sbn.key) }
    }
}

object NotificationParser {
    private val icons = HashMap<String, ImageBitmap?>()

    fun onPosted(context: Context, sbn: StatusBarNotification, initial: Boolean = false) {
        if (sbn.packageName == context.packageName) return
        val n = sbn.notification ?: return
        val ex = n.extras ?: return
        val isMedia = mediaNotificationEvidence(n.category,
            ex.containsKey(Notification.EXTRA_MEDIA_SESSION),
            runCatching { ex.getString(Notification.EXTRA_TEMPLATE) }.getOrNull())
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: appLabel(context, sbn.packageName)

        val max = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val value = ex.getInt(Notification.EXTRA_PROGRESS, 0)
        val indeterminate = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        val detailText = listOf(Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT)
            .mapNotNull { key -> ex.getCharSequence(key)?.takeIf { it.length <= 1024 }?.toString() }
            .distinct().joinToString("\n")
        val statusText = title + "\n" + detailText
        val barProgress = (max > 0 && value < max) || (indeterminate && ongoing)
        // Some apps write only "Downloading… 45%" and draw no bar
        val textPercent = if (!barProgress && ongoing && !isMedia && TransferClassifier.saysTransferring(statusText)) {
            TransferClassifier.percentIn(statusText)
        } else null
        val hasProgress = !isMedia && (barProgress || textPercent != null)
        IslandBus.progress.value = IslandBus.progress.value.toMutableMap().apply {
            if (hasProgress) {
                val labels = n.actions.orEmpty().map { it.title?.toString().orEmpty() }
                val buttons = HashMap<TransferClassifier.Button, android.app.PendingIntent>()
                n.actions?.forEach { a ->
                    val b = TransferClassifier.button(a.title?.toString().orEmpty())
                    if (b != null && a.actionIntent != null) buttons.putIfAbsent(b, a.actionIntent)
                }
                put(sbn.key, ProgressInfo(sbn.key, sbn.packageName, title, icon(context, sbn.packageName),
                    textPercent ?: value, if (textPercent != null) 100 else max, indeterminate && textPercent == null,
                    sbn.postTime, TransferDetailsParser.parse(detailText),
                    kind = TransferClassifier.kind(sbn.packageName, statusText),
                    state = TransferClassifier.state(statusText, labels),
                    pause = buttons[TransferClassifier.Button.PAUSE], resume = buttons[TransferClassifier.Button.RESUME],
                    cancel = buttons[TransferClassifier.Button.CANCEL]))
            } else {
                remove(sbn.key)
            }
        }

        val call = when {
            isMedia -> null
            n.category == Notification.CATEGORY_CALL -> parseCall(context, sbn, title)
            // Discord, Zoom, Teams and others ring without the "call" category, so we recognize them by their buttons
            VoipApps.isVoip(sbn.packageName) -> parseVoip(context, sbn, title)
            else -> null
        }
        // Which channel sends the call or alarm popup banner, for the "System banners" screen
        SystemBanners.onPosted(sbn, incomingCall = call?.incoming == true)
        // A ringing alarm or Clock timer has a full-screen alert; the island turns on the screen itself,
        // even if Clock popup banners are turned off
        val ringing = n.category == Notification.CATEGORY_ALARM && n.fullScreenIntent != null
        if (ringing != (sbn.key in IslandBus.ringingAlarms.value)) {
            IslandBus.ringingAlarms.value = if (ringing) IslandBus.ringingAlarms.value + sbn.key else IslandBus.ringingAlarms.value - sbn.key
        }
        IslandBus.calls.value = IslandBus.calls.value.toMutableMap().apply {
            if (call != null) put(sbn.key, call) else remove(sbn.key)
        }

        val ticking = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) && n.`when` > 0
        // Clock buttons (pause, resume, reset): the island's buttons press them
        val clock = ClockApps.isClock(context, sbn.packageName)
        val buttons = if (clock) ClockWords.buttons(n.actions) else emptyMap()
        // A paused Clock timer or stopwatch: the count is stopped, the time is only in the text, and the buttons have "Продолжить"
        val pausedMs = if (clock && !ticking && call == null && !isMedia && ClockAction.START in buttons) {
            listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_BIG_TEXT)
                .firstNotNullOfOrNull { ClockScreenParser.find(ex.getCharSequence(it)) }
        } else {
            null
        }
        // A running Clock timer or stopwatch whose time is only in the text (new every second), and not
        // a system countdown: on some shells (Xiaomi, vivo, etc.) it is like this, so the island counts on its own from that time
        val texts = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_BIG_TEXT)
        val runningMs = if (clock && !ticking && pausedMs == null && call == null && !isMedia && ClockAction.PAUSE in buttons) {
            texts.firstNotNullOfOrNull { ClockScreenParser.find(ex.getCharSequence(it)) }
        } else {
            null
        }
        // Many voice recorders give only time/pause. Android 14+ also allows an FGS banner without ongoing.
        // The recording name may contain a date/time; prefer the live status over its title.
        val recordingTexts = listOf(Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_INFO_TEXT, Notification.EXTRA_SUMMARY_TEXT)
            .mapNotNull { ex.getCharSequence(it)?.toString() } +
            ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty().map(CharSequence::toString)
        val recordingElapsed = recordingTexts.firstNotNullOfOrNull { ClockScreenParser.find(it) }
            ?: ClockScreenParser.parse(title.trim())
        val recordingActions = n.actions.orEmpty().filter { it.actionIntent != null }
        val recordingPause = recordingActions.firstOrNull { isPauseRecordingAction(it.title?.toString().orEmpty()) }?.actionIntent
        val recordingResume = recordingActions.firstOrNull { isResumeRecordingAction(it.title?.toString().orEmpty()) }?.actionIntent
        val recordingStop = recordingActions.firstOrNull { isFinishRecordingAction(sbn.packageName, it.title?.toString().orEmpty()) }?.actionIntent
        val recording = classifyRecording(sbn.packageName, title,
            recordingTexts.joinToString(" "), ongoing, recordingStop != null,
            hasChronometer = ticking, hasElapsedTime = recordingElapsed != null,
            hasPauseAction = recordingPause != null, hasResumeAction = recordingResume != null,
            foregroundService = n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0, hasProgress = hasProgress,
            mediaNotification = isMedia,
            hasRecordingAction = recordingActions.any { isSpecificRecordingAction(it.title?.toString().orEmpty()) })
        val recorder = call == null && recording.recording
        // An inactive (paused) timer that the island already removed is not shown again
        val chrono = call == null && !recording.playback && (recorder || !isMedia && (ticking || pausedMs != null || runningMs != null)) &&
            !ClockControl.wasDismissed(sbn.key, pausedMs)
        if (clock && chrono) ClockControl.reader?.onNotificationChrono(sbn.packageName)
        if (pausedMs != null && chrono) ClockControl.dropPausedLater(sbn.key)
        IslandBus.chrono.value = IslandBus.chrono.value.toMutableMap().apply {
            if (chrono) {
                val down = when {
                    recorder -> false
                    pausedMs != null || runningMs != null -> looksLikeTimer(n, title)
                    else -> ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)
                }
                val stop = if (recorder) recordingStop ?: n.contentIntent.takeIf { recording.stopOnTap } else n.actions?.firstOrNull { a ->
                    val t = a.title?.toString()?.lowercase().orEmpty()
                    isStopRecordingAction(t)
                }?.actionIntent
                val now = System.currentTimeMillis()
                // Countdown: system one from Notification.when; from text, from the time read; for an entry without
                // a countdown, from the time in the text or from when the notification appeared
                val base = when {
                    runningMs != null -> if (down) now + runningMs else now - runningMs
                    recorder -> n.`when`.takeIf { ticking && it in 1..now }
                        ?: recordingElapsed?.let { now - it }
                        ?: IslandBus.chrono.value[sbn.key]?.takeIf { it.recordingKind == recording.kind }?.let { previous ->
                            if (!recording.paused && previous.pausedMs != null) now - previous.pausedMs else previous.base
                        }
                        ?: n.`when`.takeIf { it in 1..now } ?: sbn.postTime
                    else -> n.`when`
                }
                put(sbn.key, ChronoInfo(sbn.key, sbn.packageName, title, icon(context, sbn.packageName), base, down, sbn.postTime, stop,
                    pausedMs = if (recorder && recording.paused) recordingElapsed
                        ?: IslandBus.chrono.value[sbn.key]?.elapsed(now) ?: (now - base).coerceAtLeast(0) else pausedMs,
                    pause = if (recorder) recordingPause else buttons[ClockAction.PAUSE],
                    resume = if (recorder) recordingResume else buttons[ClockAction.START],
                    reset = buttons[ClockAction.RESET], recordingKind = recording.kind.takeIf { recorder }, open = n.contentIntent))
            } else {
                remove(sbn.key)
            }
        }

        val live = if (call == null && !isMedia && !recorder) LiveParser.parse(context, sbn, title) else null
        val liveWasKnown = sbn.key in IslandBus.live.value
        IslandBus.live.value = IslandBus.live.value.toMutableMap().apply {
            if (live != null) put(sbn.key, live) else remove(sbn.key)
        }
        // A new "live activity" is first shown as a wide banner, then collapses into a capsule
        if (live != null && !liveWasKnown && !initial && SettingsRepo.current.cardLive && SettingsRepo.current.liveBanner) {
            LiveParser.postBannerLater(context, sbn.key)
        }
        if (recorder) IslandBus.progress.value = IslandBus.progress.value - sbn.key
        // Navigator and taxi are shown with their own card, not a progress bar or a timer
        if (live != null) {
            IslandBus.progress.value = IslandBus.progress.value - sbn.key
            IslandBus.chrono.value = IslandBus.chrono.value - sbn.key
        }

        val service = context as? NotificationListenerService
        if (!initial && service != null && !isMedia && call == null && !chrono && !hasProgress && live == null) {
            // A payment notification from a bank is "Paid" with a checkmark, not a message card
            if (PayMonitor.onNotification(context, sbn)) return
            Messages.maybeShow(service, sbn, appLabel(context, sbn.packageName))
        }
    }

    private fun parseCall(context: Context, sbn: StatusBarNotification, title: String): CallInfo? {
        val n = sbn.notification
        val ex = n.extras
        val ticking = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) && n.`when` > 0
        // CallStyle (Android 12+) puts buttons in extras; for other dialer apps we look by label in the phone's language;
        // "Join" and "Disconnect" are for calls in Discord and similar apps
        var answer = pending(ex, "android.answerIntent")
        var decline = pending(ex, "android.declineIntent")
        var hangUp = pending(ex, "android.hangUpIntent")
        n.actions?.forEach { a ->
            val t = a.title?.toString()?.lowercase().orEmpty()
            when {
                answer == null && (AnswerWords.any { it in t } || JoinWords.any { it in t }) -> answer = a.actionIntent
                decline == null && DeclineWords.any { it in t } -> decline = a.actionIntent
                hangUp == null && (HangUpWords.any { it in t } || LeaveWords.any { it in t }) -> hangUp = a.actionIntent
            }
        }
        // A call is in progress: a system countdown or an ongoing notification with an "End" button and no "Reply"
        val ongoing = ticking || (n.flags and Notification.FLAG_ONGOING_EVENT != 0 && hangUp != null && answer == null)
        val incoming = !ongoing && answer != null
        if (!incoming && !ongoing) return null

        val (name, personIcon) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            val person = runCatching { ex.getParcelable<Person>(Notification.EXTRA_CALL_PERSON) }.getOrNull()
            (person?.name?.toString()?.takeIf { it.isNotBlank() } ?: title) to person?.icon
        } else {
            title to null
        }
        val avatar = runCatching {
            val icon = personIcon ?: n.getLargeIcon()
            icon?.loadDrawable(context)?.toBitmap(128, 128)?.asImageBitmap()
        }.getOrNull()
        return CallInfo(
            key = sbn.key,
            pkg = sbn.packageName,
            name = name,
            avatar = avatar,
            incoming = incoming,
            // Without a system countdown, the call time runs from when the notification appeared
            base = if (ticking) n.`when` else n.`when`.takeIf { it in 1..System.currentTimeMillis() } ?: sbn.postTime,
            postTime = sbn.postTime,
            answer = answer,
            decline = decline ?: hangUp,
            hangUp = hangUp ?: decline,
            open = n.contentIntent,
        )
    }

    /**
     * A call in Discord, Zoom, Teams, etc.: they have no call category, so we recognize them by buttons. Incoming:
     * it has "Accept" ("Join") and "Decline"; a call in progress: an ongoing notification with "Disconnect"
     * ("Leave", "End").
     */
    private fun parseVoip(context: Context, sbn: StatusBarNotification, title: String): CallInfo? {
        val n = sbn.notification
        var answer: PendingIntent? = null
        var decline: PendingIntent? = null
        var hangUp: PendingIntent? = null
        n.actions?.forEach { a ->
            val t = a.title?.toString()?.lowercase().orEmpty()
            when {
                answer == null && (AnswerWords.any { it in t } || JoinWords.any { it in t }) -> answer = a.actionIntent
                decline == null && DeclineWords.any { it in t } -> decline = a.actionIntent
                hangUp == null && (HangUpWords.any { it in t } || LeaveWords.any { it in t }) -> hangUp = a.actionIntent
            }
        }
        val ongoingFlag = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        val incoming = answer != null && (decline != null || hangUp != null)
        val talking = !incoming && hangUp != null && ongoingFlag
        if (!incoming && !talking) return null
        val avatar = runCatching { n.getLargeIcon()?.loadDrawable(context)?.toBitmap(128, 128)?.asImageBitmap() }.getOrNull()
        val now = System.currentTimeMillis()
        return CallInfo(
            key = sbn.key,
            pkg = sbn.packageName,
            name = title.ifBlank { appLabel(context, sbn.packageName) },
            avatar = avatar,
            incoming = incoming,
            base = n.`when`.takeIf { it in 1..now } ?: sbn.postTime,
            postTime = sbn.postTime,
            answer = answer,
            decline = decline ?: hangUp,
            hangUp = hangUp ?: decline,
            open = n.contentIntent,
        )
    }

    /** A paused timer or a stopwatch, by title and channel; timer by default. */
    private fun looksLikeTimer(n: Notification, title: String): Boolean {
        val t = (title + " " + n.channelId.orEmpty()).lowercase()
        return when {
            "секундомер" in t || "stopwatch" in t -> false
            else -> true
        }
    }

    @Suppress("DEPRECATION")
    private fun pending(ex: android.os.Bundle, key: String): PendingIntent? =
        runCatching { ex.getParcelable<PendingIntent>(key) }.getOrNull()

    fun onRemoved(key: String) {
        Messages.forget(key)
        // Read in the app itself: it leaves the "Notification history", and a banner that is held longer does too
        Inbox.remove(key)
        Messages.onRemoved(key)
        LiveParser.forget(key)
        ClockControl.forget(key)
        LiveDebug.forget(key)
        if (key in IslandBus.calls.value) IslandBus.calls.value = IslandBus.calls.value - key
        if (key in IslandBus.progress.value) IslandBus.progress.value = IslandBus.progress.value - key
        if (key in IslandBus.chrono.value) IslandBus.chrono.value = IslandBus.chrono.value - key
        if (key in IslandBus.live.value) IslandBus.live.value = IslandBus.live.value - key
        if (key in IslandBus.ringingAlarms.value) IslandBus.ringingAlarms.value = IslandBus.ringingAlarms.value - key
    }

    fun icon(context: Context, pkg: String): ImageBitmap? = icons.getOrPut(pkg) {
        runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }

    fun appLabel(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}

/** Call apps without the "call" notification category: a call is recognized by buttons. */
object VoipApps {
    private val known = setOf(
        "com.discord",
        "us.zoom.videomeetings",
        "com.microsoft.teams",
        "com.skype.raider",
        "com.google.android.apps.meetings",
        "com.google.android.apps.tachyon",
        "jp.naver.line.android",
        "com.viber.voip",
    )

    fun isVoip(pkg: String) = pkg in known
}

/** Apps whose ongoing notifications the island shows as "live activities". */
object LiveApps {
    private val nav = setOf(
        "com.google.android.apps.maps",
        "ru.yandex.yandexmaps",
        "ru.yandex.yandexnavi",
        "ru.dublgis.dgismobile",
        "com.waze",
        "net.osmand",
        "net.osmand.plus",
        "com.here.app.maps",
        "cz.seznam.mapy",
        "app.organicmaps",
        "com.mapswithme.maps.pro",
    )
    private val ride = setOf(
        "ru.yandex.taxi",
        "com.yandex.go",
        "com.ubercab",
        "ee.mtakso.client",
        "ru.citymobil",
        "sinet.startup.indriver",
        "com.lyft.android",
    )
    private val delivery = setOf(
        "ru.yandex.eda",
        "ru.foodfox.client",
        "ru.samokat.app",
        "ru.instamart",
        "ru.sbermarket",
        "com.deliveryclub",
        "com.ubercab.eats",
        "ru.ozon.app.android",
        "com.wildberries.ru",
        "ru.beru.android",
    )

    fun kind(pkg: String, category: String?): LiveKind? = when {
        pkg in nav || category == Notification.CATEGORY_NAVIGATION -> LiveKind.NAV
        pkg in ride -> LiveKind.RIDE
        pkg in delivery -> LiveKind.DELIVERY
        else -> null
    }
}

/**
 * Call button labels in different languages: the island is used in Vietnam, Indonesia, Thailand, Brazil,
 * Latin America and elsewhere; without them, answering and ending from the island did not work there.
 */
private val AnswerWords = listOf(
    "ответ", "принять", "жауап", "қабылдау", "answer", "accept", "trả lời", "nghe", "jawab", "terima", "angkat", "contestar", "responder",
    "atender", "aceptar", "aceitar", "รับสาย", "cevapla", "yanıtla", "kabul", "annehmen", "répondre", "rispondi", "accetta",
    "odbierz", "відповісти", "прийняти", "sagutin", "接听", "接聽", "応答", "응답", "받기",
)
private val DeclineWords = listOf(
    "отклон", "сброс", "қабылдамау", "бас тарту", "decline", "reject", "dismiss", "từ chối", "tolak", "rechazar", "recusar", "rejeitar", "ปฏิเสธ",
    "reddet", "ablehnen", "refuser", "rifiuta", "odrzuć", "відхилити", "tanggihan", "拒接", "拒絕", "拒否", "거절",
)
private val HangUpWords = listOf(
    "заверш", "положить", "hang", "end call", "kết thúc", "gác máy", "akhiri", "colgar", "finalizar", "desligar",
    "encerrar", "วางสาย", "kapat", "auflegen", "beenden", "raccrocher", "riaggancia", "zakończ", "завершити", "ibaba",
    "挂断", "掛斷", "終了", "종료", "аяқтау",
)

/** "Join" a call (Discord and others): same as "Accept" */
private val JoinWords = listOf("join", "присоедин", "gabung", "entrar", "participar", "қосылу", "beitreten", "rejoindre", "unirse")

/** "Disconnect", "Leave" a call (Discord, Zoom and others): same as "End" */
private val LeaveWords = listOf(
    "disconnect", "leave", "отключ", "выйти", "покинуть", "putus", "keluar", "tinggalkan", "desconectar", "sair", "ажырату", "шығу",
    "trennen", "verlassen", "quitter", "salir",
)
