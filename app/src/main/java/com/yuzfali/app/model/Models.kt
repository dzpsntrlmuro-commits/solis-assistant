package com.yuzfali.app.model

data class FaceMetrics(
    val smileProbability: Float = 0f,
    val leftEyeOpen: Float = 0f,
    val rightEyeOpen: Float = 0f,
    val headEulerY: Float = 0f,
    val headEulerZ: Float = 0f,
    val faceWidth: Float = 0f,
    val faceHeight: Float = 0f,
    val frameCount: Int = 0
) {
    val eyeSymmetry: Float
        get() = 1f - kotlin.math.abs(leftEyeOpen - rightEyeOpen)

    val faceRatio: Float
        get() = if (faceHeight > 0f) faceWidth / faceHeight else 1f
}

data class PoseMetrics(
    val shoulderTilt: Float = 0f,
    val spineAngle: Float = 0f,
    val shoulderWidth: Float = 0f,
    val headOffset: Float = 0f,
    val confidence: Float = 0f,
    val frameCount: Int = 0
)

data class AnalysisSnapshot(
    val face: FaceMetrics = FaceMetrics(),
    val pose: PoseMetrics = PoseMetrics()
)

data class FortuneReport(
    val faceSection: String,
    val postureSection: String,
    val emotionSection: String,
    val futureSection: String,
    val fullSpeech: String
)
