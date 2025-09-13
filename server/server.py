from flask import Flask, request, jsonify
import sqlite3
import time
import math

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
    # タイムスタンプが秒の場合はミリ秒に変換、未指定なら現在時刻（ms）
    if timestamp is None:
        timestamp = int(time.time() * 1000)
    elif isinstance(timestamp, (int, float)) and timestamp < 10_000_000_000:  # 10桁台なら秒とみなす
        timestamp = int(timestamp * 1000)
    else:
        timestamp = int(timestamp)

    lat = data.get("lat")
    lon = data.get("lon")
    cells = data.get("cells", [])
    c = conn.cursor()
    for cell in cells:
        c.execute("INSERT INTO logs VALUES (?, ?, ?, ?, ?, ?)",
                  (timestamp, lat, lon,
                   cell.get("type"),
                   cell.get("rssi"),
                   # None はそのまま渡し、DB では NULL になる
                   cell.get("cell_id")))
    conn.commit()
    return jsonify({"status": "ok"})


@app.route('/map_data')
def map_data():
    """通常の観測ログを返す（地図表示用）"""
    c = conn.cursor()
    one_hour_ago_ms = int(time.time() * 1000) - 3600 * 1000
    c.execute("SELECT timestamp, lat, lon, type, rssi, cell_id FROM logs WHERE timestamp > ?", (one_hour_ago_ms,))
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


def _rssi_to_distance_m(rssi_dbm: float, n: float, ref_rssi_dbm: float, ref_dist_m: float) -> float:
    """対数距離モデル: PL(d) = PL(d0) + 10 n log10(d/d0)
    ここでは RSSI[dBm] ≈ ref_rssi_dbm - 10 n log10(d/ref_dist_m)
    より d = ref_dist_m * 10^((ref_rssi_dbm - rssi_dbm)/(10 n))
    """
    n = max(n, 0.1)
    d = ref_dist_m * (10 ** ((ref_rssi_dbm - rssi_dbm) / (10.0 * n)))
    # 安全な範囲にクリップ（1 m〜50 km）
    return max(1.0, min(d, 50_000.0))


def _ll_to_xy_m(lat: float, lon: float, lat0: float, lon0: float):
    R = 6371000.0
    rad = math.pi / 180.0
    x = R * math.cos(lat0 * rad) * (lon - lon0) * rad
    y = R * (lat - lat0) * rad
    return x, y


def _xy_to_ll(x: float, y: float, lat0: float, lon0: float):
    R = 6371000.0
    deg = 180.0 / math.pi
    lat = y / R * deg + lat0
    lon = x / (R * math.cos(lat0 * math.pi / 180.0)) * deg + lon0
    return lat, lon


def _circle_intersections(x0, y0, r0, x1, y1, r1):
    """2円の交点（最大2点）を返す。交わらなければ空。
    戻り値: [(x, y, w_angle), ...] ここで w_angle は交差角に基づく重み（0..1）。
    """
    dx = x1 - x0
    dy = y1 - y0
    d = math.hypot(dx, dy)
    # 分離 or 包含しすぎ or ほぼ同心
    if d <= 1e-6 or d > r0 + r1 or d < abs(r0 - r1):
        return []
    # 交点
    a = (r0*r0 - r1*r1 + d*d) / (2*d)
    h2 = r0*r0 - a*a
    if h2 < 0:
        # 丸め誤差
        h2 = 0.0
    h = math.sqrt(h2)
    xm = x0 + a * dx / d
    ym = y0 + a * dy / d
    rx = -dy * (h / d)
    ry = dx * (h / d)
    p1 = (xm + rx, ym + ry)
    p2 = (xm - rx, ym - ry)

    # 交差角重み：浅い交差ほど小さく、深い交差ほど大きい
    denom = max(1e-6, min(r0, r1))
    w_angle = max(0.0, min(1.0, h / denom))

    # 接する場合は1点
    if h <= 1e-6:
        return [(p1[0], p1[1], w_angle)]
    return [(p1[0], p1[1], w_angle), (p2[0], p2[1], w_angle)]


