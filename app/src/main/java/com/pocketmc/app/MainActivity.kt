@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
package com.pocketmc.app

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread


class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        setContent {
            PocketTheme { App() }
        }
    }
}

fun startServer(c: Context) = thread {
    Srv.resetProgress("Hazırlanıyor…")
    try { Srv.ensureJre(c) } catch (e: Throwable) { Srv.add("[APK] JRE HATA: $e"); Srv.status.value = "Kapalı"; return@thread }
    if (!File(Srv.serverDir(c), "fabric-server.jar").exists()) {
        Srv.add("[APK] Sunucu yok. Konsol sekmesinden 'Sunucuyu içe aktar' (mcserver.zip)."); return@thread
    }
    c.startForegroundService(Intent(c, ServerService::class.java))
}
fun stopServer(c: Context) { c.startService(Intent(c, ServerService::class.java).setAction("STOP")) }

val Rainbow = listOf(
    Color(0xFFFF5252), Color(0xFFFF9800), Color(0xFFFFEB3B), Color(0xFF66BB6A),
    Color(0xFF29B6F6), Color(0xFF7E57C2), Color(0xFFEC407A), Color(0xFFFF5252))

@Composable fun RainbowBar(p: Float, label: String, onClick: (() -> Unit)? = null) {
    val anim by animateFloatAsState(p.coerceIn(0f, 1f), tween(400), label = "bar")
    val shift = rememberInfiniteTransition(label = "rb").animateFloat(0f, 700f,
        infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "sh")
    Column(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick() } else Modifier).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row {
            Text(label, Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text("${(anim * 100).toInt()}%", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF222B26))) {
            Box(Modifier.fillMaxWidth(anim).fillMaxHeight().drawBehind {
                drawRect(Brush.horizontalGradient(Rainbow, startX = shift.value, endX = shift.value + 700f, tileMode = TileMode.Repeated))
            })
        }
    }
}

fun shortLine(s: String): String {
    val t = s.replace(Regex("^\\[\\d\\d:\\d\\d:\\d\\d\\] \\[[^\\]]*\\]: "), "")
    return if (t.length > 160) t.take(160) + "…" else t
}

@Composable fun SetupScreen() {
    val ctx = LocalContext.current
    val busy by Setup.busy.collectAsState()
    val prog by Setup.progress.collectAsState()
    val step by Setup.step.collectAsState()
    val err by Setup.error.collectAsState()
    val clip = LocalClipboardManager.current
    var name by remember { mutableStateOf("") }
    var mods by remember { mutableStateOf(true) }
    val valid = Regex("[A-Za-z0-9_]{3,16}").matches(name.trim())
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) thread {
            try { ctx.contentResolver.openInputStream(u)!!.use { Srv.importServer(ctx, it) } }
            catch (e: Throwable) { Srv.add("[APK] HATA: $e") }
        }
    }
    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(Modifier.weight(1.3f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("KURULUM / HOŞ GELDİN")
                Text("Bir dünya.", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Text("Senin kuralların.", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Green)
                Text("Termux gerekmez. Minecraft ${Setup.MC} Fabric sunucusu bu uygulamanın içine kurulur.", fontSize = 12.sp, color = Muted)
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                    label = { Text("Oyuncu adınız (whitelist'e eklenir)") },
                    isError = name.isNotEmpty() && !valid,
                    supportingText = { Text(if (name.isNotEmpty() && !valid) "3-16 karakter; harf, rakam, _ (boşluk yok)" else "Oyundaki adınızla birebir aynı olmalı") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Performans modları")
                        Text("lithium, ferrite-core, krypton, scalablelux", fontSize = 11.sp, color = Muted)
                    }
                    Switch(mods, { mods = it }, enabled = !busy)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ Setup.run(ctx, name.trim(), mods) }, enabled = valid && !busy) {
                        Text(if (busy) "Kuruluyor…" else "Kurulumu başlat →", fontWeight = FontWeight.ExtraBold)
                    }
                    OutlinedButton({ pick.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Mevcut sunucuyu içe aktar") }
                }
                if (busy || prog > 0f) RainbowBar(prog, step)
                if (err != null) Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3A1F1D))) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Kurulum başarısız", color = Red, fontWeight = FontWeight.Bold)
                        Text(err!!, fontSize = 12.sp)
                        Text("İnternet bağlantısını kontrol edip tekrar deneyin.", fontSize = 12.sp, color = Muted)
                        Row {
                            TextButton({ clip.setText(AnnotatedString(err + "\n" + Srv.fullLog())); Srv.toast(ctx, "Kopyalandı") }) { Text("Hatayı kopyala") }
                            TextButton({ shareText(ctx, err + "\n" + Srv.fullLog()) }) { Text("Paylaş") }
                        }
                    }
                }
            }
        }
        Card(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Kurulum adımları", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                listOf("Java ortamı" to "Çalışma ortamını hazırla", "Fabric sunucusu" to "Minecraft ${Setup.MC} indirilir", "Dünya ve modlar" to "Performans modları kurulur").forEachIndexed { i, (t, d) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Ui.accent.deep), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", color = Green, fontWeight = FontWeight.ExtraBold)
                        }
                        Column { Text(t, fontWeight = FontWeight.Bold, fontSize = 12.sp); Text(d, fontSize = 11.sp, color = Muted) }
                    }
                }
            }
        }
    }
}


