package com.apklab.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.apklab.app.databinding.ActivityCodeViewerBinding
import java.io.File

class CodeViewerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityCodeViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra(EXTRA_PATH) ?: return finish()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: File(path).name
        binding.tvTitle.text = title
        binding.tvPath.text = path

        val file = File(path)
        binding.tvContent.text = when {
            !file.exists() -> "Dosya yok"
            file.length() > 2_000_000 -> "Dosya çok büyük (${file.length()} B). Dışarıdan açın."
            else -> {
                val raw = file.readBytes()
                val text = raw.toString(Charsets.UTF_8)
                if (text.take(200).any { it < ' ' && it != '\n' && it != '\r' && it != '\t' }) {
                    "İkili dosya (${file.length()} B). Metin olarak gösterilemiyor."
                } else {
                    text.take(500_000)
                }
            }
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_TITLE = "title"
    }
}
