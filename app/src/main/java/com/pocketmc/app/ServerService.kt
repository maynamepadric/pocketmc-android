package com.pocketmc.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import java.io.File

class ServerService : Service() {
    private var wl: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    @Volatile private var worker: Thread? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val p = Srv.prefs(this)
        when (i?.action) {
            "STOP" -> {
                Srv.stopRequested = true; p.edit().putBoolean("want", false).apply()
                if (worker?.isAlive == true) { Srv.status.value = "Kapanıyor…"; Srv.send("stop"); notifyNow() }
                else { stopSelf() }
                return START_NOT_STICKY
            }
            "REPOST" -> { startForeground(1, notif()); return START_STICKY }
        }
        if (i == null && !p.getBoolean("want", false)) { stopSelf(); return START_NOT_STICKY }
        startForeground(1, notif())
        p.edit().putBoolean("want", true).apply()
        acquireLocks()
        if (worker?.isAlive != true) {
            Srv.stopRequested = false; Srv.sleepRequested = false
            worker = Thread { loop() }.also { it.start() }
        } else if (Srv.sleeping.value) Srv.wake = true
        return START_STICKY
    }

    private fun notifyNow() { getSystemService(NotificationManager::class.java).notify(1, notif()) }

    private fun notif(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("srv", "Sunucu", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, ServerService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val re = PendingIntent.getService(this, 1, Intent(this, ServerService::class.java).setAction("REPOST"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val line = Srv.info.ifEmpty { Srv.status.value }
        return Notification.Builder(this, "srv").setContentTitle("PocketMC").setContentText(line)
            .setStyle(Notification.BigTextStyle().bigText(line))
            .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).setContentIntent(open).setDeleteIntent(re)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_delete, "Durdur", stop).build()).build()
    }

    private fun acquireLocks() {
        if (wl == null) wl = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pocketmc:srv").also { it.acquire() }
        if (wifi == null) wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pocketmc:wifi").also { it.acquire() }
    }

    private fun ping(): Int {
        val a = Tunnel.addr.value ?: return -1
        val port = a.substringAfterLast(':').toIntOrNull() ?: return -1
        return try {
            val t = System.nanoTime()
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(a.substringBeforeLast(':'), port), 3000) }
            ((System.nanoTime() - t) / 1_000_000).toInt()
        } catch (_: Exception) { -1 }
    }

    private fun startInfo(): Thread = Thread {
        var pingMs = -1; var n = 0
        try {
            while (true) {
                val st = Srv.status.value
                val s = Stats.sample(this, st == "Çalışıyor")
                if (n++ % 4 == 0) pingMs = ping()
                try { Relay.tick(this, st, s) } catch (_: Throwable) {}
                val parts = mutableListOf(st)
                if (s.max > 0) parts.add("${s.on}/${s.max} oyuncu")
                if (s.rssMb > 0) parts.add("RAM ${s.rssMb} MB")
                if (s.upSec > 0) parts.add("açık " + fmtUp(s.upSec))
                if (s.sleepSec > 0) parts.add("uyku " + fmtUp(s.sleepSec))
                if (s.tempC > 0) parts.add("${"%.0f".format(s.tempC)}°C")
                if (pingMs >= 0) parts.add("ping $pingMs ms")
                Srv.info = parts.joinToString(" • ")
                notifyNow()
                Thread.sleep(15000)
            }
        } catch (_: InterruptedException) {}
    }.also { it.isDaemon = true; it.start() }

    private fun loop() {
        var fails = 0
        val infoT = startInfo()
        Relay.start(this)
        try {
            while (!Srv.stopRequested) {
                val t0 = System.currentTimeMillis()
                while (Setup.busy.value && !Srv.stopRequested) { Srv.status.value = "Sürüm/kurulum işlemi bitsin…"; Thread.sleep(2000) }
                if (Srv.stopRequested) break
                try { SyncW.auto(this, Srv.activeWorld(this), "start") } catch (e: Throwable) { Srv.add("[APK] Eşitleme hatası: $e") }
                val code = try { runOnce() } catch (e: Throwable) { Srv.add("[APK] HATA: $e"); -1 }
                Srv.add("[APK] Sunucu kapandı, çıkış kodu: $code")
                try { SyncW.auto(this, Srv.activeWorld(this), "stop") } catch (e: Throwable) { Srv.add("[APK] Eşitleme hatası: $e") }
                if (Srv.sleepRequested && !Srv.stopRequested) { Srv.sleepRequested = false; sleepWait(); if (Srv.stopRequested) break; continue }
                if (Srv.stopRequested || code == 0) break
                fails = if (System.currentTimeMillis() - t0 < 60000) fails + 1 else 0
                if (fails >= 3) { Srv.add("[APK] Çok sık çöküyor, otomatik yeniden başlatma durdu. Günlüğü Konsol sekmesinden kopyalayıp paylaşabilirsiniz."); break }
                Srv.status.value = "Çöktü, yeniden başlıyor…"; notifyNow()
                Srv.add("[APK] 5 sn sonra yeniden başlatılıyor…")
                Thread.sleep(5000)
            }
        } finally {
            infoT.interrupt(); Relay.stop(); Srv.info = ""
            Srv.status.value = "Kapalı"; Srv.running.value = false
            Srv.prefs(this).edit().putBoolean("want", false).apply()
            if (wl?.isHeld == true) wl?.release(); wl = null
            if (wifi?.isHeld == true) wifi?.release(); wifi = null
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }

    // Uyku: sunucu kapalıyken aynı portu APK dinler. Sunucu listesinde "uyuyor" görünür, giriş denemesi sunucuyu uyandırır.
    private fun sleepWait() {
        Srv.status.value = "Uykuda 💤 (biri girince uyanır)"; Srv.running.value = false; Srv.sleeping.value = true; notifyNow()
        Srv.wake = false; Clock.sleepT0 = System.currentTimeMillis()
        val ss = try {
            java.net.ServerSocket().apply { reuseAddress = true; bind(java.net.InetSocketAddress(Srv.port(this@ServerService))); soTimeout = 1000 }
        } catch (e: Throwable) { Srv.add("[APK] Uyku dinleyicisi açılamadı, sunucu yeniden başlıyor: $e"); Srv.sleeping.value = false; return }
        try {
            while (!Srv.stopRequested && !Srv.wake) {
                val s = try { ss.accept() } catch (_: java.net.SocketTimeoutException) { continue }
                Thread { Sleeper.handle(this, s) }.start()
            }
        } finally { try { ss.close() } catch (_: Throwable) {}; Srv.sleeping.value = false; Clock.sleepT0 = 0L }
    }

    private fun runOnce(): Int {
        val jre = File(filesDir, "jre"); val srv = Srv.serverDir(this)
        if (!File(srv, "fabric-server.jar").exists()) { Srv.add("[APK] fabric-server.jar yok. Konsol > Sunucuyu içe aktar"); return 0 }
        File(srv, "eula.txt").writeText("eula=true\n")
        Srv.writeProps(File(srv, "server.properties"), mapOf(
            "enable-rcon" to "true", "rcon.port" to "25575", "rcon.password" to Srv.rconPass(this),
            "broadcast-rcon-to-ops" to "false") + Srv.worldProps(this, Srv.activeWorld(this)) +
            mapOf("level-name" to Srv.activeWorld(this)))
        val ram = Srv.prefs(this).getInt("ram", 2048)
        val cmd = listOf("$jre/bin/java", "-Djava.io.tmpdir=$cacheDir", "-Xms512M", "-Xmx${ram}M",
            "-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200",
            "-XX:+UnlockExperimentalVMOptions", "-XX:+DisableExplicitGC", "-XX:G1NewSizePercent=30",
            "-XX:G1MaxNewSizePercent=40", "-XX:G1HeapRegionSize=8M", "-XX:G1ReservePercent=20",
            "-XX:G1HeapWastePercent=5", "-XX:G1MixedGCCountTarget=4", "-XX:InitiatingHeapOccupancyPercent=15",
            "-XX:G1MixedGCLiveThresholdPercent=90", "-XX:SurvivorRatio=32", "-XX:+PerfDisableSharedMem",
            "-XX:MaxTenuringThreshold=1", "-jar", "fabric-server.jar", "nogui")
        val pb = ProcessBuilder(cmd).directory(srv).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = "$jre/extra:$jre/lib:$jre/lib/server"
        pb.environment()["HOME"] = srv.path
        Srv.resetProgress("Sunucu başlatılıyor…")
        Srv.status.value = "Başlıyor…"; Srv.add("[APK] Sunucu başlatılıyor (-Xmx${ram}M)…"); notifyNow()
        val p = pb.start(); Srv.proc = p; Srv.running.value = true; Clock.t0 = System.currentTimeMillis()
        val idle = Thread {
            try {
                var since = 0L
                while (true) {
                    Thread.sleep(30000)
                    val pf = Srv.prefs(this)
                    if (!pf.getBoolean("sleep_on", false) || Srv.status.value != "Çalışıyor") { since = 0; continue }
                    val r = Srv.rcon(this, "list") ?: continue
                    val n = Regex("(\\d+) of a max").find(r)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                    if (n > 0) { since = 0; continue }
                    if (since == 0L) since = System.currentTimeMillis()
                    if (System.currentTimeMillis() - since >= pf.getInt("sleep_min", 5) * 60000L) {
                        Srv.sleepRequested = true; Srv.add("[APK] Kimse yok, sunucu uykuya geçiyor"); Srv.send("stop"); break
                    }
                }
            } catch (_: InterruptedException) {}
        }.also { it.isDaemon = true; it.start() }
        p.inputStream.bufferedReader().forEachLine { l ->
            Srv.add(l)
            Srv.stage(l)
            if (l.contains("Done (")) {
                Srv.status.value = "Çalışıyor"; notifyNow()
                Srv.prefs(this).edit().putInt("boot_s", ((System.currentTimeMillis() - Clock.t0) / 1000).toInt()).apply()
            }
        }
        val code = p.waitFor()
        idle.interrupt(); Srv.proc = null; Srv.running.value = false
        return code
    }
}
