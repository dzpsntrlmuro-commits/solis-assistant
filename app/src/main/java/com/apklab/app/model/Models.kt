package com.apklab.app.model

data class ApkInfo(
    val label: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val sourcePath: String,
    val sizeBytes: Long,
    val isSystemApp: Boolean = false
)

data class ApkWorkspace(
    val id: String,
    val label: String,
    val packageName: String,
    val sourceApk: String,
    val extractDir: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class DexClassInfo(
    val name: String,
    val dexFile: String
)

data class ExtractedString(
    val value: String,
    val dexFile: String
)

data class FileEntry(
    val relativePath: String,
    val sizeBytes: Long,
    val isDirectory: Boolean
)

data class PluginResult(
    val pluginId: String,
    val pluginName: String,
    val summary: String,
    val details: List<String>,
    val outputFile: String? = null
)

data class RebuildResult(
    val unsignedApk: String,
    val signedApk: String,
    val message: String
)
