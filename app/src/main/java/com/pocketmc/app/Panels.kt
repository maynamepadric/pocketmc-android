@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
package com.pocketmc.app

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ---------------- Mod sayfası ----------------

@Composable fun ModsTab2() {
    var detail by remember { mutableStateOf<String?>(null) }
    var cfgJar by remember { mutableStateOf<File?>(null) }
    BackHandler(detail != null) { detail = null }
    val d = detail
    if (d != null) ModDetail(d) { detail = null } else ModsList({ detail = it }, { cfgJar = it })
    val cj = cfgJar
    if (cj != null) ConfigDialog(cj) { cfgJar = null }
}

private fun side(s: String) = when (s) {
    "required" -> "zorunlu"; "optional" -> "isteğe bağlı"; "unsupported" -> "desteklenmiyor"; else -> s
}

@Composable fun VersionCard(v: JSONObject, ok: Boolean, names: Map<String, String>, busy: Boolean, blocked: Boolean, onInstall: () -> Unit) {
    val gv = v.getJSONArray("game_versions")
    val gvs = (0 until gv.length()).map { gv.getString(it) }
    val req = mutableListOf<String>()
    val inc = mutableListOf<String>()
    val deps = v.optJSONArray("dependencies")
    if (deps != null) for (i in 0 until deps.length()) {
        val d = deps.getJSONObject(i)
        if (d.isNull("project_id")) continue
        val n = names[d.getString("project_id")] ?: d.getString("project_id")
        when (d.optString("dependency_type")) { "required" -> req.add(n); "incompatible" -> inc.add(n) }
    }
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(v.optString("name"), fontWeight = FontWeight.Medium)
                    Text("${v.optString("version_number")} • ${v.optString("version_type")} • ${v.optString("date_published").take(10)}",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onInstall, enabled = ok && !busy && !blocked) { Text(if (busy) "…" else if (ok) "Yükle" else "Uyumsuz") }
            }
            Text("Minecraft: " + gvs.take(6).joinToString(", ") + (if (gvs.size > 6) " …" else ""), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (req.isNotEmpty()) Text("Gerekli: " + req.joinToString(", ") + " (otomatik kurulur)", fontSize = 12.sp, color = Amber)
            if (inc.isNotEmpty()) Text("Çakışır: " + inc.joinToString(", "), fontSize = 12.sp, color = Red)
        }
    }
}

