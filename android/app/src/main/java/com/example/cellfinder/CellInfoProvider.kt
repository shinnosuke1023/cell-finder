package com.example.cellfinder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoWcdma
import android.telephony.CellInfoLte
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat

/**
 * Data class representing cell information for tracking.
 */
data class CellInfoData(
    val cellId: String,
    val rssi: Int,
    val type: String,
    val isRegistered: Boolean
)

/**
 * Wrapper class for TelephonyManager that provides cell information,
 * with a focus on 2G (GSM) cells for IMSI catcher detection.
 */
class CellInfoProvider(private val context: Context) {
    companion object {
        private const val TAG = "CellInfoProvider"
    }
    
    private val telephonyManager: TelephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    
    private var targetCellId: String? = null
    
    /**
     * Get all available cell information.
     * 
     * @return List of CellInfoData or empty list if unavailable
     */
    fun getAllCellInfo(): List<CellInfoData> {
        if (!checkPermissions()) {
            Log.w(TAG, "Required permissions not granted")
            return emptyList()
        }
        
        try {
            val cellInfoList = telephonyManager.allCellInfo ?: return emptyList()
            
            return cellInfoList.mapNotNull { cellInfo ->
                parseCellInfo(cellInfo)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception getting cell info: ${e.message}")
            return emptyList()
        }
    }
    
    /**
     * Get the strongest 2G (GSM) cell.
     * This is useful for tracking a single fake base station.
     * 
     * @return CellInfoData for the strongest GSM cell or null if none found
     */
    fun getStrongest2GCell(): CellInfoData? {
        if (!checkPermissions()) {
            Log.w(TAG, "Required permissions not granted")
            return null
        }
        
        try {
            val cellInfoList = telephonyManager.allCellInfo ?: return null
            
            // Filter for GSM cells only
            val gsmCells = cellInfoList.filterIsInstance<CellInfoGsm>()
            
            if (gsmCells.isEmpty()) {
                Log.d(TAG, "No GSM cells found")
                return null
            }
            
            // Find the strongest GSM cell
            val strongestGsm = gsmCells.maxByOrNull { it.cellSignalStrength.dbm }
            
            return strongestGsm?.let { parseCellInfo(it) }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception getting GSM cell: ${e.message}")
            return null
        }
    }
    
    /**
     * Get information for the target cell being tracked.
     * If no target is set, returns the strongest 2G cell.
     * 
     * @return CellInfoData for the target cell or null if not available
     */
    fun getTargetCell(): CellInfoData? {
        val allCells = getAllCellInfo()
        
        // If a target is set, try to find it
        if (targetCellId != null) {
            val targetCell = allCells.find { it.cellId == targetCellId }
            if (targetCell != null) {
                return targetCell
            } else {
                Log.w(TAG, "Target cell $targetCellId not found in current scan")
            }
        }
        
        // Otherwise, return strongest 2G cell and set it as target
        val strongest2G = allCells
            .filter { it.type == "GSM" }
            .maxByOrNull { it.rssi }
        
        if (strongest2G != null) {
            targetCellId = strongest2G.cellId
            Log.d(TAG, "Auto-selected target cell: ${strongest2G.cellId}")
        }
        
        return strongest2G
    }
    
    /**
     * Manually set the target cell ID to track.
     * 
     * @param cellId Cell ID to track
     */
    fun setTargetCellId(cellId: String) {
        targetCellId = cellId
        Log.d(TAG, "Target cell set to: $cellId")
    }
    
    /**
     * Clear the target cell ID.
     */
    fun clearTargetCell() {
        targetCellId = null
        Log.d(TAG, "Target cell cleared")
    }
    
    /**
     * Parse CellInfo object into CellInfoData.
     */
    private fun parseCellInfo(cellInfo: CellInfo): CellInfoData? {
        return when (cellInfo) {
            is CellInfoGsm -> {
                val identity = cellInfo.cellIdentity
                val signal = cellInfo.cellSignalStrength
                
                val cid = identity.cid
                val lac = identity.lac
                val mcc = identity.mccString
                val mnc = identity.mncString
                
                if (cid != Int.MAX_VALUE && cid != -1) {
                    CellInfoData(
                        cellId = "GSM:${mcc}-${mnc}:${lac}-${cid}",
                        rssi = signal.dbm,
                        type = "GSM",
                        isRegistered = cellInfo.isRegistered
                    )
                } else null
            }
            is CellInfoWcdma -> {
                val identity = cellInfo.cellIdentity
                val signal = cellInfo.cellSignalStrength
                
                val cid = identity.cid
                val lac = identity.lac
                val mcc = identity.mccString
                val mnc = identity.mncString
                
                if (cid != Int.MAX_VALUE && cid != -1) {
                    CellInfoData(
                        cellId = "WCDMA:${mcc}-${mnc}:${lac}-${cid}",
                        rssi = signal.dbm,
                        type = "WCDMA",
                        isRegistered = cellInfo.isRegistered
                    )
                } else null
            }
            is CellInfoLte -> {
                val identity = cellInfo.cellIdentity
                val signal = cellInfo.cellSignalStrength
                
                val ci = identity.ci
                val tac = identity.tac
                val mcc = identity.mccString
                val mnc = identity.mncString
                
                if (ci != Int.MAX_VALUE && ci != -1) {
                    CellInfoData(
                        cellId = "LTE:${mcc}-${mnc}:${tac}-${ci}",
                        rssi = signal.dbm,
                        type = "LTE",
                        isRegistered = cellInfo.isRegistered
                    )
                } else null
            }
            else -> null
        }
    }
    
    /**
     * Check if required permissions are granted.
     */
    private fun checkPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }
}
