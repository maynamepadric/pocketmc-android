package com.pocketmc.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

val Green = Color(0xFF5DBB63)
val Red = Color(0xFFE5645A)
val Amber = Color(0xFFE0B341)

class MainActivity : ComponentActivity() {
    private fun handleLink(i: Intent?) {
        val d = i?.data ?: return
        if (d.scheme != "pmc") return
        val c = d.getQueryParameter("c") ?: return
        val r = d.getQueryParameter("r")
        val p = Link.parse(if (r != null) "PMC1|$c|$r" else c) ?: return
        val same = Link.code(this) == p.first
        if (!same) Link.save(this, p.first, p.second)
        startForegroundService(Intent(this, Watch::class.java).setAction(if (same) null else "RESET"))
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); handleLink(i); recreate() }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Link.load(this)
        handleLink(intent)
        if (Link.code(this) != null) startForegroundService(Intent(this, Watch::class.java))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Green, background = Color(0xFF0F1412), surface = Color(0xFF171E1B),
                surfaceVariant = Color(0xFF222B27), onSurfaceVariant = Color(0xFFA9B5AF))) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Root() }
            }
        }
    }
}

fun findCode(s: String?): String? {
    if (s == null) return null
    Regex("pmc://join\\?\\S+").find(s)?.let {
        val u = Uri.parse(it.value); val c = u.getQueryParameter("c") ?: return null
        val r = u.getQueryParameter("r"); return if (r != null) "PMC1|$c|$r" else c
    }
    Regex("PMC1\\|[A-Za-z0-9-]+\\|\\S+").find(s)?.let { return it.value }
    Regex("[A-Za-z0-9]{4}-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}").find(s)?.let { return it.value }
    return null
}

fun toast(c: android.content.Context, s: String) { Handler(Looper.getMainLooper()).post { Toast.makeText(c, s, Toast.LENGTH_SHORT).show() } }

@Composable fun Root() {
    val ctx = LocalContext.current
    var paired by remember { mutableStateOf(Link.code(ctx) != null) }
    if (!paired) PairScreen { paired = true } else HomeScreen { paired = false }
}

@Composable fun PairScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    val clipped = remember { findCode(clip.getText()?.text) }
    fun pairWith(s: String) {
        val p = Link.parse(findCode(s) ?: s)
        if (p == null) toast(ctx, "Kod geçersiz (12 harf/rakam)")
        else { Link.save(ctx, p.first, p.second); ctx.startForegroundService(Intent(ctx, Watch::class.java)); onDone() }
    }
    Column(Modifier.fillMaxSize().padding(24.dp).statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
        Text("PocketMC For Players", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Sunucu sahibinden eşleşme kodunu iste ve buraya yapıştır. Bir kez eşleşince adres, duyuru ve durum otomatik gelir.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (clipped != null) Button({ pairWith(clipped) }, Modifier.fillMaxWidth()) { Text("Panodaki kodla eşleş") }
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Eşleşme kodu (K7QX-M2TD-9PRW)") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ clip.getText()?.let { text = findCode(it.text) ?: it.text } }) { Text("Yapıştır") }
            Button({ pairWith(text) }) { Text("Eşleş") }
        }
    }
}

private fun fmtLeft(s: Long): String = if (s <= 0) "0" else if (s < 3600) "${s / 60} dk ${s % 60} sn" else "${s / 3600} sa ${(s % 3600) / 60} dk"

