package com.akira.island.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.palette.graphics.Palette
import androidx.core.graphics.drawable.toBitmap
import com.akira.island.core.AppMessage
import com.akira.island.core.IslandBus
import kotlinx.coroutines.launch
import com.akira.island.core.MsgAction
import com.akira.island.core.Notice
import com.akira.island.core.NoticeStyle
import com.akira.island.core.Tints
import com.akira.island.R
import com.akira.island.core.tr
import com.akira.island.core.trf
import com.akira.island.data.AccentMode
import com.akira.island.data.AppRule
import com.akira.island.data.SettingsRepo
import com.akira.island.island.Ios

/**
 * App notifications in the island, like banners on iPhone: messages from Telegram,
 * WhatsApp, email, etc. Only those that the system would show as popups anyway are shown.
 */
object Messages {
    private val deduplicator = MessageDeduplicator()

    private val skipCategories = setOf(
        Notification.CATEGORY_TRANSPORT,
        Notification.CATEGORY_CALL,
        Notification.CATEGORY_PROGRESS,
        Notification.CATEGORY_SERVICE,
        Notification.CATEGORY_SYSTEM,
        Notification.CATEGORY_STATUS,
        // Wire category names are plain strings and can also be supplied by apps on Android 8.
        "navigation",
        "stopwatch",
        "location_sharing",
    )

    private val cooldown = NotificationCooldown()

    fun maybeShow(service: NotificationListenerService, sbn: StatusBarNotification, appName: String) {
        val s = SettingsRepo.current
        if (!s.cardMessages || !s.enabled) return
        val n = sbn.notification ?: return
        val pkg = sbn.packageName
        // Ongoing, service, and group summary notifications are not messages: do not show them and do not log them to diagnostics
        if (sbn.isOngoing) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return
        NotifApps.seen(service, pkg, appName)
        val rule = s.msgAppRules[pkg]
        if (rule != AppRule.ALWAYS && n.category in skipCategories) return NotifDebug.add(pkg, trf("Служебное: {0}", "Service notification: {0}", n.category))
        if (rule == AppRule.NEVER) return NotifDebug.add(pkg, tr("Выключено для приложения", "Turned off for this app"))
        // While the app itself is open, it shows the message anyway, unless asked to show it then too
        if (!s.msgInApp && IslandBus.foregroundPackage.value == pkg) return NotifDebug.add(pkg, tr("Приложение открыто", "The app is open"))

        // Silent and collapsed ones are not shown, like the system, unless asked ("Silent notifications" or
        // "Always" for the app). Ones muted by Do Not Disturb are never shown
        val ranking = NotificationListenerService.Ranking()
        val ranked = runCatching { service.currentRanking?.getRanking(sbn.key, ranking) == true }.getOrDefault(false)
        if (ranked) {
            if (!allowsMessageImportance(ranking.importance, s.msgQuiet, rule == AppRule.ALWAYS)) {
                return NotifDebug.add(pkg, tr("Тихое уведомление — включите «Тихие уведомления» или «Всегда» для приложения",
                    "Silent notification — turn on Silent notifications or Always for this app"))
            }
            if (!ranking.matchesInterruptionFilter()) return NotifDebug.add(pkg, tr("«Не беспокоить»", "Do Not Disturb"))
        }

        // Lock screen: as in the shade, notifications are hidden there, "secret" ones are not shown,
        // and for the rest, without permission to show content, only the app name is visible
        val locked = IslandBus.locked.value
        if (locked && !Lockscreen.showsNotifications(service)) {
            return NotifDebug.add(pkg, tr("На экране блокировки уведомления выключены", "Notifications are off on the lock screen"))
        }
        val visibility = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ranked) {
            val override = ranking.lockscreenVisibilityOverride
            if (override != NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE) override else n.visibility
        } else {
            n.visibility
        }
        val secret = visibility == Notification.VISIBILITY_SECRET
        if (locked && secret) return NotifDebug.add(pkg, tr("Скрыто на экране блокировки", "Hidden on the lock screen"))
        val sensitive = Lockscreen.isSecure(service) &&
            (secret || visibility == Notification.VISIBILITY_PRIVATE && !Lockscreen.showsPrivate(service))

