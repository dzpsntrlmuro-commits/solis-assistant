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

    // GetContent("*/*") shows all files including APKs that OpenDocument often hides
    private val pickAnyFile = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        importApk(uri)
    }

    private val pickOpenDocument = registerForActivityResult(
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

    override fun onResume() {
        super.onResume()
        refreshSelectedState()
    }

    private fun playEntrance() {
        val views = listOf(
            binding.brand,
            binding.tagline,
            binding.btnSelectApk,
            binding.btnBrowseFiles,
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
                .setStartDelay((70L * index))
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
            // Show all files so .apk is visible in Downloads / Files apps
            pickAnyFile.launch("*/*")
        }

        binding.btnBrowseFiles.setOnClickListener {
            startActivity(Intent(this, FilesActivity::class.java))
        }

        binding.tileAnalyze.setOnClickListener {
            requireApk {
                startActivity(Intent(this, AnalyzeActivity::class.java))
            }
        }

        binding.tileExtract.setOnClickListener {
            requireApk { extractApk(openFilesAfter = true) }
        }

        binding.tileFiles.setOnClickListener {
            startActivity(Intent(this, FilesActivity::class.java))
        }

        binding.tileTest.setOnClickListener {
            startActivity(Intent(this, TestActivity::class.java))
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

        // Long-press APK select for SAF document picker fallback
        binding.btnSelectApk.setOnLongClickListener {
            pickOpenDocument.launch(arrayOf("*/*"))
            true
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
                if (!displayName.endsWith(".apk", ignoreCase = true) &&
                    contentResolver.getType(uri)?.contains("package-archive") != true
                ) {
                    Toast.makeText(
                        this@MainActivity,
                        "Uyarı: dosya .apk uzantılı görünmüyor, yine de içe aktarılıyor",
                        Toast.LENGTH_LONG
                    ).show()
                }

                withContext(Dispatchers.IO) {
                    val dest = WorkspacePaths.selectedApk(this@MainActivity)
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(dest).use { output -> input.copyTo(output) }
                    } ?: error("Dosya okunamadı")

                    // Basic ZIP/APK sanity check
                    if (dest.length() < 100) error("Dosya boş veya geçersiz")

                    WorkspacePaths.metaFile(this@MainActivity).writeText(displayName)

                    val extractDir = WorkspacePaths.extractDir(this@MainActivity)
                    if (extractDir.exists()) extractDir.deleteRecursively()
                }
                selectedDisplayName = displayName
                refreshSelectedState()
                binding.statusLine.text = "APK hazır: $displayName (${ApkAnalyzer.formatSize(WorkspacePaths.selectedApk(this@MainActivity).length())})"
                Toast.makeText(this@MainActivity, "APK seçildi: $displayName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                binding.statusLine.text = "İçe aktarma hatası: ${e.message}"
                Toast.makeText(this@MainActivity, e.message ?: "Hata", Toast.LENGTH_LONG).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun extractApk(openFilesAfter: Boolean) {
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
                if (openFilesAfter) {
                    startActivity(
                        Intent(this@MainActivity, FilesActivity::class.java)
                            .putExtra(
                                FilesActivity.EXTRA_START_DIR,
                                result.outputDir.absolutePath
                            )
                    )
                }
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
            binding.apkMeta.text = "Dosya seçici tüm dosyaları gösterir (.apk dahil)"
            return
        }

        val name = selectedDisplayName ?: apk.name
        binding.apkName.text = name
        binding.apkMeta.text = ApkAnalyzer.formatSize(apk.length()) + "  ·  " + apk.absolutePath

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
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun setLoading(loading: Boolean) {
        binding.loadingOverlay.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
