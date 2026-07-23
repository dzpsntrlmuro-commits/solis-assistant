package com.yuzfali.app.ui

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
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.yuzfali.app.R
import com.yuzfali.app.analysis.FaceAnalyzer
import com.yuzfali.app.databinding.ActivityMainBinding
import com.yuzfali.app.engine.FortuneEngine
import com.yuzfali.app.model.FortuneReport
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private val analyzer = FaceAnalyzer()
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var scanJob: Job? = null
    private var currentReport: FortuneReport? = null
    private var isScanning = false
    private var isSpeaking = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            binding.tvStatus.text = getString(R.string.status_permission)
            Toast.makeText(this, R.string.status_permission, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        textToSpeech = TextToSpeech(this, this)
        clearLegacyFaceData()
        setupUi()
        checkPermissions()
    }

    private fun setupUi() {
        binding.btnAction.setOnClickListener { if (isScanning) stopScan() else startScan() }
        binding.btnSpeak.setOnClickListener { if (isSpeaking) stopSpeaking() else speakFuture() }
        binding.btnRetry.setOnClickListener { resetToScan() }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private var analyzingFrame = false

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (isScanning && !analyzingFrame) {
                            analyzingFrame = true
                            lifecycleScope.launch {
                                try {
                                    analyzer.analyzeFrame(imageProxy)
                                } finally {
                                    analyzingFrame = false
                                }
                            }
                        } else {
                            imageProxy.close()
                        }
                    }
                }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Kamera başlatılamadı: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startScan() {
        analyzer.reset()
        isScanning = true
        binding.btnAction.text = getString(R.string.btn_stop)
        binding.tvStatus.text = getString(R.string.status_scanning)
        binding.scanOverlay.isVisible = true
        binding.progressScan.isVisible = true
        binding.reportScroll.isVisible = false
        binding.reportActions.isVisible = false
        stopSpeaking()

        scanJob = lifecycleScope.launch {
            delay(SCAN_DURATION_MS)
            if (isActive && isScanning) finishScan()
        }
    }

    private fun stopScan() {
        scanJob?.cancel()
        if (isScanning) finishScan()
    }

    private fun finishScan() {
        isScanning = false
        scanJob = null
        binding.btnAction.text = getString(R.string.btn_start)
        binding.scanOverlay.isVisible = false
        binding.progressScan.isVisible = false

        val snapshot = analyzer.snapshot()
        if (snapshot.face.frameCount < MIN_FACE_FRAMES) {
            binding.tvStatus.text = getString(R.string.status_no_face)
            Toast.makeText(this, R.string.status_no_face, Toast.LENGTH_LONG).show()
            return
        }

        val report = FortuneEngine.generate(snapshot, System.nanoTime())
        currentReport = report
        binding.tvStatus.text = getString(R.string.status_done)
        showReport(report)
        speakFuture()
    }

    private fun showReport(report: FortuneReport) {
        binding.tvGazeReport.text = report.gazeSection
        binding.tvFaceReport.text = report.faceSection
        binding.tvEmotionReport.text = report.emotionSection
        binding.tvFutureReport.text = report.futureSection
        binding.reportScroll.isVisible = true
        binding.reportActions.isVisible = true

        val panel = binding.bottomPanel
        val params = panel.layoutParams
        params.height = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        panel.layoutParams = params
    }

    private fun resetToScan() {
        stopSpeaking()
        currentReport = null
        analyzer.reset()
        binding.reportScroll.isVisible = false
        binding.reportActions.isVisible = false
        binding.tvStatus.text = getString(R.string.status_ready)
        binding.bottomPanel.layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private fun clearLegacyFaceData() {
        listOf("yuzfali_face_profiles", "solis_prefs").forEach { name ->
            getSharedPreferences(name, MODE_PRIVATE).edit().clear().apply()
        }
    }

    private fun speakFuture() {
        val report = currentReport ?: return
        if (!ttsReady) {
            Toast.makeText(this, "Ses motoru hazırlanıyor…", Toast.LENGTH_SHORT).show()
            return
        }
        textToSpeech?.stop()
        isSpeaking = true
        binding.btnSpeak.text = getString(R.string.btn_stop_speak)
        textToSpeech?.speak(report.futureSection, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private fun stopSpeaking() {
        textToSpeech?.stop()
        isSpeaking = false
        binding.btnSpeak.text = getString(R.string.btn_speak)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale("tr", "TR"))
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            textToSpeech?.setSpeechRate(0.92f)
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        isSpeaking = false
                        binding.btnSpeak.text = getString(R.string.btn_speak)
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    runOnUiThread {
                        isSpeaking = false
                        binding.btnSpeak.text = getString(R.string.btn_speak)
                    }
                }
            })
        }
    }

    override fun onDestroy() {
        scanJob?.cancel()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        analyzer.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val SCAN_DURATION_MS = 6000L
        private const val MIN_FACE_FRAMES = 8
        private const val UTTERANCE_ID = "fortune_speech"
    }
}
