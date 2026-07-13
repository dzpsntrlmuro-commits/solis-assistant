package com.solis.assistant.utils

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.io.*

class AudioRecorder {

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 4

    // Ses kaydet ve WAV dosyasına yaz
    fun recordToFile(outputFile: File, durationMs: Long): Boolean {
        return try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioRecorder", "AudioRecord başlatılamadı")
                return false
            }

            val buffer = ByteArray(bufferSize)
            val baos = ByteArrayOutputStream()

            audioRecord?.startRecording()
            isRecording = true

            val endTime = System.currentTimeMillis() + durationMs

            while (isRecording && System.currentTimeMillis() < endTime) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    baos.write(buffer, 0, read)
                }
            }

            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            isRecording = false

            // WAV dosyasına yaz
            writeWavFile(outputFile, baos.toByteArray())
            true
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Kayıt hatası: ${e.message}")
            false
        }
    }

    fun stop() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun writeWavFile(file: File, pcmData: ByteArray) {
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * 2

        val header = ByteArray(44)
        // RIFF header
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        // File size
        putInt(header, 4, totalDataLen)
        // WAVE
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        // fmt chunk
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        putInt(header, 16, 16) // chunk size
        putShort(header, 20, 1) // PCM format
        putShort(header, 22, 1) // mono
        putInt(header, 24, sampleRate)
        putInt(header, 28, byteRate)
        putShort(header, 32, 2) // block align
        putShort(header, 34, 16) // bits per sample
        // data chunk
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        putInt(header, 40, pcmData.size)

        FileOutputStream(file).use { fos ->
            fos.write(header)
            fos.write(pcmData)
        }
    }

    private fun putInt(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xff).toByte()
        buffer[offset + 1] = (value shr 8 and 0xff).toByte()
        buffer[offset + 2] = (value shr 16 and 0xff).toByte()
        buffer[offset + 3] = (value shr 24 and 0xff).toByte()
    }

    private fun putShort(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xff).toByte()
        buffer[offset + 1] = (value shr 8 and 0xff).toByte()
    }
}
