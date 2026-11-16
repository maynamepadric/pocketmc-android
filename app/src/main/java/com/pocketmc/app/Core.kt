package com.pocketmc.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.*
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class CountingStream(ins: InputStream, private val cb: (Long) -> Unit) : FilterInputStream(ins) {
    private var n = 0L
    private var last = 0L
    private fun tick(k: Int) {
        if (k > 0) { n += k; if (n - last >= 1000000L) { last = n; cb(n) } }
    }
    override fun read(): Int { val r = super.read(); if (r >= 0) tick(1); return r }
    override fun read(b: ByteArray, off: Int, len: Int): Int { val r = super.read(b, off, len); tick(r); return r }
}

data class Banner(val id: Long, val text: String, val err: Boolean)

object Srv {
    val showLog = MutableStateFlow(false)
    val showCfg = MutableStateFlow(false)
    val sleeping = MutableStateFlow(false)
    @Volatile var sleepRequested = false
    @Volatile var wake = false
    @Volatile var info = ""
    val requests = MutableStateFlow<List<String>>(emptyList())
    fun addRequest(n: String) {
        if (n in requests.value) return
        requests.value = (requests.value + n).takeLast(20)
        banner.value = Banner(System.currentTimeMillis(), "$n girmek istiyor (Oyuncular sekmesi)", false)
    }
    fun removeRequest(n: String) { requests.value = requests.value - n }
    val banner = MutableStateFlow<Banner?>(null)
    @Volatile private var lastErr = 0L

    private fun eventOf(s: String) {
        val now = System.currentTimeMillis()
        when {
            s.contains("not whitelisted", true) || s.contains("not white-listed", true) ->
                Regex("Disconnecting (\\w{3,16}) \\(").find(s)?.groupValues?.get(1)?.let { addRequest(it) }
            s.contains(" joined the game") -> banner.value = Banner(now, shortLine(s).substringBefore(" joined the game") + " bağlandı", false)
            s.contains(" left the game") -> banner.value = Banner(now, shortLine(s).substringBefore(" left the game") + " ayrıldı", false)
            (s.startsWith("[APK]") && (s.contains("HATA") || s.contains("BOZUK"))) || s.contains("/ERROR]") -> {
                if (now - lastErr > 4000) {
                    lastErr = now
                    banner.value = Banner(now, "Hata: " + shortLine(s).removePrefix("[APK] ").take(90), true)
                }
            }
        }
    }

    val log = MutableStateFlow<List<String>>(emptyList())
    val running = MutableStateFlow(false)
    val status = MutableStateFlow("Kapalı")
    val progress = MutableStateFlow(0f)
    val stageText = MutableStateFlow("")
    @Volatile var proc: Process? = null
    @Volatile var stopRequested = false

    // ---- günlük: arka planda biriktir, arayüze saniyede ~4 kez ver ----
    private val buf = ArrayList<String>()
    private val lock = Any()
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var pending = false

    fun port(c: Context): Int = readProps(File(serverDir(c), "server.properties"))["server-port"]?.trim()?.toIntOrNull() ?: 25565

    // ---- çoklu dünya: her dünya ayrı klasör ("world" ya da "w_<ad>"), ayrı ayar, ayrı yedek ----
    fun activeWorld(c: Context): String = prefs(c).getString("world", "world") ?: "world"
    fun worldDir(c: Context) = File(serverDir(c), activeWorld(c))
    fun tag(f: String) = f.removePrefix("w_")
    fun worldLabel(f: String) = if (f == "world") "world (ana dünya)" else tag(f)
    fun worlds(c: Context): List<String> {
        val l = serverDir(c).listFiles()?.filter { it.isDirectory && (it.name == "world" || it.name.startsWith("w_")) }
            ?.map { it.name }?.toMutableList() ?: mutableListOf()
        val a = activeWorld(c)
        if (a !in l) l.add(a)
        return l.sortedWith(compareBy({ it != a }, { it }))
    }
    // Kayıtlı dünya ayarı yoksa (eski ana dünya) hiçbir şeye dokunulmaz
    fun worldProps(c: Context, f: String): Map<String, String> {
        val raw = prefs(c).getString("wp_$f", null) ?: return emptyMap()
        val base = mapOf("level-seed" to "", "gamemode" to "survival", "difficulty" to "easy")
        return try { val o = JSONObject(raw); base + o.keys().asSequence().associateWith { o.getString(it) } } catch (e: Exception) { base }
    }
    fun saveWorldProps(c: Context, f: String, m: Map<String, String>) {
        prefs(c).edit().putString("wp_$f", JSONObject(m).toString()).apply()
    }

