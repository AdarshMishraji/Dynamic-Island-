package com.akira.island.core

import android.app.PendingIntent
import android.app.RemoteInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.concurrent.atomic.AtomicLong

data class MediaInfo(
    val key: String,
    val pkg: String,
    val title: String,
    val artist: String,
    val album: String?,
    val art: ImageBitmap?,
    val accent: Color,
    val durationMs: Long,
    val positionMs: Long,
    /** SystemClock.elapsedRealtime() of the moment positionMs refers to */
    val positionTime: Long,
    val speed: Float,
    val playing: Boolean,
    val canSeek: Boolean,
    /** "Like" and "Dislike" from the player itself (Yandex Music, YouTube Music, etc.), if it provides them */
    val like: PlayerAction? = null,
    val dislike: PlayerAction? = null,
) {
    fun positionAt(now: Long): Long {
        val p = if (playing) positionMs + ((now - positionTime) * speed).toLong() else positionMs
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }
}

/** The player's own button: what to send it and whether it's already on (track already liked). */
data class PlayerAction(val action: String, val on: Boolean)

data class LyricLine(val timeMs: Long, val text: String)

enum class LyricsStatus { LOADING, MISSING, FOUND }

/** [synced] = false: plain text without timestamps; it must not be shown as karaoke. */
data class Lyrics(val key: String, val lines: List<LyricLine>, val synced: Boolean = true) {
    /** Index of the line being sung at moment [pos], or -1 before the first line. */
    fun indexAt(pos: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= pos) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }
}

/** What a progress notification is doing; each kind has its own switch and wording. */
enum class TransferKind(val pointsUp: Boolean) {
    DOWNLOAD(false), UPLOAD(true), BACKUP(true), COPY_MOVE(false), DELETE(false), INSTALL(false);

    val title: String get() = when (this) {
        DOWNLOAD -> tr("Загрузка", "Downloading")
        UPLOAD -> tr("Отправка", "Uploading")
        BACKUP -> tr("Резервное копирование", "Backing up")
        COPY_MOVE -> tr("Копирование", "Copying")
        DELETE -> tr("Удаление", "Deleting")
        INSTALL -> tr("Установка", "Installing")
    }
}

enum class TransferState { ACTIVE, PAUSED, WAITING, FAILED }

data class ProgressInfo(
    val key: String,
    val pkg: String,
    val title: String,
    val icon: ImageBitmap?,
    val progress: Int,
    val max: Int,
    val indeterminate: Boolean,
    val postTime: Long,
    val transferDetails: TransferDetails? = null,
    val kind: TransferKind = TransferKind.DOWNLOAD,
    val state: TransferState = TransferState.ACTIVE,
    /** The app's own buttons: not every transfer offers them. */
    val pause: PendingIntent? = null,
    val resume: PendingIntent? = null,
    val cancel: PendingIntent? = null,
) {
    val fraction: Float get() = if (max > 0) (progress.toFloat() / max).coerceIn(0f, 1f) else 0f
}

enum class RecordingKind {
    VOICE, SCREEN, VIDEO, GENERIC;

    val title: String get() = when (this) {
        VOICE -> tr("Запись звука", "Audio recording")
        SCREEN -> tr("Запись экрана", "Screen recording")
        VIDEO -> tr("Запись видео", "Video recording")
        GENERIC -> tr("Запись", "Recording")
    }
}

data class ChronoInfo(
    val key: String,
    val pkg: String,
    val title: String,
    val icon: ImageBitmap?,
    /** Notification.when is wall-clock time */
    val base: Long,
    val countDown: Boolean,
    val postTime: Long,
    /** "Stop" button from the notification (screen recording, voice recorder) */
    val stop: PendingIntent? = null,
    /** When paused: the frozen time (timer or stopwatch of "Clock") */
    val pausedMs: Long? = null,
    /** Buttons from the "Clock" notification: the island presses them without opening the app */
    val pause: PendingIntent? = null,
    val resume: PendingIntent? = null,
    val reset: PendingIntent? = null,
    /** Recording type is confirmed by the source notification; a title alone doesn't make a timer a voice recorder. */
    val recordingKind: RecordingKind? = null,
    /** Original notification destination; recording controls never replace missing actions with guessed intents. */
    val open: PendingIntent? = null,
) {
    fun elapsed(nowWall: Long): Long = pausedMs ?: if (countDown) (base - nowWall).coerceAtLeast(0) else nowWall - base

    /**
     * Nothing left to show: the countdown hit zero more than 3 seconds ago and the app never removed the
     * notification (otherwise the island would show "00:00" forever), or a count-up starts in the future.
     */
    fun finished(nowWall: Long): Boolean = pausedMs == null &&
        (countDown && nowWall > base + DONE_MS || !countDown && base > nowWall + 60_000)

    companion object {
        /** How long a finished countdown stays visible in the island */
        const val DONE_MS = 3_000L
    }

    val isRecording: Boolean get() = recordingKind != null
    val recording: Boolean get() = isRecording
    val screenRecording: Boolean get() = recordingKind == RecordingKind.SCREEN
}

