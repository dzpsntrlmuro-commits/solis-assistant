package com.apkatolye.app.ui

import android.animation.ObjectAnimator
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.apkatolye.app.apk.ApkAnalyzer
import com.apkatolye.app.apk.ApkExtractor
import com.apkatolye.app.apk.WorkspacePaths
import com.apkatolye.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedDisplayName: String? = null

    private val pickApk = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        importApk(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playEntrance()
        refreshSelectedState()
        wireActions()
    }

    private fun playEntrance() {
        val views = listOf(
            binding.brand,
            binding.tagline,
            binding.btnSelectApk,
            binding.apkStatus,
            binding.actions,
            binding.disclaimer
        )
        views.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 28f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((80L * index))
                .setDuration(420L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        ObjectAnimator.ofFloat(binding.brand, View.SCALE_X, 0.96f, 1f).apply {
            duration = 500
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun wireActions() {
        binding.btnSelectApk.setOnClickListener {
            pickApk.launch(arrayOf(
                "application/vnd.android.package-archive",
                "application/octet-stream",
                "*/*"
            ))
        }

        binding.tileAnalyze.setOnClickListener {
            requireApk {
                startActivity(Intent(this, AnalyzeActivity::class.java))
            }
        }

        binding.tileExtract.setOnClickListener {
            requireApk { extractApk() }
        }

        binding.tileClasses.setOnClickListener {
            requireApk {
                startActivity(Intent(this, ClassesActivity::class.java))
            }
        }

        binding.tilePlugins.setOnClickListener {
            requireApk {
                startActivity(Intent(this, PluginsActivity::class.java))
            }
        }

        binding.tileRebuild.setOnClickListener {
            requireApk {
                startActivity(Intent(this, RebuildActivity::class.java))
            }
        }
    }

    private fun requireApk(block: () -> Unit) {
        val apk = WorkspacePaths.selectedApk(this)
        if (!apk.exists()) {
            Toast.makeText(this, "Önce bir APK seçin", Toast.LENGTH_SHORT).show()
            return
        }
        block()
    }

    private fun importApk(uri: Uri) {
        setLoading(true)
        binding.statusLine.text = "APK içe aktarılıyor…"
        lifecycleScope.launch {
            try {
                val displayName = queryDisplayName(uri) ?: "selected.apk"
                withContext(Dispatchers.IO) {
                    val dest = WorkspacePaths.selectedApk(this@MainActivity)
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(dest).use { output -> input.copyTo(output) }
                    } ?: error("Dosya okunamadı")

                    WorkspacePaths.metaFile(this@MainActivity).writeText(displayName)

                    // Clear previous extract so rebuild doesn't use stale data
                    val extractDir = WorkspacePaths.extractDir(this@MainActivity)
                    if (extractDir.exists()) extractDir.deleteRecursively()
                }
                selectedDisplayName = displayName
                refreshSelectedState()
                binding.statusLine.text = "APK hazır: $displayName"
            } catch (e: Exception) {
                binding.statusLine.text = "İçe aktarma hatası: ${e.message}"
                Toast.makeText(this@MainActivity, e.message ?: "Hata", Toast.LENGTH_LONG).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun extractApk() {
        setLoading(true)
        binding.statusLine.text = "İçerik çıkarılıyor…"
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    ApkExtractor.extract(
                        WorkspacePaths.selectedApk(this@MainActivity),
                        WorkspacePaths.extractDir(this@MainActivity)
                    )
                }
                binding.statusLine.text =
                    "Çıkarıldı: ${result.fileCount} dosya → ${result.outputDir.absolutePath}"
                Toast.makeText(this@MainActivity, "İçerik çıkarıldı", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                binding.statusLine.text = "Çıkarma hatası: ${e.message}"
            } finally {
                setLoading(false)
            }
        }
    }

    private fun refreshSelectedState() {
        val apk = WorkspacePaths.selectedApk(this)
        val meta = WorkspacePaths.metaFile(this)
        selectedDisplayName = if (meta.exists()) meta.readText().trim() else null

        if (!apk.exists()) {
            binding.apkName.text = getString(com.apkatolye.app.R.string.no_apk)
            binding.apkMeta.text = ""
            return
        }

        val name = selectedDisplayName ?: apk.name
        binding.apkName.text = name
        binding.apkMeta.text = ApkAnalyzer.formatSize(apk.length()) + "  ·  " + apk.absolutePath

        // Soft pulse on status panel when APK present
        binding.apkStatus.animate().scaleX(1.02f).scaleY(1.02f).setDuration(160)
            .withEndAction {
                binding.apkStatus.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }.start()
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
        }
        return uri.lastPathSegment
    }

    private fun setLoading(loading: Boolean) {
        binding.loadingOverlay.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
