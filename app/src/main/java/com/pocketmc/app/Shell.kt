@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
package com.pocketmc.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.RowScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import kotlin.concurrent.thread

private class Dest(val icon: String, val label: String)
private val DESTS = listOf(
    Dest("◉", "Sunucu"), Dest("›_", "Konsol"), Dest("♟", "Oyuncular"), Dest("▦", "Modlar"),
    Dest("◈", "Dünya"), Dest("⌁", "Bağlantı"), Dest("⚙", "Profil"))

@Composable fun AppInner() {
    val ctx = LocalContext.current
    val installed by Setup.installed.collectAsState()
    val running by Srv.running.collectAsState()
    val sp by Srv.progress.collectAsState()
    val st by Srv.stageText.collectAsState()
    LaunchedEffect(Unit) { Setup.refresh(ctx) }
    var sel by rememberSaveable { mutableIntStateOf(0) }
    if (!installed) {
        Column(Modifier.fillMaxSize()) { CrashCard(); SetupScreen() }
        return
    }
    Row(Modifier.fillMaxSize()) {
        Rail(sel) { sel = it }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            TopBar(DESTS[sel].label)
            CrashCard()
            AnimatedVisibility(running && sp < 1f) { RainbowBar((sp * 2f).coerceAtMost(1f), st) { sel = 1 } }
            AnimatedContent(sel, Modifier.weight(1f), transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(90)) }, label = "screen") { i ->
                when (i) {
                    0 -> HomeScreen { sel = it }
                    1 -> ConsoleScreen()
                    2 -> PlayersScreen()
                    3 -> ModsScreen()
                    4 -> WorldScreen()
                    5 -> ConnTab()
                    else -> ProfileScreen()
                }
            }
        }
    }
}

@Composable private fun Rail(sel: Int, onSel: (Int) -> Unit) {
    val ip = remember { localIp() }
    Column(Modifier.fillMaxHeight().width(96.dp).background(RailBg).padding(horizontal = 6.dp, vertical = 10.dp)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(Green), contentAlignment = Alignment.Center) {
                Text("▦", fontSize = 14.sp, color = Ui.accent.on)
            }
            Text(buildAnnotatedString { append("Pocket"); withStyle(SpanStyle(color = Green)) { append("MC") } },
                fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DESTS.forEachIndexed { i, d ->
                val on = sel == i
                val bg by animateColorAsState(if (on) Ui.accent.deep else Color.Transparent, label = "nav")
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(bg).clickable { onSel(i) }.padding(horizontal = 5.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(d.icon, fontSize = 11.sp, color = if (on) Green else Muted, modifier = Modifier.width(14.dp), textAlign = TextAlign.Center)
                    Text(d.label, fontSize = 10.sp, fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = if (on) Green else Muted, maxLines = 1, softWrap = false)
                }
            }
        }
        Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(6.dp).background(Ok, CircleShape))
            Text(ip ?: "Çevrimdışı", fontSize = 9.sp, color = Muted, maxLines = 1)
        }
    }
}

