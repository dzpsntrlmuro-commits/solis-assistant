package com.temiztube.app.data

import com.temiztube.app.model.PlayableStream
import com.temiztube.app.model.StreamKind
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Piped frontends that proxy YouTube streams. Tried in order until one succeeds.
 */
object PipedClient {

    private val instances = listOf(
        "https://pipedapi.reallyaweso.me",
        "https://api.piped.private.coffee",
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://pipedapi.syncpundit.io",
        "https://pipedapi.colinslegacy.com",
        "https://pipedapi.ducks.party",
        "https://pipedapi.darkness.services"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun resolveStream(videoId: String): PlayableStream {
        var lastError: Exception? = null
        for (base in instances) {
            try {
                return fetchFrom(base.trimEnd('/'), videoId)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Piped üzerinden video alınamadı")
    }

    private fun fetchFrom(base: String, videoId: String): PlayableStream {
        val request = Request.Builder()
            .url("$base/streams/$videoId")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) TemizTube/1.1")
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty().trim()
            if (!body.startsWith("{")) {
                throw IllegalStateException("JSON değil")
            }
            return parseStreams(JSONObject(body))
        }
    }

    private fun parseStreams(json: JSONObject): PlayableStream {
        if (json.has("error") && json.optString("error").isNotBlank()) {
            throw IllegalStateException(json.optString("error").take(120))
        }

        val title = json.optString("title")
        val uploader = json.optString("uploader")
        val videoStreams = json.optJSONArray("videoStreams")

        if (videoStreams != null) {
            val muxedMp4 = ArrayList<JSONObject>()
            for (i in 0 until videoStreams.length()) {
                val s = videoStreams.getJSONObject(i)
                if (s.optBoolean("videoOnly", true)) continue
                if (s.optString("url").isBlank()) continue
                if (isMp4Video(s)) muxedMp4.add(s)
            }
            muxedMp4.maxByOrNull { qualityRank(it.optString("quality")) }?.let { best ->
                return PlayableStream(
                    videoUrl = best.getString("url"),
                    kind = StreamKind.PROGRESSIVE,
                    title = title,
                    uploader = uploader,
                    qualityLabel = best.optString("quality").ifBlank { "mp4" }
                )
            }
        }

        json.optString("hls").takeIf { it.isNotBlank() }?.let { hls ->
            return PlayableStream(
                videoUrl = hls,
                kind = StreamKind.HLS,
                title = title,
                uploader = uploader,
                qualityLabel = "HLS"
            )
        }

        if (videoStreams != null) {
            val videoOnlyMp4 = ArrayList<JSONObject>()
            for (i in 0 until videoStreams.length()) {
                val s = videoStreams.getJSONObject(i)
                if (!s.optBoolean("videoOnly", false)) continue
                if (s.optString("url").isBlank()) continue
                if (!isMp4Video(s)) continue
                val q = qualityRank(s.optString("quality"))
                if (q in 1..1080) videoOnlyMp4.add(s)
            }
            val audioUrl = pickAudioUrl(json)
            videoOnlyMp4.maxByOrNull { qualityRank(it.optString("quality")) }?.let { best ->
                return PlayableStream(
                    videoUrl = best.getString("url"),
                    audioUrl = audioUrl,
                    kind = if (audioUrl != null) StreamKind.MERGED else StreamKind.PROGRESSIVE,
                    title = title,
                    uploader = uploader,
                    qualityLabel = best.optString("quality").ifBlank { "video" }
                )
            }
        }

        json.optString("dash").takeIf { it.isNotBlank() }?.let { dash ->
            return PlayableStream(
                videoUrl = dash,
                kind = StreamKind.DASH,
                title = title,
                uploader = uploader,
                qualityLabel = "DASH"
            )
        }

        throw IllegalStateException("Oynatılabilir akış yok")
    }

    private fun pickAudioUrl(json: JSONObject): String? {
        val audioStreams = json.optJSONArray("audioStreams") ?: return null
        var bestUrl: String? = null
        var bestScore = -1
        for (i in 0 until audioStreams.length()) {
            val a = audioStreams.getJSONObject(i)
            val url = a.optString("url")
            if (url.isBlank()) continue
            val mime = a.optString("mimeType").lowercase()
            val format = a.optString("format").uppercase()
            val mp4ish = mime.contains("mp4") || mime.contains("m4a") ||
                format.contains("M4A") || format.contains("MPEG")
            if (!mp4ish) continue
            val score = a.optInt("bitrate", 0).takeIf { it > 0 }
                ?: a.optInt("quality", 0)
            if (score >= bestScore) {
                bestScore = score
                bestUrl = url
            }
        }
        return bestUrl
    }

    private fun isMp4Video(s: JSONObject): Boolean {
        val format = s.optString("format").uppercase()
        val mime = s.optString("mimeType").lowercase()
        val url = s.optString("url").lowercase()
        return format.contains("MPEG") || mime.contains("mp4") ||
            url.contains("mime=video%2fmp4") || url.contains("mime=video/mp4")
    }

    private fun qualityRank(quality: String): Int {
        val digits = quality.filter { it.isDigit() }
        return digits.toIntOrNull() ?: 0
    }
}