@Composable fun CrashCard() {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var show by remember { mutableStateOf(Diag.needsReport(ctx)) }
    if (!show) return
    Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2F1A))) {
        Column(Modifier.padding(12.dp)) {
            Text("Uygulama geçen sefer beklenmedik kapandı", color = Amber, fontWeight = FontWeight.Bold)
            Text("Nedenini bulmak için raporu kopyalayabilir ya da paylaşabilirsiniz.", fontSize = 12.sp)
            Row {
                TextButton({ clip.setText(AnnotatedString(Diag.report(ctx))); Srv.toast(ctx, "Rapor kopyalandı") }) { Text("Raporu kopyala") }
                TextButton({ shareText(ctx, Diag.report(ctx)) }) { Text("Paylaş") }
                TextButton({ Diag.dismiss(ctx); show = false }) { Text("Kapat") }
            }
        }
    }
}

@Composable fun App() {
    val show by Srv.showLog.collectAsState()
    val cfg by Srv.showCfg.collectAsState()
    Box(Modifier.fillMaxSize()) {
        AppInner()
        BannerHost(Modifier.align(Alignment.BottomCenter).padding(start = 110.dp, end = 16.dp, top = 16.dp, bottom = 16.dp))
    }
    if (show) LogDialog()
    if (cfg) ConfigDialog()
}

fun lineColor(s: String) = when {
    s.startsWith("[APK]") -> Green
    s.contains("ERROR") || s.contains("Exception") || s.contains("FATAL") -> Red
    s.contains("WARN") -> Amber
    else -> Color(0xFFC9D3CC)
}

@Composable fun PField(label: String, key: String, m: MutableMap<String, String>, num: Boolean = true) {
    OutlinedTextField(m[key] ?: "", { m[key] = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = if (num) androidx.compose.ui.text.input.KeyboardType.Number else androidx.compose.ui.text.input.KeyboardType.Text))
}

fun localIp(): String? = try {
    val all = NetworkInterface.getNetworkInterfaces().toList()
    (all.filter { it.name.startsWith("wlan") } + all).flatMap { it.inetAddresses.toList() }
        .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }?.hostAddress
} catch (e: Exception) { null }

@Composable fun ConnTab() {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val ip = remember { localIp() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("Yerel adres (aynı Wi-Fi)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (ip != null) "$ip:${Srv.port(ctx)}" else "IP bulunamadı (Wi-Fi bağlı mı?)", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (ip != null) TextButton({ clip.setText(AnnotatedString("$ip:${Srv.port(ctx)}")); Srv.toast(ctx, "Kopyalandı") }) { Text("Kopyala") }
            }
        }
        TunnelCard()
        PlayerLinkCard()
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("Arka plan koruması", style = MaterialTheme.typography.titleMedium)
                Text("1) Pil kısıtlamasını kaldırın.", Modifier.padding(top = 6.dp))
                Button({ ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) }) { Text("Pil muafiyeti iste") }
                Text("2) HyperOS: Uygulama bilgisi > Otomatik başlatma AÇIK, Pil tasarrufu > 'Kısıtlama yok'. Son uygulamalarda uygulamayı kilitleyin.", Modifier.padding(top = 8.dp))
                OutlinedButton({ ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }) { Text("Uygulama ayarlarını aç") }
                Text("3) Android 12+: Geliştirici seçenekleri > 'Phantom process kısıtlamasını devre dışı bırak' (sunucu sebepsiz kapanırsa).", Modifier.padding(top = 8.dp))
            }
        }
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("Uyarılar", style = MaterialTheme.typography.titleMedium)
                Text("• Sunucu ve oyun aynı telefondaysa ısınma olur; şarjda ve havadar yerde tutun.", Modifier.padding(top = 6.dp))
                Text("• online-mode kapalı: whitelist açık kalsın.")
                Text("• Özel skin çevrimdışı modda görünmez.")
                Text("• Uzak bağlantı için yukarıdaki İnternet adresi kartını kullanın.", color = Amber)
            }
        }
    }
}
