package com.yuzfali.app.analysis

import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.yuzfali.app.model.AnalysisSnapshot
import com.yuzfali.app.model.FaceMetrics
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class FaceAnalyzer {

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .enableTracking()
            .build()
    )

    private var faceAccumulator = FaceAccumulator()

    fun reset() {
        faceAccumulator = FaceAccumulator()
    }

    suspend fun analyzeFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        val faces = detectFaces(inputImage)
        faces.firstOrNull()?.let { faceAccumulator.add(it) }
        imageProxy.close()
    }

    fun snapshot(): AnalysisSnapshot = AnalysisSnapshot(face = faceAccumulator.average())

    fun close() {
        faceDetector.close()
    }

    private suspend fun detectFaces(inputImage: InputImage): List<Face> =
        suspendCancellableCoroutine { cont ->
            faceDetector.process(inputImage)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(emptyList()) }
        }

    private class FaceAccumulator {
        private var smile = 0f
        private var smileMin = Float.MAX_VALUE
        private var smileMax = Float.MIN_VALUE
        private var leftEye = 0f
        private var rightEye = 0f
        private var eulerY = 0f
        private var eulerZ = 0f
        private var eulerX = 0f
        private var width = 0f
        private var height = 0f
        private var eyeDistanceRatio = 0f
        private var noseToMouthRatio = 0f
        private var mouthWidthRatio = 0f
        private var cheekWidthRatio = 0f
        private var landmarkAsymmetry = 0f
        private var landmarkCount = 0
        private var count = 0

        fun add(face: Face) {
            val smileValue = face.smilingProbability ?: 0f
            smile += smileValue
            smileMin = minOf(smileMin, smileValue)
            smileMax = maxOf(smileMax, smileValue)
            leftEye += face.leftEyeOpenProbability ?: 0.5f
            rightEye += face.rightEyeOpenProbability ?: 0.5f
            eulerY += face.headEulerAngleY
            eulerZ += face.headEulerAngleZ
            eulerX += face.headEulerAngleX
            width += face.boundingBox.width().toFloat()
            height += face.boundingBox.height().toFloat()

            FaceMetricsExtractor.landmarkFeatures(face)?.let { lm ->
                eyeDistanceRatio += lm.eyeDistanceRatio
                noseToMouthRatio += lm.noseToMouthRatio
                mouthWidthRatio += lm.mouthWidthRatio
                cheekWidthRatio += lm.cheekWidthRatio
                landmarkAsymmetry += lm.landmarkAsymmetry
                landmarkCount++
            }
            count++
        }

        fun average(): FaceMetrics {
            if (count == 0) return FaceMetrics()
            val avgSmile = smile / count
            return FaceMetrics(
                smileProbability = avgSmile,
                smileMin = if (smileMin == Float.MAX_VALUE) avgSmile else smileMin,
                smileMax = if (smileMax == Float.MIN_VALUE) avgSmile else smileMax,
                leftEyeOpen = leftEye / count,
                rightEyeOpen = rightEye / count,
                headEulerY = eulerY / count,
                headEulerZ = eulerZ / count,
                headEulerX = eulerX / count,
                faceWidth = width / count,
                faceHeight = height / count,
                eyeDistanceRatio = if (landmarkCount > 0) eyeDistanceRatio / landmarkCount else 0f,
                noseToMouthRatio = if (landmarkCount > 0) noseToMouthRatio / landmarkCount else 0f,
                mouthWidthRatio = if (landmarkCount > 0) mouthWidthRatio / landmarkCount else 0f,
                cheekWidthRatio = if (landmarkCount > 0) cheekWidthRatio / landmarkCount else 0f,
                landmarkAsymmetry = if (landmarkCount > 0) landmarkAsymmetry / landmarkCount else 0f,
                expressionVolatility = if (smileMax > smileMin) smileMax - smileMin else 0f,
                frameCount = count
            )
        }
    }
}
