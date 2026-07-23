package com.temiztube.app.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.temiztube.app.model.DownloadAssets

object MediaDownloader {

    fun enqueue(
        context: Context,
        url: String,
        fileName: String,
        mimeType: String,
        notificationTitle: String
    ): Long {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(notificationTitle)
            .setDescription(fileName)
            .setMimeType(mimeType)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Murotube/$fileName")
            .addRequestHeader(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"
            )
            .addRequestHeader("Referer", "https://www.youtube.com")
            .addRequestHeader("Origin", "https://www.youtube.com")

        return dm.enqueue(request)
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
