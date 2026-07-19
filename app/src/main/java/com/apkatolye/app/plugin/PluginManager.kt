package com.apkatolye.app.plugin

import com.apkatolye.app.apk.ApkInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PluginDefinition(
    val id: String,
    val name: String,
    val description: String,
    val type: String,
    val patterns: List<String> = emptyList(),
    val extensions: List<String> = emptyList()
)

data class PluginResult(
    val pluginId: String,
    val pluginName: String,
    val findings: List<String>
)

interface AnalysisPlugin {
    val definition: PluginDefinition
    fun run(info: ApkInfo, extractedDir: File?): PluginResult
}

object BuiltinPlugins {

    val all: List<AnalysisPlugin> = listOf(
        PermissionAuditPlugin(),
        NativeLibPlugin(),
        LargeAssetPlugin(),
        PackageSurfacePlugin(),
        DexInventoryPlugin()
    )
}

class PermissionAuditPlugin : AnalysisPlugin {
    override val definition = PluginDefinition(
        id = "permission_audit",
        name = "İzin denetimi",
        description = "Hassas izinleri listeler",
        type = "permissions",
        patterns = listOf(
            "CAMERA", "RECORD_AUDIO", "READ_CONTACTS", "ACCESS_FINE_LOCATION",
            "READ_SMS", "RECEIVE_SMS", "READ_CALL_LOG", "WRITE_EXTERNAL_STORAGE",
            "QUERY_ALL_PACKAGES", "SYSTEM_ALERT_WINDOW"
        )
    )

    override fun run(info: ApkInfo, extractedDir: File?): PluginResult {
        val hits = info.permissions.filter { perm ->
            definition.patterns.any { perm.contains(it, ignoreCase = true) }
        }
        return PluginResult(
            pluginId = definition.id,
            pluginName = definition.name,
            findings = if (hits.isEmpty()) listOf("Hassas izin bulunamadı") else hits
        )
    }
}

class NativeLibPlugin : AnalysisPlugin {
    override val definition = PluginDefinition(
        id = "native_libs",
        name = "Native kütüphaneler",
        description = ".so dosyalarını listeler",
        type = "files",
        extensions = listOf(".so")
    )

    override fun run(info: ApkInfo, extractedDir: File?): PluginResult {
        val libs = info.entries.filter { it.name.endsWith(".so", ignoreCase = true) }
            .map { "${it.name} (${format(it.size)})" }
        return PluginResult(
            pluginId = definition.id,
            pluginName = definition.name,
            findings = if (libs.isEmpty()) listOf("Native kütüphane yok") else libs.take(80)
        )
    }

    private fun format(bytes: Long): String {
        val kb = bytes / 1024.0
        return if (kb < 1024) "%.0f KB".format(kb) else "%.1f MB".format(kb / 1024.0)
    }
}

class LargeAssetPlugin : AnalysisPlugin {
    override val definition = PluginDefinition(
        id = "large_assets",
        name = "Büyük varlıklar",
        description = "1 MB üzeri dosyaları gösterir",
        type = "files"
    )

    override fun run(info: ApkInfo, extractedDir: File?): PluginResult {
        val large = info.entries
            .filter { it.size >= 1_000_000 }
            .sortedByDescending { it.size }
            .map { "%.1f MB  %s".format(it.size / 1_000_000.0, it.name) }
        return PluginResult(
            pluginId = definition.id,
            pluginName = definition.name,
            findings = if (large.isEmpty()) listOf("1 MB üzeri dosya yok") else large.take(40)
        )
    }
}

class PackageSurfacePlugin : AnalysisPlugin {
    override val definition = PluginDefinition(
        id = "package_surface",
        name = "Uygulama yüzeyi",
        description = "Activity / Service / Receiver özeti",
        type = "components"
    )

    override fun run(info: ApkInfo, extractedDir: File?): PluginResult {
        return PluginResult(
            pluginId = definition.id,
            pluginName = definition.name,
            findings = listOf(
                "Paket: ${info.packageName ?: "?"}",
                "Activities: ${info.activities.size}",
                "Services: ${info.services.size}",
                "Receivers: ${info.receivers.size}",
                "Providers: ${info.providers.size}",
                "İzinler: ${info.permissions.size}"
            )
        )
    }
}

class DexInventoryPlugin : AnalysisPlugin {
    override val definition = PluginDefinition(
        id = "dex_inventory",
        name = "DEX envanteri",
        description = "DEX dosya envanteri",
        type = "dex"
    )

    override fun run(info: ApkInfo, extractedDir: File?): PluginResult {
        val findings = info.dexFiles.map { name ->
            val size = info.entries.firstOrNull { it.name == name }?.size ?: 0L
            "$name (${"%.1f MB".format(size / 1_000_000.0)})"
        }
        return PluginResult(
            pluginId = definition.id,
            pluginName = definition.name,
            findings = if (findings.isEmpty()) listOf("DEX yok") else findings
        )
    }
}

object PluginManager {

    fun loadJsonPlugins(pluginDir: File): List<AnalysisPlugin> {
        if (!pluginDir.exists()) return emptyList()
        return pluginDir.listFiles { f -> f.extension.equals("json", true) }
            ?.mapNotNull { parseJsonPlugin(it) }
            .orEmpty()
    }

    private fun parseJsonPlugin(file: File): AnalysisPlugin? {
        return try {
            val obj = JSONObject(file.readText())
            val def = PluginDefinition(
                id = obj.getString("id"),
                name = obj.getString("name"),
                description = obj.optString("description", ""),
                type = obj.optString("type", "files"),
                patterns = obj.optJSONArray("patterns")?.toStringList().orEmpty(),
                extensions = obj.optJSONArray("extensions")?.toStringList().orEmpty()
            )
            JsonPatternPlugin(def)
        } catch (_: Exception) {
            null
        }
    }

    fun allPlugins(extraDir: File): List<AnalysisPlugin> =
        BuiltinPlugins.all + loadJsonPlugins(extraDir)

    fun runAll(plugins: List<AnalysisPlugin>, info: ApkInfo, extractedDir: File?): List<PluginResult> =
        plugins.map { it.run(info, extractedDir) }

    fun formatReport(results: List<PluginResult>): String = buildString {
        appendLine("=== Eklenti Raporu ===")
        appendLine()
        results.forEach { result ->
            appendLine("## ${result.pluginName} (${result.pluginId})")
            result.findings.forEach { appendLine("  - $it") }
            appendLine()
        }
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { getString(it) }
}

class JsonPatternPlugin(override val definition: PluginDefinition) : AnalysisPlugin {
    override fun run(info: ApkInfo, extractedDir: File?): PluginResult {
        val findings = when (definition.type) {
            "permissions" -> info.permissions.filter { perm ->
                definition.patterns.any { perm.contains(it, ignoreCase = true) }
            }
            "components" -> {
                val all = info.activities + info.services + info.receivers + info.providers
                all.filter { name -> definition.patterns.any { name.contains(it, ignoreCase = true) } }
            }
            else -> info.entries.map { it.name }.filter { name ->
                val byExt = definition.extensions.isNotEmpty() &&
                    definition.extensions.any { name.endsWith(it, ignoreCase = true) }
                val byPat = definition.patterns.isNotEmpty() &&
                    definition.patterns.any { name.contains(it, ignoreCase = true) }
                byExt || byPat
            }
        }
        return PluginResult(
            pluginId = definition.id,
            pluginName = definition.name,
            findings = if (findings.isEmpty()) listOf("Eşleşme yok") else findings.take(100)
        )
    }
}
