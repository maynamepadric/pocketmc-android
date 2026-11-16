@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.pocketmc.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.net.Socket

// ---------------- Whitelist (sunucu kapalıyken de çalışır) ----------------

object WL {
    fun add(c: Context, name: String): String {
        if (Srv.running.value) {
            val r = Srv.rcon(c, "whitelist add $name")
            if (r != null) return r.ifBlank { "Eklendi" }
        }
        val f = File(Srv.serverDir(c), "whitelist.json")
        val arr = try { JSONArray(f.readText()) } catch (e: Exception) { JSONArray() }
        for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("name").equals(name, true)) return "$name zaten listede"
        arr.put(JSONObject().put("uuid", Setup.offlineUuid(name)).put("name", name))
        Srv.serverDir(c).mkdirs()
        f.writeText(arr.toString(2))
        return "$name eklendi (sunucu kapalı, açılınca geçerli)"
    }
}

// ---------------- Uyku modu dinleyicisi (Minecraft protokolünün küçük bir parçası) ----------------

object Sleeper {
    private fun readVar(i: java.io.InputStream): Int {
        var n = 0; var sh = 0
        while (true) {
            val b = i.read(); if (b < 0) throw java.io.IOException("eof")
            n = n or ((b and 0x7F) shl sh)
            if ((b and 0x80) == 0) return n
            sh += 7; if (sh > 35) throw java.io.IOException("varint")
        }
    }
    private fun varBytes(v: Int): ByteArray {
        val o = java.io.ByteArrayOutputStream(); var x = v
        while (true) {
            if ((x and 0x7F.inv()) == 0) { o.write(x); break }
            o.write((x and 0x7F) or 0x80); x = x ushr 7
        }
        return o.toByteArray()
    }
    private fun packet(id: Int, body: ByteArray): ByteArray { val inner = varBytes(id) + body; return varBytes(inner.size) + inner }
    private fun str(s: String): ByteArray { val b = s.toByteArray(Charsets.UTF_8); return varBytes(b.size) + b }
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    fun handle(c: Context, s: Socket) {
        try {
            s.soTimeout = 4000
            val i = DataInputStream(java.io.BufferedInputStream(s.getInputStream())); val o = s.getOutputStream()
            readVar(i)
            if (readVar(i) != 0) return
            val proto = readVar(i)
            i.readFully(ByteArray(readVar(i)))
            i.readUnsignedShort()
            val next = readVar(i)
            if (next == 1) {
                readVar(i); readVar(i)
                val json = "{\"version\":{\"name\":\"PocketMC (uyuyor)\",\"protocol\":$proto},\"players\":{\"max\":10,\"online\":0}," +
                    "\"description\":{\"text\":\"Sunucu uyuyor. Girmeyi denersen uyanir (acilis ~${Srv.prefs(c).getInt("boot_s", 90)} sn).\"}}"
                o.write(packet(0, str(json))); o.flush()
                readVar(i); readVar(i)
                val payload = ByteArray(8); i.readFully(payload)
                o.write(packet(1, payload)); o.flush()
            } else if (next == 2 || next == 3) {
                readVar(i); readVar(i)
                val nb = ByteArray(readVar(i)); i.readFully(nb)
                val name = String(nb, Charsets.UTF_8)
                val dir = Srv.serverDir(c)
                val wlOn = Srv.readProps(File(dir, "server.properties"))["white-list"]?.trim() == "true"
                val ok = !wlOn || Lists.names(File(dir, "whitelist.json")).any { it.equals(name, true) }
                val msg: String
                if (ok) {
                    Srv.add("[APK] $name bağlanmayı denedi, sunucu uyanıyor")
                    msg = "Sunucu uyaniyor. Tahmini acilis ~${Srv.prefs(c).getInt("boot_s", 90)} sn, sonra tekrar baglan."
                    Srv.wake = true
                } else {
                    if (Regex("[A-Za-z0-9_]{3,16}").matches(name)) Srv.addRequest(name)
                    msg = "Whitelist'te değilsin. Yöneticiden seni eklemesini iste."
                }
                o.write(packet(0, str("{\"text\":\"${esc(msg)}\"}"))); o.flush()
            }
        } catch (_: Throwable) {
        } finally { try { s.close() } catch (_: Throwable) {} }
    }
}

// ---------------- Arayüz ----------------

@Composable fun SleepCard() {
    val ctx = LocalContext.current
    val pf = remember { Srv.prefs(ctx) }
    var on by remember { mutableStateOf(pf.getBoolean("sleep_on", false)) }
    var mins by remember { mutableIntStateOf(pf.getInt("sleep_min", 5)) }
    val sleeping by Srv.sleeping.collectAsState()
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Uyku modu", style = MaterialTheme.typography.titleMedium)
                    Text("Kimse yoksa sunucu kapanır, biri girmeyi deneyince uyanır. Tünel kodu değişmez.", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(on, { on = it; pf.edit().putBoolean("sleep_on", it).apply() })
            }
            if (on) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15, 30).forEach { m ->
                    FilterChip(mins == m, { mins = m; pf.edit().putInt("sleep_min", m).apply() }, label = { Text("$m dk") })
                }
            }
            if (sleeping) Text("Şu an uykuda. BAŞLAT ile elle de uyandırabilirsiniz.", fontSize = 12.sp, color = Green)
        }
    }
}

@Composable fun RequestsCard(onMsg: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val reqs by Srv.requests.collectAsState()
    if (reqs.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Giriş istekleri", style = MaterialTheme.typography.titleMedium)
            Text("Whitelist'te olmadığı için giremeyenler:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            reqs.forEach { n ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(n, Modifier.weight(1f))
                    TextButton({ scope.launch(Dispatchers.IO) { onMsg(WL.add(ctx, n)); Srv.removeRequest(n) } }) { Text("Ekle") }
                    TextButton({ Srv.removeRequest(n) }) { Text("✕", color = Red) }
                }
            }
        }
    }
}
