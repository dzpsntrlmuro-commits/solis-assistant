package com.apkatolye.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.apkatolye.app.apk.ApkAnalyzer
import com.apkatolye.app.apk.ApkExtractor
import com.apkatolye.app.apk.ApkRepackager
import com.apkatolye.app.apk.WorkspacePaths
import com.apkatolye.app.databinding.ActivityRebuildBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RebuildActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRebuildBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRebuildBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val extractDir = WorkspacePaths.extractDir(this)
        binding.report.text = if (extractDir.exists()) {
            "Çıkarılmış klasör hazır:\n${extractDir.absolutePath}\n\nYeniden paketlemeye başlayabilirsiniz."
        } else {
            "Çıkarılmış klasör yok. Paketlemeden önce otomatik çıkarılacak."
        }

        binding.btnRebuild.setOnClickListener { rebuild() }
    }

    private fun rebuild() {
        binding.btnRebuild.isEnabled = false
        binding.report.text = "Yeniden paketleniyor…"
        lifecycleScope.launch {
            try {
                val resultText = withContext(Dispatchers.IO) {
                    val apk = WorkspacePaths.selectedApk(this@RebuildActivity)
                    val extractDir = WorkspacePaths.extractDir(this@RebuildActivity)
                    if (!extractDir.exists()) {
                        ApkExtractor.extract(apk, extractDir)
                    }
                    val out = WorkspacePaths.rebuildApk(this@RebuildActivity)
                    val result = ApkRepackager.rebuild(extractDir, out)
                    buildString {
                        appendLine("İmzasız APK oluşturuldu.")
                        appendLine()
                        appendLine("Dosya : ${result.outputApk.absolutePath}")
                        appendLine("Boyut : ${ApkAnalyzer.formatSize(result.sizeBytes)}")
                        appendLine("İçerik: ${result.fileCount} dosya")
                        appendLine()
                        appendLine("Not: META-INF imzaları bilerek çıkarılır.")
                        appendLine("Kurulum için kendi keystore ile imzalayın.")
                        appendLine("Yalnızca sahip olduğunuz uygulamalar için kullanın.")
                    }
                }
                binding.report.text = resultText
            } catch (e: Exception) {
                binding.report.text = "Paketleme hatası:\n${e.message}"
            } finally {
                binding.btnRebuild.isEnabled = true
            }
        }
    }
}
