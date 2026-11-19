package com.akira.island.island

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.akira.island.core.Tints
import com.akira.island.core.tr
import com.akira.island.service.PrivacyMonitor

/** Green for the camera, orange for the microphone, like Android's own indicators; next to the collapsed capsule. */
@Composable
internal fun PrivacyDots(body: () -> Rect, show: Boolean) {
    val privacy by PrivacyMonitor.state.collectAsState()
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.offset {
            val r = body()
            IntOffset((r.right + 7.dp.toPx()).toInt(), (r.center.y - 3.5.dp.toPx()).toInt())
        }) {
            Dot(show && privacy.camera, Tints.Green, tr("Камера используется", "Camera in use"))
            Dot(show && privacy.mic, Tints.Orange, tr("Микрофон используется", "Microphone in use"))
        }
    }
}

@Composable
private fun Dot(visible: Boolean, color: androidx.compose.ui.graphics.Color, description: String) {
    AnimatedVisibility(visible, enter = scaleIn(spring(dampingRatio = 0.5f)), exit = scaleOut()) {
        Box(Modifier.size(11.dp).semantics { contentDescription = description }.clip(CircleShape).background(color).let {
            // a small gap between two dots
            it
        })
    }
}
