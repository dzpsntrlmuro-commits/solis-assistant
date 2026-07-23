package com.yuzfali.app.model

data class FaceMetrics(
    val smileProbability: Float = 0f,
    val smileMin: Float = 0f,
    val smileMax: Float = 0f,
    val leftEyeOpen: Float = 0f,
    val rightEyeOpen: Float = 0f,
    val headEulerY: Float = 0f,
    val headEulerZ: Float = 0f,
    val headEulerX: Float = 0f,
    val faceWidth: Float = 0f,
    val faceHeight: Float = 0f,
    val eyeDistanceRatio: Float = 0f,
    val noseToMouthRatio: Float = 0f,
    val mouthWidthRatio: Float = 0f,
    val cheekWidthRatio: Float = 0f,
    val landmarkAsymmetry: Float = 0f,
    val expressionVolatility: Float = 0f,
    val frameCount: Int = 0
) {
    val eyeSymmetry: Float
        get() = 1f - kotlin.math.abs(leftEyeOpen - rightEyeOpen)

    val faceRatio: Float
        get() = if (faceHeight > 0f) faceWidth / faceHeight else 1f

    val smileRange: Float
        get() = (smileMax - smileMin).coerceAtLeast(0f)

    val gazeHorizontal: String
        get() = when {
            headEulerY > 12f -> "sağa"
            headEulerY < -12f -> "sola"
            else -> "düz ileri"
        }

    val gazeVertical: String
        get() = when {
            headEulerX > 10f -> "yukarı"
            headEulerX < -10f -> "aşağı"
            else -> "horizon hizasında"
        }
}

data class AnalysisSnapshot(
    val face: FaceMetrics = FaceMetrics()
)

data class FortuneReport(
    val gazeSection: String,
    val faceSection: String,
    val emotionSection: String,
    val futureSection: String
)
