package com.akira.island.ui.screens

import androidx.compose.foundation.layout.Spacer
import com.akira.island.island.PreviewScene
import androidx.compose.runtime.saveable.rememberSaveable

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.rounded.Height
import androidx.compose.material.icons.rounded.RoundedCorner
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material.icons.rounded.WidthNormal
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.akira.island.data.IslandFont
import com.akira.island.island.IslandType
import com.akira.island.island.island
import com.akira.island.ui.posOf
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.BlurCircular
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.BubbleChart
import androidx.compose.material.icons.rounded.Contactless
import androidx.compose.material.icons.rounded.Flare
import androidx.compose.material.icons.rounded.FormatColorFill
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LineWeight
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akira.island.core.Tints
import com.akira.island.core.tr
import com.akira.island.core.trf
import com.akira.island.data.Elasticity
import com.akira.island.data.ExpandStyle
import com.akira.island.data.FxRate
import com.akira.island.data.IslandSettings
import com.akira.island.data.SettingsRepo
import com.akira.island.ui.ChoiceDialog
import com.akira.island.ui.Group
import com.akira.island.ui.GroupItem
import com.akira.island.ui.GroupSlider
import com.akira.island.ui.GroupToggle
import com.akira.island.ui.Page
import com.akira.island.ui.Pos
import com.akira.island.ui.Screen
import com.akira.island.ui.SectionHeader
import com.akira.island.ui.Tile
import com.akira.island.ui.Ui

/**
 * "Appearance": where the island sits and how it looks: position and size, color and glass, animations and effects.
 * What the island shows and how it behaves is in the "Features" tab.
 */
@Composable
fun CapsuleScreen(open: (Screen) -> Unit) {
    val s by SettingsRepo.state.collectAsState()
    Page(tr("Оформление", "Appearance"), action = { SettingsSearchAction(open) }) {
        IslandPreview()

        SectionHeader(tr("Положение и размер", "Position and size"))
        Group {
            GroupItem(Pos.First, Icons.Rounded.OpenWith, tr("Положение и размер", "Position and size"),
                trf("Капсула {0}×{1} dp — подогнать под камеру", "Capsule {0}×{1} dp — fit it to the camera", s.width, s.height),
                color = Tile.Indigo, onClick = { open(Screen.SIZE) }) { Arrow() }
            GroupItem(Pos.Last, Icons.Rounded.SwapHoriz, tr("Размер и форма", "Size and shape"),
                trf(
                    "С активностью, раскрытый остров и уведомления: ширина {0}%, текст {1}%",
                    "With activity, expanded island and notifications: width {0}%, text {1}%", s.expandedWidthPercent, s.contentScale,
                ),
                color = Tile.Purple, onClick = { open(Screen.LOOK) }) { Arrow() }
        }

        SectionHeader(tr("Цвет и материал", "Color and material"))
        GroupItem(Pos.Single, Icons.Rounded.FormatColorFill, tr("Цвет и рамка", "Color and border"),
            tr("Капсула и карточки: заливка, рамка, стекло и кнопки", "Capsule and cards: fill, border, glass and controls"),
            color = Tile.Orange, onClick = { open(Screen.COLORS) }) { Arrow() }

        SectionHeader(tr("Шрифт", "Font"))
        GroupItem(Pos.Single, Icons.Rounded.FontDownload, tr("Шрифт острова", "Island font"),
            "${fontTitle(s)} · ${weightTitle(s.fontWeightShift)}", color = Tile.Pink, onClick = { open(Screen.FONT) }) { Arrow() }

        SectionHeader(tr("Анимации", "Animations"))
        Group {
            GroupItem(Pos.First, Icons.Rounded.Animation, tr("Раскрытие острова", "Island expansion"),
                "${s.expandStyle.title} · ${s.durationPercent}%",
                color = Tile.Blue, onClick = { open(Screen.ANIM) }) { Arrow() }
            GroupItem(Pos.Last, Icons.Rounded.AutoAwesome, tr("Эффекты", "Effects"), effectsSummary(s),
                color = Tile.Purple, onClick = { open(Screen.EFFECTS) }) { Arrow() }
        }
    }
}

