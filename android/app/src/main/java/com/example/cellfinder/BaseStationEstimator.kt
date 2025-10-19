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
     * Weighted Least Squares (WLS) trilateration
     * 
     * Iteratively estimates position by minimizing weighted squared distance errors.
     * This is a more advanced algorithm than simple trilateration.
     * 
     * @param circles List of (x, y, radius) in meters
     * @param maxIter Maximum number of iterations
     * @param convergenceThreshold Convergence threshold in meters
     * @return Estimated position (x, y) or null
     */
    private fun wlsTrilateration(
        circles: List<Triple<Double, Double, Double>>,
        maxIter: Int = 20,
        convergenceThreshold: Double = 0.1
    ): Point? {
        if (circles.size < 3) return null
        
        // Initial estimate: centroid of observation points
        var xEst = circles.map { it.first }.average()
        var yEst = circles.map { it.second }.average()
        
        for (iteration in 0 until maxIter) {
            val H = mutableListOf<DoubleArray>()
            val b = mutableListOf<Double>()
            val weights = mutableListOf<Double>()
            
            for ((xi, yi, ri) in circles) {
                val dx = xEst - xi
                val dy = yEst - yi
                var dist = sqrt(dx * dx + dy * dy)
                
                if (dist < 1e-6) dist = 1e-6 // Avoid division by zero
                
                // Jacobian matrix row: [∂f/∂x, ∂f/∂y] = [(x-xi)/d, (y-yi)/d]
                H.add(doubleArrayOf(dx / dist, dy / dist))
                
                // Residual: estimated distance - observed distance
                b.add(dist - ri)
                
                // Weight: smaller distances are more reliable
                val weight = 1.0 / (1.0 + ri / 1000.0)
                weights.add(weight)
            }
            
            // Weight matrix W (diagonal)
            val W = Array(weights.size) { i ->
                DoubleArray(weights.size) { j ->
                    if (i == j) weights[i] else 0.0
                }
            }
            
            // Solve: Δx = (H^T W H)^(-1) H^T W b
            try {
                // H^T W
                val HTW = Array(2) { i ->
                    DoubleArray(H.size) { j ->
                        (0 until H.size).sumOf { k ->
                            H[k][i] * W[k][j]
                        }
                    }
                }
                
                // H^T W H (2x2 matrix)
                val HTWH = Array(2) { i ->
                    DoubleArray(2) { j ->
                        (0 until H.size).sumOf { k ->
                            HTW[i][k] * H[k][j]
                        }
                    }
                }
                
                // Inverse of 2x2 matrix
                val det = HTWH[0][0] * HTWH[1][1] - HTWH[0][1] * HTWH[1][0]
                if (abs(det) < 1e-10) break // Singular matrix
                
                val HTWHInv = arrayOf(
                    doubleArrayOf(HTWH[1][1] / det, -HTWH[0][1] / det),
                    doubleArrayOf(-HTWH[1][0] / det, HTWH[0][0] / det)
                )
                
                // H^T W b
                val HTWb = DoubleArray(2) { i ->
                    (0 until H.size).sumOf { k ->
                        HTW[i][k] * b[k]
                    }
                }
                
                // Δx = (H^T W H)^(-1) H^T W b
                val deltaX = HTWHInv[0][0] * HTWb[0] + HTWHInv[0][1] * HTWb[1]
                val deltaY = HTWHInv[1][0] * HTWb[0] + HTWHInv[1][1] * HTWb[1]
                
                // Update position
                xEst -= deltaX
                yEst -= deltaY
                
                // Check convergence
                val correction = sqrt(deltaX * deltaX + deltaY * deltaY)
                if (correction < convergenceThreshold) break
                
            } catch (e: Exception) {
                Log.w(TAG, "WLS iteration failed: ${e.message}")
                break
            }
        }
        
        return Point(xEst, yEst)
    }

    /**
     * Robust trilateration with outlier removal
     * 
     * Uses a RANSAC-like approach to remove outliers before estimation.
     * 
     * @param circles List of (x, y, radius) in meters
     * @param outlierThreshold Threshold for outlier detection (in multiples of MAD, default 2.5)
     * @return Estimated position (x, y) or null
     */
    private fun robustTrilateration(
        circles: List<Triple<Double, Double, Double>>,
        outlierThreshold: Double = 2.5
    ): Point? {
        if (circles.size < 3) return null
        
        // Initial WLS estimate
        val initialEst = wlsTrilateration(circles) ?: return null
        val xEst = initialEst.x
        val yEst = initialEst.y
        
        // Calculate residuals for each observation
        val residuals = circles.map { (xi, yi, ri) ->
            val dist = sqrt((xEst - xi).pow(2) + (yEst - yi).pow(2))
            abs(dist - ri)
        }
        
        // Calculate median and MAD (Median Absolute Deviation)
        val sortedResiduals = residuals.sorted()
        val median = sortedResiduals[sortedResiduals.size / 2]
        val mad = residuals.map { abs(it - median) }.sorted()[residuals.size / 2]
        
        // If MAD is 0, all data points agree
        if (mad < 1e-6) return initialEst
        
        // Remove outliers using modified Z-score
        val inliers = circles.filterIndexed { index, _ ->
            val modifiedZ = abs(residuals[index] - median) / (1.4826 * mad)
            modifiedZ < outlierThreshold
        }
        
        // Re-estimate with inliers if we have enough and removed some outliers
        return if (inliers.size >= 3 && inliers.size < circles.size) {
            wlsTrilateration(inliers) ?: initialEst
        } else {
            initialEst
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
     * 
     * @param method Estimation method: "wls", "robust", "intersection", or "centroid"
     */
    fun estimateBaseStationPositions(
        cellLogsMap: Map<String, List<CellLog>>,
        pathLossExponent: Double = 2.0,
        refRssiDbm: Double = -40.0,
        refDistM: Double = 1.0,
        bandwidthM: Double = 150.0,
        method: String = "wls"
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

            if (uniqueLogs.size < 2 || method == "centroid") {
                // Fall back to centroid method
                val (estLat, estLon) = centroidEstimate(uniqueLogs, pathLossExponent)
                results.add(EstimatedBaseStation(cellId, cellType, estLat, estLon, uniqueLogs.size))
                continue
            }

            // Calculate circles in local coordinates
            val lat0 = uniqueLogs.mapNotNull { it.lat }.average()
            val lon0 = uniqueLogs.mapNotNull { it.lon }.average()
            
            val circles = uniqueLogs.mapNotNull { log ->
                if (log.lat == null || log.lon == null || log.rssi == null) return@mapNotNull null
                
                val rssiDbm = maxOf(minOf(log.rssi.toDouble(), -20.0), -140.0)
                val distanceM = rssiToDistanceM(rssiDbm, pathLossExponent, refRssiDbm, refDistM)
                val point = latLonToXyM(log.lat, log.lon, lat0, lon0)
                
                Triple(point.x, point.y, distanceM)
            }
            
            if (circles.size < 2) {
                val (estLat, estLon) = centroidEstimate(uniqueLogs, pathLossExponent)
                results.add(EstimatedBaseStation(cellId, cellType, estLat, estLon, uniqueLogs.size))
                continue
            }

            // Use the selected estimation method
            try {
                val estimatedPoint = when (method) {
                    "wls" -> wlsTrilateration(circles)
                    "robust" -> robustTrilateration(circles)
                    "accum", "intersection" -> estimateUsingIntersections(
                        uniqueLogs, pathLossExponent, refRssiDbm, refDistM, bandwidthM
                    ).let { (lat, lon) ->
                        if (lat != null && lon != null) {
                            latLonToXyM(lat, lon, lat0, lon0)
                        } else null
                    }
                    else -> null
                }
                
                if (estimatedPoint != null) {
                    val (estLat, estLon) = xyToLatLon(estimatedPoint.x, estimatedPoint.y, lat0, lon0)
                    results.add(EstimatedBaseStation(cellId, cellType, estLat, estLon, uniqueLogs.size))
                } else {
                    // Fallback to centroid
                    val (estLat, estLon) = centroidEstimate(uniqueLogs, pathLossExponent)
                    results.add(EstimatedBaseStation(cellId, cellType, estLat, estLon, uniqueLogs.size))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Estimation method '$method' failed for cell $cellId, falling back to centroid: ${e.message}")
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