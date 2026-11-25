@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.fm

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TrashScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val list = remember(tick) { Storage.trashItems(ctx) }
    var pick by remember { mutableStateOf<TrashItem?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    fun bg(ok: String, block: () -> Unit) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { block() } }
            tick++
            r.onSuccess { toast(ctx, ok) }.onFailure { toast(ctx, "Ошибка: ${it.message}") }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Корзина") },
            navigationIcon = { TextButton(onBack) { Text("←", style = MaterialTheme.typography.titleLarge) } },
            actions = { if (list.isNotEmpty()) TextButton({ confirmAll = true }) { Text("Очистить") } })
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (list.isEmpty()) Text("Корзина пуста", Modifier.align(Alignment.Center))
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.dir.path }) { t ->
                    val f = t.file
                    if (f != null) ListItem(
                        leadingContent = { Thumb(f) },
                        headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text("${t.original.parent ?: ""} · ${fmtDate(t.time)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        modifier = Modifier.clickable { pick = t })
                }
            }
        }
    }

    pick?.let { t ->
        AlertDialog(onDismissRequest = { pick = null }, confirmButton = {}, title = { Text(t.file?.name ?: "", maxLines = 2) },
            text = {
                Column {
                    TextButton({ pick = null; bg("Восстановлено") { Storage.restore(t) } }) { Text("♻️ Восстановить") }
                    TextButton({ pick = null; bg("Удалено навсегда") { t.dir.deleteRecursively() } }) { Text("🗑 Удалить навсегда") }
                }
            })
    }
    if (confirmAll) AlertDialog(onDismissRequest = { confirmAll = false }, title = { Text("Очистить корзину?") },
        text = { Text("Все файлы в корзине будут удалены безвозвратно.") },
        confirmButton = { TextButton({ confirmAll = false; bg("Корзина очищена") { Storage.emptyTrash(ctx) } }) { Text("Очистить") } },
        dismissButton = { TextButton({ confirmAll = false }) { Text("Отмена") } })
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(title = { Text("Настройки") },
            navigationIcon = { TextButton(onBack) { Text("←", style = MaterialTheme.typography.titleLarge) } })
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            Section("Тема")
            Row(Modifier.padding(horizontal = 16.dp), Arrangement.spacedBy(8.dp)) {
                listOf("Системная", "Светлая", "Тёмная").forEachIndexed { i, l ->
                    FilterChip(Prefs.theme == i, { Prefs.theme = i; Prefs.save() }, { Text(l) })
                }
            }
            Section("Отображение")
            Switch2("Показывать скрытые файлы", Prefs.showHidden) { Prefs.showHidden = it; Prefs.save() }
            Switch2("Показывать миниатюры", Prefs.thumbnails) { Prefs.thumbnails = it; Prefs.save() }
            Section("Корзина")
            Switch2("Удалять через корзину", Prefs.useTrash) { Prefs.useTrash = it; Prefs.save() }
            Text("Автоочистка корзины", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Row(Modifier.padding(horizontal = 16.dp), Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Никогда", 7 to "7 дней", 30 to "30 дней").forEach { (d, l) ->
                    FilterChip(Prefs.trashDays == d, { Prefs.trashDays = d; Prefs.save() }, { Text(l) })
                }
            }
            TextButton({
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { Storage.emptyTrash(ctx) } }
                    toast(ctx, "Корзина очищена")
                }
            }, Modifier.padding(horizontal = 8.dp)) { Text("Очистить корзину сейчас") }
            Section("О приложении")
            Text("Файловый менеджер 2.0", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(t: String) =
    Text(t, Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary)

@Composable
private fun Switch2(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(headlineContent = { Text(title) }, trailingContent = { Switch(checked, onChange) },
        modifier = Modifier.clickable { onChange(!checked) })
}
