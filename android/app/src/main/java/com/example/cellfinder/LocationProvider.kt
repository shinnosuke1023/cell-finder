package com.example.cellfinder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wrapper class for FusedLocationProviderClient that provides location updates.
 */
class LocationProvider(private val context: Context) {
    companion object {
        private const val TAG = "LocationProvider"
    }
    
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    
    /**
     * Get the last known location.
     * 
     * @return Last known location or null if unavailable
     */
    suspend fun getLastLocation(): Location? {
        if (!checkPermissions()) {
            Log.w(TAG, "Location permission not granted")
            return null
        }
        
        return suspendCancellableCoroutine { continuation ->
            try {
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { location ->
                        if (continuation.isActive) {
                            continuation.resume(location)
                        }
                    }
                    .addOnFailureListener { exception ->
                        if (continuation.isActive) {
                            Log.e(TAG, "Failed to get location: ${exception.message}")
                            continuation.resume(null)
                        }
                    }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception getting location: ${e.message}")
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }
    }
    
    /**
     * Get the current location with high accuracy.
     * 
     * @return Current location or null if unavailable
     */
    suspend fun getCurrentLocation(): Location? {
        if (!checkPermissions()) {
            Log.w(TAG, "Location permission not granted")
            return null
        }
        
        return suspendCancellableCoroutine { continuation ->
            try {
                val cancellationTokenSource = CancellationTokenSource()
                
                continuation.invokeOnCancellation {
                    cancellationTokenSource.cancel()
                }
                
                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cancellationTokenSource.token
                )
                    .addOnSuccessListener { location ->
                        if (continuation.isActive) {
                            continuation.resume(location)
                        }
                    }
                    .addOnFailureListener { exception ->
                        if (continuation.isActive) {
                            Log.e(TAG, "Failed to get current location: ${exception.message}")
                            continuation.resume(null)
                        }
                    }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception getting current location: ${e.message}")
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }
    }
    
    /**
     * Check if location permissions are granted.
     */
    private fun checkPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