@Composable private fun TopBar(title: String) {
    val ctx = LocalContext.current
    val running by Srv.running.collectAsState()
    val status by Srv.status.collectAsState()
    val busy = status.endsWith("…")
    val pulse = rememberInfiniteTransition(label = "p").animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    val dot by animateColorAsState(if (running) Ok else Color(0xFF7C847F), label = "dot")
    val btn by animateColorAsState(if (running) Red else Green, label = "btn")
    Row(Modifier.fillMaxWidth().height(46.dp).background(Color(0x66101B19)).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
        Text("  / PocketMC", fontSize = 11.sp, color = Muted)
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(7.dp).graphicsLayer { alpha = if (running || busy) pulse.value else 1f }.background(dot, CircleShape))
        Text("  $status", fontSize = 11.sp, color = Muted, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        Button({ if (running) stopServer(ctx) else startServer(ctx) }, enabled = running || !busy,
            colors = ButtonDefaults.buttonColors(containerColor = btn, contentColor = Ui.accent.on)) {
            Text(if (running) "■ Durdur" else "▶ Başlat", fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

@Composable private fun rememberStat(key: Int = 0): State<Stat> {
    val ctx = LocalContext.current
    val status by Srv.status.collectAsState()
    val s = remember { mutableStateOf(Stat()) }
    LaunchedEffect(status, key) {
        while (true) {
            s.value = kotlinx.coroutines.withContext(Dispatchers.IO) { Stats.sample(ctx, status == "Çalışıyor") }
            kotlinx.coroutines.delay(if (status == "Kapalı") 15000 else 8000)
        }
    }
    return s
}

@Composable private fun StatTile(label: String, value: String, mod: Modifier, color: Color = Green, spin: Boolean = false, onClick: (() -> Unit)? = null) {
    val ang by rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "ang")
    val sh = RoundedCornerShape(10.dp)
    var m = mod.clip(sh).background(Panel.copy(alpha = Ui.cardAlpha.coerceAtLeast(0.25f))).border(1.dp, Line, sh)
    if (onClick != null) m = m.clickable { onClick() }
    if (spin) m = m.drawWithContent {
        drawContent()
        val cols = Rainbow.map { it.toArgb() }.toIntArray()
        val sw = android.graphics.SweepGradient(size.width / 2f, size.height / 2f, cols, null)
        val mx = android.graphics.Matrix(); mx.setRotate(ang, size.width / 2f, size.height / 2f); sw.setLocalMatrix(mx)
        drawRoundRect(ShaderBrush(sw), cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()), style = Stroke(2.dp.toPx()))
    }
    Column(m.padding(10.dp)) {
        Text(label, fontSize = 10.sp, color = Muted)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = color)
    }
}

@Composable fun Eyebrow(t: String) = Text(t, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = Green, letterSpacing = 0.8.sp)

// ---------- Sunucu (ana ekran) ----------
@Composable private fun HomeScreen(onNav: (Int) -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val running by Srv.running.collectAsState()
    val status by Srv.status.collectAsState()
    val ta by Tunnel.addr.collectAsState()
    var statKey by remember { mutableIntStateOf(0) }
    var spin by remember { mutableStateOf(false) }
    LaunchedEffect(statKey) { if (statKey > 0) { spin = true; kotlinx.coroutines.delay(3000); spin = false } }
    val stat by rememberStat(statKey)
    val ip = remember { localIp() }
    val ram = Srv.prefs(ctx).getInt("ram", 2048)
    Row(Modifier.fillMaxSize().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1.4f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(Modifier.fillMaxWidth().animateContentSize()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Eyebrow("SUNUCUYA GENEL BAKIŞ") }
                        Text("FABRIC · ${Setup.MC}", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = Green,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Ui.accent.deep).padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                    Row {
                        Text("Dünyan ", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                        Text(if (running) "açık." else if (status.endsWith("…")) "hazırlanıyor…" else "hazır.", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Green)
                    }
                    val a = "${ip ?: "—"}:${Srv.port(ctx)}"
                    Text(if (running) "Arkadaşların $a adresinden bağlanabilir." else "Sunucuyu başlat, arkadaşlarınla oynamaya başla.",
                        fontSize = 12.sp, color = Muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Button({ if (running) stopServer(ctx) else startServer(ctx) }, enabled = running || !status.endsWith("…"),
                            colors = ButtonDefaults.buttonColors(containerColor = if (running) Red else Green, contentColor = Ui.accent.on)) {
                            Text(if (running) "■ Sunucuyu durdur" else "▶ Sunucuyu başlat", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        }
                        OutlinedButton({ onNav(1) }) { Text("Günlüğü aç", fontSize = 12.sp) }
                        OutlinedButton({ clip.setText(AnnotatedString(a)); Srv.toast(ctx, "Adres kopyalandı") }) { Text("Adresi kopyala", fontSize = 12.sp) }
                    }
                    ta?.let { t -> Text("İnternet adresi: $t", fontSize = 11.sp, color = Green,
                        modifier = Modifier.clickable { clip.setText(AnnotatedString(t)); Srv.toast(ctx, "Kopyalandı") }) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("OYUNCU", if (stat.max > 0) "${stat.on}/${stat.max}" else "—", Modifier.weight(1f), Green, spin) { statKey++ }
                StatTile("SUNUCU RAM", if (stat.rssMb > 0) "${stat.rssMb} MB" else "—", Modifier.weight(1f))
                if (stat.sleepSec > 0) StatTile("UYKU 💤", fmtUp(stat.sleepSec), Modifier.weight(1f), Green)
                else StatTile("SÜRE", if (stat.upSec > 0) fmtUp(stat.upSec) else "—", Modifier.weight(1f))
            }
            if (stat.names.isNotEmpty()) Text("Çevrimiçi: " + stat.names.joinToString(", "), fontSize = 12.sp, color = Muted)
            RequestsCard { Srv.toast(ctx, it) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SleepCard()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Sunucu bilgisi", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                    InfoRow("Aktif dünya", Srv.worldLabel(Srv.activeWorld(ctx)).substringBefore(" ("))
                    InfoRow("Ayrılan RAM", if (ram % 1024 == 0) "${ram / 1024} GB" else "$ram MB")
                    InfoRow("Telefon boş RAM", if (stat.totMb > 0) "${stat.freeMb} / ${stat.totMb} MB" else "—")
                    InfoRow("Cihaz sıcaklığı", if (stat.tempC > 0) "${"%.0f".format(stat.tempC)} °C" else "—", if (stat.tempC >= 42f) Red else Color.Unspecified)
                    if (stat.tempC >= 42f) Text("Telefon ısınıyor: şarjı kesin, kılıfı çıkarın.", fontSize = 11.sp, color = Red)
                }
            }
        }
    }
}

@Composable private fun InfoRow(k: String, v: String, color: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, Modifier.weight(1f), fontSize = 11.sp, color = Muted)
        Text(v, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = color)
    }
}