        val ex = n.extras ?: return
        // The messenger appended your own reply to the notification: this is not a new message
        if (ownLastMessage(ex)) return NotifDebug.add(pkg, tr("Ваше же сообщение", "Your own message"))
        val title = (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: ex.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()
        val text = lastMessage(ex)
            ?: (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim()
            ?: ""
        if (title.isEmpty() && text.isEmpty()) return NotifDebug.add(pkg, tr("Без текста", "No text"))

        @Suppress("DEPRECATION")
        val messages = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        val last = messages?.lastOrNull() as? Bundle
        @Suppress("DEPRECATION")
        val senderIdentity = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val person = runCatching { last?.getParcelable<Person>("sender_person") }.getOrNull()
            person?.key ?: person?.name?.toString()
        } else null
        val identity = MessageIdentity(
            title, text,
            senderIdentity ?: last?.getCharSequence("sender")?.toString().orEmpty(),
            last?.getLong("time", 0L)?.takeIf { it > 0 } ?: n.`when`.takeIf { it > 0 } ?: sbn.postTime,
            messages?.size ?: n.number,
        )
        if (!deduplicator.shouldShow(sbn.key, pkg, identity, s.msgDedupe, SystemClock.elapsedRealtime())) {
            return NotifDebug.add(pkg, tr("Повтор", "Repeat"))
        }
        // A burst from one app: only the first banner per cooldown (stores, file managers and cloud apps are never held back)
        if (!TransferClassifier.isTransferApp(pkg) && !cooldown.allows(pkg, SystemClock.elapsedRealtime(), s.msgCooldownSec * 1000L)) {
            return NotifDebug.add(pkg, tr("Пауза между уведомлениями", "Notification cooldown"))
        }

        val avatar = runCatching {
            n.getLargeIcon()?.loadDrawable(service)?.toBitmap(128, 128)?.asImageBitmap()
        }.getOrNull()
        val actions = if (s.msgActions) buttons(n) else emptyList()
        val message = AppMessage(
            key = sbn.key,
            pkg = sbn.packageName,
            appName = appName,
            text = text,
            avatar = avatar,
            appIcon = NotificationParser.icon(service, sbn.packageName),
            intent = n.contentIntent,
            actions = actions,
            // Palette parsing is needed only for the "From avatar" button color
            accent = if (s.accentMode == AccentMode.ADAPTIVE) accentOf(avatar ?: NotificationParser.icon(service, sbn.packageName)) else null,
            history = history(ex),
            sensitive = sensitive,
            picture = if (sensitive) null else picture(service, ex),
        )
        IslandBus.post(
            Notice(
                title = title.ifEmpty { appName },
                icon = Ios.load(service, R.drawable.ios_chatbubble_ellipses),
                tint = Tints.Blue,
                style = NoticeStyle.MESSAGE,
                message = message,
                // Reading text takes longer than a short event, and with buttons even longer: there must be time to tap
                extraMs = if (actions.isEmpty()) 2_000 else 4_500,
            ),
        )
        NotifDebug.add(pkg, tr("Показано", "Shown"))
        // "Saved history": only on this phone, and not what is hidden on the lock screen
        if (s.keepHistory && !sensitive) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                com.akira.island.data.NotificationHistory.add(service, pkg, appName, title.ifEmpty { appName }, text)
            }
        }
        // "Notification history": after the banner, the message waits in the island
        if (s.historyMinutes != 0) Inbox.add(message, title.ifEmpty { appName })
        // For "System banners": which messenger it is and whether it also pops up its own banner over the island
        SystemBanners.onMessage(sbn.packageName, n.channelId, popsUp = !ranked || ranking.importance >= NotificationManager.IMPORTANCE_HIGH)
    }

    /**
     * Buttons from the notification, as in the shade: first "Reply" (with an input field), then one more:
     * "Mark as read" and the like. More than two do not fit in the island.
     */
    private fun buttons(n: Notification): List<MsgAction> {
        val all = n.actions?.mapNotNull { a ->
            val intent = a.actionIntent ?: return@mapNotNull null
            val title = a.title?.toString()?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            val inputs = a.remoteInputs?.filter { it.allowFreeFormInput }.orEmpty()
            MsgAction(title, intent, inputs)
        }.orEmpty()
        val reply = all.firstOrNull { it.isReply }
        val read = all.firstOrNull { a ->
            !a.isReply && listOf("прочит", "read").any { it in a.title.lowercase() }
        }
        val other = all.firstOrNull { !it.isReply }
        return listOfNotNull(reply, read ?: other)
    }

    /** The picture of a BigPictureStyle notification, scaled down: it is only ever shown a few hundred dp wide. */
    @Suppress("DEPRECATION")
    private fun picture(context: Context, ex: Bundle): ImageBitmap? = runCatching {
        val bitmap = ex.getParcelable<android.graphics.Bitmap>(Notification.EXTRA_PICTURE)
            ?: (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ex.getParcelable<android.graphics.drawable.Icon>(Notification.EXTRA_PICTURE_ICON)
                ?.loadDrawable(context)?.toBitmap() else null)
            ?: return@runCatching null
        val width = 720
        val scaled = if (bitmap.width > width) android.graphics.Bitmap.createScaledBitmap(bitmap, width, bitmap.height * width / bitmap.width, true) else bitmap
        scaled.asImageBitmap()
    }.getOrNull()

    /** All messages of the conversation for the list under the card: for groups with the sender's name. */
    private fun history(ex: Bundle): List<String> {
        @Suppress("DEPRECATION")
        val arr: Array<Parcelable> = ex.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
        // Read the MessagingStyle wire extra on older Android too; no newer platform API is invoked.
        val group = ex.getBoolean("android.isGroupConversation", false)
        return arr.mapNotNull { p ->
            val b = p as? Bundle ?: return@mapNotNull null
            val text = b.getCharSequence("text")?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            val sender = b.getCharSequence("sender")?.toString()
            if (group && !sender.isNullOrBlank()) "$sender: $text" else text
        }.takeLast(25)
    }

    /** A bright color of the avatar or app icon: readable on black and suitable for buttons. */
    private fun accentOf(image: ImageBitmap?): Color? {
        image ?: return null
        return runCatching {
            val palette = Palette.from(image.asAndroidBitmap()).maximumColorCount(12).generate()
            val rgb = palette.getVibrantColor(palette.getLightVibrantColor(palette.getDominantColor(0)))
            if (rgb == 0) return null
            val c = Color(rgb)
            // A dark color is not visible on the black capsule: highlight it
            if (c.luminance() < 0.4f) lerp(c, Color.White, 0.45f) else c
        }.getOrNull()
    }

    /** For conversations (MessagingStyle) take the last message with the sender's name in groups. */
    private fun lastMessage(ex: Bundle): String? {
        @Suppress("DEPRECATION")
        val arr: Array<Parcelable>? = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        val last = arr?.lastOrNull() as? Bundle ?: return null
        val text = last.getCharSequence("text")?.toString()?.trim() ?: return null
        // Read the MessagingStyle wire extra on older Android too; no newer platform API is invoked.
        val group = ex.getBoolean("android.isGroupConversation", false)
        val sender = last.getCharSequence("sender")?.toString()
        return if (group && !sender.isNullOrBlank()) "$sender: $text" else text
    }

    /**
     * Own messages are identified positively by the user's MessagingStyle person. Missing sender
     * metadata is also used by third-party incoming notifications, so absence alone is not evidence.
     */
    private fun ownLastMessage(ex: Bundle): Boolean {
        @Suppress("DEPRECATION")
        val arr: Array<Parcelable> = ex.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return false
        val last = arr.lastOrNull() as? Bundle ?: return false
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && sentByMe(last, ex)
    }

    /** The sender of [message] is the same person as the notification's EXTRA_MESSAGING_PERSON. */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun sentByMe(message: Bundle, ex: Bundle): Boolean {
        @Suppress("DEPRECATION")
        val sender = runCatching { message.getParcelable<Person>("sender_person") }.getOrNull()
        @Suppress("DEPRECATION")
        val me = runCatching { ex.getParcelable<Person>(Notification.EXTRA_MESSAGING_PERSON) }.getOrNull() ?: return false
        if (me.key != null && sender?.key != null) return sender.key == me.key
        val name = (sender?.name ?: message.getCharSequence("sender"))?.toString()
        return !name.isNullOrBlank() && name == me.name?.toString()
    }

    fun forget(key: String) {
        deduplicator.forget(key)
    }

    /**
     * The notification was removed (read in the app or swiped away in the shade). If its banner is held longer than usual
     * ("Notification display" longer than auto or "until you swipe"), it goes away too.
     */
    fun onRemoved(key: String) {
        IslandBus.removeMessage(key)
    }
}

/** Lock screen settings, as the shade understands them. */
private object Lockscreen {
    /** Whether a PIN, pattern, or password is set: without them the system does not hide the content. */
    fun isSecure(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** "Notifications on the lock screen" is not turned off. */
    fun showsNotifications(context: Context): Boolean = secureInt(context, "lock_screen_show_notifications", 1) != 0

    /** Showing notification content is allowed; if the setting cannot be read, assume it is not. */
    fun showsPrivate(context: Context): Boolean = secureInt(context, "lock_screen_allow_private_notifications", 0) != 0

    /** The setting was never changed: it is on in the system; if it cannot be read, [unreadable]. */
    private fun secureInt(context: Context, name: String, unreadable: Int): Int = try {
        Settings.Secure.getInt(context.contentResolver, name)
    } catch (_: Settings.SettingNotFoundException) {
        1
    } catch (_: Exception) {
        unreadable
    }
}
