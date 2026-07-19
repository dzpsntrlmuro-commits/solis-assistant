package com.apklab.app.core

import android.content.Context
import com.apklab.app.model.ApkWorkspace
import com.apklab.app.model.FileEntry
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

object ApkExtractor {

    fun workspaceRoot(context: Context): File =
        File(context.filesDir, "workspace").also { it.mkdirs() }

    fun outputRoot(context: Context): File =
        File(context.filesDir, "output").also { it.mkdirs() }

    fun extract(
        context: Context,
        apkFile: File,
        label: String,
        packageName: String,
        onProgress: ((String) -> Unit)? = null
    ): ApkWorkspace {
        require(apkFile.exists()) { "APK bulunamadı: ${apkFile.absolutePath}" }

        val id = UUID.randomUUID().toString().take(8)
        val target = File(workspaceRoot(context), id)
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()

        onProgress?.invoke("ZIP açılıyor…")
        unzip(apkFile, target)

        onProgress?.invoke("Dosyalar yazılıyor…")
        File(target, ".apklab.json").writeText(
            """
            {
              "id":"$id",
              "label":${jsonString(label)},
              "packageName":${jsonString(packageName)},
              "sourceApk":${jsonString(apkFile.absolutePath)},
              "extractDir":${jsonString(target.absolutePath)}
            }
            """.trimIndent()
        )

        return ApkWorkspace(
            id = id,
            label = label,
            packageName = packageName,
            sourceApk = apkFile.absolutePath,
            extractDir = target.absolutePath
        )
    }

    fun copyUriToCache(context: Context, input: java.io.InputStream, name: String): File {
        val out = File(context.cacheDir, name.ifBlank { "import.apk" })
        input.use { src ->
            FileOutputStream(out).use { dst -> src.copyTo(dst) }
        }
        return out
    }

    fun listFiles(extractDir: File): List<FileEntry> {
        if (!extractDir.exists()) return emptyList()
        val rootPath = extractDir.absolutePath
        return extractDir.walkTopDown()
            .filter { it.absolutePath != rootPath }
            .map {
                FileEntry(
                    relativePath = it.absolutePath.removePrefix(rootPath).trimStart('/'),
                    sizeBytes = if (it.isFile) it.length() else 0L,
                    isDirectory = it.isDirectory
                )
            }
            .sortedBy { it.relativePath.lowercase() }
            .toList()
    }

    private fun unzip(apk: File, target: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(apk))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(target, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
