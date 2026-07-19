package com.apkatolye.app.ui

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.apkatolye.app.databinding.ActivityFileEditorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset

class FileEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileEditorBinding
    private var localPath: String? = null
    private var docUri: Uri? = null
    private var charset: Charset = Charsets.UTF_8

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }

        localPath = intent.getStringExtra(EXTRA_PATH)
        docUri = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
        val name = intent.getStringExtra(EXTRA_NAME)

        binding.filePath.text = localPath ?: (name ?: docUri?.toString().orEmpty())
        binding.toolbar.title = name ?: localPath?.substringAfterLast('/') ?: "Dosya düzenle"

        binding.btnSave.setOnClickListener { save() }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    when {
                        localPath != null -> {
                            val file = File(localPath!!)
                            if (file.length() > MAX_EDIT_BYTES) {
                                error("Dosya çok büyük (max ${MAX_EDIT_BYTES / 1024} KB)")
                            }
                            file.readText(charset)
                        }
                        docUri != null -> {
                            contentResolver.openInputStream(docUri!!)?.use { input ->
                                val bytes = input.readBytes()
                                if (bytes.size > MAX_EDIT_BYTES) {
                                    error("Dosya çok büyük (max ${MAX_EDIT_BYTES / 1024} KB)")
                                }
                                String(bytes, charset)
                            } ?: error("Dosya okunamadı")
                        }
                        else -> error("Dosya belirtilmedi")
                    }
                }
                binding.editor.setText(text)
            } catch (e: Exception) {
                Toast.makeText(this@FileEditorActivity, e.message, Toast.LENGTH_LONG).show()
                binding.editor.setText("// Okuma hatası: ${e.message}")
                binding.btnSave.isEnabled = false
            }
        }
    }

    private fun save() {
        val content = binding.editor.text?.toString().orEmpty()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    when {
                        localPath != null -> File(localPath!!).writeText(content, charset)
                        docUri != null -> {
                            contentResolver.openOutputStream(docUri!!, "wt")?.use { out ->
                                out.write(content.toByteArray(charset))
                            } ?: error("Yazılamadı — klasör iznini kontrol edin")
                        }
                        else -> error("Hedef yok")
                    }
                }
                Toast.makeText(this@FileEditorActivity, "Kaydedildi", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@FileEditorActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"
        private const val MAX_EDIT_BYTES = 1_500_000
    }
}
