package com.example.fm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Root() }
            }
        }
    }
}

private fun hasAccess() = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

@Composable
fun Root() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasAccess()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) granted = hasAccess() }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    if (!granted) {
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("Нужен доступ к файлам", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("Разрешите доступ ко всем файлам, чтобы просматривать память устройства.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")))
            }) { Text("Открыть настройки") }
        }
    } else FileBrowser()
}

private sealed interface Dlg {
    data class Actions(val f: File) : Dlg
    data class Rename(val f: File) : Dlg
    data class Password(val f: File) : Dlg
    object NewFolder : Dlg
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FileBrowser() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val root = remember { Environment.getExternalStorageDirectory() }
    var dir by remember { mutableStateOf(root) }
    var tick by remember { mutableIntStateOf(0) }
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    var busy by remember { mutableStateOf(false) }
    val items = remember(dir, tick) {
        (dir.listFiles()?.toList() ?: emptyList())
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }
    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()
    BackHandler(dir != root) { dir = dir.parentFile ?: root }

    fun extract(f: File, pw: String? = null) {
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Archiver.extract(f, pw) } }
            busy = false; tick++
            r.onSuccess { toast("Распаковано: ${it.name}") }.onFailure {
                if (it.message == "PASSWORD" || it.message?.contains("password", true) == true) dlg = Dlg.Password(f)
                else toast("Ошибка: ${it.message}")
            }
        }
    }
    fun open(f: File) {
        when {
            f.isDirectory -> dir = f
            f.extension.equals("apk", true) -> installApk(ctx, f)
            Archiver.isArchive(f) -> dlg = Dlg.Actions(f)
            else -> openWith(ctx, f)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Column {
            Text("Файлы"); Text(dir.path.removePrefix(root.path).ifEmpty { "/" },
                style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            navigationIcon = { if (dir != root) TextButton({ dir = dir.parentFile ?: root }) { Text("←") } }) },
        floatingActionButton = { FloatingActionButton({ dlg = Dlg.NewFolder }) { Text("+") } }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (items.isEmpty()) Text("Папка пуста", Modifier.align(Alignment.Center))
            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.path }) { f ->
                    ListItem(
                        leadingContent = { Text(when {
                            f.isDirectory -> "📁"; f.extension.equals("apk", true) -> "🤖"
                            Archiver.isArchive(f) -> "📦"; else -> "📄" }, style = MaterialTheme.typography.headlineSmall) },
                        headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(
                            (if (f.isDirectory) "${f.list()?.size ?: 0} элем." else size(f.length())) + " · " +
                                DateFormat.getDateInstance().format(Date(f.lastModified()))) },
                        modifier = Modifier.combinedClickable(onClick = { open(f) }, onLongClick = { dlg = Dlg.Actions(f) })
                    )
                }
            }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }

    when (val d = dlg) {
        is Dlg.Actions -> AlertDialog(onDismissRequest = { dlg = null }, confirmButton = {},
            title = { Text(d.f.name, maxLines = 2) }, text = { Column {
                if (Archiver.isArchive(d.f)) TextButton({ dlg = null; extract(d.f) }) { Text("📦 Распаковать здесь") }
                if (d.f.extension.equals("apk", true)) TextButton({ dlg = null; installApk(ctx, d.f) }) { Text("🤖 Установить") }
                TextButton({ dlg = Dlg.Rename(d.f) }) { Text("✏️ Переименовать") }
                TextButton({ dlg = null; d.f.deleteRecursively(); tick++ }) { Text("🗑 Удалить") }
            } })
        is Dlg.Rename -> TextDialog("Переименовать", d.f.name, { dlg = null }) { n ->
            if (!d.f.renameTo(File(d.f.parentFile, n))) toast("Не удалось переименовать"); dlg = null; tick++ }
        Dlg.NewFolder -> TextDialog("Новая папка", "", { dlg = null }) { n ->
            File(dir, n).mkdirs(); dlg = null; tick++ }
        is Dlg.Password -> TextDialog("Пароль архива", "", { dlg = null }) { p -> dlg = null; extract(d.f, p) }
        null -> {}
    }
}

@Composable
private fun TextDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(t, { t = it }, singleLine = true) },
        confirmButton = { TextButton({ if (t.isNotBlank()) onOk(t.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } })
}

private fun size(b: Long): String = when {
    b < 1024 -> "$b Б"; b < 1024 * 1024 -> "${b / 1024} КБ"
    b < 1024L * 1024 * 1024 -> "%.1f МБ".format(b / 1048576.0); else -> "%.2f ГБ".format(b / 1073741824.0)
}

private fun uri(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", f)

private fun installApk(ctx: Context, f: File) {
    if (!ctx.packageManager.canRequestPackageInstalls()) {
        Toast.makeText(ctx, "Разрешите установку из этого приложения и повторите", Toast.LENGTH_LONG).show()
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
        return
    }
    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri(ctx, f), "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun openWith(ctx: Context, f: File) {
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri(ctx, f), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Toast.makeText(ctx, "Нет приложения для открытия этого файла", Toast.LENGTH_SHORT).show() }
}
