package com.yuzfali.app.analysis

import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import com.yuzfali.app.model.FaceFingerprint
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
        val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
        val mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
        val leftCheek = face.getLandmark(FaceLandmark.LEFT_CHEEK)?.position
        val rightCheek = face.getLandmark(FaceLandmark.RIGHT_CHEEK)?.position

        fun nx(x: Float) = (x - box.left) / boxW
        fun ny(y: Float) = (y - box.top) / boxH
        fun dist(ax: Float, ay: Float, bx: Float, by: Float) = hypot((ax - bx).toDouble(), (ay - by).toDouble()).toFloat()

        val leX = nx(leftEye.x)
        val leY = ny(leftEye.y)
        val reX = nx(rightEye.x)
        val reY = ny(rightEye.y)
        val nX = nx(nose.x)
        val nY = ny(nose.y)

        val eyeDist = dist(leX, leY, reX, reY)
        if (eyeDist < 0.05f) return null

        val eyeMidX = (leX + reX) / 2f
        val eyeMidY = (leY + reY) / 2f

        val features = mutableListOf(
            eyeDist,
            (nX - eyeMidX) / eyeDist,
            (nY - eyeMidY) / eyeDist,
            dist(leX, leY, nX, nY) / eyeDist,
            dist(reX, reY, nX, nY) / eyeDist,
            kotlin.math.abs(leY - reY) / eyeDist,
            kotlin.math.abs(nX - eyeMidX) / eyeDist
        )

        if (mouthLeft != null && mouthRight != null && mouthBottom != null) {
            val mlX = nx(mouthLeft.x)
            val mlY = ny(mouthLeft.y)
            val mrX = nx(mouthRight.x)
            val mrY = ny(mouthRight.y)
            val mbX = nx(mouthBottom.x)
            val mbY = ny(mouthBottom.y)
            val mouthWidth = dist(mlX, mlY, mrX, mrY)
            features.add(mouthWidth / eyeDist)
            features.add(dist(nX, nY, mbX, mbY) / eyeDist)
            features.add((mbY - nY) / eyeDist)
            features.add(kotlin.math.abs(mlY - mrY) / eyeDist)
        }

        if (leftCheek != null && rightCheek != null) {
            val lcX = nx(leftCheek.x)
            val lcY = ny(leftCheek.y)
            val rcX = nx(rightCheek.x)
            val rcY = ny(rightCheek.y)
            features.add(dist(lcX, lcY, leX, leY) / eyeDist)
            features.add(dist(rcX, rcY, reX, reY) / eyeDist)
            features.add(dist(lcX, lcY, rcX, rcY) / eyeDist)
        }

        features.add(face.headEulerAngleY / 90f)
        features.add(face.headEulerAngleZ / 90f)

        return FaceFingerprint(features.toFloatArray())
    }

    fun average(fingerprints: List<FaceFingerprint>): FaceFingerprint? {
        if (fingerprints.isEmpty()) return null
        val size = fingerprints.first().features.size
        if (fingerprints.any { it.features.size != size }) return fingerprints.first()
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
}
