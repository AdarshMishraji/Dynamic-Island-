package com.akira.island.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.akira.island.R
import com.akira.island.core.IslandBus
import com.akira.island.core.Lang
import com.akira.island.core.Notice
import com.akira.island.core.NoticeStyle
import com.akira.island.core.Tints
import com.akira.island.core.tr
import com.akira.island.core.trf
import com.akira.island.data.SettingsRepo
import com.akira.island.island.Ios
import com.akira.island.island.releaseHighlights
import com.akira.island.service.NotificationParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import com.akira.island.security.copyBoundedTo
import com.akira.island.security.readUtf8Bounded
import com.akira.island.security.openTrustedHttps

/** New version on GitHub: number, what changed and where to download the APK. */
data class Release(
    val version: String,
    val notes: String,
    val apkUrl: String?,
    val pageUrl: String,
    val sizeBytes: Long,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Error(val message: String) : UpdateState
}

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(val progress: Float) : DownloadState
    data class Ready(val file: File) : DownloadState
    data class Failed(val message: String) : DownloadState
}

/**
 * In-app update: checks the latest GitHub release, shows an "Update" button when there is a new version,
 * downloads the APK and opens the system installer. All builds have the same signature,
 * so the APK installs over the existing one.
 */
object UpdateChecker {
    const val REPO = "Arusaky/Dynamic-Island-"
    private const val CHECK_EVERY_MS = 3 * 60 * 60 * 1000L

    val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val download = MutableStateFlow<DownloadState>(DownloadState.Idle)

    /** Which version the island already announced with a banner, so it doesn't repeat every 3 hours */
    private var announced: String? = null
    private var installation: Job? = null

    fun currentVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "0"

    /**
     * [force] is a check from the button: shows "latest version" and connection errors.
     * The background check every 3 hours stays silent if there is no network or the dismissed version hasn't changed.
     * [announce] is a check from the island service: a new version is announced by a banner on the island
     * (tapping opens the app with the "Update" button).
     */
    suspend fun check(context: Context, force: Boolean, announce: Boolean = false) {
        val s = SettingsRepo.current
        val now = System.currentTimeMillis()
        if (!force && (!s.autoUpdateCheck || now - s.lastUpdateCheck < CHECK_EVERY_MS)) return
        if (state.value is UpdateState.Checking) return
        state.value = UpdateState.Checking
        val result = try {
            Result.success(withContext(Dispatchers.IO) { fetchLatest() })
        } catch (cancel: CancellationException) {
            state.value = UpdateState.Idle
            throw cancel
        } catch (error: Exception) { Result.failure(error) }
        // A check without a connection doesn't count: we retry at the next opportunity, not after 3 hours
        if (result.isSuccess) SettingsRepo.update { copy(lastUpdateCheck = now) }
        val release = result.getOrNull()
        state.value = when {
            result.isFailure -> if (force) UpdateState.Error(
                tr("Нет связи с GitHub. Проверьте интернет и попробуйте ещё раз.", "Can't reach GitHub. Check your internet connection and try again."),
            ) else UpdateState.Idle
            release == null -> if (force) UpdateState.UpToDate else UpdateState.Idle
            !isNewer(release.version, currentVersion(context)) -> if (force) UpdateState.UpToDate else UpdateState.Idle
            !force && release.version == s.skippedVersion -> UpdateState.Idle
            else -> UpdateState.Available(release)
        }
        val available = (state.value as? UpdateState.Available)?.release
        // The previous attempt's error doesn't apply to a new check; the card offers "Update" again
        if (available != null && download.value is DownloadState.Failed) download.value = DownloadState.Idle
        if (announce && available != null && available.version != announced) {
            announced = available.version
            IslandBus.post(
                Notice(
                    title = trf("Доступна версия {0}", "Version {0} is available", available.version),
                    icon = Ios.load(context, R.drawable.ios_download),
                    tint = Tints.Lavender,
                    caption = releaseHighlights(available.notes) ?: tr("Скачается и установится в одно нажатие", "Downloads and installs in one tap"),
                    style = NoticeStyle.UPDATE,
                    appIcon = NotificationParser.icon(context, context.packageName),
                    update = available,
                    openPkg = context.packageName,
                    extraMs = 3_500,
                ),
            )
        }
    }

