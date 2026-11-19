package com.akira.island.island

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akira.island.R
import com.akira.island.core.IslandBus
import com.akira.island.core.MediaInfo
import com.akira.island.core.Notice
import com.akira.island.core.NoticeStyle
import com.akira.island.core.tr
import com.akira.island.data.IslandSettings
import com.akira.island.data.LandscapeMode
import com.akira.island.data.SettingsRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Easter egg. When "Hopes and Dreams" by Toby Fox plays (the final battle music from Undertale), the island
 * fills with DETERMINATION: it bounces to the beat, glows rainbow like the attacks in that battle, on every
 * strong beat stars fly out from under it, like at a save point, a flash on every new phrase,
 * and a red heart, the soul, beats instead of the equalizer. Quieter in the intro, full strength with the drums,
 * fireworks at the big turns of the song, and it fades toward the end of the song and on pause.
 * Beats are counted from the track position in the player: the island does not need to hear the sound.
 */
object Egg {
    /**
     * Soundtrack tempo: in the game files the song runs at 180 beats per minute, on the album it is slowed to 512/3 ≈ 170.667.
     * Even a third of a beat of error would pile up into a whole beat over three minutes.
     */
    const val BPM = 512f / 3f
    const val BEAT_MS = 60_000f / BPM
    /** The intro is 16 bars; the drums come in at 0:22.5 */
    const val DROP_BEAT = 64L
    /** Big turns: drums (0:22.5), the "Your Best Friend" theme (1:30), key change (1:52.5) */
    internal val BIG_BEATS = longArrayOf(DROP_BEAT, 256L, 320L)

    /** Hit: 1 at the moment of a strong beat, fades quickly toward the next */
    val pulse = mutableFloatStateOf(0f)

    /** How bright everything is: quieter in the intro, fades on pause and at the end of the track */
    val intensity = mutableFloatStateOf(0f)

    /** Rainbow hue, degrees */
    val hue = mutableFloatStateOf(0f)

    /** Flash at the start of a phrase: 0 is just now, 1 is faded out */
    val flash = mutableFloatStateOf(1f)

    /** Frame time: the effect layers are redrawn by it while the stars fly */
    val frame = mutableLongStateOf(0L)

    /** How far the island bounces on a strong beat; with "Reduce effects" it does not bounce */
    internal var bounce = 0.03f

    internal val sparks = ArrayList<Spark>()

    private val LINE get() = tr("Вас переполняет РЕШИМОСТЬ.", "You're filled with DETERMINATION.")

    /** "Hopes and Dreams" by Toby Fox: matched by title and artist, album, or the "Undertale" label. */
    fun matches(m: MediaInfo): Boolean {
        val title = norm(m.title)
        if ("hopes and dreams" !in title) return false
        val about = norm(m.artist) + " " + norm(m.album) + " " + title
        return "toby fox" in about || "tobyfox" in about || "undertale" in about
    }

    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    private fun norm(s: String?): String = s.orEmpty().lowercase().replace("&", " and ").replace(nonWord, " ").trim()

    /** Current island scale: it bounces from the top center, as when expanding. */
    fun bump(): Float = 1f + bounce * pulse.floatValue * intensity.floatValue

    /** Where the island is visible, including the bounce: the glow and stars hold to its edges. */
    internal fun scaled(b: Rect): Rect {
        val k = bump()
        val half = b.width / 2f * k
        return Rect(b.center.x - half, b.top, b.center.x + half, b.top + b.height * k)
    }

    /**
     * Watches the player: while that very song plays, it turns on the easter egg ([IslandBus.egg]) and once per track
     * shows a banner like a dialog box from the game. Turned off in "Dynamic cards" → "Easter eggs".
     */
    fun watch(context: Context, scope: CoroutineScope) {
        var announced: String? = null
        scope.launch {
            combine(IslandBus.media, SettingsRepo.state, IslandBus.landscape) { m, s, land ->
                m?.takeIf { s.enabled && s.easterEggs && !(s.landscape == LandscapeMode.HIDE && land) && matches(it) }
            }.collect { m ->
                IslandBus.egg.value = m != null
                if (m == null) {
                    announced = null
                    return@collect
                }
                val id = "${m.pkg}|${m.title}"
                if (m.playing && announced != id) {
                    announced = id
                    IslandBus.post(
                        Notice(
                            title = LINE,
                            caption = m.artist.ifBlank { "Toby Fox" },
                            state = m.title,
                            icon = Ios.load(context, R.drawable.ic_soul),
                            tint = SoulRed,
                            style = NoticeStyle.EGG,
                            // The line is printed letter by letter: the banner needs time so it can be read
                            extraMs = 2_000,
                            openPkg = m.pkg,
                        ),
                    )
                }
            }
        }
    }

