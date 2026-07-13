package com.solis.assistant.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.solis.assistant.api.GeminiClient
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

    companion object {
        const val CHANNEL_ID = "solis_listener"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "STOP"
        const val ACTION_START = "START"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ListenerService::class.java).apply { action = ACTION_START })
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ListenerService::class.java).apply { action = ACTION_STOP })
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Hazır"))
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (!isListening) { isListening = true; startLoop() }
        return START_STICKY
    }

    private fun startLoop() {
        scope.launch {
            while (isListening) {
                try {
                    val file = File(cacheDir, "rec_${System.currentTimeMillis()}.wav")
                    updateNotification("Kayıt alınıyor...")
                    val ok = recorder.recordToFile(file, 45_000L)
                    if (ok && file.exists()) {
                        updateNotification("Analiz ediliyor...")
                        val transcript = SpeechToText.transcribeAudio(file)
                        val result = GeminiClient.analyzeTranscript(transcript)
                        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                        AppDatabase.getInstance(applicationContext).audioRecordDao().insert(
                            AudioRecord(
                                transcript = transcript,
                                analysis = result.summary,
                                suggestions = result.tasks.joinToString(", "),
                                date = today
                            )
                        )
                        file.delete()
                        updateNotification("Dinleniyor...")
                    }
                    delay(2 * 60 * 1000L)
                } catch (e: Exception) {
                    Log.e("ListenerService", e.message ?: "error")
                    delay(30_000L)
                }
            }
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Solis:WakeLock")
        wakeLock?.acquire(10 * 60 * 60 * 1000L)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Solis Asistan", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Solis Asistan")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isListening = false
        scope.cancel()
        recorder.stop()
        wakeLock?.release()
        super.onDestroy()
    }
}
