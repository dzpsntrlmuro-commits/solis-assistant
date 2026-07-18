package com.yuzfali.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
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
import com.yuzfali.app.R
import com.yuzfali.app.databinding.ActivityCaptureBinding
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CaptureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCaptureBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var capturing = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.faceFrame.alpha = 0.55f
        binding.faceFrame.animate()
            .alpha(1f)
            .setDuration(900L)
            .withEndAction {
                binding.faceFrame.animate()
                    .alpha(0.65f)
                    .setDuration(900L)
                    .withEndAction { pulseFrame() }
                    .start()
            }
            .start()

        binding.btnCapture.setOnClickListener { takePhoto() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun pulseFrame() {
        if (isFinishing || capturing) return
        binding.faceFrame.animate()
            .alpha(1f)
            .setDuration(1000L)
            .withEndAction {
                binding.faceFrame.animate()
                    .alpha(0.65f)
                    .setDuration(1000L)
                    .withEndAction { pulseFrame() }
                    .start()
            }
            .start()
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
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (_: Exception) {
                Toast.makeText(this, "Kamera açılamadı", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (capturing) return
        capturing = true
        binding.btnCapture.isEnabled = false
        binding.statusText.setText(R.string.analyzing)

        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = imageProxyToBitmap(image)
                    image.close()
                    if (bitmap == null) {
                        resetCapture("Fotoğraf okunamadı")
                        return
                    }
                    val path = saveBitmap(bitmap)
                    val intent = Intent(this@CaptureActivity, FortuneActivity::class.java)
                    intent.putExtra(FortuneActivity.EXTRA_IMAGE_PATH, path)
                    startActivity(intent)
                    capturing = false
                    binding.btnCapture.isEnabled = true
                    binding.statusText.text = ""
                }

                override fun onError(exception: ImageCaptureException) {
                    resetCapture("Çekim başarısız: ${exception.message}")
                }
            }
        )
    }

    private fun resetCapture(message: String) {
        capturing = false
        binding.btnCapture.isEnabled = true
        binding.statusText.text = message
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val buffer: ByteBuffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val matrix = Matrix().apply {
            postRotate(image.imageInfo.rotationDegrees.toFloat())
            // Front camera mirror correction for natural selfie look
            postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
    }

    private fun saveBitmap(bitmap: Bitmap): String {
        val file = java.io.File(cacheDir, "face_capture.jpg")
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        return file.absolutePath
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