    // Dünyayı yeniden adlandırır; ayarlarını ve yedeklerini de taşır. Hata metni ya da null döner.
    fun renameWorld(c: Context, old: String, name: String): String? {
        if (running.value && old == activeWorld(c)) return "Önce sunucuyu durdurun"
        if (!Regex("[A-Za-z0-9_]{1,16}").matches(name) || name == "world") return "Ad: harf, rakam, _ (1-16); 'world' olamaz"
        val nf = "w_$name"
        if (nf == old) return null
        val src = File(serverDir(c), old); val dst = File(serverDir(c), nf)
        if (dst.exists()) return "Bu adda bir dünya var"
        if (src.exists() && !src.renameTo(dst)) return "Klasör yeniden adlandırılamadı"
        val p = prefs(c); val e = p.edit()
        for (k in listOf("wp", "sync_uri", "sync_name", "sync_sig", "sync_in", "sync_out", "sync_player")) {
            val v = p.all["${k}_$old"] ?: continue
            when (v) {
                is String -> e.putString("${k}_$nf", v)
                is Boolean -> e.putBoolean("${k}_$nf", v)
                is Int -> e.putInt("${k}_$nf", v)
            }
            e.remove("${k}_$old")
        }
        if (activeWorld(c) == old) e.putString("world", nf)
        e.apply()
        return null
    }

    fun deleteWorld(c: Context, f: String) {
        File(serverDir(c), f).deleteRecursively()
        val e = prefs(c).edit().remove("wp_$f")
        if (activeWorld(c) == f) e.putString("world", "world")
        e.apply()
    }

    fun add(s0: String) {
        if (s0.contains("RCON Client")) return
        eventOf(s0)
        val s = if (s0.length > 2000) s0.take(2000) + "…" else s0
        synchronized(lock) {
            buf.add(s)
            if (buf.size > 600) buf.subList(0, buf.size - 600).clear()
        }
        if (!pending) {
            pending = true
            ui.postDelayed({
                pending = false
                log.value = synchronized(lock) { ArrayList(buf) }
            }, 250)
        }
    }
    fun clearLog() { synchronized(lock) { buf.clear() }; log.value = emptyList() }
    fun fullLog(): String = synchronized(lock) { buf.joinToString("\n") }

    // ---- açılış ilerlemesi (günlük satırlarından aşama çıkar) ----
    fun resetProgress(t: String) { progress.value = 0.03f; stageText.value = t }
    fun stage(l: String) {
        fun up(p: Float, t: String) { if (p > progress.value) { progress.value = p; stageText.value = t } }
        when {
            l.contains("Done (") -> { progress.value = 1f; stageText.value = "Hazır! Oyuna girebilirsiniz" }
            l.contains("Preparing spawn") || l.contains("Preparing start region") -> {
                val pct = Regex("(\\d+)%").find(l)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                up(0.55f + 0.40f * pct / 100f, "Başlangıç bölgesi hazırlanıyor…")
            }
            l.contains("Preparing level") -> up(0.50f, "Dünya hazırlanıyor…")
            l.contains("Loading properties") -> up(0.38f, "Ayarlar okunuyor…")
            l.contains("Starting minecraft server") -> up(0.30f, "Sunucu çekirdeği başlıyor…")
            Regex("Loading \\d+ mods").containsMatchIn(l) -> up(0.18f, "Modlar yükleniyor…")
            l.contains("Loading Minecraft") || l.contains("Loading for game Minecraft") -> up(0.10f, "Minecraft yükleniyor…")
            l.contains("Downloading") -> up(0.05f, "Mojang dosyaları indiriliyor…")
        }
    }

    fun send(c: String) {
        try { proc?.outputStream?.let { it.write((c + "\n").toByteArray()); it.flush() } } catch (_: IOException) {}
    }
    fun toast(c: Context, s: String) { Handler(Looper.getMainLooper()).post { Toast.makeText(c, s, Toast.LENGTH_SHORT).show() } }
    fun serverDir(c: Context) = File(c.filesDir, "server")
    fun backupDir(c: Context) = File(c.filesDir, "backups").also { it.mkdirs() }
    fun prefs(c: Context) = c.getSharedPreferences("pmc", 0)
    fun rconPass(c: Context): String {
        val p = prefs(c)
        return p.getString("rcon", null) ?: UUID.randomUUID().toString().replace("-", "").take(20)
            .also { p.edit().putString("rcon", it).apply() }
    }
    fun rcon(c: Context, cmd: String): String? = Rcon.exec(25575, rconPass(c), cmd)

