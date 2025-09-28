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
import androidx.appcompat.app.AlertDialog
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
                // This will be overridden when updateCellIdSpinner is called
                // The actual logic is in updateCellIdSpinner()
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
        
        // Disable buildings layer for better heatmap visibility
        googleMap.isBuildingsEnabled = false
        
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
                
                // Enhanced logging for debugging
                Log.d(TAG, "Database query results:")
                Log.d(TAG, "- Total cell logs: ${allCellLogs.size}")
                Log.d(TAG, "- Unique cell IDs: ${allCellIds.size} (${allCellIds.joinToString(", ")})")
                
                // Log sample data for debugging
                allCellLogs.take(3).forEachIndexed { index, log ->
                    Log.d(TAG, "Sample log $index: lat=${log.lat}, lon=${log.lon}, rssi=${log.rssi}, cellId=${log.cellId}")
                }
                
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
                handler.post {
                    Toast.makeText(this@MapsActivity, "Error loading map data: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateCellIdSpinner() {
        val items = mutableListOf("All Cell IDs (${allCellIds.size} total)")
        
        // Limit the number of displayed cell IDs if there are too many
        val maxDisplayItems = 50
        val cellIdsToShow = if (allCellIds.size > maxDisplayItems) {
            // Show first 25, then a separator, then last 25
            val firstHalf = allCellIds.take(25)
            val lastHalf = allCellIds.takeLast(25)
            val separator = "... (${allCellIds.size - 50} more) ..."
            
            items.addAll(firstHalf)
            items.add(separator)
            items.addAll(lastHalf)
        } else {
            items.addAll(allCellIds)
        }
        
        // Create custom adapter for better handling of many items
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        cellIdSpinner.adapter = adapter
        
        // Handle selection of separator item
        cellIdSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selectedItem = items[position]
                
                // If separator is selected, keep previous selection
                if (selectedItem.contains("... (") && selectedItem.contains("more) ...")) {
                    // Don't change selection for separator
                    return
                }
                
                selectedCellId = if (position == 0 || selectedItem.startsWith("All Cell IDs")) {
                    null
                } else {
                    selectedItem
                }
                updateMapVisualization()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // Log the number of cell IDs for debugging
        Log.d(TAG, "Updated cell ID spinner with ${allCellIds.size} cell IDs")
        
        // If there are many cell IDs, show a toast to inform user
        if (allCellIds.size > maxDisplayItems) {
            handler.post {
                Toast.makeText(this@MapsActivity, 
                    "Found ${allCellIds.size} cell IDs. Showing first and last 25 for performance.", 
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateMapVisualization() {
        if (!::googleMap.isInitialized) {
            Log.w(TAG, "GoogleMap not initialized yet")
            return
        }
        
        Log.d(TAG, "Updating map visualization - Mode: $currentDisplayMode, Filter: $selectedCellId")
        
        // Clear existing visualizations
        clearMapElements()
        
        // Filter data based on selected cell ID
        val filteredLogs = if (selectedCellId != null) {
            allCellLogs.filter { it.cellId == selectedCellId }
        } else {
            allCellLogs
        }
        
        Log.d(TAG, "Filtered logs: ${filteredLogs.size} from ${allCellLogs.size} total logs")
        
        when (currentDisplayMode) {
            DisplayMode.HEATMAP -> {
                Log.d(TAG, "Creating heatmap visualization")
                createHeatmap(filteredLogs)
            }
            DisplayMode.PINS -> {
                Log.d(TAG, "Creating pin visualization")
                createPinMarkers(filteredLogs)
            }
        }
        
        // Auto-fit camera if we have data
        if (filteredLogs.isNotEmpty()) {
            fitCameraToData(filteredLogs)
        } else {
            Log.w(TAG, "No data to display on map")
            Toast.makeText(this@MapsActivity, "No data available to display", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createHeatmap(cellLogs: List<CellLog>) {
        Log.d(TAG, "Creating heatmap with ${cellLogs.size} cell logs")
        val heatmapData = mutableListOf<WeightedLatLng>()
        
        for (log in cellLogs) {
            if (log.lat == null || log.lon == null || log.rssi == null) {
                Log.d(TAG, "Skipping log with null data: lat=${log.lat}, lon=${log.lon}, rssi=${log.rssi}")
                continue
            }
            
            // Convert RSSI to intensity (stronger signal = higher intensity)
            // RSSI typically ranges from -120 to -20 dBm
            val normalizedIntensity = maxOf(0.1, minOf(1.0, (log.rssi + 120) / 100.0))
            
            Log.d(TAG, "Adding heatmap point: lat=${log.lat}, lon=${log.lon}, rssi=${log.rssi}, intensity=$normalizedIntensity")
            
            heatmapData.add(
                WeightedLatLng(
                    LatLng(log.lat, log.lon),
                    normalizedIntensity
                )
            )
        }
        
        Log.d(TAG, "Prepared ${heatmapData.size} heatmap points")
        
        if (heatmapData.isNotEmpty()) {
            try {
                val heatmapProvider = HeatmapTileProvider.Builder()
                    .weightedData(heatmapData)
                    .radius(50) // Radius in pixels
                    .maxIntensity(10.0) // Lower max intensity for better visibility
                    .opacity(0.8) // Make it more visible
                    .build()
                
                heatmapTileOverlay = googleMap.addTileOverlay(
                    TileOverlayOptions()
                        .tileProvider(heatmapProvider)
                        .zIndex(1000f) // High z-index to display above buildings and other overlays
                )
                
                Log.d(TAG, "Successfully created heatmap with ${heatmapData.size} points (z-index: 1000)")
                Toast.makeText(this@MapsActivity, "Heatmap created with ${heatmapData.size} points", Toast.LENGTH_SHORT).show()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error creating heatmap: ${e.message}", e)
                Toast.makeText(this@MapsActivity, "Error creating heatmap: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Log.w(TAG, "No valid data points for heatmap")
            Toast.makeText(this@MapsActivity, "No data available for heatmap", Toast.LENGTH_SHORT).show()
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
        menu?.add(0, 2, 0, "Add Sample Data")?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu?.add(0, 3, 0, "Toggle Buildings")?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu?.add(0, 4, 0, "Clear All Logs")?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
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
            2 -> {
                // Add sample data for testing
                addSampleData()
                true
            }
            3 -> {
                // Toggle buildings visibility
                googleMap.isBuildingsEnabled = !googleMap.isBuildingsEnabled
                val status = if (googleMap.isBuildingsEnabled) "enabled" else "disabled"
                Toast.makeText(this, "Buildings $status", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Buildings layer $status")
                true
            }
            4 -> {
                // Clear all logs with confirmation
                clearAllLogsWithConfirmation()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun addSampleData() {
        Log.d(TAG, "Adding sample data for testing")
        
        backgroundExecutor.execute {
            try {
                val currentTime = System.currentTimeMillis()
                val sampleData = listOf(
                    CellLog(
                        timestamp = currentTime,
                        lat = TOKYO_LAT + 0.01,
                        lon = TOKYO_LON + 0.01,
                        type = "LTE",
                        rssi = -70,
                        cellId = "TEST_CELL_1"
                    ),
                    CellLog(
                        timestamp = currentTime,
                        lat = TOKYO_LAT + 0.02,
                        lon = TOKYO_LON + 0.02,
                        type = "5G",
                        rssi = -50,
                        cellId = "TEST_CELL_2"
                    ),
                    CellLog(
                        timestamp = currentTime,
                        lat = TOKYO_LAT - 0.01,
                        lon = TOKYO_LON - 0.01,
                        type = "LTE",
                        rssi = -90,
                        cellId = "TEST_CELL_1"
                    ),
                    CellLog(
                        timestamp = currentTime,
                        lat = TOKYO_LAT - 0.02,
                        lon = TOKYO_LON + 0.03,
                        type = "5G",
                        rssi = -45,
                        cellId = "TEST_CELL_3"
                    )
                )
                
                cellDatabase.insertCellLogs(sampleData)
                Log.d(TAG, "Sample data inserted successfully")
                
                handler.post {
                    Toast.makeText(this@MapsActivity, "Sample data added for testing", Toast.LENGTH_SHORT).show()
                    updateMapData() // Refresh the map
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error adding sample data: ${e.message}", e)
                handler.post {
                    Toast.makeText(this@MapsActivity, "Error adding sample data: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun clearAllLogsWithConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Clear All Logs")
            .setMessage("Are you sure you want to delete all cell tower logs? This action cannot be undone.")
            .setPositiveButton("Clear All") { _, _ ->
                clearAllLogs()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearAllLogs() {
        Log.d(TAG, "Clearing all logs...")
        
        backgroundExecutor.execute {
            try {
                cellDatabase.clearAllLogs()
                
                handler.post {
                    Toast.makeText(this@MapsActivity, "All logs cleared successfully", Toast.LENGTH_SHORT).show()
                    
                    // Reset data and refresh map
                    allCellLogs = emptyList()
                    allCellIds = emptyList()
                    updateCellIdSpinner()
                    updateMapVisualization()
                    
                    Log.d(TAG, "All logs cleared and map refreshed")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing logs: ${e.message}", e)
                handler.post {
                    Toast.makeText(this@MapsActivity, "Error clearing logs: ${e.message}", Toast.LENGTH_LONG).show()
                }
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