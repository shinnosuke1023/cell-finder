package com.example.cellfinder

import android.util.Log
import kotlin.math.*

data class Point(val x: Double, val y: Double)
data class CircleIntersection(val x: Double, val y: Double, val weight: Double)

object BaseStationEstimator {
    private const val TAG = "BaseStationEstimator"
    private const val EARTH_RADIUS_M = 6371000.0

    /**
     * Port of server.py _rssi_to_distance_m function
     * Logarithmic distance model: PL(d) = PL(d0) + 10 n log10(d/d0)
     * RSSI[dBm] ≈ ref_rssi_dbm - 10 n log10(d/ref_dist_m)
     * Therefore: d = ref_dist_m * 10^((ref_rssi_dbm - rssi_dbm)/(10 n))
     */
    private fun rssiToDistanceM(
        rssiDbm: Double,
        pathLossExponent: Double,
        refRssiDbm: Double,
        refDistM: Double
    ): Double {
        val n = maxOf(pathLossExponent, 0.1)
        val d = refDistM * (10.0).pow((refRssiDbm - rssiDbm) / (10.0 * n))
        // Clip to safe range (1m to 50km)
        return maxOf(1.0, minOf(d, 50_000.0))
    }

    /**
     * Convert lat/lon to local XY coordinates (meters)
     * Port of server.py _ll_to_xy_m function
     */
    private fun latLonToXyM(lat: Double, lon: Double, lat0: Double, lon0: Double): Point {
        val rad = PI / 180.0
        val x = EARTH_RADIUS_M * cos(lat0 * rad) * (lon - lon0) * rad
        val y = EARTH_RADIUS_M * (lat - lat0) * rad
        return Point(x, y)
    }

    /**
     * Convert local XY coordinates back to lat/lon
     * Port of server.py _xy_to_ll function
     */
    private fun xyToLatLon(x: Double, y: Double, lat0: Double, lon0: Double): Pair<Double, Double> {
        val deg = 180.0 / PI
        val lat = y / EARTH_RADIUS_M * deg + lat0
        val lon = x / (EARTH_RADIUS_M * cos(lat0 * PI / 180.0)) * deg + lon0
        return Pair(lat, lon)
    }

    /**
     * Find intersections of two circles
     * Port of server.py _circle_intersections function
     */
    private fun circleIntersections(
        x0: Double, y0: Double, r0: Double,
        x1: Double, y1: Double, r1: Double
    ): List<CircleIntersection> {
        val dx = x1 - x0
        val dy = y1 - y0
        val d = sqrt(dx * dx + dy * dy)

        // Circles are separated, contained, or concentric
        if (d <= 1e-6 || d > r0 + r1 || d < abs(r0 - r1)) {
            return emptyList()
        }

        // Find intersection points
        val a = (r0 * r0 - r1 * r1 + d * d) / (2 * d)
        var h2 = r0 * r0 - a * a
        if (h2 < 0) {
            h2 = 0.0 // Handle rounding errors
        }
        val h = sqrt(h2)

        val xm = x0 + a * dx / d
        val ym = y0 + a * dy / d
        val rx = -dy * (h / d)
        val ry = dx * (h / d)

        val p1 = Point(xm + rx, ym + ry)
        val p2 = Point(xm - rx, ym - ry)

        // Calculate intersection angle weight: shallow intersections get smaller weight
        val denom = maxOf(1e-6, minOf(r0, r1))
        val wAngle = maxOf(0.0, minOf(1.0, h / denom))

        // If circles are tangent, return single point
        return if (h <= 1e-6) {
            listOf(CircleIntersection(p1.x, p1.y, wAngle))
        } else {
            listOf(
                CircleIntersection(p1.x, p1.y, wAngle),
                CircleIntersection(p2.x, p2.y, wAngle)
            )
        }
    }

    /**
     * Calculate centroid estimate using weighted average
     * Similar to server.py centroid_estimate function
     */
    private fun centroidEstimate(logs: List<CellLog>, pathLossExponent: Double): Pair<Double?, Double?> {
        var sumLat = 0.0
        var sumLon = 0.0
        var sumWeight = 0.0

        for (log in logs) {
            if (log.lat == null || log.lon == null || log.rssi == null) continue
            
            val rssiDbm = maxOf(minOf(log.rssi.toDouble(), -20.0), -140.0)
            val powerMw = 10.0.pow(rssiDbm / 10.0)
            val weight = powerMw.pow(2.0 / maxOf(pathLossExponent, 0.1))
            
            sumLat += log.lat * weight
            sumLon += log.lon * weight
            sumWeight += weight
        }

        return if (sumWeight > 0) {
            Pair(sumLat / sumWeight, sumLon / sumWeight)
        } else {
            Pair(null, null)
        }
    }

