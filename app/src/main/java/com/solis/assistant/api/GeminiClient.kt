package com.solis.assistant.api

import android.util.Log
import com.solis.assistant.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

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

    private val apiKey = BuildConfig.GEMINI_API_KEY
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"

    suspend fun analyzeTranscript(transcript: String): GeminiResult = withContext(Dispatchers.IO) {
        val prompt = """Sen Solis adlı kişisel asistansın. Kullanıcının sesli kayıt metnini analiz et:
"$transcript"
Şu formatta yanıt ver (JSON):
{"ozet":"...","gorevler":["...","..."],"oneri":"..."}"""

        val requestJson = """{"contents":[{"parts":[{"text":"${ prompt.replace(""","\"").replace("
","\n") }"}]}]}"""

        try {
            val body = requestJson.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$BASE_URL?key=$apiKey")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext GeminiResult("API hatası: ${response.code}", emptyList(), "")
                }
                val responseStr = response.body?.string() ?: ""
                val json = JSONObject(responseStr)
                val text = json
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")

                return@withContext try {
                    val clean = text.trim().removePrefix("```json").removeSuffix("```").trim()
                    val result = JSONObject(clean)
                    val gorevlerArr = result.optJSONArray("gorevler")
                    val gorevler = mutableListOf<String>()
                    if (gorevlerArr != null) {
                        for (i in 0 until gorevlerArr.length()) gorevler.add(gorevlerArr.getString(i))
                    }
                    GeminiResult(
                        summary = result.optString("ozet", text),
                        tasks = gorevler,
                        suggestion = result.optString("oneri", "")
                    )
                } catch (e: Exception) {
                    GeminiResult(text, emptyList(), "")
                }
            }
        } catch (e: IOException) {
            Log.e("GeminiClient", "Hata: ${e.message}")
            GeminiResult("Bağlantı hatası: ${e.message}", emptyList(), "")
        }
    }

    suspend fun generateDailySummary(records: List<String>): String = withContext(Dispatchers.IO) {
        val combined = records.joinToString("\n---\n")
        val prompt = "Bugünün ses kayıtlarını özetle (Türkçe, 3-5 cümle):\n$combined"
        val requestJson = """{"contents":[{"parts":[{"text":"${prompt.replace(""","\"").replace("
","\n")}"}]}]}"""
        try {
            val body = requestJson.toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url("$BASE_URL?key=$apiKey").post(body).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext "API hatası"
                val json = JSONObject(response.body?.string() ?: "")
                json.getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                    .getJSONObject(0).getString("text")
            }
        } catch (e: Exception) {
            "Özet oluşturulamadı: ${e.message}"
        }
    }
}
