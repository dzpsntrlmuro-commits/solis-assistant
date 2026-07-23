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
import java.io.InputStream
import java.util.concurrent.TimeUnit

object MediaDownloader {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    data class Result(
        val localFile: File,
        val publicUri: Uri?
    )

    suspend fun download(
        context: Context,
        url: String,
        fileName: String,
        mimeType: String,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
    ): Result = withContext(Dispatchers.IO) {
        val candidates = linkedSetOf(url)
        var lastError: Exception? = null

        for (candidate in candidates) {
            try {
                return@withContext downloadOnce(context, candidate, fileName, mimeType, onProgress)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("İndirme başarısız")
    }

    private fun downloadOnce(
        context: Context,
        url: String,
        fileName: String,
        mimeType: String,
        onProgress: ((downloaded: Long, total: Long) -> Unit)?
    ): Result {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.youtube.com/")
            .header("Origin", "https://www.youtube.com")
            .header("Accept", "*/*")
            .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("Boş yanıt")
            val total = body.contentLength()
            val resolvedMime = body.contentType()?.toString()?.substringBefore(';')?.trim()
                ?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                ?: mimeType

            val dir = appDownloadDir(context)
            if (!dir.exists() && !dir.mkdirs()) {
                throw IllegalStateException("Klasör oluşturulamadı")
            }
            val localFile = uniqueFile(dir, fileName)

            body.byteStream().use { input ->
                FileOutputStream(localFile).use { output ->
                    copyWithProgress(input, output, total, onProgress)
                }
            }

            if (localFile.length() <= 0L) {
                localFile.delete()
                throw IllegalStateException("Dosya boş geldi")
            }

            val publicUri = runCatching {
                copyToPublicDownloads(context, localFile, localFile.name, resolvedMime)
            }.getOrNull()

            return Result(localFile = localFile, publicUri = publicUri)
        }
    }

    private fun copyWithProgress(
        input: InputStream,
        output: FileOutputStream,
        total: Long,
        onProgress: ((downloaded: Long, total: Long) -> Unit)?
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var downloaded = 0L
        var lastEmit = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            downloaded += read
            if (onProgress != null && (downloaded - lastEmit >= 256 * 1024 || downloaded == total)) {
                lastEmit = downloaded
                onProgress(downloaded, total)
            }
        }
        onProgress?.invoke(downloaded, total)
        output.flush()
    }

    private fun copyToPublicDownloads(
        context: Context,
        source: File,
        fileName: String,
        mimeType: String
    ): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
                ?: throw IllegalStateException("MediaStore oluşturulamadı")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { it.copyTo(out) }
                } ?: throw IllegalStateException("MediaStore yazılamadı")
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
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Murotube"
        )
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("Genel klasör oluşturulamadı")
        }
        val dest = uniqueFile(dir, fileName)
        source.copyTo(dest, overwrite = true)
        return Uri.fromFile(dest)
    }

    fun appDownloadDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        return File(base, "Murotube")
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

    private fun uniqueFile(dir: File, fileName: String): File {
        val clean = fileName.replace(Regex("""[\\\\/:*?"<>|]"""), "_")
        val dot = clean.lastIndexOf('.')
        val name = if (dot > 0) clean.substring(0, dot) else clean
        val ext = if (dot > 0) clean.substring(dot) else ""
        var file = File(dir, "$name$ext")
        var i = 1
        while (file.exists()) {
            file = File(dir, "$name ($i)$ext")
            i++
        }
        return file
    }
}

fun DownloadAssets.hasVideo(): Boolean = !videoUrl.isNullOrBlank()
fun DownloadAssets.hasAudio(): Boolean = !audioUrl.isNullOrBlank()
