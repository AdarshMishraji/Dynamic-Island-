package com.akira.island.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akira.island.core.tr
import com.akira.island.data.NotificationHistory
import com.akira.island.data.SettingsRepo
import com.akira.island.ui.GroupToggle
import com.akira.island.ui.Page
import com.akira.island.ui.Pos
import com.akira.island.ui.Tile
import com.akira.island.ui.Ui
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The notifications the island showed, saved on this phone, with a search box. */
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val s by SettingsRepo.state.collectAsState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf(emptyList<NotificationHistory.Entry>()) }
    LaunchedEffect(query, version) { rows = withContext(Dispatchers.IO) { NotificationHistory.search(context, query.trim()) } }
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    Page(tr("Сохранённая история", "Saved history"), onBack) {
        GroupToggle(Pos.Single, Icons.Rounded.History, tr("Хранить историю", "Keep history"),
            tr("Показанные уведомления сохраняются только на этом телефоне; скрытые на экране блокировки не сохраняются. Последние 1000",
                "Shown notifications are saved on this phone only; ones hidden on the lock screen are not. The last 1000"),
            s.keepHistory, color = Tile.Indigo) { v -> SettingsRepo.update { copy(keepHistory = v) } }
        OutlinedTextField(query, { query = it.take(100) }, label = { Text(tr("Поиск", "Search")) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        if (rows.isEmpty()) {
            Text(if (query.isBlank()) tr("Здесь пока пусто", "Nothing here yet") else tr("Ничего не найдено", "Nothing found"),
                color = Ui.Sub, modifier = Modifier.padding(vertical = 16.dp))
        }
        rows.forEach { e ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(e.app + " · " + format.format(Date(e.time)), color = Ui.Sub, fontSize = 12.sp)
                Text(e.title, color = Ui.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (e.text.isNotEmpty()) Text(e.text, color = Ui.Sub, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (rows.isNotEmpty()) TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { NotificationHistory.clear(context) }; version++ } }) {
            Text(tr("Очистить историю", "Clear history"))
        }
    }
}
