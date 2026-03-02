package com.example.aqi.data

import com.google.gson.annotations.SerializedName

// Models to match the minified Cloudflare Worker JSON output

data class MasterDataPayload(
    @SerializedName("t") val timestamp: Long,
    @SerializedName("s") val sensors: List<SensorData>
)

data class SensorData(
    @SerializedName("n") val name: String,
    @SerializedName("la") val lat: Double,
    @SerializedName("lo") val lon: Double,
    @SerializedName("A") val primaryAqi: Int?, // Make it nullable just in case it's missing
    @SerializedName("C") val category: String?,
    @SerializedName("t") val timestamp: Long, // UTC epoch seconds
    @SerializedName("m") val metrics: Map<String, Int>
)
