package com.pocketmc.player

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class Ann(val ts: Long, val text: String)
class Chat(val ts: Long, val name: String, val text: String)

object St {
    val status = MutableStateFlow<JSONObject?>(null)
    val anns = MutableStateFlow<List<Ann>>(emptyList())
    val link = MutableStateFlow("Bağlanıyor…")
    val chat = MutableStateFlow<List<Chat>>(emptyList())
}

object Link {
    const val DEFAULT = "https://ntfy.sh"
    fun prefs(c: Context) = c.getSharedPreferences("pl", 0)
    fun code(c: Context): String? = prefs(c).getString("code", null)
    fun base(c: Context): String = prefs(c).getString("base", DEFAULT) ?: DEFAULT
    fun topic(c: Context, s: String) = "pmc-${code(c)}-$s"

    // "K7QX-M2TD-9PRW" ya da "PMC1|kod|adres"
    fun parse(s: String): Pair<String, String>? {
        val t = s.trim()
        var base = DEFAULT
        var raw = t
        if (t.startsWith("PMC1|")) {
            val p = t.split("|"); if (p.size < 3) return null
            raw = p[1]; base = p[2].trim().trimEnd('/')
        }
        val k = raw.uppercase().filter { it.isLetterOrDigit() }
        if (k.length != 12) return null
        return k.chunked(4).joinToString("-") to base
    }

    fun save(c: Context, code: String, base: String) {
        prefs(c).edit().clear().putString("code", code).putString("base", base)
            .putLong("paired_at", System.currentTimeMillis()).apply()
        St.status.value = null; St.anns.value = emptyList(); St.chat.value = emptyList()
    }

    fun unpair(c: Context) { prefs(c).edit().clear().apply(); St.status.value = null; St.anns.value = emptyList(); St.chat.value = emptyList() }

    fun load(c: Context) {
        val p = prefs(c)
        p.getString("last", null)?.let { try { St.status.value = JSONObject(it) } catch (_: Exception) {} }
        p.getString("anns", null)?.let {
            try {
                val a = JSONArray(it)
                St.anns.value = (0 until a.length()).map { i -> val o = a.getJSONObject(i); Ann(o.getLong("ts"), o.getString("text")) }
            } catch (_: Exception) {}
        }
        p.getString("chat", null)?.let {
            try {
                val a = JSONArray(it)
                St.chat.value = (0 until a.length()).map { i -> val o = a.getJSONObject(i); Chat(o.getLong("ts"), o.getString("name"), o.getString("text")) }
            } catch (_: Exception) {}
        }
    }

    fun saveChat(c: Context) {
        val a = JSONArray()
        St.chat.value.forEach { a.put(JSONObject().put("ts", it.ts).put("name", it.name).put("text", it.text)) }
        prefs(c).edit().putString("chat", a.toString()).apply()
    }

    fun saveAnns(c: Context) {
        val a = JSONArray()
        St.anns.value.forEach { a.put(JSONObject().put("ts", it.ts).put("text", it.text)) }
        prefs(c).edit().putString("anns", a.toString()).apply()
    }

    fun post(c: Context, j: JSONObject): Boolean = try {
        val cn = URL("${base(c)}/${topic(c, "p")}").openConnection() as HttpURLConnection
        cn.requestMethod = "POST"; cn.doOutput = true; cn.connectTimeout = 10000; cn.readTimeout = 15000
        cn.outputStream.use { it.write(j.toString().toByteArray()) }
        val ok = cn.responseCode in 200..299
        cn.disconnect(); ok
    } catch (_: Exception) { false }
}
