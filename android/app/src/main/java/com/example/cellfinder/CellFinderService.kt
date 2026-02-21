package com.example.cellfinder

import android.app.*
import android.content.Intent
import android.os.Build
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
import java.util.Collections
import android.util.Log

class CellFinderService : Service() {
    companion object {
        private const val TAG = "CellFinder-Service"
        const val ACTION_GSM_DETECTED = "com.example.cellfinder.GSM_DETECTED"
        const val EXTRA_GSM_CELL_INFO = "gsm_cell_info"
        var isRunning: Boolean = false
            private set
    }

    private val CHANNEL_ID = "cell_finder_channel"
    private val GSM_ALERT_CHANNEL_ID = "gsm_alert_channel"
    private val GSM_ALERT_NOTIFICATION_ID = 1001
    private var lastGsmAlertTimeMs: Long = 0
    private var wasGsmDetected: Boolean = false
    private val GSM_ALERT_COOLDOWN_MS: Long = 60_000 // 1 minute cooldown between alerts
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var cellDatabase: CellDatabase
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient()
    private val gson = Gson()
    private val pendingSyncIds: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())
    @Volatile private var destroyed = false

    // --- ここを実行環境に合わせて変えてください ---
    private val SERVER_URL = "https://cell-finder-app-hhb9eyhjh3dyfwfg.japaneast-01.azurewebsites.net/log"
    private val POLL_INTERVAL_MS: Long = 5000
    private val RETRY_INTERVAL_MS: Long = 30_000
    private val RETRY_BATCH_SIZE = 100

    override fun onCreate() {
        Log.i(TAG, "Service onCreate() called")
        super.onCreate()
        isRunning = true
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        cellDatabase = CellDatabase(this)
        createNotificationChannel()
        startForeground(1, buildNotification())
        Log.i(TAG, "Service started in foreground with notification")
        handler.post(logRunnable)
        Log.i(TAG, "Logging runnable started with interval: ${POLL_INTERVAL_MS}ms")
        handler.postDelayed(retryRunnable, RETRY_INTERVAL_MS)
        
        // Clean up old data periodically (keep last 24 hours)
        cellDatabase.clearOldData(24)
    }

    private val logRunnable = object : Runnable {
        override fun run() {
            Log.d(TAG, "logRunnable executing...")
            sampleAndSend()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val retryRunnable = object : Runnable {
        override fun run() {
            retryUnsyncedLogs()
            handler.postDelayed(this, RETRY_INTERVAL_MS)
        }
    }

    private fun retryUnsyncedLogs() {
        val unsyncedLogs = cellDatabase.getUnsyncedLogs(RETRY_BATCH_SIZE)
            .filter { (id, _) -> id !in pendingSyncIds }
        if (unsyncedLogs.isEmpty()) return
        Log.d(TAG, "Retrying ${unsyncedLogs.size} unsynced logs")

        // Group by (timestamp, lat, lon) to better approximate original sampling batches.
        // NOTE: This is an approximation — records sharing the same (timestamp, lat, lon) are
        // assumed to originate from the same sampling event. Logs with null lat/lon will all fall
        // into a single batch. A future improvement would be to store an explicit batchId column.
        unsyncedLogs.groupBy { (_, log) -> Triple(log.timestamp, log.lat, log.lon) }
            .forEach { (_, logsForBatch) ->
            val firstLog = logsForBatch.first().second
            if (firstLog.lat == null || firstLog.lon == null) {
                Log.w(TAG, "Retrying ${logsForBatch.size} log(s) without location data (lat/lon is null)")
            }
            val cells = logsForBatch.map { (_, cellLog) ->
                mapOf(
                    "type" to cellLog.type,
                    "rssi" to cellLog.rssi,
                    "cell_id" to cellLog.cellId,
                    // hasIdentity is a derived field that can be reconstructed from cell_id
                    "hasIdentity" to (cellLog.cellId != null)
                )
            }
            // NOTE: simState, foundGsmType, and anyTypeKnown are not stored in the DB schema
            // and therefore use conservative defaults on retry.
            val payload = mapOf(
                "timestamp" to firstLog.timestamp,
                "lat" to firstLog.lat,
                "lon" to firstLog.lon,
                "simState" to null,
                "foundGsmType" to false,
                "anyTypeKnown" to false,
                "cells" to cells
            )
            val ids = logsForBatch.map { it.first }
            sendJson(payload, ids)
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
                val cellId = extractCellId(ci)
                Log.d(TAG, "Cell: type=$type, dbm=$dbm, cellId=$cellId")

                // completeness checks
                val hasIdentity = cellId != null

                mapOf(
                    "type" to type,
                    // サーバー側と合わせるフィールド名
                    "rssi" to dbm,
                    "cell_id" to cellId,
                    // 追加情報（デバッグ用）
                    "dbm" to dbm,
                    "hasIdentity" to hasIdentity
                )
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
            
            // GSM alert: notify user only on transition (not previously detected) or after cooldown
            if (foundGsmType) {
                val now = System.currentTimeMillis()
                if (!wasGsmDetected || now - lastGsmAlertTimeMs > GSM_ALERT_COOLDOWN_MS) {
                    Log.w(TAG, "GSM (2G) connection detected! Sending alert.")
                    sendGsmAlertNotification()
                    sendGsmDetectedBroadcast()
                    lastGsmAlertTimeMs = now
                }
                wasGsmDetected = true
            } else {
                wasGsmDetected = false
            }
            
            // Store data locally
            val logIds = storeDataLocally(payload, cells)
            
            // Also send to server if configured
            sendJson(payload, logIds)
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to get location: ${e.message}")
        }
    }

    private fun storeDataLocally(payload: Map<String, Any?>, cells: List<Map<String, Any?>>): List<Long> {
        return try {
            val timestamp = payload["timestamp"] as? Long ?: System.currentTimeMillis()
            val lat = payload["lat"] as? Double
            val lon = payload["lon"] as? Double
            
            val cellLogs = cells.mapNotNull { cellData ->
                val type = cellData["type"] as? String
                val rssi = cellData["rssi"] as? Int
                val cellId = cellData["cell_id"] as? String
                
                // Only store if we have essential data
                if (type != null || rssi != null || cellId != null) {
                    CellLog(timestamp, lat, lon, type, rssi, cellId)
                } else {
                    null
                }
            }
            
            if (cellLogs.isNotEmpty()) {
                val ids = cellDatabase.insertCellLogs(cellLogs)
                if (ids.isEmpty()) {
                    Log.w(TAG, "DB insert returned no IDs for ${cellLogs.size} cell log(s); " +
                        "data will still be attempted to server but cannot be retried if the request fails")
                } else {
                    Log.d(TAG, "Stored ${cellLogs.size} cell logs locally")
                }
                ids
            } else {
                emptyList()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error storing data locally: ${e.message}", e)
            emptyList()
        }
    }

    // 各無線方式ごとに基地局IDを抽出
    private fun extractCellId(ci: CellInfo): String? {
        return try {
            when (ci) {
                is CellInfoGsm -> {
                    val id = ci.cellIdentity
                    val cid = id.cid
                    val lac = id.lac
                    val mcc = id.mccString
                    val mnc = id.mncString
                    if (cid != Int.MAX_VALUE && cid != -1) {
                        // なるべく一意性を高める
                        "GSM:${mcc}-${mnc}:${lac}-${cid}"
                    } else null
                }
                is CellInfoWcdma -> {
                    val id = ci.cellIdentity
                    val cid = id.cid
                    val lac = id.lac
                    val mcc = id.mccString
                    val mnc = id.mncString
                    if (cid != Int.MAX_VALUE && cid != -1) {
                        "WCDMA:${mcc}-${mnc}:${lac}-${cid}"
                    } else null
                }
                is CellInfoLte -> {
                    val id = ci.cellIdentity
                    val ciVal = id.ci // ECI
                    val tac = id.tac
                    val mcc = id.mccString
                    val mnc = id.mncString
                    if (ciVal != Int.MAX_VALUE && ciVal != -1) {
                        "LTE:${mcc}-${mnc}:${tac}-${ciVal}"
                    } else null
                }
                is CellInfoNr -> {
                    val nrId = try { (ci as CellInfoNr).cellIdentity } catch (_: Exception) { null }
                    if (nrId != null) {
                        fun call(name: String): Any? = try {
                            nrId.javaClass.getMethod(name).invoke(nrId)
                        } catch (_: Exception) { null }
                        val nci = call("getNci")
                        val tac = call("getTac")
                        val mcc = call("getMccString") ?: call("getMcc")
                        val mnc = call("getMncString") ?: call("getMnc")
                        if (nci != null) {
                            "NR:${mcc}-${mnc}:${tac}-${nci}"
                        } else {
                            "NR:${nrId.toString()}"
                        }
                    } else null
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract cell id: ${e.message}")
            null
        }
    }

    private fun sendJson(data: Any, logIds: List<Long> = emptyList()) {
        // Register IDs as in-flight before the request so the catch block can always remove them
        if (logIds.isNotEmpty()) {
            pendingSyncIds.addAll(logIds)
        }
        try {
            val json = gson.toJson(data)
            Log.d(TAG, "Sending JSON to $SERVER_URL")
            Log.v(TAG, "JSON payload: $json")

            val body = json.toRequestBody("application/json".toMediaTypeOrNull())
            val req = Request.Builder().url(SERVER_URL).post(body).build()

            client.newCall(req).enqueue(object: Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "HTTP request failed: ${e.message}")
                    handler.post { pendingSyncIds.removeAll(logIds.toSet()) }
                }
                override fun onResponse(call: Call, response: Response) {
                    val code = response.code
                    Log.d(TAG, "HTTP response: $code ${response.message}")
                    val successful = response.isSuccessful
                    if (!successful) Log.w(TAG, "Server returned error: $code")
                    response.close()
                    handler.post {
                        if (!destroyed && successful && logIds.isNotEmpty()) {
                            cellDatabase.markAsSynced(logIds)
                        }
                        pendingSyncIds.removeAll(logIds.toSet())
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error in sendJson: ${e.message}", e)
            pendingSyncIds.removeAll(logIds.toSet())
        }
    }

    private fun buildNotification(): Notification {
        Log.d(TAG, "Building notification")
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("CellFinder")
            .setContentText("セル情報と位置をログ記録中")
            .setContentIntent(pi)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()
    }

    private fun createNotificationChannel() {
        Log.d(TAG, "Creating notification channels")
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "CellFinder", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)

        val gsmAlertChannel = NotificationChannel(
            GSM_ALERT_CHANNEL_ID,
            "GSMアラート",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "GSM（2G）接続が検出された場合にアラートを通知します"
            enableVibration(true)
        }
        nm.createNotificationChannel(gsmAlertChannel)
        Log.d(TAG, "Notification channels created")
    }

    private fun sendGsmAlertNotification() {
        // On Android 13+ (TIRAMISU), POST_NOTIFICATIONS permission is required
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted, skipping notification")
            return
        }
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, GSM_ALERT_CHANNEL_ID)
            .setContentTitle(getString(R.string.gsm_notification_title))
            .setContentText(getString(R.string.gsm_notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(GSM_ALERT_NOTIFICATION_ID, notification)
    }

    private fun sendGsmDetectedBroadcast() {
        val intent = Intent(ACTION_GSM_DETECTED)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "Service onDestroy() called")
        isRunning = false
        destroyed = true
        handler.removeCallbacks(logRunnable)
        handler.removeCallbacks(retryRunnable)
        Log.i(TAG, "Logging runnable stopped")
        try {
            client.dispatcher.cancelAll()
            pendingSyncIds.clear()
            Log.i(TAG, "All in-flight HTTP requests cancelled and pendingSyncIds cleared")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel in-flight HTTP requests", e)
        }
        try {
            cellDatabase.close()
            Log.i(TAG, "CellDatabase connection closed")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close CellDatabase", e)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind() called")
        return null
    }
}
