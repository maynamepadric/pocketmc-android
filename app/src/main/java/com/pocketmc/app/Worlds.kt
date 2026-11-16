@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
package com.pocketmc.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipOutputStream
import java.io.FileOutputStream
import java.io.BufferedOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

@Composable fun rememberExporter(): (File) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<File?>(null) }
    val l = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { u ->
        val f = pending
        if (u != null && f != null) thread {
            try {
                ctx.contentResolver.openOutputStream(u)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
                Srv.toast(ctx, "Dışa aktarıldı: ${f.name}")
            } catch (e: Throwable) { Srv.toast(ctx, "Dışa aktarma hatası: $e") }
        }
    }
    return { f -> pending = f; l.launch(f.name) }
}

@Composable fun WorldsHome() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    key(tick) { WorldDetail(Srv.activeWorld(ctx), { tick++ }, { }, single = true) }
}

@Composable fun WorldDetail(folder: String, onBack: () -> Unit, onRenamed: (String) -> Unit, single: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by Srv.running.collectAsState()
    val p = Srv.prefs(ctx)
    var tick by remember { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var delW by remember { mutableStateOf(false) }
    val export = rememberExporter()
    val active = remember(tick) { Srv.activeWorld(ctx) }
    val isActive = active == folder
    val busyW = running && isActive
    val dir = File(Srv.serverDir(ctx), folder)
    val mb by produceState(0L, tick) { value = withContext(Dispatchers.IO) { dirSize(dir) / 1048576 } }
    val imp = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) thread {
            try { ctx.contentResolver.openInputStream(u)!!.use { Srv.importWorld(ctx, it, folder) }; tick++ }
            catch (e: Throwable) { Srv.add("[APK] HATA: $e"); Srv.toast(ctx, "İçe aktarma başarısız, ayrıntı günlükte") }
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!single) TextButton(onBack) { Text("← Geri") }
                Text(Srv.worldLabel(folder), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (isActive) Text("AKTİF", color = Green, fontWeight = FontWeight.Bold)
            }
            Text("$mb MB" + (if (!File(dir, "level.dat").exists()) " • ilk açılışta üretilecek" else "") +
                (if (busyW) " • sunucu bu dünyayı çalıştırıyor" else ""),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!single) Button({
                    p.edit().putString("world", folder).apply(); Srv.toast(ctx, "Aktif: ${Srv.worldLabel(folder)} (sunucuyu başlatınca açılır)"); tick++
                }, enabled = !running && !isActive) { Text("Bu dünyayı aç") }
                OutlinedButton({ settings = true }) { Text("Oyun ayarı") }
                OutlinedButton({ imp.launch(arrayOf("*/*")) }, enabled = !busyW) { Text("İçe aktar (zip)") }
                OutlinedButton({
                    scope.launch(Dispatchers.IO) {
                        try {
                            val z = File(ctx.cacheDir, Srv.tag(folder) + ".zip"); z.delete()
                            ZipOutputStream(BufferedOutputStream(FileOutputStream(z))).use { Srv.zipDir(dir, Srv.tag(folder), it) }
                            withContext(Dispatchers.Main) { export(z) }
                        } catch (e: Throwable) { Srv.add("[APK] Dışa aktarma hatası: $e"); Srv.toast(ctx, "Dışa aktarılamadı, ayrıntı günlükte") }
                    }
                }, enabled = !busyW && File(dir, "level.dat").exists()) { Text("Dışa aktar (zip)") }
                TextButton({ delW = true }, enabled = !busyW) { Text(if (single) "Dünyayı sıfırla" else "Dünyayı sil", color = Red) }
            }
        }
        item { SyncCard(folder, busyW) }
    }
    if (settings) WorldDialog(folder) { settings = false }
    if (rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text("Yeniden adlandır") },
        text = { OutlinedTextField(newName, { newName = it.trim() }, singleLine = true, label = { Text("Yeni ad (harf, rakam, _)") }) },
        confirmButton = { TextButton({
            val err = Srv.renameWorld(ctx, folder, newName)
            if (err != null) Srv.toast(ctx, err) else { rename = false; onRenamed("w_$newName") }
        }, enabled = newName.isNotEmpty()) { Text("Kaydet") } },
        dismissButton = { TextButton({ rename = false }) { Text("Vazgeç") } })
    if (delW) AlertDialog(onDismissRequest = { delW = false }, title = { Text(if (single) "Dünya sıfırlansın mı?" else "${Srv.worldLabel(folder)} silinsin mi?") },
        text = { Text("Dünya kalıcı silinir, geri alınamaz. Sunucu bir sonraki açılışta yeni bir dünya üretir. İstersen önce \"Dışa aktar\" ile telefona çıkar.") },
        confirmButton = { TextButton({ delW = false; scope.launch(Dispatchers.IO) { Srv.deleteWorld(ctx, folder); Srv.toast(ctx, "Silindi") }; onBack() }) { Text("Sil", color = Red) } },
        dismissButton = { TextButton({ delW = false }) { Text("Vazgeç") } })
}

