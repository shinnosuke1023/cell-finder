package com.example.cellfinder

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import android.util.Log

/**
 * ViewModel for the map screen following MVVM architecture.
 * Observes tracking state from TrackingService and exposes it to the UI.
 */
class MapViewModel : ViewModel() {
    companion object {
        private const val TAG = "MapViewModel"
    }
    
    // LiveData for tracking state
    private val _trackingState = MutableLiveData<TrackingState?>()
    val trackingState: LiveData<TrackingState?> = _trackingState
    
    // LiveData for user trajectory
    private val _userTrajectory = MutableLiveData<List<Pair<Double, Double>>>(emptyList())
    val userTrajectory: LiveData<List<Pair<Double, Double>>> = _userTrajectory
    
    // LiveData for tracking status
    private val _isTracking = MutableLiveData<Boolean>(false)
    val isTracking: LiveData<Boolean> = _isTracking
    
    // Observer reference for proper cleanup
    private val trackingObserver: androidx.lifecycle.Observer<TrackingState> = androidx.lifecycle.Observer { state ->
        onTrackingStateUpdate(state)
    }
    
    init {
        Log.d(TAG, "MapViewModel initialized")
        
        // Observe tracking service LiveData
        TrackingService.estimatedPositionLiveData.observeForever(trackingObserver)
    }
    
    private fun onTrackingStateUpdate(state: TrackingState?) {
        Log.d(TAG, "Tracking state updated: $state")
        
        if (state != null) {
            _trackingState.value = state
            _isTracking.value = true
            
            // Add user position to trajectory
            addToTrajectory(state.userLatitude, state.userLongitude)
        } else {
            _isTracking.value = false
        }
    }
    
    private fun addToTrajectory(lat: Double, lon: Double) {
        val currentTrajectory = _userTrajectory.value ?: emptyList()
        
        // Avoid adding duplicate consecutive points
        if (currentTrajectory.isNotEmpty()) {
            val (lastLat, lastLon) = currentTrajectory.last()
            val distance = calculateDistance(lastLat, lastLon, lat, lon)
            
            // Only add if moved more than 5 meters
            if (distance < 5.0) {
                return
            }
        }
        
        // Limit trajectory to last 100 points
        val newTrajectory = (currentTrajectory + Pair(lat, lon)).takeLast(100)
        _userTrajectory.value = newTrajectory
        
        Log.d(TAG, "Trajectory updated: ${newTrajectory.size} points")
    }
    
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadius * c
    }
    
    fun clearTrajectory() {
        _userTrajectory.value = emptyList()
        Log.d(TAG, "Trajectory cleared")
    }
    
    override fun onCleared() {
        super.onCleared()
        // Remove observer to prevent memory leak
        TrackingService.estimatedPositionLiveData.removeObserver(trackingObserver)
        Log.d(TAG, "MapViewModel cleared")
    }
}
