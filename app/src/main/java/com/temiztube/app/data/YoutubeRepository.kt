package com.temiztube.app.data

import com.temiztube.app.model.DownloadAssets
import com.temiztube.app.model.PlayableStream
import com.temiztube.app.model.StreamKind
import com.temiztube.app.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.Locale

class YoutubeRepository {

    private val youtube = ServiceList.YouTube

    suspend fun search(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val extractor = youtube.getSearchExtractor(query)
        extractor.fetchPage()
        extractor.initialPage.items.mapNotNull { it.toVideoItem() }
    }

    suspend fun trending(): List<VideoItem> = withContext(Dispatchers.IO) {
        val extractor = youtube.kioskList.defaultKioskExtractor
        extractor.fetchPage()
        extractor.initialPage.items.mapNotNull { it.toVideoItem() }
    }

    suspend fun resolvePlayableFast(url: String): PlayableStream {
        val videoId = extractVideoId(url)
            ?: throw IllegalArgumentException("Geçersiz video bağlantısı")
        return withTimeout(5_000) {
            PipedClient.resolveStreamFast(videoId)
        }
    }

    suspend fun resolveDownloadAssets(url: String): DownloadAssets = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(url)
            ?: throw IllegalArgumentException("Geçersiz video bağlantısı")

        coroutineScope {
            val newPipeJob = async {
                runCatching { resolveDownloadViaNewPipe(url) }.getOrNull()
            }
            val pipedJob = async {
                runCatching {
                    withTimeout(15_000) { PipedClient.resolveDownloadAssets(videoId) }
                }.getOrNull()
            }

            val newPipe = newPipeJob.await()
            if (newPipe != null && (newPipe.hasVideo() || newPipe.hasAudio())) {
                val okVideo = newPipe.videoUrl.isNullOrBlank() || MediaDownloader.isReachable(newPipe.videoUrl!!)
                val okAudio = newPipe.audioUrl.isNullOrBlank() || MediaDownloader.isReachable(newPipe.audioUrl!!)
                if (okVideo || okAudio) return@coroutineScope newPipe
            }

            val piped = pipedJob.await()
            if (piped != null && (piped.hasVideo() || piped.hasAudio())) {
                return@coroutineScope piped
            }

            if (newPipe != null && (newPipe.hasVideo() || newPipe.hasAudio())) {
                return@coroutineScope newPipe
            }

            throw IllegalStateException("İndirme bağlantısı alınamadı")
        }
    }

    fun downloadAssetsFromPlayable(stream: PlayableStream): DownloadAssets? {
        if (stream.kind != StreamKind.PROGRESSIVE) return null
        if (stream.videoUrl.isBlank()) return null
        if (stream.videoUrl.contains(".m3u8", ignoreCase = true)) return null
        if (stream.videoUrl.contains("manifest", ignoreCase = true)) return null
        val title = stream.title.ifBlank { "murotube" }
        return DownloadAssets(
            title = title,
            videoUrl = stream.videoUrl,
            videoFileName = MediaDownloader.sanitizeFileName(
                if (stream.qualityLabel.isNotBlank()) "$title ${stream.qualityLabel}" else title,
                "mp4"
            ),
            videoMime = "video/mp4",
            audioUrl = stream.audioUrl,
            audioFileName = MediaDownloader.sanitizeFileName(title, "m4a"),
            audioMime = "audio/mp4"
        )
    }

    private fun resolveDownloadViaNewPipe(url: String): DownloadAssets {
        val info = StreamInfo.getInfo(url)
        val title = info.name?.takeIf { it.isNotBlank() } ?: "murotube"

        val video = info.videoStreams
            .asSequence()
            .filter { !it.isVideoOnly }
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .filter { !it.content.isNullOrBlank() && it.isUrl }
            .filter {
                val format = it.format
                format == null || format == MediaFormat.MPEG_4 || format == MediaFormat.v3GPP
            }
            .sortedWith(
                compareByDescending<org.schabi.newpipe.extractor.stream.VideoStream> {
                    resolutionHeight(it.resolution)
                }.thenByDescending {
                    if (it.format == MediaFormat.MPEG_4) 1 else 0
                }
            )
            .firstOrNull { resolutionHeight(it.resolution) in 1..1080 }
            ?: info.videoStreams
                .asSequence()
                .filter { !it.isVideoOnly }
                .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .filter { !it.content.isNullOrBlank() && it.isUrl }
                .maxByOrNull { resolutionHeight(it.resolution) }

        val audio = info.audioStreams
            .asSequence()
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .filter { !it.content.isNullOrBlank() && it.isUrl }
            .sortedWith(
                compareByDescending<org.schabi.newpipe.extractor.stream.AudioStream> {
                    when (it.format) {
                        MediaFormat.MP3 -> 3
                        MediaFormat.M4A -> 2
                        MediaFormat.WEBMA, MediaFormat.WEBMA_OPUS, MediaFormat.OPUS -> 1
                        else -> 0
                    }
                }.thenByDescending { it.averageBitrate }
            )
            .firstOrNull()

        val videoFormat = video?.format
        val videoExt = videoFormat?.suffix?.ifBlank { "mp4" } ?: "mp4"
        val videoMime = videoFormat?.mimeType ?: "video/mp4"
        val quality = video?.resolution.orEmpty()

        val audioFormat = audio?.format
        val audioExt = when (audioFormat) {
            MediaFormat.MP3 -> "mp3"
            MediaFormat.M4A -> "m4a"
            MediaFormat.WEBMA, MediaFormat.WEBMA_OPUS, MediaFormat.OPUS -> "webm"
            else -> audioFormat?.suffix?.ifBlank { "m4a" } ?: "m4a"
        }
        val audioMime = audioFormat?.mimeType ?: "audio/mp4"

        val assets = DownloadAssets(
            title = title,
            videoUrl = video?.content,
            videoFileName = MediaDownloader.sanitizeFileName(
                if (quality.isNotBlank()) "$title $quality" else title,
                videoExt
            ),
            videoMime = videoMime,
            audioUrl = audio?.content,
            audioFileName = MediaDownloader.sanitizeFileName(title, audioExt),
            audioMime = audioMime
        )
        if (!assets.hasVideo() && !assets.hasAudio()) {
            throw IllegalStateException("NewPipe akış yok")
        }
        return assets
    }

    private fun resolutionHeight(resolution: String?): Int {
        if (resolution.isNullOrBlank()) return 0
        return resolution.filter { it.isDigit() }.toIntOrNull() ?: 0
    }

    private fun InfoItem.toVideoItem(): VideoItem? {
        if (this !is StreamInfoItem) return null
        val id = extractVideoId(url) ?: url.substringAfterLast('/')
        return VideoItem(
            id = id,
            url = url,
            title = name.orEmpty(),
            uploader = uploaderName.orEmpty(),
            thumbnailUrl = thumbnails.maxByOrNull { it.height }?.url,
            durationSeconds = duration,
            viewCount = viewCount
        )
    }

    companion object {
        fun extractVideoId(url: String): String? {
            val patterns = listOf(
                Regex("""[?&]v=([a-zA-Z0-9_-]{11})"""),
                Regex("""youtu\.be/([a-zA-Z0-9_-]{11})"""),
                Regex("""shorts/([a-zA-Z0-9_-]{11})"""),
                Regex("""embed/([a-zA-Z0-9_-]{11})""")
            )
            for (p in patterns) {
                val m = p.find(url)
                if (m != null) return m.groupValues[1]
            }
            return if (url.matches(Regex("""^[a-zA-Z0-9_-]{11}$"""))) url else null
        }

        fun formatDuration(seconds: Long): String {
            if (seconds <= 0L) return ""
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            return if (h > 0) {
                String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            } else {
                String.format(Locale.US, "%d:%02d", m, s)
            }
        }

        fun formatViews(count: Long): String {
            if (count <= 0L) return ""
            return when {
                count >= 1_000_000_000 -> String.format(Locale("tr"), "%.1f Mr", count / 1_000_000_000.0)
                count >= 1_000_000 -> String.format(Locale("tr"), "%.1f Mn", count / 1_000_000.0)
                count >= 1_000 -> String.format(Locale("tr"), "%.1f B", count / 1_000.0)
                else -> count.toString()
            }
        }
    }
}
