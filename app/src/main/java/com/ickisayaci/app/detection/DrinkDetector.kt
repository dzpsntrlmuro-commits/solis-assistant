package com.ickisayaci.app.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

class DrinkDetector(context: Context) {

    private val detector: ObjectDetector
    private val latestResult = AtomicReference(DrinkCountResult(0, emptyList(), emptyMap()))

    init {
        val baseOptions = BaseOptions.builder()
            .setNumThreads(4)
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setMaxResults(15)
            .setScoreThreshold(SCORE_THRESHOLD)
            .build()
        detector = ObjectDetector.createFromFileAndOptions(
            context,
            MODEL_FILE,
            options
        )
    }

    fun latest(): DrinkCountResult = latestResult.get()

    fun analyze(imageProxy: ImageProxy): DrinkCountResult {
        val bitmap = imageProxy.toBitmap()
        var rotated: Bitmap? = null
        return try {
            rotated = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
            val frame = rotated
            val tensorImage = TensorImage.fromBitmap(frame)
            val detections = detector.detect(tensorImage)
            val drinks = mutableListOf<DrinkDetection>()

            for (detection in detections) {
                val category = detection.categories.maxByOrNull { it.score } ?: continue
                val label = category.label.lowercase().trim()
                val turkish = DRINK_LABELS[label] ?: continue
                val box = detection.boundingBox
                drinks += DrinkDetection(
                    label = label,
                    turkishLabel = turkish,
                    score = category.score,
                    left = box.left / frame.width,
                    top = box.top / frame.height,
                    right = box.right / frame.width,
                    bottom = box.bottom / frame.height
                )
            }

            val filtered = nms(drinks)
            val breakdown = filtered.groupingBy { it.turkishLabel }.eachCount()
            val result = DrinkCountResult(
                total = filtered.size,
                detections = filtered,
                breakdown = breakdown
            )
            latestResult.set(result)
            result
        } finally {
            if (rotated != null && rotated !== bitmap) {
                rotated.recycle()
            }
            bitmap.recycle()
            imageProxy.close()
        }
    }

    fun close() {
        detector.close()
    }

    private fun nms(detections: List<DrinkDetection>): List<DrinkDetection> {
        if (detections.isEmpty()) return emptyList()
        val sorted = detections.sortedByDescending { it.score }.toMutableList()
        val kept = mutableListOf<DrinkDetection>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept += best
            sorted.removeAll { iou(best, it) > NMS_THRESHOLD }
        }
        return kept
    }

    private fun iou(a: DrinkDetection, b: DrinkDetection): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val inter = max(0f, right - left) * max(0f, bottom - top)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    companion object {
        private const val MODEL_FILE = "efficientdet_lite0.tflite"
        private const val SCORE_THRESHOLD = 0.35f
        private const val NMS_THRESHOLD = 0.45f

        /** COCO labels that represent drink containers. */
        private val DRINK_LABELS = mapOf(
            "bottle" to "şişe",
            "wine glass" to "kadeh",
            "cup" to "bardak"
        )
    }
}
