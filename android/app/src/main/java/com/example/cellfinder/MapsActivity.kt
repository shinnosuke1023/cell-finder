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
import com.google.maps.android.heatmaps.Gradient
import com.google.maps.android.heatmaps.HeatmapTileProvider
import com.google.maps.android.heatmaps.WeightedLatLng
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.cos

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
    private val rssiCircles = mutableListOf<Circle>()
    private var heatmapTileOverlay: TileOverlay? = null
    
    // Data and state
    private var allCellLogs = listOf<CellLog>()
    private var allCellIds = listOf<String>()
    private var currentDisplayMode = DisplayMode.RSSI_CIRCLES
    private var selectedCellId: String? = null
    
    enum class DisplayMode(val displayName: String) {
        RSSI_CIRCLES("RSSI Circles"),
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
                val selectedItem = parent?.getItemAtPosition(position)?.toString() ?: ""
                
                selectedCellId = if (position == 0 || selectedItem.startsWith("All Cell IDs")) {
                    null
                } else {
                    selectedItem
                }
                
                Log.d(TAG, "Cell ID filter changed to: $selectedCellId")
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
        Log.d(TAG, "updateCellIdSpinner called with ${allCellIds.size} cell IDs")
        
        val items = mutableListOf("All Cell IDs")
        
        // Add count to the first item
        if (allCellIds.isNotEmpty()) {
            items[0] = "All Cell IDs (${allCellIds.size} total)"
        }
        
        // Simply add all cell IDs for now - we'll handle performance differently
        items.addAll(allCellIds)
        
        Log.d(TAG, "Spinner items: ${items.take(5)}${if (items.size > 5) "... (${items.size} total)" else ""}")
        
        try {
            // Create adapter with standard layouts
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            
            // Store current selection to preserve it
            val currentSelection = cellIdSpinner.selectedItemPosition
            
            // Update adapter
            cellIdSpinner.adapter = adapter
            
            // Restore selection if valid
            if (currentSelection < items.size && currentSelection >= 0) {
                cellIdSpinner.setSelection(currentSelection)
                Log.d(TAG, "Restored spinner selection to position $currentSelection")
            }
            
            Log.d(TAG, "Successfully updated cell ID spinner with ${items.size} items")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating cell ID spinner: ${e.message}", e)
            Toast.makeText(this, "Error updating cell ID filter: ${e.message}", Toast.LENGTH_LONG).show()
        }
        
        // If there are many cell IDs, show a toast to inform user
        if (allCellIds.size > 50) {
            handler.post {
                Toast.makeText(this@MapsActivity, 
                    "Found ${allCellIds.size} cell IDs. Dropdown may be long to scroll.", 
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
            DisplayMode.RSSI_CIRCLES -> {
                Log.d(TAG, "Creating RSSI circles visualization")
                createRssiCircles(filteredLogs)
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
        Log.d(TAG, "Creating RSSI-based heatmap with ${cellLogs.size} cell logs")
        
        // First, analyze the RSSI range for debugging
        val rssiValues = cellLogs.mapNotNull { it.rssi }
        if (rssiValues.isEmpty()) {
            Log.w(TAG, "No valid RSSI values found")
            Toast.makeText(this@MapsActivity, "No valid RSSI data for heatmap", Toast.LENGTH_SHORT).show()
            return
        }
        
        val minRssi = rssiValues.minOrNull() ?: -120
        val maxRssi = rssiValues.maxOrNull() ?: -20
        Log.d(TAG, "RSSI range in data: ${minRssi}dBm to ${maxRssi}dBm")
        
        // Step 1: Grid-based bucketing to suppress density effects
        val gridSizeMeters = 25.0
        val gridMap = mutableMapOf<String, CellLog>()
        
        // Create proper geographic grid buckets
        for (log in cellLogs) {
            if (log.lat == null || log.lon == null || log.rssi == null) {
                Log.d(TAG, "Skipping log with null data: lat=${log.lat}, lon=${log.lon}, rssi=${log.rssi}")
                continue
            }
            
            // Proper geographic grid calculation
            // 1 degree latitude ≈ 111,320 meters
            // 1 degree longitude ≈ 111,320 * cos(latitude) meters
            val latDegreesPerGrid = gridSizeMeters / 111320.0
            val lonDegreesPerGrid = gridSizeMeters / (111320.0 * cos(Math.toRadians(log.lat)))
            
            // Calculate grid indices (floor to create proper grid cells)
            val gridLatIndex = floor(log.lat / latDegreesPerGrid).toInt()
            val gridLonIndex = floor(log.lon / lonDegreesPerGrid).toInt()
            val gridKey = "${gridLatIndex}_${gridLonIndex}"
            
            // Keep only the strongest RSSI in each grid cell
            val existingLog = gridMap[gridKey]
            if (existingLog == null || log.rssi > existingLog.rssi!!) {
                gridMap[gridKey] = log
            }
        }
        
        Log.d(TAG, "Grid bucketing: ${cellLogs.size} original points -> ${gridMap.size} grid representatives")
        
        // Step 2: Convert representative points to WeightedLatLng with RSSI-based weights
        val heatmapData = mutableListOf<WeightedLatLng>()
        val rssiSamples = mutableListOf<Pair<Int, Double>>()
        
        for ((gridKey, log) in gridMap) {
            // RSSI to weight mapping: [-120, -20] -> [0.0, 1.0] with minimum 0.1
            val rssiMin = -120.0
            val rssiMax = -20.0
            val rssiRange = rssiMax - rssiMin  // 100.0
            val normalizedWeight = (log.rssi!! - rssiMin) / rssiRange
            val clampedWeight = maxOf(0.1, minOf(1.0, normalizedWeight))
            
            heatmapData.add(
                WeightedLatLng(
                    LatLng(log.lat!!, log.lon!!),
                    clampedWeight
                )
            )
            
            // Store samples for logging
            if (rssiSamples.size < 5) {
                rssiSamples.add(Pair(log.rssi, clampedWeight))
            }
        }
        
        Log.d(TAG, "Heatmap data prepared: ${heatmapData.size} weighted points")
        Log.d(TAG, "RSSI->Weight samples: ${rssiSamples.joinToString { "(${it.first}dBm -> ${String.format("%.2f", it.second)})" }}")
        
        if (heatmapData.isNotEmpty()) {
            try {
                // Step 3: Create custom gradient (blue -> cyan -> green -> yellow -> red)
                val colors = intArrayOf(
                    Color.rgb(0, 0, 255),      // Blue (weak signal)
                    Color.rgb(0, 255, 255),    // Cyan
                    Color.rgb(0, 255, 0),      // Green
                    Color.rgb(255, 255, 0),    // Yellow
                    Color.rgb(255, 0, 0)       // Red (strong signal)
                )
                val startPoints = floatArrayOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)
                val gradient = Gradient(colors, startPoints)
                
                // Step 4: Configure HeatmapTileProvider with optimized parameters
                val heatmapProvider = HeatmapTileProvider.Builder()
                    .weightedData(heatmapData)
                    .radius(35) // Reduced radius to prevent over-spreading
                    .maxIntensity(1.0) // Match our weight range to prevent summing effects
                    .opacity(0.75) // Balanced opacity
                    .gradient(gradient) // Custom RSSI-based gradient
                    .build()
                
                heatmapTileOverlay = googleMap.addTileOverlay(
                    TileOverlayOptions()
                        .tileProvider(heatmapProvider)
                        .zIndex(1000f) // High z-index to display above buildings
                )
                
                Log.d(TAG, "Successfully created RSSI-based heatmap:")
                Log.d(TAG, "- Grid buckets: ${gridMap.size}")
                Log.d(TAG, "- Heatmap points: ${heatmapData.size}")
                Log.d(TAG, "- Data RSSI range: ${minRssi}dBm to ${maxRssi}dBm")
                Log.d(TAG, "- Mapping range: -120dBm to -20dBm")
                Log.d(TAG, "- Gradient: Blue(-120dBm) -> Red(-20dBm)")
                
                Toast.makeText(this@MapsActivity, 
                    "RSSI Heatmap: ${heatmapData.size} points (${minRssi} to ${maxRssi} dBm)", 
                    Toast.LENGTH_SHORT).show()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error creating RSSI-based heatmap: ${e.message}", e)
                Toast.makeText(this@MapsActivity, "Error creating heatmap: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Log.w(TAG, "No valid data points for RSSI-based heatmap")
            Toast.makeText(this@MapsActivity, "No valid data for heatmap", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createRssiCircles(cellLogs: List<CellLog>) {
        Log.d(TAG, "Creating RSSI circles with ${cellLogs.size} cell logs")
        
        // Use same grid-based approach as heatmap
        val gridSizeMeters = 25.0
        val gridMap = mutableMapOf<String, CellLog>()
        
        // Create grid buckets and keep only the strongest RSSI per grid
        for (log in cellLogs) {
            if (log.lat == null || log.lon == null || log.rssi == null) continue
            
            // Same corrected grid calculation as heatmap
            val latDegreesPerGrid = gridSizeMeters / 111320.0
            val lonDegreesPerGrid = gridSizeMeters / (111320.0 * cos(Math.toRadians(log.lat)))
            
            val gridLatIndex = floor(log.lat / latDegreesPerGrid).toInt()
            val gridLonIndex = floor(log.lon / lonDegreesPerGrid).toInt()
            val gridKey = "${gridLatIndex}_${gridLonIndex}"
            
            val existingLog = gridMap[gridKey]
            if (existingLog == null || log.rssi > existingLog.rssi!!) {
                gridMap[gridKey] = log
            }
        }
        
        Log.d(TAG, "RSSI circles: ${cellLogs.size} original points -> ${gridMap.size} grid representatives")
        
        val rssiSamples = mutableListOf<Pair<Int, Int>>()
        
        for ((gridKey, log) in gridMap) {
            // RSSI to color mapping
            val rssiColor = rssiToColor(log.rssi!!)
            
            val circle = googleMap.addCircle(
                CircleOptions()
                    .center(LatLng(log.lat!!, log.lon!!))
                    .radius(15.0) // Fixed radius in meters
                    .fillColor(rssiColor)
                    .strokeColor(Color.argb(200, 0, 0, 0)) // Black border
                    .strokeWidth(1f)
            )
            
            rssiCircles.add(circle)
            
            // Store samples for logging
            if (rssiSamples.size < 5) {
                rssiSamples.add(Pair(log.rssi, rssiColor))
            }
        }
        
        val strongestRssi = gridMap.values.maxOfOrNull { it.rssi!! } ?: -120
        val weakestRssi = gridMap.values.minOfOrNull { it.rssi!! } ?: -120
        
        Log.d(TAG, "Successfully created ${rssiCircles.size} RSSI circles")
        Log.d(TAG, "RSSI->Color samples: ${rssiSamples.joinToString { "(${it.first}dBm)" }}")
        Log.d(TAG, "RSSI range: ${weakestRssi}dBm to ${strongestRssi}dBm")
        
        Toast.makeText(this@MapsActivity, 
            "RSSI Circles: ${rssiCircles.size} points (${weakestRssi} to ${strongestRssi} dBm)", 
            Toast.LENGTH_SHORT).show()
    }
    
    private fun rssiToColor(rssi: Int): Int {
        // RSSI to color mapping: [-120, -20] -> Blue to Red
        val rssiRange = -20.0 - (-120.0)  // 100.0
        val normalizedRssi = maxOf(0.0, minOf(1.0, (rssi - (-120.0)) / rssiRange))
        
        return when {
            normalizedRssi <= 0.25 -> {
                // Blue to Cyan
                val factor = normalizedRssi / 0.25
                Color.argb(150, 
                    0, 
                    (factor * 255).toInt(), 
                    255)
            }
            normalizedRssi <= 0.5 -> {
                // Cyan to Green
                val factor = (normalizedRssi - 0.25) / 0.25
                Color.argb(150, 
                    0, 
                    255, 
                    (255 * (1 - factor)).toInt())
            }
            normalizedRssi <= 0.75 -> {
                // Green to Yellow
                val factor = (normalizedRssi - 0.5) / 0.25
                Color.argb(150, 
                    (factor * 255).toInt(), 
                    255, 
                    0)
            }
            else -> {
                // Yellow to Red
                val factor = (normalizedRssi - 0.75) / 0.25
                Color.argb(150, 
                    255, 
                    (255 * (1 - factor)).toInt(), 
                    0)
            }
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
        Log.d(TAG, "Clearing map elements...")
        
        // Clear pin markers
        val pinCount = cellMarkers.size
        cellMarkers.forEach { it.remove() }
        cellMarkers.clear()
        
        // Clear heatmap overlay
        val hadHeatmap = heatmapTileOverlay != null
        heatmapTileOverlay?.remove()
        heatmapTileOverlay = null
        
        // Clear RSSI circles
        val circleCount = rssiCircles.size
        rssiCircles.forEach { it.remove() }
        rssiCircles.clear()
        
        // Clear debug circles
        val debugCount = debugCircles.size
        debugCircles.forEach { it.remove() }
        debugCircles.clear()
        
        Log.d(TAG, "Cleared: $pinCount pins, heatmap: $hadHeatmap, $circleCount RSSI circles, $debugCount debug circles")
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
                val deletedCount = cellDatabase.clearAllLogs()
                
                handler.post {
                    Toast.makeText(this@MapsActivity, "Cleared $deletedCount log records successfully", Toast.LENGTH_SHORT).show()
                    
                    // Reset data and refresh map
                    allCellLogs = emptyList()
                    allCellIds = emptyList()
                    updateCellIdSpinner()
                    updateMapVisualization()
                    
                    Log.d(TAG, "All logs cleared ($deletedCount records) and map refreshed")
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