@Composable fun ModDetail(id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var proj by remember { mutableStateOf<JSONObject?>(null) }
    var vers by remember { mutableStateOf(listOf<JSONObject>()) }
    var names by remember { mutableStateOf(mapOf<String, String>()) }
    var busy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id) {
        withContext(Dispatchers.IO) {
            try {
                var pid = id
                if (id.startsWith("file:")) {
                    val vf = JSONObject(Net.getString("https://api.modrinth.com/v2/version_file/" +
                        Setup.sha1(File(id.removePrefix("file:"))) + "?algorithm=sha1"))
                    pid = vf.getString("project_id")
                }
                proj = JSONObject(Net.getString("https://api.modrinth.com/v2/project/$pid"))
                val arr = JSONArray(Net.getString("https://api.modrinth.com/v2/project/$pid/version?loaders=" + Mods.enc("[\"fabric\"]")))
                vers = (0 until arr.length()).map { arr.getJSONObject(it) }
                val ids = linkedSetOf<String>()
                for (v in vers) {
                    val d = v.optJSONArray("dependencies") ?: continue
                    for (i in 0 until d.length()) { val o = d.getJSONObject(i); if (!o.isNull("project_id")) ids.add(o.getString("project_id")) }
                }
                if (ids.isNotEmpty()) {
                    val pa = JSONArray(Net.getString("https://api.modrinth.com/v2/projects?ids=" + Mods.enc(JSONArray(ids.take(40)).toString())))
                    names = (0 until pa.length()).associate { pa.getJSONObject(it).getString("id") to pa.getJSONObject(it).getString("title") }
                }
            } catch (e: Throwable) {
                err = if (id.startsWith("file:") && (e.message ?: "").contains("404")) "Bu dosya Modrinth'te bulunamadı (elle eklenmiş olabilir)."
                else "Hata: ${e.message}"
            }
            loading = false
        }
    }
    val p = proj
    val mc = Setup.MC
    val match = vers.filter { v -> val g = v.getJSONArray("game_versions"); (0 until g.length()).any { g.getString(it) == mc } }
    val rest = vers - match.toSet()
    val serverSide = p?.optString("server_side") ?: ""
    val blocked = serverSide == "unsupported"
    fun install(v: JSONObject) {
        scope.launch(Dispatchers.IO) {
            busy = v.getString("id")
            val r = try { Mods.installVersion(ctx, v).trim() } catch (e: Throwable) { "Hata: ${e.message}" }
            busy = null
            Srv.toast(ctx, r + "\n(Sunucuyu yeniden başlatınca yüklenir)")
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onBack) { Text("← Geri") }
                Text(p?.optString("title") ?: "Yükleniyor…", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        val e = err
        if (e != null) item { Text(e, color = Red) }
        if (p != null) {
            item {
                Text(p.optString("description"))
                Text("${p.optLong("downloads")} indirme", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Gereksinimler", fontWeight = FontWeight.Medium)
                        Text("Yükleyici: Fabric")
                        Text("Sunucu tarafı: ${side(serverSide)}")
                        Text("Oyuncu (istemci) tarafı: ${side(p.optString("client_side"))}")
                        if (blocked) Text("Bu mod yalnızca oyuncu tarafında çalışır, sunucuya kurulmaz.", color = Amber)
                        else if (p.optString("client_side") == "required") Text("Oyuncuların da bu modu kurması gerekir.", color = Amber)
                    }
                }
            }
            item {
                OutlinedButton({
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://modrinth.com/mod/" + p.optString("slug"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Modrinth'te aç") }
            }
            item { Text("Sunucunuza uyan sürümler ($mc) • ${match.size}", style = MaterialTheme.typography.titleMedium, color = Green) }
            if (match.isEmpty()) item { Text("Bu mod için $mc sürümü yok.", color = Amber) }
            items(match, key = { it.getString("id") }) { v ->
                VersionCard(v, true, names, busy == v.getString("id"), blocked || busy != null) { install(v) }
            }
            if (rest.isNotEmpty()) item { Text("Diğer sürümler (uyumsuz) • ${rest.size}", style = MaterialTheme.typography.titleMedium) }
            items(rest, key = { "r" + it.getString("id") }) { v ->
                VersionCard(v, false, names, false, true) { }
            }
        }
    }
}

// ---------------- Mod ayarları (dişli baloncuk) ----------------

// kind: 0 = evet/hayır, 1 = sayı, 2 = metin, 3 = ham (liste vb.)
class Ent(val path: String, val kind: Int, val orig: String, val comment: String, val line: Int = -1, val keys: List<String> = emptyList()) {
    var v by mutableStateOf(orig)
}

object Cfg {
    private val num = Regex("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?")
    private val lineRe = Regex("^(\\s*)([^=:#/;\\s\\[][^=:]*?)(\\s*[=:]\\s*)(.*)$")
    private class Span(val start: Int, val end: Int, val quoted: Boolean)

    private fun kindOf(s: String) = when {
        s == "true" || s == "false" -> 0
        num.matches(s) -> 1
        s.startsWith("[") || s.startsWith("{") -> 3
        else -> 2
    }

    private fun span(rest: String, props: Boolean): Span {
        if (rest.isEmpty()) return Span(0, 0, false)
        val c = rest[0]
        if (!props && (c == '"' || c == '\'')) { val e = rest.indexOf(c, 1); if (e > 0) return Span(1, e, true) }
        if (!props && (c == '[' || c == '{')) return Span(0, rest.trimEnd().length, false)
        var end = rest.length
        if (!props) { val m = Regex("\\s+(#|//)").find(rest); if (m != null) end = m.range.first }
        return Span(0, rest.substring(0, end).trimEnd().length, false)
    }

    fun parseText(text: String, ext: String): List<Ent> {
        val props = ext == "properties"
        val out = mutableListOf<Ent>()
        var section = ""
        val yaml = ext == "yml" || ext == "yaml"
        val stack = mutableListOf<Pair<Int, String>>()
        val cm = mutableListOf<String>()
        text.split("\n").forEachIndexed { i, raw ->
            val l = raw.trimEnd('\r')
            val t = l.trim()
            if (t.isEmpty()) { cm.clear(); return@forEachIndexed }
            if (t.startsWith("#") || t.startsWith("//") || t.startsWith(";")) {
                cm.add(t.trimStart('#', '/', ';', ' ')); if (cm.size > 3) cm.removeAt(0); return@forEachIndexed
            }
            if (!props && t.startsWith("[") && t.endsWith("]") && !t.contains("=")) { section = t.trim('[', ']'); cm.clear(); return@forEachIndexed }
            val m = lineRe.find(l)
            if (m == null) { cm.clear(); return@forEachIndexed }
            val key = m.groupValues[2].trim().trim('"')
            val rest = m.groupValues[4]
            if (yaml) {
                if (t.startsWith("-")) { cm.clear(); return@forEachIndexed }
                val ind = m.groupValues[1].length
                while (stack.isNotEmpty() && stack.last().first >= ind) stack.removeAt(stack.size - 1)
                if (rest.isBlank()) { stack.add(ind to key); cm.clear(); return@forEachIndexed }
                section = stack.joinToString(".") { it.second }
            }
            val sp = span(rest, props)
            val value = rest.substring(sp.start, sp.end)
            val kind = if (sp.quoted) 2 else kindOf(value)
            out.add(Ent((if (section.isNotEmpty()) "$section." else "") + key, kind, value, cm.joinToString(" "), i))
            cm.clear()
        }
        return out
    }

    fun applyText(text: String, ents: List<Ent>, ext: String): String {
        val props = ext == "properties"
        val lines = text.split("\n").toMutableList()
        for (e in ents) {
            if (e.v == e.orig || e.line < 0 || e.line >= lines.size) continue
            val raw = lines[e.line]
            val cr = raw.endsWith("\r")
            val l = raw.trimEnd('\r')
            val m = lineRe.find(l) ?: continue
            val prefix = m.groupValues[1] + m.groupValues[2] + m.groupValues[3]
            val rest = m.groupValues[4]
            val sp = span(rest, props)
            lines[e.line] = prefix + rest.substring(0, sp.start) + e.v + rest.substring(sp.end) + (if (cr) "\r" else "")
        }
        return lines.joinToString("\n")
    }

    fun parseJson(text: String): List<Ent>? = try {
        val t = text.trim()
        if (t.startsWith("{")) { val out = mutableListOf<Ent>(); walk(JSONObject(t), emptyList(), out); out } else null
    } catch (e: Exception) { null }

    private fun walk(o: JSONObject, prefix: List<String>, out: MutableList<Ent>) {
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            val ks = prefix + k
            val p = ks.joinToString(".")
            when (val v = o.get(k)) {
                is JSONObject -> walk(v, ks, out)
                is Boolean -> out.add(Ent(p, 0, v.toString(), "", -1, ks))
                is Int, is Long, is Double -> out.add(Ent(p, 1, v.toString(), "", -1, ks))
                is String -> out.add(Ent(p, 2, v, "", -1, ks))
                is JSONArray -> out.add(Ent(p, 3, v.toString(), "", -1, ks))
                else -> {}
            }
        }
    }

    fun applyJson(text: String, ents: List<Ent>): String {
        val root = JSONObject(text.trim())
        for (e in ents) {
            if (e.v == e.orig) continue
            var o = root
            for (k in e.keys.dropLast(1)) o = o.getJSONObject(k)
            val value: Any = when (e.kind) {
                0 -> e.v == "true"
                1 -> if (e.v.contains('.') || e.v.contains('e', true)) e.v.toDouble() else e.v.toLong()
                3 -> JSONArray(e.v)
                else -> e.v
            }
            o.put(e.keys.last(), value)
        }
        return root.toString(2)
    }
}

