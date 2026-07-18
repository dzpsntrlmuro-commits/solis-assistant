package com.yuzfali.app.analysis

import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import com.yuzfali.app.model.FaceMetrics
import kotlin.math.abs
import kotlin.math.hypot

internal object FaceMetricsExtractor {

    fun landmarkFeatures(face: Face): LandmarkFeatures? {
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
        val mlX = nx(mouthLeft.x)
        val mlY = ny(mouthLeft.y)
        val mrX = nx(mouthRight.x)
        val mrY = ny(mouthRight.y)
        val mbX = nx(mouthBottom.x)
        val mbY = ny(mouthBottom.y)

        val eyeDist = dist(leX, leY, reX, reY).coerceAtLeast(0.01f)
        val mouthWidth = dist(mlX, mlY, mrX, mrY)
        val noseToMouth = dist(nX, nY, mbX, mbY)

        val leftSide = dist(leX, leY, nX, nY)
        val rightSide = dist(reX, reY, nX, nY)
        val asymmetry = abs(leftSide - rightSide) / eyeDist

        val cheekWidth = if (leftCheek != null && rightCheek != null) {
            dist(nx(leftCheek.x), ny(leftCheek.y), nx(rightCheek.x), ny(rightCheek.y)) / eyeDist
        } else {
            0f
        }

        return LandmarkFeatures(
            eyeDistanceRatio = eyeDist,
            noseToMouthRatio = noseToMouth / eyeDist,
            mouthWidthRatio = mouthWidth / eyeDist,
            cheekWidthRatio = cheekWidth,
            landmarkAsymmetry = asymmetry
        )
    }

    data class LandmarkFeatures(
        val eyeDistanceRatio: Float,
        val noseToMouthRatio: Float,
        val mouthWidthRatio: Float,
        val cheekWidthRatio: Float,
        val landmarkAsymmetry: Float
    )
}
