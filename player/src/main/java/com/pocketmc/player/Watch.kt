package com.pocketmc.player

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class Watch : Service() {
    @Volatile private var run = false
    @Volatile private var conn: HttpURLConnection? = null
    private var t: Thread? = null
    private var n = 0

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == "RESET") { try { conn?.disconnect() } catch (_: Exception) {}; if (t?.isAlive == true) return START_STICKY }
        if (i?.action == "STOP" || Link.code(this) == null) {
            run = false; try { conn?.disconnect() } catch (_: Exception) {}
            stopForeground(true); stopSelf(); return START_NOT_STICKY
        }
        startForeground(1, ongoing(St.link.value))
        if (t?.isAlive != true) { run = true; t = Thread { loop() }.also { it.start() } }
        return START_STICKY
    }

    private fun ongoing(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("w", "Bağlantı", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "w").setContentTitle("PocketMC For Players").setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_share).setOngoing(true).setContentIntent(open).build()
    }

    private fun note(id: Int, title: String, text: String, copy: String? = null) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("n", "Sunucu olayları", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, "n").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true)
        if (copy != null) b.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_edit, "Kopyala",
            PendingIntent.getBroadcast(this, 1, Intent(this, CopyReceiver::class.java).putExtra("addr", copy),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
        nm.notify(id, b.build())
    }

    private fun summary(j: JSONObject): String {
        val st = j.optString("st")
        val parts = mutableListOf(if (j.optBoolean("sleep")) "Uyuyor" else st)
        if (j.optInt("max") > 0) parts.add("${j.optInt("on")}/${j.optInt("max")} oyuncu")
        val addr = j.optString("addr")
        if (addr.isNotEmpty()) parts.add(addr)
        return parts.joinToString(" • ")
    }

    private fun handle(time: Long, j: JSONObject) {
        val pairedAt = Link.prefs(this).getLong("paired_at", 0L)
        val fresh = time > pairedAt
        when (j.optString("t")) {
            "status" -> {
                val prev = St.status.value
                St.status.value = j
                Link.prefs(this).edit().putString("last", j.toString()).apply()
                getSystemService(NotificationManager::class.java).notify(1, ongoing(summary(j)))
                if (fresh) {
                    val a = j.optString("addr")
                    if (a.isNotEmpty() && (a != prev?.optString("addr") || j.optLong("sid") != prev?.optLong("sid"))) {
                        note(10, "Yeni oturum kodunuz", a, a)
                        St.anns.value = (listOf(Ann(time, "Yeni oturum kodu: $a")) + St.anns.value).take(30)
                        Link.saveAnns(this)
                    }
                    val st = j.optString("st")
                    if (st == "Çalışıyor" && prev?.optString("st") != "Çalışıyor") note(11, "Sunucu açıldı", "Girebilirsiniz.")
                }
            }
            "chat" -> {
                val nm = j.optString("name"); val tx = j.optString("text")
                if (tx.isNotEmpty()) {
                    St.chat.value = (St.chat.value + Chat(j.optLong("ts", time), nm, tx)).takeLast(60)
                    Link.saveChat(this)
                    if (fresh && nm != Link.prefs(this).getString("nick", "")) note(200 + (n++ % 50), nm, tx)
                }
            }
            "msg" -> {
                val text = j.optString("text")
                if (text.isNotEmpty()) {
                    St.anns.value = (listOf(Ann(j.optLong("ts", time), text)) + St.anns.value).take(30)
                    Link.saveAnns(this)
                    if (fresh) note(100 + (n++ % 50), "Sunucu duyurusu", text)
                }
            }
        }
    }

    private fun loop() {
        var since: String
        while (run) {
            since = Link.prefs(this).getString("since", null) ?: "12h"
            val code = Link.code(this) ?: break
            try {
                val cn = URL("${Link.base(this)}/${Link.topic(this, "s")}/json?since=$since").openConnection() as HttpURLConnection
                conn = cn; cn.connectTimeout = 10000; cn.readTimeout = 120000
                St.link.value = "Bağlı"
                cn.inputStream.bufferedReader().forEachLine { l ->
                    try {
                        val o = JSONObject(l)
                        if (o.optString("event") != "message") return@forEachLine
                        since = o.getString("id")
                        Link.prefs(this).edit().putString("since", since).apply()
                        handle(o.optLong("time") * 1000, JSONObject(o.getString("message")))
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
            if (!run) break
            St.link.value = "Bağlantı yok, yeniden deneniyor…"
            try { Thread.sleep(8000) } catch (_: InterruptedException) {}
        }
        if (code0() == null) stopSelf()
    }

    private fun code0() = Link.code(this)
}
