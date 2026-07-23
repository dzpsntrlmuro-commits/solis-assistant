package com.temiztube.app.data

import com.temiztube.app.model.DownloadAssets
import com.temiztube.app.model.PlayableStream
import com.temiztube.app.model.StreamKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object PipedClient {

    private val instances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://api.piped.private.coffee",
        "https://pipedapi.adminforge.de",
        "https://pipedapi.reallyaweso.me",
        "https://pipedapi.ducks.party",
        "https://pipedapi.colinslegacy.com"
    )

    private val playClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(false)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun resolveStreamFast(videoId: String): PlayableStream = withContext(Dispatchers.IO) {
        coroutineScope {
            val results = Channel<PlayableStream>(Channel.CONFLATED)
            val jobs = instances.map { base ->
                launch {
                    runCatching {
                        parseStreams(fetchJson(playClient, base.trimEnd('/'), videoId))
                    }.onSuccess { results.trySend(it) }
                }
            }
            try {
                withTimeout(5_000) { results.receive() }
            } finally {
                jobs.forEach { it.cancel() }
                results.close()
            }
        }
    }

    suspend fun resolveDownloadAssets(videoId: String): DownloadAssets = withContext(Dispatchers.IO) {
        coroutineScope {
            val results = Channel<DownloadAssets>(Channel.CONFLATED)
            val jobs = instances.map { base ->
                launch {
                    runCatching {
                        parseDownloadAssets(fetchJson(downloadClient, base.trimEnd('/'), videoId))
                    }.onSuccess { results.trySend(it) }
                }
            }
            try {
                withTimeout(12_000) { results.receive() }
            } finally {
                jobs.forEach { it.cancel() }
                results.close()
            }
        }
    }

    private fun fetchJson(client: OkHttpClient, base: String, videoId: String): JSONObject {
        val request = Request.Builder()
            .url("$base/streams/$videoId")
            .header("User-Agent", "Murotube/1.6")
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body?.string().orEmpty().trim()
            if (!body.startsWith("{")) throw IllegalStateException("JSON değil")
            val json = JSONObject(body)
            if (json.has("error") && json.optString("error").isNotBlank()) {
                throw IllegalStateException(json.optString("error").take(80))
            }
            return json
        }
    }

    private fun parseStreams(json: JSONObject): PlayableStream {
        val title = json.optString("title")
        val uploader = json.optString("uploader")

        json.optString("hls").takeIf { it.isNotBlank() }?.let { hls ->
            return PlayableStream(
                videoUrl = hls,
                kind = StreamKind.HLS,
                title = title,
                uploader = uploader,
                qualityLabel = "HLS"
            )
        }

        val videoStreams = json.optJSONArray("videoStreams")
        if (videoStreams != null) {
            val muxed = collectMuxedMp4(videoStreams, maxQuality = 720)
            muxed.minByOrNull { abs(qualityRank(it.optString("quality")) - 480) }?.let { best ->
                return PlayableStream(
                    videoUrl = best.getString("url"),
                    kind = StreamKind.PROGRESSIVE,
                    title = title,
                    uploader = uploader,
                    qualityLabel = best.optString("quality").ifBlank { "mp4" }
                )
            }

            collectMuxedMp4(videoStreams, maxQuality = 2160).firstOrNull()?.let { best ->
                return PlayableStream(
                    videoUrl = best.getString("url"),
                    kind = StreamKind.PROGRESSIVE,
                    title = title,
                    uploader = uploader,
                    qualityLabel = best.optString("quality").ifBlank { "mp4" }
                )
            }

            val audioUrl = pickAudioStream(json)?.url
            val candidates = collectVideoOnlyMp4(videoStreams, maxQuality = 720)
            candidates.minByOrNull { abs(qualityRank(it.optString("quality")) - 480) }?.let { best ->
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

        throw IllegalStateException("Akış yok")
    }

    private fun parseDownloadAssets(json: JSONObject): DownloadAssets {
        val title = json.optString("title").ifBlank { "murotube" }
        val videoStreams = json.optJSONArray("videoStreams")

        var videoUrl: String? = null
        var videoQuality = ""
        if (videoStreams != null) {
            val muxed = collectMuxedMp4(videoStreams, maxQuality = 1080)
            muxed.maxByOrNull { qualityRank(it.optString("quality")) }?.let {
                videoUrl = it.getString("url")
                videoQuality = it.optString("quality")
            }
            if (videoUrl == null) {
                collectMuxedMp4(videoStreams, maxQuality = 2160)
                    .maxByOrNull { qualityRank(it.optString("quality")) }
                    ?.let {
                        videoUrl = it.getString("url")
                        videoQuality = it.optString("quality")
                    }
            }
        }

        val audio = pickAudioStream(json)
        val audioFileExt = if (audio?.ext == "mp3") "mp3" else "m4a"
        val audioMime = when {
            audioFileExt == "mp3" -> "audio/mpeg"
            !audio?.mime.isNullOrBlank() -> audio!!.mime
            else -> "audio/mp4"
        }

        return DownloadAssets(
            title = title,
            videoUrl = videoUrl,
            videoFileName = MediaDownloader.sanitizeFileName(
                if (videoQuality.isNotBlank()) "$title $videoQuality" else title,
                "mp4"
            ),
            videoMime = "video/mp4",
            audioUrl = audio?.url,
            audioFileName = MediaDownloader.sanitizeFileName(title, audioFileExt),
            audioMime = audioMime
        )
    }

    private data class AudioPick(val url: String, val ext: String, val mime: String)

    private fun pickAudioStream(json: JSONObject): AudioPick? {
        val audioStreams = json.optJSONArray("audioStreams") ?: return null
        var bestMp3: AudioPick? = null
        var bestMp3Score = -1
        var bestM4a: AudioPick? = null
        var bestM4aScore = -1

        for (i in 0 until audioStreams.length()) {
            val a = audioStreams.getJSONObject(i)
            val url = a.optString("url")
            if (url.isBlank()) continue
            val mime = a.optString("mimeType").lowercase()
            val format = a.optString("format").uppercase()
            val score = a.optInt("bitrate", 0).takeIf { it > 0 } ?: a.optInt("quality", 0)

            val isMp3 = mime.contains("mp3") ||
                (mime.contains("mpeg") && !mime.contains("mp4")) ||
                format == "MP3"
            val isM4a = mime.contains("mp4") || mime.contains("m4a") ||
                format.contains("M4A") || format.contains("MPEG")

            when {
                isMp3 && score >= bestMp3Score -> {
                    bestMp3Score = score
                    bestMp3 = AudioPick(url, "mp3", "audio/mpeg")
                }
                isM4a && score >= bestM4aScore -> {
                    bestM4aScore = score
                    bestM4a = AudioPick(url, "m4a", mime.ifBlank { "audio/mp4" })
                }
            }
        }
        return bestMp3 ?: bestM4a
    }

    private fun collectMuxedMp4(videoStreams: JSONArray, maxQuality: Int): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        for (i in 0 until videoStreams.length()) {
            val s = videoStreams.getJSONObject(i)
            if (s.optBoolean("videoOnly", true)) continue
            if (s.optString("url").isBlank()) continue
            if (!isMp4Video(s)) continue
            val q = qualityRank(s.optString("quality"))
            if (q in 1..maxQuality) out.add(s)
        }
        return out
    }

    private fun collectVideoOnlyMp4(videoStreams: JSONArray, maxQuality: Int): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        for (i in 0 until videoStreams.length()) {
            val s = videoStreams.getJSONObject(i)
            if (!s.optBoolean("videoOnly", false)) continue
            if (s.optString("url").isBlank()) continue
            if (!isMp4Video(s)) continue
            val q = qualityRank(s.optString("quality"))
            if (q in 1..maxQuality) out.add(s)
        }
        return out
    }

    private fun isMp4Video(s: JSONObject): Boolean {
        val format = s.optString("format").uppercase()
        val mime = s.optString("mimeType").lowercase()
        val url = s.optString("url").lowercase()
        return format.contains("MPEG") || mime.contains("mp4") ||
            url.contains("mime=video%2fmp4") || url.contains("mime=video/mp4")
    }

    private fun qualityRank(quality: String): Int =
        quality.filter { it.isDigit() }.toIntOrNull() ?: 0
}
