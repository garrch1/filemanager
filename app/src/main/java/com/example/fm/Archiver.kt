package com.example.fm

import net.lingala.zip4j.ZipFile
import com.github.junrar.Junrar
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.InputStream

object Archiver {
    private val exts = listOf("tar.gz", "tgz", "tar.bz2", "tbz2", "tar.xz", "txz", "zip", "jar",
        "7z", "rar", "tar", "gz", "bz2", "xz")

    fun extOf(f: File): String? = f.name.lowercase().let { n -> exts.firstOrNull { n.endsWith(".$it") } }
    fun isArchive(f: File) = extOf(f) != null

    /** Returns the folder created for the extracted content. */
    fun extract(file: File, password: String? = null): File {
        val ext = extOf(file) ?: error("Неподдерживаемый формат")
        val out = uniqueDir(File(file.parentFile, file.name.dropLast(ext.length + 1)))
        out.mkdirs()
        try {
            when (ext) {
                "zip", "jar" -> ZipFile(file).apply {
                    if (isEncrypted) {
                        require(!password.isNullOrEmpty()) { "PASSWORD" }
                        setPassword((password ?: "").toCharArray())
                    }
                }.extractAll(out.path)
                "rar" -> Junrar.extract(file, out, password)
                "7z" -> SevenZFile(file, password?.toCharArray()).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        val t = safe(out, e.name)
                        if (e.isDirectory) t.mkdirs() else { t.parentFile?.mkdirs(); t.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024); var n: Int
                            while (z.read(buf).also { n = it } > 0) o.write(buf, 0, n) } }
                    }
                }
                "tar" -> tar(TarArchiveInputStream(file.inputStream().buffered()), out)
                "tar.gz", "tgz" -> tar(TarArchiveInputStream(GzipCompressorInputStream(file.inputStream().buffered())), out)
                "tar.bz2", "tbz2" -> tar(TarArchiveInputStream(BZip2CompressorInputStream(file.inputStream().buffered())), out)
                "tar.xz", "txz" -> tar(TarArchiveInputStream(XZCompressorInputStream(file.inputStream().buffered())), out)
                "gz" -> single(GzipCompressorInputStream(file.inputStream().buffered()), File(out, file.name.dropLast(3)))
                "bz2" -> single(BZip2CompressorInputStream(file.inputStream().buffered()), File(out, file.name.dropLast(4)))
                "xz" -> single(XZCompressorInputStream(file.inputStream().buffered()), File(out, file.name.dropLast(3)))
            }
        } catch (t: Throwable) { out.deleteRecursively(); throw t }
        return out
    }

    private fun tar(s: ArchiveInputStream<*>, out: File) = s.use {
        while (true) {
            val e = it.nextEntry ?: break
            val t = safe(out, e.name)
            if (e.isDirectory) t.mkdirs() else { t.parentFile?.mkdirs(); t.outputStream().use { o -> it.copyTo(o) } }
        }
    }

    private fun single(i: InputStream, target: File) = i.use { s -> target.outputStream().use { s.copyTo(it) } }

    // Защита от Zip Slip (../ в путях)
    private fun safe(root: File, name: String): File {
        val f = File(root, name)
        require(f.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Небезопасный путь: $name" }
        return f
    }

    private fun uniqueDir(base: File): File {
        var d = base; var i = 1
        while (d.exists()) d = File(base.parentFile, "${base.name} (${i++})")
        return d
    }
}
