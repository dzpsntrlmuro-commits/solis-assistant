package com.yuzfali.app.data

import android.content.Context
import com.yuzfali.app.model.FaceFingerprint
import com.yuzfali.app.model.FaceMatchResult
import com.yuzfali.app.model.FaceProfile
import com.yuzfali.app.model.FortuneReport
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class FaceProfileStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun findMatch(fingerprint: FaceFingerprint, scanQuality: Float): FaceMatchResult {
        val profiles = loadProfiles()
        if (profiles.isEmpty()) {
            return FaceMatchResult(null, 0f, false)
        }
        if (scanQuality < MIN_SCAN_QUALITY) {
            return FaceMatchResult(null, 0f, false)
        }

        val ranked = profiles
            .map { profile -> profile to fingerprint.similarityTo(profile.fingerprint) }
            .sortedByDescending { it.second }

        val best = ranked.first()
        val second = ranked.getOrNull(1)
        val similarityGap = best.second - (second?.second ?: 0f)

        val isConfident = best.second >= MIN_SIMILARITY &&
            similarityGap >= MIN_SIMILARITY_GAP &&
            best.first.fingerprint.features.size == fingerprint.features.size

        return FaceMatchResult(
            profile = if (isConfident) best.first else null,
            similarity = best.second,
            isConfidentMatch = isConfident
        )
    }

    fun saveProfile(fingerprint: FaceFingerprint, report: FortuneReport): FaceProfile {
        val profiles = loadProfiles().toMutableList()
        val profile = FaceProfile(
            id = UUID.randomUUID().toString(),
            displayName = "Kişi ${profiles.size + 1}",
            fingerprint = fingerprint,
            report = report
        )
        profiles.add(profile)
        persist(profiles)
        return profile
    }

    private fun loadProfiles(): List<FaceProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    add(parseProfile(array.getJSONObject(i)))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persist(profiles: List<FaceProfile>) {
        val array = JSONArray()
        profiles.forEach { array.put(serializeProfile(it)) }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    private fun serializeProfile(profile: FaceProfile): JSONObject = JSONObject().apply {
        put("id", profile.id)
        put("displayName", profile.displayName)
        put("fingerprint", JSONArray(profile.fingerprint.features.toList()))
        put("faceSection", profile.report.faceSection)
        put("postureSection", profile.report.postureSection)
        put("emotionSection", profile.report.emotionSection)
        put("futureSection", profile.report.futureSection)
        put("fullSpeech", profile.report.fullSpeech)
    }

    private fun parseProfile(json: JSONObject): FaceProfile {
        val fpArray = json.getJSONArray("fingerprint")
        val features = FloatArray(fpArray.length()) { i -> fpArray.getDouble(i).toFloat() }
        return FaceProfile(
            id = json.getString("id"),
            displayName = json.getString("displayName"),
            fingerprint = FaceFingerprint(features),
            report = FortuneReport(
                faceSection = json.getString("faceSection"),
                postureSection = json.getString("postureSection"),
                emotionSection = json.getString("emotionSection"),
                futureSection = json.getString("futureSection"),
                fullSpeech = json.getString("fullSpeech")
            )
        )
    }

    companion object {
        private const val PREFS_NAME = "yuzfali_face_profiles"
        private const val KEY_PROFILES = "profiles"
        private const val MIN_SIMILARITY = 0.985f
        private const val MIN_SIMILARITY_GAP = 0.002f
        // Quality only gates matching confidence, never blocks fortune generation.
        private const val MIN_SCAN_QUALITY = 0.25f
    }
}