/** The "Elasticity" item with its choice is right in "Appearance", next to "Island expansion". */
@Composable
private fun ElasticityItem(pos: Pos, s: IslandSettings, color: Color) {
    var dialog by remember { mutableStateOf(false) }
    GroupItem(pos, Icons.Rounded.Waves, tr("Упругость", "Elasticity"), s.elasticity.title, color = color, onClick = { dialog = true })
    if (dialog) {
        ChoiceDialog(tr("Упругость анимации", "Animation elasticity"), Elasticity.entries, s.elasticity, { it.title },
            { v -> SettingsRepo.update { copy(elasticity = v) } }) { dialog = false }
    }
}

/** "Size when active": how much thicker and wider the capsule is with navigation, flashlight or music than when idle. */
@Composable
fun LookScreen(onBack: () -> Unit) {
    val s by SettingsRepo.state.collectAsState()
    var card by rememberSaveable { mutableStateOf(false) }
    var scene by rememberSaveable { mutableStateOf(PreviewScene.MUSIC) }
    Page(tr("Размер и форма", "Size and shape"), onBack, preview = { SettingsPreview(card, scene, onScene = { scene = it }) }) {
        ChoiceStrip(listOf(false, true), card, { if (it) tr("Карточка", "Card") else tr("Капсула", "Capsule") }) { card = it }
        if (!card) {
            SectionHeader(tr("Капсула с активностью", "Active capsule"))
            SettingsHint(tr("Прибавка к размеру пустой капсулы. Положение и базовый размер настраиваются на экране «Положение и размер».",
                "Extra size while an activity is present. Set the base size and position in Position and size."))
            Group {
                GroupSlider(Pos.First, Icons.Rounded.LineWeight, tr("Добавить высоту", "Extra height"), s.activeExtra.toFloat(), 0f..14f, "+${s.activeExtra} dp", color = Tile.Purple) {
                    v -> SettingsRepo.update { copy(activeExtra = v.toInt()) }
                }
                GroupSlider(Pos.Last, Icons.Rounded.SwapHoriz, tr("Добавить ширину", "Extra width"), s.activeWidth.toFloat(), 0f..200f, "+${s.activeWidth} dp", color = Tile.Blue) {
                    v -> SettingsRepo.update { copy(activeWidth = (v / 5).toInt() * 5) }
                }
            }
        } else {
            SectionHeader(tr("Раскрытые карточки и уведомления", "Expanded cards and notifications"))
            Group {
                GroupSlider(Pos.First, Icons.Rounded.WidthNormal, tr("Ширина карточки", "Card width"), s.expandedWidthPercent.toFloat(), 60f..100f, "${s.expandedWidthPercent}%", color = Tile.Blue) {
                    v -> SettingsRepo.update { copy(expandedWidthPercent = (v / 5).toInt() * 5) }
                }
                GroupSlider(Pos.Middle, Icons.Rounded.TextIncrease, tr("Размер текста и значков", "Text and icon size"), s.contentScale.toFloat(), 70f..130f, "${s.contentScale}%", color = Tile.Pink) {
                    v -> SettingsRepo.update { copy(contentScale = (v / 5).toInt() * 5) }
                }
                GroupSlider(Pos.Last, Icons.Rounded.Height, tr("Максимальная высота", "Maximum height"), s.expandedMaxHeight.toFloat(), 180f..480f, "${s.expandedMaxHeight} dp", color = Tile.Blue) {
                    v -> SettingsRepo.update { copy(expandedMaxHeight = (v / 30).roundToInt() * 30) }
                }
            }
            SettingsHint(tr("Ограничивает длинные карточки: внутри появится прокрутка. Короткие карточки не растягиваются.",
                "Limits long cards, which scroll inside. Short cards are not stretched."))
        }
        SectionHeader(tr("Скругление углов", "Rounded corners"))
        val radius = if (card) s.expandedCornerRadius else s.cornerRadius
        ChoiceStrip(listOf(true, false), radius < 0, { if (it) tr("Автоматически", "Automatic") else tr("Свой радиус", "Custom radius") }) { auto ->
            SettingsRepo.update { if (card) copy(expandedCornerRadius = if (auto) -1 else 32) else copy(cornerRadius = if (auto) -1 else height / 2) }
        }
        if (radius >= 0) GroupSlider(Pos.Single, Icons.Rounded.RoundedCorner, tr("Радиус углов", "Corner radius"), radius.toFloat(), if (card) 0f..60f else 0f..45f, "$radius dp", color = Tile.Purple) {
            v -> SettingsRepo.update { if (card) copy(expandedCornerRadius = v.roundToInt()) else copy(cornerRadius = v.roundToInt()) }
        }
        if (s.notch) {
            GroupSlider(Pos.Single, Icons.Rounded.RoundedCorner, tr("Верхние углы выреза", "Notch top corners"), s.notchTopRadius.toFloat(), 0f..30f, "${s.notchTopRadius} dp", color = Tile.Teal) {
                v -> SettingsRepo.update { copy(notchTopRadius = v.roundToInt()) }
            }
        }
    }
}

