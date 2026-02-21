package com.example.cellfinder

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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
        private const val UPDATE_INTERVAL_MS = 5000L // 5 seconds
        private const val DEFAULT_ZOOM = 12f
        private const val TOKYO_LAT = 35.6762
        private const val TOKYO_LON = 139.6503
    }

    private lateinit var googleMap: GoogleMap
    private lateinit var cellDatabase: CellDatabase
    private val handler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    
    // ViewModel for EKF tracking
    private lateinit var mapViewModel: MapViewModel
    
    // UI Components
    private lateinit var cellIdSpinner: Spinner
    private lateinit var displayModeSpinner: Spinner
    private lateinit var refreshButton: Button
    private lateinit var stopServicesButton: Button
    private lateinit var currentCellInfoTextView: TextView
    
    // Map elements
    private val cellMarkers = mutableListOf<Marker>()
    private val baseStationMarkers = mutableListOf<Marker>()
    private val debugCircles = mutableListOf<Circle>()
    private val rssiCircles = mutableListOf<Circle>()
    private var heatmapTileOverlay: TileOverlay? = null
    
    // EKF tracking map elements
    private var ekfBaseStationMarker: Marker? = null
    private var ekfErrorCircle: Circle? = null
    private var ekfUserMarker: Marker? = null
    private var ekfTrajectoryPolyline: Polyline? = null
    
    // Circle metadata for click handling
    private val circleMetadata = mutableMapOf<String, CellLog>()
    
    // Data and state
    private var allCellLogs = listOf<CellLog>()
    private var allCellIds = listOf<String>()
    private var currentDisplayMode = DisplayMode.RSSI_CIRCLES
    private var selectedCellId: String? = null
    // Follow location state
    private lateinit var followLocationButton: Button
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var isFollowingLocation = false
    private var initialLocationDone = false
    private var locationCallback: LocationCallback? = null
    
    // Service running state
    private var servicesRunning = true
    private var isGsmAlertShowing = false
    private var hasShownScrollHint = false

    private val gsmAlertReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CellFinderService.ACTION_GSM_DETECTED) {
                Log.w(TAG, "GSM detected broadcast received in MapsActivity")
                showGsmAlertDialog()
            }
        }
    }
    
    enum class DisplayMode(val displayName: String) {
        RSSI_CIRCLES("RSSI サークル"),
        HEATMAP("ヒートマップ"),
        PINS("ピン"),
        EKF_TRACKING("EKF トラッキング")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MapsActivity onCreate")
        
        setContentView(R.layout.activity_maps)
        
        // Initialize database
        cellDatabase = CellDatabase(this)
        
        // Initialize ViewModel
        mapViewModel = androidx.lifecycle.ViewModelProvider(this)[MapViewModel::class.java]
        
        // Initialize UI components
        initializeUI()
        
        // Set up the map fragment
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
        
        // Set up action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.map_title)
        
        // Observe ViewModel for EKF tracking updates
        observeViewModel()
    }
    
    private fun observeViewModel() {
        // Observe tracking state for EKF updates
        mapViewModel.trackingState.observe(this) { state ->
            if (currentDisplayMode == DisplayMode.EKF_TRACKING && state != null) {
                updateEkfVisualization(state)
            }
        }
        
        // Observe trajectory
        mapViewModel.userTrajectory.observe(this) { trajectory ->
            if (currentDisplayMode == DisplayMode.EKF_TRACKING) {
                updateEkfTrajectory(trajectory)
            }
        }
    }
    
    private fun updateEkfVisualization(state: TrackingState) {
        if (!::googleMap.isInitialized) return
        
        Log.d(TAG, "Updating EKF visualization: estimated=(${state.estimatedLatitude}, ${state.estimatedLongitude})")
        
        // Update base station marker
        val baseStationPos = LatLng(state.estimatedLatitude, state.estimatedLongitude)
        
        if (ekfBaseStationMarker == null) {
            ekfBaseStationMarker = googleMap.addMarker(
                MarkerOptions()
                    .position(baseStationPos)
                    .title("EKF推定基地局")
                    .snippet("セルID: ${state.cellId}\n誤差: ${String.format("%.1f", state.errorRadiusMeters)}m\nP0: ${String.format("%.1f", state.referencePower)} dBm\nη: ${String.format("%.2f", state.pathLossExponent)}")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
            )
        } else {
            ekfBaseStationMarker?.position = baseStationPos
            ekfBaseStationMarker?.snippet = "セルID: ${state.cellId}\n誤差: ${String.format("%.1f", state.errorRadiusMeters)}m\nP0: ${String.format("%.1f", state.referencePower)} dBm\nη: ${String.format("%.2f", state.pathLossExponent)}"
        }
        
        // Update error circle
        if (ekfErrorCircle == null) {
            ekfErrorCircle = googleMap.addCircle(
                CircleOptions()
                    .center(baseStationPos)
                    .radius(state.errorRadiusMeters)
                    .strokeColor(Color.argb(180, 255, 165, 0))  // Orange
                    .strokeWidth(3f)
                    .fillColor(Color.argb(50, 255, 165, 0))
            )
        } else {
            ekfErrorCircle?.center = baseStationPos
            ekfErrorCircle?.radius = state.errorRadiusMeters
        }
        
        // Update user marker
        val userPos = LatLng(state.userLatitude, state.userLongitude)
        
        if (ekfUserMarker == null) {
            ekfUserMarker = googleMap.addMarker(
                MarkerOptions()
                    .position(userPos)
                    .title("現在地")
                    .snippet("RSSI: ${state.rssi} dBm\nセル: ${state.cellType}")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
            )
        } else {
            ekfUserMarker?.position = userPos
            ekfUserMarker?.snippet = "RSSI: ${state.rssi} dBm\nセル: ${state.cellType}"
        }
        
        // Don't auto-fit camera to preserve user's view
    }
    
    private fun updateEkfTrajectory(trajectory: List<Pair<Double, Double>>) {
        if (!::googleMap.isInitialized) return
        
        if (trajectory.isEmpty()) {
            ekfTrajectoryPolyline?.remove()
            ekfTrajectoryPolyline = null
            return
        }
        
        val points = trajectory.map { (lat, lon) -> LatLng(lat, lon) }
        
        if (ekfTrajectoryPolyline == null) {
            ekfTrajectoryPolyline = googleMap.addPolyline(
                PolylineOptions()
                    .addAll(points)
                    .color(Color.BLUE)
                    .width(5f)
            )
        } else {
            ekfTrajectoryPolyline?.points = points
        }
        
        Log.d(TAG, "EKF trajectory updated: ${points.size} points")
    }

    private fun initializeUI() {
        cellIdSpinner = findViewById(R.id.cellIdSpinner)
        displayModeSpinner = findViewById(R.id.displayModeSpinner)
        refreshButton = findViewById(R.id.refreshButton)
        stopServicesButton = findViewById(R.id.stopServicesButton)
        currentCellInfoTextView = findViewById(R.id.currentCellInfo)
        followLocationButton = findViewById(R.id.followLocationButton)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        // Setup follow location button
        followLocationButton.setOnClickListener { toggleFollowLocation() }
        
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
                
                selectedCellId = if (position == 0 || selectedItem.startsWith(getString(R.string.all_cell_ids))) {
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
        
        // Setup stop/start services toggle button
        updateServiceButtonState()
        stopServicesButton.setOnClickListener {
            if (servicesRunning) {
                Log.d(TAG, "Stop services button clicked on map screen")
                stopService(Intent(this, CellFinderService::class.java))
                stopService(Intent(this, TrackingService::class.java))
                servicesRunning = false
                Toast.makeText(this, getString(R.string.services_stopped), Toast.LENGTH_SHORT).show()
                Log.i(TAG, "All services stopped from map screen")
            } else {
                Log.d(TAG, "Start services button clicked on map screen")
                startService(Intent(this, CellFinderService::class.java))
                startService(Intent(this, TrackingService::class.java))
                servicesRunning = true
                Toast.makeText(this, getString(R.string.services_started), Toast.LENGTH_SHORT).show()
                Log.i(TAG, "All services started from map screen")
            }
            applyServiceButtonAppearance()
        }
    }
    
    private fun updateServiceButtonState() {
        servicesRunning = CellFinderService.isRunning || TrackingService.isRunning
        applyServiceButtonAppearance()
    }
    
    private fun applyServiceButtonAppearance() {
        if (servicesRunning) {
            stopServicesButton.text = getString(R.string.btn_stop)
            stopServicesButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
                resources.getColor(R.color.stop_button_background, theme)
            )
        } else {
            stopServicesButton.text = getString(R.string.btn_start)
            stopServicesButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
                resources.getColor(R.color.start_button_background, theme)
            )
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
        
        // Set up circle click listener for RSSI circles
        googleMap.setOnCircleClickListener { circle ->
            handleCircleClick(circle)
        }
        
        // Enable location if permitted
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == 
            PackageManager.PERMISSION_GRANTED) {
            googleMap.isMyLocationEnabled = true
        }
        
        // Set initial camera position (Tokyo as default, then move to current location)
        val initialPosition = LatLng(TOKYO_LAT, TOKYO_LON)
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(initialPosition, DEFAULT_ZOOM))
        
        // Move to current location on first open
        moveToCurrentLocation()
        
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
                    updateCurrentCellInfo()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error updating map data: ${e.message}", e)
                handler.post {
                    Toast.makeText(this@MapsActivity, getString(R.string.toast_map_data_error, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateCellIdSpinner() {
        Log.d(TAG, "updateCellIdSpinner called with ${allCellIds.size} cell IDs")
        
        val items = mutableListOf(getString(R.string.all_cell_ids))
        
        // Add count to the first item
        if (allCellIds.isNotEmpty()) {
            items[0] = getString(R.string.all_cell_ids_count, allCellIds.size)
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
            Toast.makeText(this, getString(R.string.toast_cell_id_filter_error, e.message), Toast.LENGTH_LONG).show()
        }
        
        // If there are many cell IDs, show a toast to inform user (only once)
        if (allCellIds.size > 50 && !hasShownScrollHint) {
            hasShownScrollHint = true
            handler.post {
                Toast.makeText(this@MapsActivity, 
                    getString(R.string.cell_ids_scroll_hint, allCellIds.size), 
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
            DisplayMode.EKF_TRACKING -> {
                Log.d(TAG, "EKF tracking mode - displaying real-time tracking")
                // EKF visualization is handled by observeViewModel
                // Just ensure existing elements are cleared
            }
        }
        
        // Don't auto-fit camera on updates to preserve user's view
        if (filteredLogs.isEmpty()) {
            Log.w(TAG, "No data to display on map")
        }
    }

    private fun createHeatmap(cellLogs: List<CellLog>) {
        Log.d(TAG, "Creating RSSI-based heatmap with ${cellLogs.size} cell logs")
        
        // First, analyze the RSSI range for debugging
        val rssiValues = cellLogs.mapNotNull { it.rssi }
        if (rssiValues.isEmpty()) {
            Log.w(TAG, "No valid RSSI values found")
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
                
            } catch (e: Exception) {
                Log.e(TAG, "Error creating RSSI-based heatmap: ${e.message}", e)
            }
        } else {
            Log.w(TAG, "No valid data points for RSSI-based heatmap")
        }
    }

    private fun createRssiCircles(cellLogs: List<CellLog>) {
        Log.d(TAG, "Creating RSSI circles with ${cellLogs.size} cell logs")
        
        // Clear previous circle metadata
        circleMetadata.clear()
        
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
                    .clickable(true) // Make circle clickable
            )
            
            rssiCircles.add(circle)
            
            // Store metadata for this circle using its ID
            val circleId = circle.id
            circleMetadata[circleId] = log
            
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
                    .title("セル観測")
                    .snippet("種別: ${log.type ?: "不明"}\nRSSI: ${log.rssi ?: "N/A"} dBm\nセルID: ${log.cellId ?: "不明"}")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
            )
            
            marker?.let { cellMarkers.add(it) }
        }
        Log.d(TAG, "Created ${cellMarkers.size} pin markers")
    }

    private fun updateBaseStationMarkers(estimatedPositions: List<EstimatedBaseStation>) {
        // Clear existing base station markers and debug circles
        baseStationMarkers.forEach { it.remove() }
        baseStationMarkers.clear()
        debugCircles.forEach { it.remove() }
        debugCircles.clear()
        
        for (baseStation in estimatedPositions) {
            if (baseStation.lat == null || baseStation.lon == null) continue
            
            // Only show base stations for the selected cell ID if filtering is active
            if (selectedCellId != null && baseStation.cellId != selectedCellId) continue
            
            val marker = googleMap.addMarker(
                MarkerOptions()
                    .position(LatLng(baseStation.lat, baseStation.lon))
                    .title("推定基地局")
                    .snippet("セルID: ${baseStation.cellId}\n種別: ${baseStation.type ?: "不明"}\n観測数: ${baseStation.count}")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
            )
            
            marker?.let {
                it.tag = baseStation.cellId
                baseStationMarkers.add(it)
            }
            
            // Add debug circles showing estimated coverage radius based on observations
            // Get all observations for this cell ID to calculate debug circles
            val cellObservations = allCellLogs.filter { it.cellId == baseStation.cellId }
            for (observation in cellObservations) {
                if (observation.lat == null || observation.lon == null || observation.rssi == null) continue
                
                // Calculate estimated distance from RSSI (simple path loss model)
                // Using free space path loss: d = 10^((RSSI_ref - RSSI) / (10 * n))
                // where n=2 (path loss exponent) and RSSI_ref = -40dBm at 1m
                val rssiRef = -40.0
                val pathLossExponent = 2.0
                val rssi = observation.rssi.toDouble()
                val distanceMeters = Math.pow(10.0, (rssiRef - rssi) / (10.0 * pathLossExponent))
                
                // Clamp distance to reasonable values (10m to 5000m)
                val clampedDistance = Math.max(10.0, Math.min(5000.0, distanceMeters))
                
                // Draw debug circle with low opacity
                val circle = googleMap.addCircle(
                    CircleOptions()
                        .center(LatLng(observation.lat, observation.lon))
                        .radius(clampedDistance)
                        .strokeColor(Color.argb(100, 102, 102, 102)) // Semi-transparent gray
                        .strokeWidth(1f)
                        .fillColor(Color.TRANSPARENT)
                )
                
                debugCircles.add(circle)
            }
        }
        Log.d(TAG, "Updated ${baseStationMarkers.size} base station markers and ${debugCircles.size} debug circles")
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
        
        // Clear EKF elements if not in EKF mode
        if (currentDisplayMode != DisplayMode.EKF_TRACKING) {
            ekfBaseStationMarker?.remove()
            ekfBaseStationMarker = null
            ekfErrorCircle?.remove()
            ekfErrorCircle = null
            ekfUserMarker?.remove()
            ekfUserMarker = null
            ekfTrajectoryPolyline?.remove()
            ekfTrajectoryPolyline = null
        }
        
        Log.d(TAG, "Cleared: $pinCount pins, heatmap: $hadHeatmap, $circleCount RSSI circles, $debugCount debug circles")
    }
    
    private fun handleCircleClick(circle: Circle) {
        // Get metadata for the clicked circle
        val cellLog = circleMetadata[circle.id]
        
        if (cellLog != null) {
            // Find the estimated base station for this cell ID using tag
            val baseStation = baseStationMarkers.find { marker ->
                marker.tag == cellLog.cellId
            }
            
            val baseStationInfo = if (baseStation != null) {
                val position = baseStation.position
                getString(R.string.dialog_cell_base_station_pos, position.latitude, position.longitude)
            } else {
                getString(R.string.dialog_cell_no_base_station)
            }
            
            val unknown = getString(R.string.label_unknown)
            // Show detailed information in a dialog
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_cell_info_title))
                .setMessage(getString(R.string.dialog_cell_info_message,
                    cellLog.cellId ?: unknown,
                    cellLog.type ?: unknown,
                    cellLog.rssi ?: 0,
                    cellLog.lat ?: 0.0,
                    cellLog.lon ?: 0.0,
                    baseStationInfo,
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(cellLog.timestamp))
                ))
                .setPositiveButton(getString(R.string.gsm_alert_ok), null)
                .show()
            
            Log.d(TAG, "Circle clicked: Cell ID=${cellLog.cellId}, RSSI=${cellLog.rssi}dBm")
        } else {
            // This might be a debug circle, show basic info
            Toast.makeText(this, getString(R.string.toast_debug_circle), Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun updateCurrentCellInfo() {
        // Check permissions
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != 
            PackageManager.PERMISSION_GRANTED) {
            currentCellInfoTextView.visibility = android.view.View.GONE
            return
        }
        
        try {
            val telephonyManager = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
            val cellInfo = telephonyManager?.allCellInfo
            
            if (cellInfo.isNullOrEmpty()) {
                currentCellInfoTextView.text = getString(R.string.no_cell_info)
                currentCellInfoTextView.visibility = android.view.View.VISIBLE
                return
            }
            
            // Find the registered (connected) cell
            val connectedCell = cellInfo.firstOrNull { it.isRegistered }
            
            if (connectedCell != null) {
                val cellDetails = when (connectedCell) {
                    is android.telephony.CellInfoGsm -> {
                        val identity = connectedCell.cellIdentity
                        val signal = connectedCell.cellSignalStrength
                        "GSM - CID: ${identity.cid}, RSSI: ${signal.dbm} dBm"
                    }
                    is android.telephony.CellInfoWcdma -> {
                        val identity = connectedCell.cellIdentity
                        val signal = connectedCell.cellSignalStrength
                        "WCDMA - CID: ${identity.cid}, RSSI: ${signal.dbm} dBm"
                    }
                    is android.telephony.CellInfoLte -> {
                        val identity = connectedCell.cellIdentity
                        val signal = connectedCell.cellSignalStrength
                        "LTE - CI: ${identity.ci}, RSRP: ${signal.dbm} dBm"
                    }
                    is android.telephony.CellInfoNr -> {
                        val identity = connectedCell.cellIdentity as android.telephony.CellIdentityNr
                        val signal = connectedCell.cellSignalStrength as android.telephony.CellSignalStrengthNr
                        "5G NR - NCI: ${identity.nci}, SS-RSRP: ${signal.dbm} dBm"
                    }
                    else -> getString(R.string.cell_type_unknown)
                }
                
                currentCellInfoTextView.text = getString(R.string.cell_connected, cellDetails)
                currentCellInfoTextView.visibility = android.view.View.VISIBLE
            } else {
                currentCellInfoTextView.text = getString(R.string.cell_not_connected, cellInfo.size)
                currentCellInfoTextView.visibility = android.view.View.VISIBLE
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current cell info: ${e.message}", e)
            currentCellInfoTextView.text = getString(R.string.cell_info_error)
            currentCellInfoTextView.visibility = android.view.View.VISIBLE
        }
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

    private fun moveToCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != 
            PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission not granted, skipping initial centering")
            return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null && !initialLocationDone) {
                initialLocationDone = true
                val currentLatLng = LatLng(location.latitude, location.longitude)
                googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15f))
                Log.d(TAG, "Moved camera to current location: ${location.latitude}, ${location.longitude}")
            }
        }
    }
    
    private fun toggleFollowLocation() {
        isFollowingLocation = !isFollowingLocation
        if (isFollowingLocation) {
            followLocationButton.text = getString(R.string.btn_follow_on)
            followLocationButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#4285F4")
            )
            followLocationButton.setTextColor(Color.WHITE)
            startFollowingLocation()
        } else {
            followLocationButton.text = getString(R.string.btn_follow_off)
            followLocationButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.LTGRAY
            )
            followLocationButton.setTextColor(Color.BLACK)
            stopFollowingLocation()
        }
    }
    
    private fun startFollowingLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != 
            PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission not granted for follow mode")
            return
        }
        
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
            .setMinUpdateIntervalMillis(3000L)
            .build()
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.locations.lastOrNull() ?: return
                if (isFollowingLocation && ::googleMap.isInitialized) {
                    val latLng = LatLng(location.latitude, location.longitude)
                    googleMap.animateCamera(CameraUpdateFactory.newLatLng(latLng))
                }
            }
        }
        
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, Looper.getMainLooper())
        
        // Also immediately center on current location
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null && isFollowingLocation && ::googleMap.isInitialized) {
                val latLng = LatLng(location.latitude, location.longitude)
                googleMap.animateCamera(CameraUpdateFactory.newLatLng(latLng))
            }
        }
        
        Log.d(TAG, "Started following location")
    }
    
    private fun stopFollowingLocation() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        locationCallback = null
        Log.d(TAG, "Stopped following location")
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1, 0, getString(R.string.menu_toggle_debug_circles))?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu?.add(0, 2, 0, getString(R.string.menu_add_sample_data))?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu?.add(0, 3, 0, getString(R.string.menu_toggle_buildings))?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu?.add(0, 4, 0, getString(R.string.menu_clear_all_logs))?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
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
                val status = if (googleMap.isBuildingsEnabled) getString(R.string.building_layer_visible) else getString(R.string.building_layer_hidden)
                Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this@MapsActivity, getString(R.string.toast_sample_data_added), Toast.LENGTH_SHORT).show()
                    updateMapData() // Refresh the map
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error adding sample data: ${e.message}", e)
                handler.post {
                    Toast.makeText(this@MapsActivity, getString(R.string.toast_sample_data_error, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun clearAllLogsWithConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_clear_all_title))
            .setMessage(getString(R.string.dialog_clear_all_message))
            .setPositiveButton(getString(R.string.dialog_clear_all_positive)) { _, _ ->
                clearAllLogs()
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    private fun clearAllLogs() {
        Log.d(TAG, "Clearing all logs...")
        
        backgroundExecutor.execute {
            try {
                val deletedCount = cellDatabase.clearAllLogs()
                
                handler.post {
                    Toast.makeText(this@MapsActivity, getString(R.string.toast_logs_cleared, deletedCount), Toast.LENGTH_SHORT).show()
                    
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
                    Toast.makeText(this@MapsActivity, getString(R.string.toast_logs_clear_error, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        updateServiceButtonState()
        val filter = IntentFilter(CellFinderService.ACTION_GSM_DETECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(gsmAlertReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(gsmAlertReceiver, filter)
        }
        Log.d(TAG, "GSM alert receiver registered")
    }

    override fun onStop() {
        Log.d(TAG, "MapsActivity onStop")
        try {
            unregisterReceiver(gsmAlertReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister receiver: ${e.message}")
        }
        isGsmAlertShowing = false
        super.onStop()
    }

    private fun showGsmAlertDialog() {
        if (isGsmAlertShowing) return
        isGsmAlertShowing = true
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.gsm_alert_title))
            .setMessage(getString(R.string.gsm_alert_message))
            .setPositiveButton(getString(R.string.gsm_alert_ok)) { dialog, _ ->
                dialog.dismiss()
                isGsmAlertShowing = false
            }
            .setOnCancelListener {
                isGsmAlertShowing = false
            }
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    override fun onDestroy() {
        Log.d(TAG, "MapsActivity onDestroy")
        stopFollowingLocation()
        handler.removeCallbacksAndMessages(null)
        backgroundExecutor.shutdown()
        super.onDestroy()
    }
}