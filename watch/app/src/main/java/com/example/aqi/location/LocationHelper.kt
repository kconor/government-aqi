package com.example.aqi.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.example.aqi.AppLog
import com.example.aqi.data.SensorData
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import android.os.Build
import android.os.SystemClock
import kotlin.math.*

private val IS_EMULATOR = Build.FINGERPRINT.contains("generic") ||
    Build.FINGERPRINT.contains("emulator") ||
    Build.MODEL.contains("Emulator") ||
    Build.MODEL.contains("Android SDK") ||
    Build.DEVICE.contains("emu") ||
    Build.PRODUCT.startsWith("sdk")

class LocationHelper(private val context: Context) {
    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    companion object {
        private const val EARTH_RADIUS_KM = 6371.0

        /** Haversine distance in kilometers. */
        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLon / 2) * sin(dLon / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return EARTH_RADIUS_KM * c
        }
    }

    suspend fun getOptimizedLocation(cacheMaxAgeMinutes: Int): Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        if (IS_EMULATOR) {
            AppLog.d("LocationHelper", "Emulator detected, using Seattle as default location")
            return Location("emulator").apply {
                latitude = 47.6062
                longitude = -122.3321
            }
        }

        try {
            // 1. Try the free, cached location first
            val lastLocation = fusedLocationClient.lastLocation.await()
            if (lastLocation != null) {
                val ageNanos = SystemClock.elapsedRealtimeNanos() - lastLocation.elapsedRealtimeNanos
                val ageMinutes = ageNanos / 1_000_000_000L / 60L

                if (ageMinutes <= cacheMaxAgeMinutes) {
                    return lastLocation
                }
            }

            // 2. Try passive — free piggyback on other apps' location requests
            val passiveLocation = tryGetCurrentLocation(Priority.PRIORITY_PASSIVE)
            if (passiveLocation != null) return passiveLocation

            // 3. Fall back to low-power (cell tower / wifi)
            return tryGetCurrentLocation(Priority.PRIORITY_LOW_POWER)

        } catch (e: Exception) {
            AppLog.e("LocationHelper", "Failed to get optimized location.", e)
        }

        return null
    }

    private suspend fun tryGetCurrentLocation(priority: Int): Location? {
        val cts = CancellationTokenSource()
        return try {
            fusedLocationClient.getCurrentLocation(priority, cts.token).await()
        } finally {
            cts.cancel()
        }
    }

    fun findNearestSensorFromData(currentLocation: Location, sensors: List<SensorData>): SensorData? {
        if (sensors.isEmpty()) return null

        var nearest: SensorData? = null
        var minDistance = Double.MAX_VALUE

        val lat1 = currentLocation.latitude
        val lon1 = currentLocation.longitude

        for (sensor in sensors) {
            val distance = distanceKm(lat1, lon1, sensor.lat, sensor.lon)
            if (distance < minDistance) {
                minDistance = distance
                nearest = sensor
            }
        }

        return nearest
    }
}
