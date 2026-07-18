package com.yuzfali.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
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
import com.yuzfali.app.analysis.FacePoseAnalyzer
import com.yuzfali.app.data.FaceProfileStore
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
    private lateinit var profileStore: FaceProfileStore
    private val analyzer = FacePoseAnalyzer()
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var scanJob: Job? = null
    private var currentReport: FortuneReport? = null
    private var isScanning = false
    private var isSpeaking = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
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

        profileStore = FaceProfileStore(this)
        textToSpeech = TextToSpeech(this, this)
        setupUi()
        checkPermissions()
    }

    private fun setupUi() {
        binding.btnAction.setOnClickListener {
            if (isScanning) stopScan() else startScan()
        }
        binding.btnSpeak.setOnClickListener {
            if (isSpeaking) stopSpeaking() else speakReport()
        }
        binding.btnRetry.setOnClickListener {
            resetToScan()
        }
    }

    private fun checkPermissions() {
        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startCamera()
        } else {
            permissionLauncher.launch(needed)
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

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
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
            if (isActive && isScanning) {
                finishScan()
            }
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

        val fingerprint = snapshot.fingerprint
        if (fingerprint == null || snapshot.fingerprintSampleCount < MIN_FINGERPRINT_SAMPLES) {
            binding.tvStatus.text = getString(R.string.status_no_face)
            Toast.makeText(
                this,
                "Yüz hatları net algılanamadı. Yüzünüzü ve omuzlarınızı sabit tutarak tekrar deneyin.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (snapshot.fingerprintQuality < MIN_FINGERPRINT_QUALITY) {
            binding.tvStatus.text = getString(R.string.status_no_face)
            Toast.makeText(
                this,
                "Tarama sırasında çok hareket ettiniz. Sabit durup tekrar deneyin.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val match = profileStore.findMatch(fingerprint, snapshot.fingerprintQuality)
        val report = if (match.isConfidentMatch && match.profile != null) {
            binding.tvStatus.text = getString(R.string.status_recognized, match.profile.displayName)
            match.profile.report
        } else {
            val newReport = FortuneEngine.generate(snapshot)
            val saved = profileStore.saveProfile(fingerprint, newReport)
            binding.tvStatus.text = getString(R.string.status_new_face, saved.displayName)
            newReport
        }

        currentReport = report
        showReport(report)
        speakReport(match.isConfidentMatch)
    }

    private fun showReport(report: FortuneReport) {
        if (binding.tvStatus.text.isNullOrBlank() ||
            binding.tvStatus.text == getString(R.string.status_scanning)
        ) {
            binding.tvStatus.text = getString(R.string.status_done)
        }
        binding.tvFaceReport.text = report.faceSection
        binding.tvPostureReport.text = report.postureSection
        binding.tvEmotionReport.text = report.emotionSection
        binding.tvFutureReport.text = report.futureSection

        binding.reportScroll.isVisible = true
        binding.reportActions.isVisible = true

        val panel = binding.bottomPanel
        val params = panel.layoutParams
        params.height = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        panel.layoutParams = params
        binding.reportScroll.layoutParams.height = params.height - resources.getDimensionPixelSize(
            com.google.android.material.R.dimen.mtrl_btn_padding_bottom
        ) * 6
    }

    private fun resetToScan() {
        stopSpeaking()
        currentReport = null
        analyzer.reset()
        binding.reportScroll.isVisible = false
        binding.reportActions.isVisible = false
        binding.tvStatus.text = getString(R.string.status_ready)

        val panel = binding.bottomPanel
        val params = panel.layoutParams
        params.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        panel.layoutParams = params
    }

    private fun speakReport(isReturningUser: Boolean = false) {
        val report = currentReport ?: return
        if (!ttsReady) {
            Toast.makeText(this, "Ses motoru hazırlanıyor…", Toast.LENGTH_SHORT).show()
            return
        }
        textToSpeech?.stop()
        isSpeaking = true
        binding.btnSpeak.text = getString(R.string.btn_stop_speak)
        val intro = if (isReturningUser) {
            "Sizi tanıdım. Falınız değişmedi. "
        } else {
            ""
        }
        textToSpeech?.speak(intro + report.fullSpeech, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private fun stopSpeaking() {
        textToSpeech?.stop()
        isSpeaking = false
        binding.btnSpeak.text = getString(R.string.btn_speak)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val turkish = Locale("tr", "TR")
            val result = textToSpeech?.setLanguage(turkish)
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
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
        private const val SCAN_DURATION_MS = 5000L
        private const val MIN_FACE_FRAMES = 3
        private const val MIN_FINGERPRINT_SAMPLES = 10
        private const val MIN_FINGERPRINT_QUALITY = 0.65f
        private const val UTTERANCE_ID = "fortune_speech"
    }
}