    /** Example from "Test": nothing is downloaded, the percent counts up by itself, then "Updated". */
    const val DEMO_APK = "demo://update"

    /** The "Updated to ..." card in the island, after installation and in the example. */
    fun updatedNotice(context: Context, version: String) = Notice(
        title = trf("Обновлено до {0}", "Updated to {0}", version),
        icon = Ios.load(context, R.drawable.ios_check),
        tint = Tints.Green,
        caption = tr("Всё готово, настройки сохранены", "All set, your settings are kept"),
        style = NoticeStyle.UPDATE,
        appIcon = NotificationParser.icon(context, context.packageName),
        openPkg = context.packageName,
        extraMs = 2_000,
    )

    /** "Test" → "Update": the same card that comes with a new version, just pretend. */
    fun showDemo(context: Context) {
        download.value = DownloadState.Idle
        IslandBus.post(
            Notice(
                title = tr("Доступна версия 1.7", "Version 1.7 is available"),
                icon = Ios.load(context, R.drawable.ios_download),
                tint = Tints.Lavender,
                caption = tr("Пример: так придёт новая версия", "Example: a new version arrives"),
                style = NoticeStyle.UPDATE,
                appIcon = NotificationParser.icon(context, context.packageName),
                update = Release("1.7", "", DEMO_APK, "", 0L),
                openPkg = context.packageName,
                extraMs = 3_500,
            ),
        )
    }

    /** Name of the downloaded APK: by version, without extra characters from the tag. */
    private fun apkName(version: String) = "DynamicIsland-${version.filter { it.isLetterOrDigit() || it == '.' }}.apk"

    /**
     * The downloaded APK of exactly this version, if it is still in the cache: then "Install" installs it without a new
     * download. A file of another version or one wiped by the system doesn't fit; it must be downloaded again.
     */
    fun readyFile(release: Release, state: DownloadState): File? =
        (state as? DownloadState.Ready)?.file?.takeIf { it.name == apkName(release.version) && it.exists() }

    /** "Later": no more reminders about this version until the next one comes out. */
    fun skip(version: String) {
        SettingsRepo.update { copy(skippedVersion = version) }
        state.value = UpdateState.Idle
        download.value = DownloadState.Idle
    }

