# CellFinder Android App - Standalone Mode

This document describes the standalone functionality added to the CellFinder Android app, allowing it to work independently without requiring the Flask server.

## New Features

### 🗺️ Google Maps Integration
- **Real-time map display** of cell tower observations and estimated base station positions
- **Google Maps API** integration with configurable API key
- **Interactive markers** showing detailed information about cell towers and base stations
- **Optional debug circles** showing RSSI-based distance estimates

### 📱 Standalone Operation  
- **Local SQLite database** stores cell tower observations on the device
- **No server dependency** required for basic functionality
- **Offline estimation** of base station positions using advanced algorithms
- **Backward compatibility** - still sends data to server when available

### 🔬 Advanced Base Station Estimation
- **Circle intersection method** using RSSI-based distance estimates
- **Logarithmic path loss model** for realistic distance calculations
- **Weighted voting algorithm** for improved accuracy in multi-observation scenarios
- **Automatic fallback** to centroid method when intersections aren't found

## Usage

### Setting up Google Maps API

1. Get a Google Maps API key from the [Google Cloud Console](https://console.cloud.google.com/)
2. Enable the "Maps SDK for Android" API
3. Set the API key as an environment variable:
   ```bash
   export GOOGLE_MAPS_API_KEY="your_api_key_here"
   ```

### Building the App

```bash
cd android
./gradlew assembleDebug
```

### Using the App

1. **Start Logging**: Tap "Start Logging" to begin collecting cell tower data
2. **View Map**: Tap "Show Map" to see real-time visualization of:
   - 🔵 Blue markers: Cell tower observations (your location when receiving signals)
   - 🔴 Red markers: Estimated base station positions
   - ⭕ Debug circles: RSSI-based distance estimates (optional)

3. **Map Features**:
   - Auto-updates every 30 seconds
   - Tap markers for detailed information
   - Use menu to toggle debug circles or manually refresh
   - Automatic camera fitting to show all data

## Technical Details

### Database Schema
Local SQLite database stores observations with this schema:
```sql
CREATE TABLE logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp INTEGER,
    lat REAL,
    lon REAL,
    type TEXT,
    rssi INTEGER,
    cell_id TEXT
)
```

### Base Station Estimation Algorithm

The app implements a sophisticated triangulation algorithm:

1. **RSSI to Distance Conversion**:
   ```
   distance = ref_distance × 10^((ref_rssi - measured_rssi) / (10 × path_loss_exponent))
   ```

2. **Circle Intersection Method**:
   - Convert all observations to local meter coordinates
   - Create circles centered at observation points with RSSI-based radii
   - Find all intersection points between circle pairs
   - Use density-based clustering to find the most likely base station location

3. **Weighted Voting**:
   - Each intersection gets a weight based on the crossing angle
   - Points with more nearby intersections score higher
   - Final position is weighted average of the best cluster

### Configuration Parameters

Default parameters (adjustable in `BaseStationEstimator.kt`):
- **Path Loss Exponent**: 2.0 (urban environment)
- **Reference RSSI**: -40 dBm at 1 meter distance
- **Clustering Bandwidth**: 150 meters
- **Data Retention**: 24 hours

## Files Added/Modified

### New Files
- `app/src/main/java/com/example/cellfinder/CellDatabase.kt` - Local data storage
- `app/src/main/java/com/example/cellfinder/BaseStationEstimator.kt` - Position estimation algorithms  
- `app/src/main/java/com/example/cellfinder/MapsActivity.kt` - Google Maps integration
- `app/src/main/res/layout/activity_maps.xml` - Map activity layout

### Modified Files
- `app/build.gradle` - Added Google Maps and AppCompat dependencies
- `app/src/main/AndroidManifest.xml` - Added API key configuration and MapsActivity
- `app/src/main/java/com/example/cellfinder/CellFinderService.kt` - Added local data storage
- `app/src/main/java/com/example/cellfinder/MainActivity.kt` - Added "Show Map" button
- Layout and string resources updated

## Demonstration

A working demonstration of the estimation algorithm achieves **9.3 meter accuracy** with realistic LTE observations:

```
True base station at: 35.681200, 139.767100
Estimated position: 35.681116, 139.767091
Estimation error: 9.3 meters
```

This demonstrates the effectiveness of the circle intersection method for base station localization using smartphone RSSI measurements.

## Server Compatibility

The app maintains full compatibility with the existing Flask server:
- Data is stored locally AND sent to server (dual mode)
- Server can still be used for web-based visualization
- Local and server-based estimation algorithms are identical
- Seamless operation whether server is available or not