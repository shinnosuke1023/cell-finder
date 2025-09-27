package com.example.cellfinder

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import java.util.concurrent.Executors

class MapsActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        private const val TAG = "MapsActivity"
        private const val UPDATE_INTERVAL_MS = 30000L // 30 seconds
        private const val DEFAULT_ZOOM = 12f
        private const val TOKYO_LAT = 35.6762
        private const val TOKYO_LON = 139.6503
    }

    private lateinit var googleMap: GoogleMap
    private lateinit var cellDatabase: CellDatabase
    private val handler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    
    private val cellMarkers = mutableListOf<Marker>()
    private val baseStationMarkers = mutableListOf<Marker>()
    private val debugCircles = mutableListOf<Circle>()
    
    private var showDebugCircles = false
    private var currentFilter = MapFilter.DEFAULT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MapsActivity onCreate")
        
        setContentView(R.layout.activity_maps)
        
        // Initialize database
        cellDatabase = CellDatabase(this)
        
        // Set up the map fragment
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
        
        // Set up action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Cell Tower Map"
    }

    override fun onMapReady(map: GoogleMap) {
        Log.d(TAG, "Google Map ready")
        googleMap = map
        
        // Configure map
        googleMap.uiSettings.isZoomControlsEnabled = true
        googleMap.uiSettings.isMyLocationButtonEnabled = true
        
        // Enable location if permitted
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == 
            PackageManager.PERMISSION_GRANTED) {
            googleMap.isMyLocationEnabled = true
        }
        
        // Set initial camera position (Tokyo as default)
        val initialPosition = LatLng(TOKYO_LAT, TOKYO_LON)
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(initialPosition, DEFAULT_ZOOM))
        
        // Start periodic updates
        updateMapData()
        startPeriodicUpdates()
    }

    private fun startPeriodicUpdates() {
        val updateRunnable = object : Runnable {
            override fun run() {
                updateMapData()
                handler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        }
        handler.post(updateRunnable)
        Log.d(TAG, "Started periodic map updates every ${UPDATE_INTERVAL_MS}ms")
    }

    private fun updateMapData() {
        Log.d(TAG, "Updating map data...")
        
        backgroundExecutor.execute {
            try {
                // Get filtered cell logs based on current filter
                val filteredLogs = cellDatabase.getFilteredCellLogs(currentFilter)
                val cellLogsMap = cellDatabase.getFilteredCellLogsGroupedByCell(currentFilter)
                
                // Estimate base station positions
                val estimatedPositions = BaseStationEstimator.estimateBaseStationPositions(
                    cellLogsMap,
                    pathLossExponent = 2.0,
                    refRssiDbm = -40.0,
                    refDistM = 1.0,
                    bandwidthM = 150.0,
                    useIntersectionMethod = true
                )
                
                Log.d(TAG, "Found ${filteredLogs.size} filtered logs and ${estimatedPositions.size} estimated positions")
                
                // Update UI on main thread
                handler.post {
                    updateMapMarkers(filteredLogs, estimatedPositions, cellLogsMap)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error updating map data: ${e.message}", e)
            }
        }
    }

    private fun updateMapMarkers(
        cellLogs: List<CellLog>,
        estimatedPositions: List<EstimatedBaseStation>,
        cellLogsMap: Map<String, List<CellLog>>
    ) {
        Log.d(TAG, "Updating map markers")
        
        // Clear existing markers and circles
        clearMapElements()
        
        // Add cell tower observation markers (blue) if enabled in filter
        if (currentFilter.showCellObservations) {
            addCellObservationMarkers(cellLogs)
        }
        
        // Add estimated base station markers (red) if enabled in filter
        if (currentFilter.showBaseStations) {
            addBaseStationMarkers(estimatedPositions)
        }
        
        // Add debug circles if enabled in filter
        if (currentFilter.showDebugCircles) {
            addDebugCircles(cellLogsMap)
        }
        
        // Auto-fit camera to show all markers if we have data
        if (cellMarkers.isNotEmpty() || baseStationMarkers.isNotEmpty()) {
            fitCameraToMarkers()
        }
    }

    private fun clearMapElements() {
        cellMarkers.forEach { it.remove() }
        cellMarkers.clear()
        
        baseStationMarkers.forEach { it.remove() }
        baseStationMarkers.clear()
        
        debugCircles.forEach { it.remove() }
        debugCircles.clear()
    }

    private fun addCellObservationMarkers(cellLogs: List<CellLog>) {
        for (log in cellLogs) {
            if (log.lat == null || log.lon == null) continue
            
            val marker = googleMap.addMarker(
                MarkerOptions()
                    .position(LatLng(log.lat, log.lon))
                    .title("Cell Observation")
                    .snippet("Type: ${log.type ?: "Unknown"}\nRSSI: ${log.rssi ?: "N/A"} dBm\nCell ID: ${log.cellId ?: "Unknown"}")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
            )
            
            marker?.let { cellMarkers.add(it) }
        }
        Log.d(TAG, "Added ${cellMarkers.size} cell observation markers")
    }

    private fun addBaseStationMarkers(estimatedPositions: List<EstimatedBaseStation>) {
        for (baseStation in estimatedPositions) {
            if (baseStation.lat == null || baseStation.lon == null) continue
            
            val marker = googleMap.addMarker(
                MarkerOptions()
                    .position(LatLng(baseStation.lat, baseStation.lon))
                    .title("Estimated Base Station")
                    .snippet("Cell ID: ${baseStation.cellId}\nType: ${baseStation.type ?: "Unknown"}\nObservations: ${baseStation.count}")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
            )
            
            marker?.let { baseStationMarkers.add(it) }
        }
        Log.d(TAG, "Added ${baseStationMarkers.size} base station markers")
    }

    private fun addDebugCircles(cellLogsMap: Map<String, List<CellLog>>) {
        Log.d(TAG, "Adding debug circles")
        
        for ((_, logs) in cellLogsMap) {
            for (log in logs) {
                if (log.lat == null || log.lon == null || log.rssi == null) continue
                
                // Calculate distance from RSSI (same formula as in BaseStationEstimator)
                val rssiDbm = maxOf(minOf(log.rssi.toDouble(), -20.0), -140.0)
                val refRssiDbm = -40.0
                val refDistM = 1.0
                val pathLossExponent = 2.0
                val n = maxOf(pathLossExponent, 0.1)
                val distanceM = refDistM * Math.pow(10.0, (refRssiDbm - rssiDbm) / (10.0 * n))
                val clippedDistance = maxOf(1.0, minOf(distanceM, 50_000.0))
                
                val circle = googleMap.addCircle(
                    CircleOptions()
                        .center(LatLng(log.lat, log.lon))
                        .radius(clippedDistance)
                        .strokeColor(Color.argb(100, 128, 128, 128))
                        .strokeWidth(1f)
                        .fillColor(Color.TRANSPARENT)
                )
                
                debugCircles.add(circle)
            }
        }
        Log.d(TAG, "Added ${debugCircles.size} debug circles")
    }

    private fun fitCameraToMarkers() {
        val allMarkers = cellMarkers + baseStationMarkers
        if (allMarkers.isEmpty()) return
        
        val builder = LatLngBounds.Builder()
        for (marker in allMarkers) {
            builder.include(marker.position)
        }
        
        try {
            val bounds = builder.build()
            val padding = 100 // pixels
            googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
            Log.d(TAG, "Camera fitted to ${allMarkers.size} markers")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fit camera to markers: ${e.message}")
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1, 0, "Toggle Debug Circles")?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu?.add(0, 2, 0, "Refresh Data")?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu?.add(0, 3, 0, "Filter Settings")?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            1 -> {
                // Toggle debug circles through filter
                currentFilter = currentFilter.copy(showDebugCircles = !currentFilter.showDebugCircles)
                updateMapData()
                Log.d(TAG, "Debug circles toggled: ${currentFilter.showDebugCircles}")
                true
            }
            2 -> {
                updateMapData()
                Log.d(TAG, "Manual data refresh triggered")
                true
            }
            3 -> {
                showFilterDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showFilterDialog() {
        backgroundExecutor.execute {
            try {
                val availableCellTypes = cellDatabase.getAvailableCellTypes()
                
                handler.post {
                    val dialog = MapFilterDialog(
                        context = this,
                        currentFilter = currentFilter,
                        availableCellTypes = availableCellTypes
                    ) { newFilter ->
                        currentFilter = newFilter
                        updateMapData()
                        Log.d(TAG, "Filter updated: $newFilter")
                    }
                    dialog.show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading cell types for filter dialog: ${e.message}", e)
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "MapsActivity onDestroy")
        handler.removeCallbacksAndMessages(null)
        backgroundExecutor.shutdown()
        super.onDestroy()
    }
}