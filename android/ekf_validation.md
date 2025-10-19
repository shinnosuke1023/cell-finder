# EKF Implementation Validation

## Manual Validation Checklist

### 1. UTM Conversion Accuracy

Test the UTM coordinate conversion with known values:

**Test Case: Tokyo Station**
- Input: (35.681200°, 139.767100°)
- Expected UTM Zone: 54N
- Expected UTM: approximately (387773, 3950374)

**Test Case: Sydney Opera House**
- Input: (-33.8568°, 151.2153°)
- Expected UTM Zone: 56S
- Expected UTM: approximately (334873, 6252264)

### 2. EKF Initialization

**Expected Initial State:**
```
x = [user_x, user_y, -40.0, 3.0]ᵀ (in UTM)
P = diag([1000, 1000, 1000, 1000])
Q = diag([0.00001, 0.00001, 0.00001, 0.00001])
R = 9.0
```

### 3. Jacobian Matrix Calculation

**Test Scenario:**
- Base station at: (1000, 2000) meters UTM
- User at: (1100, 2100) meters UTM
- Distance: d = √((100)² + (100)²) = 141.42 meters
- P0 = -40 dBm
- eta = 3.0

**Expected Jacobian H:**
```
common_term = (10 * 3.0) / (ln(10) * 141.42²)
            = 30 / (2.3026 * 19999.6964)
            = 30 / 46051.245
            = 0.000652

H[0] = 0.000652 * 100 = 0.0652  (∂h/∂x_fbs)
H[1] = 0.000652 * 100 = 0.0652  (∂h/∂y_fbs)
H[2] = 1.0                       (∂h/∂P0)
H[3] = -10 * log10(141.42) = -21.51  (∂h/∂eta)
```

**Expected RSSI:**
```
RSSI = -40 - 10 * 3.0 * log10(141.42)
     = -40 - 30 * 2.151
     = -104.53 dBm
```

### 4. Kalman Gain Computation

For initial step with high uncertainty (P = 1000·I):

**Expected behavior:**
- K should be large initially (high gain)
- K[0,0] and K[1,0] should be >> 0 (position updates)
- K[2,0] and K[3,0] should be > 0 (parameter updates)

### 5. State Update

**Test Scenario:**
- Predicted state: x_pred = [1000, 2000, -40, 3.0]ᵀ
- Measured RSSI: -100 dBm
- Expected RSSI: -104.53 dBm
- Innovation: -100 - (-104.53) = 4.53 dB

**Expected behavior:**
- Positive innovation → base station is closer than predicted
- x_fbs and y_fbs should move toward user position
- eta might decrease slightly (stronger signal than expected)

### 6. Covariance Update

**Expected behavior:**
- P should decrease after each update
- P[0,0] and P[1,1] (position variance) should converge
- Error radius should decrease: radius = √(P[0,0] + P[1,1])

### 7. Convergence Test

**Simulated Scenario:**
- True base station: (1000, 2000) UTM
- True parameters: P0 = -45 dBm, eta = 2.5
- User circular path around base station: radius 500m
- 50 measurements with Gaussian noise (σ = 3 dB)

**Expected convergence:**
```
Measurements   Position Error   P0 Error   eta Error   Error Radius
    10            ~200m           ~5 dB      ~0.5         ~300m
    20            ~100m           ~3 dB      ~0.3         ~150m
    50            ~50m            ~1 dB      ~0.1         ~80m
```

## Code Review Checklist

### EKFEngine.kt
- ✓ State vector properly initialized (4x1)
- ✓ Covariance matrix symmetric and positive semi-definite
- ✓ Process noise Q small but non-zero
- ✓ Measurement noise R reasonable (9.0 = 3² dB²)
- ✓ Distance calculation handles zero division
- ✓ Jacobian matrix correctly computed
- ✓ Kalman gain uses proper matrix inversion
- ✓ Covariance update uses Joseph form for numerical stability
- ✓ Error radius computed from trace

### UtmConverter
- ✓ Zone calculation correct: zone = floor((lon + 180) / 6) + 1
- ✓ Hemisphere detection: 'N' if lat ≥ 0, 'S' otherwise
- ✓ WGS84 ellipsoid parameters correct
- ✓ Forward and inverse conversions consistent
- ✓ Handles edge cases (poles, zone boundaries)

