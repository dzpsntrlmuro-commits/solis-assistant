package com.ickisayaci.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ickisayaci.app.R
import com.ickisayaci.app.databinding.ActivityMainBinding
import com.ickisayaci.app.detection.DrinkCountResult
import com.ickisayaci.app.detection.DrinkDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var detector: DrinkDetector
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val analyzing = AtomicBoolean(false)

    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var isSpeaking = false
    private var autoSpeakEnabled = true

    private var lastSpokenCount = -1
    private var stableCount = -1
    private var stableFrames = 0
    private var lastSpeakAtMs = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            binding.tvStatus.text = getString(R.string.status_permission)
            Toast.makeText(this, R.string.status_permission, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        detector = DrinkDetector(this)
        textToSpeech = TextToSpeech(this, this)
        setupUi()
        checkPermission()
    }

    private fun setupUi() {
        binding.btnSpeak.setOnClickListener {
            if (isSpeaking) {
                stopSpeaking()
            } else {
                speakResult(detector.latest(), force = true)
            }
        }
        binding.btnToggleAuto.setOnClickListener {
            autoSpeakEnabled = !autoSpeakEnabled
            binding.btnToggleAuto.text = getString(
                if (autoSpeakEnabled) R.string.btn_auto_on else R.string.btn_auto_off
            )
            binding.tvStatus.text = getString(
                if (autoSpeakEnabled) R.string.status_listening else R.string.status_manual
            )
        }
    }

    private fun checkPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { imageAnalysis ->
                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (!analyzing.compareAndSet(false, true)) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        lifecycleScope.launch(Dispatchers.Default) {
                            try {
                                val result = detector.analyze(imageProxy)
                                withContext(Dispatchers.Main) {
                                    onDetectionResult(result)
                                }
                            } catch (_: Exception) {
                                imageProxy.close()
                            } finally {
                                analyzing.set(false)
                            }
                        }
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                binding.tvStatus.text = getString(R.string.status_listening)
            } catch (e: Exception) {
                Toast.makeText(this, "Kamera açılamadı: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onDetectionResult(result: DrinkCountResult) {
        binding.tvCount.text = result.total.toString()
        binding.tvDetail.text = result.summaryText()
        binding.overlayView.setDetections(result.detections)

        if (result.total == stableCount) {
            stableFrames++
        } else {
            stableCount = result.total
            stableFrames = 1
        }

        if (autoSpeakEnabled &&
            stableFrames >= STABLE_FRAMES &&
            result.total != lastSpokenCount &&
            System.currentTimeMillis() - lastSpeakAtMs > SPEAK_COOLDOWN_MS
        ) {
            speakResult(result, force = false)
        }
    }

    private fun speakResult(result: DrinkCountResult, force: Boolean) {
        if (!ttsReady) {
            Toast.makeText(this, R.string.tts_preparing, Toast.LENGTH_SHORT).show()
            return
        }
        if (isSpeaking && !force) return

        val text = result.speechText()
        textToSpeech?.stop()
        isSpeaking = true
        lastSpokenCount = result.total
        lastSpeakAtMs = System.currentTimeMillis()
        binding.btnSpeak.text = getString(R.string.btn_stop_speak)
        binding.tvStatus.text = getString(R.string.status_speaking)
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private fun stopSpeaking() {
        textToSpeech?.stop()
        isSpeaking = false
        binding.btnSpeak.text = getString(R.string.btn_speak)
        binding.tvStatus.text = getString(
            if (autoSpeakEnabled) R.string.status_listening else R.string.status_manual
        )
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Toast.makeText(this, R.string.tts_error, Toast.LENGTH_LONG).show()
            return
        }
        val turkish = Locale("tr", "TR")
        val result = textToSpeech?.setLanguage(turkish)
        ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
            result != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ttsReady) {
            Toast.makeText(this, R.string.tts_no_turkish, Toast.LENGTH_LONG).show()
            // Fallback still usable with default locale voice
            textToSpeech?.language = Locale.getDefault()
            ttsReady = true
        }
        textToSpeech?.setSpeechRate(0.95f)
        textToSpeech?.setPitch(1.02f)
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                runOnUiThread { stopSpeaking() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread { stopSpeaking() }
            }
        })
    }

    override fun onDestroy() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        detector.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val UTTERANCE_ID = "drink_count"
        private const val STABLE_FRAMES = 4
        private const val SPEAK_COOLDOWN_MS = 3500L
    }
}
