package com.akira.island.data

import android.content.Context
import android.content.SharedPreferences
import com.akira.island.core.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** How many times per second live decorations are drawn: equalizer, glowing dot. 0 means the screen refresh rate */
@Serializable
enum class FxRate(val fps: Int, private val ru: String, private val en: String) {
    ECO(30, "30 кадров — экономнее", "30 fps — saves power"),
    SMOOTH(60, "60 кадров — плавно", "60 fps — smooth"),
    MAX(0, "Как у экрана — до 120", "Screen rate — up to 120"),
    ;

    val title: String get() = tr(ru, en)
}

@Serializable
enum class ExpandStyle(private val ru: String, private val en: String) {
    FRUIT("Как Фрукт", "Like a Fruit"),
    SPRING("Пружина", "Spring"),
    SMOOTH("Плавно", "Smooth"),
    INSTANT("Без анимации", "No animation"),
    ;

    val title: String get() = tr(ru, en)
}

@Serializable
enum class Elasticity(private val ru: String, private val en: String, val damping: Float) {
    NONE("Без упругости", "No bounce", 1f),
    LOW("Слабая упругость", "Light bounce", 0.8f),
    MEDIUM("Средняя упругость", "Medium bounce", 0.62f),
    HIGH("Сильная упругость", "Strong bounce", 0.48f),
    ;

    val title: String get() = tr(ru, en)
}

@Serializable
enum class TapAction(private val ru: String, private val en: String) {
    EXPAND("Раскрыть капсулу", "Expand the capsule"),
    OPEN_APP("Открыть приложение", "Open the app"),
    PLAY_PAUSE("Пауза / воспроизведение", "Play / pause"),
    NEXT_TRACK("Следующий трек", "Next track"),
    SCREENSHOT("Скриншот без острова", "Screenshot without the island"),
    NOTHING("Ничего", "Nothing"),
    DISMISS_NOTIFICATION("Удалить уведомление", "Dismiss notification"),
    SCROLL_TOP("Прокрутить наверх", "Scroll to top"),
    QUICK_LAUNCH("Быстрый запуск", "Quick launch"),
    ;

    val title: String get() = tr(ru, en)
}

/** What a swipe does. [DEFAULT] keeps the island's built-in behaviour for that direction. */
@Serializable
enum class SwipeAction(private val ru: String, private val en: String) {
    DEFAULT("Как обычно", "Default"),
    NOTHING("Ничего", "Nothing"),
    EXPAND("Раскрыть", "Expand"),
    NEXT_CARD("Следующая карточка", "Next card"),
    PREV_CARD("Предыдущая карточка", "Previous card"),
    NEXT_TRACK("Следующий трек", "Next track"),
    PREV_TRACK("Предыдущий трек", "Previous track"),
    PLAY_PAUSE("Пауза / воспроизведение", "Play / pause"),
    DISMISS("Убрать уведомление", "Dismiss notification"),
    CLEAR_ALL("Очистить все уведомления", "Clear all notifications"),
    QUICK_LAUNCH("Быстрый запуск", "Quick launch"),
    SHADE("Шторка уведомлений", "Notification shade"),
    SCREENSHOT("Скриншот без острова", "Screenshot without the island"),
    ;

    val title: String get() = tr(ru, en)
}

