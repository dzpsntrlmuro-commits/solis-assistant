package com.apkatolye.app.apk

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

object ApkExtractor {

    data class Result(
        val outputDir: File,
        val fileCount: Int,
        val totalBytes: Long
    )

    fun extract(apkFile: File, outputDir: File): Result {
        if (outputDir.exists()) {
            outputDir.deleteRecursively()
        }
        outputDir.mkdirs()

        var count = 0
        var total = 0L

        ZipFile(apkFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val outFile = File(outputDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                    return@forEach
                }
                outFile.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    BufferedInputStream(input).use { buffered ->
                        FileOutputStream(outFile).use { output ->
                            buffered.copyTo(output)
                        }
                    }
                }
                count++
                total += entry.size.coerceAtLeast(0)
            }
        }

        return Result(outputDir = outputDir, fileCount = count, totalBytes = total)
    }
}
