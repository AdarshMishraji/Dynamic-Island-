package com.akira.island.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.display.DisplayManager
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.akira.island.core.Controls
import com.akira.island.core.GlassTilt
import com.akira.island.core.GlassTiltFilter
import com.akira.island.core.glassTiltFromGravity
import com.akira.island.core.IslandBus
import com.akira.island.core.ServiceCommand
import com.akira.island.core.tr
import com.akira.island.R
import com.akira.island.core.LangScope
import com.akira.island.data.IslandSettings
import com.akira.island.data.LandscapeMode
import com.akira.island.data.LockScreenMode
import com.akira.island.data.SettingsRepo
import com.akira.island.island.Egg
import com.akira.island.island.ISLAND_PAD_DP
import com.akira.island.island.IslandOverlay
import com.akira.island.island.IslandTheme
import com.akira.island.island.IslandType
import com.akira.island.island.Rotated
import com.akira.island.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Accessibility service: only it can draw over the status bar
 * (TYPE_ACCESSIBILITY_OVERLAY), so the capsule lives here.
 *
 * There are two windows. The drawing window is a full-width strip across the screen; touches pass through it. Its size
 * does not change while the island is running: when the window is repositioned, the system briefly shows the old image
 * in the new place, or on some shells (ColorOS) does not show the window at all, so the island flickers.
 * It changes only with settings and screen rotation. The invisible touch window sits exactly over
 * the island and forwards touches to the drawing window; it is repositioned when the island is at rest.
 */
