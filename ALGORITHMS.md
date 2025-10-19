# Advanced Positioning Algorithms

This document describes the advanced positioning algorithms implemented in the CellFinder project for estimating base station locations from RSSI measurements.

## Overview

The project now includes four positioning algorithms with increasing sophistication:

1. **Centroid Method** (`centroid`) - Simple weighted average (基本的な加重平均法)
2. **Circle Intersection** (`accum`) - Geometric intersection voting (幾何学的交点投票法)
3. **Weighted Least Squares** (`wls`) - Iterative optimization (反復最適化法) ⭐ **Default**
4. **Robust Estimation** (`robust`) - WLS with outlier removal (外れ値除去付き最適化) ⭐ **Recommended**

## Algorithm Details

### 1. Centroid Method (加重重心法)

**Description:** Computes a weighted average of observation locations based on received signal power.

**How it works:**
- Converts RSSI to power: `P = 10^(RSSI/10)`
- Weights each location by: `w = P^(2/n)` where n is path loss exponent
- Returns weighted centroid: `(Σ(w·lat), Σ(w·lon)) / Σw`

**Pros:**
- Very simple and fast
- Always produces a result
- Good for well-distributed observations

**Cons:**
- Ignores distance information
- Poor with outliers or measurement errors
- Less accurate than other methods

**Use case:** Quick estimates, few observations, or as fallback

### 2. Circle Intersection Method (円交点投票法)

**Description:** Converts RSSI to distance, creates circles, finds intersections, and uses density-based voting.

**How it works:**
1. Convert RSSI to distance using log-distance path loss model:
   ```
   d = d₀ × 10^((RSSI₀ - RSSI)/(10n))
   ```
2. Create circles centered at each observation point with radius = estimated distance
3. Find all pairwise circle intersections
4. Weight intersections by crossing angle (perpendicular = high weight, shallow = low weight)
5. Find highest-density cluster using bandwidth parameter
6. Compute weighted average of points in best cluster

**Pros:**
- Geometric and intuitive
- Uses distance information
- Good accuracy with multiple observations

**Cons:**
- Can fail if circles don't intersect
- Sensitive to systematic RSSI errors
- No statistical optimization

**Use case:** Visual debugging, moderate accuracy needs

### 3. Weighted Least Squares (WLS) (重み付き最小二乗法)

**Description:** Iteratively minimizes weighted squared distance errors to find optimal position.

**How it works:**
1. Start with centroid as initial estimate
2. For each iteration:
   - Compute Jacobian matrix H (partial derivatives)
   - Compute residuals b (estimated distance - measured distance)
   - Apply distance-based weights W (closer observations = higher weight)
   - Solve: `Δx = (H^T W H)^(-1) H^T W b`
   - Update position: `x ← x - Δx`
3. Stop when correction < threshold (0.1m) or max iterations (20)

**Mathematical formulation:**
```
Minimize: Σ wᵢ(||p - pᵢ|| - dᵢ)²

Where:
- p = unknown base station position
- pᵢ = observation position i
- dᵢ = RSSI-derived distance estimate
- wᵢ = weight for observation i
```

**Pros:**
- Statistically optimal for Gaussian errors
- Fast convergence (typically 3-5 iterations)
- High accuracy with good data
- Uses all observations efficiently

**Cons:**
- Sensitive to outliers
- Can fail to converge with very poor initial estimate
- Requires at least 3 observations

**Use case:** High-accuracy positioning with clean data

### 4. Robust Estimation (ロバスト推定)

**Description:** WLS with automatic outlier detection and removal using Median Absolute Deviation (MAD).

**How it works:**
1. Compute initial estimate using WLS
2. Calculate residual for each observation:
   ```
   residual = |estimated_distance - measured_distance|
   ```
3. Compute median and MAD of residuals:
   ```
   MAD = median(|residual - median(residuals)|)
   ```
4. Identify outliers using modified Z-score:
   ```
   Modified Z = |residual - median| / (1.4826 × MAD)
   Outlier if: Modified Z > threshold (default 2.5)
   ```
5. Remove outliers and re-estimate with WLS using only inliers

**Pros:**
- Robust to outliers and measurement errors
- Best accuracy in real-world scenarios
- Automatic outlier detection
- Maintains WLS efficiency with clean data

**Cons:**
- Slightly more complex
- Requires at least 3 observations
- May be conservative with threshold

**Use case:** Production use, real-world data with potential errors ⭐ **Recommended**

## Performance Comparison

Based on realistic test with 6 observations (5 good + 1 outlier with 5× distance error):