/** Buttons of the expanded empty island. The button label is shorter than in settings */
@Serializable
enum class QuickAction(private val ru: String, private val en: String, private val shortRu: String, private val shortEn: String) {
    TIMER("Таймер", "Timer", "Таймер", "Timer"),
    STOPWATCH("Секундомер", "Stopwatch", "Секундомер", "Stopwatch"),
    FLASHLIGHT("Фонарик", "Flashlight", "Фонарик", "Flashlight"),
    SCREENSHOT("Скриншот без острова", "Screenshot without the island", "Скриншот", "Screenshot"),
    CAMERA("Камера", "Camera", "Камера", "Camera"),
    LOCK("Заблокировать экран", "Lock the screen", "Блокировка", "Lock"),
    RINGER("Режим звука", "Sound mode", "Звук", "Sound"),
    DND("Не беспокоить", "Do Not Disturb", "Не беспокоить", "Do Not Disturb"),
    // Android lets apps open these panels, not flip the switches themselves.
    WIFI("Wi-Fi", "Wi-Fi", "Wi-Fi", "Wi-Fi"),
    INTERNET("Мобильный интернет", "Mobile data", "Интернет", "Internet"),
    BLUETOOTH("Bluetooth", "Bluetooth", "Bluetooth", "Bluetooth"),
    HOTSPOT("Точка доступа", "Hotspot", "Точка доступа", "Hotspot"),
    AIRPLANE("Режим полёта", "Airplane mode", "Режим полёта", "Airplane"),
    ROTATION("Автоповорот", "Auto-rotate", "Поворот", "Rotate"),
    BATTERY_SAVER("Экономия заряда", "Battery saver", "Экономия", "Saver"),
    CALCULATOR("Калькулятор", "Calculator", "Калькулятор", "Calculator"),
    RECORDER("Диктофон", "Voice recorder", "Диктофон", "Recorder"),
    QUICK_SETTINGS("Быстрые настройки", "Quick settings", "Настройки", "Settings"),
    ;

    val title: String get() = tr(ru, en)
    val short: String get() = tr(shortRu, shortEn)
}

/** Where the color of the "Reply", "Mark as read" and send buttons in the notification card comes from. */
@Serializable
enum class AccentMode(private val ru: String, private val en: String, private val hintRu: String, private val hintEn: String) {
    DEFAULT("Сиреневый", "Lavender", "Цвет приложения", "The app's color"),
    SYSTEM("Системный", "System", "Цвет из обоев Android 12+ (Material You)", "From your Android 12+ wallpaper (Material You)"),
    ADAPTIVE("Из аватара", "From the avatar", "Подбирается по аватару или значку приложения", "Matched to the avatar or app icon"),
    CUSTOM("Свой", "Custom", "Любой цвет из набора", "Any color from the palette"),
    ;

    val title: String get() = tr(ru, en)
    val hint: String get() = tr(hintRu, hintEn)
}

/** Which side of the capsule the second activity's circle is on. */
@Serializable
enum class BubbleSide(private val ru: String, private val en: String) {
    RIGHT("Справа", "On the right"),
    LEFT("Слева", "On the left"),
    ;

    val title: String get() = tr(ru, en)
}

/** How an app notification looks: a large card or a small pill near the camera. */
@Serializable
enum class MessageStyle(private val ru: String, private val en: String, private val hintRu: String, private val hintEn: String) {
    CARD("Карточка", "Card", "Остров раскрывается сверху: аватар, текст и кнопки", "The island opens from the top: avatar, text and buttons"),
    PILL("Пилюля", "Pill", "Небольшая капсула у камеры: имя и одна строка текста", "A small capsule at the camera: the name and one line of text"),
    ICON("Компактный счётчик", "Compact count", "Без текста у камеры; считает текущее уведомление и историю острова. Нажмите, чтобы прочитать", "No text around the camera; counts the current notification and island history. Tap to read"),
    ;

    val title: String get() = tr(ru, en)
    val hint: String get() = tr(hintRu, hintEn)
}

/** Font of the island text. */
@Serializable
enum class IslandFont(private val ru: String, private val en: String) {
    INTER("Inter — как на iPhone", "Inter — like on iPhone"),
    SYSTEM("Как в системе", "System font"),
    CUSTOM("Свой файл шрифта", "Your own font file"),
    ;

    val title: String get() = tr(ru, en)
}

/** Island on the lock screen. */
@Serializable
enum class LockScreenMode(private val ru: String, private val en: String) {
    SHOW("Показывать", "Show"),
    HIDE("Скрывать до разблокировки", "Hide until unlocked"),
    CALLS("Только звонки", "Calls only"),
    MESSAGES("Сообщения и звонки", "Messages and calls only"),
    ;

    val title: String get() = tr(ru, en)
}

