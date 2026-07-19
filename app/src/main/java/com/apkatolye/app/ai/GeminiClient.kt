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

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasKey(): Boolean = !getApiKey().isNullOrBlank()

    fun getApiKey(): String? =
        prefs.getString(KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setApiKey(key: String?) {
        prefs.edit()
            .putString(KEY, key?.trim().orEmpty())
            .remove(KEY_MODEL) // reset cached model when key changes
            .apply()
    }

    fun think(
        latestUserMessage: String,
        memory: ConversationMemory,
        workspaceSummary: String,
        focusFiles: Map<String, String>
    ): AgentDecision {
        val apiKey = getApiKey() ?: error("API anahtarı yok. Asistan → API ile Gemini anahtarı kaydet.")

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
6) Test mesajlarında (örn. "1 2 3 deneme") nazikçe onayla, brief'e gereksiz iş ekleme.

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

        val models = resolveModels(apiKey)
        var lastError: Exception? = null
        for (model in models) {
            try {
                val raw = postGenerate(apiKey, model, body.toString())
                val text = extractModelText(raw)
                val decision = parseDecision(text)
                prefs.edit().putString(KEY_MODEL, model).apply()
                return decision
            } catch (e: Exception) {
                lastError = e
                // stale cached model — clear and continue
                if (model == prefs.getString(KEY_MODEL, null)) {
                    prefs.edit().remove(KEY_MODEL).apply()
                }
            }
        }
        throw Exception(friendlyError(lastError))
    }

    private fun resolveModels(apiKey: String): List<String> {
        val preferred = mutableListOf<String>()
        prefs.getString(KEY_MODEL, null)?.let { preferred += it }
        preferred += listOf(
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-flash-latest",
            "gemini-2.0-flash",
            "gemini-2.0-flash-001",
            "gemini-3.5-flash"
        )
        // Discover live models from API as final fallbacks
        runCatching { listGenerateContentModels(apiKey) }
            .getOrDefault(emptyList())
            .forEach { preferred += it }

        return preferred.distinct()
    }

    private fun listGenerateContentModels(apiKey: String): List<String> {
        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey&pageSize=50"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 30_000
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
        if (code !in 200..299) return emptyList()

        val root = JSONObject(raw)
        val models = root.optJSONArray("models") ?: return emptyList()
        val names = mutableListOf<String>()
        for (i in 0 until models.length()) {
            val m = models.getJSONObject(i)
            val methods = m.optJSONArray("supportedGenerationMethods") ?: JSONArray()
            var supports = false
            for (j in 0 until methods.length()) {
                if (methods.getString(j) == "generateContent") {
                    supports = true
                    break
                }
            }
            if (!supports) continue
            val name = m.optString("name", "")
                .removePrefix("models/")
            // Prefer flash models for speed/cost
            if (name.contains("flash", ignoreCase = true) &&
                !name.contains("embed", ignoreCase = true) &&
                !name.contains("image", ignoreCase = true)
            ) {
                names += name
            }
        }
        return names
    }

    private fun postGenerate(apiKey: String, model: String, jsonBody: String): String {
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
        if (code !in 200..299) {
            val apiMsg = runCatching {
                JSONObject(raw).getJSONObject("error").optString("message")
            }.getOrNull()
            error("HTTP $code / $model${if (!apiMsg.isNullOrBlank()) ": $apiMsg" else ""}")
        }
        return raw
    }

    private fun friendlyError(e: Exception?): String {
        val msg = e?.message.orEmpty()
        return when {
            msg.contains("API_KEY_INVALID", true) || msg.contains("API key not valid", true) ->
                "API anahtarı geçersiz. Google AI Studio’dan yeni bir anahtar alıp Asistan → API ile kaydet."
            msg.contains("PERMISSION_DENIED", true) || msg.contains("403") ->
                "API anahtarının Gemini izni yok veya kısıtlı. AI Studio’da anahtarı kontrol et."
            msg.contains("429") || msg.contains("RESOURCE_EXHAUSTED", true) ->
                "Gemini kotası doldu. Biraz bekleyip tekrar dene."
            msg.contains("404") || msg.contains("not found", true) ->
                "Gemini modeli bulunamadı. Uygulamayı güncelledim; tekrar dene. Hâlâ olursa AI Studio’da model erişimini kontrol et."
            msg.contains("Unable to resolve host", true) || msg.contains("UnknownHost", true) ->
                "İnternet yok veya Gemini’ye ulaşılamıyor."
            else -> "Gemini hatası: ${msg.take(220)}"
        }
    }

    private fun extractModelText(raw: String): String {
        val root = JSONObject(raw)
        val candidates = root.optJSONArray("candidates") ?: error("Gemini yanıtı boş")
        if (candidates.length() == 0) {
            val block = root.optJSONObject("promptFeedback")?.toString().orEmpty()
            error("Gemini aday yok${if (block.isNotBlank()) ": $block" else ""}")
        }
        val candidate = candidates.getJSONObject(0)
        val finish = candidate.optString("finishReason")
        val content = candidate.optJSONObject("content")
        val parts = content?.optJSONArray("parts")
        if (parts == null || parts.length() == 0) {
            error("Gemini metin yok (finish=$finish)")
        }
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            sb.append(parts.getJSONObject(i).optString("text"))
        }
        val text = sb.toString().trim()
        if (text.isEmpty()) error("Gemini boş metin (finish=$finish)")
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
        private const val KEY_MODEL = "working_model"
    }
}