private fun pretty(e: Ent): String {
    val k = e.keys.lastOrNull() ?: e.path.substringAfterLast('.')
    return k.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ").replace('_', ' ').replace('-', ' ').replaceFirstChar { it.uppercase() }
}

private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

private class Hdr(val name: String, val id: Int)

// Modun kendi dil dosyasından (Cloth Config / AutoConfig / YACL) ayar adı ve açıklamalarını çıkarır
private fun langIndex(lang: Map<String, String>): Pair<Map<String, String>, Map<String, String>> {
    val labels = HashMap<String, String>()
    val tips = HashMap<String, String>()
    val tipSuf = listOf(".@tooltip", ".tooltip", ".desc", ".description")
    for ((k, v) in lang) {
        val kl = k.lowercase()
        if (!(kl.contains("option") || kl.contains("config") || kl.contains("setting"))) continue
        val suf = tipSuf.firstOrNull { kl.endsWith(it) }
        if (suf != null) tips.putIfAbsent(norm(k.dropLast(suf.length).substringAfterLast('.')), v)
        else labels.putIfAbsent(norm(k.substringAfterLast('.')), v)
    }
    return labels to tips
}

private fun cfgFilesFor(cfgDir: File, info: ModInfo?, jar: File, all: List<File>): List<File> {
    val keys = listOfNotNull(info?.id, jar.name.substringBefore('-').substringBefore('_').substringBefore('.'))
        .map { norm(it) }.filter { it.length >= 3 }.distinct()
    return all.filter { f -> val n = norm(f.relativeTo(cfgDir).path); keys.any { n.contains(it) } }
}

