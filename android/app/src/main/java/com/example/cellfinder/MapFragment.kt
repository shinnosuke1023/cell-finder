package com.example.cellfinder

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*

/**
 * Fragment that displays the map with EKF-based base station estimation.
 */
class MapFragment : Fragment(), OnMapReadyCallback {
    companion object {
        private const val TAG = "MapFragment"
        
        fun newInstance() = MapFragment()
    }
    
    private lateinit var viewModel: MapViewModel
    private var googleMap: GoogleMap? = null
    
    // Map elements
    private var baseStationMarker: Marker? = null
    private var errorCircle: Circle? = null
    private var userMarker: Marker? = null
    private var trajectoryPolyline: Polyline? = null
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.activity_maps, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Initialize ViewModel
        viewModel = ViewModelProvider(this)[MapViewModel::class.java]
        
        // Set up map
        val mapFragment = childFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment
        mapFragment?.getMapAsync(this)
        
        // Observe ViewModel
        observeViewModel()
    }
    
    override fun onMapReady(map: GoogleMap) {
        Log.d(TAG, "Map ready")
        googleMap = map
        
        // Configure map
        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isMyLocationButtonEnabled = true
        
        // Set initial camera position (Tokyo)
        val tokyo = LatLng(35.6762, 139.6503)
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(tokyo, 15f))
    }
    
    private fun observeViewModel() {
        // Observe tracking state
        viewModel.trackingState.observe(viewLifecycleOwner) { state ->
            state?.let { updateMapWithState(it) }
        }
        
        // Observe trajectory
        viewModel.userTrajectory.observe(viewLifecycleOwner) { trajectory ->
            updateTrajectory(trajectory)
        }
    }
    
    private fun updateMapWithState(state: TrackingState) {
        val map = googleMap ?: return
        
        Log.d(TAG, "Updating map with state: estimated=(${state.estimatedLatitude}, ${state.estimatedLongitude})")
        
        // Update base station marker
        val baseStationPos = LatLng(state.estimatedLatitude, state.estimatedLongitude)
        
        if (baseStationMarker == null) {
            baseStationMarker = map.addMarker(
                MarkerOptions()
                    .position(baseStationPos)
                    .title("Estimated Base Station")
                    .snippet("Cell ID: ${state.cellId}\nError: ${String.format("%.1f", state.errorRadiusMeters)}m")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
            )
        } else {
            baseStationMarker?.position = baseStationPos
            baseStationMarker?.snippet = "Cell ID: ${state.cellId}\nError: ${String.format("%.1f", state.errorRadiusMeters)}m"
        }
        
        // Update error circle (uncertainty visualization)
        if (errorCircle == null) {
            errorCircle = map.addCircle(
                CircleOptions()
                    .center(baseStationPos)
                    .radius(state.errorRadiusMeters)
                    .strokeColor(Color.argb(150, 255, 0, 0))
                    .strokeWidth(2f)
                    .fillColor(Color.argb(50, 255, 0, 0))
            )
        } else {
            errorCircle?.center = baseStationPos
            errorCircle?.radius = state.errorRadiusMeters
        }
        
        // Update user marker
        val userPos = LatLng(state.userLatitude, state.userLongitude)
        
        if (userMarker == null) {
            userMarker = map.addMarker(
                MarkerOptions()
                    .position(userPos)
                    .title("Your Location")
                    .snippet("RSSI: ${state.rssi} dBm\nCell: ${state.cellType}")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
            )
        } else {
            userMarker?.position = userPos
            userMarker?.snippet = "RSSI: ${state.rssi} dBm\nCell: ${state.cellType}"
        }
        
        // Fit camera to show both markers
        val builder = LatLngBounds.Builder()
        builder.include(baseStationPos)
        builder.include(userPos)
        
        try {
            val bounds = builder.build()
            val padding = 200 // pixels
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fit camera: ${e.message}")
        }
    }
    
    private fun updateTrajectory(trajectory: List<Pair<Double, Double>>) {
        val map = googleMap ?: return
        
        if (trajectory.isEmpty()) {
            trajectoryPolyline?.remove()
            trajectoryPolyline = null
            return
        }
        
        val points = trajectory.map { (lat, lon) -> LatLng(lat, lon) }
        
        if (trajectoryPolyline == null) {
            trajectoryPolyline = map.addPolyline(
                PolylineOptions()
                    .addAll(points)
                    .color(Color.BLUE)
                    .width(5f)
            )
        } else {
            trajectoryPolyline?.points = points
        }
        
        Log.d(TAG, "Trajectory updated: ${points.size} points")
    }
}
