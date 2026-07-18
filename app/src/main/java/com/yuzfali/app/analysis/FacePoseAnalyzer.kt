package com.yuzfali.app.analysis

import android.graphics.PointF
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import com.yuzfali.app.model.AnalysisSnapshot
import com.yuzfali.app.model.FaceFingerprint
import com.yuzfali.app.model.FaceMetrics
import com.yuzfali.app.model.PoseMetrics
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class FacePoseAnalyzer {

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .enableTracking()
            .build()
    )

    private val poseDetector = PoseDetection.getClient(
        AccuratePoseDetectorOptions.Builder()
            .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
            .build()
    )

    private var faceAccumulator = FaceAccumulator()
    private var poseAccumulator = PoseAccumulator()
    private val fingerprintSamples = mutableListOf<FaceFingerprint>()

    fun reset() {
        faceAccumulator = FaceAccumulator()
        poseAccumulator = PoseAccumulator()
        fingerprintSamples.clear()
    }

    suspend fun analyzeFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        val faces = detectFaces(inputImage)
        val pose = detectPose(inputImage)

        faces.firstOrNull()?.let { face ->
            faceAccumulator.add(face)
            FaceFingerprintExtractor.fromFace(face)?.let { fingerprintSamples.add(it) }
        }
        pose?.let { poseAccumulator.add(it) }

        imageProxy.close()
    }

    fun snapshot(): AnalysisSnapshot = AnalysisSnapshot(
        face = faceAccumulator.average(),
        pose = poseAccumulator.average(),
        fingerprint = FaceFingerprintExtractor.average(fingerprintSamples)
    )

    fun close() {
        faceDetector.close()
        poseDetector.close()
    }

    private suspend fun detectFaces(inputImage: InputImage): List<Face> =
        suspendCancellableCoroutine { cont ->
            faceDetector.process(inputImage)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(emptyList()) }
        }

    private suspend fun detectPose(inputImage: InputImage): Pose? =
        suspendCancellableCoroutine { cont ->
            poseDetector.process(inputImage)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
        }

    private class FaceAccumulator {
        private var smile = 0f
        private var leftEye = 0f
        private var rightEye = 0f
        private var eulerY = 0f
        private var eulerZ = 0f
        private var width = 0f
        private var height = 0f
        private var count = 0

        fun add(face: Face) {
            smile += face.smilingProbability ?: 0f
            leftEye += face.leftEyeOpenProbability ?: 0.5f
            rightEye += face.rightEyeOpenProbability ?: 0.5f
            eulerY += face.headEulerAngleY
            eulerZ += face.headEulerAngleZ
            width += face.boundingBox.width().toFloat()
            height += face.boundingBox.height().toFloat()
            count++
        }

        fun average(): FaceMetrics {
            if (count == 0) return FaceMetrics()
            return FaceMetrics(
                smileProbability = smile / count,
                leftEyeOpen = leftEye / count,
                rightEyeOpen = rightEye / count,
                headEulerY = eulerY / count,
                headEulerZ = eulerZ / count,
                faceWidth = width / count,
                faceHeight = height / count,
                frameCount = count
            )
        }
    }

    private class PoseAccumulator {
        private var shoulderTilt = 0f
        private var spineAngle = 0f
        private var shoulderWidth = 0f
        private var headOffset = 0f
        private var confidence = 0f
        private var count = 0

        fun add(pose: Pose) {
            val leftShoulderLm = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
            val rightShoulderLm = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
            val noseLm = pose.getPoseLandmark(PoseLandmark.NOSE)
            val leftHipLm = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
            val rightHipLm = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)

            val leftShoulder = leftShoulderLm?.position
            val rightShoulder = rightShoulderLm?.position
            val nose = noseLm?.position
            val leftHip = leftHipLm?.position
            val rightHip = rightHipLm?.position

            if (leftShoulder == null || rightShoulder == null) return

            val tilt = Math.toDegrees(
                atan2(
                    (rightShoulder.y - leftShoulder.y).toDouble(),
                    (rightShoulder.x - leftShoulder.x).toDouble()
                )
            ).toFloat()

            val width = hypot(
                (rightShoulder.x - leftShoulder.x).toDouble(),
                (rightShoulder.y - leftShoulder.y).toDouble()
            ).toFloat()

            var spine = 0f
            if (leftHip != null && rightHip != null) {
                val midShoulder = midpoint(leftShoulder, rightShoulder)
                val midHip = midpoint(leftHip, rightHip)
                spine = abs(
                    Math.toDegrees(
                        atan2(
                            (midHip.x - midShoulder.x).toDouble(),
                            (midShoulder.y - midHip.y).toDouble()
                        )
                    ).toFloat()
                )
            }

            var offset = 0f
            if (nose != null && width > 0f) {
                val midX = (leftShoulder.x + rightShoulder.x) / 2f
                offset = abs(nose.x - midX) / width
            }

            val landmarks = listOfNotNull(leftShoulderLm, rightShoulderLm, noseLm, leftHipLm, rightHipLm)
            val avgInFrame = if (landmarks.isEmpty()) {
                0f
            } else {
                landmarks.sumOf { it.inFrameLikelihood.toDouble() }.toFloat() / landmarks.size
            }

            shoulderTilt += tilt
            spineAngle += spine
            shoulderWidth += width
            headOffset += offset
            confidence += avgInFrame
            count++
        }

        fun average(): PoseMetrics {
            if (count == 0) return PoseMetrics()
            return PoseMetrics(
                shoulderTilt = shoulderTilt / count,
                spineAngle = spineAngle / count,
                shoulderWidth = shoulderWidth / count,
                headOffset = headOffset / count,
                confidence = confidence / count,
                frameCount = count
            )
        }

        private fun midpoint(a: PointF, b: PointF): PointF =
            PointF((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    }
}
