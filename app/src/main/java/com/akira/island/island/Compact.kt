@file:OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)

package com.akira.island.island

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.akira.island.core.AppMessage
import com.akira.island.core.InboxItem
import com.akira.island.core.CallInfo
import com.akira.island.core.Controls
import com.akira.island.core.RecordingKind
import com.akira.island.core.IslandBus
import com.akira.island.core.LiveInfo
import com.akira.island.core.LiveKind
import com.akira.island.core.MsgAction
import com.akira.island.core.ReplyState
import com.akira.island.core.MediaInfo
import com.akira.island.core.Notice
import com.akira.island.core.NoticeStyle
import com.akira.island.core.Tints
import com.akira.island.core.formatDuration
import com.akira.island.data.IslandSettings
import com.akira.island.data.MessageStyle
import com.akira.island.data.MessageIconMode
import com.akira.island.data.MessageIconPosition
import com.akira.island.data.retainedNotificationCount
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.text.format.DateFormat
import java.util.Date
import com.akira.island.data.SettingsRepo
import androidx.compose.runtime.collectAsState
import com.akira.island.core.tr
import com.akira.island.core.trf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.ScrollState
import com.akira.island.service.NotifListener

/** Digits of equal width so the time does not "jump". Font and weight come from "Appearance → Font" */
val Digits: TextStyle
    get() = TextStyle(fontFamily = IslandType.family, fontFeatureSettings = "tnum", fontWeight = FontWeight.SemiBold.island)

/** Capsule height when it has something in it: slightly thicker than empty, so it does not look like a thin strip. */
fun activeHeight(s: IslandSettings): Dp = (s.height + s.activeExtra).dp

/** Free space above the camera in the middle: content does not go there. */
fun compactGap(s: IslandSettings): Dp = s.cameraGap.dp.coerceAtLeast(8.dp)

/** The widest content of one side part. */
fun sideMax(s: IslandSettings): Dp = activeHeight(s) * 2.6f

/** Event height: noticeably taller than the collapsed island, like expanded iPhone notifications (62 at height 37). */
fun noticeHeight(s: IslandSettings): Dp = (s.height.dp * 1.68f).coerceAtLeast(56.dp)

/** Incoming call height: avatar, name and two large buttons in one row (84 at height 37). */
fun callHeight(s: IslandSettings): Dp = (s.height.dp * 2.27f).coerceAtLeast(76.dp)

/** Below this line the camera no longer gets in the way of text: expanded card content goes here. */
fun cameraInset(s: IslandSettings): Dp = s.height.dp * 0.5f + 11.5.dp

@Composable
fun CompactContent(act: Act, s: IslandSettings, wideWidth: Dp) {
    IslandTextTheme(s) { CompactContentBody(act, s, wideWidth) }
}

