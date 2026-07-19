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
    val action: String, // write | replace | delete
    val content: String? = null,
    val old: String? = null,
    val new: String? = null
)

/**
 * Optional Gemini client. User provides API key in the assistant settings.
 * Used to turn free-form Turkish instructions into concrete file patches.
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

    fun planPatches(
        instruction: String,
        workspaceSummary: String,
        focusFiles: Map<String, String>
    ): Pair<String, List<CodePatch>> {
        val apiKey = getApiKey() ?: error("API anahtarı yok")

        val focusBlock = buildString {
            focusFiles.entries.take(6).forEach { (path, content) ->
                val clipped = if (content.length > 12_000) content.take(12_000) + "\n…(kısaltıldı)" else content
                appendLine("### FILE: $path")
                appendLine(clipped)
                appendLine()
            }
        }

        val prompt = """
Sen bir APK / Android kaynak asistanısın. Kullanıcı kendi uygulamasını düzenliyor.
Görevin: talimatı uygula ve SADECE geçerli JSON döndür.

Kurallar:
- Yalnızca çıkarılan APK içindeki metin/smali/xml dosyalarını düzenle.
- Zararlı yazılım, lisans kırma, ödeme atlatma üretme.
- Yanıtın SADECE şu JSON olsun (markdown yok):
{
  "message": "kısa Türkçe özet",
  "patches": [
    {"action":"write","path":"smali/.../Foo.smali","content":"...tüm dosya..."},
    {"action":"replace","path":"res/values/strings.xml","old":"...","new":"..."},
    {"action":"delete","path":"path/to/file"}
  ]
}

Çalışma alanı özeti:
$workspaceSummary

Odak dosyalar:
$focusBlock

Kullanıcı talimatı:
$instruction
""".trimIndent()

        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt))
                    )
                )
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.2)
                    .put("maxOutputTokens", 8192)
            )

        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 45_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(body.toString()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
        if (code !in 200..299) {
            error("Gemini hata ($code): ${raw.take(400)}")
        }

        val text = extractModelText(raw)
        return parsePatches(text)
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
        return sb.toString().trim()
    }

    private fun parsePatches(modelText: String): Pair<String, List<CodePatch>> {
        var jsonText = modelText.trim()
        if (jsonText.startsWith("```")) {
            jsonText = jsonText
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        }
        // Try to find outermost JSON object
        val start = jsonText.indexOf('{')
        val end = jsonText.lastIndexOf('}')
        if (start >= 0 && end > start) {
            jsonText = jsonText.substring(start, end + 1)
        }

        val obj = JSONObject(jsonText)
        val message = obj.optString("message", "Değişiklikler hazır")
        val arr = obj.optJSONArray("patches") ?: JSONArray()
        val patches = mutableListOf<CodePatch>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            patches += CodePatch(
                path = p.getString("path"),
                action = p.optString("action", "write"),
                content = if (p.has("content") && !p.isNull("content")) p.getString("content") else null,
                old = if (p.has("old") && !p.isNull("old")) p.getString("old") else null,
                new = if (p.has("new") && !p.isNull("new")) p.getString("new") else null
            )
        }
        return message to patches
    }

    companion object {
        private const val PREFS = "apk_atolye_ai"
        private const val KEY = "gemini_api_key"
    }
}
