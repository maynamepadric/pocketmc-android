package com.pocketmc.app

import android.content.Intent
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun FilesCard() {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Minecraft 26.3 (Fabric)", style = MaterialTheme.typography.titleMedium)
            OutlinedButton({
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(DocumentsContract.buildRootUri(FilesProvider.AUTH, FilesProvider.ROOT),
                        "vnd.android.document/root").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) { Srv.toast(ctx, "Dosyalar uygulamasını açıp menüden PocketMC'yi seçin") }
            }, Modifier.fillMaxWidth()) { Text("Dosya yöneticisi") }
            Text("Dünya, mod ve ayar klasörlerine dosya yöneticisinden erişebilirsiniz.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
