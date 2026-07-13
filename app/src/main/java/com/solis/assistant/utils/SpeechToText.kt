package com.solis.assistant.utils

import android.util.Log
import com.solis.assistant.BuildConfig
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Base64

object SpeechToText {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun transcribeAudio(audioFile: File): String {
        return try {
            if (!audioFile.exists() || audioFile.length() < 1000) {
                return "[Ses yok veya çok kısa]"
            }

            val audioBytes = audioFile.readBytes()
            val base64Audio = Base64.getEncoder().encodeToString(audioBytes)

            val apiKey = BuildConfig.GEMINI_API_KEY
            val url = "https://speech.googleapis.com/v1/speech:recognize?key=$apiKey"

            val requestJson = """{"config":{"encoding":"LINEAR16","sampleRateHertz":16000,"languageCode":"tr-TR","enableAutomaticPunctuation":true},"audio":{"content":"$base64Audio"}}"""

            val body = requestJson.toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(body).build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return "[API Hatası: ${response.code}]"
                val json = JSONObject(response.body?.string() ?: return "[Boş yanıt]")
                val results = json.optJSONArray("results") ?: return "[Ses anlaşılamadı]"
                val sb = StringBuilder()
                for (i in 0 until results.length()) {
                    val alternatives = results.getJSONObject(i).optJSONArray("alternatives")
                    if (alternatives != null && alternatives.length() > 0) {
                        sb.append(alternatives.getJSONObject(0).optString("transcript", ""))
                        sb.append(" ")
                    }
                }
                sb.toString().trim().ifEmpty { "[Ses anlaşılamadı]" }
            }
        } catch (e: Exception) {
            Log.e("SpeechToText", "Hata: ${e.message}")
            "[Çeviri hatası: ${e.message}]"
        }
    }
}
