@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
package com.pocketmc.app

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.thread

@Composable fun Tile(label: String, value: String, color: Color = Color.Unspecified, mod: Modifier = Modifier) {
    Column(mod, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

fun fmtUp(s: Long): String = if (s < 60) "${s}s" else if (s < 3600) "${s / 60}dk" else "${s / 3600}sa ${(s % 3600) / 60}dk"

@Composable fun StatsCard() {
    val ctx = LocalContext.current
    val status by Srv.status.collectAsState()
    var s by remember { mutableStateOf(Stat()) }
    LaunchedEffect(status) {
        while (true) {
            s = withContext(Dispatchers.IO) { Stats.sample(ctx, status == "Çalışıyor") }
            delay(if (status == "Kapalı") 15000 else 8000)
        }
    }
    val xmx = Srv.prefs(ctx).getInt("ram", 2048)
    val ratio = if (s.rssMb > 0) (s.rssMb.toFloat() / xmx).coerceIn(0f, 1f) else 0f
    val hot = s.tempC >= 42f
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Tile("OYUNCU", if (s.max > 0) "${s.on}/${s.max}" else "—", Green)
                Tile("SUNUCU RAM", if (s.rssMb > 0) "${s.rssMb} MB" else "—")
                if (s.sleepSec > 0) Tile("UYKU 💤", fmtUp(s.sleepSec), Green) else Tile("SÜRE", if (s.upSec > 0) fmtUp(s.upSec) else "—")
                Tile("ISI", if (s.tempC > 0) "${"%.0f".format(s.tempC)}°C" else "—", if (hot) Red else Color.Unspecified)
            }
            if (s.rssMb > 0) LinearProgressIndicator(progress = { ratio }, Modifier.fillMaxWidth().height(6.dp),
                color = if (ratio > 0.9f) Red else Green, trackColor = MaterialTheme.colorScheme.surfaceVariant)
            Text("Telefonda boş RAM: ${s.freeMb} / ${s.totMb} MB   •   Ayrılan: -Xmx$xmx MB   •   MC ${Setup.MC} Fabric",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (s.names.isNotEmpty()) Text("Çevrimiçi: " + s.names.joinToString(", "), fontSize = 12.sp)
            if (hot) Text("Telefon ${"%.0f".format(s.tempC)}°C: ısınıyor. Şarjı kesin, kılıfı çıkarın, oyunu kapatmayı düşünün.", fontSize = 12.sp, color = Red)
        }
    }
}

@Composable fun ListSection(title: String, items: List<String>, onTap: (String) -> Unit) {
    Text("$title (${items.size})", fontWeight = FontWeight.Medium)
    if (items.isEmpty()) Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
    else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { n -> AssistChip({ onTap(n) }, label = { Text("$n  ✕") }) }
    }
}

@Composable fun ListsCard(key: String, run: (String) -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(key) { delay(900); tick++ }
    val d = Srv.serverDir(ctx)
    val ops = remember(tick) { Lists.names(File(d, "ops.json")) }
    val bans = remember(tick) { Lists.names(File(d, "banned-players.json")) }
    val wl = remember(tick) { Lists.names(File(d, "whitelist.json")) }
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Yetkiler ve listeler (geri almak için ada dokunun)", style = MaterialTheme.typography.titleSmall)
            ListSection("Yetkililer (OP)", ops) { run("deop $it") }
            ListSection("Banlılar", bans) { run("pardon $it") }
            ListSection("Whitelist", wl) { run("whitelist remove $it") }
        }
    }
}

