package com.akira.island.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akira.island.island.swipeActionFor
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Vibration
import com.akira.island.ui.GroupSlider
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.akira.island.core.tr
import com.akira.island.data.*
import com.akira.island.data.TapAction
import com.akira.island.ui.ChoiceDialog
import com.akira.island.ui.Group
import com.akira.island.ui.GroupItem
import com.akira.island.ui.GroupToggle
import com.akira.island.ui.Page
import com.akira.island.ui.Pos
import com.akira.island.ui.Tile
import com.akira.island.ui.SectionHeader

private enum class Which { TAP, DOUBLE, LONG }

private enum class SwipeSlot { UP, DOWN, LEFT, RIGHT, EXPANDED_DOWN }

/** What an expanded card can do on a downward swipe; it has no built-in meaning for it. */
private val expandedChoices = listOf(SwipeAction.DEFAULT, SwipeAction.SHADE, SwipeAction.QUICK_LAUNCH, SwipeAction.SCREENSHOT, SwipeAction.CLEAR_ALL)

@Composable
fun GesturesScreen(onBack: () -> Unit) {
    val s by SettingsRepo.state.collectAsState()
    var swipeDialog by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<Which?>(null) }
    var slot by remember { mutableStateOf<SwipeSlot?>(null) }

    Page(tr("Жесты", "Gestures"), onBack,
        subtitle = tr("Раскрытый остров сворачивается нажатием или касанием за его пределами",
            "The expanded island collapses with a tap or a touch outside it")) {
        SectionHeader(tr("Нажатия", "Taps"))
        Group {
            GroupItem(Pos.First, Icons.Rounded.TouchApp, tr("Одиночное нажатие", "Single tap"), s.tapAction.title,
                onClick = { dialog = Which.TAP }, color = Tile.Blue)
            GroupItem(Pos.Middle, Icons.Rounded.TouchApp, tr("Двойное нажатие", "Double tap"), s.doubleTapAction.title,
                onClick = { dialog = Which.DOUBLE }, color = Tile.Indigo)
            GroupItem(Pos.Last, Icons.Rounded.PanTool, tr("Долгое нажатие", "Long press"), s.longPressAction.title,
                onClick = { dialog = Which.LONG }, color = Tile.Orange)
        }
        if (s.tapAction == TapAction.SCROLL_TOP || s.doubleTapAction == TapAction.SCROLL_TOP || s.longPressAction == TapAction.SCROLL_TOP) {
            SettingsHint(tr("Работает только в приложениях, которые предоставляют прокрутку вертикальных списков. Для длинного списка может понадобиться повторить жест.",
                "Works only in apps that expose vertical list scrolling. Long lists may need the gesture again."))
        }
        SectionHeader(tr("Свайпы", "Swipes"))
        Group {
            GroupToggle(Pos.First, Icons.Rounded.KeyboardArrowDown, tr("Потянуть вниз", "Pull down"),
                tr("Раскрыть капсулу, вверх — свернуть", "Expand the capsule, up — collapse"),
                s.swipeExpand, color = Tile.Teal) { v ->
                SettingsRepo.update { copy(swipeExpand = v) }
            }
            GroupItem(Pos.Last, Icons.Rounded.SwapHoriz, tr("Горизонтальный свайп", "Horizontal swipe"), swipeLabel(s.sideSwipe),
                color = Tile.Purple, onClick = { swipeDialog = true })
        }
        SectionHeader(tr("Свайпы по свёрнутому острову", "Swipes on the collapsed island"))
        Group {
            GroupItem(Pos.First, Icons.Rounded.KeyboardArrowUp, tr("Вверх", "Swipe up"), s.swipeUp.title, color = Tile.Teal, onClick = { slot = SwipeSlot.UP })
            GroupItem(Pos.Middle, Icons.Rounded.KeyboardArrowDown, tr("Вниз", "Swipe down"), s.swipeDown.title, color = Tile.Teal, onClick = { slot = SwipeSlot.DOWN })
            GroupItem(Pos.Middle, Icons.Rounded.KeyboardArrowLeft, tr("Влево", "Swipe left"), s.swipeLeft.title, color = Tile.Purple, onClick = { slot = SwipeSlot.LEFT })
            GroupItem(Pos.Last, Icons.Rounded.KeyboardArrowRight, tr("Вправо", "Swipe right"), s.swipeRight.title, color = Tile.Purple, onClick = { slot = SwipeSlot.RIGHT })
        }
        SettingsHint(tr("«Как обычно» — то, что остров делал раньше: вниз раскрывает, вверх убирает уведомление, в стороны — по настройке выше.",
            "Default keeps what the island did before: down expands, up dismisses the notification, sideways follows the setting above."))
        SectionHeader(tr("Раскрытая карточка", "Expanded card"))
        Group {
            GroupItem(Pos.First, Icons.Rounded.KeyboardArrowDown, tr("Свайп вниз", "Swipe down"),
                if (s.expandedSwipeDown == SwipeAction.DEFAULT) tr("Ничего", "Nothing") else s.expandedSwipeDown.title,
                color = Tile.Orange, onClick = { slot = SwipeSlot.EXPANDED_DOWN })
            GroupToggle(Pos.Last, Icons.Rounded.DeleteSweep, tr("Удержать и смахнуть вверх", "Hold, then swipe up"),
                tr("Очищает все уведомления — с уведомления и с раскрытой карточки", "Clears all notifications — from a notification or an expanded card"),
                s.holdSwipeClears, color = Tile.Red) { v -> SettingsRepo.update { copy(holdSwipeClears = v) } }
        }
        SectionHeader(tr("Отклик жестов", "Gesture feel"))
        Group {
            GroupSlider(Pos.First, Icons.Rounded.Speed, tr("Чувствительность", "Sensitivity"), s.gestureSensitivity.toFloat(), 50f..200f,
                "${s.gestureSensitivity}%", color = Tile.Blue) { v -> SettingsRepo.update { copy(gestureSensitivity = (v / 10).toInt() * 10) } }
            GroupSlider(Pos.Last, Icons.Rounded.Vibration, tr("Сила вибрации", "Haptic strength"), s.hapticStrength.toFloat(), 25f..200f,
                "${s.hapticStrength}%", color = Tile.Pink) { v -> SettingsRepo.update { copy(hapticStrength = (v / 5).toInt() * 5) } }
        }
        SettingsHint(tr("Чем выше чувствительность, тем короче нужен свайп. Сила вибрации 100% — обычный отклик системы.",
            "The higher the sensitivity, the shorter a swipe needs to be. 100% haptic strength is the system's normal feedback."))
        SectionHeader(tr("Попробуйте жесты", "Try the gestures"))
        GesturePlayground(s)
    }

    if (swipeDialog) ChoiceDialog(tr("Горизонтальный свайп", "Horizontal swipe"), SideSwipe.entries, s.sideSwipe, ::swipeLabel,
        { mode -> SettingsRepo.update { withSideSwipe(mode) } }) { swipeDialog = false }
    slot?.let { which ->
        val choices = if (which == SwipeSlot.EXPANDED_DOWN) expandedChoices else SwipeAction.entries.toList()
        val current = when (which) {
            SwipeSlot.UP -> s.swipeUp; SwipeSlot.DOWN -> s.swipeDown; SwipeSlot.LEFT -> s.swipeLeft
            SwipeSlot.RIGHT -> s.swipeRight; SwipeSlot.EXPANDED_DOWN -> s.expandedSwipeDown
        }
        ChoiceDialog(tr("Свайп", "Swipe"), choices, current, { it.title }, { v ->
            SettingsRepo.update {
                when (which) {
                    SwipeSlot.UP -> copy(swipeUp = v); SwipeSlot.DOWN -> copy(swipeDown = v); SwipeSlot.LEFT -> copy(swipeLeft = v)
                    SwipeSlot.RIGHT -> copy(swipeRight = v); SwipeSlot.EXPANDED_DOWN -> copy(expandedSwipeDown = v)
                }
            }
        }) { slot = null }
    }
    when (dialog) {
        Which.TAP -> ChoiceDialog(tr("Одиночное нажатие", "Single tap"), TapAction.entries, s.tapAction, { it.title },
            { v -> SettingsRepo.update { copy(tapAction = v) } }) { dialog = null }
        Which.DOUBLE -> ChoiceDialog(tr("Двойное нажатие", "Double tap"), TapAction.entries, s.doubleTapAction, { it.title },
            { v -> SettingsRepo.update { copy(doubleTapAction = v) } }) { dialog = null }
        Which.LONG -> ChoiceDialog(tr("Долгое нажатие", "Long press"), TapAction.entries, s.longPressAction, { it.title },
            { v -> SettingsRepo.update { copy(longPressAction = v) } }) { dialog = null }
        null -> Unit
    }
}