@Composable
private fun CompactContentBody(act: Act, s: IslandSettings, wideWidth: Dp) {
    val h = activeHeight(s)
    val gap = compactGap(s)
    val side = sideMax(s)
    val icon = h * 0.5f
    when (act) {
        // Empty capsule: optionally the clock to the left of the camera, the current charge on the right.
        is Act.Idle -> {
            val keyguard by IslandBus.keyguard.collectAsState()
            val realBattery by IslandBus.battery.collectAsState()
            val battery = if (LocalIslandPreview.current) com.akira.island.core.BatteryInfo(72) else realBattery
            if (s.idleClockBattery) {
                val now = rememberWallNow(1000)
                val context = LocalContext.current
                CompactRow(s.height.dp, gap, side,
                    left = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (s.evLock && keyguard) {
                                Icon(Ios.lock, null, tint = Color.White, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(DateFormat.getTimeFormat(context).format(Date(now)), color = Color.White, fontSize = 13.sp, style = Digits)
                        }
                    },
                    right = {
                        if (battery.level >= 0) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${battery.level}%", color = if (battery.level <= 15) Tints.Red else Color.White, fontSize = 12.sp, style = Digits)
                            if (!s.batteryPercentOnly) {
                                Spacer(Modifier.width(4.dp))
                                BatteryGlyph(battery.level, if (battery.charging) Tints.Green else Color.White, Modifier.size(22.dp, 11.dp))
                            }
                        }
                    },
                )
            } else Box(Modifier.size(s.width.dp, s.height.dp), contentAlignment = Alignment.CenterStart) {
                AnimatedVisibility(s.evLock && keyguard, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                    Icon(Ios.lock, null, tint = Color.White, modifier = Modifier.padding(start = s.height.dp * 0.38f).size(s.height.dp * 0.46f))
                }
            }
        }

        is Act.HotspotA -> CompactRow(h, gap, side,
            left = { Icon(Icons.Rounded.WifiTethering, tr("Точка доступа", "Hotspot"), tint = Tints.Blue, modifier = Modifier.size(icon)) },
            right = { Text(act.status.clients?.toString() ?: tr("Вкл", "On"), color = Color.White,
                fontSize = 17.sp, style = Digits) },
        )

        is Act.WeatherA -> CompactRow(h, gap, side,
            left = { Icon(Icons.Rounded.WbSunny, null, tint = Tints.Yellow, modifier = Modifier.size(icon)) },
            right = { Text("${act.weather.temperature.toInt()}°", color = Color.White, fontSize = 17.sp, style = Digits) },
        )

        is Act.MediaA -> MediaCompact(act.m, s)

        is Act.TimerA -> {
            val now = rememberNow(250)
            if (act.t.ringing) {
                val wobble = rememberInfiniteTransition(label = "bell")
                val angle = if (s.reduceEffects || !s.fxShake) null
                else wobble.animateFloat(-18f, 18f, infiniteRepeatable(tween(120), RepeatMode.Reverse), label = "a")
                CompactRow(h, gap, side * 1.4f,
                    left = {
                        Icon(Ios.bell, null, tint = Tints.Orange, modifier = Modifier.size(icon).graphicsLayer {
                            rotationZ = angle?.value ?: 0f
                            transformOrigin = TransformOrigin(0.5f, 0.1f)
                        })
                    },
                    right = { Text(tr("Таймер", "Timer"), color = Tints.Orange, fontSize = 16.sp, fontWeight = FontWeight.SemiBold.island) },
                )
            } else {
                val left = act.t.left(now)
                val color = if (left <= 10_000 && !act.t.paused) Tints.Red else Tints.Orange
                CompactRow(h, gap, side * 1.4f,
                    left = { Icon(if (act.t.paused) Ios.pause else Ios.timer, null, tint = color, modifier = Modifier.size(icon)) },
                    right = { TimeText(formatDuration(left + 999), color, 18, FontWeight.Bold.island) },
                )
            }
        }

        is Act.StopwatchA -> {
            val now = rememberNow(250, active = act.w.running)
            CompactRow(h, gap, side * 1.4f,
                left = { Icon(Ios.stopwatch, null, tint = Tints.Orange, modifier = Modifier.size(icon)) },
                right = { TimeText(formatDuration(act.w.elapsed(now)), Tints.Orange, 18, FontWeight.Bold.island) },
            )
        }

        is Act.ChronoA -> {
            val now = rememberWallNow(1_000, active = act.c.pausedMs == null)
            // Screen recording is red, like on iPhone; Clock timers and stopwatches are orange
            val recording = act.c.isRecording
            val tint = if (recording) Tints.Red else Tints.Orange
            CompactRow(h, gap, side * 1.4f,
                left = {
                    when {
                        act.c.recordingKind == RecordingKind.VOICE -> Equalizer(tint, act.c.pausedMs == null, s.liveEqualizer,
                            Modifier.size(h * 0.62f, h * 0.42f).semantics { contentDescription = RecordingKind.VOICE.title }, bars = 5)
                        recording -> Icon(act.c.recordingKind!!.icon, act.c.recordingKind.title, tint = tint, modifier = Modifier.size(icon))
                        act.c.pausedMs != null -> Icon(Ios.pause, null, tint = tint, modifier = Modifier.size(icon))
                        else -> AppIcon(act.c.icon, h * 0.56f, Ios.timer, tint)
                    }
                },
                right = { TimeText(formatDuration(act.c.elapsed(now)), tint, 18, FontWeight.Bold.island) },
            )
        }

        is Act.CallA -> if (act.c.incoming) {
            IncomingCall(act.c, s, wideWidth)
        } else {
            val now = rememberWallNow(500)
            CompactRow(h, gap, side * 2f,
                left = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Ios.call, null, tint = Tints.Green, modifier = Modifier.size(h * 0.42f))
                        Spacer(Modifier.width(6.dp))
                        TimeText(formatDuration(now - act.c.base), Tints.Green, 15)
                    }
                },
                right = { Equalizer(Tints.Green, true, s.liveEqualizer, Modifier.size(h * 0.7f, h * 0.42f), bars = 6) },
            )
        }

        is Act.TorchA -> CompactRow(h, gap, side,
            left = { Icon(Ios.flashlight, null, tint = Tints.Yellow, modifier = Modifier.size(icon)) },
            right = { GlowDot(Tints.Yellow, h * 0.26f, !s.reduceEffects) },
        )

        is Act.ProgressA -> {
            val tint = transferTint(act.p.state)
            CompactRow(h, gap, side,
                left = { AppIcon(act.p.icon, h * 0.56f, transferIcon(act.p.kind), tint) },
                right = {
                    if (act.p.indeterminate) {
                        Icon(transferIcon(act.p.kind), null, tint = tint, modifier = Modifier.size(icon))
                    } else {
                        // The ring moves up to the new percentage instead of jumping
                        val fraction by animateFloatAsState(act.p.fraction, tween(450, easing = FastOutSlowInEasing), label = "progress")
                        ProgressRing(fraction, tint, Modifier.size(h * 0.52f), stroke = 3.dp)
                    }
                },
            )
        }

        is Act.ChargingA -> CompactRow(h, gap, side * 1.6f,
            left = { Icon(Ios.flash, null, tint = Tints.Green, modifier = Modifier.size(icon)) },
            right = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TimeText("${act.b.level}%", Tints.Green, 15)
                    Spacer(Modifier.width(5.dp))
                    if (!s.batteryPercentOnly) BatteryGlyph(act.b.level, Tints.Green, Modifier.size(h * 0.6f, h * 0.3f))
                }
            },
        )

        is Act.LiveA -> LiveCompact(act.l, s)

        is Act.AlarmA -> AlarmCompact(act.a, s)

        is Act.NoticeA -> NoticeCompact(act.n, s, wideWidth)

        is Act.PayA -> PayReady(tr("Оплата", "Pay"), tr("К терминалу", "Hold near reader"), act.p.icon, s, wideWidth)

        // "Notification history": icons of apps with something waiting, and the total count
        is Act.InboxA -> CompactRow(h, gap, side * 1.4f,
            left = { AppStack(act.items, h * 0.56f, islandFill(s)) },
            right = { TimeText("${act.items.size}", Tints.Blue, 16) },
        )

        // Exists only while expanded.
        is Act.Launcher -> Unit

    }
}

@Composable
private fun TimeText(text: String, color: Color, size: Int = 16, weight: FontWeight = FontWeight.SemiBold.island) {
    Text(text, color = color, fontSize = size.sp, style = Digits.copy(fontWeight = weight), maxLines = 1, softWrap = false)
}

