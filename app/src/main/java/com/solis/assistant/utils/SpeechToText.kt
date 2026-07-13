package com.solis.assistant.utils

import android.util.Log
import com.solis.assistant.BuildConfig
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

object SpeechToText {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun transcribeAudio(audioFile: File): String {
        if (!audioFile.exists() || audioFile.length() < 500) return "[Ses çok kısa]"
        return try {
            val audioBytes = audioFile.readBytes()
            val base64Audio = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP)
            val url = "https://speech.googleapis.com/v1/speech:recognize?key=${BuildConfig.GEMINI_API_KEY}"
            val body = buildRequest(base64Audio).toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(body).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return "[Hata: ${response.code}]"
                parseResponse(response.body?.string() ?: "")
            }
        } catch (e: Exception) {
            Log.e("STT", e.message ?: "error")
            "[Çeviri hatası]"
        }
    }

    private fun buildRequest(base64Audio: String) =
        """{"config":{"encoding":"LINEAR16","sampleRateHertz":16000,"languageCode":"tr-TR"},"audio":{"content":"$base64Audio"}}"""

    private fun parseResponse(json: String): String {
        return try {
            val arr = JSONObject(json).optJSONArray("results") ?: return "[Anlaşılamadı]"
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                arr.getJSONObject(i).optJSONArray("alternatives")?.let { alt ->
                    if (alt.length() > 0) sb.append(alt.getJSONObject(0).optString("transcript")).append(" ")
                }
            }
            sb.toString().trim().ifEmpty { "[Ses anlaşılamadı]" }
        } catch (e: Exception) { "[Parse hatası]" }
    }
}
