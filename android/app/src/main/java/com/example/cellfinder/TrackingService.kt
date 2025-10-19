package com.example.cellfinder

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*

/**
 * Foreground service that tracks a fake base station using EKF.
 * Runs continuously in the background and updates the estimated position.
 */
class TrackingService : Service() {
    companion object {
        private const val TAG = "TrackingService"
        private const val CHANNEL_ID = "tracking_channel"
        private const val NOTIFICATION_ID = 2
        private const val UPDATE_INTERVAL_MS = 2000L  // 2 seconds as specified
        
        // LiveData for state updates
        val estimatedPositionLiveData = MutableLiveData<TrackingState>()
    }
    
    private lateinit var ekfEngine: EKFEngine
    private lateinit var locationProvider: LocationProvider
    private lateinit var cellInfoProvider: CellInfoProvider
    
    private var serviceScope: CoroutineScope? = null
    private var trackingJob: Job? = null
    
    private var lastUtmZone: Int = 54  // Default for Tokyo
    private var lastHemisphere: Char = 'N'
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "TrackingService onCreate()")
        
        // Initialize components
        ekfEngine = EKFEngine()
        locationProvider = LocationProvider(this)
        cellInfoProvider = CellInfoProvider(this)
        
        // Create coroutine scope
        serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        
        // Create notification channel
        createNotificationChannel()
        
        // Start foreground
        startForeground(NOTIFICATION_ID, buildNotification())
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "TrackingService onStartCommand()")
        
        // Start tracking loop
        startTracking()
        
        return START_STICKY
    }
    
    private fun startTracking() {
        Log.d(TAG, "Starting tracking loop")
        
        // Cancel any existing job
        trackingJob?.cancel()
        
        // Start new tracking job
        trackingJob = serviceScope?.launch {
            while (isActive) {
                try {
                    performTrackingStep()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in tracking step: ${e.message}", e)
                }
                
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }
    
    private suspend fun performTrackingStep() {
        Log.d(TAG, "Performing tracking step")
        
        // Get current location
        val location = locationProvider.getCurrentLocation() ?: run {
            Log.w(TAG, "Location not available")
            return
        }
        
        // Get target cell info (2G cell)
        val cellInfo = cellInfoProvider.getTargetCell() ?: run {
            Log.w(TAG, "No target cell available")
            return
        }
        
        Log.d(TAG, "Location: ${location.latitude}, ${location.longitude}")
        Log.d(TAG, "Cell: ${cellInfo.cellId}, RSSI: ${cellInfo.rssi} dBm, Type: ${cellInfo.type}")
        
        // Convert location to UTM
        val userUtm = UtmConverter.latLonToUtm(location.latitude, location.longitude)
        
        // Store zone and hemisphere for later conversion back
        lastUtmZone = userUtm.zone
        lastHemisphere = userUtm.hemisphere
        
        // Initialize EKF on first measurement
        if (!ekfEngine.isInitialized()) {
            Log.i(TAG, "Initializing EKF with first measurement")
            ekfEngine.initialize(userUtm)
        }
        
        // Perform EKF step
        ekfEngine.step(userUtm, cellInfo.rssi.toDouble())
        
        // Get estimated position
        val estimatedUtm = ekfEngine.getEstimatedPositionUtm()
        val errorRadius = ekfEngine.getErrorRadius()
        val (p0, eta) = ekfEngine.getPathLossParameters()
        
        if (estimatedUtm != null) {
            // Convert back to lat/lon
            val estimatedUtmWithZone = UtmCoord(
                estimatedUtm.x,
                estimatedUtm.y,
                lastUtmZone,
                lastHemisphere
            )
            val (estLat, estLon) = UtmConverter.utmToLatLon(estimatedUtmWithZone)
            
            // Create tracking state
            val state = TrackingState(
                estimatedLatitude = estLat,
                estimatedLongitude = estLon,
                errorRadiusMeters = errorRadius,
                userLatitude = location.latitude,
                userLongitude = location.longitude,
                cellId = cellInfo.cellId,
                rssi = cellInfo.rssi,
                cellType = cellInfo.type,
                pathLossExponent = eta,
                referencePower = p0,
                measurementCount = 0  // Could track this if needed
            )
            
            Log.d(TAG, "Estimated position: ($estLat, $estLon), error radius: $errorRadius m")
            Log.d(TAG, "Path loss parameters: P0=$p0 dBm, eta=$eta")
            
            // Update LiveData
            estimatedPositionLiveData.postValue(state)
        }
    }
    
    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Base Station Tracker")
            .setContentText("Tracking base station location...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .build()
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Base Station Tracking",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Tracking fake base station location"
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
    
    override fun onDestroy() {
        Log.i(TAG, "TrackingService onDestroy()")
        
        // Cancel tracking job
        trackingJob?.cancel()
        
        // Cancel scope
        serviceScope?.cancel()
        
        super.onDestroy()
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}

/**
 * Data class representing the tracking state.
 */
data class TrackingState(
    val estimatedLatitude: Double,
    val estimatedLongitude: Double,
    val errorRadiusMeters: Double,
    val userLatitude: Double,
    val userLongitude: Double,
    val cellId: String,
    val rssi: Int,
    val cellType: String,
    val pathLossExponent: Double,
    val referencePower: Double,
    val measurementCount: Int
)
