# Mathematical Derivations for EKF-Based Base Station Tracking

## 1. Log-Distance Path Loss Model

### Basic Model
The received signal strength (RSSI) follows the log-distance path loss model:

```
RSSI(d) = P₀ - 10η log₁₀(d)
```

Where:
- `P₀`: Reference received power at 1 meter (dBm)
- `η` (eta): Path loss exponent (typically 2-4)
- `d`: Distance from transmitter to receiver (meters)

### Rationale
This model captures the fundamental physics of electromagnetic wave propagation:
- Free space: η = 2 (inverse square law)
- Urban environment: η = 2.7 - 3.5
- Indoor: η = 3 - 5

## 2. State Space Model

### State Vector
```
x = [x_fbs]     Base station x-coordinate (UTM, meters)
    [y_fbs]     Base station y-coordinate (UTM, meters)
    [P₀   ]     Reference power at 1m (dBm)
    [η    ]     Path loss exponent (dimensionless)
```

### State Transition (Prediction)
Assuming stationary base station:
```
x_{k+1|k} = x_{k|k}
```

Process noise accounts for small variations:
```
P_{k+1|k} = P_{k|k} + Q
```

Where `Q` is the process noise covariance (very small for stationary base station).

### Measurement Model
The measurement is RSSI at user position (x_user, y_user):

```
z_k = h(x_k) + v_k

where h(x) = P₀ - 10η log₁₀(d(x))
      d(x) = √[(x_fbs - x_user)² + (y_fbs - y_user)²]
      v_k ~ N(0, R)  measurement noise
```

## 3. Jacobian Matrix Derivation

The Jacobian H is the gradient of h(x) with respect to x:

```
H = ∇h(x) = [∂h/∂x_fbs, ∂h/∂y_fbs, ∂h/∂P₀, ∂h/∂η]
```

### Partial Derivatives

#### (1) ∂h/∂x_fbs

Starting from:
```
h(x) = P₀ - 10η log₁₀(d)
d = √[(x_fbs - x_user)² + (y_fbs - y_user)²]
```

Using chain rule:
```
∂h/∂x_fbs = -10η · ∂log₁₀(d)/∂x_fbs
          = -10η · (1/(d·ln(10))) · ∂d/∂x_fbs
```

Now:
```
∂d/∂x_fbs = ∂/∂x_fbs √[(x_fbs - x_user)² + (y_fbs - y_user)²]
          = (1/2) · [2(x_fbs - x_user)] / d
          = (x_fbs - x_user) / d
```

Therefore:
```
∂h/∂x_fbs = -10η · (1/(d·ln(10))) · (x_fbs - x_user)/d
          = -10η(x_fbs - x_user) / (d²·ln(10))
```

Or equivalently:
```
∂h/∂x_fbs = (10η)/(ln(10)·d²) · (x_fbs - x_user)
```

#### (2) ∂h/∂y_fbs

By symmetry:
```
∂h/∂y_fbs = (10η)/(ln(10)·d²) · (y_fbs - y_user)
```

#### (3) ∂h/∂P₀

Trivially:
```
∂h/∂P₀ = 1
```

#### (4) ∂h/∂η

```
∂h/∂η = ∂/∂η [P₀ - 10η log₁₀(d)]
      = -10 log₁₀(d)
```

### Complete Jacobian

```
H = [(10η)/(ln(10)·d²)·(x_fbs - x_user),
     (10η)/(ln(10)·d²)·(y_fbs - y_user),
     1,
     -10·log₁₀(d)]
```

## 4. Kalman Gain Calculation

### Innovation Covariance
```
S = H·P_{k|k-1}·Hᵀ + R
```

Since H is 1×4 and P is 4×4:
- `H·P` is 1×4
- `H·P·Hᵀ` is 1×1 (scalar)
- `S` is scalar

### Kalman Gain
```
K = P_{k|k-1}·Hᵀ·S⁻¹
```

Where:
- `P_{k|k-1}` is 4×4
- `Hᵀ` is 4×1
- `S⁻¹` is scalar
- `K` is 4×1

## 5. State Update Equations

### Innovation (Measurement Residual)
```
y_k = z_k - h(x_{k|k-1})
    = RSSI_measured - [P₀ - 10η log₁₀(d)]
```

### State Update
```
x_{k|k} = x_{k|k-1} + K·y_k
```

Element-wise:
```
x_fbs^new = x_fbs^pred + K₁·y_k
y_fbs^new = y_fbs^pred + K₂·y_k
P₀^new    = P₀^pred    + K₃·y_k
η^new     = η^pred     + K₄·y_k
```

### Covariance Update

Standard form:
```
P_{k|k} = (I - K·H)·P_{k|k-1}
```