@Composable fun WorldBubbles(onChanged: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val running by Srv.running.collectAsState()
    val active = remember(tick) { Srv.activeWorld(ctx) }
    val list = remember(tick) { Srv.worlds(ctx).sortedBy { if (it == active) 0 else 1 } }
    var dlg by remember { mutableStateOf(false) }
    var delF by remember { mutableStateOf<String?>(null) }
    var stor by remember { mutableStateOf(false) }
    var renF by remember { mutableStateOf<String?>(null) }
    var renName by remember { mutableStateOf("") }
    val sizes by produceState(Pair(0L, 0L), tick) {
        value = withContext(Dispatchers.IO) { Pair(dirSize(File(ctx.filesDir, "jre")) / 1048576, dirSize(Srv.serverDir(ctx)) / 1048576) }
    }
    val oldMb by produceState(0L, tick) {
        value = withContext(Dispatchers.IO) { dirSize(File(ctx.filesDir, "backups")) / 1048576 + dirSize(File(ctx.filesDir, "archive")) / 1048576 }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list, key = { it }) { f ->
            val isAct = f == active
            val dir = File(Srv.serverDir(ctx), f)
            val mb by produceState(0L, tick, f) { value = withContext(Dispatchers.IO) { dirSize(dir) / 1048576 } }
            val pr = remember(tick, f) { Srv.worldProps(ctx, f).ifEmpty { Srv.readProps(File(Srv.serverDir(ctx), "server.properties")) } }
            val last = File(dir, "level.dat").lastModified()
            Card(Modifier.fillMaxWidth().animateContentSize().let { if (isAct) it.border(1.5.dp, Green, RoundedCornerShape(10.dp)) else it }) {
                Column(Modifier.padding(if (isAct) 16.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Srv.worldLabel(f), Modifier.weight(1f), fontWeight = FontWeight.ExtraBold, fontSize = if (isAct) 17.sp else 14.sp)
                        if (isAct) Text("AKTİF", color = Green, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp,
                            modifier = Modifier.background(Ui.accent.deep, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                    Text("$mb MB" + (if (last > 0) " · son oynanma " + java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(java.util.Date(last)) else " · ilk açılışta üretilecek"),
                        fontSize = 11.sp, color = Muted)
                    Text("Mod: ${pr["gamemode"] ?: "survival"} · Zorluk: ${pr["difficulty"] ?: "easy"}", fontSize = 11.sp, color = Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (isAct) Text(if (running) "Şu an çalışıyor" else "Başlat'a basınca bu dünya açılır", Modifier.weight(1f), fontSize = 11.sp, color = Green)
                        else Button({ Srv.prefs(ctx).edit().putString("world", f).apply(); tick++; onChanged() }, enabled = !running) { Text("Aktif yap", fontSize = 12.sp) }
                        if (!isAct) Spacer(Modifier.weight(1f))
                        TextButton({ renName = Srv.tag(f).let { if (it == "world") "" else it }; renF = f }, enabled = !(running && isAct)) { Text("Adı değiştir", fontSize = 12.sp) }
                        TextButton({ delF = f }, enabled = !(running && isAct)) { Text("Sil", color = Red, fontSize = 12.sp) }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), onClick = { dlg = true }) {
                Box(Modifier.fillMaxWidth().padding(vertical = 22.dp), contentAlignment = Alignment.Center) {
                    Text("+ Boş dünya oluştur", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = Green)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), onClick = { stor = true }) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Depolama (ayrıntı ve silme için dokun)", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                    Text("Java: ${sizes.first} MB · Sunucu ve dünyalar: ${sizes.second} MB", fontSize = 11.sp, color = Muted)
                    if (oldMb > 0) {
                        Text("Eski sürümlerden kalan yedek dosyaları: $oldMb MB", fontSize = 11.sp, color = Muted)
                        OutlinedButton({ thread { File(ctx.filesDir, "backups").deleteRecursively(); File(ctx.filesDir, "archive").deleteRecursively(); tick++ } }) { Text("Sil ve yer aç", fontSize = 12.sp) }
                    }
                }
            }
        }
    }
    if (dlg) WorldDialog("") { dlg = false; tick++; onChanged() }
    if (stor) StorageDialog { stor = false; tick++; onChanged() }
    renF?.let { f ->
        AlertDialog(onDismissRequest = { renF = null }, title = { Text("Dünyanın adını değiştir") },
            text = { OutlinedTextField(renName, { renName = it.trim() }, singleLine = true, label = { Text("Yeni ad (harf, rakam, _)") }) },
            confirmButton = { TextButton({
                val err = Srv.renameWorld(ctx, f, renName)
                if (err != null) Srv.toast(ctx, err) else { renF = null; tick++; onChanged() }
            }, enabled = renName.isNotEmpty()) { Text("Kaydet") } },
            dismissButton = { TextButton({ renF = null }) { Text("Vazgeç") } })
    }
    delF?.let { f ->
        AlertDialog(onDismissRequest = { delF = null }, title = { Text("${Srv.worldLabel(f)} silinsin mi?") },
            text = { Text("Bu dünya kalıcı silinir, geri alınamaz. Önce \"Dışa aktar (zip)\" ile telefona çıkarabilirsin.") },
            confirmButton = { TextButton({ delF = null; thread { Srv.deleteWorld(ctx, f); Srv.toast(ctx, "Silindi"); tick++; onChanged() } }) { Text("Sil", color = Red) } },
            dismissButton = { TextButton({ delF = null }) { Text("Vazgeç") } })
    }
}
