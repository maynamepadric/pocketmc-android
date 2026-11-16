@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.pocketmc.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

// Sunucu dünyası <-> oyunun (launcher) saves klasöründeki dünya. Her yazmadan önce yedek alınır.
object SyncW {
    private fun pf(c: Context) = Srv.prefs(c)
    fun name(c: Context, w: String) = pf(c).getString("sync_name_$w", null)
    fun root(c: Context, w: String): DocumentFile? {
        val u = pf(c).getString("sync_uri_$w", null) ?: return null
        return try { DocumentFile.fromTreeUri(c, Uri.parse(u)) } catch (_: Exception) { null }
    }

    fun bind(c: Context, w: String, uri: Uri): String? {
        try { c.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
        val d = DocumentFile.fromTreeUri(c, uri) ?: return "Klasör açılamadı"
        val n = d.name ?: return "Klasör adı okunamadı"
        if (d.findFile("level.dat") == null) return "Bu bir Minecraft dünyası değil (level.dat yok). Oyunda aynı adla bir dünya oluşturup o klasörü seçin."
        pf(c).edit().putString("sync_uri_$w", uri.toString()).putString("sync_name_$w", n).remove("sync_sig_$w").apply()
        return null
    }

    fun unbind(c: Context, w: String) {
        pf(c).edit().remove("sync_uri_$w").remove("sync_name_$w").remove("sync_sig_$w")
            .putBoolean("sync_in_$w", false).putBoolean("sync_out_$w", false).apply()
    }

    private fun gzOk(i: java.io.InputStream) = try { GZIPInputStream(i).use { it.readBytes().size > 50 } } catch (_: Exception) { false }
    private fun localOk(w: File) = File(w, "level.dat").let { it.exists() && gzOk(it.inputStream()) }
    private fun docOk(c: Context, d: DocumentFile) = d.findFile("level.dat")?.let { f ->
        try { gzOk(c.contentResolver.openInputStream(f.uri)!!) } catch (_: Exception) { false }
    } ?: false
    private fun sigLocal(w: File) = File(w, "level.dat").let { if (it.exists()) "${it.lastModified()}:${it.length()}" else "-" }
    private fun sigDoc(d: DocumentFile) = d.findFile("level.dat")?.let { "${it.lastModified()}:${it.length()}" } ?: "-"

    private fun copyToDoc(c: Context, src: File, dst: DocumentFile) {
        for (f in src.listFiles() ?: return) {
            if (f.name == "session.lock") continue
            if (f.isDirectory) {
                val d = dst.createDirectory(f.name) ?: throw IOException("Klasör oluşturulamadı: ${f.name}")
                copyToDoc(c, f, d)
            } else {
                val t = dst.createFile("application/octet-stream", f.name) ?: throw IOException("Dosya oluşturulamadı: ${f.name}")
                c.contentResolver.openOutputStream(t.uri, "wt")!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            }
        }
    }

    private fun copyFromDoc(c: Context, src: DocumentFile, dst: File) {
        dst.mkdirs()
        for (d in src.listFiles()) {
            val n = d.name ?: continue
            if (n == "session.lock") continue
            if (d.isDirectory) copyFromDoc(c, d, File(dst, n))
            else c.contentResolver.openInputStream(d.uri)!!.use { i -> File(dst, n).outputStream().use { i.copyTo(it) } }
        }
    }

    private fun zipDoc(c: Context, d: DocumentFile, z: ZipOutputStream, prefix: String) {
        for (f in d.listFiles()) {
            val n = f.name ?: continue
            if (f.isDirectory) zipDoc(c, f, z, "$prefix$n/")
            else { z.putNextEntry(ZipEntry("$prefix$n")); c.contentResolver.openInputStream(f.uri)?.use { it.copyTo(z) }; z.closeEntry() }
        }
    }

    private fun restoreZip(c: Context, z: File, root: DocumentFile) {
        ZipFile(z).use { zf ->
            for (e in zf.entries()) {
                if (e.isDirectory) continue
                val parts = e.name.split('/')
                var d = root
                for (p in parts.dropLast(1)) d = d.findFile(p) ?: d.createDirectory(p) ?: throw IOException("klasör")
                val t = d.createFile("application/octet-stream", parts.last()) ?: throw IOException("dosya")
                c.contentResolver.openOutputStream(t.uri, "wt")!!.use { o -> zf.getInputStream(e).use { it.copyTo(o) } }
            }
        }
    }

    private fun playerUuid(c: Context, w: String): String? {
        val n = pf(c).getString("sync_player_$w", "")?.trim()
        return if (!n.isNullOrEmpty() && Regex("[A-Za-z0-9_]{3,16}").matches(n)) Setup.offlineUuid(n) else null
    }

    // Sunucudaki playerdata/<uuid>.dat -> oyundaki level.dat içindeki Data.Player
    @Suppress("UNCHECKED_CAST")
    private fun injectPlayer(c: Context, root: DocumentFile, pfile: File) {
        val pRoot = Nbt.parse(Nbt.gunzip(pfile.readBytes()))
        val ld = root.findFile("level.dat") ?: throw IOException("level.dat yok")
        val lRoot = Nbt.parse(Nbt.gunzip(c.contentResolver.openInputStream(ld.uri)!!.use { it.readBytes() }))
        val data = (lRoot.v as Map<String, NTag>)["Data"] ?: throw IOException("level.dat içinde Data yok")
        (data.v as MutableMap<String, NTag>)["Player"] = NTag(10, pRoot.v)
        val out = Nbt.gzip(Nbt.write(lRoot))
        c.contentResolver.openOutputStream(ld.uri, "wt")!!.use { it.write(out) }
    }

    // Oyundaki level.dat içindeki Data.Player -> sunucu playerdata/<uuid>.dat
    @Suppress("UNCHECKED_CAST")
    private fun extractPlayer(tmp: File, uuid: String) {
        val lRoot = Nbt.parse(Nbt.gunzip(File(tmp, "level.dat").readBytes()))
        val player = (lRoot.v as Map<String, NTag>)["Data"]?.let { (it.v as Map<String, NTag>)["Player"] }
            ?: throw IOException("oyun dünyasında oyuncu verisi yok")
        val m = LinkedHashMap(player.v as Map<String, NTag>)
        if (m.containsKey("UUID")) {
            val u = UUID.fromString(uuid)
            val a = longArrayOf(u.mostSignificantBits, u.leastSignificantBits)
            m["UUID"] = NTag(11, intArrayOf((a[0] shr 32).toInt(), a[0].toInt(), (a[1] shr 32).toInt(), a[1].toInt()))
        }
        val pd = File(tmp, "playerdata"); pd.mkdirs()
        File(pd, "$uuid.dat").writeBytes(Nbt.gzip(Nbt.write(NTag(10, m))))
    }

    private fun target(c: Context, w: String): DocumentFile {
        val root = root(c, w) ?: throw IOException("Oyun klasörü seçilmemiş")
        if (root.name != name(c, w)) throw IOException("Seçili klasörün adı değişmiş (${root.name} ≠ ${name(c, w)}), güvenlik için durduruldu")
        if (!docOk(c, root)) throw IOException("Oyun klasöründe geçerli bir dünya bulunamadı")
        return root
    }

    fun toMc(c: Context, w: String): String {
        if (Srv.running.value) throw IOException("Sunucu çalışırken eşitlenmez")
        val src = File(Srv.serverDir(c), w)
        if (!localOk(src)) throw IOException("Sunucu dünyası sağlam görünmüyor (level.dat), kopyalanmadı")
        val root = target(c, w)
        val bk = File(Srv.backupDir(c), "mcsave_$w-${System.currentTimeMillis()}.zip")
        ZipOutputStream(bk.outputStream().buffered()).use { zipDoc(c, root, it, "") }
        try {
            for (f in root.listFiles()) if (!f.delete()) throw IOException("Eski dosya silinemedi: ${f.name}")
            copyToDoc(c, src, root)
            if (!docOk(c, root)) throw IOException("Kopya doğrulanamadı")
        } catch (e: Throwable) {
            try { root.listFiles().forEach { it.delete() }; restoreZip(c, bk, root) } catch (_: Throwable) {}
            throw IOException("Oyuna yazılamadı, eski hâl geri yüklenmeye çalışıldı: $e")
        }
        var extra = ""
        playerUuid(c, w)?.let { uid ->
            val pfile = File(src, "playerdata/$uid.dat")
            extra = if (pfile.exists()) try { injectPlayer(c, root, pfile); " • envanter/konum oyuna aktarıldı" }
                catch (e: Throwable) { " • oyuncu verisi çevrilemedi: $e" } else " • sunucuda bu oyuncunun verisi yok"
        }
        bk.delete()
        pf(c).edit().putString("sync_sig_$w", sigLocal(src) + "|" + sigDoc(root)).apply()
        return "Sunucu → oyun tamam (${root.name})$extra"
    }

    fun toServer(c: Context, w: String): String {
        if (Srv.running.value) throw IOException("Sunucu çalışırken eşitlenmez")
        val root = target(c, w)
        val dst = File(Srv.serverDir(c), w)
        val tmp = File(Srv.serverDir(c), "$w.pmc_new"); tmp.deleteRecursively()
        val old = File(Srv.serverDir(c), "$w.pmc_old"); old.deleteRecursively()
        var extra = ""
        try {
            copyFromDoc(c, root, tmp)
            if (!localOk(tmp)) throw IOException("Kopya doğrulanamadı")
            playerUuid(c, w)?.let { uid ->
                extra = try { extractPlayer(tmp, uid); " • envanter/konum sunucuya aktarıldı" } catch (e: Throwable) { " • oyuncu verisi çevrilemedi: ${e.message}" }
            }
            if (dst.exists() && !dst.renameTo(old)) throw IOException("Eski dünya taşınamadı")
            if (!tmp.renameTo(dst)) { old.renameTo(dst); throw IOException("Yeni dünya yerleştirilemedi") }
            old.deleteRecursively()
        } catch (e: Throwable) { tmp.deleteRecursively(); throw if (e is IOException) e else IOException(e.toString()) }
        pf(c).edit().putString("sync_sig_$w", sigLocal(dst) + "|" + sigDoc(root)).apply()
        return "Oyun → sunucu tamam (${root.name})$extra"
    }

    // Sunucu durunca ("stop") ve başlamadan önce ("start") çağrılır. Çakışmada hiçbir şey yazmaz.
    fun auto(c: Context, w: String, phase: String) {
        val p = pf(c)
        val out = p.getBoolean("sync_out_$w", false); val inn = p.getBoolean("sync_in_$w", false)
        if (!out && !inn) return
        val root = root(c, w) ?: return
        val saved = (p.getString("sync_sig_$w", "") ?: "").split("|")
        val sCh = sigLocal(File(Srv.serverDir(c), w)) != (saved.getOrNull(0) ?: "")
        val mCh = sigDoc(root) != (saved.getOrNull(1) ?: "")
        if (!sCh && !mCh) return
        val msg = when {
            sCh && mCh -> { 
                val t = "Eşitleme çakışması (${Srv.worldLabel(w)}): hem sunucuda hem oyunda değişiklik var. Dünyalar ekranından yönü elle seçin."
                Srv.banner.value = Banner(System.currentTimeMillis(), t, true); t
            }
            phase == "stop" && out && sCh -> { Srv.status.value = "Eşitleniyor…"; toMc(c, w) }
            phase == "start" && inn && mCh -> { Srv.status.value = "Eşitleniyor…"; toServer(c, w) }
            else -> return
        }
        Srv.add("[APK] $msg")
    }
}

@Composable fun SyncCard(folder: String, busy: Boolean) {
    val ctx = LocalContext.current
    val p = remember { Srv.prefs(ctx) }
    val running by Srv.running.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var confirm by remember { mutableIntStateOf(0) }
    var out by remember { mutableStateOf(p.getBoolean("sync_out_$folder", false)) }
    var inn by remember { mutableStateOf(p.getBoolean("sync_in_$folder", false)) }
    var pn by remember { mutableStateOf(p.getString("sync_player_$folder", "") ?: "") }
    val name = remember(tick) { SyncW.name(ctx, folder) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) thread { msg = SyncW.bind(ctx, folder, u) ?: "Seçildi. İlk eşitlemeyi aşağıdan yönü seçerek yapın."; tick++ }
    }
    fun go(label: String, f: () -> String) {
        working = true; msg = "$label…"
        thread { msg = try { f() } catch (e: Throwable) { "HATA: ${e.message}" }; working = false; tick++ }
    }
    val can = !running && !working && !busy && name != null
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Oyunla eşitleme (bu dünyaya özel)", style = MaterialTheme.typography.titleMedium)
            Text("Oyunun (launcher) saves klasöründeki aynı dünyanın klasörünü seçin. Sadece seçilen klasöre yazılır; adı değişirse işlem durur. " +
                "Her yazmadan önce yedek alınır. Android 11+ sürümlerinde Android/data içindeki klasörler seçilemeyebilir.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (name != null) "Seçili oyun dünyası: $name" else "Seçili klasör yok", color = if (name != null) Green else Amber)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ pick.launch(null) }, enabled = !working && !running) { Text(if (name != null) "Klasörü değiştir" else "Oyun klasörünü seç") }
                if (name != null) TextButton({ SyncW.unbind(ctx, folder); out = false; inn = false; tick++; msg = "Bağlantı kaldırıldı" }, enabled = !working) { Text("Kaldır", color = Red) }
            }
            if (name != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sunucu kapanınca oyuna kaydet", Modifier.weight(1f))
                    Switch(out, { v -> out = v; p.edit().putBoolean("sync_out_$folder", v).apply() })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Başlatmadan önce oyundakini sunucuya al", Modifier.weight(1f))
                    Switch(inn, { v -> inn = v; p.edit().putBoolean("sync_in_$folder", v).apply() })
                }
                OutlinedTextField(pn, { pn = it.trim(); p.edit().putString("sync_player_$folder", pn).apply() }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Oyun içi adın (envanter/konum için, boş = kapalı)") },
                    isError = pn.isNotEmpty() && !Regex("[A-Za-z0-9_]{3,16}").matches(pn))
                Text("Otomatik eşitleme yalnızca tek tarafta değişiklik varsa çalışır. İkisinde de varsa çakışma uyarısı çıkar ve dokunulmaz.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ confirm = 1 }, enabled = can) { Text("Şimdi sunucu → oyun") }
                    OutlinedButton({ confirm = 2 }, enabled = can) { Text("Şimdi oyun → sunucu") }
                }
                if (running) Text("Sunucu çalışırken eşitlenmez.", fontSize = 11.sp, color = Amber)
            }
            if (msg.isNotEmpty()) Text(msg, fontSize = 12.sp)
        }
    }
    if (confirm != 0) AlertDialog(onDismissRequest = { confirm = 0 },
        title = { Text(if (confirm == 1) "Sunucu → oyun" else "Oyun → sunucu") },
        text = { Text(if (confirm == 1) "Oyundaki \"$name\" dünyasının içeriği silinip sunucudakiyle değiştirilecek. Önce zip yedeği alınır."
            else "Sunucudaki dünya, oyundaki \"$name\" ile değiştirilecek. Önce normal yedek alınır.") },
        confirmButton = { TextButton({
            val k = confirm; confirm = 0
            if (k == 1) go("Oyuna kopyalanıyor") { SyncW.toMc(ctx, folder) } else go("Sunucuya kopyalanıyor") { SyncW.toServer(ctx, folder) }
        }) { Text("Devam") } },
        dismissButton = { TextButton({ confirm = 0 }) { Text("Vazgeç") } })
}
