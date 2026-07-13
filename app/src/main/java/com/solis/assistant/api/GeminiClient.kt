package com.solis.assistant.api

import android.util.Log
import com.solis.assistant.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class GeminiResult(
    val summary: String,
    val tasks: List<String>,
    val suggestion: String
)

object GeminiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private const val BASE_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"

    suspend fun analyzeTranscript(transcript: String): GeminiResult = withContext(Dispatchers.IO) {
        val safeTranscript = transcript.take(2000)
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        val requestBody = """
            {"contents":[{"parts":[{"text":"Kullanıcı ses kaydı: $safeTranscript\nJSON formatında yanıt ver: {\"ozet\":\"...\",\"gorevler\":[\"...\"],\"oneri\":\"...\"}"}]}]}
        """.trimIndent()
        try {
            val body = requestBody.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$BASE_URL?key=${BuildConfig.GEMINI_API_KEY}")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext GeminiResult("Hata: ${response.code}", emptyList(), "")
                val text = extractText(response.body?.string() ?: "")
                parseResult(text)
            }
        } catch (e: Exception) {
            Log.e("Gemini", e.message ?: "error")
            GeminiResult("Bağlantı hatası", emptyList(), "")
        }
    }

    suspend fun generateDailySummary(records: List<String>): String = withContext(Dispatchers.IO) {
        val combined = records.joinToString(" / ").take(3000)
            .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val requestBody = """{"contents":[{"parts":[{"text":"Bugünün kayıtlarını 3 cümleyle özetle: $combined"}]}]}"""
        try {
            val body = requestBody.toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url("$BASE_URL?key=${BuildConfig.GEMINI_API_KEY}").post(body).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext "Hata"
                extractText(response.body?.string() ?: "")
            }
        } catch (e: Exception) {
            "Özet alınamadı"
        }
    }

    private fun extractText(responseStr: String): String {
        return try {
            JSONObject(responseStr)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
                .getJSONObject(0).getString("text")
        } catch (e: Exception) { "" }
    }

    private fun parseResult(text: String): GeminiResult {
        return try {
            val clean = text.trim().removePrefix("```json").removeSuffix("```").trim()
            val j = JSONObject(clean)
            val tasks = mutableListOf<String>()
            j.optJSONArray("gorevler")?.let { arr ->
                for (i in 0 until arr.length()) tasks.add(arr.getString(i))
            }
            GeminiResult(j.optString("ozet", text), tasks, j.optString("oneri", ""))
        } catch (e: Exception) {
            GeminiResult(text, emptyList(), "")
        }
    }
}
