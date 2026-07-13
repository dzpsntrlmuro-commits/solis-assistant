package com.solis.assistant.utils

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.*

class AudioRecorder {

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val sampleRate = 16000
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
    ) * 4

    @SuppressLint("MissingPermission")
    fun recordToFile(outputFile: File, durationMs: Long): Boolean {
        return try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) return false

            val buffer = ByteArray(bufferSize)
            val baos = ByteArrayOutputStream()
            audioRecord?.startRecording()
            isRecording = true
            val endTime = System.currentTimeMillis() + durationMs
            while (isRecording && System.currentTimeMillis() < endTime) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) baos.write(buffer, 0, read)
            }
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            isRecording = false
            writeWavFile(outputFile, baos.toByteArray())
            true
        } catch (e: Exception) {
            Log.e("AudioRecorder", e.message ?: "error")
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
        FileOutputStream(file).use { out ->
            val header = ByteArray(44)
            header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
            writeInt(header, 4, totalDataLen)
            header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
            writeInt(header, 16, 16)
            writeShort(header, 20, 1); writeShort(header, 22, 1)
            writeInt(header, 24, sampleRate); writeInt(header, 28, byteRate)
            writeShort(header, 32, 2); writeShort(header, 34, 16)
            header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
            writeInt(header, 40, pcmData.size)
            out.write(header)
            out.write(pcmData)
        }
    }

    private fun writeInt(b: ByteArray, offset: Int, value: Int) {
        b[offset] = (value and 0xFF).toByte()
        b[offset+1] = ((value shr 8) and 0xFF).toByte()
        b[offset+2] = ((value shr 16) and 0xFF).toByte()
        b[offset+3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShort(b: ByteArray, offset: Int, value: Int) {
        b[offset] = (value and 0xFF).toByte()
        b[offset+1] = ((value shr 8) and 0xFF).toByte()
    }
}