/** Island in landscape orientation (games, video). */
@Serializable
enum class LandscapeMode(private val ru: String, private val en: String, private val hintRu: String, private val hintEn: String) {
    HIDE("Скрывать", "Hide", "Не мешает играм и видео", "Stays out of games and videos"),
    CAMERA("У камеры", "At the camera",
        "Остров остаётся над камерой, как на iPhone: повёрнут вместе с телефоном и не уезжает",
        "The island stays over the camera like on iPhone: it turns with the phone and doesn't move"),
    TOP("Сверху по центру", "Top center", "Посередине верхнего края экрана", "In the middle of the top edge of the screen"),
    ;

    val title: String get() = tr(ru, en)
    val hint: String get() = tr(hintRu, hintEn)
}

/** Rule for notifications from one app. */
@Serializable
enum class AppRule { ALWAYS, NEVER }

@Serializable
enum class MessageIconMode(private val ru: String, private val en: String) {
    DEFAULT("Аватар", "Avatar"), APP("Значок приложения", "App icon"), NONE("Без значка", "No icon");
    val title: String get() = tr(ru, en)
}

@Serializable
enum class MessageIconPosition(private val ru: String, private val en: String) {
    LEFT("Слева", "On the left"),
    CENTER("По центру под камерой", "Centered below the camera"),
    RIGHT("Справа", "On the right"),
    ;

    val title: String get() = tr(ru, en)
}

/**
 * App and island language. "As in system" is the phone's language if the app has it, otherwise English.
 * Language names are in the languages themselves, so you can find yours even if an unreadable one is currently selected.
 */
@Serializable
enum class AppLanguage(val code: String) {
    SYSTEM(""), RU("ru"), EN("en"), ID("id"), PT("pt"), KK("kk"), FR("fr"), ES("es"), JA("ja"), ZH("zh");

    val title: String
        get() = when (this) {
            SYSTEM -> tr("Как в системе", "System default")
            RU -> "Русский"
            EN -> "English"
            ID -> "Bahasa Indonesia"
            PT -> "Português (Brasil)"
            KK -> "Қазақ тілі"
            FR -> "Français"
            ES -> "Español"
            JA -> "日本語"
            ZH -> "简体中文"
        }
}

