package com.solis.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.d("BootReceiver", "Telefon açıldı, Solis başlatılıyor...")
            val prefs = context.getSharedPreferences("solis_prefs", Context.MODE_PRIVATE)
            val isEnabled = prefs.getBoolean("listener_enabled", true)
            if (isEnabled) {
                ListenerService.start(context)
            }
        }
    }
}