Joseph form (more numerically stable):
```
P_{k|k} = (I - K·H)·P_{k|k-1}·(I - K·H)ᵀ + K·R·Kᵀ
```

For simplicity, we use the standard form and ensure symmetry:
```
P_{k|k} = (I - K·H)·P_{k|k-1}
P_{k|k} = (P_{k|k} + P_{k|k}ᵀ) / 2  [symmetrization]
```

## 6. Uncertainty Quantification

### Position Uncertainty

The position uncertainty is captured in the 2×2 submatrix:
```
P_pos = [P₁₁  P₁₂]
        [P₂₁  P₂₂]
```

Where:
- `P₁₁ = var(x_fbs)`
- `P₂₂ = var(y_fbs)`
- `P₁₂ = P₂₁ = cov(x_fbs, y_fbs)`

### Error Circle Radius

For visualization, we use the trace:
```
radius = √(P₁₁ + P₂₂)
```

This represents the RMS position error.

### 95% Confidence Ellipse

The 95% confidence region is an ellipse defined by:
```
(x - x̂)ᵀ·P_pos⁻¹·(x - x̂) ≤ 5.991
```

Where 5.991 is the 95th percentile of χ²(2).

Semi-major and semi-minor axes:
```
λ₁, λ₂ = eigenvalues(P_pos)
a = √(5.991·λ₁)
b = √(5.991·λ₂)
```

## 7. Convergence Analysis

### Fisher Information Matrix

The Fisher Information Matrix (FIM) for our problem:
```
I(x) = E[∇log p(z|x)·∇log p(z|x)ᵀ]
     = Hᵀ·R⁻¹·H
```

For our model:
```
I(x) = (1/R)·Hᵀ·H
```

### Cramér-Rao Lower Bound (CRLB)

The best achievable covariance (lower bound):
```
P_optimal ≥ I(x)⁻¹
```

After N measurements from different locations:
```
P_N ≥ [Σᵢ₌₁ᴺ Hᵢᵀ·R⁻¹·Hᵢ]⁻¹
```

This shows that:
1. More measurements → smaller P → better accuracy
2. Diverse measurement locations → better conditioned FIM → faster convergence

## 8. Observability Analysis

### Observability Matrix

For the system to be observable, we need rank(O) = 4, where:
```
O = [H₁]
    [H₂]
    [...]
    [Hₙ]
```

### Geometric Interpretation

The system is observable if:
1. Measurements are taken from at least 2 different locations
2. Locations are not collinear with the base station
3. Sufficient distance variation

## 9. Numerical Considerations

### Distance Clamping
To avoid singularities:
```
d_safe = max(d, d_min)
```
where `d_min = 1.0` meter.

### Covariance Symmetrization
To maintain numerical stability:
```
P = (P + Pᵀ) / 2
```

### Matrix Inversion
For scalar S:
```
S⁻¹ = 1/S  [direct]
```

For matrix inversion, EJML uses LU decomposition.

## 10. Example Calculation

### Scenario
- Base station at: (1000, 2000) m UTM
- User at: (1100, 2100) m UTM
- Distance: d = √(100² + 100²) = 141.42 m
- True parameters: P₀ = -45 dBm, η = 2.5
- Measurement: RSSI = -84.5 dBm

### Expected RSSI
```
h(x) = -45 - 10(2.5)log₁₀(141.42)
     = -45 - 25(2.150)
     = -98.75 dBm
```

### Innovation
```
y = -84.5 - (-98.75) = 14.25 dB
```

Positive innovation → signal stronger than expected → base station closer or P₀ higher.

### Jacobian
```
common = 10(2.5) / (ln(10)·141.42²)
       = 25 / (2.3026·19999.7)
       = 0.000543

H = [0.0543, 0.0543, 1.0, -21.51]
```

### Update
With initial high uncertainty (P = 1000·I), the Kalman gain will be large, causing significant state adjustment toward measurements.

## References

1. **Kalman Filtering Theory:**
   - Kalman, R. E. (1960). "A New Approach to Linear Filtering and Prediction Problems"
   - Welch & Bishop (2006). "An Introduction to the Kalman Filter"

2. **Radio Propagation:**
   - Rappaport, T. S. (2002). "Wireless Communications: Principles and Practice"
   - Goldsmith, A. (2005). "Wireless Communications"

3. **State Estimation:**
   - Bar-Shalom, Y., et al. (2001). "Estimation with Applications to Tracking and Navigation"
   - Simon, D. (2006). "Optimal State Estimation"

4. **Matrix Computations:**
   - Golub & Van Loan (2013). "Matrix Computations"
   - Press, et al. (2007). "Numerical Recipes"
