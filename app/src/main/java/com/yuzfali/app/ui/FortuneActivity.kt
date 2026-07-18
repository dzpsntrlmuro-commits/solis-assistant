package com.yuzfali.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yuzfali.app.R
import com.yuzfali.app.databinding.ActivityFortuneBinding
import com.yuzfali.app.fortune.FaceReader
import com.yuzfali.app.fortune.FortuneEngine
import com.yuzfali.app.model.FortuneScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class FortuneActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        const val EXTRA_IMAGE_PATH = "image_path"
    }

    private lateinit var binding: ActivityFortuneBinding
    private var tts: TextToSpeech? = null
    private var script: FortuneScript? = null
    private var streamJob: Job? = null
    private var ttsReady = false
    private var pendingSpeak = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFortuneBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra(EXTRA_IMAGE_PATH)
        if (path.isNullOrBlank()) {
            Toast.makeText(this, R.string.no_face, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val bitmap = BitmapFactory.decodeFile(path)
        if (bitmap == null) {
            Toast.makeText(this, R.string.no_face, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        binding.faceImage.setImageBitmap(bitmap)
        binding.fortuneText.text = getString(R.string.analyzing)
        binding.actionsRow.visibility = View.GONE

        tts = TextToSpeech(this, this)

        binding.btnReplay.setOnClickListener { playFortune() }
        binding.btnNew.setOnClickListener {
            startActivity(Intent(this, CaptureActivity::class.java))
            finish()
        }

        lifecycleScope.launch {
            try {
                val traits = withContext(Dispatchers.Default) { FaceReader.read(bitmap) }
                if (traits == null) {
                    Toast.makeText(this@FortuneActivity, R.string.no_face, Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }
                script = FortuneEngine.generate(traits)
                binding.fortuneHeadline.text = script!!.headline
                if (ttsReady) playFortune() else pendingSpeak = true
            } catch (e: Exception) {
                Toast.makeText(this@FortuneActivity, "Analiz başarısız: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("tr", "TR"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.getDefault()
            }
            tts?.setSpeechRate(0.92f)
            tts?.setPitch(0.95f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        binding.videoProgress.progress = 100
                        binding.actionsRow.visibility = View.VISIBLE
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    runOnUiThread { binding.actionsRow.visibility = View.VISIBLE }
                }
            })
            ttsReady = true
            if (pendingSpeak) playFortune()
        } else {
            // TTS yoksa sadece metin animasyonu
            ttsReady = false
            if (script != null) playFortune()
        }
    }

    private fun playFortune() {
        val fortune = script ?: return
        streamJob?.cancel()
        tts?.stop()
        binding.actionsRow.visibility = View.GONE
        binding.videoProgress.progress = 0
        binding.fortuneText.text = ""

        val full = fortune.fullText
        val words = full.split(" ").filter { it.isNotBlank() }

        streamJob = lifecycleScope.launch {
            val builder = StringBuilder()
            words.forEachIndexed { index, word ->
                if (!isActive) return@launch
                if (builder.isNotEmpty()) builder.append(' ')
                builder.append(word)
                binding.fortuneText.text = builder.toString()
                binding.scriptScroll.post {
                    binding.scriptScroll.fullScroll(View.FOCUS_DOWN)
                }
                binding.videoProgress.progress =
                    (((index + 1).toFloat() / words.size) * 100).toInt()
                delay(140L)
            }
            binding.actionsRow.visibility = View.VISIBLE
        }

        if (ttsReady) {
            tts?.speak(full, TextToSpeech.QUEUE_FLUSH, null, "fortune_main")
        }
    }

    override fun onDestroy() {
        streamJob?.cancel()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
