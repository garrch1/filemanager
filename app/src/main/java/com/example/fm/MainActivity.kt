@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.example.fm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContent {
            val dark = when (Prefs.theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Root() }
            }
        }
    }
}

private fun hasAccess(c: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

@Composable
fun Root() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasAccess(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasAccess(ctx) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) granted = hasAccess(ctx) }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    if (!granted) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("Нужен доступ к файлам", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("Разрешите доступ ко всем файлам, чтобы работать с внутренней памятью и SD-картой.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= 30)
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                else launcher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
            }) { Text("Дать доступ") }
        }
    } else App()
}

enum class Screen { HOME, TRASH, SETTINGS }
class Clip(val files: List<File>, val move: Boolean)

@Composable
fun App() {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf(Screen.HOME) }
    var dir by remember { mutableStateOf<File?>(null) }
    var clip by remember { mutableStateOf<Clip?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { Storage.autoClean(ctx) } } }
    when (screen) {
        Screen.TRASH -> TrashScreen { screen = Screen.HOME }
        Screen.SETTINGS -> SettingsScreen { screen = Screen.HOME }
        Screen.HOME -> Browser(dir, { dir = it }, clip, { clip = it }, { screen = Screen.TRASH }, { screen = Screen.SETTINGS })
    }
}

private sealed interface Dlg {
    data class Archive(val f: File) : Dlg
    data class Rename(val f: File) : Dlg
    data class Password(val f: File) : Dlg
    data class Compress(val files: List<File>) : Dlg
    data class Delete(val files: List<File>) : Dlg
    object NewFolder : Dlg
}

