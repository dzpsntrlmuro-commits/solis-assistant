package com.yuzfali.app.analysis

import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import com.yuzfali.app.model.FaceFingerprint
import kotlin.math.abs
import kotlin.math.hypot

object FaceFingerprintExtractor {

    fun fromFace(face: Face): FaceFingerprint? {
        val box = face.boundingBox
        val boxW = box.width().toFloat()
        val boxH = box.height().toFloat()
        if (boxW <= 0f || boxH <= 0f) return null

        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position ?: return null
        // Mouth/cheek landmarks are optional — requiring all of them rejected too many valid frames.
        val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
        val mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
        val leftCheek = face.getLandmark(FaceLandmark.LEFT_CHEEK)?.position
        val rightCheek = face.getLandmark(FaceLandmark.RIGHT_CHEEK)?.position

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

        val eyeDist = dist(leX, leY, reX, reY)
        if (eyeDist < 0.08f) return null

        val eyeMidX = (leX + reX) / 2f
        val eyeMidY = (leY + reY) / 2f
        val faceHeight = boxH / boxW

        val features = mutableListOf(
            dist(leX, leY, nX, nY) / eyeDist,
            dist(reX, reY, nX, nY) / eyeDist,
            (nX - eyeMidX) / eyeDist,
            (nY - eyeMidY) / eyeDist,
            abs(leY - reY) / eyeDist,
            abs(nX - eyeMidX) / eyeDist
        )

        if (mouthLeft != null && mouthRight != null && mouthBottom != null) {
            val mlX = nx(mouthLeft.x)
            val mlY = ny(mouthLeft.y)
            val mrX = nx(mouthRight.x)
            val mrY = ny(mouthRight.y)
            val mbX = nx(mouthBottom.x)
            val mbY = ny(mouthBottom.y)
            val mouthWidth = dist(mlX, mlY, mrX, mrY).coerceAtLeast(0.01f)
            features.add(mouthWidth / eyeDist)
            features.add(dist(nX, nY, mbX, mbY) / eyeDist)
            features.add((mbY - nY) / eyeDist)
            features.add(abs(mlY - mrY) / eyeDist)
            features.add(dist(mlX, mlY, mbX, mbY) / mouthWidth)
            features.add(dist(mrX, mrY, mbX, mbY) / mouthWidth)
        } else {
            repeat(6) { features.add(0f) }
        }

        if (leftCheek != null && rightCheek != null) {
            val lcX = nx(leftCheek.x)
            val lcY = ny(leftCheek.y)
            val rcX = nx(rightCheek.x)
            val rcY = ny(rightCheek.y)
            features.add(dist(lcX, lcY, leX, leY) / eyeDist)
            features.add(dist(rcX, rcY, reX, reY) / eyeDist)
            features.add(dist(lcX, lcY, rcX, rcY) / eyeDist)
            features.add(dist(lcX, lcY, nX, nY) / eyeDist)
            features.add(dist(rcX, rcY, nX, nY) / eyeDist)
        } else {
            repeat(5) { features.add(0f) }
        }

        features.add(faceHeight)

        if (features.any { it.isNaN() || it.isInfinite() }) return null
        return FaceFingerprint(features.toFloatArray())
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
        if (fingerprints.size < 3) return 0.5f
        var totalDistance = 0f
        var pairs = 0
        // Sample consecutive pairs only — full pairwise is dominated by early/late jitter.
        for (i in 0 until fingerprints.lastIndex) {
            totalDistance += fingerprints[i].distanceTo(fingerprints[i + 1])
            pairs++
        }
        if (pairs == 0) return 0.5f
        val avgDistance = totalDistance / pairs
        return (1f - avgDistance / MAX_STABLE_DISTANCE).coerceIn(0f, 1f)
    }

    // Normal selfie sway / landmark noise stays well under this.
    private const val MAX_STABLE_DISTANCE = 0.18f
}
