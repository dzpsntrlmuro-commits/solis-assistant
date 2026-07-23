package com.temiztube.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.temiztube.app.model.DownloadAssets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object MediaDownloader {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun download(
        context: Context,
        url: String,
        fileName: String,
        mimeType: String
    ): Uri = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.youtube.com")
            .header("Origin", "https://www.youtube.com")
            .header("Accept", "*/*")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("İndirme hatası HTTP ${response.code}")
            }
            val body = response.body
                ?: throw IllegalStateException("Boş yanıt")
            val resolvedMime = body.contentType()?.toString()?.substringBefore(';')?.trim()
                ?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                ?: mimeType

            body.byteStream().use { input ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    writeMediaStore(context, fileName, resolvedMime, input)
                } else {
                    writeLegacyFile(fileName, input)
                }
            }
        }
    }

    private fun writeMediaStore(
        context: Context,
        fileName: String,
        mimeType: String,
        input: java.io.InputStream
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/Murotube"
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Dosya oluşturulamadı")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                input.copyTo(output, bufferSize = DEFAULT_BUFFER_SIZE)
            } ?: throw IllegalStateException("Dosya yazılamadı")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun writeLegacyFile(fileName: String, input: java.io.InputStream): Uri {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Murotube"
        )
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("Klasör oluşturulamadı")
        }
        val file = File(dir, fileName)
        FileOutputStream(file).use { output ->
            input.copyTo(output, bufferSize = DEFAULT_BUFFER_SIZE)
        }
        return Uri.fromFile(file)
    }

    fun sanitizeFileName(title: String, ext: String): String {
        val base = title
            .replace(Regex("""[\\\\/:*?"<>|]"""), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(80)
            .ifBlank { "murotube" }
        val cleanExt = ext.trimStart('.')
        return "$base.$cleanExt"
    }
}

fun DownloadAssets.hasVideo(): Boolean = !videoUrl.isNullOrBlank()
fun DownloadAssets.hasAudio(): Boolean = !audioUrl.isNullOrBlank()