/** Call from the dialer's notification: incoming or ongoing. */
data class CallInfo(
    val key: String,
    val pkg: String,
    val name: String,
    val avatar: ImageBitmap?,
    val incoming: Boolean,
    /** Notification.when is the start of the call, wall-clock time */
    val base: Long,
    val postTime: Long,
    val answer: PendingIntent?,
    val decline: PendingIntent?,
    val hangUp: PendingIntent?,
    val open: PendingIntent?,
)

enum class LiveKind { NAV, RIDE, DELIVERY }

/** Where to turn, from the navigator's hint. */
enum class Turn { STRAIGHT, LEFT, RIGHT, UTURN }

/** "Live activity" from an ongoing notification: navigator, taxi, delivery. */
data class LiveInfo(
    val key: String,
    val pkg: String,
    val kind: LiveKind,
    val title: String,
    val text: String,
    val sub: String,
    val icon: ImageBitmap?,
    /** Large image from the notification: maneuver arrow, car, courier */
    val art: ImageBitmap?,
    val postTime: Long,
    val open: PendingIntent?,
    /** Distance to the maneuver or destination ("300 м", "3,7 км"), if found in the notification */
    val distance: String? = null,
    /** Arrival and travel time ("15:52 · 9 мин") */
    val eta: String? = null,
    /** Where to turn, from the text or icon description in the navigator's notification */
    val turn: Turn? = null,
    /** Until when (System.currentTimeMillis) to show; 0 means as long as the notification itself lives. Needed for the test activity. */
    val expiresAt: Long = 0,
)

data class TorchState(
    val on: Boolean = false,
    val cameraId: String? = null,
    val level: Int = 1,
    val maxLevel: Int = 1,
)

data class TimerState(
    val totalMs: Long,
    /** SystemClock.elapsedRealtime() of the end */
    val endAt: Long,
    val pausedLeft: Long? = null,
    val ringing: Boolean = false,
) {
    fun left(now: Long): Long = pausedLeft ?: (endAt - now).coerceAtLeast(0)
    val paused: Boolean get() = pausedLeft != null && !ringing
}

/** [laps] are the total elapsed time at each press of Lap, oldest first. */
data class StopwatchState(val startedAt: Long?, val accumulated: Long, val laps: List<Long> = emptyList()) {
    fun elapsed(now: Long): Long = accumulated + (startedAt?.let { now - it } ?: 0)
    val running: Boolean get() = startedAt != null
}

data class BatteryInfo(
    val level: Int = -1,
    val charging: Boolean = false,
    /** Battery current and voltage, if the device exposes them via BatteryManager. */
    val currentMa: Float? = null,
    val voltageV: Float? = null,
    /** Measured power at the battery, not the charger's rated power. */
    val powerWatts: Float? = null,
    /** Time to full charge: Android's estimate or an explicitly marked approximate one; null means unknown. */
    val remainingMs: Long? = null,
    /** True only for an approximate rate observed during this charging session. */
    val remainingApproximate: Boolean = false,
)

/** How the event looks in the island. */
enum class NoticeStyle {
    /** Wide banner across almost the full width: charging, headphones, Wi-Fi */
    WIDE,
    /** Pill as tall as the collapsed island, shudders when it appears: ringer mode, lock */
    PILL,
    /** App notification: avatar, name and text */
    MESSAGE,
    /** Payment went through: green checkmark and amount */
    PAY_DONE,
    /** Navigator, taxi or delivery just started: wide banner, then capsule */
    LIVE,
    /** Alarm set: wake-up time and how much sleep is left */
    ALARM,
    /** Easter egg: a dialog window like in a game, the line is typed letter by letter */
    EGG,
    /** Reply sent: "Sent" with a checkmark and to whom */
    SENT,
    /** New version: app icon, what's new, and "Later" and "Update" buttons; after install, "Updated" */
    UPDATE,
}

