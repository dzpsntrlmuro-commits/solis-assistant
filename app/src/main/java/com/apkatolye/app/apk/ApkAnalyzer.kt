package com.apkatolye.app.apk

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.util.zip.ZipFile

data class ApkInfo(
    val path: String,
    val fileName: String,
    val sizeBytes: Long,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val permissions: List<String>,
    val activities: List<String>,
    val services: List<String>,
    val receivers: List<String>,
    val providers: List<String>,
    val entries: List<ApkEntry>,
    val dexFiles: List<String>
)

data class ApkEntry(
    val name: String,
    val size: Long,
    val compressedSize: Long
)

object ApkAnalyzer {

    fun analyze(context: Context, apkFile: File): ApkInfo {
        require(apkFile.exists()) { "APK bulunamadı" }

        val pm = context.packageManager
        val pkgInfo = pm.getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.GET_PERMISSIONS or
                PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS
        )

        pkgInfo?.applicationInfo?.apply {
            sourceDir = apkFile.absolutePath
            publicSourceDir = apkFile.absolutePath
        }

        val entries = mutableListOf<ApkEntry>()
        val dexFiles = mutableListOf<String>()

        ZipFile(apkFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                if (!entry.isDirectory) {
                    entries += ApkEntry(
                        name = entry.name,
                        size = entry.size,
                        compressedSize = entry.compressedSize
                    )
                    if (entry.name.endsWith(".dex", ignoreCase = true)) {
                        dexFiles += entry.name
                    }
                }
            }
        }

        val versionCode = pkgInfo?.longVersionCode

        return ApkInfo(
            path = apkFile.absolutePath,
            fileName = apkFile.name,
            sizeBytes = apkFile.length(),
            packageName = pkgInfo?.packageName,
            versionName = pkgInfo?.versionName,
            versionCode = versionCode,
            permissions = pkgInfo?.requestedPermissions?.toList().orEmpty().sorted(),
            activities = pkgInfo?.activities?.mapNotNull { it.name }.orEmpty().sorted(),
            services = pkgInfo?.services?.mapNotNull { it.name }.orEmpty().sorted(),
            receivers = pkgInfo?.receivers?.mapNotNull { it.name }.orEmpty().sorted(),
            providers = pkgInfo?.providers?.mapNotNull { it.name }.orEmpty().sorted(),
            entries = entries.sortedBy { it.name },
            dexFiles = dexFiles.sorted()
        )
    }

    fun formatReport(info: ApkInfo): String = buildString {
        appendLine("=== APK Atölye Analiz Raporu ===")
        appendLine()
        appendLine("Dosya      : ${info.fileName}")
        appendLine("Boyut      : ${formatSize(info.sizeBytes)}")
        appendLine("Paket      : ${info.packageName ?: "?"}")
        appendLine("Sürüm      : ${info.versionName ?: "?"} (${info.versionCode ?: "?"})")
        appendLine("DEX sayısı : ${info.dexFiles.size}")
        appendLine("Dosya sayısı: ${info.entries.size}")
        appendLine()

        appendLine("--- DEX dosyaları ---")
        info.dexFiles.forEach { appendLine("  $it") }
        appendLine()

        appendLine("--- İzinler (${info.permissions.size}) ---")
        if (info.permissions.isEmpty()) appendLine("  (yok)")
        else info.permissions.forEach { appendLine("  $it") }
        appendLine()

        appendLine("--- Activities (${info.activities.size}) ---")
        info.activities.take(40).forEach { appendLine("  $it") }
        if (info.activities.size > 40) appendLine("  … +${info.activities.size - 40} daha")
        appendLine()

        appendLine("--- Services (${info.services.size}) ---")
        info.services.take(30).forEach { appendLine("  $it") }
        if (info.services.size > 30) appendLine("  … +${info.services.size - 30} daha")
        appendLine()

        appendLine("--- Receivers (${info.receivers.size}) ---")
        info.receivers.take(30).forEach { appendLine("  $it") }
        if (info.receivers.size > 30) appendLine("  … +${info.receivers.size - 30} daha")
        appendLine()

        appendLine("--- Providers (${info.providers.size}) ---")
        info.providers.take(20).forEach { appendLine("  $it") }
        if (info.providers.size > 20) appendLine("  … +${info.providers.size - 20} daha")
        appendLine()

        appendLine("--- En büyük dosyalar ---")
        info.entries.sortedByDescending { it.size }.take(15).forEach {
            appendLine("  ${formatSize(it.size).padEnd(10)} ${it.name}")
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        return "%.2f MB".format(mb)
    }
}
