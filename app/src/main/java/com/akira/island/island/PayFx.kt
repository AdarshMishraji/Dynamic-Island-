package com.akira.island.island

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.akira.island.core.NoticeStyle
import com.akira.island.core.Tints
import com.akira.island.data.IslandSettings
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Payment around the island, as in the promo video. While we wait for the terminal, lilac NFC waves spread from the island;
 * when payment succeeds, the island bounces and a green ring and sparks fly out from it.
 * Turned off in "Effects" ("Payment waves") and, along with all effects, by "Reduce effects".
 */
object PayFx {
    /** How much bigger the island is than usual right now: on "Paid" it grows by 6% for a moment */
    val pop = mutableFloatStateOf(1f)
}

@Composable
fun PayEffects(primary: Act, body: () -> Rect, s: IslandSettings) {
    if (s.reduceEffects || !s.fxPay) return
    val notice = (primary as? Act.NoticeA)?.n
    when {
        primary is Act.PayA -> PayWaves(body, s.notch)
        notice?.style == NoticeStyle.PAY_DONE -> key(notice.id) { PayBurst(body, notice.tint, s.notch) }
    }
}

/** NFC waves: a new one every 0.9 s, spreading from the island's edges and fading in 1.35 s. */
@Composable
private fun PayWaves(body: () -> Rect, notch: Boolean) {
    val clock = rememberInfiniteTransition(label = "nfc")
    val t by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "t")
    Canvas(Modifier.fillMaxSize()) {
        val b = body()
        if (b.isEmpty) return@Canvas
        for (j in 0..1) {
            val p = (t + j) / 1.5f
            if (p >= 1f) continue
            val e = ExpoOut.transform(p)
            val d = 6.dp.toPx() + e * 90.dp.toPx()
            val alpha = (1f - p) * 0.75f
            outline(b, d, Tints.Lavender.copy(alpha = alpha * 0.25f), 7.dp.toPx(), notch)
            outline(b, d, Tints.Lavender.copy(alpha = alpha), 1.6.dp.toPx(), notch)
        }
    }
}

/** Success: the ring moves away from the island, sparks fly down and to the sides, the island bounces. */
@Composable
private fun PayBurst(body: () -> Rect, color: Color, notch: Boolean) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val pop = Animatable(1.06f)
        PayFx.pop.floatValue = 1.06f
        try {
            launch { pop.animateTo(1f, tween(450, easing = ExpoOut)) { PayFx.pop.floatValue = value } }
            p.animateTo(1f, tween(1000, easing = ExpoOut))
        } finally {
            PayFx.pop.floatValue = 1f
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val b = body()
        if (b.isEmpty || p.value >= 1f) return@Canvas
        val e = p.value
        outline(b, 4.dp.toPx() + e * 130.dp.toPx(), color.copy(alpha = 1f - e), 3.dp.toPx(), notch)
        // 22 sparks fanning downward: green and white, as in the promo video
        val c = b.center
        for (i in 0 until 22) {
            val a = Math.toRadians(8.0 + 164.0 * i / 21).toFloat()
            val spread = 18.dp.toPx() + e * (40.dp.toPx() + Seeds[i] * 50.dp.toPx())
            val x = c.x + cos(a) * (b.width / 2 + spread)
            val y = c.y + sin(a) * (b.height / 2 + spread * 0.8f)
            drawCircle(if (i % 2 == 0) color else Color.White, radius = 2.dp.toPx(), center = Offset(x, y), alpha = 1f - e)
        }
    }
}

/** Random, but the same on every frame, so the sparks do not flicker. */
private val Seeds = FloatArray(22) { i -> ((sin(i * 127.1 + 311.7) * 43758.5453) % 1.0).let { if (it < 0) it + 1.0 else it }.toFloat() }

/** A rounded outline around the island at distance [d]: the same shape, only larger (with a straight top for "Like cutout"). */
private fun DrawScope.outline(b: Rect, d: Float, color: Color, width: Float, notch: Boolean) {
    val corner = min(b.height / 2f, 46.dp.toPx())
    drawIslandRect(
        color,
        topLeft = Offset(b.left - d, b.top - d),
        size = Size(b.width + d * 2, b.height + d * 2),
        corner = corner + d,
        notch = notch,
        style = Stroke(width),
    )
}
