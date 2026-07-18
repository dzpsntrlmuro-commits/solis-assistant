package com.macsans.app.api

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Open-Meteo archive — geçmiş maç günü hava durumu (anahtar gerekmez).
 */
object HistoricalWeatherClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    data class DayWeather(
        val date: String,
        val tempMax: Double,
        val precipMm: Double,
        val windMax: Double,
        val hardCondition: Boolean
    )

    fun fetchDay(lat: Double, lon: Double, date: String): DayWeather? {
        if (lat == 0.0 && lon == 0.0) return null
        if (date.isBlank()) return null
        return try {
            val url =
                "https://archive-api.open-meteo.com/v1/archive?latitude=$lat&longitude=$lon" +
                    "&start_date=$date&end_date=$date" +
                    "&daily=temperature_2m_max,precipitation_sum,windspeed_10m_max&timezone=auto"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val daily = JSONObject(body).optJSONObject("daily") ?: return null
                val temp = daily.optJSONArray("temperature_2m_max")?.optDouble(0, 18.0) ?: 18.0
                val precip = daily.optJSONArray("precipitation_sum")?.optDouble(0, 0.0) ?: 0.0
                val wind = daily.optJSONArray("windspeed_10m_max")?.optDouble(0, 10.0) ?: 10.0
                DayWeather(
                    date = date,
                    tempMax = temp,
                    precipMm = precip,
                    windMax = wind,
                    hardCondition = precip >= 2.0 || wind >= 28.0 || temp <= 2.0 || temp >= 32.0
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