@Composable
fun Browser(dir: File?, setDir: (File?) -> Unit, clip: Clip?, setClip: (Clip?) -> Unit,
            openTrash: () -> Unit, openSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val vols = remember(tick) { Storage.volumes(ctx) }
    val sel = remember { mutableStateListOf<File>() }
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    fun say(s: String) = toast(ctx, s)
    fun up(d: File): File? = if (vols.any { it.root == d }) null else d.parentFile

    BackHandler(sel.isNotEmpty() || dir != null) {
        if (sel.isNotEmpty()) sel.clear() else if (dir != null) setDir(up(dir))
    }
    LaunchedEffect(dir) { sel.clear() }

    val files = remember(dir, tick, Prefs.showHidden, Prefs.sortBy, Prefs.sortDesc) {
        if (dir == null) emptyList() else Storage.list(dir)
    }

    fun task(ok: String? = null, block: () -> Unit) {
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { block() } }
            busy = false; tick++
            r.onSuccess { if (ok != null) say(ok) }.onFailure { say("Ошибка: ${it.message}") }
        }
    }

    fun extract(f: File, pw: String? = null) {
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Archiver.extract(f, pw) } }
            busy = false; tick++
            r.onSuccess { say("Распаковано: ${it.name}") }.onFailure {
                if (it.message == "PASSWORD" || it.message?.contains("password", true) == true) dlg = Dlg.Password(f)
                else say("Ошибка: ${it.message}")
            }
        }
    }

    fun delete(list: List<File>) {
        sel.clear()
        task(if (Prefs.useTrash) "Перемещено в корзину: ${list.size}" else "Удалено: ${list.size}") {
            list.forEach { if (Prefs.useTrash) Storage.toTrash(ctx, it) else it.deleteRecursively() }
        }
    }

    fun paste() {
        val c = clip ?: return
        val d = dir ?: return
        task(if (c.move) "Перемещено" else "Скопировано") {
            for (f in c.files) {
                if (f.isDirectory && (d.path + "/").startsWith(f.path + "/")) error("Нельзя поместить папку внутрь самой себя")
                if (c.move && f.parentFile == d) continue
                val t = Storage.unique(File(d, f.name))
                if (c.move) Storage.move(f, t) else Storage.copy(f, t)
            }
        }
        if (c.move) setClip(null)
    }

    fun open(f: File) {
        when {
            f.isDirectory -> setDir(f)
            f.extension.equals("apk", true) -> installApk(ctx, f)
            Archiver.isArchive(f) -> dlg = Dlg.Archive(f)
            else -> openWith(ctx, f)
        }
    }

    Scaffold(
        topBar = {
            if (sel.isNotEmpty()) {
                TopAppBar(
                    title = { Text("Выбрано: ${sel.size}") },
                    navigationIcon = { TextButton({ sel.clear() }) { Text("✕", style = MaterialTheme.typography.titleLarge) } },
                    actions = {
                        if (sel.size == 1) TextButton({ dlg = Dlg.Rename(sel[0]) }) { Text("✏️") }
                        TextButton({ sel.clear(); sel.addAll(files) }) { Text("☑️") }
                    })
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(if (dir == null) "Файлы" else (vols.firstOrNull { it.root == dir }?.name ?: dir.name))
                            if (dir != null) {
                                val root = vols.firstOrNull { dir.path.startsWith(it.root.path) }?.root
                                Text(dir.path.removePrefix(root?.path ?: "").ifEmpty { "/" },
                                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    },
                    navigationIcon = { if (dir != null) TextButton({ setDir(up(dir)) }) { Text("←", style = MaterialTheme.typography.titleLarge) } },
                    actions = {
                        if (dir == null) TextButton(openTrash) { Text("🗑") }
                        else Box {
                            TextButton({ sortMenu = true }) { Text("⇅") }
                            DropdownMenu(sortMenu, { sortMenu = false }) {
                                SortBy.values().forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text((if (Prefs.sortBy == s) "✓ " else "    ") + s.label) },
                                        onClick = { Prefs.sortBy = s; Prefs.save(); sortMenu = false })
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(if (Prefs.sortDesc) "↓ По убыванию" else "↑ По возрастанию") },
                                    onClick = { Prefs.sortDesc = !Prefs.sortDesc; Prefs.save(); sortMenu = false })
                            }
                        }
                        TextButton(openSettings) { Text("⚙️") }
                    })
            }
        },
        bottomBar = {
            if (sel.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        val m = Modifier.weight(1f)
                        Act("📄", "Копия", m) { setClip(Clip(sel.toList(), false)); sel.clear(); say("Откройте нужную папку и нажмите «Вставить»") }
                        Act("✂️", "Перенести", m) { setClip(Clip(sel.toList(), true)); sel.clear(); say("Откройте нужную папку и нажмите «Вставить»") }
                        Act("🗜", "Сжать", m) { dlg = Dlg.Compress(sel.toList()) }
                        Act("📤", "Отправить", m) { shareFiles(ctx, sel.toList()) }
                        Act("🗑", "Удалить", m) { if (Prefs.useTrash) delete(sel.toList()) else dlg = Dlg.Delete(sel.toList()) }
                    }
                }
            } else if (clip != null && dir != null) {
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                        Text((if (clip.move) "Перенос: " else "Копирование: ") + clip.files.size, Modifier.weight(1f))
                        TextButton({ setClip(null) }) { Text("Отмена") }
                        Button({ paste() }) { Text("Вставить сюда") }
                    }
                }
            }
        },
        floatingActionButton = {
            if (dir != null && sel.isEmpty()) FloatingActionButton({ dlg = Dlg.NewFolder }) { Text("+", style = MaterialTheme.typography.titleLarge) }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (dir == null) Home(vols, setDir, openTrash)
            else {
                if (files.isEmpty()) Text("Папка пуста", Modifier.align(Alignment.Center))
                LazyColumn(Modifier.fillMaxSize()) {
                    items(files, key = { it.path }) { f ->
                        val selected = f in sel
                        FileRow(f, selected, sel.isNotEmpty(),
                            onClick = { if (sel.isEmpty()) open(f) else if (selected) sel.remove(f) else sel.add(f) },
                            onLong = { if (!selected) sel.add(f) })
                    }
                }
            }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }

    when (val d = dlg) {
        is Dlg.Archive -> AlertDialog(onDismissRequest = { dlg = null }, confirmButton = {},
            title = { Text(d.f.name, maxLines = 2) },
            text = {
                Column {
                    TextButton({ dlg = null; extract(d.f) }) { Text("📦 Распаковать здесь") }
                    TextButton({ dlg = null; openWith(ctx, d.f) }) { Text("Открыть в другом приложении") }
                }
            })
        is Dlg.Rename -> TextDialog("Переименовать", d.f.name, { dlg = null }) { n ->
            val t = File(d.f.parentFile, n)
            if (t.exists()) say("Такое имя уже есть") else if (!d.f.renameTo(t)) say("Не удалось переименовать")
            dlg = null; sel.clear(); tick++
        }
        Dlg.NewFolder -> TextDialog("Новая папка", "", { dlg = null }) { n ->
            if (dir != null && !File(dir, n).mkdirs()) say("Не удалось создать папку")
            dlg = null; tick++
        }
        is Dlg.Password -> TextDialog("Пароль архива", "", { dlg = null }) { p -> dlg = null; extract(d.f, p) }
        is Dlg.Delete -> AlertDialog(onDismissRequest = { dlg = null }, title = { Text("Удалить безвозвратно?") },
            text = { Text("Элементов: ${d.files.size}. Корзина отключена в настройках, восстановить будет нельзя.") },
            confirmButton = { TextButton({ dlg = null; delete(d.files) }) { Text("Удалить") } },
            dismissButton = { TextButton({ dlg = null }) { Text("Отмена") } })
        is Dlg.Compress -> {
            var name by remember { mutableStateOf(if (d.files.size == 1) d.files[0].nameWithoutExtension else "Архив") }
            var pw by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { dlg = null }, title = { Text("Сжать в ZIP") },
                text = {
                    Column {
                        OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Имя архива") })
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(pw, { pw = it }, singleLine = true, label = { Text("Пароль (необязательно)") },
                            visualTransformation = PasswordVisualTransformation())
                    }
                },
                confirmButton = {
                    TextButton({
                        val base = dir
                        if (base != null) {
                            val list = d.files; val n = name.trim().ifEmpty { "Архив" }; val p = pw
                            dlg = null; sel.clear()
                            task("Архив создан") { Storage.zip(list, Storage.unique(File(base, "$n.zip")), p) }
                        }
                    }) { Text("Сжать") }
                },
                dismissButton = { TextButton({ dlg = null }) { Text("Отмена") } })
        }
        null -> {}
    }
}

