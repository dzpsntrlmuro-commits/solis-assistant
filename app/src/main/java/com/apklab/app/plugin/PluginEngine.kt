package com.apklab.app.plugin

import android.content.Context
import com.apklab.app.model.PluginResult
import org.json.JSONObject
import java.io.File

/**
 * Discovers built-in plugins and optional external plugin descriptors
 * placed under files/plugins as *.plugin.json files.
 *
 * External descriptor format example:
 * id, name, description, type=report_template, include=[classes,strings,permissions]
 */
object PluginEngine {

    fun builtin(): List<ApkPlugin> = listOf(
        ManifestPlugin(),
        ClassesPlugin(),
        StringsPlugin(),
        AssetsPlugin(),
        ExportCodePlugin()
    )

    fun pluginsDir(context: Context): File =
        File(context.filesDir, "plugins").also { it.mkdirs() }

    fun allPlugins(context: Context): List<ApkPlugin> {
        val external = pluginsDir(context).listFiles()
            ?.filter { it.name.endsWith(".plugin.json") }
            ?.mapNotNull { loadDescriptor(it) }
            .orEmpty()
        return builtin() + external
    }

    fun runAll(
        context: Context,
        extractDir: File,
        packageName: String,
        label: String,
        enabledIds: Set<String>? = null
    ): List<PluginResult> {
        val reportDir = File(extractDir, "_apklab_reports").also {
            if (it.exists()) it.deleteRecursively()
            it.mkdirs()
        }
        val pluginContext = PluginContext(
            extractDir = extractDir,
            packageName = packageName,
            label = label,
            reportDir = reportDir
        )
        return allPlugins(context)
            .filter { enabledIds == null || it.id in enabledIds }
            .map { plugin ->
                try {
                    plugin.analyze(pluginContext)
                } catch (e: Exception) {
                    PluginResult(
                        pluginId = plugin.id,
                        pluginName = plugin.name,
                        summary = "Hata: ${e.message}",
                        details = emptyList()
                    )
                }
            }
    }

    fun installSampleExternalPlugin(context: Context) {
        val sample = File(pluginsDir(context), "url_hunt.plugin.json")
        if (sample.exists()) return
        sample.writeText(
            """
            {
              "id": "url_hunt",
              "name": "URL Avcısı",
              "description": "Metinlerden http/https adreslerini süzgeçler",
              "type": "report_template",
              "include": ["strings"],
              "filter": "https?://"
            }
            """.trimIndent()
        )
    }

    private fun loadDescriptor(file: File): ApkPlugin? {
        return try {
            val json = JSONObject(file.readText())
            DescriptorPlugin(json)
        } catch (_: Exception) {
            null
        }
    }
}

private class DescriptorPlugin(private val json: JSONObject) : ApkPlugin {
    override val id: String = json.getString("id")
    override val name: String = json.optString("name", id)
    override val description: String = json.optString("description", "")

    override fun analyze(context: PluginContext): PluginResult {
        val filter = json.optString("filter", "")
        val include = json.optJSONArray("include")
        val wantsStrings = include == null || (0 until include.length()).any {
            include.getString(it) == "strings"
        }
        val details = mutableListOf<String>()
        if (wantsStrings) {
            val (_, strings) = com.apklab.app.core.DexInspector.inspect(context.extractDir)
            val filtered = if (filter.isBlank()) {
                strings.map { it.value }
            } else {
                strings.map { it.value }.filter { it.contains(Regex(filter)) }
            }
            details += filtered.take(200)
        }
        if (include != null) {
            for (i in 0 until include.length()) {
                when (include.getString(i)) {
                    "permissions" -> details += com.apklab.app.core.ManifestDecoder
                        .extractPermissions(context.extractDir)
                    "classes" -> {
                        val (classes, _) = com.apklab.app.core.DexInspector.inspect(context.extractDir)
                        details += classes.take(100).map { it.name }
                    }
                }
            }
        }
        val out = File(context.reportDir, "$id.txt")
        out.writeText(details.joinToString("\n"))
        return PluginResult(
            pluginId = id,
            pluginName = name,
            summary = "${details.size} satır üretildi",
            details = details.take(50),
            outputFile = out.absolutePath
        )
    }
}
