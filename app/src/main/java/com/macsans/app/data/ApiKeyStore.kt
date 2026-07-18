package com.macsans.app.data

import android.content.Context

object ApiKeyStore {
    private const val PREFS = "macsans_prefs"
    private const val KEY_API = "api_football_key"

    fun get(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_API, "")
            ?.trim()
            .orEmpty()
    }

    fun save(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API, key.trim())
            .apply()
    }

    fun hasKey(context: Context): Boolean = get(context).isNotBlank()
}