/** "Island expansion": how the island expands, how much it springs, and how much of the event is visible. */
@Composable
fun AnimScreen(onBack: () -> Unit) {
    val s by SettingsRepo.state.collectAsState()
    var styleDialog by remember { mutableStateOf(false) }
    var previewOpen by remember { mutableStateOf(false) }
    LaunchedEffect(s.expandStyle, s.durationPercent, s.elasticity, s.liquidExpand, s.reduceEffects) {
        previewOpen = false
        kotlinx.coroutines.delay(500)
        previewOpen = true
    }

    Page(tr("Раскрытие острова", "Island expansion"), onBack, action = {
        Text(tr("Сброс", "Reset"), color = Ui.Sub, fontSize = 16.sp, modifier = Modifier.clickable { SettingsRepo.resetAnimations() })
    }, preview = { SettingsPreview(previewOpen, onExpand = { previewOpen = !previewOpen }) }) {
        SectionHeader(tr("Скорость", "Speed"))
        ChoiceStrip(listOf(75, 35, 10), s.durationPercent,
            { when (it) { 75 -> tr("Медленно", "Slow"); 35 -> tr("Обычно", "Normal"); else -> tr("Быстро", "Fast") } },
            { v -> SettingsRepo.update { copy(durationPercent = v) } })
        Spacer(Modifier.height(12.dp))
        Group {
            GroupItem(Pos.First, Icons.Rounded.Animation, tr("Стиль раскрытия", "Expansion style"), s.expandStyle.title, color = Tile.Purple,
                onClick = { styleDialog = true })
            GroupSlider(
                Pos.Middle, Icons.Rounded.Speed, tr("Длительность", "Duration"),
                value = s.durationPercent.toFloat(), range = 0f..100f, label = "${s.durationPercent}%", color = Tile.Indigo,
            ) { v -> SettingsRepo.update { copy(durationPercent = v.toInt()) } }
            ElasticityItem(Pos.Middle, s, Tile.Teal)
            GroupToggle(Pos.Middle, Icons.Rounded.BubbleChart, tr("Жидкое раскрытие", "Liquid expansion"),
                tr("Содержимое растёт вместе с островом", "Content grows with the island"), s.liquidExpand, color = Tile.Indigo) {
                v -> SettingsRepo.update { copy(liquidExpand = v) }
            }
            GroupToggle(Pos.Last, Icons.Rounded.BlurOn, tr("Уменьшить эффекты", "Reduce effects"),
                tr("Может быть полезно для не самых производительных устройств", "Can help on less powerful devices"),
                s.reduceEffects, color = Tile.Gray) { v -> SettingsRepo.update { copy(reduceEffects = v) } }
        }
    }

    if (styleDialog) {
        ChoiceDialog(tr("Стиль раскрытия", "Expansion style"), ExpandStyle.entries, s.expandStyle, { it.title },
            { v -> SettingsRepo.update { copy(expandStyle = v) } }) { styleDialog = false }
    }
}

/** What is enabled in "Effects": the item's label. */
private fun effectsSummary(s: IslandSettings): String {
    if (s.reduceEffects) return tr("Выключены: включено «Уменьшить эффекты»", "Off: Reduce effects is on")
    val on = listOfNotNull(
        tr("размытие", "blur").takeIf { s.fxBlur },
        tr("эквалайзер", "equalizer").takeIf { s.fxEqualizer },
        tr("свечение", "glow").takeIf { s.fxGlow },
        tr("волны оплаты", "payment waves").takeIf { s.fxPay },
        tr("вздрагивание", "shake").takeIf { s.fxShake },
    )
    return if (on.isEmpty()) tr("Все выключены", "All off") else on.joinToString(", ").replaceFirstChar { it.uppercase() }
}

