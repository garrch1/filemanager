package com.example.fm

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.util.UUID

data class Vol(val name: String, val root: File)

class TrashItem(val dir: File) {
    private val lines = File(dir, ".meta").takeIf { it.exists() }?.readLines().orEmpty()
    val original = File(lines.getOrElse(0) { "" })
    val time: Long = lines.getOrNull(1)?.toLongOrNull() ?: dir.lastModified()
    val file: File? = dir.listFiles()?.firstOrNull { it.name != ".meta" }
}

object Storage {
    /** Внутренняя память + все SD-карты / USB-накопители. */
    fun volumes(ctx: Context): List<Vol> {
        val sm = ctx.getSystemService(StorageManager::class.java)
        val res = mutableListOf<Vol>()
        ctx.getExternalFilesDirs(null).forEachIndexed { i, d ->
            if (d == null) return@forEachIndexed
            val root = File(d.path.substringBefore("/Android/data"))
            if (!root.exists() || res.any { it.root == root }) return@forEachIndexed
            val name = if (i == 0) "Внутренняя память" else (sm?.getStorageVolume(d)?.getDescription(ctx) ?: "SD-карта")
            res += Vol(name, root)
        }
        if (res.isEmpty()) res += Vol("Внутренняя память", Environment.getExternalStorageDirectory())
        return res
    }

    fun list(d: File): List<File> {
        val all = (d.listFiles()?.toList() ?: emptyList())
            .filter { it.name != ".FMTrash" && (Prefs.showHidden || !it.name.startsWith(".")) }
        val cmp: Comparator<File> = when (Prefs.sortBy) {
            SortBy.NAME -> compareBy<File> { it.name.lowercase() }
            SortBy.DATE -> compareBy<File> { it.lastModified() }
            SortBy.SIZE -> compareBy<File> { if (it.isDirectory) 0L else it.length() }
            SortBy.TYPE -> compareBy<File> { it.extension.lowercase() }.thenBy { it.name.lowercase() }
        }
        val ordered = if (Prefs.sortDesc) cmp.reversed() else cmp
        return all.sortedWith(compareBy<File> { !it.isDirectory }.then(ordered))
    }

    fun unique(f: File): File {
        if (!f.exists()) return f
        val ext = if (f.isFile && f.extension.isNotEmpty()) "." + f.extension else ""
        val base = if (ext.isEmpty()) f.name else f.name.dropLast(ext.length)
        var i = 1
        var n: File
        do { n = File(f.parentFile, "$base ($i)$ext"); i++ } while (n.exists())
        return n
    }

    fun copy(src: File, dst: File) {
        if (!src.copyRecursively(dst)) { dst.deleteRecursively(); error("Не удалось скопировать ${src.name}") }
    }

    fun move(src: File, dst: File) {
        if (src.renameTo(dst)) return
        copy(src, dst)
        src.deleteRecursively()
    }

    fun zip(files: List<File>, out: File, password: String?) {
        val z = ZipFile(out)
        val p = ZipParameters()
        if (!password.isNullOrEmpty()) {
            z.setPassword(password.toCharArray())
            p.isEncryptFiles = true
            p.encryptionMethod = EncryptionMethod.AES
        }
        files.forEach { if (it.isDirectory) z.addFolder(it, p) else z.addFile(it, p) }
    }

    // ---------- Корзина (по одной скрытой папке .FMTrash на каждом накопителе) ----------
    private fun trashRoot(ctx: Context, f: File): File {
        val root = volumes(ctx).firstOrNull { f.path.startsWith(it.root.path + "/") }?.root
            ?: Environment.getExternalStorageDirectory()
        return File(root, ".FMTrash")
    }

    fun toTrash(ctx: Context, f: File) {
        val now = System.currentTimeMillis()
        val dir = File(trashRoot(ctx, f), "${now}_${UUID.randomUUID().toString().take(6)}")
        dir.mkdirs()
        File(dir, ".meta").writeText("${f.path}\n$now")
        try { move(f, File(dir, f.name)) } catch (e: Exception) { dir.deleteRecursively(); throw e }
    }

    fun trashItems(ctx: Context): List<TrashItem> =
        volumes(ctx).flatMap { File(it.root, ".FMTrash").listFiles()?.toList() ?: emptyList() }
            .filter { it.isDirectory }.map { TrashItem(it) }.filter { it.file != null }.sortedByDescending { it.time }

    fun restore(t: TrashItem) {
        val src = t.file ?: return
        val target = unique(t.original)
        target.parentFile?.mkdirs()
        move(src, target)
        t.dir.deleteRecursively()
    }

    fun emptyTrash(ctx: Context) = trashItems(ctx).forEach { it.dir.deleteRecursively() }

    fun autoClean(ctx: Context) {
        val days = Prefs.trashDays
        if (days <= 0) return
        val limit = System.currentTimeMillis() - days * 86_400_000L
        trashItems(ctx).filter { it.time < limit }.forEach { it.dir.deleteRecursively() }
    }
}
