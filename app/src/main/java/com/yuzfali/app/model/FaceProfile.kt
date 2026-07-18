package com.yuzfali.app.model

data class FaceFingerprint(
    val features: FloatArray
) {
    fun similarityTo(other: FaceFingerprint): Float {
        if (features.size != other.features.size || features.isEmpty()) return 0f
        val normA = normalize(features)
        val normB = normalize(other.features)
        var dot = 0f
        for (i in normA.indices) {
            dot += normA[i] * normB[i]
        }
        return dot.coerceIn(0f, 1f)
    }

    fun distanceTo(other: FaceFingerprint): Float {
        if (features.size != other.features.size || features.isEmpty()) return Float.MAX_VALUE
        var sum = 0f
        for (i in features.indices) {
            val diff = features[i] - other.features[i]
            sum += diff * diff
        }
        return kotlin.math.sqrt(sum / features.size)
    }

    private fun normalize(values: FloatArray): FloatArray {
        var sumSq = 0f
        for (v in values) sumSq += v * v
        if (sumSq <= 0f) return values
        val inv = 1f / kotlin.math.sqrt(sumSq)
        return FloatArray(values.size) { i -> values[i] * inv }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceFingerprint) return false
        return features.contentEquals(other.features)
    }

    override fun hashCode(): Int = features.contentHashCode()
}

data class FaceProfile(
    val id: String,
    val displayName: String,
    val fingerprint: FaceFingerprint,
    val report: FortuneReport
)

data class FaceMatchResult(
    val profile: FaceProfile?,
    val similarity: Float,
    val isConfidentMatch: Boolean
)
