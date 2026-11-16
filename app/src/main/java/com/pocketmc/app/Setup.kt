package com.pocketmc.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import kotlin.concurrent.thread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object Net {
    const val UA = "PocketMC/0.3 (Android)"

    private fun open(url: String, readTimeout: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = readTimeout
        c.setRequestProperty("User-Agent", UA)
        return c
    }

    fun getString(url: String): String {
        val c = open(url, 20000)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}: $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    fun download(url: String, dest: File, onProgress: (Float) -> Unit) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        val c = open(url, 30000)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}: $url")
            val total = c.contentLengthLong
            c.inputStream.use { ins ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var n = ins.read(buf)
                    while (n > 0) {
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                        n = ins.read(buf)
                    }
                }
            }
        } finally { c.disconnect() }
        dest.delete()
        if (!tmp.renameTo(dest)) throw IOException("Dosya taşınamadı: ${dest.name}")
    }

    // Herhangi bir HTTP cevabı gelirse erişilebilir sayılır (403/404 dahil)
    fun reachable(url: String): Boolean {
        return try {
            val c = open(url, 5000)
            c.connectTimeout = 5000
            c.requestMethod = "HEAD"
            val code = c.responseCode
            c.disconnect()
            code > 0
        } catch (e: Exception) { false }
    }
}

object Setup {
    var MC by mutableStateOf("26.3")
    private val MODS = listOf("lithium", "ferrite-core", "krypton", "scalablelux")

    val installed = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val progress = MutableStateFlow(0f)
    val step = MutableStateFlow("")
    val error = MutableStateFlow<String?>(null)
    val note = MutableStateFlow<String?>(null)

    fun refresh(c: Context) {
        val d = Srv.serverDir(c)
        installed.value = File(d, "fabric-server.jar").exists() && !File(d, ".installing").exists()
    }

