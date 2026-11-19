package com.akira.island.core

import com.akira.island.service.CallMonitor
import com.akira.island.data.LauncherVisibility

import android.app.ActivityOptions
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock

/** Commands the capsule sends to the system: player, timer, flashlight, volume. */
object Controls {
    @Volatile
    var mediaController: MediaController? = null

    // Filled in by IslandService while it is running
    var torchOn: () -> Unit = {}
    var torchOff: () -> Unit = {}
    var torchLevel: (Int) -> Unit = {}

    fun expand(id: String) {
        IslandBus.focus.value = id
        IslandBus.expanded.value = true
    }

    fun collapse() {
        IslandBus.expanded.value = false
        IslandBus.focus.value = null
    }

    // Player

    /** The player's own button, "Like" or "Dislike". */
    fun playerAction(action: String) {
        mediaController?.transportControls?.sendCustomAction(action, null)
    }

    fun playPause() {
        val c = mediaController ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    fun next() {
        mediaController?.transportControls?.skipToNext()
    }

    fun prev() {
        mediaController?.transportControls?.skipToPrevious()
    }

    fun seekTo(ms: Long) {
        mediaController?.transportControls?.seekTo(ms)
    }

    // Timer

    fun startTimer(minutes: Int) {
        val total = minutes.coerceIn(1, 99) * 60_000L
        IslandBus.timer.value = TimerState(total, SystemClock.elapsedRealtime() + total)
    }

    fun startTimerSeconds(seconds: Int) {
        val total = seconds * 1000L
        IslandBus.timer.value = TimerState(total, SystemClock.elapsedRealtime() + total)
    }

    fun pauseTimer() {
        val t = IslandBus.timer.value ?: return
        if (t.pausedLeft == null && !t.ringing) {
            IslandBus.timer.value = t.copy(pausedLeft = t.left(SystemClock.elapsedRealtime()))
        }
    }

    fun resumeTimer() {
        val t = IslandBus.timer.value ?: return
        val left = t.pausedLeft ?: return
        if (!t.ringing) IslandBus.timer.value = t.copy(endAt = SystemClock.elapsedRealtime() + left, pausedLeft = null)
    }

    fun cancelTimer() {
        IslandBus.timer.value = null
        if (IslandBus.focus.value == "timer") collapse()
    }

    // Stopwatch

    fun startStopwatch() {
        IslandBus.stopwatch.value = StopwatchState(SystemClock.elapsedRealtime(), 0)
    }

    fun toggleStopwatch() {
        val w = IslandBus.stopwatch.value ?: return
        val now = SystemClock.elapsedRealtime()
        IslandBus.stopwatch.value =
            if (w.running) StopwatchState(null, w.elapsed(now), w.laps) else StopwatchState(now, w.accumulated, w.laps)
    }

    /** Marks a lap; only a running stopwatch has laps. */
    fun lapStopwatch() {
        val w = IslandBus.stopwatch.value?.takeIf { it.running } ?: return
        IslandBus.stopwatch.value = w.copy(laps = (w.laps + w.elapsed(SystemClock.elapsedRealtime())).takeLast(50))
    }

    fun stopStopwatch() {
        IslandBus.stopwatch.value = null
        if (IslandBus.focus.value == "stopwatch") collapse()
    }

    // Other

    fun openApp(context: Context, pkg: String) {
        val intent = if (pkg == context.packageName) LauncherVisibility.settingsIntent(context)
            else context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { context.startActivity(intent) }
        collapse()
    }

    /** Press a button from the notification (reply, decline, open). */
    fun send(context: Context, intent: PendingIntent?): Boolean {
        intent ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                intent.send(context, 0, null, null, null, null, options.toBundle())
            } else {
                intent.send()
            }
        }.isSuccess
    }

    /** Send a reply via the notification's "Reply" button, the same way the shade does. */
    fun sendReply(context: Context, action: MsgAction, text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty() || action.inputs.isEmpty()) return false
        return runCatching {
            val inputs = action.inputs.toTypedArray()
            val results = android.os.Bundle()
            inputs.forEach { results.putCharSequence(it.resultKey, clean) }
            val fill = Intent()
            android.app.RemoteInput.addResultsToIntent(inputs, fill, results)
            action.intent.send(context, 0, fill)
        }.isSuccess
    }

    // Calls: first the button from the dialer's notification, otherwise the system telecom service

    @Suppress("DEPRECATION", "MissingPermission")
    fun answerCall(context: Context, call: CallInfo) {
        if (call.key == TEST_CALL) {
            IslandBus.calls.value = IslandBus.calls.value + (TEST_CALL to call.copy(incoming = false, base = System.currentTimeMillis()))
            return
        }
        if (send(context, call.answer)) return
        // A VoIP notification must never answer an unrelated cellular call.
        if (call.key != CallMonitor.KEY && call.pkg != runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()) {
            if (!send(context, call.open)) openApp(context, call.pkg)
            return
        }
        val ok = runCatching { context.getSystemService(TelecomManager::class.java)!!.acceptRingingCall() }.isSuccess
        if (!ok) showCallScreen(context, call)
    }

    @Suppress("DEPRECATION", "MissingPermission")
    fun endCall(context: Context, call: CallInfo) {
        if (call.key == TEST_CALL) {
            IslandBus.calls.value = IslandBus.calls.value - TEST_CALL
            collapse()
            return
        }
        if (send(context, if (call.incoming) call.decline else call.hangUp)) return
        if (call.key != CallMonitor.KEY && call.pkg != runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()) {
            if (!send(context, call.open)) openApp(context, call.pkg)
            return
        }
        val ok = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            runCatching { context.getSystemService(TelecomManager::class.java)!!.endCall() }.getOrDefault(false)
        if (!ok) showCallScreen(context, call)
    }

    /**
     * Answering or hanging up from the island failed, most likely the "Calls" permission wasn't granted. Then we open
     * the phone's call screen: its buttons always work. If that's missing too, the dialer.
     */
    @Suppress("MissingPermission")
    private fun showCallScreen(context: Context, call: CallInfo) {
        val telecom = context.getSystemService(TelecomManager::class.java)
        if (telecom != null && runCatching { telecom.showInCallScreen(false) }.isSuccess) return
        if (send(context, call.open)) return
        val dialer = runCatching { telecom?.defaultDialerPackage }.getOrNull()?.takeIf { it.isNotEmpty() } ?: call.pkg
        if (dialer.isNotEmpty()) openApp(context, dialer)
    }

    /** Incoming call swiped up: it leaves the island (the call continues, and can be answered in the dialer). */
    fun hideIncomingCall() {
        IslandBus.callHidden.value = true
    }

    /** Screenshot without the island: the island hides for a moment, Android takes the shot (9+) */
    fun screenshot() {
        IslandBus.commands.tryEmit(ServiceCommand.SCREENSHOT)
    }

    /** Only a configured, explicit island gesture requests scrolling in the current app. */
    fun scrollToTop() {
        IslandBus.commands.tryEmit(ServiceCommand.SCROLL_TOP)
    }

    fun lockScreen() {
        IslandBus.commands.tryEmit(ServiceCommand.LOCK)
    }

    /** Turn off the island service, e.g. while using a banking app. It can only be turned back on in settings */
    fun turnOffIsland() {
        IslandBus.commands.tryEmit(ServiceCommand.TURN_OFF)
    }

    /**
     * "Ringer mode" button: ring → vibrate → silent → ring. Android allows "Silent" only
     * with "Do Not Disturb" access; without it the button toggles ring and vibrate and opens that access.
     */
    fun cycleRinger(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val next = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        val ok = runCatching { audio.ringerMode = next }.isSuccess
        if (!ok) {
            runCatching { audio.ringerMode = AudioManager.RINGER_MODE_NORMAL }
            openPolicyAccess(context)
        }
        IslandBus.ringer.value = audio.ringerMode
    }

    /** "Do Not Disturb" button: turn on or off. Without access it opens the screen where it's granted. */
    fun toggleDnd(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.isNotificationPolicyAccessGranted) {
            openPolicyAccess(context)
            return
        }
        val on = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        runCatching {
            nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        }
        IslandBus.dnd.value = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    private fun openPolicyAccess(context: Context) {
        collapse()
        runCatching {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Quick launch: open the panel only if the phone isn't locked; launching apps behind the keyguard does nothing useful. */
    fun openQuickLaunch() {
        if (!IslandBus.locked.value) expand(QUICK_LAUNCH)
    }

    const val QUICK_LAUNCH = "launcher"

    /** First of [actions] the phone can open, then collapse. Android 10+ apps may open these panels but not flip the switches. */
    fun openSystem(context: Context, vararg actions: String): Boolean {
        val opened = actions.any { action ->
            runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        }
        if (opened) collapse()
        return opened
    }

    fun openIntent(context: Context, intent: Intent): Boolean {
        val opened = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (opened) collapse()
        return opened
    }

    fun rotationOn(context: Context): Boolean =
        Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1

    /** Auto-rotate needs "Modify system settings"; without it, open that screen instead. */
    fun toggleRotation(context: Context) {
        if (!Settings.System.canWrite(context)) {
            openIntent(context, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:" + context.packageName)))
            return
        }
        runCatching { Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (rotationOn(context)) 0 else 1) }
    }

    fun openShade() {
        IslandBus.commands.tryEmit(ServiceCommand.SHADE)
        collapse()
    }

    /** Dismisses every notification Android lets us dismiss, and empties the island's own list. */
    fun clearAllNotifications() {
        com.akira.island.service.NotifListener.dismissEverything()
        IslandBus.notice.value = null
        collapse()
    }

    fun openQuickSettings() {
        IslandBus.commands.tryEmit(ServiceCommand.QUICK_SETTINGS)
        collapse()
    }

    fun openCamera(context: Context) {
        runCatching {
            context.startActivity(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    @Suppress("MissingPermission")
    fun openCall(context: Context, call: CallInfo) {
        if (call.key == TEST_CALL) return
        if (send(context, call.open)) return
        val telecom = context.getSystemService(TelecomManager::class.java)
        if (call.key != CallMonitor.KEY && call.pkg != runCatching { telecom?.defaultDialerPackage }.getOrNull()) {
            if (call.pkg.isNotEmpty()) openApp(context, call.pkg)
            return
        }
        if (runCatching { telecom?.showInCallScreen(false) }.isSuccess && telecom != null) return
        if (call.pkg.isNotEmpty()) openApp(context, call.pkg)
    }

    /** Test incoming call from the app: buttons work, and after 20 seconds it disappears by itself. */
    fun showTestCall() {
        val now = System.currentTimeMillis()
        IslandBus.calls.value = IslandBus.calls.value +
            (TEST_CALL to CallInfo(TEST_CALL, "", tr("Анна", "Anna"), null, true, now, now, null, null, null, null))
        IslandBus.scope.launch {
            delay(20_000)
            IslandBus.calls.value = IslandBus.calls.value - TEST_CALL
        }
    }

    const val TEST_CALL = "test-call"

    /** Test navigation: wide banner, then capsule; disappears by itself after 10 seconds, even if you leave the app. */
    fun showTestLive(context: Context) {
        val live = LiveInfo(
            key = TEST_LIVE, pkg = context.packageName, kind = LiveKind.NAV,
            title = tr("300 м", "300 m"), text = tr("Поверните направо на Тверскую улицу", "Turn right onto Main Street"), sub = "",
            icon = com.akira.island.service.NotificationParser.icon(context, context.packageName), art = null,
            postTime = System.currentTimeMillis(), open = null,
            distance = tr("300 м", "300 m"), eta = tr("Прибытие в 18:42 · 12 мин · 4,2 км", "Arrive 18:42 · 12 min · 4.2 km"), turn = Turn.RIGHT,
            expiresAt = System.currentTimeMillis() + TEST_LIVE_MS,
        )
        IslandBus.live.value = IslandBus.live.value + (TEST_LIVE to live)
        IslandBus.post(com.akira.island.service.LiveParser.banner(context, live))
        IslandBus.scope.launch {
            delay(TEST_LIVE_MS)
            IslandBus.live.value = IslandBus.live.value - TEST_LIVE
        }
    }

    private const val TEST_LIVE_MS = 10_000L
    const val TEST_LIVE = "test-live"

    private var testProgressKind = 0
    private var testProgressJob: kotlinx.coroutines.Job? = null

    /** Demo for "Try it": one kind of transfer per tap, filling up in a few seconds. */
    fun showTestProgress(context: Context) {
        val kind = TransferKind.entries[testProgressKind++ % TransferKind.entries.size]
        val name = when (kind) {
            TransferKind.DOWNLOAD -> "Report.pdf"
            TransferKind.UPLOAD -> "Holiday.mp4"
            TransferKind.BACKUP -> tr("Фото", "Photos")
            TransferKind.COPY_MOVE -> tr("Документы", "Documents")
            TransferKind.DELETE -> tr("Старые загрузки", "Old downloads")
            TransferKind.INSTALL -> tr("Остров", "Island")
        }
        val icon = com.akira.island.service.NotificationParser.icon(context, context.packageName)
        testProgressJob?.cancel()
        testProgressJob = IslandBus.scope.launch {
            try {
                for (percent in 0..100 step 4) {
                    IslandBus.progress.value = IslandBus.progress.value + (TEST_PROGRESS to
                        ProgressInfo(TEST_PROGRESS, context.packageName, name, icon, percent, 100, false, System.currentTimeMillis(), kind = kind))
                    delay(250)
                }
            } finally {
                IslandBus.progress.value = IslandBus.progress.value - TEST_PROGRESS
            }
        }
    }

    const val TEST_PROGRESS = "test-progress"

    /** Where audio is output: the system volume panel with a device picker. */
    fun openOutput(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_VOLUME)
        } else {
            Intent(Settings.ACTION_SOUND_SETTINGS)
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        collapse()
    }

}