@Composable fun ModsList(onOpen: (String) -> Unit, onCfg: (File) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf(listOf<Mods.Hit>()) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        thread {
            var n = 0
            for (u in uris) {
                val name = ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null } ?: "mod.jar"
                if (!name.endsWith(".jar")) { Srv.toast(ctx, "Sadece .jar: $name"); continue }
                val dir = File(Srv.serverDir(ctx), "mods"); dir.mkdirs()
                ctx.contentResolver.openInputStream(u)?.use { i -> File(dir, File(name).name).outputStream().use { o -> i.copyTo(o) } }
                n++
            }
            if (n > 0) Srv.toast(ctx, "$n mod eklendi (yeniden başlatınca yüklenir)")
            tick++
        }
    }
    val files = remember(tick) {
        File(Srv.serverDir(ctx), "mods").listFiles()
            ?.filter { it.name.endsWith(".jar") || it.name.endsWith(".jar.disabled") }?.sortedBy { it.name } ?: emptyList()
    }
    fun search() { scope.launch(Dispatchers.IO) {
        msg = "Aranıyor…"
        try { hits = Mods.search(q); msg = if (hits.isEmpty()) "Sonuç yok (${Setup.MC} için uyumlu mod bulunamadı)" else "" }
        catch (e: Throwable) { msg = "Arama hatası: ${e.message}" }
    } }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Değişiklikler sunucu yeniden başlayınca geçerli olur.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Button({ pick.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) { Text("+ Mod ekle (.jar seç)") } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Modrinth'te ara (boş = popüler)") })
                Button({ search() }) { Text("Ara") }
            }
        }
        if (msg.isNotEmpty()) item { Text(msg, color = Amber) }
        items(hits, key = { "h" + it.slug }) { h ->
            Card(onClick = { onOpen(h.slug) }) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(h.title, fontWeight = FontWeight.Medium)
                    Text(h.desc, fontSize = 12.sp, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${h.dl} indirme", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button({ scope.launch(Dispatchers.IO) {
                    busy = h.slug
                    msg = try { Mods.install(ctx, h.slug).trim() } catch (e: Throwable) { "Hata: ${e.message}" }
                    busy = null; tick++
                } }, enabled = busy == null) { Text(if (busy == h.slug) "…" else "Yükle") }
            } }
        }
        item { Text("Kurulu modlar (${files.size})", style = MaterialTheme.typography.titleMedium) }
        items(files, key = { it.name }) { f ->
            val on = f.name.endsWith(".jar")
            Row(verticalAlignment = Alignment.CenterVertically) {
            Card(onClick = { onOpen("file:" + f.path) }, modifier = Modifier.weight(1f)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.name.removeSuffix(".disabled"), fontWeight = FontWeight.Medium)
                    Text("${f.length() / 1024} KB", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(on, { f.renameTo(File(f.parentFile, if (on) f.name + ".disabled" else f.name.removeSuffix(".disabled"))); tick++ })
                TextButton({ f.delete(); tick++ }) { Text("Sil", color = Red) }
            } }
            Spacer(Modifier.width(8.dp))
            Surface(onClick = { onCfg(f) }, shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("⚙", fontSize = 20.sp) }
            }
            }
        }
    }
}

@Composable fun Qr(text: String) {
    val m = remember(text) { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0) }
    Canvas(Modifier.size(170.dp).background(Color.White)) {
        val cell = size.width / m.width
        for (y in 0 until m.height) for (x in 0 until m.width)
            if (m.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
    }
}

