package com.example.cellfinder

import android.app.*
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.google.android.gms.location.LocationServices
import android.Manifest
import androidx.core.app.ActivityCompat
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoWcdma
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import android.util.Log

class CellFinderService : Service() {
    companion object {
        private const val TAG = "CellFinder-Service"
    }

    private val CHANNEL_ID = "cell_finder_channel"
    private lateinit var telephonyManager: TelephonyManager
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient()
    private val gson = Gson()

    // --- ここを実行環境に合わせて変えてください ---
    private val SERVER_URL = "http://192.168.0.17:5000/log" // ← 必ず書き換える
    private val POLL_INTERVAL_MS: Long = 5000

    override fun onCreate() {
        Log.i(TAG, "Service onCreate() called")
        super.onCreate()
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        createNotificationChannel()
        startForeground(1, buildNotification())
        Log.i(TAG, "Service started in foreground with notification")
        handler.post(logRunnable)
        Log.i(TAG, "Logging runnable started with interval: ${POLL_INTERVAL_MS}ms")
    }

    private val logRunnable = object : Runnable {
        override fun run() {
            Log.d(TAG, "logRunnable executing...")
            sampleAndSend()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun sampleAndSend() {
        Log.d(TAG, "sampleAndSend() called")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Required permissions not granted, skipping data collection")
            return
        }

        val fused = LocationServices.getFusedLocationProviderClient(this)
        fused.lastLocation.addOnSuccessListener { location ->
            Log.d(TAG, "Location received: ${location?.latitude}, ${location?.longitude}")

            val simState = when (telephonyManager.simState) {
                TelephonyManager.SIM_STATE_READY -> "READY"
                TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
                TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
                TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
                TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
                TelephonyManager.SIM_STATE_UNKNOWN -> "UNKNOWN"
                else -> "OTHER"
            }
            Log.d(TAG, "SIM state: $simState")

            val cellInfo = telephonyManager.allCellInfo ?: emptyList<CellInfo>()
            Log.d(TAG, "Found ${cellInfo.size} cell towers")

            var foundGsmType = false
            var anyTypeKnown = false
            val cells = cellInfo.map { ci ->
                val type = when (ci) {
                    is CellInfoGsm -> { foundGsmType = true; anyTypeKnown = true; "GSM" }
                    is CellInfoWcdma -> { anyTypeKnown = true; "WCDMA" }
                    is CellInfoLte -> { anyTypeKnown = true; "LTE" }
                    is CellInfoNr -> { anyTypeKnown = true; "NR" }
                    else -> "UNKNOWN"
                }
                val dbm = when (ci) {
                    is CellInfoGsm -> ci.cellSignalStrength.dbm
                    is CellInfoWcdma -> ci.cellSignalStrength.dbm
                    is CellInfoLte -> ci.cellSignalStrength.dbm
                    is CellInfoNr -> ci.cellSignalStrength.dbm
                    else -> null
                }
                Log.d(TAG, "Cell: type=$type, dbm=$dbm")

                // completeness checks
                val hasIdentity = try {
                    val ident = when (ci) {
                        is CellInfoGsm -> ci.cellIdentity
                        is CellInfoWcdma -> ci.cellIdentity
                        is CellInfoLte -> ci.cellIdentity
                        is CellInfoNr -> ci.cellIdentity
                        else -> null
                    }
                    Log.d(TAG, "Cell identity: $ident")
                    ident != null
                } catch (_: Exception) {
                    false
                }

                mapOf("type" to type, "dbm" to dbm, "hasIdentity" to hasIdentity)
            }

            val payload = mapOf(
                "timestamp" to System.currentTimeMillis(),
                "lat" to location?.latitude,
                "lon" to location?.longitude,
                "simState" to simState,
                "foundGsmType" to foundGsmType,
                "anyTypeKnown" to anyTypeKnown,
                "cells" to cells
            )

            Log.i(TAG, "Sending payload: foundGsmType=$foundGsmType, anyTypeKnown=$anyTypeKnown, cellCount=${cells.size}")
            sendJson(payload)
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to get location: ${e.message}")
        }
    }

    private fun sendJson(data: Any) {
        try {
            val json = gson.toJson(data)
            Log.d(TAG, "Sending JSON to $SERVER_URL")
            Log.v(TAG, "JSON payload: $json")

            val body = json.toRequestBody("application/json".toMediaTypeOrNull())
            val req = Request.Builder().url(SERVER_URL).post(body).build()

            client.newCall(req).enqueue(object: Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "HTTP request failed: ${e.message}")
                }
                override fun onResponse(call: Call, response: Response) {
                    Log.d(TAG, "HTTP response: ${response.code} ${response.message}")
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Server returned error: ${response.code}")
                    }
                    response.close()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error in sendJson: ${e.message}", e)
        }
    }

    private fun buildNotification(): Notification {
        Log.d(TAG, "Building notification")
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("CellFinder")
            .setContentText("Logging cell & location")
            .setContentIntent(pi)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()
    }

    private fun createNotificationChannel() {
        Log.d(TAG, "Creating notification channel")
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "CellFinder", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
        Log.d(TAG, "Notification channel created")
    }

    override fun onDestroy() {
        Log.i(TAG, "Service onDestroy() called")
        handler.removeCallbacks(logRunnable)
        Log.i(TAG, "Logging runnable stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind() called")
        return null
    }
}
