package com.akira.island.service

import com.akira.island.core.TransferKind
import com.akira.island.core.TransferState
import java.util.Locale

/**
 * Reads a progress notification's wording to tell what kind of transfer it is, and whether it is stuck. Works on
 * the lower-cased title and text; Russian and English plus the common wording of the other app languages.
 */
internal object TransferClassifier {
    private val delete = listOf("deleting", "emptying", "erasing", "удаление", "удаляется", "eliminando", "suppression", "excluindo", "menghapus")
    private val backup = listOf("backing up", "backup", "back up", "syncing", "synchroniz", "sync in progress", "резервн", "копия", "синхронизац",
        "copia de seguridad", "sauvegarde", "cadastro", "sincroniz", "mencadangkan")
    private val install = listOf("installing", "updating app", "updating apps", "установка", "устанавливается", "instalando", "installation", "menginstal")
    private val copy = listOf("copying", "moving", "compressing", "extracting", "zipping", "unzipping", "pasting", "копирование", "перемещение",
        "копируется", "copiando", "copie", "kopiere", "menyalin")
    private val upload = listOf("upload", "sending", "posting", "exporting", "sharing", "seeding", "отправка", "выгрузка", "отправляется",
        "subiendo", "enviando", "envoi", "hochladen", "mengunggah", "mengirim")
    private val download = listOf("download", "receiving", "restoring", "загрузка", "скачивание", "скачивается", "descargando", "baixando",
        "téléchargement", "herunterladen", "mengunduh")

    private val failed = listOf("failed", "couldn't", "could not", "unsuccessful", "interrupted", "error", "не удалось", "ошибка", "сбой",
        "falló", "falhou", "échec", "fehlgeschlagen", "gagal")
    private val paused = listOf("paused", "приостановлен", "на паузе", "en pausa", "pausado", "en pause", "pausiert", "dijeda")
    private val waiting = listOf("waiting for", "waiting to", "queued", "pending", "no connection", "retrying", "will resume", "ожидани",
        "в очереди", "нет сети", "esperando", "en attente", "warten", "menunggu")

    private val pauseLabel = listOf("pause", "пауза", "приостанов", "pausar", "mettre en pause", "pausieren", "jeda")
    private val resumeLabel = listOf("resume", "retry", "try again", "продолж", "повтор", "возобнов", "reanudar", "retomar", "reprendre", "lanjut")
    private val cancelLabel = listOf("cancel", "stop", "отмен", "стоп", "остановить", "cancelar", "annuler", "abbrechen", "batal")

    // The file this app's own wording and package names alone can't settle.
    private val stores = setOf("com.android.vending", "com.sec.android.app.samsungapps", "org.fdroid.fdroid", "com.aurora.store",
        "com.xiaomi.mipicks", "com.heytap.market", "com.vivo.appstore", "com.huawei.appmarket")
    private val fileManagers = setOf("com.google.android.documentsui", "com.android.documentsui", "com.google.android.apps.nbu.files",
        "com.sec.android.app.myfiles", "com.mi.android.globalfileexplorer", "com.android.fileexplorer", "com.coloros.filemanager",
        "com.oneplus.filemanager")
    private val clouds = setOf("com.google.android.apps.photos", "com.google.android.apps.docs", "com.dropbox.android", "com.microsoft.skydrive",
        "com.nextcloud.client", "com.owncloud.android", "mega.privacy.android.app", "com.box.android", "com.pcloud.pcloud",
        "com.amazon.clouddrive.photos", "com.samsung.android.scloud", "me.proton.android.drive", "com.syncthing.android")

    private fun List<String>.hit(text: String) = any { it in text }

    fun kind(pkg: String, text: String): TransferKind {
        val t = text.lowercase(Locale.ROOT)
        val p = pkg.lowercase(Locale.ROOT)
        return when {
            delete.hit(t) -> TransferKind.DELETE
            // A restore brings data down, even though its text says "backup"
            "restoring" in t || "восстановление" in t -> TransferKind.DOWNLOAD
            backup.hit(t) -> TransferKind.BACKUP
            install.hit(t) -> TransferKind.INSTALL
            copy.hit(t) -> TransferKind.COPY_MOVE
            upload.hit(t) -> TransferKind.UPLOAD
            download.hit(t) -> TransferKind.DOWNLOAD
            p in stores -> TransferKind.INSTALL
            p in fileManagers -> TransferKind.COPY_MOVE
            p in clouds -> TransferKind.UPLOAD
            else -> TransferKind.DOWNLOAD
        }
    }

    /** Failed beats paused beats waiting; a Resume button with no Pause button also means paused. */
    fun state(text: String, labels: List<String>): TransferState {
        val t = text.lowercase(Locale.ROOT).replace(Regex("""\b0\s+(?:failed|errors?)\b"""), "")
        val l = labels.map { it.lowercase(Locale.ROOT) }
        return when {
            failed.hit(t) -> TransferState.FAILED
            paused.hit(t) || (l.any { s -> resumeLabel.hit(s) } && l.none { s -> pauseLabel.hit(s) }) -> TransferState.PAUSED
            waiting.hit(t) -> TransferState.WAITING
            else -> TransferState.ACTIVE
        }
    }

    enum class Button { PAUSE, RESUME, CANCEL }

    fun button(label: String): Button? {
        val l = label.lowercase(Locale.ROOT)
        return when {
            pauseLabel.hit(l) -> Button.PAUSE
            resumeLabel.hit(l) -> Button.RESUME
            cancelLabel.hit(l) -> Button.CANCEL
            else -> null
        }
    }

    /** True when the wording says a transfer is running right now, so a bare "45%" in the text can be believed. */
    fun saysTransferring(text: String): Boolean {
        val t = text.lowercase(Locale.ROOT)
        return listOf(delete, backup, install, copy, upload, download).any { it.hit(t) }
    }

    private val percent = Regex("""(?<![\d.,])(\d{1,3})(?:[.,]\d+)?\s?%""")

    /** "45%" in the text, 0 to 99; a finished transfer (100%) is not progress any more. */
    fun percentIn(text: String): Int? = percent.find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..99 }
}