@Composable
private fun Act(icon: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun FileRow(f: File, selected: Boolean, selecting: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    ListItem(
        leadingContent = { Thumb(f) },
        headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text((if (f.isDirectory) "${f.list()?.size ?: 0} элем." else fmtSize(f.length())) + " · " + fmtDate(f.lastModified()),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = { if (selecting) Checkbox(selected, null) },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLong))
}

@Composable
private fun Home(vols: List<Vol>, setDir: (File?) -> Unit, openTrash: () -> Unit) {
    val quick = listOf(Triple("📥", "Загрузки", "Download"), Triple("📷", "Камера", "DCIM"), Triple("🖼", "Изображения", "Pictures"),
        Triple("📄", "Документы", "Documents"), Triple("🎵", "Музыка", "Music"), Triple("🎬", "Видео", "Movies"))
        .map { Triple(it.first, it.second, File(vols.first().root, it.third)) }.filter { it.third.isDirectory }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Хранилища", style = MaterialTheme.typography.titleMedium) }
        items(vols) { v ->
            Card(Modifier.fillMaxWidth().clickable { setDir(v.root) }) {
                Column(Modifier.padding(16.dp)) {
                    Text((if (v == vols.first()) "📱 " else "💾 ") + v.name, style = MaterialTheme.typography.titleMedium)
                    val total = v.root.totalSpace
                    val free = v.root.freeSpace
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { if (total > 0) (total - free).toFloat() / total else 0f }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text("Свободно ${fmtSize(free)} из ${fmtSize(total)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (quick.isNotEmpty()) item { Text("Быстрый доступ", style = MaterialTheme.typography.titleMedium) }
        items(quick) { q ->
            ListItem(leadingContent = { Text(q.first, style = MaterialTheme.typography.headlineSmall) },
                headlineContent = { Text(q.second) }, modifier = Modifier.clickable { setDir(q.third) })
        }
        item {
            ListItem(leadingContent = { Text("🗑", style = MaterialTheme.typography.headlineSmall) },
                headlineContent = { Text("Корзина") }, modifier = Modifier.clickable { openTrash() })
        }
    }
}

@Composable
fun TextDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(t, { t = it }, singleLine = true) },
        confirmButton = { TextButton({ if (t.isNotBlank()) onOk(t.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } })
}
