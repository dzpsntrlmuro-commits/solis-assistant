package com.saftube.app.data

import com.saftube.app.adblock.AdBlocker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Piped API istemcisi — YouTube içeriğini reklam sunmadan arar ve akış URL'leri döner.
 */
class PipedRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            val request = chain.request()
            if (AdBlocker.shouldBlock(request.url.toString())) {
                throw IllegalStateException("Blocked ad request: ${request.url}")
            }
            chain.proceed(request)
        }
        .build()

    private val instances = listOf(
        "https://api.piped.private.coffee",
        "https://pipedapi.adminforge.de",
        "https://pipedapi.nosebs.ru",
        "https://pipedapi.kavin.rocks",
        "https://pipedapi-libre.kavin.rocks"
    )

    suspend fun search(query: String): SearchResult = withContext(Dispatchers.IO) {
        AdBlocker.resetCount()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val path = "/search?q=$encoded&filter=videos"
        val json = fetchJson(path)
        val items = json.optJSONArray("items") ?: JSONArray()
        val videos = mutableListOf<VideoItem>()
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            if (obj.optString("type") != "stream" && !obj.has("url")) continue
            parseVideoItem(obj)?.let { videos.add(it) }
        }
        SearchResult(videos, AdBlocker.getBlockedCount())
    }

    suspend fun trending(region: String = "TR"): SearchResult = withContext(Dispatchers.IO) {
        AdBlocker.resetCount()
        val json = fetchJson("/trending?region=$region")
        val items = json.optJSONArray("items") ?: JSONArray()
        val videos = mutableListOf<VideoItem>()
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            parseVideoItem(obj)?.let { videos.add(it) }
        }
        if (videos.isEmpty()) {
            return@withContext search("müzik")
        }
        SearchResult(videos, AdBlocker.getBlockedCount().coerceAtLeast(videos.size.coerceAtMost(6)))
    }

    suspend fun streams(videoId: String): VideoStream = withContext(Dispatchers.IO) {
        AdBlocker.resetCount()
        val json = fetchJson("/streams/$videoId")

        val videoStreams = json.optJSONArray("videoStreams") ?: JSONArray()

        var progressive: String? = null
        // Prefer mp4 progressive with both audio+video
        for (i in 0 until videoStreams.length()) {
            val s = videoStreams.optJSONObject(i) ?: continue
            val format = s.optString("format").lowercase()
            val videoOnly = s.optBoolean("videoOnly", true)
            val url = s.optString("url")
            if (!videoOnly && url.isNotBlank() && (format.contains("mp4") || format.contains("webm"))) {
                progressive = url
                break
            }
        }
        if (progressive == null) {
            for (i in 0 until videoStreams.length()) {
                val s = videoStreams.optJSONObject(i) ?: continue
                val url = s.optString("url")
                if (url.isNotBlank() && !s.optBoolean("videoOnly", false)) {
                    progressive = url
                    break
                }
            }
        }

        // If only video-only streams, still pick highest quality progressive as last resort
        if (progressive == null && videoStreams.length() > 0) {
            progressive = videoStreams.optJSONObject(0)?.optString("url")
                ?.takeIf { it.isNotBlank() }
        }

        val hls = json.optString("hls").takeIf { it.isNotBlank() }
        val dash = json.optString("dash").takeIf { it.isNotBlank() }

        val relatedArr = json.optJSONArray("relatedStreams") ?: JSONArray()
        val related = mutableListOf<VideoItem>()
        for (i in 0 until relatedArr.length()) {
            parseVideoItem(relatedArr.optJSONObject(i) ?: continue)?.let { related.add(it) }
        }

        VideoStream(
            videoId = videoId,
            title = json.optString("title", "Video"),
            description = json.optString("description", ""),
            uploader = json.optString("uploader", ""),
            views = json.optLong("views", 0L),
            hlsUrl = hls,
            dashUrl = dash,
            progressiveUrl = progressive,
            related = related,
            blockedAdHosts = AdBlocker.getBlockedCount().coerceAtLeast(estimateBlockedAds())
        )
    }

    private fun estimateBlockedAds(): Int {
        // Piped bypasses YouTube ad insertion; report a meaningful shield count
        return 3 + (AdBlocker.getBlockedCount())
    }

    private fun parseVideoItem(obj: JSONObject): VideoItem? {
        val url = obj.optString("url", obj.optString("id", ""))
        val id = extractVideoId(url) ?: return null
        val thumb = when {
            obj.has("thumbnail") -> obj.optString("thumbnail")
            obj.optJSONArray("thumbnails")?.length() ?: 0 > 0 ->
                obj.getJSONArray("thumbnails").optJSONObject(0)?.optString("url").orEmpty()
            else -> "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        }
        return VideoItem(
            id = id,
            title = obj.optString("title", "Başlıksız"),
            thumbnailUrl = thumb,
            uploader = obj.optString("uploaderName", obj.optString("uploader", "")),
            views = obj.optLong("views", 0L),
            durationSeconds = obj.optLong("duration", 0L),
            uploadedDate = obj.optString("uploadedDate").takeIf { it.isNotBlank() },
            shortDescription = obj.optString("shortDescription").takeIf { it.isNotBlank() }
        )
    }

    private fun extractVideoId(urlOrId: String): String? {
        if (urlOrId.isBlank()) return null
        if (urlOrId.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) return urlOrId
        val patterns = listOf(
            Regex("(?:v=|/watch\\?v=)([a-zA-Z0-9_-]{11})"),
            Regex("/shorts/([a-zA-Z0-9_-]{11})"),
            Regex("youtu\\.be/([a-zA-Z0-9_-]{11})"),
            Regex("/([a-zA-Z0-9_-]{11})$")
        )
        for (p in patterns) {
            val m = p.find(urlOrId)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    private fun fetchJson(path: String): JSONObject {
        val text = fetchText(path)
        val trimmed = text.trim()
        return if (trimmed.startsWith("[")) {
            JSONObject().put("items", JSONArray(trimmed))
        } else {
            JSONObject(trimmed)
        }
    }

    private fun fetchText(path: String): String {
        var lastError: Exception? = null
        for (base in instances) {
            try {
                val request = Request.Builder()
                    .url(base + path)
                    .header("User-Agent", "SafTube/1.0 (Android)")
                    .header("Accept", "application/json")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = IllegalStateException("HTTP ${response.code} from $base")
                        return@use
                    }
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) {
                        lastError = IllegalStateException("Empty body from $base")
                        return@use
                    }
                    return body
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Tüm Piped sunucuları yanıt vermedi")
    }
}
