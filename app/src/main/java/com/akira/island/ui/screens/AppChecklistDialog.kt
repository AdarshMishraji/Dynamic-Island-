package com.akira.island.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.akira.island.core.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pick any number of installed apps: ticking one adds its package to [selected]. */
@Composable
fun AppChecklistDialog(title: String, selected: Set<String>, onChange: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val apps by produceState(emptyList<Pair<String, String>>()) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .distinctBy { it.activityInfo.packageName }
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .filter { it.first != context.packageName }
                .sortedBy { it.second.lowercase() }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            apps.forEach { (pkg, label) ->
                val on = pkg in selected
                Row(Modifier.fillMaxWidth().clickable { onChange(if (on) selected - pkg else selected + pkg) }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(on, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text(label)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Готово", "Done")) } })
}