@app.route('/cell_map')
def cell_map():
    """セルごとに「受信電力→距離」の円の交点を投票して基地局位置を推定して返す。
    クエリ引数:
      - ple: パス損失指数 n（既定 2.0）
      - window_sec: この秒数以内のログのみを対象（省略時は全期間）
      - ref_rssi: 参照距離 ref_dist[m] における RSSI[dBm]（既定 -40）
      - ref_dist: 参照距離[m]（既定 1.0）
      - bandwidth_m: 交点クラスタリング半径[m]（既定 150）
      - method: 'accum'（交点投票。既定） or 'centroid'（従来の加重重心）
      - debug: 1 でデバッグ情報（各観測円）を返す
    重複排除:
      - 同一 (cell_id, type, lat, lon) のレコードが複数ある場合は、最新 (timestamp が最大) のみ利用。
    """
    c = conn.cursor()

    # パラメータ取得
    ple = request.args.get('ple', default=2.0, type=float)
    window_sec = request.args.get('window_sec', default=None, type=int)
    ref_rssi = request.args.get('ref_rssi', default=-40.0, type=float)
    ref_dist = request.args.get('ref_dist', default=1.0, type=float)
    bandwidth_m = request.args.get('bandwidth_m', default=150.0, type=float)
    method = request.args.get('method', default='accum', type=str)
    debug_flag = request.args.get('debug', default=0, type=int)

    # 期間フィルタ
    params = []
    query = "SELECT cell_id, type, lat, lon, rssi, timestamp FROM logs WHERE cell_id IS NOT NULL"
    if window_sec is not None and window_sec > 0:
        cutoff_ms = int(time.time() * 1000) - window_sec * 1000
        query += " AND timestamp > ?"
        params.append(cutoff_ms)

    c.execute(query, tuple(params))
    rows = c.fetchall()

    # 完全一致重複（位置情報+セル情報が同一）のうち最新のみを採用
    latest_rows = {}
    for cell_id, ctype, lat, lon, rssi, ts in rows:
        if lat is None or lon is None:
            continue
        key = (cell_id, ctype, lat, lon)
        prev = latest_rows.get(key)
        if prev is None or ts > prev[-1]:
            latest_rows[key] = (cell_id, ctype, lat, lon, rssi, ts)

    # セルIDごとに観測をグループ化
    by_cell = {}
    for cell_id, ctype, lat, lon, rssi, _ts in latest_rows.values():
        try:
            rssi_dbm = float(rssi)
        except Exception:
            continue
        by_cell.setdefault(cell_id, {"type": ctype, "logs": []})
        by_cell[cell_id]["logs"].append({"lat": lat, "lon": lon, "rssi": rssi_dbm})

    result = []

    for cell_id, info in by_cell.items():
        logs = info["logs"]
        ctype = info["type"]
        if len(logs) == 0:
            result.append({"cell_id": cell_id, "type": ctype, "lat": None, "lon": None, "count": 0})
            continue

        # 従来の重心フォールバック用: 電力重みの重心
        def centroid_estimate():
            sum_lat = sum_lon = sum_w = 0.0
            for log in logs:
                rssi_dbm = max(min(log["rssi"], -20.0), -140.0)
                p_mw = 10 ** (rssi_dbm / 10.0)
                w = (p_mw ** (2.0 / max(ple, 0.1)))
                sum_lat += log["lat"] * w
                sum_lon += log["lon"] * w
                sum_w += w
            if sum_w > 0:
                return (sum_lat / sum_w, sum_lon / sum_w)
            return (None, None)

        if method == 'centroid' or len(logs) < 2:
            est_latlon = centroid_estimate()
            out = {"cell_id": cell_id, "type": ctype, "lat": est_latlon[0], "lon": est_latlon[1], "count": len(logs)}
            if debug_flag:
                # 円のデバッグ（RSSI→距離）
                out["debug"] = {"circles": [{"lat": l["lat"], "lon": l["lon"], "radius_m": _rssi_to_distance_m(max(min(l["rssi"], -20.0), -140.0), ple, ref_rssi, ref_dist)} for l in logs]}
            result.append(out)
            continue

        # 局所平面で処理（メートル座標）
        lat0 = sum(l["lat"] for l in logs) / len(logs)
        lon0 = sum(l["lon"] for l in logs) / len(logs)

        pts = []  # (x, y, r)
        debug_circles = []
        for log in logs:
            rssi_dbm = max(min(log["rssi"], -20.0), -140.0)
            d_m = _rssi_to_distance_m(rssi_dbm, ple, ref_rssi, ref_dist)
            x, y = _ll_to_xy_m(log["lat"], log["lon"], lat0, lon0)
            pts.append((x, y, d_m))
            if debug_flag:
                debug_circles.append({"lat": log["lat"], "lon": log["lon"], "radius_m": d_m})

        # 全ペアの円交点を収集（交差角重み付き）
        intersections = []
        n = len(pts)
        for i in range(n):
            x0, y0, r0 = pts[i]
            for j in range(i + 1, n):
                x1, y1, r1 = pts[j]
                inter = _circle_intersections(x0, y0, r0, x1, y1, r1)
                for (px, py, wang) in inter:
                    intersections.append((px, py, wang))

        if not intersections:
            est_latlon = centroid_estimate()
            out = {"cell_id": cell_id, "type": ctype, "lat": est_latlon[0], "lon": est_latlon[1], "count": len(logs)}
            if debug_flag:
                out["debug"] = {"circles": debug_circles}
            result.append(out)
            continue

        # 近傍密度（半径 bandwidth_m 内の票数）最大の点を中心に加重平均
        bw = max(5.0, float(bandwidth_m))
        best_idx = -1
        best_score = -1.0
        for k, (xk, yk, wk) in enumerate(intersections):
            score = 0.0
            for (xi, yi, wi) in intersections:
                if (xi - xk) * (xi - xk) + (yi - yk) * (yi - yk) <= bw * bw:
                    score += wi
            if score > best_score:
                best_score = score
                best_idx = k
        cx, cy, _ = intersections[best_idx]

        # ベスト近傍で加重平均（距離減衰×交差角重み）
        sumx = sumy = sumw = 0.0
        for (xi, yi, wi) in intersections:
            d2 = (xi - cx) * (xi - cx) + (yi - cy) * (yi - cy)
            if d2 <= bw * bw:
                w_dist = (1.0 - math.sqrt(d2) / bw)  # 0..1
                w = wi * w_dist
                sumx += xi * w
                sumy += yi * w
                sumw += w
        if sumw > 0:
            ex = sumx / sumw
            ey = sumy / sumw
        else:
            ex, ey = cx, cy

        est_lat, est_lon = _xy_to_ll(ex, ey, lat0, lon0)
        out = {
            "cell_id": cell_id,
            "type": ctype,
            "lat": est_lat,
            "lon": est_lon,
            "count": len(logs)
        }
        if debug_flag:
            out["debug"] = {"circles": debug_circles}
        result.append(out)

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

    var overlayLayers = [];
    function clearOverlays() {
      overlayLayers.forEach(function(l){ map.removeLayer(l); });
      overlayLayers = [];
    }

    function updateMap() {
      clearOverlays();

      // 通常の観測ログ
      fetch('/map_data')
        .then(res => res.json())
        .then(data => {
          data.forEach(log => {
            if (log.lat != null && log.lon != null) {
              var m = L.circleMarker([log.lat, log.lon], {
                radius: 4,
                color: "blue"
              }).bindPopup("Type: " + log.type + "<br>RSSI: " + log.rssi + "<br>Cell ID: " + log.cell_id);
              m.addTo(map); overlayLayers.push(m);
            }
          });
        });

      // 基地局推定位置（デバッグ円付き）
      fetch('/cell_map?debug=1')
        .then(res => res.json())
        .then(cells => {
          cells.forEach(cell => {
            if (cell.lat != null && cell.lon != null) {
              var cm = L.circleMarker([cell.lat, cell.lon], {
                radius: 8,
                color: "red"
              }).bindPopup("推定基地局<br>Cell ID: " + cell.cell_id + "<br>Type: " + cell.type + "<br>観測数: " + cell.count);
              cm.addTo(map); overlayLayers.push(cm);
            }
            if (cell.debug && cell.debug.circles) {
              cell.debug.circles.forEach(c => {
                var circ = L.circle([c.lat, c.lon], {
                  radius: c.radius_m,
                  color: '#666',
                  weight: 1,
                  opacity: 0.4,
                  fill: false
                });
                circ.addTo(map); overlayLayers.push(circ);
              });
            }
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
