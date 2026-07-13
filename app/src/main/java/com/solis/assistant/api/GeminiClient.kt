package com.solis.assistant.api

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.solis.assistant.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

object GeminiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val apiKey = BuildConfig.GEMINI_API_KEY
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"

    // Ses metnini analiz et ve öneriler üret
    suspend fun analyzeTranscript(transcript: String): GeminiResult = withContext(Dispatchers.IO) {
        val prompt = """
            Sen Solis adında bir kişisel asistansın. Türkçe konuşan bir kullanıcının günlük sesli kayıtlarını analiz ediyorsun.
            
            Aşağıdaki ses kaydı metni kullanıcının gün içindeki konuşmalarından alındı:
            
            "$transcript"
            
            Lütfen şunları yap:
            1. Bu konuşmada geçen önemli konuları, kişileri, görevleri özetle (2-3 cümle)
            2. Kullanıcının yapması gereken işler veya takip etmesi gereken konular varsa listele
            3. Faydalı bir öneri veya fikir sun
            
            JSON formatında yanıt ver:
            {
              "ozet": "...",
              "yapilacaklar": ["...", "..."],
              "oneri": "..."
            }
        """.trimIndent()

        makeRequest(prompt)
    }

    // Gün sonu özeti oluştur
    suspend fun createDailySummary(allTranscripts: String): GeminiResult = withContext(Dispatchers.IO) {
        val prompt = """
            Sen Solis adında bir kişisel asistansın. Kullanıcının bugünkü tüm ses kayıtlarını analiz ediyorsun.
            
            Bugünkü tüm konuşmalar:
            $allTranscripts
            
            Gün sonu kapsamlı özet oluştur:
            1. Bugün kimlerle konuşuldu?
            2. Hangi konular ele alındı?
            3. Tamamlanan veya tamamlanması gereken işler neler?
            4. Yarın için öneriler
            
            JSON formatında yanıt ver:
            {
              "ozet": "...",
              "konusulanlar": ["...", "..."],
              "konular": ["...", "..."],
              "yapilacaklar": ["...", "..."],
              "yarin_onerileri": ["...", "..."]
            }
        """.trimIndent()

        makeRequest(prompt)
    }

    private fun makeRequest(prompt: String): GeminiResult {
        return try {
            val requestBody = gson.toJson(
                mapOf(
                    "contents" to listOf(
                        mapOf("parts" to listOf(mapOf("text" to prompt)))
                    ),
                    "generationConfig" to mapOf(
                        "temperature" to 0.7,
                        "maxOutputTokens" to 1024
                    )
                )
            )

            val request = Request.Builder()
                .url("$BASE_URL?key=$apiKey")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val parsed = gson.fromJson(body, GeminiResponse::class.java)
                val text = parsed.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: ""
                GeminiResult.Success(text)
            } else {
                GeminiResult.Error("API Hatası: ${response.code}")
            }
        } catch (e: IOException) {
            GeminiResult.Error("Bağlantı hatası: ${e.message}")
        } catch (e: Exception) {
            GeminiResult.Error("Hata: ${e.message}")
        }
    }
}

sealed class GeminiResult {
    data class Success(val text: String) : GeminiResult()
    data class Error(val message: String) : GeminiResult()
}

// Response modelleri
data class GeminiResponse(
    val candidates: List<Candidate>?
)

data class Candidate(
    val content: Content?
)

data class Content(
    val parts: List<Part>?
)

data class Part(
    val text: String?
)
