package com.akira.island.service

import android.content.Context
import com.akira.island.core.AppMessage
import com.akira.island.core.IslandBus
import com.akira.island.core.InboxItem
import com.akira.island.data.SettingsRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Why not shown": the latest notifications the island parsed as messages, and what it decided.
 * Only the app and the reason, no names or texts; kept in memory only, empty after a restart.
 */
object NotifDebug {
    val log = MutableStateFlow<List<String>>(emptyList())

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    fun add(pkg: String, result: String) {
        val label = NotifApps.label(pkg)
        log.value = (listOf("${clock.format(Date())} · $label\n$result") + log.value).take(30)
    }
}

/**
 * Apps that have sent notifications, for the "Apps" list in the notification settings.
 * Android doesn't give the phone's app list without a special permission, so we remember the ones that posted.
 */
object NotifApps {
    private const val PREFS = "island_apps"
    private const val KEY = "apps"
    private const val LIMIT = 80

    /** Package → name, new ones at the end */
    val apps = MutableStateFlow<Map<String, String>>(emptyMap())
    private var loaded = false
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        runCatching { json.decodeFromString(serializer, raw) }.onSuccess { apps.value = it }
    }

    fun label(pkg: String): String = apps.value[pkg] ?: pkg

    fun seen(context: Context, pkg: String, label: String) {
        load(context)
        if (apps.value[pkg] == label) return
        val next = LinkedHashMap(apps.value).apply {
            remove(pkg)
            put(pkg, label)
            while (size > LIMIT) remove(keys.first())
        }
        apps.value = next
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, json.encodeToString(serializer, next)).apply()
    }
}

/**
 * "Notification history": shown messages wait in the island: app icons in the capsule, a list when expanded.
 * They go away when read in the app itself (notification removed), opened from the island, cleared,
 * or when the time from the settings passes. Kept in memory only.
 */
object Inbox {
    private const val LIMIT = 20

    fun add(m: AppMessage, title: String) {
        val item = InboxItem(
            key = m.key, pkg = m.pkg, appName = m.appName, title = title, text = m.text, avatar = m.avatar,
            appIcon = m.appIcon, intent = m.intent, time = System.currentTimeMillis(), sensitive = m.sensitive,
        )
        IslandBus.inbox.value = (IslandBus.inbox.value.filter { it.key != m.key } + item).takeLast(LIMIT)
    }

    fun remove(key: String) {
        if (IslandBus.inbox.value.any { it.key == key }) IslandBus.inbox.value = IslandBus.inbox.value.filter { it.key != key }
    }

    fun clear() {
        IslandBus.inbox.value = emptyList()
    }

    /** How much longer to wait until the oldest one expires; null means nothing to wait for (or "until you read it"). */
    fun nextExpiry(now: Long): Long? {
        val minutes = SettingsRepo.current.historyMinutes
        if (minutes <= 0) return null
        val oldest = IslandBus.inbox.value.minOfOrNull { it.time } ?: return null
        return (oldest + minutes * 60_000L - now).coerceAtLeast(0)
    }

    /** Remove the expired ones; if history is off, remove everything. */
    fun prune(now: Long) {
        val minutes = SettingsRepo.current.historyMinutes
        val list = IslandBus.inbox.value
        if (list.isEmpty()) return
        val keep = when {
            minutes == 0 -> emptyList()
            minutes < 0 -> list
            else -> list.filter { now - it.time < minutes * 60_000L }
        }
        if (keep.size != list.size) IslandBus.inbox.value = keep
    }
}
