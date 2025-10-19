CellFinder project
Android app (Kotlin) + Flask server sample.
Build Android app with Android Studio; edit SERVER_URL in CellFinderService.kt to point to your server IP.
Start server: python server.py
Open http://<server_ip>:5000/map to see markers.

## Advanced Positioning Algorithms

The project includes four positioning algorithms:
- **Accum (Circle Intersection)**: Geometric voting method (Default, proven)
- **Centroid**: Simple weighted average (Fast fallback)
- **WLS (Weighted Least Squares)**: Iterative optimization (Experimental)
- **Robust**: WLS with automatic outlier detection (Experimental)

See [ALGORITHMS.md](ALGORITHMS.md) for detailed documentation.

### Usage

Server API:
```bash
# Circle intersection (default, proven method)
curl "http://localhost:5000/cell_map?method=accum"

# WLS estimation (experimental, requires 3+ observations)
curl "http://localhost:5000/cell_map?method=wls"

# Robust estimation (experimental)
curl "http://localhost:5000/cell_map?method=robust"

# Simple centroid (fast fallback)
curl "http://localhost:5000/cell_map?method=centroid"
```

Android API:
```kotlin
val results = BaseStationEstimator.estimateBaseStationPositions(
    cellLogsMap = cellLogs,
    method = "accum"  // or "wls", "robust", "centroid"
)
```

