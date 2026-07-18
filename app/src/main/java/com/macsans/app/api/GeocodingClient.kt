package com.macsans.app.api

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object GeocodingClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val cache = ConcurrentHashMap<String, Pair<Double, Double>>()

    fun lookup(city: String, country: String = ""): Pair<Double, Double> {
        val key = "$city|$country".lowercase()
        cache[key]?.let { return it }
        if (city.isBlank()) return 0.0 to 0.0
        return try {
            val q = URLEncoder.encode(city, "UTF-8")
            val url = "https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=tr&format=json"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return 0.0 to 0.0
                val body = response.body?.string() ?: return 0.0 to 0.0
                val results = JSONObject(body).optJSONArray("results") ?: return 0.0 to 0.0
                if (results.length() == 0) return 0.0 to 0.0
                val first = results.getJSONObject(0)
                val lat = first.optDouble("latitude", 0.0)
                val lon = first.optDouble("longitude", 0.0)
                (lat to lon).also { cache[key] = it }
            }
        } catch (_: Exception) {
            0.0 to 0.0
        }
    }
}