@Composable fun ColumnScope.CfgForm(ents: List<Ent>, lang: Map<String, String>) {
    var q by remember { mutableStateOf("") }
    var showKeys by remember { mutableStateOf(false) }
    val (labels, tips) = remember(lang) { langIndex(lang) }
    val shown = ents.filter { q.isBlank() || it.path.contains(q, true) || it.comment.contains(q, true) }
    val rows = buildList<Any> {
        var last: String? = null
        var n = 0
        for (e in shown) {
            val g = e.path.substringBeforeLast('.', "")
            if (g != last) { last = g; if (g.isNotEmpty()) add(Hdr(g, n++)) }
            add(e)
        }
    }
    OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true,
        placeholder = { Text("Ayar ara (${ents.size} ayar)") })
    LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Değişiklikler sunucu yeniden başlayınca geçerli olur.", Modifier.weight(1f), fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Teknik adlar  ", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Switch(showKeys, { showKeys = it })
            }
        }
        items(rows, key = { if (it is Ent) it.path + "#" + it.line else "h" + (it as Hdr).id }) { r ->
            if (r is Hdr) {
                Text(r.name.split('.').joinToString(" › ") { p -> p.replace('_', ' ').replace('-', ' ').replaceFirstChar { c -> c.uppercase() } },
                    Modifier.padding(top = 8.dp), color = Green, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            } else if (r is Ent) {
                val e = r
                val k = norm(e.keys.lastOrNull() ?: e.path.substringAfterLast('.'))
                val label = labels[k] ?: pretty(e)
                val tip = tips[k] ?: e.comment
                val bad = e.kind == 1 && e.v.toDoubleOrNull() == null
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(label, fontWeight = FontWeight.Medium)
                                if (showKeys) Text(e.path, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (e.kind == 0) Switch(e.v == "true", { on -> e.v = on.toString() })
                        }
                        if (tip.isNotEmpty()) Text(tip, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (e.kind != 0) OutlinedTextField(e.v, { e.v = it }, Modifier.fillMaxWidth(), singleLine = e.kind != 3, isError = bad,
                            keyboardOptions = KeyboardOptions(keyboardType = if (e.kind == 1) KeyboardType.Decimal else KeyboardType.Text))
                        if (e.v != e.orig) Text("Değişti (önceki: ${e.orig})", fontSize = 10.sp, color = Amber)
                    }
                }
            }
        }
    }
}