private fun swipeLabel(mode: SideSwipe): String = when (mode) {
    SideSwipe.OFF -> tr("Выключен", "Off")
    SideSwipe.TRACKS -> tr("Переключать треки", "Change tracks")
    SideSwipe.ACTIVITIES -> tr("Листать карточки", "Switch cards")
}

/** A box to try the gestures in: it says what the island would do, and does nothing. */
@Composable
private fun GesturePlayground(s: IslandSettings) {
    var last by remember { mutableStateOf<String?>(null) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val k = 100f / s.gestureSensitivity.coerceIn(50, 200)
    val threshold = with(density) { 36.dp.toPx() } * k
    val side = with(density) { 56.dp.toPx() } * k
    fun swipeText(dir: com.akira.island.island.SwipeDir): String {
        val action = s.swipeActionFor(dir, expanded = false)
        val name = when (dir) {
            com.akira.island.island.SwipeDir.UP -> tr("Свайп вверх", "Swipe up")
            com.akira.island.island.SwipeDir.DOWN -> tr("Свайп вниз", "Swipe down")
            com.akira.island.island.SwipeDir.LEFT -> tr("Свайп влево", "Swipe left")
            com.akira.island.island.SwipeDir.RIGHT -> tr("Свайп вправо", "Swipe right")
        }
        return "$name → " + if (action == SwipeAction.DEFAULT) tr("как обычно", "default behaviour") else action.title
    }
    Column(
        androidx.compose.ui.Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(24.dp)).background(com.akira.island.ui.Ui.Card)
            .pointerInput(s) {
                detectTapGestures(
                    onTap = { last = tr("Нажатие", "Tap") + " → " + s.tapAction.title },
                    onDoubleTap = { last = tr("Двойное нажатие", "Double tap") + " → " + s.doubleTapAction.title },
                    onLongPress = { last = tr("Долгое нажатие", "Long press") + " → " + s.longPressAction.title },
                )
            }
            .pointerInput(s) {
                var dx = 0f
                var dy = 0f
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f },
                    onDragEnd = { com.akira.island.island.swipeDirection(dx, dy, threshold, side)?.let { last = swipeText(it) } },
                ) { change, amount -> change.consume(); dx += amount.x; dy += amount.y }
            }.padding(20.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        androidx.compose.material3.Text(last ?: tr("Нажмите, дважды нажмите, удержите или смахните здесь", "Tap, double tap, hold or swipe here"),
            color = if (last == null) com.akira.island.ui.Ui.Sub else com.akira.island.ui.Ui.Text, fontSize = 16.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
