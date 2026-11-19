package com.akira.island.service

import com.akira.island.core.TransferKind
import com.akira.island.core.TransferState
import com.akira.island.data.IslandSettings
import org.junit.Assert.*
import org.junit.Test

class TransferClassifierTest {
    private fun kind(text: String, pkg: String = "com.example") = TransferClassifier.kind(pkg, text)

    @Test fun wordingDecidesTheKind() {
        assertEquals(TransferKind.DOWNLOAD, kind("Downloading report.pdf"))
        assertEquals(TransferKind.UPLOAD, kind("Uploading video"))
        assertEquals(TransferKind.BACKUP, kind("Backing up photos"))
        assertEquals(TransferKind.COPY_MOVE, kind("Copying 3 files"))
        assertEquals(TransferKind.DELETE, kind("Deleting 12 items"))
        assertEquals(TransferKind.INSTALL, kind("Installing Maps"))
        assertEquals(TransferKind.DOWNLOAD, kind("Скачивание файла"))
        assertEquals(TransferKind.UPLOAD, kind("Отправка файла"))
    }

    @Test fun aRestoreBringsDataDownEvenThoughItSaysBackup() {
        assertEquals(TransferKind.DOWNLOAD, kind("Restoring backup"))
    }

    @Test fun packageIsTheFallbackWhenWordingSaysNothing() {
        assertEquals(TransferKind.INSTALL, kind("Maps", "com.android.vending"))
        assertEquals(TransferKind.COPY_MOVE, kind("Working", "com.google.android.documentsui"))
        assertEquals(TransferKind.UPLOAD, kind("Working", "com.dropbox.android"))
        assertEquals(TransferKind.DOWNLOAD, kind("Working"))
    }

    @Test fun aStoreDownloadStillSaysDownloading() {
        assertEquals(TransferKind.DOWNLOAD, kind("Downloading Maps", "com.android.vending"))
    }

    @Test fun stateComesFromWordingAndButtons() {
        assertEquals(TransferState.ACTIVE, TransferClassifier.state("Downloading 40%", listOf("Pause", "Cancel")))
        assertEquals(TransferState.PAUSED, TransferClassifier.state("Paused", emptyList()))
        assertEquals(TransferState.PAUSED, TransferClassifier.state("Downloading", listOf("Resume", "Cancel")))
        assertEquals(TransferState.FAILED, TransferClassifier.state("Download failed", listOf("Retry")))
        assertEquals(TransferState.WAITING, TransferClassifier.state("Waiting for network", emptyList()))
        assertEquals(TransferState.ACTIVE, TransferClassifier.state("Synced, 0 failed", emptyList()))
    }

    @Test fun buttonsAreRecognisedByLabel() {
        assertEquals(TransferClassifier.Button.PAUSE, TransferClassifier.button("Pause"))
        assertEquals(TransferClassifier.Button.RESUME, TransferClassifier.button("Продолжить"))
        assertEquals(TransferClassifier.Button.CANCEL, TransferClassifier.button("Cancel"))
        assertNull(TransferClassifier.button("Open"))
    }

    @Test fun percentInTextNeedsTransferWordingAndIsNotFinished() {
        assertTrue(TransferClassifier.saysTransferring("Downloading… 45%"))
        assertFalse(TransferClassifier.saysTransferring("Battery 45%"))
        assertEquals(45, TransferClassifier.percentIn("Downloading 45%"))
        assertEquals(7, TransferClassifier.percentIn("7,5 %"))
        assertNull(TransferClassifier.percentIn("Done 100%"))
        assertNull(TransferClassifier.percentIn("no number"))
    }

    @Test fun everyKindHasItsOwnSwitch() {
        val all = IslandSettings()
        assertTrue(TransferKind.entries.all { all.transferEnabled(it) })
        assertFalse(all.copy(transferUpload = false).transferEnabled(TransferKind.UPLOAD))
        assertTrue(all.copy(transferUpload = false).transferEnabled(TransferKind.DOWNLOAD))
    }
}
