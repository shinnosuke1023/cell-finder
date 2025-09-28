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
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.maps.android.heatmaps.HeatmapTileProvider
import com.google.maps.android.heatmaps.WeightedLatLng
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
    
    // UI Components
    private lateinit var cellIdSpinner: Spinner
    private lateinit var displayModeSpinner: Spinner
    private lateinit var refreshButton: Button
    
    // Map elements
    private val cellMarkers = mutableListOf<Marker>()
    private val baseStationMarkers = mutableListOf<Marker>()
    private val debugCircles = mutableListOf<Circle>()
    private var heatmapTileOverlay: TileOverlay? = null
    
    // Data and state
    private var allCellLogs = listOf<CellLog>()
    private var allCellIds = listOf<String>()
    private var currentDisplayMode = DisplayMode.HEATMAP
    private var selectedCellId: String? = null
    
    enum class DisplayMode(val displayName: String) {
        HEATMAP("Heatmap"),
        PINS("Pins")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MapsActivity onCreate")
        
        setContentView(R.layout.activity_maps)
        
        // Initialize database
        cellDatabase = CellDatabase(this)
        
        // Initialize UI components
        initializeUI()
        
        // Set up the map fragment
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
        
        // Set up action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Cell Tower Heatmap"
    }

    private fun initializeUI() {
        cellIdSpinner = findViewById(R.id.cellIdSpinner)
        displayModeSpinner = findViewById(R.id.displayModeSpinner)
        refreshButton = findViewById(R.id.refreshButton)
        
        // Setup display mode spinner
        val displayModes = DisplayMode.values().map { it.displayName }
        val displayModeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayModes)
        displayModeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        displayModeSpinner.adapter = displayModeAdapter
        
        displayModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                currentDisplayMode = DisplayMode.values()[position]
                updateMapVisualization()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // Setup cell ID spinner listener
        cellIdSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectedCellId = if (position == 0) null else allCellIds[position - 1]
                updateMapVisualization()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // Setup refresh button
        refreshButton.setOnClickListener {
            updateMapData()
        }
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
                // Get recent cell logs
                allCellLogs = cellDatabase.getRecentCellLogs(60) // Last 60 minutes
                val cellLogsMap = cellDatabase.getCellLogsGroupedByCell(60)
                
                // Get unique cell IDs
                allCellIds = allCellLogs.mapNotNull { it.cellId }.distinct().sorted()
                
                // Estimate base station positions
                val estimatedPositions = BaseStationEstimator.estimateBaseStationPositions(
                    cellLogsMap,
                    pathLossExponent = 2.0,
                    refRssiDbm = -40.0,
                    refDistM = 1.0,
                    bandwidthM = 150.0,
                    useIntersectionMethod = true
                )
                
                Log.d(TAG, "Found ${allCellLogs.size} recent logs, ${allCellIds.size} cell IDs, and ${estimatedPositions.size} estimated positions")
                
                // Update UI on main thread
                handler.post {
                    updateCellIdSpinner()
                    updateMapVisualization()
                    updateBaseStationMarkers(estimatedPositions)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error updating map data: ${e.message}", e)
            }
        }
    }

    private fun updateCellIdSpinner() {
        val items = mutableListOf("All Cell IDs")
        items.addAll(allCellIds)
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        cellIdSpinner.adapter = adapter
    }

    private fun updateMapVisualization() {
        if (!::googleMap.isInitialized) return
        
        Log.d(TAG, "Updating map visualization - Mode: $currentDisplayMode, Filter: $selectedCellId")
        
        // Clear existing visualizations
        clearMapElements()
        
        // Filter data based on selected cell ID
        val filteredLogs = if (selectedCellId != null) {
            allCellLogs.filter { it.cellId == selectedCellId }
        } else {
            allCellLogs
        }
        
        when (currentDisplayMode) {
            DisplayMode.HEATMAP -> createHeatmap(filteredLogs)
            DisplayMode.PINS -> createPinMarkers(filteredLogs)
        }
        
        // Auto-fit camera if we have data
        if (filteredLogs.isNotEmpty()) {
            fitCameraToData(filteredLogs)
        }
    }

    private fun createHeatmap(cellLogs: List<CellLog>) {
        val heatmapData = mutableListOf<WeightedLatLng>()
        
        for (log in cellLogs) {
            if (log.lat == null || log.lon == null || log.rssi == null) continue
            
            // Convert RSSI to intensity (stronger signal = higher intensity)
            // RSSI typically ranges from -120 to -20 dBm
            val normalizedIntensity = maxOf(0.1, minOf(1.0, (log.rssi + 120) / 100.0))
            
            heatmapData.add(
                WeightedLatLng(
                    LatLng(log.lat, log.lon),
                    normalizedIntensity
                )
            )
        }
        
        if (heatmapData.isNotEmpty()) {
            val heatmapProvider = HeatmapTileProvider.Builder()
                .weightedData(heatmapData)
                .radius(50) // Radius in pixels
                .maxIntensity(1000.0)
                .build()
            
            heatmapTileOverlay = googleMap.addTileOverlay(
                TileOverlayOptions().tileProvider(heatmapProvider)
            )
            
            Log.d(TAG, "Created heatmap with ${heatmapData.size} points")
        }
    }

    private fun createPinMarkers(cellLogs: List<CellLog>) {
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
        Log.d(TAG, "Created ${cellMarkers.size} pin markers")
    }

    private fun updateBaseStationMarkers(estimatedPositions: List<EstimatedBaseStation>) {
        // Clear existing base station markers
        baseStationMarkers.forEach { it.remove() }
        baseStationMarkers.clear()
        
        for (baseStation in estimatedPositions) {
            if (baseStation.lat == null || baseStation.lon == null) continue
            
            // Only show base stations for the selected cell ID if filtering is active
            if (selectedCellId != null && baseStation.cellId != selectedCellId) continue
            
            val marker = googleMap.addMarker(
                MarkerOptions()
                    .position(LatLng(baseStation.lat, baseStation.lon))
                    .title("Estimated Base Station")
                    .snippet("Cell ID: ${baseStation.cellId}\nType: ${baseStation.type ?: "Unknown"}\nObservations: ${baseStation.count}")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
            )
            
            marker?.let { baseStationMarkers.add(it) }
        }
        Log.d(TAG, "Updated ${baseStationMarkers.size} base station markers")
    }

    private fun clearMapElements() {
        // Clear pin markers
        cellMarkers.forEach { it.remove() }
        cellMarkers.clear()
        
        // Clear heatmap
        heatmapTileOverlay?.remove()
        heatmapTileOverlay = null
        
        // Clear debug circles
        debugCircles.forEach { it.remove() }
        debugCircles.clear()
    }

    private fun fitCameraToData(cellLogs: List<CellLog>) {
        val validLogs = cellLogs.filter { it.lat != null && it.lon != null }
        if (validLogs.isEmpty()) return
        
        val builder = LatLngBounds.Builder()
        for (log in validLogs) {
            builder.include(LatLng(log.lat!!, log.lon!!))
        }
        
        // Also include base station markers
        for (marker in baseStationMarkers) {
            builder.include(marker.position)
        }
        
        try {
            val bounds = builder.build()
            val padding = 100 // pixels
            googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
            Log.d(TAG, "Camera fitted to ${validLogs.size} data points")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fit camera to data: ${e.message}")
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1, 0, "Toggle Debug Circles")?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            1 -> {
                // Debug circles functionality can be implemented if needed
                Log.d(TAG, "Debug circles toggle requested")
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "MapsActivity onDestroy")
        handler.removeCallbacksAndMessages(null)
        backgroundExecutor.shutdown()
        super.onDestroy()
    }
}