/** A softly glowing dot shows that the flashlight is on. The glow is only redrawn. */
@Composable
private fun GlowDot(color: Color, size: Dp, animate: Boolean) {
    // The flashlight and navigator stay on for a long time, so the glow runs at the rate from "Effects" (60 frames by default), not up to 120
    val time = rememberFxClock(animate)
    Canvas(Modifier.size(size * 1.8f)) {
        val k = if (animate) 0.55f + 0.45f * pingPong(time.value, 1100) else 1f
        val r = this.size.minDimension / 2
        drawCircle(color.copy(alpha = 0.25f * k), radius = r * k)
        drawCircle(color, radius = r / 1.8f)
    }
}

/**
 * Left and right parts of the collapsed capsule, like on iPhone: icon at the left edge, text or time
 * at the right, camera in the middle. The capsule is wider than empty by "Width with activity"; if the content
 * does not fit, it shrinks a little, and if it is still too long, the capsule widens just enough
 * to avoid covering the camera.
 * The edge inset equals the top inset: content fits within the rounded corner, as Apple requires.
 */
@Composable
fun CompactRow(
    h: Dp,
    gap: Dp,
    side: Dp,
    left: @Composable BoxScope.() -> Unit,
    right: @Composable BoxScope.() -> Unit,
    shrink: Boolean = true,
) {
    // A capsule with activity is wider than a calm one by "Width with activity"; long text will widen it by itself
    val st = SettingsRepo.state.collectAsState().value
    val baseWidth = st.width.dp + st.activeWidth.dp
    val density = LocalDensity.current
    SubcomposeLayout { _ ->
        val hPx = h.roundToPx()
        val pad = (h * 0.3f).roundToPx()
        val gapPx = gap.roundToPx()
        val loose = Constraints(maxWidth = side.roundToPx().coerceAtLeast(1), maxHeight = hPx)
        var l = subcompose("l") { Box(contentAlignment = Alignment.Center, content = left) }.first().measure(loose)
        var r = subcompose("r") { Box(contentAlignment = Alignment.Center, content = right) }.first().measure(loose)
        val base = baseWidth.roundToPx()
        val sideNeed = maxOf(l.width, r.width)
        // In a narrow capsule the content first shrinks (down to 70%), as if drawn smaller,
        // and only if it still does not fit does the capsule widen
        val sideRoom = (base - gapPx) / 2 - pad
        val k = if (shrink && sideNeed > sideRoom && sideRoom > 0) (sideRoom.toFloat() / sideNeed).coerceIn(0.7f, 1f) else 1f
        if (k < 1f) {
            val small = Density(density.density * k, density.fontScale)
            l = subcompose("ls") {
                CompositionLocalProvider(LocalDensity provides small) { Box(contentAlignment = Alignment.Center, content = left) }
            }.first().measure(loose)
            r = subcompose("rs") {
                CompositionLocalProvider(LocalDensity provides small) { Box(contentAlignment = Alignment.Center, content = right) }
            }.first().measure(loose)
        }
        val needed = 2 * (pad + maxOf(l.width, r.width)) + gapPx
        val w = maxOf(needed, base)
        layout(w, hPx) {
            l.place(pad, (hPx - l.height) / 2)
            r.place(w - pad - r.width, (hPx - r.height) / 2)
        }
    }
}

@Composable
private fun MediaCompact(m: MediaInfo, s: IslandSettings) {
    val h = activeHeight(s)
    val art = h * 0.58f
    val egg by IslandBus.egg.collectAsState()
    // New track: the cover changes smoothly, the equalizer color blends into the new cover color
    val accent by animateColorAsState(m.accent, tween(400), label = "accent")
    CompactRow(h, compactGap(s), sideMax(s),
        left = { Crossfade(m.art, animationSpec = tween(300), label = "art") { Art(it, art, art * 0.24f) } },
        right = {
            // Easter egg: a soul beats instead of the equalizer
            if (egg) SoulHeart(Modifier.size(h * 0.5f)) else Equalizer(accent, m.playing, s.liveEqualizer, Modifier.size(h * 0.6f, h * 0.42f))
        },
    )
}

/**
 * Event like on iPhone. Wide: the island expands to almost the full screen width:
 * icon and label on the left, state, battery or ring on the right. Pill is a short
 * toggle (sound mode), the icon swings. Message: avatar, name and text.
 */
@Composable
private fun NoticeCompact(n: Notice, s: IslandSettings, wideWidth: Dp) {
    val requestedDetail by IslandBus.messageDetail.collectAsState()
    val preview = LocalIslandPreview.current
    val detail = requestedDetail && !preview
    when {
        n.message != null && detail -> MessageCard(n, n.message, s, wideWidth, detail = true, preview = preview)
        n.style == NoticeStyle.MESSAGE && n.message != null && s.messageStyle == MessageStyle.ICON -> MessageIcon(n.message, s)
        n.style == NoticeStyle.MESSAGE && n.message != null && s.messageStyle == MessageStyle.PILL -> MessagePill(n, n.message, s)
        n.style == NoticeStyle.MESSAGE && n.message != null -> MessageCard(n, n.message, s, wideWidth, preview = preview)
        n.style == NoticeStyle.PAY_DONE -> PayDone(n, s, wideWidth)
        n.style == NoticeStyle.LIVE && n.live != null -> LiveBanner(n, n.live, s, wideWidth)
        n.style == NoticeStyle.ALARM && n.alarm != null -> AlarmBanner(n, n.alarm, s, wideWidth)
        n.style == NoticeStyle.EGG -> EggBanner(n, s)
        n.style == NoticeStyle.SENT -> SentBanner(n, s, wideWidth)
        n.style == NoticeStyle.UPDATE -> UpdateCard(n, s, wideWidth)
        n.style == NoticeStyle.PILL || n.title.isBlank() && n.state == null -> PillNotice(n, s)
        else -> WideNotice(n, s, wideWidth)
    }
}

