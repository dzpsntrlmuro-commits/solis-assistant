package com.solis.assistant.utils

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

object VideoMerger {

    private const val TAG = "VideoMerger"

    fun merge(context: Context, inputUris: List<Uri>, outputFile: File): Boolean {
        if (inputUris.size < 2) return false
        return try {
            val tempFiles = inputUris.mapIndexed { index, uri ->
                copyUriToCache(context, uri, index)
            }
            mergeFiles(tempFiles, outputFile).also {
                tempFiles.forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.e(TAG, e.message ?: "merge failed")
            false
        }
    }

    fun saveToGallery(context: Context, sourceFile: File, displayName: String): Uri? {
        val values = android.content.ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SolisAsistan")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            sourceFile.inputStream().use { it.copyTo(out) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }

    private fun copyUriToCache(context: Context, uri: Uri, index: Int): File {
        val file = File(context.cacheDir, "merge_input_$index.mp4")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Dosya okunamadı")
        return file
    }

    private fun mergeFiles(inputs: List<File>, output: File): Boolean {
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrack = -1
        var audioTrack = -1
        var offsetUs = 0L
        var muxerStarted = false

        try {
            for (file in inputs) {
                val extractor = MediaExtractor()
                extractor.setDataSource(file.absolutePath)

                val trackMap = IntArray(extractor.trackCount) { -1 }
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    when {
                        mime.startsWith("video/") -> {
                            if (videoTrack == -1) videoTrack = muxer.addTrack(format)
                            trackMap[i] = videoTrack
                        }
                        mime.startsWith("audio/") -> {
                            if (audioTrack == -1) audioTrack = muxer.addTrack(format)
                            trackMap[i] = audioTrack
                        }
                    }
                }

                if (!muxerStarted) {
                    muxer.start()
                    muxerStarted = true
                }

                val buffer = ByteBuffer.allocate(1024 * 1024)
                val info = MediaCodec.BufferInfo()

                for (i in 0 until extractor.trackCount) {
                    val outTrack = trackMap[i]
                    if (outTrack < 0) continue
                    extractor.selectTrack(i)
                    while (true) {
                        info.offset = 0
                        info.size = extractor.readSampleData(buffer, 0)
                        if (info.size < 0) break
                        info.presentationTimeUs = extractor.sampleTime + offsetUs
                        info.flags = extractor.sampleFlags
                        muxer.writeSampleData(outTrack, buffer, info)
                        extractor.advance()
                    }
                    extractor.unselectTrack(i)
                }

                val durationUs = getDurationUs(file)
                offsetUs += durationUs
                extractor.release()
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, e.message ?: "mux error")
            output.delete()
            return false
        } finally {
            try {
                if (muxerStarted) muxer.stop()
            } catch (_: Exception) { }
            muxer.release()
        }
    }

    private fun getDurationUs(file: File): Long {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        var maxDuration = 0L
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                maxDuration = maxOf(maxDuration, format.getLong(MediaFormat.KEY_DURATION))
            }
        }
        extractor.release()
        return maxDuration
    }
}
