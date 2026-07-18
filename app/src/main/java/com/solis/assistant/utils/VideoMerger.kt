package com.solis.assistant.utils

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Birden fazla videoyu sırayla tek bir MP4 dosyasında birleştirir.
 * Yeniden kodlama yapmaz (remux); aynı codec/çözünürlükteki klipler için uygundur.
 */
object VideoMerger {

    data class Result(
        val outputFile: File,
        val durationUs: Long
    )

    suspend fun merge(
        context: Context,
        uris: List<Uri>,
        outputFile: File,
        onProgress: (Float) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        require(uris.size >= 2) { "En az 2 video seçilmelidir" }

        if (outputFile.exists()) outputFile.delete()

        var muxer: MediaMuxer? = null
        var videoOutTrack = -1
        var audioOutTrack = -1
        var timeOffsetUs = 0L

        try {
            uris.forEachIndexed { index, uri ->
                val extractor = MediaExtractor()
                try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        extractor.setDataSource(pfd.fileDescriptor)
                    } ?: throw IllegalStateException("${index + 1}. video açılamadı")

                    val videoTrack = findTrack(extractor, "video/")
                        ?: throw IllegalStateException("${index + 1}. dosyada video parçası yok")
                    val audioTrack = findTrack(extractor, "audio/")

                    if (muxer == null) {
                        muxer = MediaMuxer(
                            outputFile.absolutePath,
                            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                        )
                        videoOutTrack = muxer!!.addTrack(extractor.getTrackFormat(videoTrack))
                        if (audioTrack != null) {
                            audioOutTrack = muxer!!.addTrack(extractor.getTrackFormat(audioTrack))
                        }
                        muxer!!.start()
                    }

                    val videoDuration = copyTrack(
                        extractor = extractor,
                        srcTrack = videoTrack,
                        muxer = muxer!!,
                        dstTrack = videoOutTrack,
                        timeOffsetUs = timeOffsetUs
                    )

                    val audioDuration = if (audioTrack != null && audioOutTrack >= 0) {
                        copyTrack(
                            extractor = extractor,
                            srcTrack = audioTrack,
                            muxer = muxer!!,
                            dstTrack = audioOutTrack,
                            timeOffsetUs = timeOffsetUs
                        )
                    } else {
                        0L
                    }

                    val formatDuration = extractor.getTrackFormat(videoTrack)
                        .takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
                        ?.getLong(MediaFormat.KEY_DURATION)
                        ?: 0L

                    val clipDuration = maxOf(videoDuration, audioDuration, formatDuration)
                    timeOffsetUs += clipDuration + 10_000L
                    onProgress((index + 1f) / uris.size)
                } finally {
                    extractor.release()
                }
            }

            muxer?.stop()
            muxer?.release()
            muxer = null

            if (!outputFile.exists() || outputFile.length() == 0L) {
                throw IllegalStateException("Çıktı dosyası oluşturulamadı. Videoların formatı uyumlu olmayabilir.")
            }

            Result(outputFile = outputFile, durationUs = timeOffsetUs)
        } catch (e: Exception) {
            muxer?.runCatching {
                stop()
                release()
            }
            if (outputFile.exists()) outputFile.delete()
            throw e
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return null
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        srcTrack: Int,
        muxer: MediaMuxer,
        dstTrack: Int,
        timeOffsetUs: Long
    ): Long {
        extractor.selectTrack(srcTrack)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val format = extractor.getTrackFormat(srcTrack)
        val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(256 * 1024)
        } else {
            2 * 1024 * 1024
        }
        val buffer = ByteBuffer.allocate(maxSize)
        val info = MediaCodec.BufferInfo()
        var lastRelativePts = 0L

        while (true) {
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break

            val sampleTime = extractor.sampleTime
            if (sampleTime < 0) break

            info.offset = 0
            info.size = sampleSize
            info.presentationTimeUs = sampleTime + timeOffsetUs
            info.flags = extractor.sampleFlags
            lastRelativePts = sampleTime
            muxer.writeSampleData(dstTrack, buffer, info)
            extractor.advance()
        }

        extractor.unselectTrack(srcTrack)
        return lastRelativePts
    }
}
