package com.example.fm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object Thumbs {
    private val IMG = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
    private val VID = setOf("mp4", "mkv", "3gp", "webm", "avi", "mov", "m4v", "ts")
    private val AUD = setOf("mp3", "m4a", "flac", "ogg", "wav", "aac", "opus", "wma")

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private fun key(f: File) = f.path + "|" + f.lastModified()

    fun cached(f: File): Bitmap? = cache.get(key(f))

    fun get(ctx: Context, f: File, px: Int = 160): Bitmap? {
        cache.get(key(f))?.let { return it }
        val b = load(ctx, f, px) ?: return null
        cache.put(key(f), b)
        return b
    }

    private fun load(ctx: Context, f: File, px: Int): Bitmap? = when (f.extension.lowercase()) {
        in IMG -> decode(f, px)
        in VID -> frame(f, px)
        in AUD -> cover(f, px)
        "apk" -> apkIcon(ctx, f, px)
        "pdf" -> pdf(f, px)
        else -> null
    }

    private fun scaled(b: Bitmap, px: Int): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= px) return b
        val r = px.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * r).toInt().coerceAtLeast(1), (b.height * r).toInt().coerceAtLeast(1), true)
    }

    private fun decode(f: File, px: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun frame(f: File, px: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try { r.setDataSource(f.path); r.frameAtTime?.let { scaled(it, px) } } finally { r.release() }
    }

    private fun cover(f: File, px: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.let { scaled(it, px) }
        } finally { r.release() }
    }

    private fun apkIcon(ctx: Context, f: File, px: Int): Bitmap? {
        val pm = ctx.packageManager
        val ai = pm.getPackageArchiveInfo(f.path, 0)?.applicationInfo ?: return null
        ai.sourceDir = f.path
        ai.publicSourceDir = f.path
        return ai.loadIcon(pm).toBitmap(px, px)
    }

    private fun pdf(f: File, px: Int): Bitmap? {
        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = PdfRenderer(pfd)
        try {
            if (r.pageCount == 0) return null
            val p = r.openPage(0)
            try {
                val h = (px * p.height.toFloat() / p.width).toInt().coerceAtLeast(1)
                val b = Bitmap.createBitmap(px, h, Bitmap.Config.ARGB_8888)
                b.eraseColor(android.graphics.Color.WHITE)
                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return b
            } finally { p.close() }
        } finally { r.close(); pfd.close() }
    }
}

/** Превью для любого файла: картинка/видео/обложка/иконка APK/первая страница PDF, иначе цветная плитка с расширением. */
@Composable
fun Thumb(f: File, size: Dp = 52.dp) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(Thumbs.cached(f), f.path, f.lastModified(), Prefs.thumbnails) {
        value = if (f.isFile && Prefs.thumbnails)
            withContext(Dispatchers.IO) { runCatching { Thumbs.get(ctx, f) }.getOrNull() } else null
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        val b = bmp
        when {
            b != null -> Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            f.isDirectory -> Text("📁", fontSize = 30.sp)
            Archiver.isArchive(f) -> Text("📦", fontSize = 28.sp)
            else -> {
                val ext = f.extension.lowercase()
                Box(Modifier.fillMaxSize().background(Color.hsv(ext.hashCode().mod(360).toFloat(), 0.45f, 0.55f)),
                    contentAlignment = Alignment.Center) {
                    Text(ext.take(4).uppercase().ifEmpty { "FILE" }, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