    internal fun reset() {
        pulse.floatValue = 0f
        intensity.floatValue = 0f
        flash.floatValue = 1f
        sparks.clear()
    }
}

/** A star that flew out from under the island: where, where to and at what speed (px/ms), how long it lives (ms). */
internal class Spark(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val life: Float,
    val size: Float,
    val color: Color,
    var spin: Float,
    var age: Float = 0f,
)

private val Gold = Color(0xFFFFE14D)
private val SoulRed = Color(0xFFFF1F1F)

private fun rainbow(hue: Float): Color = Color.hsv(((hue % 360f) + 360f) % 360f, 0.85f, 1f)

/** Island corner radius: the capsule is half the height, expanded is at most 44 dp. */
private fun DrawScope.cornerOf(b: Rect) = min(b.height / 2f, 44.dp.toPx())

/**
 * Drives the easter egg frame by frame: counts beats from the track position, speeds up the rainbow, triggers flashes and stars.
 * On pause, once everything has faded, it spends no frames and waits until the music plays again.
 */
@Composable
fun EggDriver(body: () -> Rect, s: IslandSettings) {
    val density = LocalDensity.current.density
    val settings by rememberUpdatedState(s)
    LaunchedEffect(Unit) {
        var lastBeat = Long.MIN_VALUE
        var last = 0L
        try {
            while (isActive) {
                if (IslandBus.media.value?.playing != true && Egg.intensity.floatValue < 0.01f && Egg.sparks.isEmpty()) {
                    Egg.reset()
                    IslandBus.media.first { it?.playing == true }
                    last = 0L
                    continue
                }
                withFrameMillis { }
                val now = SystemClock.elapsedRealtime()
                val dt = if (last == 0L) 16f else (now - last).coerceIn(1L, 100L).toFloat()
                last = now
                val reduce = settings.reduceEffects
                Egg.bounce = if (reduce) 0f else 0.03f

                val m = IslandBus.media.value
                val playing = m?.playing == true
                val pos = m?.positionAt(now) ?: 0L
                val beatF = pos / Egg.BEAT_MS
                val beat = floor(beatF).toLong()
                val phase = beatF - beat
                val left = if (m != null && m.durationMs > 0) m.durationMs - pos else Long.MAX_VALUE
                val target = when {
                    !playing -> 0f
                    left < 4_000 -> left / 4_000f
                    beat < Egg.DROP_BEAT -> 0.6f
                    else -> 1f
                }
                Egg.intensity.floatValue += (target - Egg.intensity.floatValue) * min(1f, dt / 250f)
                Egg.pulse.floatValue = if (playing) {
                    exp(-phase * 5f) * (if (beat % 4 == 0L) 1f else 0.55f)
                } else {
                    Egg.pulse.floatValue * exp(-dt / 120f)
                }
                Egg.hue.floatValue = (Egg.hue.floatValue + dt * 0.05f) % 360f
                Egg.flash.floatValue = min(1f, Egg.flash.floatValue + dt / 700f)

                if (playing && beat != lastBeat && beat >= 0) {
                    val strong = beat % 4 == 0L
                    // Strong beat: the rainbow jumps ahead, stars fly from under the island; a new phrase gives a flash
                    if (strong) Egg.hue.floatValue = (Egg.hue.floatValue + 40f) % 360f
                    if (!reduce) {
                        val b = Egg.scaled(body())
                        when {
                            beat in Egg.BIG_BEATS -> {
                                spawn(b, density, 30, colorful = true)
                                Egg.flash.floatValue = 0f
                            }
                            beat % 16 == 0L -> {
                                spawn(b, density, 14, colorful = true)
                                Egg.flash.floatValue = 0f
                            }
                            strong -> spawn(b, density, 6, colorful = false)
                        }
                    }
                }
                lastBeat = beat
                step(dt, density)
                Egg.frame.longValue = now
            }
        } finally {
            Egg.reset()
        }
    }
}

