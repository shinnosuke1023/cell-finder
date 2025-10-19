CellFinder project
Android app (Kotlin) + Flask server sample.
Build Android app with Android Studio; edit SERVER_URL in CellFinderService.kt to point to your server IP.
Start server: python server.py
Open http://<server_ip>:5000/map to see markers.

## Advanced Positioning Algorithms

The project now includes four advanced algorithms for base station position estimation:
- **WLS (Weighted Least Squares)**: Iterative optimization for high accuracy (Default)
- **Robust**: WLS with automatic outlier detection (Recommended for production)
- **Accum (Circle Intersection)**: Geometric voting method
- **Centroid**: Simple weighted average

See [ALGORITHMS.md](ALGORITHMS.md) for detailed documentation.

### Usage

Server API:
```bash
# Robust estimation (recommended)
curl "http://localhost:5000/cell_map?method=robust"

# WLS estimation (fast, accurate)
curl "http://localhost:5000/cell_map?method=wls"

# Circle intersection
curl "http://localhost:5000/cell_map?method=accum"

# Simple centroid
curl "http://localhost:5000/cell_map?method=centroid"
```

Android API:
```kotlin
val results = BaseStationEstimator.estimateBaseStationPositions(
    cellLogsMap = cellLogs,
    method = "robust"  // or "wls", "intersection", "centroid"
)
```