@Composable fun HomeScreen(onUnpair: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val st by St.status.collectAsState()
    val anns by St.anns.collectAsState()
    val link by St.link.collectAsState()
    val chatList by St.chat.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis() } }
    var nick by remember { mutableStateOf(Link.prefs(ctx).getString("nick", "") ?: "") }
    var chat by remember { mutableStateOf("") }
    val nickOk = Regex("[A-Za-z0-9_]{3,16}").matches(nick)
    fun send(j: JSONObject, okMsg: String) {
        if (nickOk) Link.prefs(ctx).edit().putString("nick", nick).apply()
        thread { toast(ctx, if (Link.post(ctx, j.put("name", nick).put("ts", System.currentTimeMillis()))) okMsg else "Gönderilemedi (internet?)") }
    }
    val j = st
    val sleeping = j?.optBoolean("sleep") == true
    val state = if (j == null) "Henüz bilgi yok" else if (sleeping) "Uyuyor 💤" else j.optString("st")
    val pct = j?.optInt("pct", -1) ?: -1
    val boot = j?.optInt("boot", 90) ?: 90
    val addr = j?.optString("addr", "")?.takeIf { it.isNotEmpty() }
    val left = if (j != null && addr != null) (j.optLong("exp", 0L) - now) / 1000 else 0L
    val agoMin = if (j != null) (now - j.optLong("ts", now)) / 60000 else 0L
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("PocketMC For Players", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("SUNUCU", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = if (state == "Çalışıyor") Green else Color.Unspecified)
                if (pct >= 0) {
                    LinearProgressIndicator(progress = { pct / 100f }, Modifier.fillMaxWidth())
                    Text("Açılıyor: ~%$pct (tahmini toplam ~$boot sn)", fontSize = 12.sp)
                }
                if (j != null && j.optInt("max") > 0) Text("Oyuncu: ${j.optInt("on")}/${j.optInt("max")}   •   MC ${j.optString("mc")}", fontSize = 13.sp)
                if (j != null && agoMin >= 45) Text("Veri eski olabilir ($agoMin dk önce)", fontSize = 12.sp, color = Amber)
                Text("Bağlantı: $link", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (sleeping) Button({ send(JSONObject().put("t", "wake"), "Uyandırma isteği gönderildi") }, Modifier.fillMaxWidth()) { Text("Sunucuyu uyandır") }
            }
        }
        if (addr != null) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("SUNUCU ADRESİ", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(addr, fontFamily = FontFamily.Monospace, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Green)
                Text(if (left > 0) "Adres yaklaşık ${fmtLeft(left)} geçerli" else "Adres süresi doldu, yenisi bekleniyor", fontSize = 12.sp,
                    color = if (left in 1..299) Amber else MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({ clip.setText(AnnotatedString(addr)); toast(ctx, "Kopyalandı") }) { Text("Kopyala") }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sunucuya istek", fontWeight = FontWeight.Medium)
                OutlinedTextField(nick, { nick = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Oyun içi adın") },
                    isError = nick.isNotEmpty() && !nickOk, supportingText = { if (nick.isNotEmpty() && !nickOk) Text("3-16 karakter; harf, rakam, _") })
                OutlinedButton({ send(JSONObject().put("t", "wl"), "Whitelist isteği gönderildi") }, enabled = nickOk) { Text("Whitelist iste") }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Sohbet (sunucu sahibi ve eşleşenler)", fontWeight = FontWeight.Medium)
                if (chatList.isEmpty()) Text("Henüz mesaj yok.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                chatList.takeLast(15).forEach { m ->
                    Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.ts)) + "  " + m.name + ": " + m.text, fontSize = 13.sp)
                }
                OutlinedTextField(chat, { chat = it.take(120) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(if (nickOk) "Mesaj" else "Önce oyun içi adını yaz (üstte)") })
                OutlinedButton({ send(JSONObject().put("t", "chat").put("text", chat), "Mesaj gönderildi"); chat = "" }, enabled = nickOk && chat.isNotBlank()) { Text("Gönder") }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Duyurular", fontWeight = FontWeight.Medium)
                if (anns.isEmpty()) Text("Henüz duyuru yok.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                anns.take(10).forEach { a ->
                    Text(SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(a.ts)) + "  " + a.text, fontSize = 13.sp)
                }
            }
        }
        OutlinedButton({
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
        }) { Text("Arka plan koruması (pil muafiyeti)") }
        TextButton({ ctx.startService(Intent(ctx, Watch::class.java).setAction("STOP")); Link.unpair(ctx); onUnpair() }) { Text("Eşleşmeyi kaldır", color = Red) }
    }
}
