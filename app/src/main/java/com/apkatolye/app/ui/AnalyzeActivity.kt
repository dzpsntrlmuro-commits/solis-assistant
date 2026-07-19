package com.apkatolye.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.apkatolye.app.apk.ApkAnalyzer
import com.apkatolye.app.apk.WorkspacePaths
import com.apkatolye.app.databinding.ActivityAnalyzeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AnalyzeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAnalyzeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAnalyzeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.report.text = "Analiz ediliyor…"

        lifecycleScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    val info = ApkAnalyzer.analyze(
                        this@AnalyzeActivity,
                        WorkspacePaths.selectedApk(this@AnalyzeActivity)
                    )
                    val text = ApkAnalyzer.formatReport(info)
                    WorkspacePaths.reportFile(this@AnalyzeActivity, "analyze.txt").writeText(text)
                    text
                }
                binding.report.text = report
            } catch (e: Exception) {
                binding.report.text = "Analiz hatası:\n${e.message}"
            }
        }
    }
}