    // ---- zip ----
    fun unzip(ins: InputStream, dest: File, strip: String?) {
        dest.mkdirs()
        val base = dest.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(ins, 1 shl 16)).use { z ->
            val buf = ByteArray(1 shl 16)
            var e = z.nextEntry
            while (e != null) {
                var name = e.name
                if (strip != null) {
                    if (!name.startsWith(strip)) { e = z.nextEntry; continue }
                    name = name.substring(strip.length)
                }
                if (name.isNotEmpty()) {
                    val f = File(dest, name)
                    if (!f.canonicalPath.startsWith(base) && f.canonicalPath + File.separator != base)
                        throw IOException("zip-slip: $name")
                    if (e.isDirectory) f.mkdirs() else {
                        f.parentFile?.mkdirs()
                        FileOutputStream(f).use { o ->
                            var n = z.read(buf)
                            while (n > 0) { o.write(buf, 0, n); n = z.read(buf) }
                        }
                        f.setExecutable(true, false)
                    }
                }
                e = z.nextEntry
            }
        }
    }
    fun zipDir(d: File, prefix: String, z: ZipOutputStream) {
        d.listFiles()?.forEach { f ->
            val n = "$prefix/${f.name}"
            if (f.isDirectory) {
                zipDir(f, n, z)
            } else if (f.name != "session.lock") {
                z.putNextEntry(ZipEntry(n)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
    }

    // ---- JRE / sunucu ----
    fun ensureJre(c: Context, onProgress: ((Float) -> Unit)? = null) {
        val ok = File(c.filesDir, "jre2.ok")
        if (ok.exists()) { onProgress?.invoke(1f); return }
        status.value = "JRE hazırlanıyor…"; add("[APK] JRE çıkarılıyor (ilk sefer ~1 dk)")
        File(c.filesDir, "jre").deleteRecursively()
        val total = try { c.assets.openFd("jre.zip").use { it.length } } catch (e: Exception) { -1L }
        c.assets.open("jre.zip").use { raw ->
            val counted = CountingStream(raw) { n -> if (total > 0) onProgress?.invoke((n.toFloat() / total).coerceAtMost(1f)) }
            unzip(counted, c.filesDir, null)
        }
        ok.createNewFile(); add("[APK] JRE hazır"); status.value = "Kapalı"
        onProgress?.invoke(1f)
    }
    fun importServer(c: Context, ins: InputStream) {
        if (running.value) { add("[APK] Önce sunucuyu durdurun"); return }
        add("[APK] Sunucu içe aktarılıyor…")
        val d = serverDir(c); d.deleteRecursively()
        unzip(ins, d, "mcserver/")
        add("[APK] Bitti. fabric-server.jar var mı: " + File(d, "fabric-server.jar").exists())
        Setup.refresh(c)
    }

    // ---- server.properties ----
    fun readProps(f: File): Map<String, String> =
        if (!f.exists()) emptyMap() else f.readLines().filter { it.isNotBlank() && !it.startsWith("#") && "=" in it }
            .associate { it.substringBefore("=") to it.substringAfter("=") }
    fun writeProps(f: File, ch: Map<String, String>) {
        f.parentFile?.mkdirs()
        val seen = HashSet<String>()
        val out = (if (f.exists()) f.readLines() else emptyList()).map { l ->
            val k = l.substringBefore("=")
            if (!l.startsWith("#") && "=" in l && ch.containsKey(k)) { seen.add(k); "$k=${ch[k]}" } else l
        }.toMutableList()
        ch.forEach { (k, v) -> if (k !in seen) out.add("$k=$v") }
        f.writeText(out.joinToString("\n") + "\n")
    }

    fun importWorld(c: Context, ins: InputStream, folder: String = activeWorld(c)) {
        if (running.value && folder == activeWorld(c)) { add("[APK] Önce sunucuyu durdurun"); toast(c, "Önce sunucuyu durdurun"); return }
        val tmp = File(c.cacheDir, "wimport"); tmp.deleteRecursively(); tmp.mkdirs()
        unzip(ins, tmp, null)
        val lvl = tmp.walkTopDown().firstOrNull { it.name == "level.dat" }
        if (lvl == null) { add("[APK] level.dat bulunamadı, içe aktarma iptal"); toast(c, "level.dat yok"); tmp.deleteRecursively(); return }
        val w = File(serverDir(c), folder)
        w.deleteRecursively()
        lvl.parentFile!!.copyRecursively(w, true); tmp.deleteRecursively()
        add("[APK] Dünya içe aktarıldı: ${worldLabel(folder)}"); toast(c, "İçe aktarıldı: ${worldLabel(folder)}")
    }
}

object Rcon {
    fun exec(port: Int, pass: String, cmd: String): String? {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 2000); s.soTimeout = 5000
                val o = s.getOutputStream(); val i = DataInputStream(BufferedInputStream(s.getInputStream()))
                fun send(id: Int, type: Int, body: String) {
                    val b = body.toByteArray(Charsets.UTF_8)
                    val bb = ByteBuffer.allocate(14 + b.size).order(ByteOrder.LITTLE_ENDIAN)
                    bb.putInt(10 + b.size).putInt(id).putInt(type).put(b).put(0.toByte()).put(0.toByte())
                    o.write(bb.array()); o.flush()
                }
                fun read(): Pair<Int, String> {
                    val h = ByteArray(4); i.readFully(h)
                    val len = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).int
                    val d = ByteArray(len); i.readFully(d)
                    val id = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN).int
                    return id to String(d, 8, len - 10, Charsets.UTF_8)
                }
                send(1, 3, pass)
                if (read().first == -1) return@use null
                send(2, 2, cmd)
                read().second
            }
        } catch (e: Exception) { null }
    }
}
