package com.akira.island.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import android.provider.Settings
import com.akira.island.core.IslandBus
import com.akira.island.R
import com.akira.island.core.tr
import com.akira.island.data.SettingsRepo
import com.akira.island.data.LauncherVisibility
import com.akira.island.ui.Perms

/**
 * "Keep island loaded": a foreground service with a quiet notification. Android does not kill a process with such a service
 * together with the app when it is swiped from recents, so the island stays on screen.
 * Keeps the foreground notification and checks the listener connection only while the screen is on.
 */
class KeepAliveService : Service() {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    private var maintenance: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SettingsRepo.init(this)
        // A service started with startForegroundService must go foreground even if it was already turned off:
        // otherwise Android crashes the app. So first go foreground, then stop if needed
        val shown = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ID, notification())
            }
        }.isSuccess
        if (!shown || !SettingsRepo.current.keepAlive || !SettingsRepo.current.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        maintenance?.cancel()
        maintenance = scope.launch {
            IslandBus.screenOn.collectLatest { screenOn ->
                if (screenOn) while (SettingsRepo.current.keepAlive && SettingsRepo.current.enabled) {
                    if (Perms.notifications(this@KeepAliveService) && !NotifListener.isConnected) {
                        NotifListener.requestReconnect(this@KeepAliveService)
                    }
                    delay(60_000)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, tr("Работа острова", "Island running"), NotificationManager.IMPORTANCE_MIN).apply {
                description = tr("Не даёт Android закрыть остров, когда его смахивают из недавних",
                    "Keeps Android from closing the island when it's swiped away from recent apps")
                setShowBadge(false)
            },
        )
        // Tapping opens the island settings
        val open = PendingIntent.getActivity(this, 0, LauncherVisibility.settingsIntent(this), PendingIntent.FLAG_IMMUTABLE)
        val restore = PendingIntent.getActivity(this, 1,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tab_island)
            .setContentTitle(tr("Остров работает", "The island is running"))
            .setContentText(tr("Уведомление не даёт системе закрыть остров", "This notification keeps the system from closing the island"))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null,
                tr("Восстановить остров", "Restore island"), restore).build())
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val ID = 7
        private const val CHANNEL = "keep_alive"

        /**
         * Start or stop according to the setting. The service is needed only while the island itself (accessibility) is on.
         * From the background, Android 12+ may not start it; then it starts when the app is opened.
         */
        fun sync(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (SettingsRepo.current.keepAlive && SettingsRepo.current.enabled && Perms.accessibility(context)) {
                runCatching { context.startForegroundService(intent) }
            } else {
                context.stopService(intent)
            }
        }
    }
}
