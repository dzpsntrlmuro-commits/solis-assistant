package com.apkatolye.app.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class CodePatch(
    val path: String,
    val action: String,
    val content: String? = null,
    val old: String? = null,
    val new: String? = null
)

/**
 * Cursor-style Gemini agent: listens to full conversation, consolidates goals,
 * then returns patches + local actions.
 */
class GeminiClient(private val context: Context) {

    fun hasKey(): Boolean = !getApiKey().isNullOrBlank()

    fun getApiKey(): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun setApiKey(key: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, key?.trim().orEmpty())
            .apply()
    }

    fun think(
        latestUserMessage: String,
        memory: ConversationMemory,
        workspaceSummary: String,
        focusFiles: Map<String, String>
    ): AgentDecision {
        val apiKey = getApiKey() ?: error("API anahtarı yok")

        val focusBlock = buildString {
            focusFiles.entries.take(8).forEach { (path, content) ->
                val clipped = if (content.length > 10_000) content.take(10_000) + "\n…(kısaltıldı)" else content
                appendLine("### FILE: $path")
                appendLine(clipped)
                appendLine()
            }
        }

        val system = """
Sen Cursor gibi çalışan bir APK düzenleme ajanısın.
Kullanıcının söylediği HER şeye odaklan. Hiçbir cümleyi unutma.
Arka planda sürekli bir "brief" (hedef özeti) tut ve güncelle.

Davranış:
1) Kullanıcının tüm notlarını ve son mesajı birleştir.
2) Ne istediğini netleştir; eksikse kısa soru sor ama mümkünse uygula.
3) Yapabileceğin dosya değişikliklerini patches olarak ver.
4) Gerekirse local_actions kullan: extract, rebuild, open_files, open_test, pick_image
5) Zararlı yazılım / lisans kırma / ödeme atlatma üretme. Kullanıcı kendi uygulamasını düzenliyor.

SADECE şu JSON'u döndür (markdown yok):
{
  "message": "kullanıcıya Türkçe, net cevap",
  "brief": "şimdiye kadar istenenlerin güncel özeti (madde madde)",
  "plan": "sıradaki adımlar",
  "focus_paths": ["ilgili/dosya.smali"],
  "local_actions": ["rebuild"],
  "listen_more": false,
  "patches": [
    {"action":"write","path":"...","content":"..."},
    {"action":"replace","path":"...","old":"...","new":"..."},
    {"action":"delete","path":"..."}
  ]
}
""".trimIndent()

        val userPrompt = """
Güncel brief:
${memory.brief.ifBlank { "(henüz yok)" }}

Kullanıcının biriken tüm notları:
${memory.allUserNotes()}

Son konuşma:
${memory.recentTranscript(20)}

Çalışma alanı:
$workspaceSummary

Odak dosya içerikleri:
$focusBlock

SON KULLANICI MESAJI (buna özellikle odaklan):
$latestUserMessage
""".trimIndent()

        val body = JSONObject()
            .put(
                "system_instruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", system))
                )
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
                )
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.25)
                    .put("maxOutputTokens", 8192)
            )

        val models = listOf(
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite",
            "gemini-1.5-flash"
        )
        var lastError: Exception? = null
        for (model in models) {
            try {
                val raw = post(apiKey, model, body.toString())
                val text = extractModelText(raw)
                return parseDecision(text)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: error("Gemini yanıt vermedi")
    }

    private fun post(apiKey: String, model: String, jsonBody: String): String {
        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 45_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(jsonBody) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
        if (code !in 200..299) error("Gemini ($model) $code: ${raw.take(500)}")
        return raw
    }

    private fun extractModelText(raw: String): String {
        val root = JSONObject(raw)
        val candidates = root.optJSONArray("candidates") ?: error("Gemini yanıtı boş")
        if (candidates.length() == 0) error("Gemini aday yok")
        val content = candidates.getJSONObject(0).optJSONObject("content")
        val parts = content?.optJSONArray("parts") ?: error("Gemini parts yok")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            sb.append(parts.getJSONObject(i).optString("text"))
        }
        val text = sb.toString().trim()
        if (text.isEmpty()) error("Gemini boş metin")
        return text
    }

    private fun parseDecision(modelText: String): AgentDecision {
        var jsonText = modelText.trim()
        if (jsonText.startsWith("```")) {
            jsonText = jsonText
                .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
                .removeSuffix("```").trim()
        }
        val start = jsonText.indexOf('{')
        val end = jsonText.lastIndexOf('}')
        if (start >= 0 && end > start) jsonText = jsonText.substring(start, end + 1)

        val obj = JSONObject(jsonText)
        val patches = mutableListOf<CodePatch>()
        val arr = obj.optJSONArray("patches") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            patches += CodePatch(
                path = p.getString("path"),
                action = p.optString("action", "write"),
                content = p.stringOrNull("content"),
                old = p.stringOrNull("old"),
                new = p.stringOrNull("new")
            )
        }
        val actions = mutableListOf<String>()
        val la = obj.optJSONArray("local_actions") ?: JSONArray()
        for (i in 0 until la.length()) actions += la.getString(i)

        val focus = mutableListOf<String>()
        val fp = obj.optJSONArray("focus_paths") ?: JSONArray()
        for (i in 0 until fp.length()) focus += fp.getString(i)

        return AgentDecision(
            message = obj.optString("message", "Tamam, üzerinde çalışıyorum."),
            brief = obj.optString("brief", ""),
            plan = obj.optString("plan", ""),
            patches = patches,
            localActions = actions,
            focusPaths = focus,
            listenMore = obj.optBoolean("listen_more", false)
        )
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    companion object {
        private const val PREFS = "apk_atolye_ai"
        private const val KEY = "gemini_api_key"
    }
}