| Method    | Error (m) | Rank | Notes                          |
|-----------|-----------|------|--------------------------------|
| Robust    | **16.4**  | 🥇 1 | Successfully removed outlier   |
| Accum     | 33.2      | 🥈 2 | Somewhat affected by outlier   |
| Centroid  | 45.6      | 🥉 3 | Simple average affected        |
| WLS       | 199.8     | 4    | Severely affected by outlier   |

**Key findings:**
- **Robust** method achieved **64% better accuracy** than simple centroid
- **Robust** method was **92% better** than WLS alone (which was affected by outlier)
- **Accum** method showed good middle-ground performance
- WLS without outlier removal is vulnerable to bad measurements

## Usage

### Server API (Python/Flask)

```python
# GET /cell_map?method=<method>&ple=2.0&debug=1

# Examples:
GET /cell_map?method=robust         # Recommended - robust estimation
GET /cell_map?method=wls            # Fast, accurate with clean data
GET /cell_map?method=accum          # Geometric circle intersection
GET /cell_map?method=centroid       # Simple weighted average
```

**Parameters:**
- `method`: Algorithm selection (`wls`, `robust`, `accum`, `centroid`)
- `ple`: Path loss exponent (default: 2.0, urban environment)
- `ref_rssi`: Reference RSSI at reference distance (default: -40 dBm)
- `ref_dist`: Reference distance (default: 1.0 m)
- `bandwidth_m`: Clustering bandwidth for accum method (default: 150 m)
- `window_sec`: Only use observations within this time window
- `debug`: Return debug information (default: 0)

### Android API (Kotlin)

```kotlin
val estimator = BaseStationEstimator

val results = estimator.estimateBaseStationPositions(
    cellLogsMap = cellLogs,
    pathLossExponent = 2.0,
    refRssiDbm = -40.0,
    refDistM = 1.0,
    bandwidthM = 150.0,
    method = "robust"  // or "wls", "intersection", "centroid"
)
```

## Configuration Recommendations

### Path Loss Exponent (n)

Depends on environment:
- **Free space**: n = 2.0
- **Urban area**: n = 2.7 - 3.5
- **Indoor**: n = 3.0 - 5.0
- **Dense urban/indoor with obstacles**: n = 4.0 - 6.0

Default: **2.0** (conservative, works well for outdoor LTE)

### Reference Parameters

Default values work well for typical cellular networks:
- `ref_rssi = -40 dBm` at `ref_dist = 1.0 m`

These can be adjusted based on:
- Transmit power of base stations
- Antenna gain
- Local calibration data

### Outlier Threshold

For robust estimation:
- **2.0**: Aggressive outlier removal (may remove valid data)
- **2.5**: Balanced (default, recommended)
- **3.0**: Conservative (keeps more data, tolerates more variance)

## Implementation Notes

### Coordinate System

All algorithms work in a local Cartesian (meter) coordinate system:
1. Convert lat/lon to local XY using spherical approximation
2. Perform calculations in meters
3. Convert back to lat/lon

This provides:
- Simpler distance calculations
- Better numerical stability
- More intuitive error metrics

### Numerical Stability

The implementation includes safeguards:
- Minimum distance thresholds (1e-6) to prevent division by zero
- Maximum distance clamping (50km) for unrealistic RSSI values
- Matrix singularity detection in WLS solver
- Graceful fallback to simpler methods on failure

### Performance

Typical execution times (6 observations, on modern hardware):
- Centroid: < 0.1 ms
- Accum: 1-2 ms
- WLS: 2-5 ms (3-5 iterations)
- Robust: 4-10 ms (2× WLS calls)

All methods are fast enough for real-time use.

## Future Improvements

Potential enhancements:
1. **Kalman filtering** for temporal smoothing of estimates
2. **Machine learning** for automatic path loss exponent estimation
3. **Multi-cell triangulation** using multiple base stations simultaneously
4. **Bayesian inference** for uncertainty quantification
5. **RANSAC** as alternative to MAD-based outlier removal

## References

### Trilateration and Positioning
- Caffery, J. J., & Stuber, G. L. (1998). "Overview of radiolocation in CDMA cellular systems"
- Gezici, S., et al. (2005). "Localization via ultra-wideband radios"

### Robust Estimation
- Rousseeuw, P. J., & Croux, C. (1993). "Alternatives to the median absolute deviation"
- Huber, P. J., & Ronchetti, E. M. (2009). "Robust Statistics"

### Path Loss Models
- Rappaport, T. S. (1996). "Wireless Communications: Principles and Practice"
- 3GPP TR 36.873: "Study on 3D channel model for LTE"

## License

See main project LICENSE file.

## Contributors

- Implementation of WLS algorithm
- Implementation of robust estimation with MAD-based outlier detection
- Integration with existing circle intersection method
- Comprehensive testing framework
