package com.example.cellfinder

data class MapFilter(
    val timeWindowMinutes: Int = 60,
    val cellTypes: Set<String>? = null, // null means show all types
    val minRssi: Int? = null, // minimum RSSI in dBm
    val maxRssi: Int? = null, // maximum RSSI in dBm
    val showCellObservations: Boolean = true,
    val showBaseStations: Boolean = true,
    val showDebugCircles: Boolean = false
) {
    fun shouldShowCellLog(cellLog: CellLog): Boolean {
        // Check RSSI range
        if (cellLog.rssi != null) {
            if (minRssi != null && cellLog.rssi < minRssi) return false
            if (maxRssi != null && cellLog.rssi > maxRssi) return false
        }
        
        // Check cell type
        if (cellTypes != null && cellLog.type != null) {
            if (!cellTypes.contains(cellLog.type)) return false
        }
        
        return true
    }
    
    companion object {
        val DEFAULT = MapFilter()
    }
}