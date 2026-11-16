package com.pocketmc.player

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

class CopyReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val a = i.getStringExtra("addr") ?: return
        c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adres", a))
        toast(c, "Adres kopyalandı")
    }
}
