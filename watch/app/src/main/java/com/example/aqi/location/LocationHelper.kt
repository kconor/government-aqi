package com.example.aqi.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.example.aqi.data.SensorData
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import android.os.SystemClock
import kotlin.math.*

class LocationHelper(private val context: Context) {
    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    suspend fun getOptimizedLocation(cacheMaxAgeMinutes: Int): Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        try {
            // 1. Try the free, cached location first
            val lastLocation = fusedLocationClient.lastLocation.await()
            if (lastLocation != null) {
                // Location.time is UTC time in milliseconds. Alternatively, elapsedRealtimeNanos is safer for relative time since boot.
                // We'll use elapsedRealtimeNanos to calculate exactly how old it is without clock skew issues.
                val ageNanos = SystemClock.elapsedRealtimeNanos() - lastLocation.elapsedRealtimeNanos
                val ageMinutes = ageNanos / 1_000_000_000L / 60L
                
                if (ageMinutes <= cacheMaxAgeMinutes) {
                    return lastLocation
                }
            }

            // 2. If null or too old, get a fresh location using LOW_POWER (cell tower / wifi level)
            val cancellationTokenSource = CancellationTokenSource()
            try {
                return fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_LOW_POWER,
                    cancellationTokenSource.token
                ).await()
            } finally {
                cancellationTokenSource.cancel()
            }

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun findNearestSensorFromData(currentLocation: Location, sensors: List<SensorData>): SensorData? {
        if (sensors.isEmpty()) return null

        var nearest: SensorData? = null
        var minDistance = Double.MAX_VALUE

        val lat1 = currentLocation.latitude
        val lon1 = currentLocation.longitude

        for (sensor in sensors) {
            val distance = haversine(lat1, lon1, sensor.lat, sensor.lon)
            if (distance < minDistance) {
                minDistance = distance
                nearest = sensor
            }
        }

        return nearest
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0 // Radius of earth in kilometers
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }
}
