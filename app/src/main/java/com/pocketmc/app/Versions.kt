@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.pocketmc.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Profil > "Minecraft sürümü": her sürümün modları ve dünyaları ayrı tutulur, sadece aktif sürüm görünür.
@Composable fun VersionCard() {
    val busy by Setup.busy.collectAsState()
    val prog by Setup.progress.collectAsState()
    val step by Setup.step.collectAsState()
    val err by Setup.error.collectAsState()
    val note by Setup.note.collectAsState()
    val running by Srv.running.collectAsState()
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Minecraft sürümü", style = MaterialTheme.typography.titleSmall, color = Green)
            Text("Şu an: ${Setup.MC} (Fabric)", fontSize = 14.sp)
            Text("Her sürümün modları ve dünyaları ayrı saklanır; geri döndüğünüzde yeniden görünür.", fontSize = 11.sp, color = Muted)
            Button({ open = true }, enabled = !busy && !running) { Text("Sürümleri yönet") }
            if (running) Text("Sürüm değiştirmek için önce sunucuyu durdurun.", fontSize = 11.sp, color = Amber)
            if (busy) { LinearProgressIndicator(progress = { prog }, Modifier.fillMaxWidth()); Text(step, fontSize = 11.sp, color = Muted) }
            err?.let { Text(it, fontSize = 12.sp, color = Red) }
            note?.let { Text(it, fontSize = 12.sp, color = Green) }
        }
    }
    if (open) VersionDialog { open = false }
}

@Composable private fun VersionDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val busy by Setup.busy.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(busy) { tick++ }
    val list = remember(tick) { Setup.profiles(ctx) }
    var avail by remember { mutableStateOf<List<String>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            val all = withContext(Dispatchers.IO) { Setup.gameVersions() }
            avail = all
        } catch (e: Throwable) { failed = true; avail = emptyList() }
    }
    val jm = remember { Setup.javaMajor(ctx) }
    var baseMods by remember { mutableStateOf(true) }
    var pending by remember { mutableStateOf<String?>(null) }     // geçiş onayı
    var delVer by remember { mutableStateOf<String?>(null) }      // silme onayı
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u: Uri? ->
        val v = delVer
        if (u != null && v != null) { Setup.deleteProfile(ctx, v, u); delVer = null; onClose() }
    }
    Dialog(onDismissRequest = onClose) {
        Card(Modifier.fillMaxWidth().heightIn(max = 330.dp)) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sürümler", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClose) { Text("✕", fontSize = 18.sp) }
                }
                Text("Aktif: ${Setup.MC}", fontSize = 13.sp, color = Green)
                if (list.isNotEmpty()) {
                    Text("Saklanan sürümler", fontSize = 12.sp, color = Muted)
                    list.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(p.ver, fontSize = 14.sp)
                                Text("${p.mods} mod · ${p.worlds.size} dünya · ${p.mb} MB" + if (p.offline) " · internetsiz açılır" else "", fontSize = 11.sp, color = Muted)
                            }
                            Button({ pending = p.ver }, enabled = !busy) { Text("Geç", fontSize = 12.sp) }
                            OutlinedButton({ delVer = p.ver }, enabled = !busy) { Text("Sil", fontSize = 12.sp) }
                        }
                    }
                }
                Text("Yeni sürüm", fontSize = 12.sp, color = Muted)
                val shown = (avail ?: emptyList()).filter { it != Setup.MC && list.none { p -> p.ver == it } }
                if (avail == null) Text("Sürümler yükleniyor…", fontSize = 12.sp)
                else if (failed) Text("Sürüm listesi alınamadı (internet?)", fontSize = 12.sp, color = Red)
                if (jm > 0) Text("Uygulamadaki Java: $jm. Kırmızı sürümler bu Java ile çalışmaz.", fontSize = 11.sp, color = Muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    shown.forEach { v ->
                        val bad = jm > 0 && jm < Setup.needJava(v)
                        AssistChip({ if (!bad) pending = v }, label = { Text(if (bad) "$v · Java ${Setup.needJava(v)}" else v, color = if (bad) Red else Color.Unspecified) },
                            enabled = !busy, border = androidx.compose.foundation.BorderStroke(1.dp, if (bad) Red else Line))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(baseMods, { baseMods = it })
                    Text("Yeni sürüme temel modları kur", fontSize = 12.sp)
                }
            }
        }
    }
    pending?.let { v ->
        AlertDialog(onDismissRequest = { pending = null },
            title = { Text("$v sürümüne geç?") },
            text = { Text("Şu anki ${Setup.MC} sürümünün modları ve dünyaları saklanır, $v sürümünün kendi modları ve dünyaları görünür. " +
                "Hiçbir şey silinmez; geri dönünce her şey yerinde olur.") },
            confirmButton = { Button({ val t = v; pending = null; Setup.switchVersion(ctx, t, baseMods); onClose() }) { Text("Geç") } },
            dismissButton = { TextButton({ pending = null }) { Text("Vazgeç") } })
    }
    delVer?.let { v ->
        val info = list.firstOrNull { it.ver == v }
        val wn = info?.worlds?.size ?: 0
        AlertDialog(onDismissRequest = { delVer = null },
            title = { Text("$v sürümü silinsin mi?") },
            text = { Text(if (wn > 0) "$v sürümünün modları silinecek. $wn dünya silinmeden önce seçeceğiniz klasöre (örn. Download) zip olarak çıkarılır; çıkarma doğrulanmazsa hiçbir şey silinmez."
                else "$v sürümünün modları silinecek. Dünya yok.") },
            confirmButton = { Button({
                if (wn > 0) pickTree.launch(null) else { Setup.deleteProfile(ctx, v, null); delVer = null; onClose() }
            }) { Text(if (wn > 0) "Klasör seç ve sil" else "Sil") } },
            dismissButton = { TextButton({ delVer = null }) { Text("Vazgeç") } })
    }
}
