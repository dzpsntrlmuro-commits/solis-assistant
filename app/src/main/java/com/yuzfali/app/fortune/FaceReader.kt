package com.yuzfali.app.fortune

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.yuzfali.app.model.FaceTraits
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

object FaceReader {

    private val detector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .enableTracking()
            .build()
        FaceDetection.getClient(options)
    }

    suspend fun read(bitmap: Bitmap): FaceTraits? = suspendCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (face == null) {
                    cont.resume(null)
                    return@addOnSuccessListener
                }
                val box = face.boundingBox
                val widthRatio = box.width().toFloat() / bitmap.width.coerceAtLeast(1)
                cont.resume(
                    FaceTraits(
                        smile = face.smilingProbability ?: 0.4f,
                        leftEyeOpen = face.leftEyeOpenProbability ?: 0.5f,
                        rightEyeOpen = face.rightEyeOpenProbability ?: 0.5f,
                        headYaw = face.headEulerAngleY,
                        headPitch = face.headEulerAngleX,
                        faceWidthRatio = widthRatio
                    )
                )
            }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}
