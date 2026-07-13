package com.solis.assistant.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.solis.assistant.api.GeminiClient
import com.solis.assistant.api.GeminiResult
import com.solis.assistant.data.AppDatabase
import com.solis.assistant.ui.DailySummaryActivity
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class SummaryService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            generateAndNotifySummary()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun generateAndNotifySummary() {
        val db = AppDatabase.getInstance(this)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val records = db.audioRecordDao().getByDateSync(today)

        if (records.isEmpty()) {
            sendNotification("Bugün kayıt bulunamadı.", "Solis - Gün Sonu Özeti")
            return
        }

        // Tüm kayıtları birleştir
        val allText = records.joinToString("\n---\n") { record ->
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(record.timestamp))
            "[$time] ${record.transcript}"
        }

        val result = GeminiClient.createDailySummary(allText)

        when (result) {
            is GeminiResult.Success -> {
                val summary = parseSummary(result.text)
                sendDetailedNotification(summary, result.text)
            }
            is GeminiResult.Error -> {
                sendNotification("Özet oluşturulamadı: ${result.message}", "Solis - Gün Sonu Özeti")
            }
        }
    }

    private fun parseSummary(json: String): String {
        return try {
            val gson = com.google.gson.Gson()
            val map = gson.fromJson(json, Map::class.java)
            map["ozet"]?.toString() ?: json.take(200)
        } catch (e: Exception) {
            json.take(200)
        }
    }

    private fun sendDetailedNotification(summary: String, fullJson: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Özet aktivitesine yönlendir
        val intent = Intent(this, DailySummaryActivity::class.java).apply {
            putExtra("summary_json", fullJson)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel("solis_summary", "Gün Sonu Özeti", NotificationManager.IMPORTANCE_HIGH)
            manager.createNotificationChannel(channel)
        }

        val notif = NotificationCompat.Builder(this, "solis_summary")
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("🌙 Solis - Gün Sonu Özeti")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        manager.notify(9999, notif)
    }

    private fun sendNotification(text: String, title: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel("solis_summary", "Gün Sonu Özeti", NotificationManager.IMPORTANCE_HIGH)
            manager.createNotificationChannel(channel)
        }

        val notif = NotificationCompat.Builder(this, "solis_summary")
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()

        manager.notify(9999, notif)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