@Serializable
data class IslandSettings(
    val enabled: Boolean = true,
    /** App and island language */
    val language: AppLanguage = AppLanguage.SYSTEM,

    // Dynamic cards
    val cardMedia: Boolean = true,
    val cardTorch: Boolean = true,
    val cardProgress: Boolean = true,
    val showNetworkRate: Boolean = false,
    val cardTimer: Boolean = true,
    val cardRecording: Boolean = true,
    val cardCharging: Boolean = true,
    val cardCall: Boolean = true,
    val cardMessages: Boolean = true,
    /** "Reply" and "Mark as read" buttons in the notification card */
    val msgActions: Boolean = true,
    /** Navigation, taxi and delivery: "live activities" from ongoing notifications */
    val cardLive: Boolean = true,
    /** Alarm: 12 hours before it, the wake-up time and how much sleep is left */
    val cardAlarm: Boolean = true,
    /** The island's buttons for the "Clock" timer press the "Clock" app's own buttons (without notifications, by opening it for a moment) */
    val clockAutoTap: Boolean = true,
    /** "Set a timer" from the assistant and widgets can be handed to the island */
    val timerFromAssistant: Boolean = true,
    /** When a timer, alarm or incoming call rings, the island turns on the screen and keeps it from going off */
    val wakeForAlerts: Boolean = true,
    /** The island greets special songs with effects in time with the music */
    val easterEggs: Boolean = true,

    // Events
    val evChargeStart: Boolean = true,
    val evChargeStop: Boolean = true,
    val evLowBattery: Boolean = true,
    val evWifiOn: Boolean = true,
    val evWifiOff: Boolean = true,
    val evHeadsetOn: Boolean = true,
    val evHeadsetOff: Boolean = true,
    val evAirplaneOn: Boolean = true,
    val evAirplaneOff: Boolean = true,
    val evRinger: Boolean = true,
    val evBrightness: Boolean = true,
    val evUsbOn: Boolean = true,
    val evUsbOff: Boolean = true,
    val evUnlock: Boolean = false,
    /** Optional, verified face unlock only. Never falls back to a generic unlock event. */
    val faceUnlockOnly: Boolean = false,
    /** On the lock screen the capsule shows a lock; it goes away after unlock */
    val evLock: Boolean = true,
    val evDnd: Boolean = true,
    /** Phone payment screen (T-Pay, SberPay, Mir Pay, Google Wallet…): "Payment · To terminal" */
    val evPayReady: Boolean = true,
    /** "To terminal" immediately when a bank or wallet is opened, not only on the phone payment screen */
    val payHintOnOpen: Boolean = false,
    /** Payment notification arrived: "Paid" with a checkmark */
    val evPaySuccess: Boolean = true,
    /** Alarm set: a banner with the wake-up time */
    val evAlarmSet: Boolean = true,

    // Size and position, dp. Android cameras are smaller than the iPhone's (there the capsule is 126×37), so the island
    // is smaller by default too; width and height are set in "Position and size"
    val width: Int = 104,
    val height: Int = 31,
    val offsetX: Int = 0,
    val offsetY: Int = 11,
    /** Independent edge trim for waterdrop cutouts; old notch placements retain their zero offset. */
    val notchOffsetY: Int = 0,
    /**
     * "Like a cutout": the island touches the top edge of the screen and has a flat top, for phones with a waterdrop
     * notch: the capsule covers the cutout and grows down from it. No top offset is needed then.
     */
    val notch: Boolean = false,
    /** Free space above the camera in the middle of the capsule, where content doesn't go */
    val cameraGap: Int = 34,
    /** How much thicker a capsule with an activity (music, timer, charging) is than an empty one */
    val activeExtra: Int = 6,
    /** How much wider a collapsed capsule with an activity (navigator, flashlight, music) is than the idle one; 0 means the same */
    val activeWidth: Int = 40,
    // Missing in old settings → 1, then sizes are migrated to the new ones
    val layoutVersion: Int = 1,

    // Colors
    val color: Long = 0xFF000000,
    val outline: Boolean = false,
    val outlineColor: Long = 0xFF3A3A40,
    val outlineWidth: Float = 1f,
    val outlineFromArt: Boolean = true,
    val shadow: Boolean = false,

    // Button color in the notification card
    val accentMode: AccentMode = AccentMode.DEFAULT,
    val accentColor: Long = 0xFF0A84FF,
    val textColorEnabled: Boolean = false,
    val textColor: Long = 0xFFFFFFFF,

    // Updates from GitHub Releases
    val autoUpdateCheck: Boolean = true,
    /** Version that was declined: we don't remind about it anymore */
    val skippedVersion: String = "",
    /** Version for which "What's new" was already shown */
    val lastSeenVersion: String = "",
    val lastUpdateCheck: Long = 0,
    /** Which version the island has already seen: after an update it shows "Updated" once */
    val islandVersion: String = "",

    // Liquid Glass: translucent glass with highlights instead of a black fill. Off by default
    val liquidGlass: Boolean = false,
    val glassOpacity: Int = 68,
    val glassBlurStrength: Int = 50,
    val backdropBlur: Boolean = false,

    // Animations
    val expandStyle: ExpandStyle = ExpandStyle.FRUIT,
    val durationPercent: Int = 35,
    /**
     * "Liquid expand" ("Appearance"): content grows with the island and fades in from blur.
     * Duration and springiness are the same as without it. Off means the old flowing transition
     */
    val liquidExpand: Boolean = true,
    /** Weak springiness: with strong, the island, as commenters wrote, "bounces" too much */
    val elasticity: Elasticity = Elasticity.LOW,
    val reduceEffects: Boolean = false,
    val haptics: Boolean = true,

    // Effects: each can be turned off separately; "Reduce effects" turns them all off
    /** Island content flows with a blur (Android 12+) */
    val fxBlur: Boolean = true,
    /** Equalizer bars dance to the music; off means they stand flat, which saves more power */
    val fxEqualizer: Boolean = true,
    /** Equalizer and glow frame rate: 60 is smooth and half the load of the screen's 120 */
    val fxRate: FxRate = FxRate.SMOOTH,
    /** Soft glow of the cover color around the island while music plays */
    val fxGlow: Boolean = false,
    /** NFC waves around the island while waiting for the terminal, and a flash with sparks when payment succeeds */
    val fxPay: Boolean = true,
    /** The capsule shudders when the ringer mode changes, the bell of a ringing timer swings */
    val fxShake: Boolean = true,
    /** In Android power saving mode the island goes without live effects. Off by default */
    val batterySaver: Boolean = false,

    // Gestures
    val tapAction: TapAction = TapAction.EXPAND,
    val doubleTapAction: TapAction = TapAction.NOTHING,
    val longPressAction: TapAction = TapAction.OPEN_APP,
    val swipeTracks: Boolean = true,
    val swipeExpand: Boolean = true,

    // Lyrics
    val lyricsEnabled: Boolean = true,

    // Behavior
    val hideIdle: Boolean = false,
    /** Hide an inactive empty capsule after this many seconds; 0 disables the timer. */
    val idleHideSec: Int = 0,
    val hideInLandscape: Boolean = true,
    val hideWhenPlayerOpen: Boolean = true,
    /** "Scheduled": the island is visible only from [scheduleFrom] to [scheduleTo] o'clock (may span midnight) */
    val scheduleOn: Boolean = false,
    val scheduleFrom: Int = 10,
    val scheduleTo: Int = 23,
    val noticeDurationMs: Int = 2500,
    val timerMinutes: Int = 5,
    /** After how many seconds without touches the expanded island collapses by itself; 0 means it doesn't collapse */
    val autoCollapseSec: Int = 0,
    /** Second activity is a circle next to the capsule, like on iPhone */
    val bubble: Boolean = true,
    /** A third activity goes into a pill on the other side of the capsule (needs the bubble). */
    val thirdBubble: Boolean = true,
    /** Tapping the empty island expands it: clock, charge and [quickActions] */
    val idleExpand: Boolean = true,
    /** Buttons of the expanded empty island; empty means only clock and charge */
    val quickActions: List<QuickAction> = listOf(QuickAction.TIMER, QuickAction.STOPWATCH, QuickAction.FLASHLIGHT),
    /** "Keep island alive": a service with a quiet notification so the island isn't closed along with recent apps */
    val keepAlive: Boolean = false,
    /** How many lines of text are visible in the notification card */
    val msgLines: Int = 3,
    /** Notifications of the currently open app also come to the island */
    val msgInApp: Boolean = false,
    /** Messengers whose messages have already been in the island, and their notification channel: for "System banners" */
    val msgChannels: Map<String, String> = emptyMap(),
    /** The "Notifications arrive twice?" hint on the home screen was removed */
    val bannerHintHidden: Boolean = false,

    // 1.7: by request from the comments
    /** Island fill opacity, %: 100 is solid color, less lets you see what's under the island */
    val islandOpacity: Int = 100,
    /** Width of the expanded island, banners and notifications, % of normal (almost full screen width) */
    val expandedWidthPercent: Int = 100,
    /** Size of text and icons in the expanded island and notifications, % */
    val contentScale: Int = 100,
    /** Which side of the capsule the second activity's circle is on */
    val bubbleSide: BubbleSide = BubbleSide.RIGHT,
    /** Look of app notifications */
    val messageStyle: MessageStyle = MessageStyle.CARD,
    /** Island font; for [IslandFont.CUSTOM] the file chosen in "Font" */
    val islandFont: IslandFont = IslandFont.INTER,
    /** Name of the custom font: to show it in settings */
    val customFontName: String = "",
    /** Custom font file in the app's private folder: each choice has its own name so a new font isn't mixed up with the old one */
    val customFontFile: String = "",
    /** Island text weight: -1 thinner, 0 as designed, 1 and 2 bolder */
    val fontWeightShift: Int = 0,
    /** Delay before an event appears in the island, ms */
    val noticeDelayMs: Int = 0,
    /** How long an app notification is visible: 0 same as events (with reading margin), -1 until swiped away, otherwise seconds */
    val messageSeconds: Int = 0,
    /** Repeats (the same message from the same app again) aren't shown */
    val msgDedupe: Boolean = true,
    /**
     * "Notification history": after the banner a notification waits in the island this many minutes, app icons in the capsule,
     * a list on expand. 0 is off, -1 until you read or clear it
     */
    val historyMinutes: Int = 0,
    /** Also show quiet notifications: those the system doesn't show as pop-ups (like Snapchat and many apps) */
    val msgQuiet: Boolean = false,
    /** Custom per-app rules: always show or never */
    val msgAppRules: Map<String, AppRule> = emptyMap(),
    /** Island on the lock screen */
    val lockScreen: LockScreenMode = LockScreenMode.SHOW,
    /** Island in landscape orientation; replaces "Hide in landscape orientation" */
    val landscape: LandscapeMode = LandscapeMode.HIDE,
    /** Charging is only a banner on plug-in, without a capsule with the percentage the whole time it charges */
    val chargingBrief: Boolean = false,

    // Optional presentation controls. Defaults preserve the existing 1.7 layout.
    val idleClockBattery: Boolean = false,
    val preferMusic: Boolean = false,
    val keepExpandedOnOutsideTouch: Boolean = false,
    val adaptiveChargeEta: Boolean = false,
    val showAlarmCreator: Boolean = false,
    val showPlayerBattery: Boolean = false,
    val swipeActivities: Boolean = false,
    val vinylArt: Boolean = false,
    val hideWhenSourceOpen: Boolean = false,
    val collapseOnShade: Boolean = false,
    val hideOnAod: Boolean = false,
    val hideWhileRecording: Boolean = false,
    val evVpn: Boolean = false,
    val evHotspot: Boolean = false,
    val evDebugging: Boolean = false,
    /** Which kinds of progress notification get a card (all of them, unless the progress indicator is off). */
    val transferDownload: Boolean = true,
    val transferUpload: Boolean = true,
    val transferBackup: Boolean = true,
    val transferCopy: Boolean = true,
    val transferDelete: Boolean = true,
    val transferInstall: Boolean = true,
    /** Swipes on the collapsed island; [SwipeAction.DEFAULT] is what it did before these were configurable. */
    val swipeUp: SwipeAction = SwipeAction.DEFAULT,
    val swipeDown: SwipeAction = SwipeAction.DEFAULT,
    val swipeLeft: SwipeAction = SwipeAction.DEFAULT,
    val swipeRight: SwipeAction = SwipeAction.DEFAULT,
    /** Swiping down on an expanded card. */
    val expandedSwipeDown: SwipeAction = SwipeAction.DEFAULT,
    /** Holding, then swiping up on a notification or an expanded card clears every notification. */
    val holdSwipeClears: Boolean = false,
    /** Percent: higher means a shorter swipe is enough. */
    val gestureSensitivity: Int = 100,
    /** Percent of a normal tap; 100 uses the system's own feedback. */
    val hapticStrength: Int = 100,
    /** A short banner on every full hour. Off by default: it appears even when nothing else is going on. */
    /** A dot beside the capsule while the camera (green) or microphone (orange) is in use. */
    val privacyDots: Boolean = false,
    /** Apps in front of which the island switches itself off (games, video). */
    val exemptApps: Set<String> = emptySet(),
    /** Seconds before the same app's next notification banner may show; 0 is off. */
    val msgCooldownSec: Int = 0,
    /** Keep a searchable list of shown notifications on this phone only. */
    val keepHistory: Boolean = false,
    /** Shows the developer entries in About (log export). Switched on by tapping the version seven times. */
    val devMode: Boolean = false,
    val hourTick: Boolean = false,
    /** A banner with its own glyph at sunrise (6:00), afternoon (12:00), sunset (17:30) and midnight (23:00). */
    val dayMoments: Boolean = false,
    val evFullCharge: Boolean = true,
    val evPowerSave: Boolean = true,
    val evWiredHeadset: Boolean = true,
    /** Bonded non-audio Bluetooth devices: watches, cars, keyboards. Off by default: some reconnect often. */
    val evBluetooth: Boolean = false,
    /** Quick launch: pinned apps, recent apps and tools in the expanded island. */
    val quickLaunch: Boolean = true,
    val launcherApps: List<String> = emptyList(),
    val launcherRecents: Boolean = true,
    val launcherTools: List<QuickAction> = QuickAction.entries.filter { it != QuickAction.TIMER },
    /** -1 preserves the automatic capsule/card radius. */
    val cornerRadius: Int = -1,
    val notchTopRadius: Int = 0,
    val expandedCornerRadius: Int = -1,
    /** Scrollable content limit, in dp; constrained to the screen by the renderer. */
    val expandedMaxHeight: Int = 480,
    val expandedColorEnabled: Boolean = false,
    val expandedColor: Long = 0xFF000000,
    val expandedOutlineColor: Long = 0xFF3A3A40,
    /** -1 inherits the capsule outline. */
    val expandedOutlineWidth: Float = -1f,
    val expandedOutlineOpacity: Int = 100,
    val outlineOpacity: Int = 100,
    /** Per-edge widths; negative values inherit the corresponding uniform outline width. */
    val outlineTopWidth: Float = -1f,
    val outlineRightWidth: Float = -1f,
    val outlineBottomWidth: Float = -1f,
    val outlineLeftWidth: Float = -1f,
    val expandedOutlineTopWidth: Float = -1f,
    val expandedOutlineRightWidth: Float = -1f,
    val expandedOutlineBottomWidth: Float = -1f,
    val expandedOutlineLeftWidth: Float = -1f,

    val bottomGradient: Int = 0,
    val messageIconMode: MessageIconMode = MessageIconMode.DEFAULT,
    /** Kept for settings saved before the three-position choice was added. */
    val messageIconRight: Boolean = false,
    /** Null inherits the saved left/right choice without changing existing notification layouts. */
    val messageIconPosition: MessageIconPosition? = null,
    val batteryPercentOnly: Boolean = false,
    val liveBanner: Boolean = true,
    val actionColorEnabled: Boolean = false,
    val actionColor: Long = 0xFFC6C3F7,

) {
    val notificationIconPosition: MessageIconPosition
        get() = messageIconPosition ?: if (messageIconRight) MessageIconPosition.RIGHT else MessageIconPosition.LEFT

    fun withMessageIconPosition(position: MessageIconPosition): IslandSettings = copy(
        messageIconPosition = position,
        messageIconRight = position == MessageIconPosition.RIGHT,
    )

    fun transferEnabled(kind: com.akira.island.core.TransferKind): Boolean = when (kind) {
        com.akira.island.core.TransferKind.DOWNLOAD -> transferDownload
        com.akira.island.core.TransferKind.UPLOAD -> transferUpload
        com.akira.island.core.TransferKind.BACKUP -> transferBackup
        com.akira.island.core.TransferKind.COPY_MOVE -> transferCopy
        com.akira.island.core.TransferKind.DELETE -> transferDelete
        com.akira.island.core.TransferKind.INSTALL -> transferInstall
    }

    /** With "Battery saver" in Android power saving: same as "Reduce effects" */
    fun forPowerSave(on: Boolean): IslandSettings = if (on && batterySaver && !reduceEffects) copy(reduceEffects = true) else this

    /** Whether the island's working hours are now in effect: the schedule is off or hour [hour] is inside it (22–7 spans midnight) */
    fun inSchedule(hour: Int): Boolean = when {
        !scheduleOn || scheduleFrom == scheduleTo -> true
        scheduleFrom < scheduleTo -> hour in scheduleFrom until scheduleTo
        else -> hour >= scheduleFrom || hour < scheduleTo
    }

    /** Island top offset: with "Like a cutout" the island starts at the very edge of the screen */
    val topOffset: Int get() = if (notch) notchOffsetY.coerceIn(-10, 30) else offsetY

    /** Whether the equalizer dances */
    val liveEqualizer: Boolean get() = fxEqualizer && !reduceEffects
}

