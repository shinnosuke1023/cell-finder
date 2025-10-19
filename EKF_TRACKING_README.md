# EKF-Based 2G Fake Base Station Location Estimation

This Android application implements a standalone Extended Kalman Filter (EKF) system for estimating the location of a single 2G fake base station (IMSI catcher) in real-time.

## Overview

The application uses a self-calibrating Extended Kalman Filter that simultaneously estimates:
- **Base station position** (x_fbs, y_fbs) in UTM coordinates
- **Reference power** P0 at 1 meter distance (dBm)
- **Path loss exponent** η (eta)

## Key Features

### 1. Self-Calibrating EKF
- Adapts to local radio propagation conditions
- Continuously refines both position and propagation parameters
- Uses log-distance path loss model: RSSI = P0 - 10·η·log₁₀(d)

### 2. UTM Coordinate System
- All calculations performed in UTM (Universal Transverse Mercator) coordinates
- Provides accurate metric-based distance calculations
- Automatic conversion to/from latitude/longitude for display

### 3. Real-Time Visualization
- Orange marker: Estimated base station location
- Blue marker: Current user position
- Orange circle: Position uncertainty (error radius)
- Blue line: User trajectory
- Info: Path loss parameters and error metrics

## Usage

### Starting EKF Tracking

1. Grant required permissions (Location and Phone State)
2. Tap "Start EKF Tracking" in MainActivity
3. Open "Show Map" and select "EKF Tracking" mode from the spinner
4. Move around the area to collect measurements
5. Watch the estimated position converge

### Understanding the Display

**Base Station Marker (Orange)**
- Title: "EKF Estimated Base Station"
- Shows: Cell ID, error radius, P0, η

**Error Circle (Orange)**
- Radius represents position uncertainty
- Shrinks as more measurements are collected
- Based on covariance matrix trace: radius = √(P[0,0] + P[1,1])

**User Marker (Blue)**
- Your current location
- Shows: Current RSSI and cell type

**Trajectory (Blue Line)**
- Your movement path
- Limited to last 100 points
- Helps visualize coverage area

## Technical Details

### State Vector
```
x = [x_fbs, y_fbs, P0, eta]ᵀ
```
- x_fbs, y_fbs: Base station position (meters, UTM)
- P0: Reference power at 1m (dBm)
- eta: Path loss exponent (typically 2-4)

### EKF Algorithm

**Initialization:**
```
x = [user_x, user_y, -40.0, 3.0]ᵀ
P = 1000·I₄
Q = 0.00001·I₄
R = 9.0 (3 dB std dev)
```

**Prediction:**
```
x_pred = x (stationary base station)
P_pred = P + Q
```

**Update:**
```
d = √((x_fbs - user_x)² + (y_fbs - user_y)²)
h(x) = P0 - 10·eta·log₁₀(d)
H = ∇h(x) = [∂h/∂x_fbs, ∂h/∂y_fbs, ∂h/∂P0, ∂h/∂eta]
K = P_pred·Hᵀ·(H·P_pred·Hᵀ + R)⁻¹
x = x_pred + K·(RSSI_measured - h(x_pred))
P = (I - K·H)·P_pred
```

### Jacobian Matrix
```
H = [10η/(ln(10)·d²)·(x_fbs - user_x),
     10η/(ln(10)·d²)·(y_fbs - user_y),
     1,
     -10·log₁₀(d)]
```

## Architecture

### Components

1. **EKFEngine.kt**
   - Core EKF implementation using EJML
   - State prediction and update
   - Covariance management

2. **TrackingService.kt**
   - Foreground service for continuous tracking
   - 2-second update cycle
   - LiveData for state updates

3. **LocationProvider.kt**
   - Wrapper for FusedLocationProviderClient
   - Coroutine-based location access

4. **CellInfoProvider.kt**
   - Wrapper for TelephonyManager
   - Focuses on 2G/GSM cells
   - Automatic target cell selection

5. **UtmCoord.kt / UtmConverter**
   - UTM coordinate system implementation
   - Lat/Lon ↔ UTM conversion

6. **MapViewModel.kt**
   - MVVM architecture
   - Manages tracking state
   - Trajectory management

7. **MapsActivity.kt / MapFragment.kt**
   - Map visualization
   - Multiple display modes
   - Real-time updates

## Dependencies

```gradle
// EJML for matrix operations
implementation 'org.ejml:ejml-simple:0.43.1'

// AndroidX Lifecycle
implementation 'androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2'
implementation 'androidx.lifecycle:lifecycle-livedata-ktx:2.6.2'

// Kotlin Coroutines
implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'

// Google Play Services
implementation 'com.google.android.gms:play-services-location:21.0.1'
implementation 'com.google.android.gms:play-services-maps:18.2.0'
```

## Minimum Requirements

- Android 8.0 (API 26) or higher
- GPS/Location services enabled
- 2G/GSM network (for fake base station detection)
- Phone state permission (for cell info)

## Performance Characteristics

### Convergence
- Initial error: ~1000m (high uncertainty)
- After 10-20 measurements: ~100-500m
- After 50+ measurements: ~10-100m
- Depends on:
  - Movement pattern
  - RSSI stability
  - Signal strength

### Update Rate
- 2 seconds per EKF step
- Adaptive to location update availability
- Low battery impact (foreground service)

## Comparison with Circle Intersection Method

| Feature | EKF | Circle Intersection |
|---------|-----|-------------------|
| Real-time | ✓ | ✗ |
| Self-calibrating | ✓ | ✗ |
| Uncertainty estimate | ✓ | ✗ |
| Data requirements | Low | High |
| Accuracy (steady state) | High | High |
| Convergence speed | Fast | N/A |

## Troubleshooting

### No measurements
- Check if TrackingService is running
- Verify location permissions
- Ensure GPS is enabled
- Confirm 2G cell is available

### High uncertainty
- Move around more to collect diverse measurements
- Check RSSI values are reasonable (-120 to -20 dBm)
- Ensure consistent cell ID

### Slow convergence
- Increase movement distance
- Check for RSSI fluctuations
- Verify stable cell connection

## Future Enhancements

- Multi-target tracking (multiple fake base stations)
- Particle filter for non-Gaussian noise
- Map-based motion model
- Historical data replay
- Export tracking results

## References

1. Kalman, R. E. (1960). "A New Approach to Linear Filtering and Prediction Problems"
2. Welch, G., & Bishop, G. (2006). "An Introduction to the Kalman Filter"
3. Bar-Shalom, Y., et al. (2001). "Estimation with Applications to Tracking and Navigation"
4. Rappaport, T. S. (2002). "Wireless Communications: Principles and Practice"

## License

See parent project LICENSE file.
