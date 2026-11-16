package com.pocketmc.app

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONArray
import java.io.File
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import kotlin.concurrent.thread

object Clock { @Volatile var t0 = 0L; @Volatile var sleepT0 = 0L }

data class Stat(
    val on: Int = 0, val max: Int = 0, val names: List<String> = emptyList(),
    val rssMb: Long = 0, val freeMb: Long = 0, val totMb: Long = 0,
    val upSec: Long = 0, val tempC: Float = 0f, val sleepSec: Long = 0)

object Stats {
    private fun pid(p: Process): Long? = try {
        val f = p.javaClass.getDeclaredField("pid"); f.isAccessible = true; (f.get(p) as Number).toLong()
    } catch (e: Throwable) { null }

    fun sample(c: Context, ask: Boolean): Stat {
        var on = 0; var max = 0; var names = listOf<String>()
        if (ask) {
            val r = Srv.rcon(c, "list")
            if (r != null) {
                Regex("(\\d+) of a max of (\\d+)").find(r)?.let { on = it.groupValues[1].toInt(); max = it.groupValues[2].toInt() }
                names = r.substringAfter(":", "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }
        }
        var rss = 0L
        val p = Srv.proc
        if (p != null) {
            val id = pid(p)
            if (id != null) try {
                rss = (File("/proc/$id/status").readLines().firstOrNull { it.startsWith("VmRSS") }
                    ?.filter { ch -> ch.isDigit() }?.toLongOrNull() ?: 0L) / 1024
            } catch (_: Throwable) {}
        }
        val mi = ActivityManager.MemoryInfo()
        c.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        val bi = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val t = (bi?.getIntExtra("temperature", 0) ?: 0) / 10f
        val up = if (Srv.running.value && Clock.t0 > 0) (System.currentTimeMillis() - Clock.t0) / 1000 else 0L
        val sl = if (Srv.sleeping.value && Clock.sleepT0 > 0) (System.currentTimeMillis() - Clock.sleepT0) / 1000 else 0L
        return Stat(on, max, names, rss, mi.availMem / 1048576, mi.totalMem / 1048576, up, t, sl)
    }
}

object Lists {
    fun names(f: File): List<String> = try {
        if (!f.exists()) emptyList() else {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { a.getJSONObject(it).optString("name") }.filter { it.isNotEmpty() }
        }
    } catch (e: Throwable) { emptyList() }
}

class ModInfo(val id: String, val name: String, val version: String, val description: String, val authors: String, val lang: Map<String, String>)

object Mods {
    // Modun fabric.mod.json dosyasından ad, sürüm, açıklama ve dil metinlerini okur (Mod Menu da aynısını yapar)
    fun info(jar: File): ModInfo? {
        return try {
            java.util.zip.ZipFile(jar).use { z ->
                val e = z.getEntry("fabric.mod.json") ?: return null
                val o = org.json.JSONObject(z.getInputStream(e).bufferedReader().readText())
                val id = o.optString("id")
                val a = o.optJSONArray("authors")
                val authors = if (a == null) "" else (0 until a.length()).joinToString(", ") { i ->
                    val x = a.get(i); if (x is org.json.JSONObject) x.optString("name") else x.toString()
                }
                val lang = HashMap<String, String>()
                val le = z.entries().asSequence().firstOrNull { it.name.startsWith("assets/") && it.name.endsWith("/lang/en_us.json") }
                if (le != null) {
                    val lo = org.json.JSONObject(z.getInputStream(le).bufferedReader().readText())
                    for (k in lo.keys()) lang[k] = lo.optString(k)
                }
                ModInfo(id, o.optString("name", id), o.optString("version"), o.optString("description"), authors, lang)
            }
        } catch (e: Exception) { null }
    }

    class Hit(val slug: String, val title: String, val desc: String, val dl: Long)

    fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // Seçilen sürümü ve zorunlu bağımlılıklarını indirir
    fun installVersion(c: Context, v: org.json.JSONObject): String {
        val files = v.getJSONArray("files")
        var f = files.getJSONObject(0)
        for (i in 0 until files.length()) {
            val o = files.getJSONObject(i)
            if (o.optBoolean("primary", false)) { f = o; break }
        }
        val mods = File(Srv.serverDir(c), "mods"); mods.mkdirs()
        val name = File(f.getString("filename")).name
        val dest = File(mods, name)
        var out = ""
        if (!dest.exists()) {
            Net.download(f.getString("url"), dest) {}
            val want = f.optJSONObject("hashes")?.optString("sha1", "") ?: ""
            if (want.isNotEmpty() && Setup.sha1(dest) != want.lowercase()) { dest.delete(); throw java.io.IOException("sha1 uyuşmuyor: $name") }
            out += "Kuruldu: $name\n"
        } else out += "Zaten var: $name\n"
        val seen = mutableSetOf(v.optString("project_id"))
        val deps = v.optJSONArray("dependencies")
        if (deps != null) for (i in 0 until deps.length()) {
            val d = deps.getJSONObject(i)
            if (d.optString("dependency_type") == "required" && !d.isNull("project_id"))
                out += install(c, d.getString("project_id"), seen, 1)
        }
        return out
    }

    fun search(q: String): List<Hit> {
        val facets = enc("[[\"project_type:mod\"],[\"categories:fabric\"],[\"versions:${Setup.MC}\"],[\"server_side:required\",\"server_side:optional\"]]")
        val url = "https://api.modrinth.com/v2/search?limit=20&index=downloads&facets=$facets" +
            (if (q.isBlank()) "" else "&query=" + enc(q))
        val hits = org.json.JSONObject(Net.getString(url)).getJSONArray("hits")
        return (0 until hits.length()).map {
            val h = hits.getJSONObject(it)
            Hit(h.getString("slug"), h.getString("title"), h.optString("description"), h.optLong("downloads"))
        }
    }

    // Modu ve zorunlu bağımlılıklarını indirir. Sonuç satırlarını döndürür.
    fun install(c: Context, id: String, seen: MutableSet<String> = mutableSetOf(), depth: Int = 0): String {
        if (!seen.add(id) || depth > 3) return ""
        val url = "https://api.modrinth.com/v2/project/$id/version?loaders=" + enc("[\"fabric\"]") +
            "&game_versions=" + enc("[\"${Setup.MC}\"]")
        val arr = JSONArray(Net.getString(url))
        if (arr.length() == 0) return "$id: ${Setup.MC} için sürüm yok\n"
        var v = arr.getJSONObject(0)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("version_type") == "release") { v = o; break }
        }
        val files = v.getJSONArray("files")
        var f = files.getJSONObject(0)
        for (i in 0 until files.length()) {
            val o = files.getJSONObject(i)
            if (o.optBoolean("primary", false)) { f = o; break }
        }
        val mods = File(Srv.serverDir(c), "mods"); mods.mkdirs()
        val name = File(f.getString("filename")).name
        val dest = File(mods, name)
        var out = ""
        if (!dest.exists()) {
            Net.download(f.getString("url"), dest) {}
            val want = f.optJSONObject("hashes")?.optString("sha1", "") ?: ""
            if (want.isNotEmpty() && Setup.sha1(dest) != want.lowercase()) { dest.delete(); throw java.io.IOException("sha1 uyuşmuyor: $name") }
            out += "Kuruldu: $name\n"
        } else out += "Zaten var: $name\n"
        val deps = v.optJSONArray("dependencies")
        if (deps != null) for (i in 0 until deps.length()) {
            val d = deps.getJSONObject(i)
            if (d.optString("dependency_type") == "required" && !d.isNull("project_id"))
                out += install(c, d.getString("project_id"), seen, depth + 1)
        }
        return out
    }
}

class CopyReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            "COPY" -> {
                val a = i.getStringExtra("addr") ?: return
                (c.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("kod", a))
                Srv.toast(c, "Kopyalandı: $a")
            }
            "REFRESH" -> { Tunnel.stop(); Tunnel.start(c.applicationContext) }
        }
    }
}

object Tunnel {
    val addr = MutableStateFlow<String?>(null)
    val state = MutableStateFlow("Kapalı")
    val expires = MutableStateFlow(0L)
    val on = MutableStateFlow(false)
    @Volatile private var client: SSHClient? = null
    @Volatile private var gen = 0
    private var app: Context? = null

    private fun ensureBc() {
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }

    private fun keyPair(c: Context): KeyPair {
        val p = Srv.prefs(c)
        val pub = p.getString("ssh_pub", null); val prv = p.getString("ssh_prv", null)
        val kf = KeyFactory.getInstance("RSA")
        if (pub != null && prv != null) try {
            return KeyPair(kf.generatePublic(X509EncodedKeySpec(Base64.decode(pub, 0))),
                kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(prv, 0))))
        } catch (_: Exception) {}
        val g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048)
        val kp = g.generateKeyPair()
        p.edit().putString("ssh_pub", Base64.encodeToString(kp.public.encoded, 0))
            .putString("ssh_prv", Base64.encodeToString(kp.private.encoded, 0)).apply()
        return kp
    }

    // ---- bildirimler: id 2 = kod (kaydırılabilir), id 3 = olay (kaydırılabilir) ----
    private fun post(id: Int, title: String, text: String, a: String?) {
        val c = app ?: return
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("tun", "Bağlantı kodu", NotificationManager.IMPORTANCE_DEFAULT))
            fun pi(act: String, rc: Int) = PendingIntent.getBroadcast(c, rc,
                Intent(c, CopyReceiver::class.java).setAction(act).putExtra("addr", a ?: ""),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = Notification.Builder(c, "tun").setContentTitle(title).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_menu_share).setAutoCancel(true)
            if (a != null) b.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_edit, "Kopyala", pi("COPY", 10)).build())
            b.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_rotate, "Yenile", pi("REFRESH", 11)).build())
            nm.notify(id, b.build())
        } catch (_: Throwable) {}
    }

    private fun parse(t: String): String? {
        Regex("tcp://([^\\s:/]+):(\\d+)").find(t)?.let { return it.groupValues[1] + ":" + it.groupValues[2] }
        for (m in Regex("([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+):(\\d{3,5})").findAll(t)) {
            val h = m.groupValues[1]; val p = m.groupValues[2]
            if (h.endsWith("a.pinggy.io") || p == "443" || p == "22") continue
            if (h.contains("pinggy") || h.endsWith(".link")) return "$h:$p"
        }
        return null
    }

    fun start(c: Context) {
        if (on.value) return
        app = c.applicationContext
        on.value = true
        val g = ++gen
        thread { loop(g) }
    }

    fun stop() {
        gen++
        on.value = false
        try { client?.disconnect() } catch (_: Throwable) {}
        state.value = "Kapalı"; addr.value = null
        try { app?.getSystemService(NotificationManager::class.java)?.cancel(2) } catch (_: Throwable) {}
    }

    private fun loop(g: Int) {
        var wait = 5
        while (gen == g) {
            val got = runOnce(g)
            if (gen != g) break
            state.value = "Yeniden bağlanıyor…"
            try { app?.getSystemService(NotificationManager::class.java)?.cancel(2) } catch (_: Throwable) {}
            post(3, "Bağlantı koptu", "Yeniden bağlanılıyor… Yeni kod gelince haber vereceğim.", null)
            wait = if (got) 5 else (wait * 2).coerceAtMost(60)
            var w = 0
            while (gen == g && w < wait) { Thread.sleep(1000); w++ }
        }
    }

    // true: adres alındı (bağlantı çalıştı)
    private fun runOnce(g: Int): Boolean {
        val c = app ?: return false
        var ssh: SSHClient? = null
        var got = false
        try {
            state.value = "Bağlanıyor…"; addr.value = null
            ensureBc()
            ssh = SSHClient()
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            ssh.connectTimeout = 15000
            ssh.connect("a.pinggy.io", 443)
            client = ssh
            ssh.authPublickey("tcp", ssh.loadKeys(keyPair(c)))
            ssh.connection.keepAlive.keepAliveInterval = 30
            ssh.remotePortForwarder.bind(RemotePortForwarder.Forward(0),
                SocketForwardingConnectListener(InetSocketAddress("127.0.0.1", Srv.port(c))))
            val s = ssh.startSession()
            s.allocateDefaultPTY()
            val sh = s.startShell()
            state.value = "Bağlı, kod bekleniyor… (üstteki { } günlüğüne bakın)"
            expires.value = System.currentTimeMillis() + 60 * 60 * 1000L
            val ansi = Regex("\u001B(\\[[0-9;?]*[ -/]*[@-~]|\\][^\u0007]*\u0007|[()][0-9A-Za-z])")
            val ins = sh.inputStream
            val buf = ByteArray(4096); val acc = StringBuilder(); var logged = 0
            while (gen == g) {
                val n = ins.read(buf)
                if (n < 0) break
                val chunk = String(buf, 0, n, Charsets.UTF_8).replace(ansi, "")
                acc.append(chunk)
                if (acc.length > 6000) acc.delete(0, acc.length - 3000)
                if (!got && logged < 60) chunk.split("\n", "\r").filter { it.isNotBlank() }.forEach { Srv.add("[Tünel] " + it.trim()); logged++ }
                if (!got) parse(acc.toString())?.let { a ->
                    got = true
                    addr.value = a; state.value = "Bağlı"
                    expires.value = System.currentTimeMillis() + 60 * 60 * 1000L
                    post(2, "Bağlandı", "Kodunuz: $a", a)
                    app?.let { cc -> thread { try { Relay.publishNow(cc) } catch (_: Throwable) {} } }
                }
            }
        } catch (e: Throwable) {
            if (gen == g) { state.value = "Hata: ${e.message ?: e.javaClass.simpleName}"; Srv.add("[Tünel] HATA: $e") }
        } finally {
            try { ssh?.disconnect() } catch (_: Throwable) {}
            if (gen == g) { client = null; addr.value = null }
        }
        return got
    }
}
