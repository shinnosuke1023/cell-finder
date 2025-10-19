package com.example.cellfinder

import android.util.Log
import org.ejml.simple.SimpleMatrix
import kotlin.math.*

/**
 * Extended Kalman Filter (EKF) engine for estimating fake base station position
 * and radio propagation parameters.
 * 
 * State vector: [x_fbs, y_fbs, P0, eta]
 * - x_fbs, y_fbs: Base station position in UTM coordinates (meters)
 * - P0: Reference received power at 1 meter (dBm)
 * - eta: Path loss exponent
 */
class EKFEngine {
    companion object {
        private const val TAG = "EKFEngine"
    }
    
    // State vector: [x_fbs, y_fbs, P0, eta]
    private var x: SimpleMatrix = SimpleMatrix(4, 1)
    
    // State covariance matrix (uncertainty)
    private var P: SimpleMatrix = SimpleMatrix(4, 4)
    
    // Process noise covariance matrix
    private var Q: SimpleMatrix = SimpleMatrix(4, 4)
    
    // Measurement noise variance (RSSI measurement noise)
    private var R: Double = 9.0  // Variance = (3 dB)^2
    
    private var initialized: Boolean = false
    
    /**
     * Initialize the EKF with an initial position guess.
     * 
     * @param initialUtmLocation Initial guess for base station location in UTM coordinates
     */
    fun initialize(initialUtmLocation: UtmCoord) {
        Log.d(TAG, "Initializing EKF at UTM: x=${initialUtmLocation.x}, y=${initialUtmLocation.y}")
        
        // Initialize state vector
        x.set(0, 0, initialUtmLocation.x)  // x_fbs
        x.set(1, 0, initialUtmLocation.y)  // y_fbs
        x.set(2, 0, -40.0)                  // P0 (reference power at 1m)
        x.set(3, 0, 3.0)                    // eta (path loss exponent)
        
        // Initialize covariance matrix with high uncertainty
        P = SimpleMatrix.identity(4).scale(1000.0)
        
        // Initialize process noise covariance
        Q = SimpleMatrix.identity(4).scale(0.00001)
        
        // Measurement noise variance (RSSI std dev = 3 dBm)
        R = 9.0
        
        initialized = true
        
        Log.d(TAG, "EKF initialized: x_fbs=${x.get(0,0)}, y_fbs=${x.get(1,0)}, " +
                "P0=${x.get(2,0)}, eta=${x.get(3,0)}")
    }
    