### TrackingService
- ✓ Runs as foreground service with notification
- ✓ 2-second update interval
- ✓ Coroutine scope properly managed
- ✓ Location and cell info providers initialized
- ✓ UTM zone/hemisphere tracked across measurements
- ✓ LiveData updates posted on correct thread

### LocationProvider
- ✓ Permission checks before location access
- ✓ Uses high-accuracy priority
- ✓ Coroutine-based async operations
- ✓ Cancellation token for cleanup

### CellInfoProvider
- ✓ Permission checks before cell access
- ✓ Filters for 2G/GSM cells
- ✓ Auto-selects strongest cell as target
- ✓ Parses cell ID correctly
- ✓ Returns RSSI in dBm

## Integration Test Scenarios

### Scenario 1: Stationary User
**Setup:**
- User stays at fixed location
- Target cell has stable RSSI

**Expected:**
- Position estimate should remain stable
- Error radius should decrease but not to zero (measurement noise)
- Parameter estimates should stabilize

### Scenario 2: Moving User (Circle)
**Setup:**
- User walks in circle around base station
- Radius: 100-500 meters
- Multiple measurements per circuit

**Expected:**
- Position estimate should converge to center
- Error radius should decrease significantly
- Parameters should be well-estimated

### Scenario 3: Moving User (Linear)
**Setup:**
- User walks in straight line
- Crosses different distances from base station

**Expected:**
- Position estimate should adjust along perpendicular
- Error radius should be larger perpendicular to motion
- Requires multiple passes for good estimate

### Scenario 4: RSSI Noise
**Setup:**
- RSSI fluctuates ±5 dB
- Typical urban environment

**Expected:**
- EKF should smooth out noise
- Convergence slower but still stable
- Error radius accounts for uncertainty

### Scenario 5: Multiple Cells
**Setup:**
- Multiple 2G cells visible
- Target cell changes mid-tracking

**Expected:**
- Service should lock onto strongest cell
- EKF should reset when cell changes
- Clear indication in UI

## Performance Benchmarks

### Memory Usage
- EKF state: 4 doubles = 32 bytes
- Covariance P: 16 doubles = 128 bytes
- EJML overhead: ~1 KB
- Total per EKF instance: < 5 KB

### CPU Usage
- Matrix operations: ~1 ms per step
- Location updates: ~10 ms
- Cell info query: ~5 ms
- Total per cycle: < 20 ms (negligible)

### Battery Impact
- Foreground service: minimal
- Location updates (2s): low impact
- CPU usage: negligible
- Expected battery drain: < 5% per hour

## Known Limitations

1. **Single Target:** Only tracks one base station at a time
2. **2G Focus:** Primarily designed for GSM/2G cells
3. **Stationary Assumption:** Assumes base station doesn't move
4. **Line-of-Sight:** Performance degrades with obstacles
5. **NLOS Bias:** Non-line-of-sight can cause positive bias
6. **Initial Guess:** Requires some movement for convergence

## Validation Results

### Build Status
- [ ] Project builds successfully
- [ ] No compilation errors
- [ ] All dependencies resolved

### Runtime Tests
- [ ] Service starts without crashes
- [ ] Location updates received
- [ ] Cell info retrieved correctly
- [ ] EKF step executes without errors
- [ ] Map visualization displays correctly
- [ ] Error circle updates smoothly
- [ ] Trajectory renders properly

### Accuracy Tests
- [ ] UTM conversion verified against external tools
- [ ] Jacobian verified through numerical differentiation
- [ ] Convergence verified with simulated data
- [ ] Real-world test shows reasonable estimates

## Validation Tools

### External UTM Converter
https://www.earthpoint.us/Convert.aspx

### RSSI Calculator
Path loss formula: PL(d) = PL(d0) + 10n·log10(d/d0)
RSSI = Tx_power - PL(d)

### Matrix Calculator
https://matrixcalc.org/

### Numerical Differentiation
For Jacobian verification:
∂f/∂x ≈ (f(x+h) - f(x-h)) / (2h)
with h = 0.0001
