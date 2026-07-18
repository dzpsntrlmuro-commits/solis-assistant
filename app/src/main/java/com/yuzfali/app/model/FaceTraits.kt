package com.yuzfali.app.model

data class FaceTraits(
    val smile: Float,
    val leftEyeOpen: Float,
    val rightEyeOpen: Float,
    val headYaw: Float,
    val headPitch: Float,
    val faceWidthRatio: Float
) {
    val moodSeed: Int
        get() = ((smile * 100).toInt() * 31 +
            (leftEyeOpen * 100).toInt() * 17 +
            (rightEyeOpen * 100).toInt() * 13 +
            (headYaw * 10).toInt() * 7 +
            (headPitch * 10).toInt() * 5 +
            (faceWidthRatio * 100).toInt() * 3).and(0x7fffffff)
}

data class FortuneScript(
    val headline: String,
    val paragraphs: List<String>
) {
    val fullText: String
        get() = paragraphs.joinToString(" ")
}