object SettingsRepo {
    private const val KEY = "settings_v1"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private var prefs: SharedPreferences? = null

    /** Settings were already saved before this launch: so this is an update, not a first install. */
    var hadSavedSettings = false
        private set
    private val _state = MutableStateFlow(IslandSettings())
    val state: StateFlow<IslandSettings> = _state.asStateFlow()
    val current: IslandSettings get() = _state.value

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("island", Context.MODE_PRIVATE)
        prefs = p
        hadSavedSettings = p.contains(KEY)
        p.getString(KEY, null)?.let { raw ->
            runCatching { json.decodeFromString<IslandSettings>(raw) }.onSuccess { _state.value = it }
        }
        // 1.1 and earlier: default sizes were smaller than the iPhone's; migrate if they weren't touched
        val cur = _state.value
        // 1.4: auto-fit could set a strange camera cutout; reset to default
        if (cur.layoutVersion in 2..2) update { copy(cameraGap = IslandSettings().cameraGap, layoutVersion = 3) }
        if (cur.layoutVersion < 2) {
            update {
                copy(
                    width = if (width == 96) 126 else width,
                    height = if (height == 34) 37 else height,
                    offsetY = if (offsetY == 8) 11 else offsetY,
                    layoutVersion = 3,
                )
            }
        }
        // 1.6.2: the old iPhone size 126×37 is too big on Android; if it wasn't changed, set the new, smaller one
        if (current.layoutVersion < 4) {
            val d = IslandSettings()
            update {
                val old = width == 126 && height == 37
                copy(
                    width = if (old) d.width else width,
                    height = if (old) d.height else height,
                    activeWidth = if (old && activeWidth == 48) d.activeWidth else activeWidth,
                    // Strong springiness was the default; switch to the new, softer one
                    elasticity = if (elasticity == Elasticity.HIGH) d.elasticity else elasticity,
                    layoutVersion = 4,
                )
            }
        }
        migrateTo5()
    }

    /**
     * 1.7: instead of "Hide in landscape orientation", a choice of three. With hiding off, the island was shown
     * at the top center, and that stays.
     */
    private fun migrateTo5() {
        if (current.layoutVersion >= 5) return
        update { copy(landscape = if (hideInLandscape) LandscapeMode.HIDE else LandscapeMode.TOP, layoutVersion = 5) }
    }

    fun update(block: IslandSettings.() -> IslandSettings) {
        val next = _state.value.block()
        if (next == _state.value) return
        _state.value = next
        prefs?.edit()?.putString(KEY, json.encodeToString(next))?.apply()
    }

    fun reset() = update { IslandSettings() }

    fun exportJson(): String = json.encodeToString(_state.value)

    /**
     * Applies settings from a backup. Things that belong to this phone stay: the update bookkeeping, the list of
     * messengers seen, and a custom font file (it lives in this app's private folder, so it can't travel).
     */
    fun importJson(raw: String, dryRun: Boolean = false): Boolean = runCatching {
        val incoming = json.decodeFromString<IslandSettings>(raw)
        if (!dryRun) update {
            incoming.copy(
                skippedVersion = skippedVersion, lastSeenVersion = lastSeenVersion, lastUpdateCheck = lastUpdateCheck,
                islandVersion = islandVersion, msgChannels = msgChannels,
                islandFont = if (incoming.islandFont == IslandFont.CUSTOM && customFontFile.isEmpty()) IslandFont.INTER else incoming.islandFont,
                customFontName = customFontName, customFontFile = customFontFile,
            )
        }
        true
    }.getOrDefault(false)

    fun resetAnimations() = update {
        val d = IslandSettings()
        copy(
            expandStyle = d.expandStyle,
            durationPercent = d.durationPercent,
            liquidExpand = d.liquidExpand,
            elasticity = d.elasticity,
            reduceEffects = d.reduceEffects,
        )
    }

    fun resetEffects() = update {
        val d = IslandSettings()
        copy(
            fxBlur = d.fxBlur, fxEqualizer = d.fxEqualizer, fxRate = d.fxRate, fxGlow = d.fxGlow, fxPay = d.fxPay,
            fxShake = d.fxShake, batterySaver = d.batterySaver, backdropBlur = d.backdropBlur,
        )
    }
}
