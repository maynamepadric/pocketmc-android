@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.pocketmc.app

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

// PocketMC For Players ile haberleşme: ntfy tipi bir aracı servis (konu adı = gizli eşleşme kodu)
// -s konusu: sunucu -> oyuncular (durum, duyuru, sohbet) ; -p konusu: oyuncular -> sunucu (istek, sohbet)
object Relay {
    const val DEFAULT = "https://ntfy.sh"
    @Volatile private var conn: HttpURLConnection? = null
    @Volatile private var running = false
    private var lastSig = ""
    private var lastPost = 0L
    private var lastOn = 0
    private var lastMax = 0
    val chat = MutableStateFlow<List<String>>(emptyList())

    private fun pf(c: Context) = Srv.prefs(c)
    fun enabled(c: Context) = pf(c).getBoolean("relay_on", false)
    fun base(c: Context) = (pf(c).getString("relay", DEFAULT) ?: DEFAULT).trim().trimEnd('/')

    private fun newCode(): String {
        val a = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"; val r = SecureRandom()
        return (0 until 12).map { a[r.nextInt(a.length)] }.joinToString("").chunked(4).joinToString("-")
    }
    fun code(c: Context): String {
        var s = pf(c).getString("pair", null)
        if (s == null) { s = newCode(); pf(c).edit().putString("pair", s).apply() }
        return s
    }
    fun regen(c: Context) { pf(c).edit().putString("pair", newCode()).remove("relay_since").apply(); lastSig = ""; chat.value = emptyList() }
    fun pairString(c: Context) = if (base(c) == DEFAULT) code(c) else "PMC1|${code(c)}|${base(c)}"
    fun link(c: Context) = "pmc://join?c=" + code(c) + (if (base(c) != DEFAULT) "&r=" + URLEncoder.encode(base(c), "UTF-8") else "")
    private fun topic(c: Context, suf: String) = "pmc-${code(c)}-$suf"

    fun post(c: Context, suf: String, j: JSONObject): Boolean = try {
        val cn = URL("${base(c)}/${topic(c, suf)}").openConnection() as HttpURLConnection
        cn.requestMethod = "POST"; cn.doOutput = true; cn.connectTimeout = 10000; cn.readTimeout = 15000
        cn.outputStream.use { it.write(j.toString().toByteArray()) }
        val ok = cn.responseCode in 200..299
        cn.disconnect(); ok
    } catch (_: Exception) { false }

    fun announce(c: Context, text: String) {
        if (enabled(c)) post(c, "s", JSONObject().put("t", "msg").put("text", text).put("ts", System.currentTimeMillis()))
        if (Srv.running.value) Srv.rcon(c, "say " + text.replace(Regex("[\\r\\n]"), " "))
    }

    private fun addChat(name: String, text: String) {
        val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        chat.value = (chat.value + "$t $name: $text").takeLast(40)
    }

    // Sohbet: sunucu uygulaması ile For Players kullanıcıları arasında. Oyun sohbetine aktarma isteğe bağlı.
    fun sendChat(c: Context, name: String, text: String) {
        addChat(name, text)
        if (enabled(c)) post(c, "s", JSONObject().put("t", "chat").put("name", name).put("text", text).put("ts", System.currentTimeMillis()))
        if (pf(c).getBoolean("relay_ingame", false) && Srv.running.value) Srv.rcon(c, "say [$name] $text")
    }

    @Synchronized private fun publish(c: Context, force: Boolean) {
        val st = Srv.status.value
        val pct = if (st.startsWith("Başlıyor")) (Srv.progress.value * 100).toInt() / 10 * 10 else -1
        val addr = Tunnel.addr.value
        val sig = "$st|$pct|$addr|${Tunnel.expires.value}|$lastOn"
        val now = System.currentTimeMillis()
        if (!force && sig == lastSig && now - lastPost < 20 * 60000L) return
        val j = JSONObject().put("t", "status").put("st", st).put("sleep", Srv.sleeping.value).put("pct", pct)
            .put("on", lastOn).put("max", lastMax).put("mc", Setup.MC).put("boot", pf(c).getInt("boot_s", 90)).put("ts", now)
        if (addr != null) { j.put("addr", addr); j.put("exp", Tunnel.expires.value); j.put("sid", Tunnel.expires.value) }
        if (post(c, "s", j)) { lastSig = sig; lastPost = now }
    }

    // Hizmet içinde 15 sn'de bir çağrılır; değişiklik olunca ya da 20 dk'da bir durum yayınlar
    fun tick(c: Context, st: String, s: Stat) {
        lastOn = s.on; lastMax = s.max
        if (enabled(c)) publish(c, false)
    }

    // Tünel yeni bağlantı kurunca hemen çağrılır: yeni oturum kodu anında For Players'a gider
    fun publishNow(c: Context) { if (enabled(c)) publish(c, true) }

    private fun clean(s: String) = s.filter { it.code >= 32 && it.code != 127 }.take(120)

