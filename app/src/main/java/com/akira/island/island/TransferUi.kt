package com.akira.island.island

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileCopy
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.akira.island.core.Tints
import com.akira.island.core.TransferKind
import com.akira.island.core.TransferState
import com.akira.island.core.tr

@Composable
internal fun transferIcon(kind: TransferKind): ImageVector = when (kind) {
    TransferKind.DOWNLOAD -> Ios.download
    TransferKind.UPLOAD -> Ios.arrowUp
    TransferKind.BACKUP -> Icons.Rounded.CloudUpload
    TransferKind.COPY_MOVE -> Icons.Rounded.FileCopy
    TransferKind.DELETE -> Icons.Rounded.Delete
    TransferKind.INSTALL -> Icons.Rounded.SystemUpdate
}

internal fun transferTint(state: TransferState): Color = when (state) {
    TransferState.ACTIVE -> Tints.Blue
    TransferState.PAUSED, TransferState.WAITING -> Tints.Orange
    TransferState.FAILED -> Tints.Red
}

internal fun transferStateText(state: TransferState): String? = when (state) {
    TransferState.ACTIVE -> null
    TransferState.PAUSED -> tr("На паузе", "Paused")
    TransferState.WAITING -> tr("Ожидание", "Waiting")
    TransferState.FAILED -> tr("Ошибка", "Failed")
}
