package com.akira.island.island

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akira.island.core.Controls
import com.akira.island.core.IslandBus
import com.akira.island.core.Tints
import com.akira.island.core.tr
import com.akira.island.data.IslandSettings
import com.akira.island.data.QuickAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** Apps opened on this phone, newest first, as the accessibility service sees windows change. */
object RecentApps {
    // ponytail: in memory, so the list starts empty after the service restarts; persist it if that bothers users.
    val packages = MutableStateFlow<List<String>>(emptyList())

    fun push(pkg: String) {
        if (packages.value.firstOrNull() == pkg) return
        packages.value = (listOf(pkg) + packages.value.filter { it != pkg }).take(24)
    }
}

private enum class LauncherTab { TOOLS, APPS, RECENTS }

private const val COLUMNS = 4

/** Quick launch: pinned apps, recent apps and system tools, one tab at a time. */
@Composable
internal fun LauncherExpanded(s: IslandSettings) {
    val tabs = LauncherTab.entries.filter {
        (it != LauncherTab.RECENTS || s.launcherRecents) && (it != LauncherTab.TOOLS || s.launcherTools.isNotEmpty())
    }
    var picked by remember { mutableStateOf<LauncherTab?>(null) }
    // Opens on the first tab (Tools); Apps is always present, so the list is never empty.
    val tab = picked?.takeIf { it in tabs } ?: tabs.first()
    Column {
        if (tabs.size > 1) {
            Row(Modifier.fillMaxWidth().clip(CircleShape).background(Color.White.copy(alpha = 0.08f)).padding(3.dp)) {
                tabs.forEach { t ->
                    val selected = t == tab
                    Box(Modifier.weight(1f).clip(CircleShape).background(if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable { picked = t }.padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
                        Text(t.title, color = if (selected) Color.White else Color.White.copy(alpha = 0.6f), fontSize = 13.sp,
                            fontWeight = FontWeight.Medium.island, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        // Five rows of tools exceed the card's height limit: scroll instead of clipping the last row.
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        when (tab) {
            LauncherTab.APPS -> AppGrid(s.launcherApps,
                tr("Добавьте приложения: Функции → Дополнительная панель → Быстрый запуск",
                    "Add apps in Features → Extra controls → Quick launch"))
            LauncherTab.RECENTS -> AppGrid(rememberRecentApps(),
                tr("Здесь появятся приложения, которые вы открываете", "Apps you open will appear here"))
            LauncherTab.TOOLS -> ToolGrid(s.launcherTools)
        }
        }
    }
}

private val LauncherTab.title: String get() = when (this) {
    LauncherTab.APPS -> tr("Приложения", "Apps")
    LauncherTab.RECENTS -> tr("Недавние", "Recents")
    LauncherTab.TOOLS -> tr("Инструменты", "Tools")
}

/** Recents worth showing: launchable apps other than the launcher, this app and the one already open. */
@Composable
private fun rememberRecentApps(): List<String> {
    val context = LocalContext.current
    val recent by RecentApps.packages.collectAsState()
    val open by IslandBus.foregroundPackage.collectAsState()
    val list by produceState(emptyList<String>(), recent, open) {
        value = withContext(Dispatchers.IO) { launchable(context, recent.filter { it != open }).take(COLUMNS * 2) }
    }
    return list
}

private fun launchable(context: Context, packages: List<String>): List<String> {
    val pm = context.packageManager
    val home = runCatching {
        pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
    }.getOrNull()
    return packages.filter { it != context.packageName && it != home && runCatching { pm.getLaunchIntentForPackage(it) }.getOrNull() != null }
}

@Composable
private fun AppGrid(packages: List<String>, empty: String) {
    val context = LocalContext.current
    if (packages.isEmpty()) {
        Text(empty, color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp))
        return
    }
    Grid(packages) { pkg ->
        val app = rememberAppShortcut(pkg)
        Cell(app.label, { Controls.openApp(context, pkg) }) { ShortcutAvatar(app.icon, null, Modifier.size(48.dp)) }
    }
}

@Composable
private fun ToolGrid(tools: List<QuickAction>) {
    val context = LocalContext.current
    Grid(tools) { a ->
        val on = quickOn(a)
        Cell(quickLabel(a), { runQuickAction(a, context) }) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(if (on) Tints.Purple else Color.White.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(quickIcon(a), null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun <T> Grid(items: List<T>, cell: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { item -> Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) { cell(item) } }
                repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Cell(label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val haptic = rememberHaptic()
    Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable { haptic(); onClick() }.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        icon()
        Spacer(Modifier.height(5.dp))
        Text(label, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}