@Composable fun ConfigDialog(modJar: File? = null, onClose: () -> Unit = { Srv.showCfg.value = false }) {
    val ctx = LocalContext.current
    var cur by remember { mutableStateOf<File?>(null) }
    var text by remember { mutableStateOf("") }
    var orig by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(true) }
    var ents by remember { mutableStateOf<List<Ent>?>(null) }
    var canForm by remember { mutableStateOf(false) }
    val info = remember(modJar) { if (modJar != null) Mods.info(modJar) else null }
    val cfgDir = File(Srv.serverDir(ctx), "config")
    val exts = setOf("json", "json5", "toml", "properties", "cfg", "conf", "yml", "yaml", "txt")
    val files = remember(modJar) {
        val all = cfgDir.walkTopDown().filter { it.isFile && it.length() < 300000 && it.extension.lowercase() in exts }.sortedBy { it.path }.toList()
        if (modJar == null) all else cfgFilesFor(cfgDir, info, modJar, all)
    }
    fun parse(ext: String, t: String): List<Ent>? = when (ext) {
        "json" -> Cfg.parseJson(t)
        "properties", "toml", "cfg", "conf", "yml", "yaml" -> Cfg.parseText(t, ext)
        else -> null
    }
    fun open(f: File) {
        val t = f.readText()
        val e = parse(f.extension.lowercase(), t)
        cur = f; text = t; orig = t; ents = e
        canForm = !e.isNullOrEmpty()
        advanced = !canForm
    }
    LaunchedEffect(Unit) { if (modJar != null && files.size == 1) open(files[0]) }
    fun back() { if (cur != null && !(modJar != null && files.size == 1)) cur = null else onClose() }
    val c = cur
    val ext = c?.extension?.lowercase() ?: ""
    val dirty = text != orig || (!advanced && ents?.any { it.v != it.orig } == true)
    fun applyForm(): String? {
        val e = ents ?: return text
        if (e.any { it.kind == 1 && it.v.toDoubleOrNull() == null }) { Srv.toast(ctx, "Geçersiz sayı var, kırmızı alanları düzeltin"); return null }
        return try { if (ext == "json") Cfg.applyJson(text, e) else Cfg.applyText(text, e, ext) }
        catch (x: Exception) { Srv.toast(ctx, "Hata: ${x.message}"); null }
    }
    fun save() {
        val f = cur ?: return
        val t = if (advanced) text else (applyForm() ?: return)
        if (ext == "json") {
            try { val s = t.trim(); if (s.startsWith("[")) JSONArray(s) else JSONObject(s) }
            catch (x: Exception) { Srv.toast(ctx, "Geçersiz JSON, kaydedilmedi: ${x.message}"); return }
        }
        try {
            f.copyTo(File(f.path + ".bak"), true)
            f.writeText(t); text = t; orig = t; ents = parse(ext, t)
            Srv.toast(ctx, "Kaydedildi (sunucuyu yeniden başlatınca geçerli)")
        } catch (x: Exception) { Srv.toast(ctx, "Hata: ${x.message}") }
    }
    val title = if (modJar != null) "⚙  " + (info?.name ?: modJar.name.removeSuffix(".disabled"))
        else if (c == null) "⚙  Mod ayarları" else c.relativeTo(cfgDir).path
    Dialog(onDismissRequest = { back() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0F0D)) {
            Column(Modifier.padding(top = 24.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
                    if (c != null) {
                        if (canForm) TextButton({
                            if (advanced) {
                                val e = parse(ext, text)
                                if (e.isNullOrEmpty()) Srv.toast(ctx, "Form açılamadı (dosya biçimi hatalı olabilir)") else { ents = e; advanced = false }
                            } else applyForm()?.let { text = it; advanced = true }
                        }) { Text(if (advanced) "Form" else "Kod") }
                        TextButton({ text = orig; if (!advanced) ents = parse(ext, orig) }, enabled = dirty) { Text("Geri al") }
                        TextButton({ save() }, enabled = dirty) { Text("Kaydet") }
                    }
                    TextButton({ back() }) { Text(if (c == null || (modJar != null && files.size == 1)) "✕" else "←", fontSize = 20.sp) }
                }
                if (modJar != null) {
                    val sub = listOfNotNull(info?.version?.takeIf { it.isNotBlank() }?.let { "v$it" },
                        info?.authors?.takeIf { it.isNotBlank() }, c?.relativeTo(cfgDir)?.path).joinToString(" • ")
                    if (sub.isNotEmpty()) Text(sub, Modifier.padding(horizontal = 12.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    info?.description?.takeIf { it.isNotBlank() }?.let {
                        Text(it, Modifier.padding(horizontal = 12.dp, vertical = 2.dp), fontSize = 12.sp, maxLines = 2)
                    }
                }
                if (c == null) {
                    LazyColumn(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (files.isEmpty()) item {
                            Text(if (modJar != null) "Bu modun ayar dosyası yok. Mod ayar sunmuyor olabilir ya da dosyayı sunucu bir kez açılınca oluşturur."
                                else "Ayar dosyası yok. Modlar, sunucu bir kez açılınca config/ klasörüne ayar dosyalarını oluşturur.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(files, key = { it.path }) { f ->
                            Card(onClick = { open(f) }) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(f.relativeTo(cfgDir).path, fontWeight = FontWeight.Medium)
                                    Text("${f.length() / 1024} KB", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                } else if (advanced) {
                    OutlinedTextField(text, { text = it }, Modifier.weight(1f).fillMaxWidth().padding(12.dp),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                } else {
                    ents?.let { CfgForm(it, info?.lang ?: emptyMap()) }
                }
            }
        }
    }
}

// ---------------- Komut / duyuru paneli ----------------

private fun loadHist(c: android.content.Context) =
    (Srv.prefs(c).getString("hist", "") ?: "").split("\n").filter { it.isNotBlank() }
private fun saveHist(c: android.content.Context, l: List<String>) =
    Srv.prefs(c).edit().putString("hist", l.joinToString("\n")).apply()

@Composable fun CommandPanel() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by Srv.status.collectAsState()
    var cmd by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var hist by remember { mutableStateOf(loadHist(ctx)) }
    val up = status == "Çalışıyor"
    fun run(raw: String) {
        val c = raw.trim().removePrefix("/").trim()
        if (c.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val r = Srv.rcon(ctx, c)
            result = if (r == null) "RCON'a ulaşılamadı (sunucu 'Çalışıyor' olmalı)" else r.ifBlank { "Tamam (çıktı yok)" }
            hist = (listOf(c) + hist.filter { it != c }).take(15); saveHist(ctx, hist)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Komut", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(cmd, { cmd = it }, Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("örn. /time set day") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { run(cmd); cmd = "" }))
                    Button({ run(cmd); cmd = "" }, enabled = cmd.isNotBlank() && up) { Text("Gönder") }
                }
                if (!up) Text("Sunucu 'Çalışıyor' olunca komut gönderilebilir.", fontSize = 11.sp, color = Amber)
                if (result.isNotEmpty()) Text(result, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                if (hist.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    hist.forEach { h -> AssistChip({ cmd = h }, label = { Text(h, maxLines = 1) }) }
                }
                Text("Başa / koyabilirsiniz, koymasanız da olur. Geçmişten dokunup tekrar kullanın.", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Oyunculara duyuru", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(msg, { msg = it }, Modifier.fillMaxWidth(), placeholder = { Text("örn. 5 dk içinde kapatacağım, işinizi bitirin") })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ run("say $msg"); msg = "" }, enabled = msg.isNotBlank() && up) { Text("Sohbette duyur") }
                    OutlinedButton({ run("title @a title {\"text\":${JSONObject.quote(msg)}}"); msg = "" }, enabled = msg.isNotBlank() && up) { Text("Ekranda göster") }
                }
                Text("Hazır duyurular", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip({ run("say Sunucu 5 dakika içinde kapanacak, işinizi bitirip çıkın!") }, enabled = up, label = { Text("5 dk uyarısı") })
                    AssistChip({ run("say Sunucu 1 dakika içinde kapanacak!") }, enabled = up, label = { Text("1 dk uyarısı") })
                    AssistChip({ run("say Dünya kaydediliyor, kısa bir takılma olabilir.") }, enabled = up, label = { Text("Kayıt uyarısı") })
                    AssistChip({ run("save-all flush") }, enabled = up, label = { Text("Dünyayı kaydet") })
                }
            }
        }
        Spacer(Modifier.height(72.dp))
    }
}
