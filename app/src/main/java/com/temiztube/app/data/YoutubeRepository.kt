package com.temiztube.app.data

import com.temiztube.app.model.PlayableStream
import com.temiztube.app.model.StreamKind
import com.temiztube.app.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.VideoStream
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

    suspend fun resolvePlayable(url: String): PlayableStream = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(url)
            ?: throw IllegalArgumentException("Geçersiz video bağlantısı")

        val errors = mutableListOf<String>()

        runCatching { PipedClient.resolveStream(videoId) }
            .onSuccess { return@withContext it }
            .onFailure { errors += "Piped: ${it.message}" }

        runCatching { resolveWithNewPipe(url) }
            .onSuccess { return@withContext it }
            .onFailure { errors += "NewPipe: ${it.message}" }

        throw IllegalStateException(errors.joinToString(" | ").ifBlank {
            "Video akışı alınamadı"
        })
    }

    private fun resolveWithNewPipe(url: String): PlayableStream {
        val info = StreamInfo.getInfo(youtube, url)
        val title = info.name.orEmpty()
        val uploader = info.uploaderName.orEmpty()

        // Prefer muxed progressive MP4 (single URL, most compatible)
        pickMuxed(info.videoStreams)?.let { chosen ->
            return PlayableStream(
                videoUrl = chosen.content,
                kind = StreamKind.PROGRESSIVE,
                title = title,
                uploader = uploader,
                qualityLabel = labelOf(chosen)
            )
        }

        // HLS (live / some VODs)
        info.hlsUrl?.takeIf { it.isNotBlank() }?.let { hls ->
            return PlayableStream(
                videoUrl = hls,
                kind = StreamKind.HLS,
                title = title,
                uploader = uploader,
                qualityLabel = "HLS"
            )
        }

        // Separate video + audio (typical YouTube case)
        val video = pickVideoOnly(info.videoOnlyStreams) ?: pickMuxed(info.videoStreams)
        val audio = pickAudio(info.audioStreams)
        if (video != null) {
            return PlayableStream(
                videoUrl = video.content,
                audioUrl = audio?.content,
                kind = if (audio != null) StreamKind.MERGED else StreamKind.PROGRESSIVE,
                title = title,
                uploader = uploader,
                qualityLabel = labelOf(video)
            )
        }

        info.dashMpdUrl?.takeIf { it.isNotBlank() }?.let { dash ->
            return PlayableStream(
                videoUrl = dash,
                kind = StreamKind.DASH,
                title = title,
                uploader = uploader,
                qualityLabel = "DASH"
            )
        }

        throw IllegalStateException(
            "Akış yok (video=${info.videoStreams.size}, " +
                "videoOnly=${info.videoOnlyStreams.size}, audio=${info.audioStreams.size})"
        )
    }

    private fun pickMuxed(streams: List<VideoStream>): VideoStream? =
        streams
            .filter { hasContent(it) }
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || it.isUrl }
            .sortedWith(
                compareByDescending<VideoStream> { it.format == MediaFormat.MPEG_4 }
                    .thenByDescending { compatibleHeight(it.height) }
                    .thenByDescending { it.bitrate }
            )
            .firstOrNull()

    private fun pickVideoOnly(streams: List<VideoStream>): VideoStream? =
        streams
            .filter { hasContent(it) }
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || it.isUrl }
            .sortedWith(
                compareByDescending<VideoStream> { it.format == MediaFormat.MPEG_4 }
                    .thenByDescending { compatibleHeight(it.height) }
                    .thenByDescending { it.bitrate }
            )
            .firstOrNull()

    private fun pickAudio(streams: List<AudioStream>): AudioStream? =
        streams
            .filter { !it.content.isNullOrBlank() }
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || it.isUrl }
            .sortedWith(
                compareByDescending<AudioStream> {
                    it.format == MediaFormat.M4A || it.format == MediaFormat.MPEG_4
                }.thenByDescending { it.averageBitrate }
            )
            .firstOrNull()

    private fun hasContent(stream: VideoStream): Boolean =
        !stream.content.isNullOrBlank()

    /** Prefer <=720p for broader device codec support. */
    private fun compatibleHeight(height: Int): Int =
        when {
            height <= 0 -> 0
            height <= 720 -> height + 1000 // boost 720p and below
            height <= 1080 -> height
            else -> 480
        }

    private fun labelOf(stream: VideoStream): String =
        stream.getResolution().orEmpty().ifBlank { "${stream.height}p" }

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