// ---------- Konsol ----------
@Composable private fun ConsoleScreen() {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val lines by Srv.log.collectAsState()
    val shown = remember(lines.size) { lines.takeLast(300) }
    val st = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(shown.size, follow) { if (follow && shown.isNotEmpty()) st.scrollToItem(shown.size - 1) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) thread {
            try { ctx.contentResolver.openInputStream(u)!!.use { Srv.importServer(ctx, it) } }
            catch (e: Throwable) { Srv.add("[APK] HATA: $e") }
        }
    }
    Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1.5f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton({ clip.setText(AnnotatedString(Srv.fullLog())); Srv.toast(ctx, "Günlük kopyalandı") }) { Text("Kopyala") }
                OutlinedButton({ shareText(ctx, Srv.fullLog()) }) { Text("Paylaş") }
                OutlinedButton({ Srv.clearLog() }) { Text("Temizle") }
                OutlinedButton({ pick.launch(arrayOf("*/*")) }) { Text("Sunucuyu içe aktar") }
                FilterChip(follow, { follow = !follow }, label = { Text("Otomatik kaydır") })
            }
            Card(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(Modifier.fillMaxSize().padding(8.dp), state = st) {
                    items(shown.size) { i ->
                        Text(shortLine(shown[i]), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, color = lineColor(shown[i]))
                    }
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { CommandPanel() }
    }
}

// ---------- Oyuncular ----------
@Composable private fun PlayersScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by Srv.running.collectAsState()
    var name by remember { mutableStateOf("") }
    var online by remember { mutableStateOf(listOf<String>()) }
    var msg by remember { mutableStateOf("") }
    var confirmOp by remember { mutableStateOf(false) }
    val valid = Regex("[A-Za-z0-9_]{3,16}").matches(name.trim())
    fun run(cmd: String) {
        scope.launch(Dispatchers.IO) { msg = Srv.rcon(ctx, cmd)?.ifBlank { "Tamam" } ?: "RCON'a ulaşılamadı (sunucu 'Çalışıyor' olmalı)" }
    }
    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val r = Srv.rcon(ctx, "list")
            if (r == null) msg = "RCON'a ulaşılamadı"
            else online = r.substringAfter(":", "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
    Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!running) Text("Sunucu kapalı. Whitelist ekleme çalışır, diğer komutlar için önce başlatın.", color = Amber, fontSize = 12.sp)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Çevrimiçi (${online.size})", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        OutlinedButton({ refresh() }, enabled = running) { Text("Yenile") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { online.forEach { p -> AssistChip({ name = p }, label = { Text(p) }) } }
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Oyuncu adı") },
                        isError = name.isNotEmpty() && !valid,
                        supportingText = { if (name.isNotEmpty() && !valid) Text("3-16 karakter; harf, rakam, _ (boşluk yok)") })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button({ scope.launch(Dispatchers.IO) { msg = WL.add(ctx, name.trim()); Srv.removeRequest(name.trim()) } }, enabled = valid) { Text("Whitelist'e ekle") }
                        OutlinedButton({ run("whitelist remove ${name.trim()}") }, enabled = valid && running) { Text("Whitelist'ten çıkar") }
                        Button({ confirmOp = true }, enabled = valid && running) { Text("OP ver") }
                        OutlinedButton({ run("deop ${name.trim()}") }, enabled = valid && running) { Text("OP al") }
                        OutlinedButton({ run("kick ${name.trim()}") }, enabled = valid && running) { Text("At") }
                        OutlinedButton({ run("ban ${name.trim()}") }, enabled = valid && running) { Text("Yasakla") }
                        OutlinedButton({ run("pardon ${name.trim()}") }, enabled = valid && running) { Text("Yasağı kaldır") }
                    }
                    AnimatedVisibility(msg.isNotEmpty()) { Text(msg, fontSize = 12.sp, color = Green) }
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RequestsCard { msg = it }
            ListsCard(msg) { c -> run(c) }
        }
    }
    if (confirmOp) AlertDialog(onDismissRequest = { confirmOp = false },
        title = { Text("OP vermek istiyor musunuz?") },
        text = { Text("online-mode kapalı: adı bilen herkes bu adla girebilir. Whitelist açıkken OP verin, yoksa herkes yönetici olabilir.") },
        confirmButton = { TextButton({ confirmOp = false; run("op ${name.trim()}") }) { Text("OP ver") } },
        dismissButton = { TextButton({ confirmOp = false }) { Text("Vazgeç") } })
}

