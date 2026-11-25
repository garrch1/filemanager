package com.example.fm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.DateFormat
import java.util.Date

fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

fun fmtSize(b: Long): String = when {
    b < 1024 -> "$b Б"
    b < 1024 * 1024 -> "${b / 1024} КБ"
    b < 1024L * 1024 * 1024 -> "%.1f МБ".format(b / 1048576.0)
    else -> "%.2f ГБ".format(b / 1073741824.0)
}

fun fmtDate(t: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t))

fun fileUri(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", f)

fun mimeOf(f: File): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

fun installApk(ctx: Context, f: File) {
    if (!ctx.packageManager.canRequestPackageInstalls()) {
        toast(ctx, "Разрешите установку из этого приложения и повторите")
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
        return
    }
    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(fileUri(ctx, f), "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun openWith(ctx: Context, f: File) {
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(fileUri(ctx, f), mimeOf(f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { toast(ctx, "Нет приложения для открытия этого файла") }
}

fun shareFiles(ctx: Context, files: List<File>) {
    val fs = files.filter { it.isFile }
    if (fs.isEmpty()) { toast(ctx, "Папки нельзя отправить напрямую — сначала сожмите их в архив"); return }
    if (fs.size < files.size) toast(ctx, "Папки пропущены — их можно отправить архивом")
    val uris = ArrayList(fs.map { fileUri(ctx, it) })
    val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    i.type = if (fs.size == 1) mimeOf(fs[0]) else "*/*"
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Поделиться").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