/** Stars fly outward from the bottom edge and sides of the island. [px] is pixels per dp. */
private fun spawn(b: Rect, px: Float, n: Int, colorful: Boolean) {
    if (b.isEmpty) return
    repeat(n) {
        val side = Random.nextFloat()
        val x: Float
        val y: Float
        var nx: Float
        var ny: Float
        when {
            side < 0.6f -> {
                x = b.left + b.width * (0.08f + 0.84f * Random.nextFloat())
                y = b.bottom
                nx = (x - b.center.x) / b.width
                ny = 1f
            }
            side < 0.8f -> {
                x = b.left
                y = b.top + b.height * Random.nextFloat()
                nx = -1f
                ny = 0.45f
            }
            else -> {
                x = b.right
                y = b.top + b.height * Random.nextFloat()
                nx = 1f
                ny = 0.45f
            }
        }
        val len = sqrt(nx * nx + ny * ny)
        nx /= len
        ny /= len
        val turn = (Random.nextFloat() - 0.5f) * 0.9f
        val speed = (0.07f + Random.nextFloat() * 0.15f) * px
        val color = when {
            colorful -> rainbow(Random.nextFloat() * 360f)
            Random.nextFloat() < 0.3f -> Color.White
            else -> Gold
        }
        Egg.sparks += Spark(
            x, y,
            vx = (nx * cos(turn) - ny * sin(turn)) * speed,
            vy = (nx * sin(turn) + ny * cos(turn)) * speed,
            life = 700f + Random.nextFloat() * 500f,
            size = (3f + Random.nextFloat() * 4f) * px,
            color = color,
            spin = Random.nextFloat() * 6.28f,
        )
    }
    while (Egg.sparks.size > 90) Egg.sparks.removeAt(0)
}

/** Stars fly, slow down and fall slightly, the same at any frame rate. */
private fun step(dt: Float, px: Float) {
    val drag = exp(-dt / 900f)
    val it = Egg.sparks.iterator()
    while (it.hasNext()) {
        val p = it.next()
        p.age += dt
        if (p.age >= p.life) {
            it.remove()
            continue
        }
        p.x += p.vx * dt
        p.y += p.vy * dt
        p.vx *= drag
        p.vy = p.vy * drag + 0.00008f * px * dt
        p.spin += dt * 0.004f
    }
}

/** Rainbow glow behind the island: a halo flashes and spreads out on every beat. */
@Composable
fun EggGlow(body: () -> Rect) {
    Canvas(Modifier.fillMaxSize()) {
        Egg.frame.longValue
        val raw = body()
        val i = Egg.intensity.floatValue
        if (raw.isEmpty || i < 0.01f) return@Canvas
        val b = Egg.scaled(raw)
        val e = Egg.pulse.floatValue
        val hue = Egg.hue.floatValue
        val corner = cornerOf(b)
        // Rings are twice as wide as the step and overlap each other, so the glow is even, without bands
        val gap = 2.2.dp.toPx() * (1f + 0.8f * e)
        val width = gap * 2f
        for (k in 1..10) {
            val grow = k * gap
            val fade = 1f - k / 11f
            drawIslandRect(
                rainbow(hue + k * 10f).copy(alpha = (i * (0.08f + 0.3f * e) * fade * fade).coerceIn(0f, 1f)),
                topLeft = Offset(b.left - grow, b.top - grow),
                size = Size(b.width + grow * 2, b.height + grow * 2),
                corner = corner + grow,
                notch = SettingsRepo.current.notch,
                style = Stroke(width),
            )
        }
    }
}