// ---------- Modlar ----------
@Composable private fun ModsScreen() {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Modlar", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedButton({ Srv.showCfg.value = true }) { Text("Yapılandırma dosyaları") }
        }
        Box(Modifier.weight(1f)) { ModsTab2() }
    }
}

// ---------- Dünya ----------
@Composable private fun WorldScreen() {
    var rev by remember { mutableIntStateOf(0) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.weight(1.2f)) { key(rev) { WorldsHome() } }
        Box(Modifier.weight(1f)) { WorldBubbles { rev++ } }
    }
}

// ---------- Profil ----------
@Composable private fun ProfileScreen() {
    val ctx = LocalContext.current
    val d = Srv.serverDir(ctx)
    val defName = remember { Lists.names(File(d, "ops.json")).firstOrNull() ?: Lists.names(File(d, "whitelist.json")).firstOrNull() ?: "Oyuncu" }
    var player by remember { mutableStateOf(Srv.prefs(ctx).getString("owner", null) ?: defName) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val pf = File(d, "server.properties")
    val m = remember { mutableStateMapOf<String, String>().apply { putAll(Srv.readProps(pf)) } }
    var ram by remember { mutableIntStateOf(Srv.prefs(ctx).getInt("ram", 2048)) }
    var adv by remember { mutableStateOf(false) }
    val running by Srv.running.collectAsState()
    val clip = LocalClipboardManager.current
    fun profile(v: Int, s: Int, p: Int) { m["view-distance"] = "$v"; m["simulation-distance"] = "$s"; m["max-players"] = "$p" }
    Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(Ui.accent.deep).border(2.dp, Green, CircleShape), contentAlignment = Alignment.Center) {
                        Text(player.take(1).uppercase(), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Green)
                    }
                    Column(Modifier.weight(1f).animateContentSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (editing) {
                            OutlinedTextField(draft, { draft = it.take(20) }, singleLine = true, label = { Text("Sunucu sahibi adı") })
                            Row {
                                TextButton({
                                    if (draft.isNotBlank()) { player = draft.trim(); Srv.prefs(ctx).edit().putString("owner", player).apply() }
                                    editing = false
                                }) { Text("Kaydet") }
                                TextButton({ editing = false }) { Text("Vazgeç") }
                            }
                        } else {
                            Text(player, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Sunucu sahibi", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Minecraft ${Setup.MC} · Fabric", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton({ draft = player; editing = true }) { Text("İsmi değiştir") }
                        }
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Görünüm", style = MaterialTheme.typography.titleSmall, color = Green)
                    Text("Tema rengi", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Accent.values().forEach { a ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(42.dp).clip(CircleShape).background(a.c)
                                    .border(if (Ui.accent == a) 3.dp else 0.dp, Color.White, CircleShape)
                                    .clickable { Ui.accent = a; Ui.save(ctx) })
                                Text(a.label, fontSize = 11.sp)
                            }
                        }
                    }
                    val pickBg = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
                        if (u != null) thread { Ui.setBg(ctx, u) }
                    }
                    Text("Arka plan resmi", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton({ pickBg.launch(arrayOf("image/*")) }) { Text(if (Ui.bg == null) "Resim seç" else "Resmi değiştir") }
                        if (Ui.bg != null) TextButton({ Ui.clearBg(ctx) }) { Text("Kaldır", color = Red) }
                    }
                    if (Ui.bg != null) {
                        Text("Arka plan görünürlüğü: %${((1f - Ui.dim) * 100).toInt()}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(1f - Ui.dim, { Ui.dim = 1f - it }, valueRange = 0.05f..1f, onValueChangeFinished = { Ui.save(ctx) })
                    }
                    Text("Çerçeve saydamlığı: %${((1f - Ui.cardAlpha) * 100).toInt()}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(1f - Ui.cardAlpha, { Ui.cardAlpha = 1f - it }, valueRange = 0f..1f, onValueChangeFinished = { Ui.save(ctx) })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Çerçeveleri blur yap")
                            Text(if (Ui.bg == null) "Önce arka plan resmi seçin" else "Çerçevelerin arkası bulanık görünür", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(Ui.cardBlur, { Ui.cardBlur = it; Ui.save(ctx) }, enabled = Ui.bg != null)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Renkli ışık efekti")
                            Text("Köşelerde vurgu renginde parıltı", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(Ui.glass, { Ui.glass = it; Ui.save(ctx) })
                    }
                }
            }
            FilesCard()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Sorun giderme", style = MaterialTheme.typography.titleSmall, color = Green)
                    Text("Bir sorun olursa raporu kopyalayın ya da paylaşın.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ clip.setText(AnnotatedString(Diag.report(ctx))); Srv.toast(ctx, "Rapor kopyalandı") }) { Text("Raporu kopyala") }
                        OutlinedButton({ shareText(ctx, Diag.report(ctx)) }) { Text("Paylaş") }
                    }
                }
            }
        }
        Column(Modifier.weight(1.2f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            VersionCard()
            Card(Modifier.fillMaxWidth().animateContentSize()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Performans", style = MaterialTheme.typography.titleSmall, color = Green)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip({ profile(4, 4, 5) }, label = { Text("Hafif") })
                        AssistChip({ profile(6, 5, 10) }, label = { Text("Dengeli") })
                        AssistChip({ profile(6, 10, 10) }, label = { Text("Farm odaklı") })
                    }
                    Text("Sunucuya ayrılan RAM", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1024, 1536, 2048, 3072).forEach { mb ->
                            FilterChip(ram == mb, { ram = mb }, label = { Text(if (mb % 1024 == 0) "${mb / 1024} GB" else "${mb / 1024.0} GB") })
                        }
                    }
                    AnimatedVisibility(ram >= 3072) { Text("3 GB: oyun açıkken Android sunucuyu öldürebilir.", color = Amber, fontSize = 12.sp) }
                }
            }
            Card(Modifier.fillMaxWidth().animateContentSize()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Oyun", style = MaterialTheme.typography.titleSmall, color = Green)
                    PField("Sunucu adı (motd)", "motd", m, false)
                    PField("En fazla oyuncu", "max-players", m)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Whitelist")
                            Text("Sadece listedekiler girebilir", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(m["white-list"] == "true", { v -> m["white-list"] = v.toString() })
                    }
                    AnimatedVisibility(m["white-list"] != "true") { Text("Whitelist kapalıyken herkes girebilir.", color = Amber, fontSize = 12.sp) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("PvP (oyuncular birbirine vurabilir)", Modifier.weight(1f))
                        Switch(m["pvp"] != "false", { v -> m["pvp"] = v.toString() })
                    }
                }
            }
            Card(Modifier.fillMaxWidth().animateContentSize(), onClick = { adv = !adv }) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Gelişmiş", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = Green)
                        Text(if (adv) "▲" else "▼")
                    }
                    if (adv) {
                        PField("Görüş mesafesi (view-distance)", "view-distance", m)
                        PField("Simülasyon mesafesi (simulation-distance)", "simulation-distance", m)
                        PField("Sunucu portu (varsayılan 25565)", "server-port", m)
                    }
                }
            }
            Button({
                Srv.serverDir(ctx).mkdirs(); Srv.writeProps(pf, m.toMap())
                Srv.prefs(ctx).edit().putInt("ram", ram).apply()
                Srv.toast(ctx, if (running) "Kaydedildi, yeniden başlatınca geçerli" else "Kaydedildi")
            }, Modifier.fillMaxWidth()) { Text("Kaydet") }
        }
    }
}
