package com.yuzfali.app.model

data class FaceFingerprint(
    val features: FloatArray
) {
    fun distanceTo(other: FaceFingerprint): Float {
        if (features.size != other.features.size || features.isEmpty()) return Float.MAX_VALUE
        var sum = 0f
        for (i in features.indices) {
            val diff = features[i] - other.features[i]
            sum += diff * diff
        }
        return kotlin.math.sqrt(sum / features.size)
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
