package com.example.aqi.data

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

// Models to match the minified Cloudflare Worker JSON output

@Keep
data class MasterDataPayload(
    @SerializedName("t") val timestamp: Long,
    @SerializedName("s") val sensors: List<SensorData>
)

@Keep
data class SensorData(
    @SerializedName("n") val name: String,
    @SerializedName("la") val lat: Double,
    @SerializedName("lo") val lon: Double,
    @SerializedName("A") val primaryAqi: Int?, // Make it nullable just in case it's missing
    @SerializedName("C") val category: String?,
    @SerializedName("t") val timestamp: Long, // UTC epoch seconds
    @SerializedName("m") val metrics: Map<String, Int>
)

// Forecast models

@Keep
data class ForecastPayload(
    @SerializedName("t") val timestamp: Long,
    @SerializedName("s") val locations: List<ForecastLocation>
)

@Keep
data class ForecastLocation(
    @SerializedName("n") val name: String,
    @SerializedName("la") val lat: Double,
    @SerializedName("lo") val lon: Double,
    @SerializedName("f") val forecasts: List<ForecastDay>
)

@Keep
data class ForecastDay(
    @SerializedName("d") val date: String,       // "YYYY-MM-DD"
    @SerializedName("A") val aqiValue: Int?,
    @SerializedName("C") val category: String?,
    @SerializedName("p") val parameter: String?,
    @SerializedName("a") val actionDay: Boolean?
)