class IslandService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var privacy: AutoCloseable? = null
    private var inExemptApp = false
    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var display: FrameLayout? = null
    private var touch: TouchRoot? = null
    private var networkRateMonitor: NetworkRateMonitor? = null
    private var backdrop: IslandBackdropBlur? = null
    private var bubbleBackdrop: IslandBackdropBlur? = null
    private var backdropGeometry: com.akira.island.island.BackdropGeometry? = null
    private var backdropPending = false
    private var backdropSampler: (() -> Unit)? = null
    private var backdropClosed = false
    private val backdropUpdate = Runnable {
        if (backdropPending && !backdropClosed) {
            backdropPending = false
            updateBackdrop()
        }
    }
    private val owner = OverlayLifecycleOwner()
    private var events: SystemEvents? = null
    private var faceUnlock: com.akira.island.face.FaceUnlockController? = null
    private var torch: TorchController? = null
    private var alarm: TimerAlarm? = null
    private var calls: CallMonitor? = null
    private var navReader: NavScreenReader? = null
    private var clockReader: ClockScreenReader? = null
    private var payReader: PayScreenReader? = null
    private var scrollToTop: ScrollToTop? = null
    private var alarmMonitor: AlarmMonitor? = null
    private var screenEvents = false
    private var connected = false
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    /** The lock screen was showing: wait for it to go away so the unlock can be shown right away */
    private var keyguardShown = false

    /** The keyboard has already appeared while reply input is open: when it is hidden, the input closes */
    private var keyboardSeen = false
    /** A timer is ringing or an incoming call is active; [wakeBlocked]: the screen was turned off with the button while it was ringing */
    private var alerting = false
    private var wakeBlocked = false
    private var lastScreenOn = true
    private var replySince = 0L
    @Volatile private var imeSnapshot = ImeSnapshot(0L, emptySet())

    private var tiltOn = false
    private val tiltFilter = GlassTiltFilter()
    private var screenRotation = Surface.ROTATION_0
    private val tiltListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val next = tiltFilter.update(glassTiltFromGravity(e.values[0], e.values[1], screenRotation,
                IslandBus.contentRotation.value), e.timestamp)
            val previous = IslandBus.glassTilt.value
            if (abs(next.x - previous.x) > 0.01f || abs(next.y - previous.y) > 0.01f) IslandBus.glassTilt.value = next
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private var visible = true
    private var body = Rect.Zero
    private var bubble = Rect.Zero
    private var third = Rect.Zero
    /** The moment (uptimeMillis) since which the touch window has been waiting to be repositioned; 0 means not waiting */
    private var touchSince = 0L
    private val touchUpdate = Runnable {
        touchSince = 0L
        updateTouchWindow()
    }
    /** Screenshot without the island: the shot is taken when the window is already transparent, and the island comes back afterwards */
    private val shoot = Runnable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
    }
    private val showAgain = Runnable { display?.alpha = 1f; scheduleBackdrop() }

    /** Screen rotation: with "Near camera" in landscape, the island window sits at the edge with the camera */
    private val displays by lazy { getSystemService(DisplayManager::class.java) }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                readRotation()
                applyCurrentSettings()
            }
        }
    }

    private val displayParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "DynamicIsland"
        cutoutAlways()
    }

    private val touchParams = WindowManager.LayoutParams(
        1,
        1,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "DynamicIslandTouch"
        x = -10
        y = -10
        cutoutAlways()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        SettingsRepo.init(this)
        // Android may reconnect this same service instance. Do not create duplicate collectors/windows.
        if (connected) {
            applyCurrentSettings()
            return
        }
        connected = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        IslandBus.landscape.value = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        readRotation()
        // A 180° rotation (from one landscape to the other) does not change the configuration; only the display reports it
        runCatching { displays?.registerDisplayListener(displayListener, handler) }

        createWindows()
        KeepAliveService.sync(this)
        IslandTile.refresh(this)
        events = SystemEvents(this).also { runCatching { it.start() } }
        HourTick.start(this, scope)
        privacy = PrivacyMonitor.start(this)
        keyguardShown = keyguard?.isKeyguardLocked == true
        torch = TorchController(this).also { runCatching { it.start() } }
        alarm = TimerAlarm(this, scope).also { runCatching { it.start() } }
        calls = CallMonitor(this, scope).also { runCatching { it.start() } }
        navReader = NavScreenReader(this, scope)
        clockReader = ClockScreenReader(this, scope).also { ClockControl.reader = it }
        payReader = PayScreenReader(this, scope)
        scrollToTop = ScrollToTop(scope, { windows },
            canRun = { IslandBus.screenOn.value && !IslandBus.locked.value && !IslandBus.keyguard.value &&
                keyguard?.isKeyguardLocked == false && getSystemService(PowerManager::class.java)?.isInteractive == true },
            isExcluded = { pkg, cls -> pkg == packageName || pkg == "com.android.systemui" || isKeyboard(pkg, cls) },
            report = { result ->
                val message = when (result) {
                    ScrollTopResult.UNSUPPORTED -> tr("На этом экране нет доступной прокрутки вверх", "No supported upward scroll action on this screen")
                    ScrollTopResult.LIMIT_REACHED -> tr("Прокрутка остановлена. Повторите жест, чтобы продолжить.", "Scrolling stopped. Repeat the gesture to continue.")
                    else -> null
                }
                if (message != null) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            })
        alarmMonitor = AlarmMonitor(this).also { runCatching { it.start() } }
        WeatherRepo.start(this, scope)
        IslandBus.serviceRunning.value = display != null && touch != null
        faceUnlock = com.akira.island.face.FaceUnlockController(this, scope).also { it.start() }
        networkRateMonitor = NetworkRates.monitor().also { it.start(scope) }

        // An incoming call was swiped away: it stays hidden while it rings; once it stops ringing, the next incoming call shows in the island again.
        // Here, not in the island: while the island is hidden it is not drawn and would not reset anything
        scope.launch {
            IslandBus.calls.map { calls -> calls.values.any { it.incoming } }.distinctUntilChanged()
                .collect { ringing -> if (!ringing) IslandBus.callHidden.value = false }
        }

        // Things only the service can do: screenshot, lock, power off, and scroll by gesture
        scope.launch {
            IslandBus.commands.collect { cmd ->
                when (cmd) {
                    ServiceCommand.SCREENSHOT -> screenshotWithoutIsland()
                    ServiceCommand.LOCK -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                    ServiceCommand.TURN_OFF -> disableSelf()
                    ServiceCommand.SCROLL_TOP -> scrollToTop?.request()
                    ServiceCommand.QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                    ServiceCommand.SHADE -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                }
            }
        }

        // While a notification reply is being typed, the window needs focus, otherwise the keyboard will not show
        scope.launch {
            IslandBus.reply.collect {
                keyboardSeen = false
                replySince = SystemClock.elapsedRealtime()
                setInputFocus(it != null)
            }
        }
        // A timer, a Clock alarm, or an incoming call is ringing: the screen turns on and stays on while it rings
        scope.launch {
            combine(IslandBus.timer, IslandBus.calls, IslandBus.ringingAlarms, IslandBus.screenOn) { t, calls, alarms, on ->
                (t?.ringing == true || calls.values.any { it.incoming } || alarms.isNotEmpty()) to on
            }.distinctUntilChanged().collect { (alert, on) -> onAlert(alert, on) }
        }
        // The screen went off: the lock screen comes next, so wait for it to go away; there will be no phone payment anymore
        scope.launch {
            IslandBus.screenOn.collect {
                applyCurrentSettings()
                if (!it) {
                    scrollToTop?.cancel()
                    keyguardShown = true
                    PayMonitor.clearReady()
                }
            }
        }
        // The phone was locked: replying from the lock screen is not allowed
        scope.launch { IslandBus.locked.collect { if (it) { IslandBus.reply.value = null; scrollToTop?.cancel() } } }
        scope.launch { IslandBus.keyguard.collect { if (it) scrollToTop?.cancel() } }

        // New version on GitHub: the check limits itself to once every 3 hours, here we only wake it up
        scope.launch {
            while (isActive) {
                if (IslandBus.screenOn.value) UpdateChecker.check(this@IslandService, force = false, announce = true)
                delay(60 * 60 * 1000L)
            }
        }

        // The sensor is not needed with the screen off, with static effects, and in battery saver.
        scope.launch {
            combine(SettingsRepo.state, IslandBus.screenOn, IslandBus.powerSave) { saved, on, saving ->
                val s = saved.forPowerSave(saving)
                s.liquidGlass && s.enabled && !s.reduceEffects && on
            }
                .distinctUntilChanged()
                .collect { refreshTilt() }
        }

        scope.launch {
            combine(SettingsRepo.state, IslandBus.landscape, IslandBus.offSchedule, IslandBus.keyguard, IslandBus.displayRotation) { s, land, _, kg, rot ->
                Placement(s, land, kg, rot)
            }.collect { p -> applySettings(p.s, p.landscape, p.keyguard, p.rotation) }
        }

        scope.launch {
            IslandBus.chrono.map { items -> items.values.any { it.screenRecording } }
                .distinctUntilChanged().collect { applyCurrentSettings() }
        }

        // "On schedule": check exactly at the hour boundaries and when the screen turns on (while the phone sleeps, the wait is paused)
        scope.launch {
            combine(SettingsRepo.state.map { Triple(it.scheduleOn, it.scheduleFrom, it.scheduleTo) }.distinctUntilChanged(), IslandBus.screenOn) { sch, _ -> sch }
                .collectLatest { (on) ->
                    while (true) {
                        val now = Calendar.getInstance()
                        IslandBus.offSchedule.value = !SettingsRepo.current.inSchedule(now.get(Calendar.HOUR_OF_DAY))
                        if (!on) break
                        val next = (now.clone() as Calendar).apply {
                            add(Calendar.HOUR_OF_DAY, 1)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        delay(next.timeInMillis - now.timeInMillis + 500)
                    }
                }
        }

        // Updated: show the "Updated" card once, as in the video (the first install stays silent)
        val version = UpdateChecker.currentVersion(this)
        val seen = SettingsRepo.current.islandVersion
        if (seen != version) {
            if (seen.isNotEmpty() && SettingsRepo.current.autoUpdateCheck) {
                IslandBus.post(UpdateChecker.updatedNotice(this, version))
            }
            SettingsRepo.update { copy(islandVersion = version) }
        }

        // Easter egg: that particular song is playing, so the island celebrates (the window height is enough for the effects)
        Egg.watch(this, scope)
    }

    private fun createWindows() {
        backdropClosed = false
        val compose = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                IslandTheme {
                    LangScope {
                        // "Near camera" in landscape: the island is rotated toward the edge with the camera, as if the screen had not been rotated
                        val rotation by IslandBus.contentRotation.collectAsState()
                        Rotated(rotation) {
                            IslandOverlay(
                                // Rounded to whole pixels: fractions of a pixel at the end of the spring do not move the window
                                onBodyBounds = { r -> val q = r.snapped(); if (q != body) { body = q; onBoundsChanged() } },
                                onBubbleBounds = { r -> val q = r.snapped(); if (q != bubble) { bubble = q; onBoundsChanged() } },
                                onThirdBounds = { r -> val q = r.snapped(); if (q != third) { third = q; onBoundsChanged() } },
                                onBackdropSampler = { sampler ->
                                    backdropSampler = sampler
                                    if (sampler == null) { backdropGeometry = null; scheduleBackdrop() }
                                },
                                onBackdropShape = { geometry ->
                                    if (backdropGeometry != geometry) {
                                        backdropGeometry = geometry
                                        scheduleBackdrop()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        val drawRoot = object : FrameLayout(this) {
            // While a reply is being typed, the island window has focus and "Back" arrives here: close the input instead of swallowing the gesture
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && IslandBus.reply.value != null) {
                    if (event.action == KeyEvent.ACTION_UP) IslandBus.reply.value = null
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        drawRoot.addView(compose)
        drawRoot.viewTreeObserver.addOnPreDrawListener {
            // Sample animated graphics-layer transforms even when content itself is cached.
            backdropSampler?.invoke()
            // Layout has supplied the current bounds; prepare the material before foreground draw.
            if (backdropPending) backdropUpdate.run()
            true
        }
        owner.create()
        owner.attach(drawRoot)
        placeDisplayWindow(SettingsRepo.current, IslandBus.contentRotation.value)
        runCatching { wm?.addView(drawRoot, displayParams) }.onSuccess { display = drawRoot }

        val touchRoot = TouchRoot(this, { display }, ::onOutsideTouch)
        runCatching { wm?.addView(touchRoot, touchParams) }.onSuccess { touch = touchRoot }
        scope.launch {
            combine(SettingsRepo.state, IslandBus.expanded, IslandBus.screenOn, IslandBus.powerSave, IslandBus.locked) {
                    _, _, _, _, _ -> Unit
            }.collect { scheduleBackdrop() }
        }
    }

    /**
     * A touch outside the expanded island collapses it, unless keeping the card is turned on.
     * A touch outside the reply field closes the input by the same keyboard rule as before.
     * Taps on the keyboard also arrive here as "outside" (it is a separate window), and the system does not say
     * where exactly the touch was. So while the keyboard is on screen, do not close the input:
     * it is closed by "Back" (the keyboard hides) or by swiping the banner up.
     */
    private fun onOutsideTouch() {
        val replyOpen = IslandBus.reply.value != null
        when (com.akira.island.core.outsideTouchDecision(IslandBus.expanded.value, IslandBus.messageDetail.value,
            replyOpen, replyOpen && keyboardShown(), SettingsRepo.current.keepExpandedOnOutsideTouch)) {
            com.akira.island.core.OutsideTouchDecision.KEEP -> Unit
            com.akira.island.core.OutsideTouchDecision.CLOSE_REPLY -> IslandBus.reply.value = null
            com.akira.island.core.OutsideTouchDecision.COLLAPSE -> {
                IslandBus.messageDetail.value = false
                Controls.collapse()
            }
        }
    }

    /** Whether the keyboard is visible. If the window list is unavailable, assume it is visible, so the input is not cut off. */
    private fun keyboardShown(): Boolean = runCatching {
        val list = windows
        list.isEmpty() || list.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }.getOrDefault(true)

    private fun applyCurrentSettings() {
        applySettings(SettingsRepo.current, IslandBus.landscape.value,
            IslandBus.keyguard.value, IslandBus.displayRotation.value)
        scheduleBackdrop()
    }

    private fun scheduleBackdrop() {
        if (backdropClosed || (backdrop == null && (!SettingsRepo.current.liquidGlass || Build.VERSION.SDK_INT < 31))) return
        val s = SettingsRepo.current
        if (!s.enabled || !visible || !IslandBus.screenOn.value || IslandBus.locked.value ||
            IslandBus.powerSave.value || s.reduceEffects || !s.liquidGlass) {
            backdrop?.hide(); bubbleBackdrop?.hide()
        }
        if (backdropPending) return
        backdropPending = true
        val view = display
        if (view?.isAttachedToWindow == true) view.postInvalidateOnAnimation()
        else handler.post(backdropUpdate)
    }

    private fun updateBackdrop() {
        val s = SettingsRepo.current
        val allowed = backdropAllowed(s.liquidGlass, s.enabled, visible && display?.alpha == 1f,
            IslandBus.screenOn.value, IslandBus.locked.value, IslandBus.powerSave.value, s.reduceEffects, backdropGeometry?.usable == true)
        if (!allowed || Build.VERSION.SDK_INT < 31) {
            backdrop?.hide(); bubbleBackdrop?.hide()
            if (!s.liquidGlass) {
                backdrop?.close(); backdrop = null
                bubbleBackdrop?.close(); bubbleBackdrop = null
            }
            return
        }
        val view = display ?: return
        val geometry = backdropGeometry
        if (geometry != null && backdropTransmits(s, geometry.card) && !body.isEmpty && !geometry.area.isEmpty) {
            val layer = backdrop ?: IslandBackdropBlur(this, view).also { backdrop = it }
            layer.update(android.graphics.Rect(ceil(geometry.area.left).toInt(), ceil(geometry.area.top).toInt(),
                floor(geometry.area.right).toInt(), floor(geometry.area.bottom).toInt()), geometry.bottomRadius, true,
                geometry.topRadius, strengthPercent = s.glassBlurStrength)
        } else backdrop?.hide()
        // The bubble is a pill, not a card: no glass, so no blur layer behind it.
        bubbleBackdrop?.hide()
    }

    private fun isAmbientDisplay(): Boolean {
        val state = runCatching { displays?.getDisplay(Display.DEFAULT_DISPLAY)?.state }.getOrNull()
        return !IslandBus.screenOn.value || state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND || state == Display.STATE_OFF
    }

    private fun applySettings(s: IslandSettings, landscape: Boolean, keyguard: Boolean, rotation: Int) {
        IslandType.apply(this, s)
        // Panel IDs are needed only for the opt-in shade behavior, not for reading messages.
        serviceInfo?.let { info ->
            val wanted = if (s.collapseOnShade) info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                else info.flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS.inv()
            if (wanted != info.flags) {
                info.flags = wanted
                runCatching { serviceInfo = info }
            }
        }
        visible = s.enabled && !(s.hideWhileRecording && IslandBus.chrono.value.values.any { it.screenRecording }) &&
            !(landscape && s.landscape == LandscapeMode.HIDE) && !IslandBus.offSchedule.value &&
            !(keyguard && s.lockScreen == LockScreenMode.HIDE) && !(s.hideOnAod && isAmbientDisplay()) &&
            IslandBus.foregroundPackage.value !in s.exemptApps
        // "Near camera": in landscape the island is rotated toward the edge with the camera; otherwise as usual, at the top
        val turn = if (landscape && s.landscape == LandscapeMode.CAMERA) rotation else 0
        IslandBus.contentRotation.value = turn
        display?.visibility = if (visible) View.VISIBLE else View.GONE
        // The island is hidden: turned off, outside the schedule, on the lock screen, or while rotated (say, watching a video):
        // Compose does not draw in its window and does not wake up for animations and the equalizer until the island is shown again.
        // We do not do this with the screen off: when the screen turns on, the island immediately shows what is current now,
        // and the decorations wait for the screen anyway
        owner.setActive(visible)
        refreshTilt()
        if (!visible) Controls.collapse()
        if (placeDisplayWindow(s, turn)) display?.let { v -> runCatching { wm?.updateViewLayout(v, displayParams) } }
        updateTouchWindow()
    }

    /**
     * Where the island window is: normally a full-width strip at the top, as tall as the expanded island; "Near camera"
     * in landscape is the same strip along the edge with the camera. Returns true if something changed.
     */
    private fun placeDisplayWindow(s: IslandSettings, rotation: Int): Boolean {
        val band = dp(windowBandDp(s))
        val offset = dp(s.topOffset - ISLAND_PAD_DP)
        val p = displayParams
        val old = listOf(p.width, p.height, p.x, p.y, p.gravity)
        if (rotation == 0) {
            p.width = WindowManager.LayoutParams.MATCH_PARENT
            p.height = band
            p.gravity = Gravity.TOP or Gravity.START
            p.x = 0
            p.y = offset
        } else {
            p.width = band
            p.height = WindowManager.LayoutParams.MATCH_PARENT
            p.gravity = (if (rotation < 0) Gravity.LEFT else Gravity.RIGHT) or Gravity.TOP
            p.x = offset
            p.y = 0
        }
        return old != listOf(p.width, p.height, p.x, p.y, p.gravity)
    }

    /** Screen rotation: camera on the left (90°) rotates the island by -90°, camera on the right (270°) by 90°. */
    private fun readRotation() {
        val rotation = runCatching { displays?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation }.getOrNull() ?: Surface.ROTATION_0
        screenRotation = rotation
        IslandBus.displayRotation.value = when (rotation) {
            Surface.ROTATION_90 -> -90
            Surface.ROTATION_270 -> 90
            else -> 0
        }
    }

    /**
     * Screenshot without the island. The system screenshot by buttons is beyond the island's control: Android does not allow hiding
     * an overlay from it. So the island takes the shot itself: the window becomes transparent, a frame or two later
     * Android captures the screen without it, then the island returns.
     */
    private fun screenshotWithoutIsland() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        Controls.collapse()
        backdrop?.hide()
        bubbleBackdrop?.hide()
        val view = display ?: return
        view.alpha = 0f
        handler.removeCallbacks(shoot)
        handler.removeCallbacks(showAgain)
        handler.postDelayed(shoot, 250)
        handler.postDelayed(showAgain, 1_600)
    }

    private fun onBoundsChanged() {
        scheduleBackdrop()
        // During animation the bounds change every frame, and repositioning the window is a system call in the middle of a frame,
        // which can make the island jerk, and on ColorOS vanish for a third of a second. So the touch window
        // is repositioned once, when the island is at rest (120 ms without changes), and if it keeps moving for a long time
        // (the Easter egg), no more than once every three seconds
        val now = SystemClock.uptimeMillis()
        if (touchSince == 0L) touchSince = now
        handler.removeCallbacks(touchUpdate)
        handler.postAtTime(touchUpdate, minOf(now + TOUCH_QUIET_MS, touchSince + TOUCH_MAX_WAIT_MS))
        growTouchWindowIfOutside()
    }

    /**
     * The touch window is fitted with a delay, and while the island grows (expanding, track change), its edge
     * ends up outside the window: a button tap goes to the app under the island, and ACTION_OUTSIDE collapses
     * the card. So if the island goes outside the window, the window immediately expands to the whole drawing window, and the exact
     * fitting is still done when the island is at rest.
     */
    private fun growTouchWindowIfOutside() {
        val view = touch ?: return
        val shown = listOf(body, bubble, third).filterNot { it.isEmpty }
        if (!visible || shown.isEmpty()) return
        val canvas = display ?: return
        val origin = IntArray(2)
        canvas.getLocationOnScreen(origin)
        val left = origin[0] + floor(shown.minOf { it.left }).toInt()
        val top = origin[1] + floor(shown.minOf { it.top }).toInt()
        val right = origin[0] + ceil(shown.maxOf { it.right }).toInt()
        val bottom = origin[1] + ceil(shown.maxOf { it.bottom }).toInt()
        if ((touchParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0 &&
            left >= touchParams.x && top >= touchParams.y &&
            right <= touchParams.x + touchParams.width && bottom <= touchParams.y + touchParams.height
        ) return
        touchParams.flags = touchParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        touchParams.x = origin[0]
        touchParams.y = origin[1]
        touchParams.width = canvas.width.coerceAtLeast(right - origin[0])
        touchParams.height = canvas.height.coerceAtLeast(bottom - origin[1])
        runCatching { wm?.updateViewLayout(view, touchParams) }
    }

    private fun updateTouchWindow() {
        val view = touch ?: return
        // The island plus whichever pills are showing beside it
        val shown = listOf(body, bubble, third).filterNot { it.isEmpty }
        val area = if (shown.isEmpty()) Rect.Zero else Rect(
            shown.minOf { it.left }, shown.minOf { it.top }, shown.maxOf { it.right }, shown.maxOf { it.bottom },
        )
        val usable = visible && !area.isEmpty
        val origin = IntArray(2)
        display?.getLocationOnScreen(origin)
        val x = if (usable) origin[0] + floor(area.left).toInt() else -10
        val y = if (usable) origin[1] + floor(area.top).toInt() else -10
        val w = if (usable) ceil(area.width).toInt().coerceAtLeast(1) else 1
        val h = if (usable) ceil(area.height).toInt().coerceAtLeast(1) else 1
        val flags = if (usable) touchParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else touchParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (touchParams.x == x && touchParams.y == y && touchParams.width == w && touchParams.height == h &&
            touchParams.flags == flags
        ) return
        // The island moved slightly or shrank but stayed inside the touch window: leave the window alone, since each
        // repositioning is an extra system call and risks flickering on some shells
        val slack = dp(8)
        if (usable && flags == touchParams.flags && x >= touchParams.x && y >= touchParams.y &&
            x + w <= touchParams.x + touchParams.width && y + h <= touchParams.y + touchParams.height &&
            touchParams.width - w <= slack && touchParams.height - h <= slack
        ) return
        touchParams.x = x
        touchParams.y = y
        touchParams.width = w
        touchParams.height = h
        touchParams.flags = flags
        runCatching { wm?.updateViewLayout(view, touchParams) }
    }

    private fun refreshTilt() {
        val s = SettingsRepo.current.forPowerSave(IslandBus.powerSave.value)
        setTiltEnabled(visible && s.liquidGlass && s.enabled && !s.reduceEffects && IslandBus.screenOn.value)
    }

    private fun setTiltEnabled(on: Boolean) {
        if (on == tiltOn) return
        tiltOn = on
        val sm = getSystemService(SensorManager::class.java) ?: return
        if (on) {
            val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            sm.registerListener(tiltListener, sensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            sm.unregisterListener(tiltListener)
            tiltFilter.reset()
            IslandBus.glassTilt.value = GlassTilt.Zero
        }
    }

    private fun setInputFocus(on: Boolean) {
        val view = display ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val flags = if (on) displayParams.flags and flag.inv() else displayParams.flags or flag
        if (flags == displayParams.flags) return
        displayParams.flags = flags
        displayParams.softInputMode = if (on) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        else WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        runCatching { wm?.updateViewLayout(view, displayParams) }
    }

    /**
     * A timer is ringing or an incoming call is active: the screen turns on and stays on. If it was turned off with the
     * power button while ringing, the island does not turn it on again until the next ring.
     */
    private fun onAlert(alert: Boolean, screenOn: Boolean) {
        if (!alert) {
            wakeBlocked = false
        } else if (alerting && lastScreenOn && !screenOn) {
            wakeBlocked = true
        }
        // The "Turn on screen" setting is off: the island does not touch the screen
        val wake = SettingsRepo.current.wakeForAlerts
        if (wake && alert && !alerting && !screenOn) ScreenWaker.wake(this, scope) { alerting && !wakeBlocked }
        alerting = alert
        lastScreenOn = screenOn
        setAlarmScreen(keepOn = wake && alert, turnOn = wake && alert && !wakeBlocked)
    }

    /**
     * The "turn screen on" flag on a window without an Activity is applied by the system on every repositioning if the screen
     * is off, which is how the island wakes the phone by itself. "Keep screen on" keeps it on while ringing.
     */
    @Suppress("DEPRECATION")
    private fun setAlarmScreen(keepOn: Boolean, turnOn: Boolean) {
        val view = display ?: return
        val mask = WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        var flags = displayParams.flags and mask.inv()
        if (keepOn) flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (turnOn) flags = flags or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        if (flags == displayParams.flags) return
        displayParams.flags = flags
        runCatching { wm?.updateViewLayout(view, displayParams) }
    }

    /**
     * Island window height: the expanded island, a notification card, and the history list fit with room to spare,
     * and with large text from "Appearance" it is even taller.
     */
    private fun windowBandDp(s: IslandSettings): Int = maxOf(
        (WINDOW_BAND_DP * maxOf(1f, s.contentScale / 100f)).toInt(),
        s.expandedMaxHeight.coerceIn(180, 480) + 2 * ISLAND_PAD_DP,
    )

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        scrollToTop?.onAccessibilityEvent(event)
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> onWindowsChanged()
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowState(event)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                clockReader?.onContentChanged(event)
                payReader?.onContentChanged(event)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> clockReader?.onClicked(event)
        }
    }

    /**
     * While Clock or the wallet is open, the service needs its screen changes and clicks, so the timer in the island
     * appears right away, and "Paid" as soon as the wallet shows the amount. The rest of the time these events
     * are off: there are many of them and they do not concern the island.
     */
    private fun setScreenEvents(on: Boolean) {
        if (on == screenEvents) return
        screenEvents = on
        val info = serviceInfo ?: return
        val extra = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_VIEW_CLICKED
        info.eventTypes = if (on) info.eventTypes or extra else info.eventTypes and extra.inv()
        runCatching { serviceInfo = info }
    }

    /**
     * Best effort from the accessible SystemUI tree. Never mistake the lock screen, keyboard or a
     * generic full-screen system window for the shade; OEMs without exposed panel IDs stay unchanged.
     */
    private fun updateShadeState() {
        if (keyguard?.isKeyguardLocked == true) {
            IslandBus.shadeOpen.value = false
            return
        }
        val open = runCatching {
            windows.any { window ->
                if (window.type != AccessibilityWindowInfo.TYPE_SYSTEM || !window.isActive) return@any false
                val root = window.root ?: return@any false
                if (root.packageName?.toString() != "com.android.systemui") return@any false
                val nodes = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
                nodes.add(root)
                var checked = 0
                var found = false
                while (nodes.isNotEmpty() && checked++ < 128) {
                    val node = nodes.removeFirst()
                    if (node.isVisibleToUser && ShadeDetection.isPanel(node.viewIdResourceName.orEmpty(), node.className?.toString().orEmpty())) {
                        found = true
                        break
                    }
                    for (i in 0 until node.childCount) node.getChild(i)?.let(nodes::add)
                }
                found
            }
        }.getOrDefault(false)
        if (open != IslandBus.shadeOpen.value) {
            IslandBus.shadeOpen.value = open
            if (open && SettingsRepo.current.collapseOnShade) {
                IslandBus.reply.value = null
                Controls.collapse()
            }
        }
    }

    /** While reply input is open: the keyboard was hidden ("Back"), so the input closes, as in the shade. */
    private fun onWindowsChanged() {
        checkUnlock()
        updateShadeState()
        if (IslandBus.reply.value == null) return
        if (keyboardShown()) {
            keyboardSeen = true
        } else if (keyboardSeen && SystemClock.elapsedRealtime() - replySince > 1000) {
            // Ignore the first second: another app's keyboard may hide before ours
            IslandBus.reply.value = null
        }
    }

    /**
     * The lock screen went away: unlock is shown immediately, without waiting for USER_PRESENT. And conversely:
     * if the lock engaged later than the screen went off (lock after several seconds), the lock icon appears then too.
     */
    private fun checkUnlock() {
        if (!keyguardShown && IslandBus.screenOn.value && !IslandBus.keyguard.value && keyguard?.isKeyguardLocked == true) {
            keyguardShown = true
            IslandBus.keyguard.value = true
            IslandBus.locked.value = keyguard?.isDeviceLocked == true
            return
        }
        if (!keyguardShown || !IslandBus.screenOn.value) return
        if (keyguard?.isKeyguardLocked == false) {
            keyguardShown = false
            events?.onUnlocked()
        }
    }

    private fun onWindowState(event: AccessibilityEvent) {
        updateShadeState()
        val pkg = event.packageName?.toString() ?: return
        // The shade, the keyboard, and the island itself do not change the "open app"
        val cls = event.className?.toString().orEmpty()
        if (pkg == "com.android.systemui" || isKeyboard(pkg, cls)) return
        // The payment window from Google services (phone brought to a terminal) is not an "open app"
        if (pkg == PayMonitor.GMS && "tapandpay" in cls.lowercase()) {
            PayMonitor.onTapAndPay(this)
            payReader?.onTapAndPay()
            setScreenEvents(true)
            return
        }
        if (pkg == packageName && !cls.endsWith("Activity")) return
        // A bank or wallet was opened from another app: show the payment hint; left it: the hint goes away
        PayMonitor.onForeground(pkg)
        if (IslandBus.foregroundPackage.value != pkg && pkg != packageName) PayMonitor.onAppOpened(this, pkg)
        // A navigator is open: read distance and maneuver from its screen; left it: stop reading
        navReader?.onForeground(pkg)
        // Clock is open: read the timer and stopwatch from the screen (if their notifications are off)
        clockReader?.onForeground(pkg)
        // A wallet or bank is open: watch for when it shows the payment amount
        payReader?.onForeground(pkg, cls)
        setScreenEvents(clockReader?.active == true || payReader?.active == true)
        IslandBus.foregroundPackage.value = pkg
        com.akira.island.island.RecentApps.push(pkg)
        // Games and video: the island switches itself off in front of the chosen apps, and back on when they leave
        val exempt = pkg in SettingsRepo.current.exemptApps
        if (exempt != inExemptApp) {
            inExemptApp = exempt
            applyCurrentSettings()
        }
    }

    /**
     * Keyboard window: for all keyboards this is android.inputmethodservice.SoftInputWindow, and just in
     * case we also check against the list of enabled keyboards (refreshed once a minute).
     */
    private fun isKeyboard(pkg: String, cls: String): Boolean {
        if (cls.contains("inputmethod", ignoreCase = true)) return true
        val now = SystemClock.elapsedRealtime()
        val snapshot = imeSnapshot
        if (snapshot.checkedAt == 0L || now - snapshot.checkedAt > 60_000) {
            val packages = runCatching {
                getSystemService(InputMethodManager::class.java)?.enabledInputMethodList.orEmpty().map { it.packageName }.toSet()
            }.getOrDefault(emptySet())
            // Accessibility actions also consult this cache from their worker thread.
            imeSnapshot = ImeSnapshot(now, packages)
        }
        return pkg in imeSnapshot.packages
    }

    override fun onInterrupt() { scrollToTop?.cancel() }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        scrollToTop?.cancel()
        IslandBus.landscape.value = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    override fun onDestroy() {
        faceUnlock?.stop(); faceUnlock = null
        networkRateMonitor?.stop(); networkRateMonitor = null
        backdropClosed = true
        backdropSampler = null
        display?.removeCallbacks(backdropUpdate)
        backdropPending = false
        handler.removeCallbacks(backdropUpdate)
        backdrop?.close(); backdrop = null
        bubbleBackdrop?.close(); bubbleBackdrop = null
        // The island was turned off in accessibility: no need to keep its process any longer
        stopService(Intent(this, KeepAliveService::class.java))
        handler.removeCallbacks(shoot)
        handler.removeCallbacks(showAgain)
        handler.removeCallbacks(touchUpdate)
        runCatching { displays?.unregisterDisplayListener(displayListener) }
        IslandBus.egg.value = false
        setTiltEnabled(false)
        IslandBus.serviceRunning.value = false
        IslandBus.shadeOpen.value = false
        IslandTile.refresh(this)
        events?.stop()
        runCatching { privacy?.close() }; privacy = null
        torch?.stop()
        alarm?.stop()
        calls?.stop()
        navReader?.stop()
        clockReader?.stop()
        payReader?.stop()
        scrollToTop?.cancel()
        ClockControl.reader = null
        alarmMonitor?.stop()
        scope.cancel()
        touch?.let { runCatching { wm?.removeView(it) } }
        display?.let { runCatching { wm?.removeView(it) } }
        touch = null
        display = null
        owner.destroy()
        super.onDestroy()
    }
}

private fun WindowManager.LayoutParams.cutoutAlways() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
}

/** Invisible window over the island: forwards touches to the drawing window. */
@SuppressLint("ViewConstructor")
class TouchRoot(
    context: Context,
    private val target: () -> View?,
    private val onOutside: () -> Unit,
) : View(context) {
    private val origin = IntArray(2)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutside()
            return true
        }
        val view = target() ?: return false
        view.getLocationOnScreen(origin)
        val copy = MotionEvent.obtain(ev)
        // rawX - x is the top-left corner of the touch window on screen
        copy.offsetLocation(ev.rawX - ev.x - origin[0], ev.rawY - ev.y - origin[1])
        view.dispatchTouchEvent(copy)
        copy.recycle()
        return true
    }
}

/** Island window height without the large-text adjustment, dp */
private const val WINDOW_BAND_DP = 480
private const val TOUCH_QUIET_MS = 120L
private const val TOUCH_MAX_WAIT_MS = 3000L

/** Bounds rounded outward to whole pixels */
private fun Rect.snapped() = Rect(floor(left), floor(top), ceil(right), ceil(bottom))

/** What decides where the island is and whether it is visible: settings, rotation, lock screen. */
private data class Placement(val s: IslandSettings, val landscape: Boolean, val keyguard: Boolean, val rotation: Int)

/** Immutable cache shared by the service callbacks and the scroll action worker. */
private data class ImeSnapshot(val checkedAt: Long, val packages: Set<String>)