    /**
     * Estimate base station positions using circle intersection method
     * Port of server.py cell_map logic
     */
    fun estimateBaseStationPositions(
        cellLogsMap: Map<String, List<CellLog>>,
        pathLossExponent: Double = 2.0,
        refRssiDbm: Double = -40.0,
        refDistM: Double = 1.0,
        bandwidthM: Double = 150.0,
        useIntersectionMethod: Boolean = true
    ): List<EstimatedBaseStation> {
        
        Log.d(TAG, "Estimating positions for ${cellLogsMap.size} cells")
        val results = mutableListOf<EstimatedBaseStation>()

        for ((cellId, logs) in cellLogsMap) {
            if (logs.isEmpty()) {
                results.add(EstimatedBaseStation(cellId, null, null, null, 0))
                continue
            }

            // Get the most recent log's type for this cell
            val cellType = logs.maxByOrNull { it.timestamp }?.type

            // Remove duplicate observations (same position + cell ID), keep latest
            val uniqueLogs = logs.filter { it.lat != null && it.lon != null && it.rssi != null }
                .groupBy { Triple(it.lat, it.lon, it.cellId) }
                .map { (_, duplicates) -> duplicates.maxByOrNull { it.timestamp }!! }

            if (uniqueLogs.size < 2 || !useIntersectionMethod) {
                // Fall back to centroid method
                val (estLat, estLon) = centroidEstimate(uniqueLogs, pathLossExponent)
                results.add(EstimatedBaseStation(cellId, cellType, estLat, estLon, uniqueLogs.size))
                continue
            }

            // Use circle intersection method
            try {
                val estimatedPosition = estimateUsingIntersections(
                    uniqueLogs, pathLossExponent, refRssiDbm, refDistM, bandwidthM
                )
                results.add(EstimatedBaseStation(
                    cellId, cellType, estimatedPosition.first, 
                    estimatedPosition.second, uniqueLogs.size
                ))
            } catch (e: Exception) {
                Log.w(TAG, "Intersection method failed for cell $cellId, falling back to centroid: ${e.message}")
                val (estLat, estLon) = centroidEstimate(uniqueLogs, pathLossExponent)
                results.add(EstimatedBaseStation(cellId, cellType, estLat, estLon, uniqueLogs.size))
            }
        }

        Log.d(TAG, "Completed estimation for ${results.size} base stations")
        return results
    }

    private fun estimateUsingIntersections(
        logs: List<CellLog>,
        pathLossExponent: Double,
        refRssiDbm: Double,
        refDistM: Double,
        bandwidthM: Double
    ): Pair<Double?, Double?> {
        
        // Calculate centroid for local coordinate system
        val lat0 = logs.mapNotNull { it.lat }.average()
        val lon0 = logs.mapNotNull { it.lon }.average()

        // Convert to local coordinates and calculate distances
        val circles = logs.mapNotNull { log ->
            if (log.lat == null || log.lon == null || log.rssi == null) return@mapNotNull null
            
            val rssiDbm = maxOf(minOf(log.rssi.toDouble(), -20.0), -140.0)
            val distanceM = rssiToDistanceM(rssiDbm, pathLossExponent, refRssiDbm, refDistM)
            val point = latLonToXyM(log.lat, log.lon, lat0, lon0)
            
            Triple(point.x, point.y, distanceM)
        }

        if (circles.size < 2) {
            return Pair(null, null)
        }

        // Find all circle intersections
        val intersections = mutableListOf<CircleIntersection>()
        for (i in circles.indices) {
            val (x0, y0, r0) = circles[i]
            for (j in i + 1 until circles.size) {
                val (x1, y1, r1) = circles[j]
                intersections.addAll(circleIntersections(x0, y0, r0, x1, y1, r1))
            }
        }

        if (intersections.isEmpty()) {
            return Pair(null, null)
        }

        // Find the point with maximum density within bandwidth
        val bw = maxOf(5.0, bandwidthM)
        var bestScore = -1.0
        var bestIntersection: CircleIntersection? = null

        for (intersection in intersections) {
            val score = intersections.sumOf { other ->
                val distSq = (other.x - intersection.x).pow(2) + (other.y - intersection.y).pow(2)
                if (distSq <= bw * bw) other.weight else 0.0
            }
            
            if (score > bestScore) {
                bestScore = score
                bestIntersection = intersection
            }
        }

        bestIntersection ?: return Pair(null, null)

        // Calculate weighted average within the best neighborhood
        var sumX = 0.0
        var sumY = 0.0
        var sumWeight = 0.0

        for (intersection in intersections) {
            val distSq = (intersection.x - bestIntersection.x).pow(2) + 
                        (intersection.y - bestIntersection.y).pow(2)
            
            if (distSq <= bw * bw) {
                val distanceWeight = 1.0 - sqrt(distSq) / bw
                val totalWeight = intersection.weight * distanceWeight
                
                sumX += intersection.x * totalWeight
                sumY += intersection.y * totalWeight
                sumWeight += totalWeight
            }
        }

        val finalX = if (sumWeight > 0) sumX / sumWeight else bestIntersection.x
        val finalY = if (sumWeight > 0) sumY / sumWeight else bestIntersection.y

        // Convert back to lat/lon
        val (estLat, estLon) = xyToLatLon(finalX, finalY, lat0, lon0)
        return Pair(estLat, estLon)
    }
}