package com.apkatolye.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.apkatolye.app.apk.ApkAnalyzer
import com.apkatolye.app.apk.WorkspacePaths
import com.apkatolye.app.databinding.ActivityPluginsBinding
import com.apkatolye.app.plugin.PluginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PluginsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPluginsBinding
    private lateinit var pluginDir: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPluginsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }

        pluginDir = File(WorkspacePaths.root(this), "plugins").also { it.mkdirs() }
        seedAssetPlugins()

        val plugins = PluginManager.allPlugins(pluginDir)
        binding.pluginList.text = buildString {
            appendLine("Yüklü eklentiler (${plugins.size})")
            appendLine()
            plugins.forEach {
                appendLine("• ${it.definition.name}")
                appendLine("  ${it.definition.description}")
                appendLine("  id: ${it.definition.id}")
                appendLine()
            }
            appendLine("Özel eklenti eklemek için JSON dosyasını şu klasöre koyun:")
            appendLine(pluginDir.absolutePath)
        }

        binding.btnRun.setOnClickListener { runPlugins() }
    }

    private fun seedAssetPlugins() {
        try {
            assets.list("plugins")?.forEach { name ->
                val dest = File(pluginDir, name)
                if (!dest.exists()) {
                    assets.open("plugins/$name").use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        } catch (_: Exception) {
            // optional assets
        }
    }

    private fun runPlugins() {
        binding.report.text = "Eklentiler çalışıyor…"
        binding.btnRun.isEnabled = false
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    val info = ApkAnalyzer.analyze(
                        this@PluginsActivity,
                        WorkspacePaths.selectedApk(this@PluginsActivity)
                    )
                    val extract = WorkspacePaths.extractDir(this@PluginsActivity)
                        .takeIf { it.exists() }
                    val plugins = PluginManager.allPlugins(pluginDir)
                    val results = PluginManager.runAll(plugins, info, extract)
                    val report = PluginManager.formatReport(results)
                    WorkspacePaths.reportFile(this@PluginsActivity, "plugins.txt").writeText(report)
                    report
                }
                binding.report.text = text
            } catch (e: Exception) {
                binding.report.text = "Hata: ${e.message}"
            } finally {
                binding.btnRun.isEnabled = true
            }
        }
    }
}
