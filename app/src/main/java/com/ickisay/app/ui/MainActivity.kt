package com.ickisay.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.ickisay.app.R
import com.ickisay.app.databinding.ActivityMainBinding
import com.ickisay.app.detection.DrinkCountResult
import com.ickisay.app.detection.DrinkDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var drinkDetector: DrinkDetector? = null

    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var isSpeaking = false
    private var isCounting = false
    private var lastSpeech: String? = null

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

        cameraExecutor = Executors.newSingleThreadExecutor()
        textToSpeech = TextToSpeech(this, this)

        binding.btnCount.setOnClickListener { countDrinks() }
        binding.btnSpeak.setOnClickListener {
            if (isSpeaking) stopSpeaking() else speakLast()
        }

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                drinkDetector = DrinkDetector(applicationContext)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Yapay zeka modeli yüklenemedi: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        checkPermission()
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

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
                binding.tvStatus.text = getString(R.string.status_ready)
            } catch (e: Exception) {
                binding.tvStatus.text = getString(R.string.status_camera_error)
                Toast.makeText(
                    this,
                    "${getString(R.string.status_camera_error)}: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun countDrinks() {
        if (isCounting) return
        val capture = imageCapture ?: run {
            Toast.makeText(this, R.string.status_camera_error, Toast.LENGTH_SHORT).show()
            return
        }
        if (drinkDetector == null) {
            Toast.makeText(this, "Yapay zeka modeli henüz hazır değil", Toast.LENGTH_SHORT).show()
            return
        }

        isCounting = true
        binding.btnCount.isEnabled = false
        binding.btnCount.text = getString(R.string.btn_counting)
        binding.progressScan.isVisible = true
        binding.tvStatus.text = getString(R.string.status_counting)
        binding.overlayView.clear()

        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = imageProxyToBitmap(image)
                image.close()
                if (bitmap == null) {
                    runOnUiThread { onCountFailed("Görüntü alınamadı") }
                    return
                }
                val result = try {
                    drinkDetector?.detect(bitmap)
                } catch (e: Exception) {
                    runOnUiThread { onCountFailed(e.message ?: "Analiz hatası") }
                    return
                }
                runOnUiThread {
                    if (result != null) onCountSuccess(result, bitmap.width, bitmap.height)
                    else onCountFailed("Sonuç yok")
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread { onCountFailed(exception.message ?: "Fotoğraf hatası") }
            }
        })
    }

    private fun onCountSuccess(result: DrinkCountResult, imageWidth: Int, imageHeight: Int) {
        isCounting = false
        binding.btnCount.isEnabled = true
        binding.btnCount.text = getString(R.string.btn_count)
        binding.progressScan.isVisible = false
        binding.tvCount.text = result.total.toString()
        binding.tvDetail.text = result.detailText
        binding.tvStatus.text = if (result.total == 0) {
            "İçki bulunamadı"
        } else {
            "${result.total} içki bulundu"
        }
        binding.overlayView.setDetections(result.items, imageWidth, imageHeight)
        lastSpeech = result.summarySpeech
        binding.btnSpeak.isEnabled = true
        speak(result.summarySpeech)
    }

    private fun onCountFailed(message: String) {
        isCounting = false
        binding.btnCount.isEnabled = true
        binding.btnCount.text = getString(R.string.btn_count)
        binding.progressScan.isVisible = false
        binding.tvStatus.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val bitmap = when (image.format) {
            ImageFormat.JPEG -> {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            ImageFormat.YUV_420_888 -> yuvToBitmap(image)
            else -> {
                // CameraX genelde JPEG döner; yine de deneme yap
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } ?: return null

        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun yuvToBitmap(image: ImageProxy): Bitmap? {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
        val jpeg = out.toByteArray()
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    }

    private fun speakLast() {
        lastSpeech?.let { speak(it) }
    }

    private fun speak(text: String) {
        if (!ttsReady) {
            Toast.makeText(this, "Ses motoru hazırlanıyor…", Toast.LENGTH_SHORT).show()
            return
        }
        textToSpeech?.stop()
        isSpeaking = true
        binding.btnSpeak.text = getString(R.string.btn_stop_speak)
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private fun stopSpeaking() {
        textToSpeech?.stop()
        isSpeaking = false
        binding.btnSpeak.text = getString(R.string.btn_speak)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val turkish = Locale("tr", "TR")
        val result = textToSpeech?.setLanguage(turkish)
        ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
            result != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ttsReady) {
            // Cihazda Türkçe yoksa varsayılan dil ile devam et
            textToSpeech?.language = Locale.getDefault()
            ttsReady = true
        }
        textToSpeech?.setSpeechRate(0.95f)
        textToSpeech?.setPitch(1.05f)
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

    override fun onDestroy() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        drinkDetector?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val UTTERANCE_ID = "icki_say_speech"
    }
}