    private fun handle(c: Context, msg: String) {
        val j = try { JSONObject(msg) } catch (_: Exception) { return }
        val name = j.optString("name")
        if (!Regex("[A-Za-z0-9_]{3,16}").matches(name)) return
        when (j.optString("t")) {
            "wake" -> {
                val fresh = System.currentTimeMillis() - j.optLong("ts", 0L) < 120000L
                if (fresh && Srv.sleeping.value) { Srv.add("[Oyuncu] $name sunucuyu uyandırdı"); Srv.wake = true }
            }
            "wl" -> Srv.addRequest(name)
            "chat" -> { val t = clean(j.optString("text")); if (t.isNotBlank()) sendChat(c, name, t) }
        }
    }

    fun start(c: Context) {
        if (running) return
        running = true
        val app = c.applicationContext
        Thread {
            while (running) {
                if (!enabled(app)) { try { Thread.sleep(10000) } catch (_: InterruptedException) {}; continue }
                var since = pf(app).getString("relay_since", null) ?: (System.currentTimeMillis() / 1000).toString()
                try {
                    val cn = URL("${base(app)}/${topic(app, "p")}/json?since=$since").openConnection() as HttpURLConnection
                    conn = cn; cn.connectTimeout = 10000; cn.readTimeout = 120000
                    cn.inputStream.bufferedReader().forEachLine { l ->
                        val o = try { JSONObject(l) } catch (_: Exception) { return@forEachLine }
                        if (o.optString("event") != "message") return@forEachLine
                        since = o.optString("id", since)
                        pf(app).edit().putString("relay_since", since).apply()
                        handle(app, o.optString("message"))
                    }
                } catch (_: Exception) {}
                if (running) try { Thread.sleep(8000) } catch (_: InterruptedException) {}
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() { running = false; try { conn?.disconnect() } catch (_: Exception) {} }
}

@Composable fun PlayerLinkCard() {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val pf = remember { Srv.prefs(ctx) }
    var on by remember { mutableStateOf(Relay.enabled(ctx)) }
    var code by remember { mutableStateOf(Relay.pairString(ctx)) }
    var ann by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(Relay.base(ctx)) }
    var ingame by remember { mutableStateOf(pf.getBoolean("relay_ingame", false)) }
    val lines by Relay.chat.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("PocketMC For Players", style = MaterialTheme.typography.titleMedium)
                    Text("Eşleşen oyuncular adresi, duyuruları ve durumu otomatik alır; aranızda sohbet edebilirsiniz.", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(on, { on = it; pf.edit().putBoolean("relay_on", it).apply() })
            }
            if (on) {
                Text(code, fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Green)
                Row {
                    TextButton({ clip.setText(AnnotatedString(code)); Srv.toast(ctx, "Kopyalandı") }) { Text("Kopyala") }
                    TextButton({
                        val txt = "PocketMC For Players ile eşleş:\nKod: ${Relay.code(ctx)}\nBağlantı: ${Relay.link(ctx)}\n(Bu mesajı kopyalayıp uygulamayı açman yeter, kod otomatik algılanır.)"
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, txt), "Paylaş")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Paylaş") }
                    TextButton({ Relay.regen(ctx); code = Relay.pairString(ctx) }) { Text("Yeni kod", color = Red) }
                }
                Text("Kod sınırsız kişiyle ve süresiz kullanılabilir. Yeni kod üretirsen eski eşleşmelerin hepsi kopar. Kodu sadece güvendiğin kişilere ver.",
                    fontSize = 11.sp, color = Amber)
                Qr(code)
                Text("Sohbet", fontWeight = FontWeight.Medium)
                if (lines.isEmpty()) Text("Henüz mesaj yok.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                lines.takeLast(12).forEach { Text(it, fontSize = 13.sp) }
                OutlinedTextField(msg, { msg = it.take(120) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Mesaj (oyuncular uygulamasına)") })
                Button({ val t = msg.trim(); msg = ""; thread { Relay.sendChat(ctx, "Sunucu", t) } }, enabled = msg.isNotBlank()) { Text("Gönder") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sohbeti oyun sohbetine de aktar", Modifier.weight(1f), fontSize = 13.sp)
                    Switch(ingame, { ingame = it; pf.edit().putBoolean("relay_ingame", it).apply() })
                }
                OutlinedTextField(ann, { ann = it.take(120) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Duyuru (uygulamaya ve oyun sohbetine)") })
                Button({ val t = ann.trim(); ann = ""; thread { Relay.announce(ctx, t) } }, enabled = ann.isNotBlank()) { Text("Duyur") }
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Aracı servis adresi") })
                OutlinedButton({
                    pf.edit().putString("relay", url.trim().trimEnd('/').ifEmpty { Relay.DEFAULT }).apply()
                    code = Relay.pairString(ctx); Srv.toast(ctx, "Kaydedildi. Oyuncular yeni kodu girmeli.")
                }) { Text("Adresi kaydet") }
            }
        }
    }
}
