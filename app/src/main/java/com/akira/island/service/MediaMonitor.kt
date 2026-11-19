package com.akira.island.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.MediaSession
import android.service.notification.StatusBarNotification
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import androidx.palette.graphics.Palette
import com.akira.island.core.Controls
import com.akira.island.core.IslandBus
import com.akira.island.core.Lyrics
import com.akira.island.core.LyricsStatus
import com.akira.island.core.MediaInfo
import com.akira.island.core.PlayerAction
import com.akira.island.core.Tints
import com.akira.island.data.SettingsRepo
import com.akira.island.lyrics.LyricsRepo
import com.akira.island.lyrics.FoundLyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Watches system media sessions (any player) and picks the one that is playing. */
class MediaMonitor(private val context: Context) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, NotifListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val bound = ArrayList<Pair<MediaController, MediaController.Callback>>()
    private var current: MediaController? = null
    private var started = false
    private val notificationSessions = LinkedHashMap<String, MediaController>()
    private var lyricsRequest: String? = null
    private var lyricsRetriedRequest: String? = null
    /** Fingerprint of the last cover art (see [artPrint]); null means no cover art */
    private var lastArt: Long? = null
    private var artJob: Job? = null
    private var lyricsJob: Job? = null
    private var hideJob: Job? = null
    private var hiddenKey: String? = null
    private var lyricsKey: String? = null
    private var pendingHideKey: String? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        bind((list.orEmpty() + notificationSessions.values).distinctBy { it.sessionToken })
    }

    fun start() {
        if (started) return
        started = true
        try {
            manager?.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            bind(manager?.getActiveSessions(component).orEmpty())
        } catch (_: SecurityException) {
            // Notification access has not been granted yet
        }
        // Notification listeners can reconnect after OEM battery restrictions; refresh on wake too.
        scope.launch {
            IslandBus.screenOn.collect { on ->
                if (on) {
                    rescan()
                    val m = IslandBus.media.value
                    if (m != null && SettingsRepo.current.lyricsEnabled && lyricsJob?.isActive != true && IslandBus.lyrics.value?.synced != true) loadLyrics(m)
                }
            }
        }
        // Lyrics were turned on in settings: load them for the current track
        scope.launch {
            SettingsRepo.state.map { it.lyricsEnabled }.distinctUntilChanged().collect { enabled ->
                val m = IslandBus.media.value
                if (m != null && (enabled && IslandBus.lyrics.value?.key != m.key || !enabled)) loadLyrics(m)
            }
        }
        // Some recorders expose a MediaSession while capturing. Their confirmed recording belongs
        // to Chrono, not to a second music card. Reconsider playback as soon as capture ends.
        scope.launch {
            IslandBus.chrono.map { entries -> entries.values.filter { it.isRecording }.map { it.pkg }.toSet() }
                .distinctUntilChanged().collect { refresh() }
        }
    }

    fun stop() {
        started = false
        notificationSessions.clear()
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        unbindAll()
        scope.cancel()
        Controls.mediaController = null
        IslandBus.media.value = null
        IslandBus.lyrics.value = null
        IslandBus.lyricsStatus.value = null
    }

    /** Some players expose their session in the media notification before the global list changes. */
    fun onNotificationPosted(sbn: StatusBarNotification) {
        @Suppress("DEPRECATION")
        val token = runCatching { sbn.notification.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION) }.getOrNull()
        if (token != null) {
            if (notificationSessions[sbn.key]?.sessionToken == token) {
                // Some players update progress/artwork notifications every second. Reuse the
                // controller and callbacks; a refresh also covers players that miss callbacks.
                if (bound.any { it.first.sessionToken == token }) refresh() else rescan()
                return
            }
            runCatching { MediaController(context, token) }.getOrNull()?.let { notificationSessions[sbn.key] = it }
            rescan()
        }
    }

    fun onNotificationRemoved(key: String) {
        if (notificationSessions.remove(key) != null) rescan()
    }

    private fun rescan() {
        if (!started) return
        val listed = runCatching { manager?.getActiveSessions(component).orEmpty() }.getOrDefault(emptyList())
        bind((listed + notificationSessions.values).distinctBy { it.sessionToken })
    }

    private fun bind(list: List<MediaController>) {
        val previous = bound.associateBy { it.first.sessionToken }.toMutableMap()
        val next = ArrayList<Pair<MediaController, MediaController.Callback>>()
        list.distinctBy { it.sessionToken }.forEach { c ->
            val existing = previous.remove(c.sessionToken)
            if (existing != null) {
                next += existing
                return@forEach
            }
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
                override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
                override fun onSessionDestroyed() {
                    notificationSessions.entries.removeAll { it.value.sessionToken == c.sessionToken }
                    val remaining = bound.map { it.first }.filter { it.sessionToken != c.sessionToken }
                    bind(remaining)
                }
            }
            if (runCatching { c.registerCallback(cb, handler) }.isSuccess) next += c to cb
        }
        previous.values.forEach { (controller, callback) -> runCatching { controller.unregisterCallback(callback) } }
        bound.clear()
        bound.addAll(next)
        refresh()
    }

    private fun unbindAll() {
        bound.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        bound.clear()
    }

    private fun isPlaying(c: MediaController): Boolean {
        val st = c.playbackState?.state
        return st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING
    }

    private fun refresh() {
        val recordingPackages = IslandBus.chrono.value.values.asSequence().filter { it.isRecording }.map { it.pkg }.toSet()
        val controllers = bound.map { it.first }.filterNot { it.packageName in recordingPackages }
        val previous = current?.let { cur -> controllers.firstOrNull { it.sessionToken == cur.sessionToken } }
        val chosen = previous?.takeIf(::isPlaying)
            ?: controllers.firstOrNull { isPlaying(it) }
            ?: previous
            ?: controllers.firstOrNull { it.metadata != null }
        current = chosen
        Controls.mediaController = chosen

        val meta = chosen?.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (chosen == null || meta == null || title.isNullOrBlank()) {
            publish(null)
            return
        }

        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val state = chosen.playbackState
        val playing = isPlaying(chosen)
        val album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0)
        val mediaId = meta.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
        val key = "${chosen.packageName}|$mediaId|$title|$artist|${album.orEmpty()}|$duration"
        val prev = IslandBus.media.value
        val sameTrack = prev?.key == key

        val (like, dislike) = rates(state)
        val info = MediaInfo(
            key = key,
            pkg = chosen.packageName,
            title = title,
            artist = artist,
            album = album,
            art = if (sameTrack) prev?.art else null,
            accent = if (sameTrack && prev != null) prev.accent else Tints.Lavender,
            durationMs = duration,
            positionMs = state?.position ?: 0,
            positionTime = state?.lastPositionUpdateTime?.takeIf { it in 1..SystemClock.elapsedRealtime() } ?: SystemClock.elapsedRealtime(),
            speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            playing = state?.state == PlaybackState.STATE_PLAYING,
            canSeek = (state?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO != 0L,
            like = like,
            dislike = dislike,
        )

        // On pause the card stays a little longer and then goes away, as on iPhone
        if (playing) {
            hideJob?.cancel()
            hiddenKey = null
        } else {
            if (hiddenKey == key) {
                publish(null)
                return
            }
            if (hideJob?.isActive != true || pendingHideKey != key) {
                hideJob?.cancel()
                pendingHideKey = key
                hideJob = scope.launch {
                    delay(PAUSE_KEEP_MS)
                    // Never remove a paused player while the user is reading/using its expanded card.
                    while (IslandBus.expanded.value && IslandBus.focus.value == "media") {
                        combine(IslandBus.expanded, IslandBus.focus) { expanded, focus -> !expanded || focus != "media" }.first { it }
                        delay(PAUSE_KEEP_MS)
                    }
                    hiddenKey = key
                    publish(null)
                }
            }
        }

        publish(info)
        val art = runCatching {
            meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }.getOrNull()
        // On every pause and seek the player hands over the cover art as a new object, though the image is the same, so we do not recompute it.
        // Compare not by size but by the image itself: track covers are usually the same size, and when the track changes
        // the player often first sends the new title with the old cover and only then the new cover
        val print = art?.let(::artPrint)
        if (!sameTrack || print != lastArt) loadArt(key, art, print)
        // Look up the lyrics once per track, not after every time the card hides on pause
        if (key != lyricsKey) loadLyrics(info)
    }

    /**
     * "Like" or "Dislike" among the player's own buttons: Yandex Music, YouTube Music, Spotify put them
     * in the media session next to pause. A track already liked is labeled by the player "Убрать…", "Remove…", "Unlike".
     */
    private fun rates(state: PlaybackState?): Pair<PlayerAction?, PlayerAction?> {
        var like: PlayerAction? = null
        var dislike: PlayerAction? = null
        for (a in state?.customActions.orEmpty()) {
            val t = "${a.action} ${a.name}".lowercase()
            when {
                DislikeWords.containsMatchIn(t) -> if (dislike == null) dislike = PlayerAction(a.action, UndoWords.containsMatchIn(t))
                LikeWords.containsMatchIn(t) -> if (like == null) like = PlayerAction(a.action, UndoWords.containsMatchIn(t))
            }
        }
        return like to dislike
    }

    private fun publish(info: MediaInfo?) {
        IslandBus.media.value = info
        if (info == null) {
            lastArt = null
            artJob?.cancel()
            hideJob?.cancel()
            lyricsJob?.cancel()
            lyricsKey = null
            lyricsRequest = null
            IslandBus.lyrics.value = null
            IslandBus.lyricsStatus.value = null
        }
    }

    private fun loadArt(key: String, art: Bitmap?, print: Long?) {
        lastArt = print
        artJob?.cancel()
        if (art == null) return
        artJob = scope.launch {
            val result = try {
                withContext(Dispatchers.Default) {
                    if (art.isRecycled) return@withContext null
                    // Hardware/recycled artwork and unusual aspect ratios must not crash the service.
                    val readable = if (art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) else art
                    if (readable == null || readable.isRecycled) return@withContext null
                    val small = if (readable.width > 300 || readable.height > 300) {
                        val scale = 300f / maxOf(readable.width, readable.height)
                        readable.scale((readable.width * scale).toInt().coerceAtLeast(1), (readable.height * scale).toInt().coerceAtLeast(1))
                    } else readable
                    val palette = Palette.from(small).maximumColorCount(16).generate()
                    val rgb = palette.getVibrantColor(palette.getLightVibrantColor(palette.getDominantColor(0xFFC6C3F7.toInt())))
                    small.asImageBitmap() to Color(rgb)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val m = IslandBus.media.value
            if (m?.key == key && result != null) IslandBus.media.value = m.copy(art = result.first, accent = result.second)
        }
    }

    private fun loadLyrics(m: MediaInfo) {
        lyricsKey = m.key
        val request = "${m.key}|${m.album}|${m.durationMs}"
        lyricsRequest = request
        lyricsJob?.cancel()
        IslandBus.lyrics.value = null
        if (!SettingsRepo.current.lyricsEnabled) {
            lyricsRetriedRequest = null
            IslandBus.lyricsStatus.value = null
            return
        }
        fun publishLyrics(found: FoundLyrics?): Boolean {
            if (lyricsRequest != request || IslandBus.media.value?.key != m.key || !SettingsRepo.current.lyricsEnabled) return false
            if (found != null && found.lines.isNotEmpty()) {
                IslandBus.lyrics.value = Lyrics(m.key, found.lines, found.synced)
                IslandBus.lyricsStatus.value = m.key to LyricsStatus.FOUND
            } else {
                IslandBus.lyricsStatus.value = m.key to LyricsStatus.MISSING
            }
            return true
        }
        IslandBus.lyricsStatus.value = m.key to LyricsStatus.LOADING
        lyricsJob = scope.launch {
            // The phone is in a pocket and nobody will see the lyrics: search when the screen turns on, and only for the current track
            if (!IslandBus.screenOn.value) IslandBus.screenOn.first { it }
            val found = LyricsRepo.find(m.title, m.artist, m.album, m.durationMs)
            currentCoroutineContext().ensureActive()
            if (!publishLyrics(found) || found?.synced == true || lyricsRetriedRequest == request) return@launch

            // One retry belongs to this same cancellable job. Hidden players wait for a visibility
            // event rather than polling, and the readable plain fallback stays on screen meanwhile.
            suspend fun awaitVisible() {
                combine(IslandBus.screenOn, IslandBus.expanded, IslandBus.focus, IslandBus.media) { on, expanded, focus, media ->
                    on && expanded && focus == "media" && media?.key == m.key
                }.first { it }
            }
            awaitVisible()
            delay(5_000)
            awaitVisible()
            currentCoroutineContext().ensureActive()
            if (lyricsRequest != request || !SettingsRepo.current.lyricsEnabled) return@launch
            lyricsRetriedRequest = request
            if (found == null) IslandBus.lyricsStatus.value = m.key to LyricsStatus.LOADING
            val retried = LyricsRepo.find(m.title, m.artist, m.album, m.durationMs, refreshUnsynced = true)
            currentCoroutineContext().ensureActive()
            if (retried != null || found == null) publishLyrics(retried)
        }
    }

    private companion object {
        const val PAUSE_KEEP_MS = 45_000L
        val DislikeWords = Regex("dislike|не нрав|thumbs?.?down|ban|block")
        val LikeWords = Regex("like|нрав|favou?rite|heart|thumbs?.?up|любим|избран")
        val UndoWords = Regex("unlike|un_like|undo|remove|убрать|удалить|отмен")
    }
}

/**
 * Image fingerprint: the size and 25 points on a 5×5 grid. Cheap (not the whole bitmap) and tells apart different covers of the same size.
 * A hardware bitmap is immutable: its generationId tells a new image apart without reading GPU pixels.
 */
private fun artPrint(b: Bitmap): Long = runCatching {
    var h = b.width * 31L + b.height
    if (b.config == Bitmap.Config.HARDWARE) return@runCatching (h * 31 + b.generationId) * 31 + System.identityHashCode(b)
    for (i in 1..5) for (j in 1..5) h = h * 31 + b.getPixel(b.width * i / 6, b.height * j / 6)
    h
}.getOrDefault(System.nanoTime())
