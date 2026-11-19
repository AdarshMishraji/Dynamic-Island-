package com.akira.island.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import com.akira.island.core.tr
import com.akira.island.data.SettingsRepo
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * System pop-up banners that duplicate the island: incoming call, timer and alarm ring,
 * battery. The app can't remove another app's banner; it is turned off in the notification settings,
 * and the island shows the event as before. Here we remember which channel sent such a banner,
 * so we can open exactly that one in the settings.
 */
object SystemBanners {
    /** The notification channel that sent the banner, and whether it is a call */
    data class Seen(val channel: String, val call: Boolean)

    val seen = MutableStateFlow<Map<String, Seen>>(emptyMap())

    /** Whether a messenger shows its own banner, based on the last message (channel importance is "high") */
    val popUp = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    const val SYSTEM_UI = "com.android.systemui"
    const val ANDROID = "android"

    fun onPosted(sbn: StatusBarNotification, incomingCall: Boolean) {
        val n = sbn.notification ?: return
        if (!incomingCall && n.category != Notification.CATEGORY_ALARM) return
        val channel = n.channelId ?: return
        val item = Seen(channel, incomingCall)
        if (seen.value[sbn.packageName] != item) seen.value = seen.value + (sbn.packageName to item)
    }

    /**
     * A messenger message was shown in the island. Its channel is remembered in the settings, so after a restart
     * the list in "System banners" isn't empty; it is written only when the channel is new.
     */
    fun onMessage(pkg: String, channel: String?, popsUp: Boolean) {
        if (popUp.value[pkg] != popsUp) popUp.value = popUp.value + (pkg to popsUp)
        if (channel != null && SettingsRepo.current.msgChannels[pkg] != channel) {
            SettingsRepo.update { copy(msgChannels = msgChannels + (pkg to channel)) }
        }
    }

    /** The default dialer and apps that have already sent incoming calls. */
    fun callApps(context: Context, seen: Map<String, Seen>): List<String> {
        val dialer = runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()
        return (listOfNotNull(dialer) + seen.filterValues { it.call }.keys).distinct()
    }

    /** "Clock" apps: those that can set a timer and those that have already sent a ring signal. */
    fun clockApps(context: Context, seen: Map<String, Seen>): List<String> {
        val timers = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(Intent(AlarmClock.ACTION_SET_TIMER), 0).map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        return (timers + seen.filterValues { !it.call }.keys).distinct().filter { it != SYSTEM_UI && it != ANDROID && it != context.packageName }
    }

    fun label(context: Context, pkg: String): String = when (pkg) {
        SYSTEM_UI -> tr("Системный интерфейс", "System UI")
        ANDROID -> tr("Система Android", "Android System")
        else -> NotificationParser.appLabel(context, pkg)
    }

    /** Notification settings of the app [pkg], opening the needed channel directly if it is already known. */
    fun openSettings(context: Context, pkg: String) {
        val channel = seen.value[pkg]?.channel ?: SettingsRepo.current.msgChannels[pkg]
        val intent = if (channel != null) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, channel)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        }
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
