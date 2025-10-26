package com.example.cellfinder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.widget.Button
import android.widget.TextView
import android.telephony.TelephonyManager
import android.util.Log

class MainActivity : Activity() {
    companion object {
        private const val TAG = "CellFinder-MainActivity"
    }

    private val PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.READ_PHONE_STATE
    )
    private val REQ_PERM = 100
    private lateinit var statusView: TextView
    private lateinit var btnTrackStart: Button
    private lateinit var btnTrackStop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate() called")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById<TextView>(R.id.statusView)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val btnMap = findViewById<Button>(R.id.btnMap)
        btnTrackStart = findViewById<Button>(R.id.btnTrackStart)
        btnTrackStop = findViewById<Button>(R.id.btnTrackStop)

        Log.d(TAG, "UI components initialized")

        btnStart.setOnClickListener {
            Log.d(TAG, "Start button clicked")
            if (!hasPermissions()) {
                Log.w(TAG, "Permissions not granted, requesting permissions")
                ActivityCompat.requestPermissions(this, PERMISSIONS, REQ_PERM)
            } else {
                Log.i(TAG, "Permissions granted, starting service")
                updateSimStatus()
                startService(Intent(this, CellFinderService::class.java))
                statusView.text = getString(R.string.status_logging_started)
                Log.i(TAG, "CellFinderService started successfully")
            }
        }

        btnStop.setOnClickListener {
            Log.d(TAG, "Stop button clicked")
            stopService(Intent(this, CellFinderService::class.java))
            statusView.text = getString(R.string.status_logging_stopped)
            Log.i(TAG, "CellFinderService stopped")
        }

        btnMap.setOnClickListener {
            Log.d(TAG, "Map button clicked")
            startActivity(Intent(this, MapsActivity::class.java))
        }
        
        btnTrackStart.setOnClickListener {
            Log.d(TAG, "Track Start button clicked")
            if (!hasPermissions()) {
                Log.w(TAG, "Permissions not granted, requesting permissions")
                ActivityCompat.requestPermissions(this, PERMISSIONS, REQ_PERM)
            } else {
                Log.i(TAG, "Permissions granted, starting tracking service")
                startService(Intent(this, TrackingService::class.java))
                statusView.text = "Status: EKF tracking started"
                Log.i(TAG, "TrackingService started successfully")
            }
        }
        
        btnTrackStop.setOnClickListener {
            Log.d(TAG, "Track Stop button clicked")
            stopService(Intent(this, TrackingService::class.java))
            statusView.text = "Status: EKF tracking stopped"
            Log.i(TAG, "TrackingService stopped")
        }

        Log.d(TAG, "onCreate() completed")
    }

    private fun hasPermissions(): Boolean {
        val result = PERMISSIONS.all { p ->
            ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        }
        Log.d(TAG, "hasPermissions() result: $result")
        PERMISSIONS.forEach { permission ->
            val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Permission $permission: ${if (granted) "GRANTED" else "DENIED"}")
        }
        return result
    }

    private fun updateSimStatus() {
        Log.d(TAG, "updateSimStatus() called")
        try {
            val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            val simState = tm.simState
            val stateStr = when (simState) {
                TelephonyManager.SIM_STATE_READY -> "READY"
                TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
                TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
                TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
                TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
                TelephonyManager.SIM_STATE_UNKNOWN -> "UNKNOWN"
                else -> "OTHER"
            }
            Log.i(TAG, "SIM state: $stateStr (raw value: $simState)")
            statusView.text = getString(R.string.status_sim_state, stateStr)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in updateSimStatus(): ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Exception in updateSimStatus(): ${e.message}", e)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        Log.d(TAG, "onRequestPermissionsResult() called with requestCode: $requestCode")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) {
            Log.d(TAG, "Processing permission request result")
            permissions.forEachIndexed { index, permission ->
                val granted = grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED
                Log.d(TAG, "Permission result - $permission: ${if (granted) "GRANTED" else "DENIED"}")
            }

            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.i(TAG, "All permissions granted, starting service")
                updateSimStatus()
                startService(Intent(this, CellFinderService::class.java))
                statusView.text = getString(R.string.status_logging_started)
                Log.i(TAG, "Service started after permission grant")
            } else {
                Log.w(TAG, "Some permissions were denied")
                statusView.text = getString(R.string.status_permission_denied)
            }
        }
    }
}
