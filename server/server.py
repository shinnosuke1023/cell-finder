from flask import Flask, request, jsonify
import sqlite3
import time

app = Flask(__name__)

# データベース初期化（毎回リセット）
conn = sqlite3.connect("cells.db", check_same_thread=False)
c = conn.cursor()
c.execute("DROP TABLE IF EXISTS logs")
c.execute('''CREATE TABLE logs
             (timestamp INTEGER, lat REAL, lon REAL, type TEXT, rssi INTEGER, cell_id TEXT)''')
conn.commit()

@app.route('/log', methods=['POST'])
def log():
    data = request.get_json()
    timestamp = data.get("timestamp")
    lat = data.get("lat")
    lon = data.get("lon")
    cells = data.get("cells", [])
    c = conn.cursor()
    for cell in cells:
        c.execute("INSERT INTO logs VALUES (?, ?, ?, ?, ?, ?)",
                  (timestamp, lat, lon,
                   cell.get("type"),
                   cell.get("rssi"),
                   str(cell.get("cell_id"))))
    conn.commit()
    return jsonify({"status": "ok"})


@app.route('/map_data')
def map_data():
    """通常の観測ログを返す（地図表示用）"""
    c = conn.cursor()
    one_hour_ago = int(time.time()) - 3600
    c.execute("SELECT timestamp, lat, lon, type, rssi, cell_id FROM logs WHERE timestamp > ?", (one_hour_ago,))
    rows = c.fetchall()
    result = []
    for r in rows:
        result.append({
            "timestamp": r[0],
            "lat": r[1],
            "lon": r[2],
            "type": r[3],
            "rssi": r[4],
            "cell_id": r[5]
        })
    return jsonify(result)


@app.route('/cell_map')
def cell_map():
    """セルごとに加重平均（三角測量）した基地局推定位置を返す"""
    c = conn.cursor()
    c.execute("SELECT cell_id, type, lat, lon, rssi FROM logs WHERE cell_id IS NOT NULL")
    rows = c.fetchall()

    cells = {}
    for cell_id, ctype, lat, lon, rssi in rows:
        if cell_id not in cells:
            cells[cell_id] = {"type": ctype, "sum_lat": 0, "sum_lon": 0, "sum_w": 0, "count": 0}
        # RSSI(dBm)を線形スケールに変換 (mW相当)
        try:
            w = 10 ** (rssi / 10.0)
        except:
            w = 1
        cells[cell_id]["sum_lat"] += lat * w
        cells[cell_id]["sum_lon"] += lon * w
        cells[cell_id]["sum_w"] += w
        cells[cell_id]["count"] += 1

    result = []
    for cell_id, data in cells.items():
        if data["sum_w"] > 0:
            avg_lat = data["sum_lat"] / data["sum_w"]
            avg_lon = data["sum_lon"] / data["sum_w"]
        else:
            avg_lat, avg_lon = None, None
        result.append({
            "cell_id": cell_id,
            "type": data["type"],
            "lat": avg_lat,
            "lon": avg_lon,
            "count": data["count"]
        })

    return jsonify(result)


@app.route('/map')
def map_page():
    """Leafletで地図を表示するページ"""
    return """
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8" />
  <title>Cell Logger Map</title>
  <link rel="stylesheet" href="https://unpkg.com/leaflet/dist/leaflet.css" />
  <style>
    #map { height: 90vh; width: 100%; }
  </style>
</head>
<body>
  <h3>Cell Logger Map</h3>
  <div id="map"></div>
  <script src="https://unpkg.com/leaflet/dist/leaflet.js"></script>
  <script>
    var map = L.map('map').setView([35.0, 135.0], 12);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '© OpenStreetMap contributors'
    }).addTo(map);

    function updateMap() {
      // 通常の観測ログ
      fetch('/map_data')
        .then(res => res.json())
        .then(data => {
          data.forEach(log => {
            L.circleMarker([log.lat, log.lon], {
              radius: 4,
              color: "blue"
            }).addTo(map)
              .bindPopup("Type: " + log.type + "<br>RSSI: " + log.rssi + "<br>Cell ID: " + log.cell_id);
          });
        });

      // 基地局推定位置
      fetch('/cell_map')
        .then(res => res.json())
        .then(cells => {
          cells.forEach(cell => {
            L.circleMarker([cell.lat, cell.lon], {
              radius: 8,
              color: "red"
            }).addTo(map)
              .bindPopup("推定基地局<br>Cell ID: " + cell.cell_id + "<br>Type: " + cell.type + "<br>観測数: " + cell.count);
          });
        });
    }

    updateMap();
    setInterval(updateMap, 30000); // 30秒ごとに更新
  </script>
</body>
</html>
    """


if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000)