/** "Effects": each live island effect is switched on and off separately. */
@Composable
fun EffectsScreen(onBack: () -> Unit) {
    val s by SettingsRepo.state.collectAsState()
    var rateDialog by remember { mutableStateOf(false) }
    Page(tr("Эффекты", "Effects"), onBack, action = {
        Text(tr("Сброс", "Reset"), color = Ui.Sub, fontSize = 16.sp, modifier = Modifier.clickable { SettingsRepo.resetEffects() })
    }, preview = { SettingsPreview(scene = PreviewScene.MUSIC) }) {
        if (s.reduceEffects) {
            Text(
                tr("Сейчас включено «Уменьшить эффекты» в «Раскрытии острова» — пока оно включено, эффектов нет",
                    "Reduce effects is on in Island expansion — while it's on, there are no effects"),
                color = Tints.Orange, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 8.dp),
            )
        }

        SectionHeader(tr("Движение", "Motion"))
        Group {
            GroupToggle(Pos.First, Icons.Rounded.BlurCircular, tr("Размытие при смене", "Blur on change"),
                tr("Содержимое острова перетекает с размытием, как на iPhone (Android 12+)",
                    "The island's content flows with a blur, like on iPhone (Android 12+)"),
                s.fxBlur, color = Tile.Blue) { v -> SettingsRepo.update { copy(fxBlur = v) } }
            GroupToggle(Pos.Middle, Icons.Rounded.GraphicEq, tr("Живой эквалайзер", "Live equalizer"),
                tr("Полоски у музыки и звонка пляшут. Выключено — стоят ровно, так экономнее",
                    "The music and call bars dance. Off — they stand still, which saves power"),
                s.fxEqualizer, color = Tile.Pink) { v -> SettingsRepo.update { copy(fxEqualizer = v) } }
            // Expansion and content changes always run at the screen refresh rate; this is only for things that move constantly
            GroupItem(Pos.Middle, Icons.Rounded.Speed, tr("Частота эквалайзера", "Equalizer frame rate"),
                trf(
                    "{0}. Ещё светящаяся точка фонарика и навигатора",
                    "{0}. Also the glowing dot of the flashlight and navigation", s.fxRate.title,
                ),
                color = Tile.Indigo, onClick = { rateDialog = true })
            GroupToggle(Pos.Last, Icons.Rounded.NotificationsActive, tr("Вздрагивание", "Shake"),
                tr("Капсула вздрагивает при смене режима звука, колокольчик звонящего таймера качается",
                    "The capsule shakes when the ring mode changes, and a ringing timer's bell swings"),
                s.fxShake, color = Tile.Orange) { v -> SettingsRepo.update { copy(fxShake = v) } }
        }

        SectionHeader(tr("Свечение", "Glow"))
        Group {
            GroupToggle(Pos.First, Icons.Rounded.Flare, tr("Свечение обложки", "Artwork glow"),
                tr("Мягкий ореол цвета обложки вокруг острова, пока играет музыка", "A soft halo in the artwork's color around the island while music plays"),
                s.fxGlow, color = Tile.Purple) { v -> SettingsRepo.update { copy(fxGlow = v) } }
            GroupToggle(Pos.Last, Icons.Rounded.Contactless, tr("Волны оплаты", "Payment waves"),
                tr("Волны NFC вокруг острова, пока ждём терминал, и вспышка с искрами на «Оплачено»",
                    "NFC waves around the island while waiting for the reader, and a burst of sparks on Paid"),
                s.fxPay, color = Tile.Teal) { v -> SettingsRepo.update { copy(fxPay = v) } }
        }

        SectionHeader(tr("Батарея", "Battery"))
        GroupToggle(Pos.Single, Icons.Rounded.BatterySaver, tr("Экономия заряда", "Battery saving"),
            tr("В режиме энергосбережения Android остров сам обходится без живых эффектов",
                "In Android's battery saver mode the island skips live effects by itself"),
            s.batterySaver, color = Tile.Green) { v -> SettingsRepo.update { copy(batterySaver = v) } }
    }

    if (rateDialog) {
        ChoiceDialog(tr("Частота эквалайзера", "Equalizer frame rate"), FxRate.entries, s.fxRate, { it.title },
            { v -> SettingsRepo.update { copy(fxRate = v) } }) { rateDialog = false }
    }
}

/** Island font name for the label: for a custom font, the file name. */
private fun fontTitle(s: IslandSettings): String =
    if (s.islandFont == IslandFont.CUSTOM && s.customFontName.isNotEmpty()) s.customFontName else s.islandFont.title