    /** Downloads the APK to the app cache and opens the installer right away. */
    suspend fun downloadAndInstall(context: Context, release: Release) {
        val url = release.apkUrl
        if (url == DEMO_APK) {
            if (download.value is DownloadState.Running) return
            for (p in 0..100 step 5) {
                download.value = DownloadState.Running(p / 100f)
                delay(70)
            }
            download.value = DownloadState.Idle
            IslandBus.notice.value = null
            delay(350)
            IslandBus.post(updatedNotice(context, release.version))
            return
        }
        if (url == null) {
            download.value = DownloadState.Failed(tr("В релизе нет APK. Откройте страницу релиза.", "The release has no APK. Open the release page."))
            return
        }
        if (download.value is DownloadState.Running) return
        download.value = DownloadState.Running(0f)
        val result = try {
            val downloaded = withContext(Dispatchers.IO) {
                require(UpdateDownloadPolicy.isReleaseAssetUrl(url))
                require(release.sizeBytes in 0..UpdateDownloadPolicy.MAX_APK_BYTES)
                val job = currentCoroutineContext()
                val dir = File(context.cacheDir, "update").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, apkName(release.version))
                val partial = File(dir, file.name + ".part")
                val conn = openTrustedHttps(url, 15_000, 30_000,
                    mapOf("User-Agent" to "DynamicIsland-Android"), UpdateDownloadPolicy::isDownloadHop)
                try {
                    if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                    val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
                    require(total in 0..UpdateDownloadPolicy.MAX_APK_BYTES)
                    if (release.sizeBytes > 0 && total > 0) require(total == release.sizeBytes)
                    conn.inputStream.use { input ->
                        partial.outputStream().use { out ->
                            var lastShown = -1
                            input.copyBoundedTo(out, UpdateDownloadPolicy.MAX_APK_BYTES, total) { done ->
                                job.ensureActive()
                                if (total > 0) {
                                    val percent = (done * 100 / total).toInt()
                                    if (percent != lastShown) {
                                        lastShown = percent
                                        download.value = DownloadState.Running(percent / 100f)
                                    }
                                }
                            }
                        }
                    }
                    job.ensureActive()
                    check(trustedUpdate(context, partial, release.version))
                    check(partial.renameTo(file))
                    file
                } finally {
                    conn.disconnect()
                    partial.delete()
                }
            }
            Result.success(downloaded)
        } catch (cancel: CancellationException) {
            download.value = DownloadState.Idle
            throw cancel
        } catch (error: Exception) { Result.failure(error) }
        val file = result.getOrNull()
        if (file == null) {
            download.value = DownloadState.Failed(tr("Не удалось скачать. Проверьте интернет и попробуйте ещё раз.",
                "Couldn't download. Check your internet and try again."))
            return
        }
        download.value = DownloadState.Ready(file)
        install(context, file, release)
    }

    /**
     * Opens the system installer. If the app isn't yet allowed to install from unknown
     * sources, the relevant settings open first; after allowing, tap "Install" again.
     */
    fun install(context: Context, file: File, release: Release) {
        if (installation?.isActive == true) return
        installation = IslandBus.scope.launch {
            try {
                val verified = withContext(Dispatchers.IO) {
                    file.canonicalFile.parentFile == File(context.cacheDir, "update").canonicalFile &&
                        readyFile(release, DownloadState.Ready(file)) != null &&
                        trustedUpdate(context, file, release.version)
                }
                check(verified)
                if (!context.packageManager.canRequestPackageInstalls()) {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    return@launch
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                download.value = DownloadState.Failed(tr("Не удалось открыть установку", "Couldn't open the installer"))
            }
        }
    }

    private fun fetchLatest(): Release? {
        val conn = openTrustedHttps("https://api.github.com/repos/$REPO/releases/latest", 10_000, 15_000,
            mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "DynamicIsland-Android"))
        try {
            // No releases yet
            if (conn.responseCode == 404) return null
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return parseRelease(conn.inputStream.use { it.readUtf8Bounded(1_000_000) })
        } finally {
            conn.disconnect()
        }
    }

    /** Parsing the GitHub API `releases/latest` response. */
    internal fun parseRelease(json: String): Release? {
        val o = Json.parseToJsonElement(json).jsonObject
        val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val apk: JsonObject? = o["assets"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { a ->
            val name = a["name"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
            val url = a["browser_download_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val size = a["size"]?.jsonPrimitive?.longOrNull ?: 0
            name.endsWith(".apk") && "debug" !in name && UpdateDownloadPolicy.isReleaseAssetUrl(url) && size in 0..UpdateDownloadPolicy.MAX_APK_BYTES
        }
        return Release(
            version = tag.removePrefix("v").removePrefix("V"),
            notes = o["body"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
            apkUrl = apk?.get("browser_download_url")?.jsonPrimitive?.contentOrNull,
            pageUrl = o["html_url"]?.jsonPrimitive?.contentOrNull ?: "https://github.com/$REPO/releases",
            sizeBytes = apk?.get("size")?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }

    /** 1.10.0 is newer than 1.9.2: compare by numbers, not by strings. */
    internal fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.takeWhile { it.isDigit() || it == '.' }.split('.').map { it.toIntOrNull() ?: 0 }
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}

/**
 * Release description in two languages ("## 🇬🇧 English" and "## 🇷🇺 Русский"): lines in the app's language.
 * If the needed section is missing, the whole description.
 */
fun localNotes(markdown: String): List<String> {
    val all = markdown.lines()
    val start = all.indexOfFirst { it.startsWith("## ") && (if (Lang.en) "English" in it else "Русский" in it) }
    if (start < 0) return all
    val rest = all.drop(start + 1)
    val end = rest.indexOfFirst { it.startsWith("## ") && ("English" in it || "Русский" in it) }
    return if (end < 0) rest else rest.take(end)
}
