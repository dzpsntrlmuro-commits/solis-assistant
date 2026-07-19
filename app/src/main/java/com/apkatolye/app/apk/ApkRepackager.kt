package com.apkatolye.app.apk

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Repackages an extracted APK directory into an unsigned APK (ZIP with STORED/DEFLATED entries).
 * Output is NOT installable until signed with your own keystore.
 */
object ApkRepackager {

    data class Result(
        val outputApk: File,
        val fileCount: Int,
        val sizeBytes: Long
    )

    fun rebuild(extractedDir: File, outputApk: File): Result {
        require(extractedDir.isDirectory) { "Çıkarılmış klasör bulunamadı. Önce içeriği çıkarın." }

        outputApk.parentFile?.mkdirs()
        if (outputApk.exists()) outputApk.delete()

        var count = 0
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputApk))).use { zos ->
            // Prefer no recompression surprises for already-aligned resources: use DEFLATED for all.
            extractedDir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relative = file.relativeTo(extractedDir).invariantSeparatorsPath
                    // Skip existing signatures — rebuild is unsigned by design
                    if (relative.startsWith("META-INF/", ignoreCase = true)) return@forEach

                    val entry = ZipEntry(relative)
                    zos.putNextEntry(entry)
                    FileInputStream(file).use { input -> input.copyTo(zos) }
                    zos.closeEntry()
                    count++
                }
        }

        return Result(
            outputApk = outputApk,
            fileCount = count,
            sizeBytes = outputApk.length()
        )
    }
}
