package com.ickisay.app.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector

data class DrinkItem(
    val label: String,
    val turkishLabel: String,
    val score: Float,
    val box: RectF
)

data class DrinkCountResult(
    val total: Int,
    val items: List<DrinkItem>,
    val summarySpeech: String,
    val detailText: String
)

class DrinkDetector(context: Context) {

    private val detector: ObjectDetector

    init {
        val baseOptions = BaseOptions.builder()
            .setNumThreads(4)
            .build()

        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setScoreThreshold(SCORE_THRESHOLD)
            .setMaxResults(MAX_RESULTS)
            .build()

        detector = ObjectDetector.createFromFileAndOptions(
            context,
            MODEL_FILE,
            options
        )
    }

    fun detect(bitmap: Bitmap): DrinkCountResult {
        val image = TensorImage.fromBitmap(bitmap)
        val detections = detector.detect(image)
        val drinks = detections.mapNotNull { toDrinkItem(it) }
            .let { mergeOverlapping(it) }
            .sortedByDescending { it.score }

        val total = drinks.size
        val grouped = drinks.groupingBy { it.turkishLabel }.eachCount()

        val detail = if (drinks.isEmpty()) {
            "İçki algılanamadı. Şişe, kutu, kadeh veya bardaklara daha net tutun."
        } else {
            buildString {
                append("Toplam $total içki\n")
                grouped.entries.sortedByDescending { it.value }.forEach { (name, count) ->
                    append("• $count $name\n")
                }
            }.trim()
        }

        val speech = when (total) {
            0 -> "Hiç içki göremiyorum. Lütfen kamerayı şişe, kutu veya bardaklara tutun."
            1 -> "Bir tane içki görüyorum. ${drinks.first().turkishLabel}."
            else -> {
                val parts = grouped.entries
                    .sortedByDescending { it.value }
                    .joinToString(", ") { "${it.value} ${it.key}" }
                "$total tane içki görüyorum. $parts."
            }
        }

        return DrinkCountResult(
            total = total,
            items = drinks,
            summarySpeech = speech,
            detailText = detail
        )
    }

    fun close() {
        detector.close()
    }

    private fun toDrinkItem(detection: Detection): DrinkItem? {
        val category = detection.categories.maxByOrNull { it.score } ?: return null
        val label = category.label.trim().lowercase()
        val turkish = DRINK_LABELS[label] ?: return null
        val box = detection.boundingBox
        return DrinkItem(
            label = label,
            turkishLabel = turkish,
            score = category.score,
            box = RectF(box.left, box.top, box.right, box.bottom)
        )
    }

    /** Aynı nesneyi iki kez saymamak için örtüşen kutuları birleştir. */
    private fun mergeOverlapping(items: List<DrinkItem>): List<DrinkItem> {
        if (items.isEmpty()) return items
        val sorted = items.sortedByDescending { it.score }
        val kept = mutableListOf<DrinkItem>()
        for (item in sorted) {
            val overlaps = kept.any { iou(it.box, item.box) > IOU_THRESHOLD }
            if (!overlaps) kept.add(item)
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val inter = maxOf(0f, right - left) * maxOf(0f, bottom - top)
        if (inter <= 0f) return 0f
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    companion object {
        private const val MODEL_FILE = "efficientdet_lite0.tflite"
        private const val SCORE_THRESHOLD = 0.35f
        private const val MAX_RESULTS = 25
        private const val IOU_THRESHOLD = 0.45f

        // COCO etiketleri: bottle, wine glass, cup (+ yakın eş anlamlılar)
        private val DRINK_LABELS = mapOf(
            "bottle" to "şişe",
            "wine glass" to "kadeh",
            "cup" to "bardak",
            "wine_glass" to "kadeh"
        )
    }
}
