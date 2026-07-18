package com.macsans.app.api

import com.macsans.app.engine.PredictionEngine
import com.macsans.app.model.WeatherInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object WeatherClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    fun fetch(city: String, lat: Double, lon: Double): WeatherInfo {
        return try {
            val url =
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,relative_humidity_2m,precipitation,weather_code,wind_speed_10m" +
                    "&timezone=auto"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return fallback(city)
                val body = response.body?.string() ?: return fallback(city)
                val json = JSONObject(body).getJSONObject("current")
                val temp = json.optDouble("temperature_2m", 18.0)
                val humidity = json.optInt("relative_humidity_2m", 55)
                val precip = json.optDouble("precipitation", 0.0)
                val wind = json.optDouble("wind_speed_10m", 10.0)
                val code = json.optInt("weather_code", 0)
                val condition = conditionFromCode(code)
                val impact = PredictionEngine.weatherImpact(temp, wind, precip, condition)
                WeatherInfo(
                    city = city,
                    temperatureC = temp,
                    humidity = humidity,
                    windKmh = wind,
                    precipitationMm = precip,
                    condition = condition,
                    impactScore = impact.first,
                    impactNote = impact.second
                )
            }
        } catch (_: Exception) {
            fallback(city)
        }
    }

    private fun conditionFromCode(code: Int): String = when (code) {
        0 -> "Açık"
        1, 2 -> "Az bulutlu"
        3 -> "Bulutlu"
        45, 48 -> "Sisli"
        51, 53, 55 -> "Çiseleyen"
        61, 63, 65 -> "Yağmurlu"
        66, 67 -> "Dondurucu yağmur"
        71, 73, 75, 77 -> "Karlı"
        80, 81, 82 -> "Sağanak"
        95, 96, 99 -> "Fırtınalı"
        else -> "Değişken"
    }

    private fun fallback(city: String): WeatherInfo {
        val impact = PredictionEngine.weatherImpact(17.0, 14.0, 0.2, "Parçalı bulutlu")
        return WeatherInfo(
            city = city,
            temperatureC = 17.0,
            humidity = 58,
            windKmh = 14.0,
            precipitationMm = 0.2,
            condition = "Parçalı bulutlu",
            impactScore = impact.first,
            impactNote = impact.second + " (tahmini veri)"
        )
    }
}
