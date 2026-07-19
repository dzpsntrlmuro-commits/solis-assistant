package com.apklab.app.plugin

import com.apklab.app.core.DexInspector
import com.apklab.app.core.ManifestDecoder
import com.apklab.app.model.PluginResult
import java.io.File

class ManifestPlugin : ApkPlugin {
    override val id = "manifest"
    override val name = "Manifest Okuyucu"
    override val description = "AndroidManifest.xml içeriğini ve izinleri çıkarır"

    override fun analyze(context: PluginContext): PluginResult {
        val decoded = ManifestDecoder.decode(context.extractDir)
        val permissions = ManifestDecoder.extractPermissions(context.extractDir)
        val out = File(context.reportDir, "manifest.txt")
        out.writeText(decoded + "\n\n--- İZİNLER ---\n" + permissions.joinToString("\n"))
        return PluginResult(
            pluginId = id,
            pluginName = name,
            summary = "${permissions.size} izin, manifest kaydedildi",
            details = permissions.take(40),
            outputFile = out.absolutePath
        )
    }
}

class ClassesPlugin : ApkPlugin {
    override val id = "classes"
    override val name = "Sınıf Listesi"
    override val description = "DEX içindeki sınıf adlarını listeler"

    override fun analyze(context: PluginContext): PluginResult {
        val (classes, _) = DexInspector.inspect(context.extractDir)
        val out = File(context.reportDir, "classes.txt")
        out.writeText(classes.joinToString("\n") { "${it.name}\t(${it.dexFile})" })
        return PluginResult(
            pluginId = id,
            pluginName = name,
            summary = "${classes.size} sınıf bulundu",
            details = classes.take(80).map { it.name },
            outputFile = out.absolutePath
        )
    }
}

class StringsPlugin : ApkPlugin {
    override val id = "strings"
    override val name = "Metin Çıkarıcı"
    override val description = "DEX içinden okunabilir metinleri çeker"

    override fun analyze(context: PluginContext): PluginResult {
        val (_, strings) = DexInspector.inspect(context.extractDir)
        val useful = strings.filter { it.value.length in 6..240 }.take(2000)
        val out = File(context.reportDir, "strings.txt")
        out.writeText(useful.joinToString("\n") { it.value })
        return PluginResult(
            pluginId = id,
            pluginName = name,
            summary = "${useful.size} metin çıkarıldı",
            details = useful.take(60).map { it.value },
            outputFile = out.absolutePath
        )
    }
}

class AssetsPlugin : ApkPlugin {
    override val id = "assets"
    override val name = "Varlık Tarayıcı"
    override val description = "assets/ ve res/ altındaki dosyaları listeler"

    override fun analyze(context: PluginContext): PluginResult {
        val roots = listOf("assets", "res", "lib", "kotlin", "META-INF")
        val files = mutableListOf<String>()
        roots.forEach { root ->
            val dir = File(context.extractDir, root)
            if (dir.exists()) {
                dir.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.absolutePath.removePrefix(context.extractDir.absolutePath).trimStart('/')
                    files += "$rel (${f.length()} B)"
                }
            }
        }
        val out = File(context.reportDir, "assets.txt")
        out.writeText(files.joinToString("\n"))
        return PluginResult(
            pluginId = id,
            pluginName = name,
            summary = "${files.size} kaynak dosyası",
            details = files.take(80),
            outputFile = out.absolutePath
        )
    }
}

class ExportCodePlugin : ApkPlugin {
    override val id = "export_code"
    override val name = "Kod Paketi"
    override val description = "Sınıf ve metinleri tek bir kod özeti dosyasında birleştirir"

    override fun analyze(context: PluginContext): PluginResult {
        val (classes, strings) = DexInspector.inspect(context.extractDir)
        val manifest = ManifestDecoder.decode(context.extractDir)
        val out = File(context.reportDir, "code_export.txt")
        out.writeText(
            buildString {
                appendLine("# ApkLab Kod Özeti")
                appendLine("Paket: ${context.packageName}")
                appendLine("Uygulama: ${context.label}")
                appendLine()
                appendLine("## Manifest")
                appendLine(manifest.take(20_000))
                appendLine()
                appendLine("## Sınıflar (${classes.size})")
                classes.take(2000).forEach { appendLine(it.name) }
                appendLine()
                appendLine("## Metinler (${strings.size})")
                strings.take(1500).forEach { appendLine(it.value) }
            }
        )
        return PluginResult(
            pluginId = id,
            pluginName = name,
            summary = "Kod özeti dışa aktarıldı (${out.length()} B)",
            details = listOf(out.absolutePath),
            outputFile = out.absolutePath
        )
    }
}
