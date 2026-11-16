package com.pocketmc.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PocketApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Setup.recover(this)
        Setup.MC = Srv.prefs(this).getString("mc", "26.3") ?: "26.3"
        Ui.load(this)
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                File(filesDir, "crash.txt").writeText("Thread: ${t.name}\nSaat: ${Date()}\n$sw")
            } catch (_: Throwable) {}
            old?.uncaughtException(t, e)
        }
    }
}

fun shareText(c: Context, text: String) {
    try {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text.takeLast(100000))
        c.startActivity(Intent.createChooser(i, "Paylaş").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Throwable) {}
}

object Diag {
    // 2=SIGNALED 3=LOW_MEMORY 4=CRASH 5=CRASH_NATIVE 6=ANR 9=EXCESSIVE_RESOURCE_USAGE 12=DEPENDENCY_DIED
    private val bad = setOf(2, 3, 4, 5, 6, 9, 12)

    private fun name(r: Int): String = when (r) {
        0 -> "UNKNOWN"
        1 -> "EXIT_SELF"
        2 -> "SIGNALED (sistem öldürdü)"
        3 -> "LOW_MEMORY (bellek yetmedi)"
        4 -> "CRASH (uygulama çöktü)"
        5 -> "CRASH_NATIVE"
        6 -> "ANR (donma)"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER"
        else -> "KOD $r"
    }

    class Ex(val ts: Long, val reason: Int, val text: String)

    fun exits(c: Context): List<Ex> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        return try {
            val am = c.getSystemService(ActivityManager::class.java)
            val f = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)
            am.getHistoricalProcessExitReasons(c.packageName, 0, 5).map {
                Ex(it.timestamp, it.reason,
                    "${f.format(Date(it.timestamp))}  ${name(it.reason)}  status=${it.status}  ${it.description ?: ""}")
            }
        } catch (e: Throwable) { emptyList() }
    }

    fun report(c: Context): String {
        val sb = StringBuilder()
        sb.append("Cihaz: ${Build.MODEL}  Android ${Build.VERSION.SDK_INT}\n")
        try {
            val mi = ActivityManager.MemoryInfo()
            c.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
            sb.append("Boş RAM: ${mi.availMem / 1048576} MB / ${mi.totalMem / 1048576} MB\n")
        } catch (_: Throwable) {}
        val cf = File(c.filesDir, "crash.txt")
        sb.append("\n== Uygulama çökmesi ==\n")
        sb.append(if (cf.exists()) cf.readText().take(5000) else "(kayıt yok)").append("\n")
        sb.append("\n== Son kapanış nedenleri (Android) ==\n")
        val ex = exits(c)
        sb.append(if (ex.isEmpty()) "(alınamadı)" else ex.joinToString("\n") { it.text })
        sb.append("\n\n== Sunucu günlüğünün sonu ==\n")
        val lf = File(Srv.serverDir(c), "logs/latest.log")
        val tail = try {
            if (lf.exists()) lf.readLines().takeLast(30) else Srv.fullLog().lines().takeLast(30)
        } catch (e: Throwable) { listOf("(okunamadı: $e)") }
        sb.append(tail.joinToString("\n") { it.take(300) })
        return sb.toString()
    }

    fun needsReport(c: Context): Boolean {
        if (File(c.filesDir, "crash.txt").exists()) return true
        val last = exits(c).firstOrNull() ?: return false
        return last.reason in bad && last.ts > Srv.prefs(c).getLong("seen_ts", 0L)
    }

    fun dismiss(c: Context) {
        File(c.filesDir, "crash.txt").delete()
        exits(c).firstOrNull()?.let { Srv.prefs(c).edit().putLong("seen_ts", it.ts).apply() }
    }
}
