package com.mackandall.jdvr.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** What the sky is doing, for the Home backdrop and its label. */
enum class Sky { Clear, PartlyCloudy, Cloudy, Fog, Rain, Snow, Storm }

data class WeatherNow(
    val temperature: Int,
    val high: Int?,
    val low: Int?,
    val sky: Sky,
    val isDay: Boolean,
    val label: String,
    // The next few hours (Home's weather tile).
    val hours: List<WeatherHour> = emptyList(),
)

data class WeatherHour(
    val time: java.time.LocalDateTime,
    val temperature: Int,
    val sky: Sky,
    val label: String,
)

data class Place(
    val name: String,
    val latitude: Double,
    val longitude: Double,
)

/**
 * Weather for the Home screen from Open-Meteo (free, no account or key). Only the chosen town's
 * coordinates are sent, on a client of its own (nothing from the Jellyfin session).
 */
object Weather {
    private val http = OkHttpClient.Builder().build()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class GeoResults(
        val results: List<GeoResult> = emptyList(),
    )

    @Serializable
    private data class GeoResult(
        val name: String = "",
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
        val admin1: String? = null,
        @SerialName("country_code") val countryCode: String? = null,
    )

    @Serializable
    private data class Forecast(
        val current: Current? = null,
        val daily: Daily? = null,
        val hourly: Hourly? = null,
    )

    @Serializable
    private data class Hourly(
        val time: List<String> = emptyList(),
        @SerialName("temperature_2m") val temperature: List<Double> = emptyList(),
        @SerialName("weather_code") val code: List<Int> = emptyList(),
    )

    @Serializable
    private data class Current(
        @SerialName("temperature_2m") val temperature: Double = 0.0,
        @SerialName("weather_code") val code: Int = 0,
        @SerialName("is_day") val isDay: Int = 1,
    )

    @Serializable
    private data class Daily(
        @SerialName("temperature_2m_max") val max: List<Double> = emptyList(),
        @SerialName("temperature_2m_min") val min: List<Double> = emptyList(),
    )

    private suspend fun get(url: okhttp3.HttpUrl): String = withContext(Dispatchers.IO) { http.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() } }

    /** A town by name ("Atlanta", "Springfield, IL"): its best match. */
    suspend fun find(name: String): Place? {
        val url =
            "https://geocoding-api.open-meteo.com/v1/search"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("name", name.substringBefore(',').trim())
                .addQueryParameter("count", "10")
                .build()
        val results = json.decodeFromString<GeoResults>(get(url)).results
        // "Springfield, IL": prefer the one whose state / region starts with what follows the comma.
        val hint = name.substringAfter(',', "").trim().lowercase()
        val best = results.firstOrNull { hint.isNotEmpty() && (it.admin1?.lowercase()?.startsWith(hint) == true || it.countryCode?.lowercase() == hint) } ?: results.firstOrNull()
        return best?.let { Place(listOfNotNull(it.name, it.admin1).joinToString(", "), it.latitude, it.longitude) }
    }

    suspend fun now(
        place: Place,
        fahrenheit: Boolean,
    ): WeatherNow {
        val url =
            "https://api.open-meteo.com/v1/forecast"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("latitude", place.latitude.toString())
                .addQueryParameter("longitude", place.longitude.toString())
                .addQueryParameter("current", "temperature_2m,weather_code,is_day")
                .addQueryParameter("daily", "temperature_2m_max,temperature_2m_min")
                .addQueryParameter("hourly", "temperature_2m,weather_code")
                .addQueryParameter("forecast_hours", "6")
                .addQueryParameter("temperature_unit", if (fahrenheit) "fahrenheit" else "celsius")
                .addQueryParameter("timezone", "auto")
                .addQueryParameter("forecast_days", "1")
                .build()
        val f = json.decodeFromString<Forecast>(get(url))
        val c = f.current ?: error("No current weather")
        val (sky, label) = describe(c.code)
        return WeatherNow(
            temperature = Math.round(c.temperature).toInt(),
            high =
                f.daily
                    ?.max
                    ?.firstOrNull()
                    ?.let { Math.round(it).toInt() },
            low =
                f.daily
                    ?.min
                    ?.firstOrNull()
                    ?.let { Math.round(it).toInt() },
            sky = sky,
            isDay = c.isDay == 1,
            label = label,
            // Times are the town's own (timezone=auto): the hours after this one.
            hours =
                f.hourly
                    ?.let { h ->
                        h.time.indices
                            .mapNotNull { i ->
                                val t = runCatching { java.time.LocalDateTime.parse(h.time[i]) }.getOrNull() ?: return@mapNotNull null
                                val (s, l) = describe(h.code.getOrElse(i) { 3 })
                                WeatherHour(t, Math.round(h.temperature.getOrElse(i) { 0.0 }).toInt(), s, l)
                            }.drop(1)
                            .take(4)
                    }.orEmpty(),
        )
    }

    // WMO weather codes, as Open-Meteo reports them.
    private fun describe(code: Int): Pair<Sky, String> =
        when (code) {
            0 -> Sky.Clear to "Clear"
            1 -> Sky.Clear to "Mostly clear"
            2 -> Sky.PartlyCloudy to "Partly cloudy"
            3 -> Sky.Cloudy to "Cloudy"
            45, 48 -> Sky.Fog to "Fog"
            51, 53, 55, 56, 57 -> Sky.Rain to "Drizzle"
            61, 63, 66, 80, 81 -> Sky.Rain to "Rain"
            65, 67, 82 -> Sky.Rain to "Heavy rain"
            71, 73, 77, 85 -> Sky.Snow to "Snow"
            75, 86 -> Sky.Snow to "Heavy snow"
            95 -> Sky.Storm to "Thunderstorm"
            96, 99 -> Sky.Storm to "Thunderstorm, hail"
            else -> Sky.Cloudy to "Cloudy"
        }
}