@Composable
private fun WideNotice(n: Notice, s: IslandSettings, wideWidth: Dp) {
    // The icon "jumps out", the battery and ring fill up, like on iPhone
    val pop = remember(n.id) { Animatable(if (s.reduceEffects) 1f else 0.3f) }
    val fill = remember(n.id) { Animatable(if (s.reduceEffects) 1f else 0f) }
    LaunchedEffect(n.id) {
        launch { pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 320f)) }
        fill.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    val height = noticeHeight(s)
    // As in the video: icon and what happened on the left, the value in the event color and a battery or ring on the right.
    // For headphones with a known charge the value is a percentage, otherwise a label ("Connected")
    val value = when {
        n.ring != null -> "${(n.ring * 100).toInt()}%"
        else -> n.state ?: n.caption
    }
    Row(
        Modifier.width(wideWidth).height(height).padding(horizontal = height * 0.32f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Icon(n.icon, null, tint = n.tint, modifier = Modifier.size(height * 0.355f).scale(pop.value))
            Spacer(Modifier.width(9.dp))
            // "Do Not Disturb" and "Airplane mode" do not fit before the camera, so the font is a bit smaller instead of an ellipsis
            FitText(n.title, Color.White, 17.sp, 13.sp, FontWeight.SemiBold.island, Modifier.weight(1f, fill = false))
        }
        Spacer(Modifier.width(compactGap(s)))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically) {
            if (value != null) {
                FitText(value, n.tint, if (value.length <= 5) 17.sp else 16.sp, 12.sp, FontWeight.SemiBold.island,
                    Modifier.weight(1f, fill = false), style = Digits)
            }
            if (n.battery != null) {
                Spacer(Modifier.width(8.dp))
                BatteryGlyph((n.battery * fill.value).toInt(), n.tint, Modifier.size(30.dp, 14.dp))
            }
            if (n.ring != null) {
                Spacer(Modifier.width(8.dp))
                ProgressRing(n.ring * fill.value, n.tint, Modifier.size(20.dp), stroke = 3.dp)
            }
        }
    }
}

/** Sound mode pill: the icon on the left swings like a bell on iPhone, the state on the right. */
@Composable
private fun PillNotice(n: Notice, s: IslandSettings) {
    val h = activeHeight(s)
    val swing = remember(n.id) { Animatable(0f) }
    LaunchedEffect(n.id) {
        if (s.reduceEffects || !s.fxShake) return@LaunchedEffect
        // Damped swing: 22° → -16° → 12° → … → 0
        var amp = 22f
        repeat(6) {
            swing.animateTo(amp, tween(70))
            amp = -amp * 0.75f
        }
        swing.animateTo(0f, spring(dampingRatio = 0.5f))
    }
    CompactRow(h, compactGap(s), h * 2.4f,
        left = {
            Icon(
                n.icon, null, tint = n.tint,
                modifier = Modifier.size(h * 0.5f).graphicsLayer {
                    rotationZ = swing.value
                    transformOrigin = TransformOrigin(0.5f, 0.1f)
                },
            )
        },
        right = {
            if (n.state != null) {
                Text(n.state, color = n.tint, fontSize = 16.sp, fontWeight = FontWeight.SemiBold.island, maxLines = 1, softWrap = false)
            }
        },
        // As in the video: the label at full size, the pill a bit wider for it
        shrink = false,
    )
}

/**
 * Swipe a notification down, like an iOS sheet: drag the card down and it goes away. Works on top of scrolling:
 * it only takes over the gesture from the very top of the list, so reading a long message is not confused
 * with dismissing. On release past the threshold [onDismiss] is called, otherwise the card returns to its place.
 */
@Composable
private fun Modifier.swipeDownToDismiss(
    scroll: ScrollState?,
    enabled: Boolean,
    onDismiss: () -> Unit,
): Modifier {
    if (!enabled) return this
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val maxPull = with(density) { 180.dp.toPx() }
    val threshold = with(density) { 96.dp.toPx() }
    return this
        .graphicsLayer { translationY = offset.value }
        .nestedScroll(remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y >= 0f || (scroll != null && scroll.value > 0)) return Offset.Zero
                    scope.launch { offset.snapTo((offset.value - available.y).coerceAtMost(maxPull)) }
                    return Offset(0f, available.y)
                }
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (available.y >= 0f || (scroll != null && scroll.value > 0)) return Offset.Zero
                    scope.launch { offset.snapTo((offset.value - available.y).coerceAtMost(maxPull)) }
                    return Offset(0f, available.y)
                }
            }
        })
        .pointerInput(enabled) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var down = true
                while (down) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    down = event.changes.any { it.pressed }
                }
                if (offset.value >= threshold) {
                    onDismiss()
                } else if (offset.value > 0f) {
                    scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 500f)) }
                }
            }
        }
}

/**
 * App notification: like the expanded iPhone island, but sized like an Android heads-up notification:
 * the same width (16 dp from the edges), avatar 40 dp, name 15 sp, text 14 sp, buttons 14 sp. At the top, on both sides
 * of the camera, are the app icon and name and "now"; the notification itself starts below the status
 * bar, at the same place as the system one. Below are the buttons from the notification ("Reply", "Mark as read").
 * All sp sizes grow with the Android font size; width and insets follow the island settings.
 */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
