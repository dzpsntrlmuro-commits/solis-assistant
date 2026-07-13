package com.solis.assistant.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.solis.assistant.BuildConfig
import com.solis.assistant.api.GeminiClient
import com.solis.assistant.api.GeminiResult
import com.solis.assistant.data.AppDatabase
import com.solis.assistant.data.AudioRecord
import com.solis.assistant.ui.MainActivity
import com.solis.assistant.utils.AudioRecorder
import com.solis.assistant.utils.SpeechToText
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ListenerService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val recorder = AudioRecorder()
    private var wakeLock: PowerManager.WakeLock? = null
    private var isListening = false

    // Her 2 dakikada bir kayıt al
    private val RECORD_INTERVAL_MS = 2 * 60 * 1000L
    // Her kayıt 45 saniye
    private val RECORD_DURATION_MS = 45 * 1000L

    companion object {
        const val CHANNEL_ID = "solis_listener"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.solis.assistant.STOP"
        const val ACTION_START = "com.solis.assistant.START"

        fun start(context: Context) {
            val intent = Intent(context, ListenerService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ListenerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Dinleniyor..."))
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (!isListening) {
                    isListening = true
                    startListeningLoop()
                }
            }
        }
        return START_STICKY
    }

    private fun startListeningLoop() {
        scope.launch {
            while (isListening) {
                try {
                    updateNotification("🎙️ Ses kaydediliyor...")
                    val audioFile = recordAudio()

                    if (audioFile != null) {
                        updateNotification("🔍 Analiz ediliyor...")
                        processAudio(audioFile)
                        audioFile.delete()
                    }

                    updateNotification("⏳ Dinleniyor... (${RECORD_INTERVAL_MS / 60000} dk bekleme)")
                    delay(RECORD_INTERVAL_MS)
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e("ListenerService", "Döngü hatası: ${e.message}")
                    delay(30_000) // Hata varsa 30 sn bekle
                }
            }
        }
    }

    private fun recordAudio(): File? {
        return try {
            val file = File(cacheDir, "audio_${System.currentTimeMillis()}.wav")
            val success = recorder.recordToFile(file, RECORD_DURATION_MS)
            if (success) file else null
        } catch (e: Exception) {
            Log.e("ListenerService", "Kayıt hatası: ${e.message}")
            null
        }
    }

    private suspend fun processAudio(audioFile: File) {
        // 1. Ses → Metin
        val transcript = withContext(Dispatchers.IO) {
            SpeechToText.transcribeAudio(audioFile)
        }

        Log.d("ListenerService", "Transcript: $transcript")

        // Anlamlı içerik yoksa atla
        if (transcript.startsWith("[") || transcript.length < 10) {
            Log.d("ListenerService", "Boş/hatalı transcript, atlanıyor")
            return
        }

        // 2. Gemini ile analiz
        val result = GeminiClient.analyzeTranscript(transcript)

        val analysis: String
        val suggestions: String

        when (result) {
            is GeminiResult.Success -> {
                val text = result.text
                analysis = extractJson(text, "ozet") ?: text.take(200)
                suggestions = extractJson(text, "oneri") ?: "Öneri yok"

                // Görev varsa bildirim gönder
                val tasks = extractJsonArray(text, "yapilacaklar")
                if (tasks.isNotEmpty()) {
                    sendTaskNotification(tasks)
                }
            }
            is GeminiResult.Error -> {
                analysis = "Analiz başarısız: ${result.message}"
                suggestions = ""
            }
        }

        // 3. Veritabanına kaydet
        val db = AppDatabase.getInstance(this)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        db.audioRecordDao().insert(
            AudioRecord(
                transcript = transcript,
                analysis = analysis,
                suggestions = suggestions,
                date = today
            )
        )
    }

    private fun sendTaskNotification(tasks: List<String>) {
        val notifManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val taskText = tasks.take(3).joinToString("\n• ", prefix = "• ")

        val notif = NotificationCompat.Builder(this, "solis_tasks")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("📋 Solis - Yapılacaklar")
            .setContentText(tasks.firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(taskText))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        // Task notification channel
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel("solis_tasks", "Görev Bildirimleri", NotificationManager.IMPORTANCE_HIGH)
            notifManager.createNotificationChannel(channel)
        }

        notifManager.notify(System.currentTimeMillis().toInt(), notif)
    }

    private fun extractJson(json: String, key: String): String? {
        return try {
            val gson = com.google.gson.Gson()
            val map = gson.fromJson(json, Map::class.java)
            map[key]?.toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun extractJsonArray(json: String, key: String): List<String> {
        return try {
            val gson = com.google.gson.Gson()
            val map = gson.fromJson(json, Map::class.java)
            @Suppress("UNCHECKED_CAST")
            (map[key] as? List<String>) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Solis:ListenerLock")
        wakeLock?.acquire(12 * 60 * 60 * 1000L) // 12 saat max
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Solis Dinleyici",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Solis asistan arka planda çalışıyor"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ListenerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("🤖 Solis Asistan")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Durdur", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        isListening = false
        recorder.stop()
        scope.cancel()
        wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