    // Çevrimdışı modda Minecraft UUID'si: "OfflinePlayer:<ad>" MD5 (sürüm 3)
    fun offlineUuid(name: String): String =
        UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8)).toString()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun sha1(f: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        f.inputStream().use { i ->
            val b = ByteArray(1 shl 16)
            var n = i.read(b)
            while (n > 0) { md.update(b, 0, n); n = i.read(b) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- Minecraft sürümü değiştirme ----
    fun cmpVer(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }; val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 }); if (d != 0) return d
        }
        return 0
    }

    // ---- Java uyumu: paketlenmiş JRE'nin sürümünü jre/release dosyasından okur (bulunamazsa 0 = bilinmiyor) ----
    fun javaMajor(c: Context): Int {
        val root = File(c.filesDir, "jre")
        fun find(d: File, depth: Int): File? {
            File(d, "release").takeIf { it.isFile }?.let { return it }
            if (depth == 0) return null
            for (x in d.listFiles() ?: emptyArray()) if (x.isDirectory) find(x, depth - 1)?.let { return it }
            return null
        }
        return try {
            val t = find(root, 2)?.readText() ?: return 0
            val v = Regex("JAVA_VERSION=\"([^\"]+)\"").find(t)?.groupValues?.get(1) ?: return 0
            val p = v.split(".", "_", "-", "+")
            if (p[0] == "1") p[1].toInt() else p[0].toInt()
        } catch (e: Throwable) { 0 }
    }

    // Bir Minecraft sürümünün en az hangi Java'yı istediği
    fun needJava(mc: String): Int {
        val p = mc.split(".", "-").mapNotNull { it.toIntOrNull() }
        if (p.isEmpty()) return 17
        if (p[0] >= 26) return 25
        val minor = p.getOrElse(1) { 0 }; val patch = p.getOrElse(2) { 0 }
        return when {
            minor > 20 || (minor == 20 && patch >= 5) -> 21
            else -> 17 // Fabric loader zaten Java 17+ ister
        }
    }

    fun gameVersions(): List<String> {
        val a = JSONArray(Net.getString("https://meta.fabricmc.net/v2/versions/game"))
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optBoolean("stable", false) }.map { it.getString("version") }
    }

    private fun fabricPair(mc: String): Pair<String, String> {
        val loaders = JSONArray(Net.getString("https://meta.fabricmc.net/v2/versions/loader/$mc"))
        if (loaders.length() == 0) throw IOException("Fabric, Minecraft $mc için bulunamadı")
        var loader = loaders.getJSONObject(0).getJSONObject("loader").getString("version")
        for (i in 0 until loaders.length()) {
            val l = loaders.getJSONObject(i).getJSONObject("loader")
            if (l.optBoolean("stable", false)) { loader = l.getString("version"); break }
        }
        val installers = JSONArray(Net.getString("https://meta.fabricmc.net/v2/versions/installer"))
        if (installers.length() == 0) throw IOException("Fabric yükleyici listesi boş")
        var inst = installers.getJSONObject(0).getString("version")
        for (i in 0 until installers.length()) {
            val o = installers.getJSONObject(i)
            if (o.optBoolean("stable", false)) { inst = o.getString("version"); break }
        }
        return loader to inst
    }

    // Kurulu modları yeni sürüme göre günceller; uyumlusu yoksa kapatır
    // ---- Sürüm profilleri ----
    // Her Minecraft sürümünün modları ve dünyaları ayrı saklanır (profiles/<sürüm>/). Sadece aktif sürümünkiler görünür.
    // Taşımalar aynı depolamada yeniden adlandırmadır: kopya yok, ek yer yok. Hata olursa yapılanlar geri alınır.
    class PInfo(val ver: String, val mods: Int, val worlds: List<String>, val mb: Long, val offline: Boolean)

    fun profiles(c: Context): List<PInfo> {
        val root = File(Srv.serverDir(c), "profiles")
        return (root.listFiles() ?: emptyArray()).filter { it.isDirectory }.map { d ->
            val ws = File(d, "worlds").listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
            val mods = File(d, "mods").listFiles()?.count { it.name.endsWith(".jar") } ?: 0
            PInfo(d.name, mods, ws, dirSize(d) / 1048576, File(d, "fabric-server.jar").exists())
        }.sortedWith { a, b -> cmpVer(b.ver, a.ver) }
    }

    private fun mvf(s: File, d: File) {
        d.parentFile?.mkdirs()
        try { Files.move(s.toPath(), d.toPath(), StandardCopyOption.ATOMIC_MOVE) }
        catch (e: Exception) { if (!s.renameTo(d)) throw IOException("Taşınamadı: ${s.name}") }
    }

    private fun installBaseMods(app: Context, mc: String, p0: Float, span: Float) {
        val mods = File(Srv.serverDir(app), "mods"); mods.mkdirs()
        val all = listOf("fabric-api") + MODS
        all.forEachIndexed { idx, slug ->
            step.value = "Mod indiriliyor: $slug"
            progress.value = p0 + span * idx / all.size
            try {
                val arr = JSONArray(Net.getString("https://api.modrinth.com/v2/project/$slug/version?loaders=" + enc("[\"fabric\"]") + "&game_versions=" + enc("[\"$mc\"]")))
                if (arr.length() == 0) { Srv.add("[APK] $slug: $mc için sürüm yok, atlandı"); return@forEachIndexed }
                var v = arr.getJSONObject(0)
                for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if (o.optString("version_type") == "release") { v = o; break } }
                val files = v.getJSONArray("files")
                var f = files.getJSONObject(0)
                for (i in 0 until files.length()) { val o = files.getJSONObject(i); if (o.optBoolean("primary", false)) { f = o; break } }
                val dest = File(mods, File(f.getString("filename")).name)
                Net.download(f.getString("url"), dest) { }
                val want = f.optJSONObject("hashes")?.optString("sha1", "") ?: ""
                if (want.isNotEmpty() && sha1(dest) != want.lowercase()) { dest.delete(); throw IOException("sha1 uyuşmuyor") }
                Srv.add("[APK] Mod kuruldu: ${dest.name}")
            } catch (e: Exception) { Srv.add("[APK] $slug kurulamadı: ${e.message}") }
        }
    }

    // Geçiş günlüğü: uygulama geçiş sırasında kapanırsa bir sonraki açılışta ya geri alınır ya tamamlanır
    private fun jf(c: Context) = File(Srv.serverDir(c), "profiles/.journal")

    private fun rollback(c: Context, lines: List<String>) {
        for (l in lines.drop(1).reversed()) {
            val p = l.split("|"); if (p.size != 2) continue
            val src = File(p[0]); val dst = File(p[1])
            if (dst.exists() && !src.exists()) try { mvf(dst, src) } catch (x: Throwable) { Srv.add("[APK] Geri alma hatası: $x") }
        }
    }

    // Uygulama açılırken çağrılır; yarım kalmış geçiş varsa düzeltir
    fun recover(c: Context) {
        val j = jf(c); if (!j.exists()) return
        try {
            val srv = Srv.serverDir(c)
            val lines = j.readLines()
            val target = lines.firstOrNull() ?: ""
            val nj = File(srv, "fabric-server.jar.new")
            if (Srv.prefs(c).getString("mc", "") == target && lines.size > 1) {
                if (nj.exists()) Files.move(nj.toPath(), File(srv, "fabric-server.jar").toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else { rollback(c, lines); nj.delete() }
            j.delete()
            Srv.add("[APK] Yarım kalan sürüm geçişi düzeltildi")
        } catch (e: Throwable) { Srv.add("[APK] Geçiş onarma hatası: $e") }
    }

    fun switchVersion(c: Context, target: String, baseMods: Boolean) {
        if (busy.value || target == MC) return
        if (Srv.running.value) { error.value = "Önce sunucuyu durdurun"; return }
        val jm = javaMajor(c)
        if (jm in 1 until needJava(target)) { error.value = "$target için Java ${needJava(target)} gerekir, uygulamadaki Java $jm"; return }
        busy.value = true; error.value = null; note.value = null; progress.value = 0f
        val app = c.applicationContext
        val old = MC
        thread {
            val srv = Srv.serverDir(app)
            val j = jf(app)
            val newJar = File(srv, "fabric-server.jar.new")
            fun mv(s: File, d: File) { mvf(s, d); j.appendText(s.path + "|" + d.path + "\n") }
            var committed = false
            try {
                val savedJar = File(srv, "profiles/$target/fabric-server.jar")
                val offline = savedJar.exists() && savedJar.length() > 1024
                if (offline) step.value = "Kayıtlı sürüm kullanılıyor (internet gerekmez)"
                else {
                    step.value = "Fabric sürümü aranıyor"
                    val (loader, inst) = fabricPair(target)
                    step.value = "Fabric sunucusu indiriliyor"
                    Net.download("https://meta.fabricmc.net/v2/versions/loader/$target/$loader/$inst/server/jar", newJar) { progress.value = it * 0.5f }
                    if (newJar.length() < 1024) throw IOException("Sunucu dosyası indirilemedi")
                }
                step.value = "Dosyalar ayrılıyor"
                j.parentFile?.mkdirs(); j.writeText(target + "\n")
                val oldP = File(srv, "profiles/$old")
                val modsDir = File(srv, "mods")
                if (modsDir.exists()) mv(modsDir, File(oldP, "mods"))
                for (w in Srv.worlds(app)) File(srv, w).takeIf { it.exists() }?.let { mv(it, File(oldP, "worlds/$w")) }
                val newP = File(srv, "profiles/$target")
                val hadMods = File(newP, "mods").exists()
                if (hadMods) mv(File(newP, "mods"), modsDir)
                File(newP, "worlds").listFiles()?.forEach { mv(it, File(srv, it.name)) }
                // sunucu dosyası ve oyun önbelleği de sürümle birlikte saklanır (çevrimdışı geri dönüş için)
                File(srv, "fabric-server.jar").takeIf { it.exists() }?.let { mv(it, File(oldP, "fabric-server.jar")) }
                for (rel in CACHE_DIRS) File(srv, rel).listFiles()?.forEach { f ->
                    if (verOf(f.name) == old) mv(f, File(oldP, "cache/$rel/${f.name}"))
                }
                if (offline) mv(savedJar, File(srv, "fabric-server.jar"))
                for (rel in CACHE_DIRS) File(newP, "cache/$rel").listFiles()?.forEach { f -> mv(f, File(srv, "$rel/${f.name}")) }
                modsDir.mkdirs()
                // dönüş noktası: tercihler kaydedilir (commit), sonra jar değişir
                val sp = Srv.prefs(app)
                val newWorld = sp.getString("world_$target", "world") ?: "world"
                val ok = sp.edit().putString("world_$old", Srv.activeWorld(app)).putString("world", newWorld).putString("mc", target).commit()
                if (!ok) throw IOException("Tercihler kaydedilemedi")
                committed = true
                MC = target
                if (newJar.exists()) Files.move(newJar.toPath(), File(srv, "fabric-server.jar").toPath(), StandardCopyOption.REPLACE_EXISTING)
                j.delete()
                newP.deleteRecursively()
                if (baseMods && !hadMods) installBaseMods(app, target, 0.5f, 0.5f)
                progress.value = 1f; step.value = "Sürüm değişti: $old → $target"
                note.value = "Minecraft $target aktif. $old sürümünün modları ve dünyaları saklandı, geri dönünce görünür. " +
                    "İlk açılışta Minecraft indirilir (birkaç dakika sürebilir)."
            } catch (e: Throwable) {
                newJar.delete()
                if (!committed) {
                    try { rollback(app, if (j.exists()) j.readLines() else emptyList()); j.delete() } catch (x: Throwable) { Srv.add("[APK] Geri alma hatası: $x") }
                    error.value = "Sürüm değişmedi, her şey eski haline döndü. Ayrıntı günlükte."
                } else error.value = "Sürüm değişti ama son adımlar tamamlanamadı. Ayrıntı günlükte."
                Srv.add("[APK] SÜRÜM DEĞİŞTİRME HATASI: $e")
            } finally { busy.value = false }
        }
    }

    private val CACHE_DIRS = listOf(".fabric/remappedJars", ".fabric/server", "versions")
    private fun verOf(name: String) = Regex("\\d+\\.\\d+(?:\\.\\d+)?").find(name)?.value

    // Saklanan bir sürümü siler. Dünyaları varsa önce seçilen klasöre zip olarak çıkarılır; doğrulanmadan hiçbir şey silinmez.
    fun deleteProfile(c: Context, ver: String, tree: Uri?) {
        if (busy.value || ver == MC) return
        busy.value = true; error.value = null; note.value = null; progress.value = 0f
        val app = c.applicationContext
        thread {
            try {
                val p = File(Srv.serverDir(app), "profiles/$ver")
                val worlds = File(p, "worlds").listFiles()?.filter { it.isDirectory } ?: emptyList()
                if (worlds.isNotEmpty()) {
                    if (tree == null) throw IOException("Dışa aktarma klasörü seçilmedi")
                    val cr = app.contentResolver
                    val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                    worlds.forEachIndexed { i, w ->
                        step.value = "Dünya çıkarılıyor (${i + 1}/${worlds.size}): ${Srv.tag(w.name)}"
                        progress.value = i.toFloat() / worlds.size
                        val doc = DocumentsContract.createDocument(cr, root, "application/zip", "$ver-${Srv.tag(w.name)}.zip")
                            ?: throw IOException("Dosya oluşturulamadı")
                        cr.openOutputStream(doc)!!.use { out -> ZipOutputStream(BufferedOutputStream(out)).use { Srv.zipDir(w, Srv.tag(w.name), it) } }
                        var lvl = false
                        ZipInputStream(cr.openInputStream(doc)!!).use { zi ->
                            val buf = ByteArray(1 shl 16)
                            while (true) { val e = zi.nextEntry ?: break; if (e.name.endsWith("level.dat")) lvl = true; while (zi.read(buf) > 0) { } }
                        }
                        if (!lvl && File(w, "level.dat").exists()) throw IOException("Dışa aktarılan dosya doğrulanamadı: ${w.name}")
                    }
                }
                step.value = "Siliniyor"
                p.deleteRecursively()
                progress.value = 1f; step.value = "$ver silindi"
                note.value = "$ver sürümünün modları silindi" + if (worlds.isNotEmpty()) "; ${worlds.size} dünya seçtiğiniz klasöre zip olarak çıkarıldı." else "."
            } catch (e: Throwable) {
                error.value = "Silinmedi, hiçbir şey kaybolmadı. Ayrıntı günlükte."
                Srv.add("[APK] SÜRÜM SİLME HATASI: $e")
            } finally { busy.value = false }
        }
    }

    fun run(c: Context, player: String, withMods: Boolean) {
        if (busy.value) return
        busy.value = true; error.value = null; note.value = null; progress.value = 0f
        val app = c.applicationContext
        thread {
            try {
                val srv = Srv.serverDir(app)
                srv.mkdirs()
                File(srv, ".installing").writeText("1")

                // 1) Java (0 - 30%)
                step.value = "Java hazırlanıyor (ilk sefer ~1 dk)"
                Srv.ensureJre(app) { progress.value = it * 0.30f }
                progress.value = 0.30f

                // 2) Fabric sürümü (30 - 34%)
                step.value = "Fabric sürümü aranıyor"
                val loaders = JSONArray(Net.getString("https://meta.fabricmc.net/v2/versions/loader/$MC"))
                if (loaders.length() == 0) throw IOException("Fabric, Minecraft $MC için bulunamadı")
                var loader = loaders.getJSONObject(0).getJSONObject("loader").getString("version")
                for (i in 0 until loaders.length()) {
                    val l = loaders.getJSONObject(i).getJSONObject("loader")
                    if (l.optBoolean("stable", false)) { loader = l.getString("version"); break }
                }
                val installers = JSONArray(Net.getString("https://meta.fabricmc.net/v2/versions/installer"))
                if (installers.length() == 0) throw IOException("Fabric yükleyici listesi boş")
                var inst = installers.getJSONObject(0).getString("version")
                for (i in 0 until installers.length()) {
                    val o = installers.getJSONObject(i)
                    if (o.optBoolean("stable", false)) { inst = o.getString("version"); break }
                }
                progress.value = 0.34f

                // 3) Fabric sunucu jar'ı (34 - 54%)
                step.value = "Fabric sunucusu indiriliyor"
                Net.download(
                    "https://meta.fabricmc.net/v2/versions/loader/$MC/$loader/$inst/server/jar",
                    File(srv, "fabric-server.jar")
                ) { progress.value = 0.34f + it * 0.20f }
                progress.value = 0.54f

                // 4) Modlar (54 - 94%)
                if (withMods) {
                    val mods = File(srv, "mods")
                    mods.mkdirs()
                    MODS.forEachIndexed { idx, slug ->
                        step.value = "Mod indiriliyor: $slug"
                        val span = 0.40f / MODS.size
                        val base = 0.54f + span * idx
                        try {
                            val url = "https://api.modrinth.com/v2/project/$slug/version?loaders=" +
                                enc("[\"fabric\"]") + "&game_versions=" + enc("[\"$MC\"]")
                            val arr = JSONArray(Net.getString(url))
                            if (arr.length() == 0) {
                                Srv.add("[APK] $slug: $MC için sürüm yok, atlandı")
                            } else {
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
                                val name = f.getString("filename")
                                val dest = File(mods, name)
                                Net.download(f.getString("url"), dest) { progress.value = base + it * span }
                                val want = f.optJSONObject("hashes")?.optString("sha1", "") ?: ""
                                if (want.isNotEmpty() && sha1(dest) != want.lowercase()) {
                                    dest.delete()
                                    throw IOException("sha1 uyuşmuyor")
                                }
                                Srv.add("[APK] Mod kuruldu: $name")
                            }
                        } catch (e: Exception) {
                            Srv.add("[APK] $slug indirilemedi: ${e.message}")
                        }
                        progress.value = base + span
                    }
                }
                progress.value = 0.94f

                // 5) Ayar dosyaları (94 - 100%)
                step.value = "Ayarlar yazılıyor"
                File(srv, "eula.txt").writeText("eula=true\n")
                Srv.writeProps(File(srv, "server.properties"), mapOf(
                    "online-mode" to "false", "white-list" to "true",
                    "view-distance" to "6", "simulation-distance" to "5",
                    "sync-chunk-writes" to "false", "entity-broadcast-range-percentage" to "75",
                    "network-compression-threshold" to "256", "max-players" to "10",
                    "pause-when-empty-seconds" to "-1", "motd" to "PocketMC",
                    "enable-rcon" to "true", "rcon.port" to "25575", "rcon.password" to Srv.rconPass(app)))
                val wl = JSONArray().put(JSONObject().put("uuid", offlineUuid(player)).put("name", player))
                File(srv, "whitelist.json").writeText(wl.toString(2))

                // Mojang erişim kontrolü
                step.value = "Bağlantı kontrol ediliyor"
                val mojang = Net.reachable("https://piston-data.mojang.com/")
                note.value = if (mojang) null else
                    "Mojang sunucusuna (piston-data.mojang.com) erişilemiyor. Fabric, ilk BAŞLAT'ta oyunun kendisini oradan indirir; bu yüzden ilk açılış başarısız olabilir. " +
                    "Engel DNS tabanlıysa Android Ayarlar > Özel DNS > dns.google deneyin. Olmazsa Konsol sekmesinden Termux'taki sunucuyu içe aktarın."

                File(srv, ".installing").delete()
                progress.value = 1f
                step.value = "Kurulum tamam!"
                refresh(app)
            } catch (e: Throwable) {
                error.value = "${e.javaClass.simpleName}: ${e.message}"
                Srv.add("[APK] KURULUM HATASI: $e")
                if (!Srv.running.value) Srv.status.value = "Kapalı"
            } finally {
                busy.value = false
            }
        }
    }
}
