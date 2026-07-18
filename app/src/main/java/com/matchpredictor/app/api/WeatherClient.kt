package com.matchpredictor.app.api

import com.matchpredictor.app.data.models.WeatherInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WeatherClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun fetchWeatherForLocation(city: String, country: String, venue: String): WeatherInfo? {
        val query = listOf(city, country, venue)
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: return null

        val coords = geocode(query) ?: return null
        return fetchForecast(coords.first, coords.second)
    }

    private fun geocode(name: String): Pair<Double, Double>? {
        val encoded = java.net.URLEncoder.encode(name, "UTF-8")
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=tr&format=json"
        val json = fetchJson(url) ?: return null
        val results = json.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val first = results.getJSONObject(0)
        return first.getDouble("latitude") to first.getDouble("longitude")
    }

    private fun fetchForecast(lat: Double, lon: Double): WeatherInfo? {
        val url = "https://api.open-meteo.com/v1/forecast?" +
            "latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,precipitation,wind_speed_10m,weather_code" +
            "&timezone=auto"
        val json = fetchJson(url) ?: return null
        val current = json.optJSONObject("current") ?: return null

        val temp = current.optDouble("temperature_2m", 15.0)
        val humidity = current.optInt("relative_humidity_2m", 50)
        val precipitation = current.optDouble("precipitation", 0.0)
        val wind = current.optDouble("wind_speed_10m", 0.0)
        val code = current.optInt("weather_code", 0)
        val description = weatherDescription(code)
        val (impact, explanation) = calculateWeatherImpact(temp, precipitation, wind, humidity)

        return WeatherInfo(
            temperature = temp,
            humidity = humidity,
            precipitation = precipitation,
            windSpeed = wind,
            weatherCode = code,
            description = description,
            impactScore = impact,
            impactExplanation = explanation
        )
    }

    private fun weatherDescription(code: Int): String = when (code) {
        0 -> "Açık hava"
        1, 2, 3 -> "Parçalı bulutlu"
        45, 48 -> "Sisli"
        51, 53, 55 -> "Çisenti"
        61, 63, 65 -> "Yağmurlu"
        71, 73, 75 -> "Karlı"
        80, 81, 82 -> "Sağanak yağış"
        95, 96, 99 -> "Fırtınalı"
        else -> "Değişken hava"
    }

    private fun calculateWeatherImpact(
        temp: Double,
        precipitation: Double,
        wind: Double,
        humidity: Int
    ): Pair<Double, String> {
        var impact = 0.0
        val notes = mutableListOf<String>()

        when {
            precipitation > 5 -> { impact -= 0.15; notes.add("Yoğun yağış top oyununu zorlaştırır, beraberlik olasılığını artırır") }
            precipitation > 1 -> { impact -= 0.08; notes.add("Yağışlı hava kaygan zemine ve pas hatalarına yol açar") }
        }
        when {
            wind > 40 -> { impact -= 0.12; notes.add("Şiddetli rüzgar uzun pasları ve duran top oyununu etkiler") }
            wind > 25 -> { impact -= 0.06; notes.add("Güçlü rüzgar oyun planlarını değiştirebilir") }
        }
        when {
            temp > 35 -> { impact -= 0.10; notes.add("Aşırı sıcak fiziksel dayanıklılığı düşürür, ikinci yarıda yorgunluk artar") }
            temp < 0 -> { impact -= 0.08; notes.add("Donma noktası altı sıcaklık top kontrolünü zorlaştırır") }
        }
        if (humidity > 85) {
            impact -= 0.05
            notes.add("Yüksek nem oyuncu performansını olumsuz etkileyebilir")
        }
        if (notes.isEmpty()) {
            notes.add("Hava koşulları normal, oyun akışını önemli ölçüde etkilemez")
        }
        return impact to notes.joinToString(". ")
    }

    private fun fetchJson(url: String): JSONObject? {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            return JSONObject(body)
        }
    }
}
