package com.apkatolye.app.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps everything the user says, plus a living "brief" so the assistant
 * stays focused like a persistent coding agent.
 */
class ConversationMemory(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Turn(val role: String, val text: String, val atMs: Long = System.currentTimeMillis())

    private val turns = mutableListOf<Turn>()
    private val rawNotes = mutableListOf<String>()
    var brief: String = ""
        private set
    var lastPlan: String = ""
        private set

    init {
        brief = prefs.getString(KEY_BRIEF, "").orEmpty()
        lastPlan = prefs.getString(KEY_PLAN, "").orEmpty()
        prefs.getString(KEY_NOTES, "")
            ?.split("\n•••\n")
            ?.filter { it.isNotBlank() }
            ?.let { rawNotes.addAll(it) }
    }

    fun addUser(text: String) {
        turns += Turn("user", text)
        rawNotes += text.trim()
        if (rawNotes.size > 80) {
            rawNotes.subList(0, rawNotes.size - 80).clear()
        }
        persistNotes()
    }

    fun addAssistant(text: String) {
        turns += Turn("assistant", text)
        if (turns.size > 60) {
            turns.subList(0, turns.size - 60).clear()
        }
    }

    fun updateBrief(newBrief: String) {
        if (newBrief.isBlank()) return
        brief = newBrief.trim()
        prefs.edit().putString(KEY_BRIEF, brief).apply()
    }

    fun updatePlan(plan: String) {
        if (plan.isBlank()) return
        lastPlan = plan.trim()
        prefs.edit().putString(KEY_PLAN, lastPlan).apply()
    }

    fun allUserNotes(): String = rawNotes.joinToString("\n- ", prefix = "- ")

    fun recentTranscript(limit: Int = 16): String = buildString {
        turns.takeLast(limit).forEach { t ->
            val who = if (t.role == "user") "Kullanıcı" else "Asistan"
            appendLine("$who: ${t.text}")
        }
    }

    fun clearSessionKeepBrief() {
        turns.clear()
        // keep brief + notes for continuity across sessions
    }

    fun resetAll() {
        turns.clear()
        rawNotes.clear()
        brief = ""
        lastPlan = ""
        prefs.edit().clear().apply()
    }

    private fun persistNotes() {
        prefs.edit().putString(KEY_NOTES, rawNotes.joinToString("\n•••\n")).apply()
    }

    companion object {
        private const val PREFS = "apk_atolye_memory"
        private const val KEY_BRIEF = "brief"
        private const val KEY_PLAN = "plan"
        private const val KEY_NOTES = "notes"
    }
}

data class AgentDecision(
    val message: String,
    val brief: String = "",
    val plan: String = "",
    val patches: List<CodePatch> = emptyList(),
    val localActions: List<String> = emptyList(), // extract, rebuild, open_files, open_test, pick_image
    val focusPaths: List<String> = emptyList(),
    val needsApiKey: Boolean = false,
    val listenMore: Boolean = false
)