/** Island text weight: -1 thinner, 0 normal, 1 and 2 bolder. */
private fun weightTitle(shift: Int): String = when (shift) {
    -1 -> tr("Тоньше", "Lighter")
    1 -> tr("Жирнее", "Bolder")
    2 -> tr("Самая жирная", "Boldest")
    else -> tr("Обычная толщина", "Regular weight")
}

/**
 * "Island font": Inter (as on iPhone), the phone's font, or your own .ttf/.otf file, plus text weight.
 * A custom font is copied to the app's private folder; if the file can't be read as a font, the previous one stays.
 */
@Composable
fun FontScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val s by SettingsRepo.state.collectAsState()
    LaunchedEffect(s) { IslandType.apply(context, s) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.IO) { copyFont(context, uri) }
            if (picked == null) {
                Toast.makeText(context, tr("Этот файл не читается как шрифт", "This file can't be read as a font"), Toast.LENGTH_LONG).show()
            } else {
                val old = SettingsRepo.current.customFontFile
                SettingsRepo.update { copy(islandFont = IslandFont.CUSTOM, customFontName = picked.first, customFontFile = picked.second) }
                // The previous custom font is no longer needed
                if (old.isNotEmpty() && old != picked.second) withContext(Dispatchers.IO) { File(IslandType.fontsDir(context), old).delete() }
            }
        }
    }

    Page(tr("Шрифт острова", "Island font"), onBack, preview = { SettingsPreview(card = true, scene = PreviewScene.MESSAGE) }) {
        SectionHeader(tr("Шрифт", "Font"))
        Group {
            IslandFont.entries.forEachIndexed { i, f ->
                val chosen = s.islandFont == f
                GroupItem(
                    posOf(i, IslandFont.entries.size), Icons.Rounded.FontDownload,
                    if (f == IslandFont.CUSTOM && s.customFontName.isNotEmpty()) "${f.title}: ${s.customFontName}" else f.title,
                    when (f) {
                        IslandFont.INTER -> tr("Близок к шрифту iPhone", "Close to the iPhone font")
                        IslandFont.SYSTEM -> tr("Тот, что выбран в настройках телефона", "The one chosen in your phone's settings")
                        IslandFont.CUSTOM -> tr("Файл .ttf или .otf с телефона", "A .ttf or .otf file from your phone")
                    },
                    color = if (chosen) Tile.Pink else Tile.Gray,
                    onClick = {
                        when {
                            f != IslandFont.CUSTOM -> SettingsRepo.update { copy(islandFont = f) }
                            // A custom font was already chosen; tapping again lets you pick another file
                            s.customFontFile.isEmpty() || chosen -> picker.launch(arrayOf("font/*", "application/*"))
                            else -> SettingsRepo.update { copy(islandFont = f) }
                        }
                    },
                ) { if (chosen) Icon(Icons.Rounded.Check, null, tint = Ui.Primary) }
            }
        }
        SectionHeader(tr("Толщина", "Weight"))
        Group {
            listOf(-1, 0, 1, 2).forEachIndexed { i, w ->
                GroupItem(posOf(i, 4), Icons.Rounded.FormatBold, weightTitle(w),
                    color = if (s.fontWeightShift == w) Tile.Indigo else Tile.Gray,
                    onClick = { SettingsRepo.update { copy(fontWeightShift = w) } },
                ) { if (s.fontWeightShift == w) Icon(Icons.Rounded.Check, null, tint = Ui.Primary) }
            }
        }
    }
}

/**
 * A copy of the chosen font file in the private folder: (name for settings, file name) or null if it isn't a font.
 * We don't copy anything over 30 MB, since fonts are never that big.
 */
private fun copyFont(context: Context, uri: Uri): Pair<String, String>? {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.substringBeforeLast('.')?.take(40)?.ifBlank { null } ?: tr("Свой шрифт", "Custom font")
    val dir = IslandType.fontsDir(context).apply { mkdirs() }
    val file = File(dir, "font_${System.currentTimeMillis()}")
    val ok = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 30L * 1024 * 1024) return@runCatching false
                    out.write(buf, 0, n)
                }
            }
            true
        } ?: false
    }.getOrDefault(false)
    if (!ok || !IslandType.isFont(file)) {
        file.delete()
        return null
    }
    return name to file.name
}
