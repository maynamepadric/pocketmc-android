package com.pocketmc.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import java.io.File
import kotlin.concurrent.thread
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint

enum class Accent(val label: String, val c: Color, val on: Color, val deep: Color) {
    Green("Yeşil", Color(0xFF72DF91), Color(0xFF0D2B1A), Color(0xFF1F3B2C)),
    Red("Kırmızı", Color(0xFFFF8A83), Color(0xFF3A0D0B), Color(0xFF472320)),
    Blue("Mavi", Color(0xFF88BAFF), Color(0xFF0A2342), Color(0xFF1F3452))
}

object Ui {
    var accent by mutableStateOf(Accent.Green)
    var glass by mutableStateOf(true)
    var bg by mutableStateOf<ImageBitmap?>(null)
    var dim by mutableFloatStateOf(0.7f)          // arka plan resmini koyulaştırma (0 = tam görünür)
    var cardAlpha by mutableFloatStateOf(0.85f)   // çerçeve doluluğu (1 = opak, 0 = tam saydam)
    var cardBlur by mutableStateOf(false)         // çerçevelerin arkasını bulanıklaştır
    var blurred by mutableStateOf<ImageBitmap?>(null)
    var bgRev by mutableIntStateOf(0)
    var bgRaw: Bitmap? = null
    private fun decode(f: File): Bitmap? = try { BitmapFactory.decodeFile(f.path) } catch (_: Throwable) { null }

    // Resmi ekran boyutuna doldurup (crop) bulanıklaştırır: küçült-büyüt ile hızlı blur
    fun makeBlur(src: Bitmap, w: Int, h: Int): ImageBitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val sc = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val m = Matrix().apply { postScale(sc, sc); postTranslate((w - src.width * sc) / 2f, (h - src.height * sc) / 2f) }
        AndroidCanvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        var cur = out
        for (div in intArrayOf(10, 24)) {
            val small = Bitmap.createScaledBitmap(cur, maxOf(2, w / div), maxOf(2, h / div), true)
            cur = Bitmap.createScaledBitmap(small, w, h, true)
        }
        return cur.asImageBitmap()
    }
    fun setBg(c: Context, u: Uri) {
        try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            c.contentResolver.openInputStream(u)!!.use { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / sample > 1600) sample *= 2
            var bmp = c.contentResolver.openInputStream(u)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return
            val rot = try {
                c.contentResolver.openInputStream(u)!!.use {
                    when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
                }
            } catch (_: Throwable) { 0f }
            if (rot != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
            File(c.filesDir, "bg.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bgRaw = bmp; bg = bmp.asImageBitmap(); bgRev++
            Srv.toast(c, "Arka plan ayarlandı")
        } catch (e: Throwable) { Srv.toast(c, "Resim açılamadı") }
    }
    fun clearBg(c: Context) { File(c.filesDir, "bg.jpg").delete(); bg = null; bgRaw = null; blurred = null; bgRev++ }
    fun load(c: Context) {
        val f = File(c.filesDir, "bg.jpg")
        if (f.exists()) thread { val b = decode(f); bgRaw = b; bg = b?.asImageBitmap(); bgRev++ }
        dim = Srv.prefs(c).getFloat("bg_dim", 0.7f)
        cardAlpha = Srv.prefs(c).getFloat("card_a", 0.85f)
        cardBlur = Srv.prefs(c).getBoolean("card_blur", false)
        val p = Srv.prefs(c)
        accent = Accent.values().firstOrNull { it.name == p.getString("accent", "Green") } ?: Accent.Green
        glass = p.getBoolean("glass", true)
    }
    fun save(c: Context) { Srv.prefs(c).edit().putString("accent", accent.name).putBoolean("glass", glass).putFloat("bg_dim", dim).putFloat("card_a", cardAlpha).putBoolean("card_blur", cardBlur).apply() }
}

// Vurgu rengi: seçilen temaya göre değişir. Durum noktası gibi sabit anlamlar için Ok kullanılır.
val Green: Color get() = Ui.accent.c
val Ok = Color(0xFF72DF91)
val Line = Color(0xFF365047)
val Panel = Color(0xFF13221D)
val RailBg = Color(0xFF14231F)
val Muted = Color(0xFFA7B8AD)
val Red = Color(0xFFFF8A83)
val Amber = Color(0xFFE0B341)

@Composable fun PocketTheme(content: @Composable () -> Unit) {
    val a = Ui.accent
    val glass = Ui.glass
    val card = Color(0xFF1D2C29).copy(alpha = Ui.cardAlpha)
    val scheme = darkColorScheme(
        primary = a.c, onPrimary = a.on, primaryContainer = a.deep, onPrimaryContainer = Color(0xFFEEF7F0),
        background = Color(0xFF0A100E), surface = Color(0xFF101B19),
        surfaceVariant = Panel, onSurface = Color(0xFFEEF7F0), onSurfaceVariant = Muted, outline = Line, outlineVariant = Line,
        secondaryContainer = a.deep, onSecondaryContainer = Color(0xFFEEF7F0),
        surfaceContainerLowest = Color(0xFF0A100E), surfaceContainerLow = card, surfaceContainer = card,
        surfaceContainerHigh = Color(0xFF1D2C29), surfaceContainerHighest = card)
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(rootSize, Ui.bgRev, Ui.cardBlur) {
        val raw = Ui.bgRaw
        if (Ui.cardBlur && raw != null && rootSize.width > 0 && rootSize.height > 0)
            Ui.blurred = withContext(Dispatchers.Default) { try { Ui.makeBlur(raw, rootSize.width, rootSize.height) } catch (_: Throwable) { null } }
        else Ui.blurred = null
    }
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, minOf(d.fontScale, 1.0f))) {
    MaterialTheme(colorScheme = scheme) {
        Box(Modifier.fillMaxSize().onSizeChanged { rootSize = it }
            .background(Brush.verticalGradient(listOf(Color(0xFF0A100E), Color(0xFF0E1916))))
            .drawBehind {
                if (glass) {
                    drawRect(Brush.radialGradient(listOf(a.c.copy(alpha = 0.26f), Color.Transparent),
                        center = Offset(size.width * 0.12f, size.height * 0.05f), radius = size.maxDimension * 0.55f))
                    drawRect(Brush.radialGradient(listOf(a.c.copy(alpha = 0.14f), Color.Transparent),
                        center = Offset(size.width * 0.95f, size.height * 0.95f), radius = size.maxDimension * 0.5f))
                }
            }) {
            Ui.bg?.let { img ->
                Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color(0xFF0A100E).copy(alpha = Ui.dim)))
            }
            content()
        }
    }
    }
}