/** Phone payment on screen: which app and its icon. Stays until paid or left. */
data class PayReady(val pkg: String, val icon: ImageBitmap?)

/**
 * Next alarm (AlarmManager.getNextAlarmClock): when it rings, which app set it
 * and what a tap opens, usually the "Clock" alarms screen.
 */
data class AlarmInfo(val triggerAt: Long, val pkg: String?, val show: PendingIntent?)

/**
 * Button from the notification: "Reply", "Mark as read", etc.
 * [inputs] is non-empty for a button with a reply field; then the island shows an input line.
 */
class MsgAction(
    val title: String,
    val intent: PendingIntent,
    val inputs: List<RemoteInput> = emptyList(),
) {
    val isReply: Boolean get() = inputs.isNotEmpty()
}

/** A reply to notification [noticeId] is being typed via button [action]. */
class ReplyState(val noticeId: Long, val action: MsgAction)

/** Another app's notification shown in the island. */
data class AppMessage(
    val key: String,
    val pkg: String,
    val appName: String,
    val text: String,
    val avatar: ImageBitmap?,
    val appIcon: ImageBitmap?,
    val intent: PendingIntent?,
    /** Buttons from the notification, at most two: reply and one more */
    val actions: List<MsgAction> = emptyList(),
    /** Color picked from the app's avatar or icon, for the "From avatar" mode */
    val accent: Color? = null,
    /** All unread messages of the conversation, newest last: shown by the counter with an arrow */
    val history: List<String> = emptyList(),
    /** On the lock screen, only the app name instead of name and text, as in the shade */
    val sensitive: Boolean = false,
    /** Large picture from the notification (BigPictureStyle) */
    val picture: ImageBitmap? = null,
)

/** Notification in "Notification history": waits in the island until read, cleared, or time runs out. */
data class InboxItem(
    val key: String,
    val pkg: String,
    val appName: String,
    val title: String,
    val text: String,
    val avatar: ImageBitmap?,
    val appIcon: ImageBitmap?,
    val intent: PendingIntent?,
    /** When it arrived, System.currentTimeMillis */
    val time: Long,
    val sensitive: Boolean,
)

/**
 * Short iOS-style event: icon and label on the left, state on the right.
 * [battery] draws a battery on the right, [ring] a ring (e.g. headphone charge),
 * [caption] is a small gray line above the label ("Подключено").
 */
data class Notice(
    val title: String,
    val state: String? = null,
    val icon: ImageVector,
    val tint: Color,
    val battery: Int? = null,
    val ring: Float? = null,
    val caption: String? = null,
    val style: NoticeStyle = NoticeStyle.WIDE,
    val message: AppMessage? = null,
    /**
     * How much longer than "Event display" (Animations → Event display) to keep the banner: a message with buttons
     * needs time to press, a payment needs time to tap the phone. Everything is counted from this setting.
     */
    val extraMs: Long = 0,
    /** App icon, e.g. of the bank when paying */
    val appIcon: ImageBitmap? = null,
    /** "Live activity" data for the NoticeStyle.LIVE banner */
    val live: LiveInfo? = null,
    /** Which app to open when the banner is tapped (e.g. this very app for "New version available") */
    val openPkg: String? = null,
    /** Alarm for the NoticeStyle.ALARM banner */
    val alarm: AlarmInfo? = null,
    /** New version for the NoticeStyle.UPDATE banner; null means already updated */
    val update: com.akira.island.update.Release? = null,
    val id: Long = ids.incrementAndGet(),
) {
    private companion object {
        val ids = AtomicLong()
    }
}

object Tints {
    val Green = Color(0xFF34C759)
    val Red = Color(0xFFFF453A)
    val Blue = Color(0xFF0A84FF)
    val Orange = Color(0xFFFF9F0A)
    val Yellow = Color(0xFFFFD60A)
    val Purple = Color(0xFFBF5AF2)
    val Teal = Color(0xFF64D2FF)
    val Gray = Color(0xFF8E8E93)
    val Lavender = Color(0xFFC6C3F7)
}

fun formatDuration(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