/** On top of the island: the new phrase flash and flying stars. */
@Composable
fun EggSparks(body: () -> Rect) {
    Canvas(Modifier.fillMaxSize()) {
        Egg.frame.longValue
        val raw = body()
        val i = Egg.intensity.floatValue
        if (raw.isEmpty || i < 0.01f) return@Canvas
        val f = Egg.flash.floatValue
        if (f < 1f) {
            // Rainbow shockwave: a bright ring and two paler ones outside
            val b = Egg.scaled(raw)
            val corner = cornerOf(b)
            val width = 4.dp.toPx() * (1f - f) + 1.5f
            val wave = Brush.sweepGradient(List(7) { rainbow(Egg.hue.floatValue + 180f + it * 60f) }, b.center)
            for (k in 0..2) {
                val grow = f * 46.dp.toPx() + k * width
                drawIslandRect(
                    wave,
                    topLeft = Offset(b.left - grow, b.top - grow),
                    size = Size(b.width + grow * 2, b.height + grow * 2),
                    corner = corner + grow,
                    notch = SettingsRepo.current.notch,
                    style = Stroke(width),
                    alpha = ((1f - f) * 0.9f * i / (k + 1)).coerceIn(0f, 1f),
                )
            }
        }
        for (p in Egg.sparks) {
            val t = p.age / p.life
            star(Offset(p.x, p.y), p.size * (1f - 0.4f * t), p.spin, p.color.copy(alpha = ((1f - t) * i).coerceIn(0f, 1f)))
        }
    }
}

/**
 * Rainbow rim along the island edge. Drawn inside it, so it follows both the shape and the bounce;
 * the colors run around the circle.
 */
fun DrawScope.eggRim(corner: Float, notch: Boolean) {
    val i = Egg.intensity.floatValue
    if (i < 0.01f) return
    val e = Egg.pulse.floatValue
    val w = (1.5f + 1.5f * e).dp.toPx()
    val hue = Egg.hue.floatValue
    drawIslandRect(
        Brush.sweepGradient(List(7) { rainbow(hue + it * 60f) }, center),
        topLeft = Offset(w / 2, w / 2),
        size = Size(size.width - w, size.height - w),
        corner = corner - w / 2,
        notch = notch,
        style = Stroke(w),
        alpha = (i * (0.55f + 0.45f * e)).coerceIn(0f, 1f),
    )
}

/** Four-pointed star, like a save point. */
private fun DrawScope.star(c: Offset, r: Float, rotation: Float, color: Color) {
    val path = Path()
    for (k in 0 until 8) {
        val angle = rotation + k * PI.toFloat() / 4f
        val rr = if (k % 2 == 0) r else r * 0.36f
        val x = c.x + cos(angle) * rr
        val y = c.y + sin(angle) * rr
        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}

/** The soul is a red pixel heart from Undertale; it beats to the song. */
@Composable
fun SoulHeart(modifier: Modifier = Modifier) {
    Icon(
        ImageVector.vectorResource(R.drawable.ic_soul), null, tint = SoulRed,
        modifier = modifier.graphicsLayer {
            val k = 1f + 0.25f * Egg.pulse.floatValue
            scaleX = k
            scaleY = k
        },
    )
}

/**
 * Banner at the start of the song: like a dialog box from the game, a white frame and a line printed letter by letter.
 * At the top, on both sides of the camera: the soul with the artist, and the song title, like a notification header.
 * Tapping opens the player.
 */
@Composable
fun EggBanner(n: Notice, s: IslandSettings) {
    val haptic = rememberHaptic()
    LaunchedEffect(n.id) { haptic() }
    val dim = Color.White.copy(alpha = 0.55f)
    val line = n.title
    var typed by remember(n.id) { mutableIntStateOf(if (s.reduceEffects) line.length else 0) }
    LaunchedEffect(n.id) {
        while (typed < line.length) {
            // As in the game: letter by letter, with a pause at a period
            delay(if (line[typed] in ".,!?") 180L else 42L)
            typed++
        }
    }
    Column(Modifier.width(messageWidth(s)).padding(bottom = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().height(s.height.dp).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                SoulHeart(Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(n.caption.orEmpty(), color = dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(compactGap(s)))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(n.state.orEmpty(), color = dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height((messageTop(s) - s.height.dp).coerceAtLeast(4.dp)))
        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .border(2.5.dp, Color.White, RoundedCornerShape(3.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text("*", color = Color.White, fontSize = 15.sp, lineHeight = 21.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(10.dp))
            // Text not yet printed already takes up space, so lines do not jump while the text is being printed
            Text(
                buildAnnotatedString {
                    append(line.take(typed))
                    withStyle(SpanStyle(color = Color.Transparent)) { append(line.drop(typed)) }
                },
                color = Color.White, fontSize = 15.sp, lineHeight = 21.sp, fontFamily = FontFamily.Monospace,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
