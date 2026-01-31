package com.example.cellfinder

import kotlin.math.*

/**
 * Represents a coordinate in the UTM (Universal Transverse Mercator) coordinate system.
 * UTM uses meters as the unit of measurement and is suitable for accurate distance calculations.
 */
data class UtmCoord(
    val x: Double,      // Easting in meters
    val y: Double,      // Northing in meters
    val zone: Int,      // UTM zone number (1-60)
    val hemisphere: Char // 'N' or 'S'
)

/**
 * Utility object for converting between Lat/Lon and UTM coordinates.
 * Implements simplified UTM projection suitable for local calculations.
 */
object UtmConverter {
    private const val WGS84_A = 6378137.0          // Semi-major axis (meters)
    private const val WGS84_E = 0.0818191908       // First eccentricity
    private const val WGS84_E2 = 0.00669438        // e^2
    private const val K0 = 0.9996                  // Scale factor
    private const val E0 = 500000.0                // False Easting
    private const val N0 = 0.0                     // False Northing (Northern hemisphere)
    
    /**
     * Convert latitude/longitude to UTM coordinates.
     * 
     * @param lat Latitude in degrees
     * @param lon Longitude in degrees
     * @return UTM coordinates
     */
    fun latLonToUtm(lat: Double, lon: Double): UtmCoord {
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)
        
        // Calculate UTM zone
        val zone = ((lon + 180.0) / 6.0).toInt() + 1
        val hemisphere = if (lat >= 0) 'N' else 'S'
        
        // Central meridian for the zone
        val lon0 = ((zone - 1) * 6 - 180 + 3).toDouble()
        val lon0Rad = Math.toRadians(lon0)
        
        val n = WGS84_A / sqrt(1 - WGS84_E2 * sin(latRad).pow(2))
        val t = tan(latRad).pow(2)
        val c = WGS84_E2 * cos(latRad).pow(2) / (1 - WGS84_E2)
        val a = (lonRad - lon0Rad) * cos(latRad)
        
        // Calculate M (meridional arc)
        val e4 = WGS84_E2.pow(2)
        val e6 = WGS84_E2.pow(3)
        val m = WGS84_A * ((1 - WGS84_E2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * latRad
                - (3 * WGS84_E2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * sin(2 * latRad)
                + (15 * e4 / 256 + 45 * e6 / 1024) * sin(4 * latRad)
                - (35 * e6 / 3072) * sin(6 * latRad))
        
        // Calculate UTM coordinates
        val x = E0 + K0 * n * (a + (1 - t + c) * a.pow(3) / 6 
                + (5 - 18 * t + t.pow(2) + 72 * c - 58 * WGS84_E2) * a.pow(5) / 120)
        
        val y = K0 * (m + n * tan(latRad) * (a.pow(2) / 2 
                + (5 - t + 9 * c + 4 * c.pow(2)) * a.pow(4) / 24
                + (61 - 58 * t + t.pow(2) + 600 * c - 330 * WGS84_E2) * a.pow(6) / 720))
        
        // Adjust for southern hemisphere
        val yFinal = if (hemisphere == 'S') y + 10000000.0 else y
        
        return UtmCoord(x, yFinal, zone, hemisphere)
    }
    
    /**
     * Convert UTM coordinates back to latitude/longitude.
     * 
     * @param utm UTM coordinates
     * @return Pair of (latitude, longitude) in degrees
     */
    fun utmToLatLon(utm: UtmCoord): Pair<Double, Double> {
        val x = utm.x - E0
        val y = if (utm.hemisphere == 'S') utm.y - 10000000.0 else utm.y
        
        // Central meridian for the zone
        val lon0 = ((utm.zone - 1) * 6 - 180 + 3).toDouble()
        
        val m = y / K0
        val mu = m / (WGS84_A * (1 - WGS84_E2 / 4 - 3 * WGS84_E2.pow(2) / 64 
                - 5 * WGS84_E2.pow(3) / 256))
        
        val e1 = (1 - sqrt(1 - WGS84_E2)) / (1 + sqrt(1 - WGS84_E2))
        val phi1 = mu + (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu)
                + (21 * e1.pow(2) / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu)
                + (151 * e1.pow(3) / 96) * sin(6 * mu)
                + (1097 * e1.pow(4) / 512) * sin(8 * mu)
        
        val n1 = WGS84_A / sqrt(1 - WGS84_E2 * sin(phi1).pow(2))
        val t1 = tan(phi1).pow(2)
        val c1 = WGS84_E2 * cos(phi1).pow(2) / (1 - WGS84_E2)
        val r1 = WGS84_A * (1 - WGS84_E2) / (1 - WGS84_E2 * sin(phi1).pow(2)).pow(1.5)
        val d = x / (n1 * K0)
        
        // Calculate latitude
        val lat = phi1 - (n1 * tan(phi1) / r1) * (d.pow(2) / 2 
                - (5 + 3 * t1 + 10 * c1 - 4 * c1.pow(2) - 9 * WGS84_E2) * d.pow(4) / 24
                + (61 + 90 * t1 + 298 * c1 + 45 * t1.pow(2) - 252 * WGS84_E2 
                - 3 * c1.pow(2)) * d.pow(6) / 720)
        
        // Calculate longitude
        val lon = (d - (1 + 2 * t1 + c1) * d.pow(3) / 6
                + (5 - 2 * c1 + 28 * t1 - 3 * c1.pow(2) + 8 * WGS84_E2 
                + 24 * t1.pow(2)) * d.pow(5) / 120) / cos(phi1)
        
        return Pair(Math.toDegrees(lat), lon0 + Math.toDegrees(lon))
    }
}