    /**
     * Perform one EKF step with new measurement.
     * 
     * @param userUtmLocation User's current position in UTM coordinates
     * @param measuredRssi Measured RSSI value in dBm
     */
    fun step(userUtmLocation: UtmCoord, measuredRssi: Double) {
        if (!initialized) {
            Log.w(TAG, "EKF not initialized, calling initialize first")
            initialize(userUtmLocation)
        }
        
        Log.d(TAG, "EKF step: user=(${userUtmLocation.x}, ${userUtmLocation.y}), RSSI=$measuredRssi")
        
        // ===== PREDICTION PHASE =====
        // For this application, we assume the base station is stationary,
        // so the predicted state is the same as the previous state
        val x_pred = x.copy()
        val P_pred = P.plus(Q)
        
        // ===== UPDATE PHASE =====
        
        // Extract state variables
        val fbs_x = x_pred.get(0, 0)
        val fbs_y = x_pred.get(1, 0)
        val P0 = x_pred.get(2, 0)
        val eta = x_pred.get(3, 0)
        
        val user_x = userUtmLocation.x
        val user_y = userUtmLocation.y
        
        // Calculate distance between user and predicted base station position
        val dx = fbs_x - user_x
        val dy = fbs_y - user_y
        val d = sqrt(dx * dx + dy * dy)
        
        // Avoid division by zero
        val d_safe = maxOf(d, 1.0)
        
        // Calculate expected RSSI using log-distance path loss model
        // RSSI = P0 - 10 * eta * log10(d)
        val expected_rssi = P0 - 10.0 * eta * log10(d_safe)
        
        // Calculate innovation (measurement residual)
        val innovation = measuredRssi - expected_rssi
        
        Log.d(TAG, "Distance: $d_safe m, Expected RSSI: $expected_rssi dBm, " +
                "Innovation: $innovation dB")
        
        // ===== CALCULATE JACOBIAN MATRIX H (1x4) =====
        // H = [∂h/∂x_fbs, ∂h/∂y_fbs, ∂h/∂P0, ∂h/∂eta]
        // where h(x) = P0 - 10 * eta * log10(d)
        
        val d2 = d_safe * d_safe
        val ln10 = ln(10.0)
        val common_term = (10.0 * eta) / (ln10 * d2)
        
        val H = SimpleMatrix(1, 4)
        H.set(0, 0, common_term * dx)        // ∂h/∂x_fbs
        H.set(0, 1, common_term * dy)        // ∂h/∂y_fbs
        H.set(0, 2, 1.0)                     // ∂h/∂P0
        H.set(0, 3, -10.0 * log10(d_safe))  // ∂h/∂eta
        
        // ===== CALCULATE KALMAN GAIN K (4x1) =====
        // K = P_pred * H^T * (H * P_pred * H^T + R)^-1
        
        val H_transpose = H.transpose()
        
        // Innovation covariance: S = H * P_pred * H^T + R
        val S_matrix = H.mult(P_pred).mult(H_transpose)
        val S = S_matrix.get(0, 0) + R
        
        // Kalman gain: K = P_pred * H^T / S
        val K = P_pred.mult(H_transpose).scale(1.0 / S)
        
        // ===== UPDATE STATE =====
        // x = x_pred + K * innovation
        val K_times_innovation = K.scale(innovation)
        x = x_pred.plus(K_times_innovation)
        
        // ===== UPDATE COVARIANCE =====
        // P = (I - K * H) * P_pred
        val I = SimpleMatrix.identity(4)
        val K_times_H = K.mult(H)
        P = (I.minus(K_times_H)).mult(P_pred)
        
        // Ensure P remains positive semi-definite by making it symmetric
        P = P.plus(P.transpose()).scale(0.5)
        
        Log.d(TAG, "Updated state: x_fbs=${x.get(0,0)}, y_fbs=${x.get(1,0)}, " +
                "P0=${x.get(2,0)}, eta=${x.get(3,0)}")
        Log.d(TAG, "Position uncertainty: σx=${sqrt(P.get(0,0))}, σy=${sqrt(P.get(1,1))}")
    }
    
    /**
     * Get the current estimated base station position in UTM coordinates.
     * 
     * @return Estimated position or null if not initialized
     */
    fun getEstimatedPositionUtm(): UtmCoord? {
        if (!initialized) return null
        
        // We need to use the same zone and hemisphere as the initial location
        // For simplicity, we'll reconstruct with a default zone
        // In a real application, you'd need to track the original zone
        return UtmCoord(
            x = x.get(0, 0),
            y = x.get(1, 0),
            zone = 54,  // Default zone for Tokyo area
            hemisphere = 'N'
        )
    }
    
    /**
     * Get the current state covariance matrix.
     * 
     * @return Covariance matrix P
     */
    fun getCovariance(): SimpleMatrix {
        return P.copy()
    }
    
    /**
     * Get the position uncertainty (standard deviation) in meters.
     * 
     * @return Pair of (σx, σy) standard deviations
     */
    fun getPositionUncertainty(): Pair<Double, Double> {
        if (!initialized) return Pair(Double.MAX_VALUE, Double.MAX_VALUE)
        
        val sigma_x = sqrt(abs(P.get(0, 0)))
        val sigma_y = sqrt(abs(P.get(1, 1)))
        
        return Pair(sigma_x, sigma_y)
    }
    
    /**
     * Get the circular error radius for visualization.
     * Based on the trace of the position covariance submatrix.
     * 
     * @return Radius in meters
     */
    fun getErrorRadius(): Double {
        if (!initialized) return Double.MAX_VALUE
        
        // Get the 2x2 position submatrix
        val trace = P.get(0, 0) + P.get(1, 1)
        
        // Return radius as sqrt of trace
        return sqrt(maxOf(0.0, trace))
    }
    
    /**
     * Check if the EKF has been initialized.
     */
    fun isInitialized(): Boolean = initialized
    
    /**
     * Get current estimated path loss parameters.
     * 
     * @return Pair of (P0, eta)
     */
    fun getPathLossParameters(): Pair<Double, Double> {
        if (!initialized) return Pair(-40.0, 3.0)
        
        return Pair(x.get(2, 0), x.get(3, 0))
    }
    
    /**
     * Reset the EKF state.
     */
    fun reset() {
        initialized = false
        Log.d(TAG, "EKF reset")
    }
}
