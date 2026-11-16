@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.pocketmc.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Depolama dökümü: aktif sürümün modları ve dünyaları, ön bellek, saklanan sürümler. Hepsi silinebilir.
private class SRow(val title: String, val sub: String, val bytes: Long, val warn: String?, val ver: String? = null, val del: (() -> Unit)?)

private fun mb(b: Long) = if (b >= 1048576) "${b / 1048576} MB" else "${b / 1024} KB"

@Composable fun StorageDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val running by Srv.running.collectAsState()
    val busy by Setup.busy.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<SRow?>(null) }
    var delVer by remember { mutableStateOf<String?>(null) }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u: Uri? ->
        val v = delVer
        if (u != null && v != null) { Setup.deleteProfile(ctx, v, u); delVer = null; onClose() }
    }
    val srv = Srv.serverDir(ctx)
    val rows by produceState<List<SRow>?>(null, tick, busy) {
        value = withContext(Dispatchers.IO) {
            val l = ArrayList<SRow>()
            val mods = File(srv, "mods")
            l.add(SRow("Modlar", "${Setup.MC} · ${mods.listFiles()?.count { it.name.endsWith(".jar") } ?: 0} mod", dirSize(mods), "Tüm modlar silinecek.", null) {
                mods.listFiles()?.forEach { it.deleteRecursively() }
            })
            for (w in Srv.worlds(ctx)) {
                val d = File(srv, w); if (!d.exists()) continue
                l.add(SRow("Dünya: " + Srv.worldLabel(w), "${Setup.MC} dünyası", dirSize(d), "Dünya kalıcı silinir. İstersen önce Dünya sekmesinden dışa aktar.", null) { Srv.deleteWorld(ctx, w) })
            }
            val caches = listOf(".fabric", "versions", "libraries", "logs", "crash-reports", "cache").map { File(srv, it) }.filter { it.exists() }
            val cb = caches.sumOf { dirSize(it) } + dirSize(ctx.cacheDir)
            l.add(SRow("Ön bellek", "Oyun dosyaları, kütüphaneler, günlükler", cb, "Silinirse sonraki açılışta Minecraft yeniden indirilir (birkaç dakika sürer). Dünyalar ve modlar etkilenmez.", null) {
                caches.forEach { it.deleteRecursively() }; ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            })
            for (p in Setup.profiles(ctx)) {
                l.add(SRow("Sürüm ${p.ver}", "${p.mods} mod · ${p.worlds.size} dünya (saklanan)", dirSize(File(srv, "profiles/${p.ver}")), null, p.ver, null))
            }
            val old = File(ctx.filesDir, "backups"); val arc = File(ctx.filesDir, "archive")
            val ob = dirSize(old) + dirSize(arc)
            if (ob > 0) l.add(SRow("Eski yedek dosyaları", "Eski sürümlerden kalan", ob, "Silinecek.", null) { old.deleteRecursively(); arc.deleteRecursively() })
            l
        }
    }
    val jre by produceState(0L) { value = withContext(Dispatchers.IO) { dirSize(File(ctx.filesDir, "jre")) } }
    Dialog(onDismissRequest = onClose) {
        Card(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Depolama", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClose) { Text("✕", fontSize = 18.sp) }
                }
                val total = (rows?.sumOf { it.bytes } ?: 0L) + jre
                Text("Toplam: ${mb(total)} · Java ${mb(jre)} (silinemez)", fontSize = 12.sp, color = Muted)
                if (running) Text("Silmek için önce sunucuyu durdurun.", fontSize = 11.sp, color = Amber)
                if (rows == null) Text("Hesaplanıyor…", fontSize = 12.sp)
                rows?.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(r.title, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("${r.sub} · ${mb(r.bytes)}", fontSize = 11.sp, color = Muted)
                        }
                        OutlinedButton({ if (r.ver != null) delVer = r.ver else confirm = r }, enabled = !running && !busy) { Text("Sil", fontSize = 12.sp, color = Red) }
                    }
                }
            }
        }
    }
    confirm?.let { r ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("${r.title} silinsin mi?") }, text = { Text(r.warn ?: "") },
            confirmButton = { TextButton({ confirm = null; kotlin.concurrent.thread { try { r.del?.invoke() } catch (e: Throwable) { Srv.add("[APK] Silme hatası: $e") }; tick++ } }) { Text("Sil", color = Red) } },
            dismissButton = { TextButton({ confirm = null }) { Text("Vazgeç") } })
    }
    delVer?.let { v ->
        val wn = Setup.profiles(ctx).firstOrNull { it.ver == v }?.worlds?.size ?: 0
        AlertDialog(onDismissRequest = { delVer = null }, title = { Text("$v sürümü silinsin mi?") },
            text = { Text(if (wn > 0) "Modları silinecek. $wn dünya silinmeden önce seçeceğin klasöre zip olarak çıkarılır; çıkarma doğrulanmazsa hiçbir şey silinmez." else "Modları silinecek. Dünya yok.") },
            confirmButton = { Button({ if (wn > 0) pickTree.launch(null) else { Setup.deleteProfile(ctx, v, null); delVer = null; onClose() } }) { Text(if (wn > 0) "Klasör seç ve sil" else "Sil") } },
            dismissButton = { TextButton({ delVer = null }) { Text("Vazgeç") } })
    }
}
