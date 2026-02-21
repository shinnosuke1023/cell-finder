package com.example.cellfinder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
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

    private val PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
    }
    private val REQ_PERM = 100
    private lateinit var statusView: TextView
    private var isGsmAlertShowing = false

    private val gsmAlertReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CellFinderService.ACTION_GSM_DETECTED) {
                Log.w(TAG, "GSM detected broadcast received in MainActivity")
                showGsmAlertDialog()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate() called")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById<TextView>(R.id.statusView)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)

        Log.d(TAG, "UI components initialized")

        btnStart.setOnClickListener {
            Log.d(TAG, "Start button clicked")
            if (!hasPermissions()) {
                Log.w(TAG, "Permissions not granted, requesting permissions")
                ActivityCompat.requestPermissions(this, PERMISSIONS, REQ_PERM)
            } else {
                Log.i(TAG, "Permissions granted, starting services and opening map")
                startServicesAndOpenMap()
            }
        }

        btnStop.setOnClickListener {
            Log.d(TAG, "Stop button clicked")
            stopService(Intent(this, CellFinderService::class.java))
            stopService(Intent(this, TrackingService::class.java))
            statusView.text = getString(R.string.status_logging_stopped)
            Log.i(TAG, "All services stopped")
        }

        Log.d(TAG, "onCreate() completed")
    }

    private fun startServicesAndOpenMap() {
        updateSimStatus()
        startService(Intent(this, CellFinderService::class.java))
        startService(Intent(this, TrackingService::class.java))
        statusView.text = getString(R.string.status_logging_started)
        Log.i(TAG, "CellFinderService and TrackingService started successfully")
        startActivity(Intent(this, MapsActivity::class.java))
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(CellFinderService.ACTION_GSM_DETECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(gsmAlertReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(gsmAlertReceiver, filter)
        }
        Log.d(TAG, "GSM alert receiver registered")
    }

    override fun onStop() {
        Log.d(TAG, "onStop() called")
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
                Log.i(TAG, "All permissions granted, starting services and opening map")
                startServicesAndOpenMap()
            } else {
                Log.w(TAG, "Some permissions were denied")
                statusView.text = getString(R.string.status_permission_denied)
            }
        }
    }
}
