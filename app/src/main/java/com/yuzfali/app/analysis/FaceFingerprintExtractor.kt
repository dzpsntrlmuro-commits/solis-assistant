package com.yuzfali.app.analysis

import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import com.yuzfali.app.model.FaceFingerprint
import kotlin.math.abs
import kotlin.math.hypot

object FaceFingerprintExtractor {

    private const val MIN_FEATURE_COUNT = 12

    fun fromFace(face: Face): FaceFingerprint? {
        val box = face.boundingBox
        val boxW = box.width().toFloat()
        val boxH = box.height().toFloat()
        if (boxW <= 0f || boxH <= 0f) return null

        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position ?: return null
        val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position ?: return null
        val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position ?: return null
        val mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position ?: return null
        val leftCheek = face.getLandmark(FaceLandmark.LEFT_CHEEK)?.position ?: return null
        val rightCheek = face.getLandmark(FaceLandmark.RIGHT_CHEEK)?.position ?: return null

        fun nx(x: Float) = (x - box.left) / boxW
        fun ny(y: Float) = (y - box.top) / boxH
        fun dist(ax: Float, ay: Float, bx: Float, by: Float) =
            hypot((ax - bx).toDouble(), (ay - by).toDouble()).toFloat()

        val leX = nx(leftEye.x)
        val leY = ny(leftEye.y)
        val reX = nx(rightEye.x)
        val reY = ny(rightEye.y)
        val nX = nx(nose.x)
        val nY = ny(nose.y)
        val mlX = nx(mouthLeft.x)
        val mlY = ny(mouthLeft.y)
        val mrX = nx(mouthRight.x)
        val mrY = ny(mouthRight.y)
        val mbX = nx(mouthBottom.x)
        val mbY = ny(mouthBottom.y)
        val lcX = nx(leftCheek.x)
        val lcY = ny(leftCheek.y)
        val rcX = nx(rightCheek.x)
        val rcY = ny(rightCheek.y)

        val eyeDist = dist(leX, leY, reX, reY)
        if (eyeDist < 0.08f) return null

        val eyeMidX = (leX + reX) / 2f
        val eyeMidY = (leY + reY) / 2f
        val mouthWidth = dist(mlX, mlY, mrX, mrY)
        val faceHeight = boxH / boxW

        val features = floatArrayOf(
            dist(leX, leY, nX, nY) / eyeDist,
            dist(reX, reY, nX, nY) / eyeDist,
            (nX - eyeMidX) / eyeDist,
            (nY - eyeMidY) / eyeDist,
            abs(leY - reY) / eyeDist,
            abs(nX - eyeMidX) / eyeDist,
            mouthWidth / eyeDist,
            dist(nX, nY, mbX, mbY) / eyeDist,
            (mbY - nY) / eyeDist,
            abs(mlY - mrY) / eyeDist,
            dist(mlX, mlY, mbX, mbY) / mouthWidth.coerceAtLeast(0.01f),
            dist(mrX, mrY, mbX, mbY) / mouthWidth.coerceAtLeast(0.01f),
            dist(lcX, lcY, leX, leY) / eyeDist,
            dist(rcX, rcY, reX, reY) / eyeDist,
            dist(lcX, lcY, rcX, rcY) / eyeDist,
            dist(lcX, lcY, nX, nY) / eyeDist,
            dist(rcX, rcY, nX, nY) / eyeDist,
            faceHeight
        )

        if (features.size < MIN_FEATURE_COUNT) return null
        if (features.any { it.isNaN() || it.isInfinite() }) return null

        return FaceFingerprint(features)
    }

    fun average(fingerprints: List<FaceFingerprint>): FaceFingerprint? {
        if (fingerprints.isEmpty()) return null
        val size = fingerprints.first().features.size
        if (fingerprints.any { it.features.size != size }) return null
        val avg = FloatArray(size)
        for (fp in fingerprints) {
            for (i in avg.indices) {
                avg[i] += fp.features[i]
            }
        }
        for (i in avg.indices) {
            avg[i] = avg[i] / fingerprints.size
        }
        return FaceFingerprint(avg)
    }

    fun scanQuality(fingerprints: List<FaceFingerprint>): Float {
        if (fingerprints.size < 3) return 0f
        // Compare consecutive samples only — cheaper and less harsh than all-pairs.
        var totalDistance = 0f
        var pairs = 0
        for (i in 0 until fingerprints.lastIndex) {
            totalDistance += fingerprints[i].distanceTo(fingerprints[i + 1])
            pairs++
        }
        if (pairs == 0) return 0f
        val avgDistance = totalDistance / pairs
        return (1f - avgDistance / MAX_STABLE_DISTANCE).coerceIn(0f, 1f)
    }

    // Allow normal micro-movement / lighting flicker without rejecting the scan.
    private const val MAX_STABLE_DISTANCE = 0.18f
}