@Composable fun TunnelCard() {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val addr by Tunnel.addr.collectAsState()
    val st by Tunnel.state.collectAsState()
    val exp by Tunnel.expires.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val left = if (addr != null && exp > 0) ((exp - now) / 1000).coerceAtLeast(0) else 0L
    val busy by Tunnel.on.collectAsState()
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("İnternet adresi (uzaktan bağlantı)", color = MaterialTheme.colorScheme.onSurfaceVariant)
            val a = addr
            if (a != null) {
                Text(a, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Kalan süre: ${left / 60}:${"%02d".format(left % 60)}", color = if (left < 300) Amber else Green)
                if (left < 300) Text("Süre dolmak üzere. Yenileyince adres DEĞİŞİR, arkadaşlara yenisini gönderin.", fontSize = 12.sp, color = Amber)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ clip.setText(AnnotatedString(a)); Srv.toast(ctx, "Kopyalandı") }) { Text("Kopyala") }
                    OutlinedButton({ ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "Minecraft sunucum: $a"), "Paylaş")) }) { Text("Paylaş") }
                    OutlinedButton({ Tunnel.stop(); Tunnel.start(ctx) }) { Text("Yenile") }
                }
                Qr(a)
            } else Text(st, color = if (st.startsWith("Hata")) Red else MaterialTheme.colorScheme.onSurface)
            if (busy && a == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button({ if (busy) Tunnel.stop() else Tunnel.start(ctx) }, Modifier.fillMaxWidth()) { Text(if (busy) "Tüneli kapat" else "Tüneli aç") }
            Text("Ücretsiz Pinggy: 60 dk'da kapanır, adres her seferinde değişir. Uzaktan girecek kişileri Oyuncular sekmesinden whitelist'e ekleyin (ad birebir aynı olmalı). Sunucu açıkken tüneli açın.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable fun BannerHost(mod: Modifier) {
    val b by Srv.banner.collectAsState()
    var vis by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }
    LaunchedEffect(b) {
        val x = b ?: return@LaunchedEffect
        text = x.text; err = x.err; vis = true
        delay(3500); vis = false
    }
    AnimatedVisibility(vis, mod, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        Card(colors = CardDefaults.cardColors(containerColor = if (err) Color(0xFF4A2220) else Color(0xFF1F3A27))) {
            Text(text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = if (err) Red else Green, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable fun LogDialog() {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val lines by Srv.log.collectAsState()
    val sc = rememberScrollState()
    LaunchedEffect(lines.size) { sc.scrollTo(sc.maxValue) }
    Dialog(onDismissRequest = { Srv.showLog.value = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0F0D)) {
            Column(Modifier.padding(top = 24.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("{ }  Günlük", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    TextButton({ clip.setText(AnnotatedString(Srv.fullLog())); Srv.toast(ctx, "Günlük kopyalandı") }) { Text("Kopyala") }
                    TextButton({ shareText(ctx, Srv.fullLog()) }) { Text("Paylaş") }
                    TextButton({ Srv.clearLog() }) { Text("Temizle") }
                    TextButton({ Srv.showLog.value = false }) { Text("✕", fontSize = 20.sp) }
                }
                SelectionContainer(Modifier.weight(1f)) {
                    Column(Modifier.fillMaxSize().verticalScroll(sc).padding(12.dp)) {
                        Text(lines.takeLast(400).joinToString("\n") { shortLine(it) },
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, color = Color(0xFFC9D3CC))
                    }
                }
            }
        }
    }
}

fun dirSize(f: File): Long = if (f.isFile) f.length() else (f.listFiles()?.sumOf { dirSize(it) } ?: 0L)

@Composable fun WorldDialog(folder: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val isNew = folder.isEmpty()
    val cur = remember {
        if (isNew) emptyMap() else Srv.worldProps(ctx, folder).ifEmpty { Srv.readProps(File(Srv.serverDir(ctx), "server.properties")) }
    }
    var name by remember { mutableStateOf("") }
    var gm by remember { mutableStateOf(cur["gamemode"] ?: "survival") }
    var df by remember { mutableStateOf(cur["difficulty"] ?: "easy") }
    var seed by remember { mutableStateOf(cur["level-seed"] ?: "") }
    val generated = !isNew && File(Srv.serverDir(ctx), "$folder/level.dat").exists()
    val nameOk = Regex("[A-Za-z0-9_]{1,16}").matches(name) && name != "world" && !File(Srv.serverDir(ctx), "w_$name").exists()
    AlertDialog(onDismissRequest = onDone,
        title = { Text(if (isNew) "Yeni dünya" else "Dünya ayarları") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isNew) OutlinedTextField(name, { name = it.trim() }, singleLine = true, label = { Text("Ad (harf, rakam, _)") },
                isError = name.isNotEmpty() && !nameOk)
            Text("Oyun modu")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("survival", "creative", "adventure").forEach { m -> FilterChip(gm == m, { gm = m }, label = { Text(m) }) }
            }
            Text("Zorluk")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("peaceful", "easy", "normal", "hard").forEach { m -> FilterChip(df == m, { df = m }, label = { Text(m) }) }
            }
            OutlinedTextField(seed, { seed = it }, singleLine = true, enabled = !generated, label = { Text("Seed (boş = rastgele)") })
            if (generated) Text("Dünya üretildi, seed değişmez.", fontSize = 11.sp)
        } },
        confirmButton = { TextButton({
            val f = if (isNew) "w_$name" else folder
            File(Srv.serverDir(ctx), f).mkdirs()
            Srv.saveWorldProps(ctx, f, mapOf("gamemode" to gm, "difficulty" to df, "level-seed" to seed.trim()))
            onDone()
        }, enabled = !isNew || nameOk) { Text("Kaydet") } },
        dismissButton = { TextButton(onDone) { Text("Vazgeç") } })
}