internal fun MessageCard(n: Notice, m: AppMessage, s: IslandSettings, @Suppress("UNUSED_PARAMETER") wideWidth: Dp, detail: Boolean = false, preview: Boolean = false) {
    val context = LocalContext.current
    val haptic = rememberHaptic()
    val iconSize = 40.dp
    val reply by IslandBus.reply.collectAsState()
    val locked by IslandBus.locked.collectAsState()
    // On a locked phone, like the shade: no buttons, and hidden notifications have no name or text
    val hidden = locked && m.sensitive
    val iconPosition = s.notificationIconPosition
    val showIcon = s.messageIconMode != MessageIconMode.NONE
    val iconMessage = if (hidden || s.messageIconMode == MessageIconMode.APP) m.copy(avatar = null) else m
    val replyAction = reply?.takeIf { it.noticeId == n.id && !locked }?.action
    val accent = rememberAccent(s, m.accent)
    var open by remember(n.id) { mutableStateOf(false) }
    val dim = Color.White.copy(alpha = 0.55f)
    val scrollState = rememberScrollState()
    val dismissible = !locked && NotifListener.canDismiss(m.key)

    // While replying or scrolling through a conversation, the banner does not leave on a timer
    DisposableEffect(open, replyAction != null, detail) {
        if (!preview) IslandBus.noticeHold.value = open || replyAction != null || detail
        onDispose { if (!preview) IslandBus.noticeHold.value = false }
    }
    // Banner gone: the input closes and the window returns to "not focusable" mode
    DisposableEffect(n.id) {
        onDispose { if (!preview && IslandBus.reply.value?.noticeId == n.id) IslandBus.reply.value = null }
    }

    Column(
        Modifier
            .swipeDownToDismiss(
                scroll = if (detail) scrollState else null,
                enabled = detail && dismissible,
                onDismiss = {
                    haptic()
                    NotifListener.dismiss(m.key)
                },
            )
            .animateContentSize(islandSpec(s))
            .width(messageWidth(s))
            .heightIn(max = s.expandedMaxHeight.dp)
            .then(if (detail) Modifier.verticalScroll(scrollState) else Modifier)
            .padding(bottom = 12.dp),
    ) {
        // Header at camera level: app on the left, time on the right, like the side parts of the iPhone island
        Row(
            Modifier.fillMaxWidth().height(s.height.dp).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The app's name and icon are not repeated here: the card already shows the avatar and title.
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(compactGap(s)))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(tr("сейчас", "now"), color = dim, fontSize = 12.sp, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(4.dp))
                if (s.messageIconMode != MessageIconMode.NONE) Icon(Ios.bell, null, tint = dim, modifier = Modifier.size(12.dp))
                // The swipe/dismiss notification button is right in the header, so a long message does not cover the screen
                if (detail && dismissible) {
                    Spacer(Modifier.width(8.dp))
                    RoundButton(Ios.close, size = 20.dp, iconScale = 0.5f, bg = Color.White.copy(alpha = 0.12f)) {
                        NotifListener.dismiss(m.key)
                    }
                }
            }
        }
        Spacer(Modifier.height((messageTop(s) - s.height.dp).coerceAtLeast(4.dp)))
        // Below the header it is larger or smaller per "Content size"; the header at the camera stays as is
        Scaled(s.contentScale) {
            Column {
                if (showIcon && iconPosition == MessageIconPosition.CENTER) {
                    Box(Modifier.fillMaxWidth().padding(bottom = 10.dp), contentAlignment = Alignment.Center) {
                        NotifIcon(iconMessage, n, iconSize, Color(s.color))
                    }
                }
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.Top) {
                    if (showIcon && iconPosition == MessageIconPosition.LEFT) {
                        NotifIcon(iconMessage, n, iconSize, Color(s.color))
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (hidden) m.appName else n.title, color = Color.White, fontSize = 15.sp, lineHeight = 20.sp,
                                fontWeight = FontWeight.SemiBold.island, maxLines = if (detail) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            if (m.history.size > 1 && !hidden) {
                                Spacer(Modifier.width(8.dp))
                                CountPill(m.history.size, open, accent) {
                                    haptic()
                                    open = !open
                                }
                            }
                        }
                        if (hidden) {
                            Text(tr("Содержимое скрыто", "Content hidden"), color = dim, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 1)
                        } else if ((open || detail) && m.history.size > 1) {
                            m.history.forEachIndexed { i, line ->
                                Text(
                                    line, color = Color.White.copy(alpha = if (i == m.history.lastIndex) 0.9f else 0.6f),
                                    fontSize = 14.sp, lineHeight = 19.sp, maxLines = if (detail) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(bottom = 2.dp),
                                )
                            }
                        } else if (m.text.isNotEmpty()) {
                            Text(
                                m.text, color = Color.White.copy(alpha = 0.88f), fontSize = 14.sp, lineHeight = 19.sp, maxLines = if (detail) Int.MAX_VALUE else s.msgLines.coerceIn(1, 8),
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (showIcon && iconPosition == MessageIconPosition.RIGHT) {
                        Spacer(Modifier.width(12.dp))
                        NotifIcon(iconMessage, n, iconSize, Color(s.color))
                    }
                }
                // Image from the notification: a small preview first, larger when the card is tapped
                if (!hidden && m.picture != null) {
                    Image(
                        m.picture, null,
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).fillMaxWidth()
                            .heightIn(max = if (detail) 360.dp else 140.dp).clip(RoundedCornerShape(14.dp)),
                        contentScale = if (detail) ContentScale.Fit else ContentScale.Crop,
                    )
                }
                if (replyAction != null) {
                    // The reply row sits below the text, like the buttons, not on the same line as it
                    Box(Modifier.padding(start = 16.dp + (if (showIcon && iconPosition == MessageIconPosition.LEFT) iconSize + 12.dp else 0.dp), end = 16.dp)) {
                        ReplyField(replyAction, accent, draftKey = m.key) { text ->
                            if (text.isBlank()) return@ReplyField
                            if (Controls.sendReply(context, replyAction, text)) {
                                com.akira.island.core.ReplyDrafts.clear(m.key)
                                IslandBus.reply.value = null
                                IslandBus.notice.value = null
                                // As in the video: for a moment "Sent" with a checkmark and the recipient
                                IslandBus.post(
                                    Notice(
                                        title = tr("Отправлено", "Sent"),
                                        state = n.title,
                                        icon = n.icon,
                                        tint = accent,
                                        style = NoticeStyle.SENT,
                                        extraMs = -SettingsRepo.current.noticeDurationMs / 2L,
                                    ),
                                )
                            } else {
                                // The field stays open with the text: you can try again or reply in the app
                                Toast.makeText(context, tr("Не удалось отправить ответ", "Couldn't send the reply"), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else if (preview && s.msgActions) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        TextAction(tr("Ответить", "Reply"), accent) {}
                        TextAction(tr("Пометить прочитанным", "Mark as read"), accent) {}
                    }
                } else if (m.actions.isNotEmpty() && !locked) {
                    // Full labels; with a large font the next button wraps to a new line.
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        m.actions.forEach { a ->
                            TextAction(a.title, accent, size = 14.sp) {
                                if (a.isReply) {
                                    IslandBus.reply.value = ReplyState(n.id, a)
                                } else {
                                    Controls.send(context, a.intent)
                                    IslandBus.notice.value = null
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Pill notification ("Notification style → Pill"): at the camera, avatar and sender on the left, app on the right,
 * one line of text below them. Takes less space than a card; tapping expands the message inside the island.
 */
@Composable
private fun MessagePill(n: Notice, m: AppMessage, s: IslandSettings) {
    val locked by IslandBus.locked.collectAsState()
    val hidden = locked && m.sensitive
    val iconPosition = s.notificationIconPosition
    val showIcon = s.messageIconMode != MessageIconMode.NONE
    val dim = Color.White.copy(alpha = 0.6f)
    Column(Modifier.width(messageWidth(s)).padding(bottom = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().height(s.height.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                val face = if (hidden || s.messageIconMode == MessageIconMode.APP) m.appIcon else m.avatar ?: m.appIcon
                if (face != null && showIcon && iconPosition == MessageIconPosition.LEFT) {
                    Image(face, null, Modifier.size(20.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    if (hidden) m.appName else n.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold.island,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(compactGap(s)))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(m.appName, color = dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (showIcon && iconPosition == MessageIconPosition.RIGHT) {
                    val icon = if (hidden || s.messageIconMode == MessageIconMode.APP) m.appIcon else m.avatar ?: m.appIcon
                    if (icon != null) {
                        Spacer(Modifier.width(5.dp))
                        Image(icon, null, Modifier.size(18.dp).clip(CircleShape))
                    }
                }
            }
        }
        Scaled(s.contentScale) {
            Column {
                if (showIcon && iconPosition == MessageIconPosition.CENTER) {
                    Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                        NotifIcon(if (hidden || s.messageIconMode == MessageIconMode.APP) m.copy(avatar = null) else m,
                            n, 24.dp, Color(s.color))
                    }
                }
                Text(
                    if (hidden) tr("Содержимое скрыто", "Content hidden") else m.text.ifEmpty { m.appName },
                    color = Color.White.copy(alpha = 0.88f), fontSize = 14.sp, lineHeight = 18.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp),
                )
            }
        }
    }
}

/** App icons overlap, newest on top and on the left; each has a rim in the island color. */
@Composable
private fun AppStack(items: List<InboxItem>, size: Dp, ring: Color) {
    val apps = items.asReversed().distinctBy { it.pkg }.take(3)
    val step = size * 0.55f
    Box(Modifier.size(width = size + step * (apps.size - 1).coerceAtLeast(0), height = size)) {
        for (k in apps.indices.reversed()) {
            val icon = apps[k].appIcon
            Box(
                Modifier.offset(x = step * k).size(size).clip(CircleShape).background(ring).padding(1.5.dp).clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Image(icon, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(Ios.chatbubble, null, tint = Tints.Blue, modifier = Modifier.fillMaxSize().padding(3.dp))
                }
            }
        }
    }
}

/**
 * Notification banner width is like an Android heads-up notification: 16 dp from the screen edges;
 * narrower, per "Expanded island width".
 */
@Composable
fun messageWidth(s: IslandSettings): Dp {
    val full = (LocalConfiguration.current.screenWidthDp.dp - 32.dp).coerceAtMost(460.dp)
    return (full * (s.expandedWidthPercent.coerceIn(60, 100) / 100f)).coerceAtLeast((s.width.dp + 60.dp).coerceAtMost(full))
}

/**
 * Where the notification itself starts in the banner: below the status bar, at the same height as the system
 * notification, taking into account how far down the island is set in settings. Not above the header at the camera.
 */
@Composable
fun messageTop(s: IslandSettings): Dp {
    val status = statusBarHeight()
    return maxOf(s.height.dp + 6.dp, status + 24.dp - s.topOffset.dp)
}

/** Status bar height comes from system resources: the island window does not receive status bar insets. */
@Composable
private fun statusBarHeight(): Dp {
    val res = LocalContext.current.resources
    val density = LocalDensity.current
    return remember(res, density) {
        @Suppress("DiscouragedApi", "InternalInsetResource")
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) with(density) { res.getDimensionPixelSize(id).toDp() } else 24.dp
    }
}

/** A round avatar (or app icon) with the app icon in the corner. */
@Composable
private fun NotifIcon(m: AppMessage, n: Notice, size: Dp, ring: Color) {
    Box(Modifier.size(size + 4.dp).semantics { contentDescription = tr("Значок уведомления", "Notification icon") }) {
        val big = m.avatar ?: m.appIcon
        if (big != null) {
            Image(big, null, Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.size(size).clip(CircleShape).background(Color(0xFF2C2C2E)), contentAlignment = Alignment.Center) {
                Icon(n.icon, null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
            }
        }
        if (m.avatar != null && m.appIcon != null) {
            Image(
                m.appIcon, null,
                Modifier.align(Alignment.BottomEnd).size(size * 0.44f).clip(CircleShape).background(ring).padding(2.dp).clip(CircleShape),
            )
        }
    }
}

/** Unread counter with an arrow: tapping expands the whole conversation. */
@Composable
private fun CountPill(count: Int, open: Boolean, accent: Color, onClick: () -> Unit) {
    val ink = onAccent(accent)
    val rotation by animateFloatAsState(if (open) 180f else 0f, spring(dampingRatio = 0.6f, stiffness = 400f), label = "chevron")
    Row(
        Modifier.height(24.dp).clip(CircleShape).background(accent).clickable(onClick = onClick).padding(start = 9.dp, end = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$count", color = ink, fontSize = 13.sp, style = Digits, maxLines = 1, customizable = false)
        Icon(Icons.Rounded.KeyboardArrowDown, null, tint = ink, modifier = Modifier.size(17.dp).graphicsLayer { rotationZ = rotation })
    }
}

/** Button under the text: just a bold label in the [accent] color, dims slightly under the finger. */
@Composable
private fun TextAction(title: String, accent: Color, modifier: Modifier = Modifier, size: TextUnit = 16.sp, onClick: () -> Unit) {
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(interaction, indication = null) {
                haptic()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title, color = accent.copy(alpha = if (pressed) 0.55f else 1f), fontSize = size, fontWeight = FontWeight.SemiBold.island,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Reply row like in the video and iOS quick reply: a rounded field, and to its right a round
 * button with an up arrow in the [accent] color. The island window is given focus for this time (see IslandService),
 * so the keyboard will show.
 */
@Composable
private fun ReplyField(action: MsgAction, accent: Color, draftKey: String, onSend: (String) -> Unit) {
    var text by remember(draftKey) { mutableStateOf(com.akira.island.core.ReplyDrafts.get(draftKey)) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        // The window does not get focus right away, so we ask for the keyboard several times
        repeat(3) {
            delay(180)
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    val ready = text.isNotBlank()
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.1f))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it; com.akira.island.core.ReplyDrafts.set(draftKey, it) },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp, fontFamily = IslandType.family),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend(text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) {
                            Text("${action.title.ifBlank { "Ответ" }}…", color = Tints.Gray, fontSize = 15.sp, maxLines = 1)
                        }
                        inner()
                    }
                },
            )
        }
        Spacer(Modifier.width(8.dp))
        RoundButton(
            Ios.arrowUp, size = 40.dp,
            bg = if (ready) accent else accent.copy(alpha = 0.35f), fg = onAccent(accent), iconScale = 0.5f,
        ) { onSend(text) }
    }
}

/**
 * Payment, like Apple Pay and the video: on the left the contactless symbol with arcs that light up
 * one by one, and "Payment"; on the right "To terminal" and a bank icon. The middle is space for the camera.
 */
@Composable
private fun PayReady(title: String, caption: String?, appIcon: ImageBitmap?, s: IslandSettings, wideWidth: Dp) {
    val height = noticeHeight(s)
    Row(
        Modifier.width(wideWidth).height(height).padding(horizontal = height * 0.29f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            ContactlessWaves(Tints.Lavender, !s.reduceEffects, Modifier.size(height * 0.42f))
            Spacer(Modifier.width(10.dp))
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold.island, maxLines = 1)
        }
        Spacer(Modifier.width(compactGap(s)))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (caption != null) {
                // "Hold near reader" is longer than "To terminal": it shrinks a little instead of being cut off
                FitText(caption, Tints.Gray, 14.sp, 11.sp, FontWeight.Normal.island, Modifier.weight(1f, fill = false))
            }
            if (appIcon != null) {
                Spacer(Modifier.width(9.dp))
                Image(appIcon, null, Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)))
            }
        }
    }
}

/** Payment done: the green circle springs out, the checkmark is drawn, the amount on the right, like "Done" in Apple Pay. */
@Composable
private fun PayDone(n: Notice, s: IslandSettings, wideWidth: Dp) {
    val haptic = rememberHaptic()
    LaunchedEffect(n.id) { haptic() }
    val height = noticeHeight(s)
    Row(
        Modifier.width(wideWidth).height(height).padding(horizontal = height * 0.29f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            CheckBadge(n.tint, !s.reduceEffects, Modifier.size(height * 0.42f))
            Spacer(Modifier.width(10.dp))
            Text(n.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold.island, maxLines = 1)
        }
        Spacer(Modifier.width(compactGap(s)))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (n.state != null) {
                Text(n.state, color = n.tint, fontSize = 18.sp, style = Digits.copy(fontWeight = FontWeight.Bold.island), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** "Live activity" color: navigator green, taxi yellow, delivery orange. */
fun liveTint(kind: LiveKind): Color = when (kind) {
    LiveKind.NAV -> Tints.Green
    LiveKind.RIDE -> Tints.Yellow
    LiveKind.DELIVERY -> Tints.Orange
}

/**
 * Main info from the notification for a narrow capsule: distance to the maneuver; for a navigator without distance,
 * travel time; for taxi and delivery, the title. Returns null if there is nothing to show.
 */
fun liveShort(l: LiveInfo): String? = l.distance
    ?: if (l.kind == LiveKind.NAV) {
        l.eta?.substringAfterLast(" · ")?.takeIf { it.isNotBlank() }
    } else {
        l.title.ifBlank { l.text }.take(18).takeIf { it.isNotBlank() }
    }

/** "Live activity" icon: a maneuver arrow if the direction is known, otherwise the notification image or the app icon. */
@Composable
fun LiveGlyph(l: LiveInfo, size: Dp, tint: Color) {
    when {
        l.turn != null -> TurnArrow(l.turn, tint, Modifier.size(size))
        l.art != null -> Image(l.art, null, Modifier.size(size).clip(CircleShape))
        else -> AppIcon(l.icon, size, Ios.navigate, tint)
    }
}

/** Navigator, taxi or delivery in the capsule: app icon at the left edge, main info at the right. */
@Composable
private fun LiveCompact(l: LiveInfo, s: IslandSettings) {
    val h = activeHeight(s)
    val tint = liveTint(l.kind)
    CompactRow(h, compactGap(s), sideMax(s) * 1.5f,
        left = { LiveGlyph(l, if (l.turn != null) h * 0.51f else h * 0.56f, tint) },
        right = {
            val short = liveShort(l)
            if (short != null) {
                Text(short, color = tint, fontSize = 17.sp, style = Digits.copy(fontWeight = FontWeight.Bold.island), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            } else {
                GlowDot(tint, h * 0.22f, !s.reduceEffects)
            }
        },
    )
}

/**
 * Wide banner when a navigator, taxi or delivery starts, like iPhone and the video: arrow and distance
 * on the left, street and arrival time on the right; then the island shrinks into a capsule.
 */
@Composable
private fun LiveBanner(n: Notice, l: LiveInfo, s: IslandSettings, wideWidth: Dp) {
    val height = noticeHeight(s)
    val tint = liveTint(l.kind)
    val pop = remember(n.id) { Animatable(if (s.reduceEffects) 1f else 0.3f) }
    LaunchedEffect(n.id) { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 320f)) }
    Row(
        Modifier.width(wideWidth).height(height).padding(horizontal = height * 0.32f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.scale(pop.value)) { LiveGlyph(l, height * 0.39f, tint) }
            Spacer(Modifier.width(10.dp))
            Text(
                l.distance ?: l.title, color = Color.White, fontSize = if (l.distance != null) 19.sp else 16.sp,
                style = Digits.copy(fontWeight = FontWeight.Bold.island), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(compactGap(s)))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
            if (l.text.isNotBlank()) {
                Text(l.text, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 16.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
            }
            // On a narrow banner it is short: "12:31 · 19 h"; in full in the expanded view
            val arrive = trf("Прибытие в {0}", "Arrive {0}", "")
            val shortEta = l.eta?.removePrefix(arrive)?.removePrefix("Прибытие в ")?.removePrefix("Arrive ")?.split(" · ")?.take(2)
                ?.joinToString(" · ")
            if (shortEta != null) {
                Text(shortEta, color = tint, fontSize = 13.sp, style = Digits, maxLines = 1)
            }
        }
    }
}

/**
 * Incoming call, like iPhone and the video: avatar, label with the name and two round buttons in one row
 * in the middle of the height; the green button "breathes" while ringing. The camera falls between the name and the buttons.
 */
@Composable
private fun IncomingCall(c: CallInfo, s: IslandSettings, wideWidth: Dp) {
    val context = LocalContext.current
    val breath = rememberInfiniteTransition(label = "answer")
    val wave = if (s.reduceEffects) null else breath.animateFloat(0f, 1f, infiniteRepeatable(tween(500, easing = LinearEasing)), label = "w")
    val pulse = Modifier.graphicsLayer {
        val k = wave?.let { 1f + 0.06f * kotlin.math.sin(it.value * 2 * Math.PI).toFloat().coerceAtLeast(0f) } ?: 1f
        scaleX = k
        scaleY = k
    }
    Row(
        Modifier.width(wideWidth).height(callHeight(s)).padding(horizontal = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFFFDBA74), Color(0xFFF472B6)))),
            contentAlignment = Alignment.Center,
        ) {
            if (c.avatar != null) {
                Image(c.avatar, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text(c.name.take(1).uppercase(), color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold.island)
            }
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(tr("Входящий вызов", "Incoming call"), color = Tints.Gray, fontSize = 12.5.sp, lineHeight = 15.sp, maxLines = 1)
            Text(c.name, color = Color.White, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold.island, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        RoundButton(Ios.call, size = 46.dp, bg = Tints.Red, iconScale = 0.52f, iconRotation = 135f) {
            Controls.endCall(context, c)
        }
        Spacer(Modifier.width(11.dp))
        RoundButton(Ios.call, pulse, size = 46.dp, bg = Tints.Green, iconScale = 0.52f) {
            Controls.answerCall(context, c)
        }
    }
}


/** Small notification indicator: text stays hidden until the user opens the card. */
@Composable
private fun MessageIcon(m: AppMessage, s: IslandSettings) {
    val h = activeHeight(s)
    val inbox by IslandBus.inbox.collectAsState()
    val count = if (LocalIslandPreview.current) m.history.size.coerceAtLeast(1)
        else retainedNotificationCount(m.key, inbox.map { it.key })
    CompactRow(h, compactGap(s), sideMax(s),
        left = { AppIcon(m.appIcon, h * 0.55f, Ios.chatbubble, Tints.Blue) },
        right = { Text(count.toString(), color = Color.White, fontSize = 15.sp, style = Digits,
            maxLines = 1, softWrap = false) },
    )
}
