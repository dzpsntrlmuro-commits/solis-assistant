package com.solis.assistant.utils

import android.content.Context
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.solis.assistant.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import kotlin.coroutines.resume

/**
 * Ses dosyasını Google Speech-to-Text API ile metne çevirir.
 * Gemini API key'i kullanır (Google Cloud Speech API).
 */
object SpeechToText {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // Google Speech-to-Text REST API
    fun transcribeAudio(audioFile: File): String {
        return try {
            if (!audioFile.exists() || audioFile.length() < 1000) {
                return "[Ses yok veya çok kısa]"
            }

            // Dosyayı base64'e çevir
            val audioBytes = audioFile.readBytes()
            val base64Audio = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP)

            val apiKey = BuildConfig.GEMINI_API_KEY
            val url = "https://speech.googleapis.com/v1/speech:recognize?key=$apiKey"

            val requestJson = """
                {
                  "config": {
                    "encoding": "LINEAR16",
                    "sampleRateHertz": 16000,
                    "languageCode": "tr-TR",
                    "enableAutomaticPunctuation": true,
                    "model": "default"
                  },
                  "audio": {
                    "content": "$base64Audio"
                  }
                }
            """.trimIndent()

            val requestBody = requestJson.toByteArray()
                .let { okhttp3.RequestBody.create("application/json".toMediaType(), it) }

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (response.isSuccessful) {
                parseTranscript(body)
            } else {
                Log.e("SpeechToText", "Hata: $body")
                "[Ses tanıma hatası: ${response.code}]"
            }
        } catch (e: Exception) {
            Log.e("SpeechToText", "Exception: ${e.message}")
            "[Ses tanıma başarısız]"
        }
    }

    private fun parseTranscript(json: String): String {
        return try {
            val gson = com.google.gson.Gson()
            val response = gson.fromJson(json, SpeechResponse::class.java)
            response.results
                ?.flatMap { it.alternatives ?: emptyList() }
                ?.maxByOrNull { it.confidence ?: 0f }
                ?.transcript
                ?: "[Konuşma algılanamadı]"
        } catch (e: Exception) {
            "[Parse hatası]"
        }
    }
}

data class SpeechResponse(val results: List<SpeechResult>?)
data class SpeechResult(val alternatives: List<SpeechAlternative>?)
data class SpeechAlternative(val transcript: String?, val confidence: Float?)
