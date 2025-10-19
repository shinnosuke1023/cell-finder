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
    cell_id_filter = request.args.get('cell_id', None)
    one_hour_ago_ms = int(time.time() * 1000) - 3600 * 1000
    
    params = [one_hour_ago_ms]
    query = "SELECT timestamp, lat, lon, type, rssi, cell_id FROM logs WHERE timestamp > ?"
    
    if cell_id_filter:
        query += " AND cell_id = ?"
        params.append(cell_id_filter)
    
    c.execute(query, tuple(params))
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


@app.route('/heatmap_data')
def heatmap_data():
    """ヒートマップ用のデータを返す"""
    c = conn.cursor()
    cell_id_filter = request.args.get('cell_id', None)
    one_hour_ago_ms = int(time.time() * 1000) - 3600 * 1000
    
    params = [one_hour_ago_ms]
    query = "SELECT timestamp, lat, lon, type, rssi, cell_id FROM logs WHERE timestamp > ?"
    
    if cell_id_filter:
        query += " AND cell_id = ?"
        params.append(cell_id_filter)
    
    c.execute(query, tuple(params))
    rows = c.fetchall()
    
    # ヒートマップ用のデータ形式 [lat, lon, intensity]
    heatmap_points = []
    for r in rows:
        if r[1] is not None and r[2] is not None:  # lat, lon not null
            # RSSI値を強度に変換（-20から-120の範囲を0.1から1.0にマッピング）
            rssi = r[4] if r[4] is not None else -100
            intensity = max(0.1, min(1.0, (rssi + 120) / 100))
            heatmap_points.append([r[1], r[2], intensity])
    
    return jsonify(heatmap_points)


@app.route('/cell_ids')
def get_cell_ids():
    """利用可能なセルIDのリストを返す"""
    c = conn.cursor()
    one_hour_ago_ms = int(time.time() * 1000) - 3600 * 1000
    c.execute("SELECT DISTINCT cell_id FROM logs WHERE timestamp > ? AND cell_id IS NOT NULL ORDER BY cell_id", (one_hour_ago_ms,))
    rows = c.fetchall()
    cell_ids = [row[0] for row in rows]
    return jsonify(cell_ids)


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


def _wls_trilateration(pts, max_iter=20, convergence_threshold=0.1):
    """Weighted Least Squares (WLS) による三辺測量
    
    反復的に位置を推定する高度なアルゴリズム。
    各観測点からの距離誤差を最小化するように位置を推定する。
    
    Args:
        pts: [(x, y, r), ...] 観測点の座標と推定距離のリスト
        max_iter: 最大反復回数
        convergence_threshold: 収束判定の閾値（メートル）
    
    Returns:
        (x, y) 推定位置、または None
    """
    if len(pts) < 3:
        return None
    
    # 初期推定値：観測点の重心
    x_est = sum(p[0] for p in pts) / len(pts)
    y_est = sum(p[1] for p in pts) / len(pts)
    
    for iteration in range(max_iter):
        # ヤコビ行列 H と残差ベクトル b を構築
        H = []
        b = []
        weights = []
        
        for (xi, yi, ri) in pts:
            dx = x_est - xi
            dy = y_est - yi
            dist = math.hypot(dx, dy)
            
            if dist < 1e-6:
                dist = 1e-6  # ゼロ除算を避ける
            
            # ヤコビ行列の行: [∂f/∂x, ∂f/∂y] = [(x-xi)/d, (y-yi)/d]
            H.append([dx / dist, dy / dist])
            
            # 残差: 推定距離 - 観測距離
            b.append(dist - ri)
            
            # 重み：距離が小さいほど信頼性が高い
            # また、RSSI測定の不確実性を考慮
            weight = 1.0 / (1.0 + ri / 1000.0)  # 距離に応じた重み
            weights.append(weight)
        
        # 重み行列 W
        W = [[weights[i] if i == j else 0.0 for j in range(len(weights))] 
             for i in range(len(weights))]
        
        # 最小二乗解: Δx = (H^T W H)^(-1) H^T W b
        try:
            # H^T W を計算
            HT_W = [[sum(H[k][i] * W[k][j] for k in range(len(H))) 
                    for j in range(len(H))] for i in range(2)]
            
            # H^T W H を計算 (2x2 行列)
            HTW_H = [[sum(HT_W[i][k] * H[k][j] for k in range(len(H))) 
                     for j in range(2)] for i in range(2)]
            
            # 2x2 行列の逆行列を計算
            det = HTW_H[0][0] * HTW_H[1][1] - HTW_H[0][1] * HTW_H[1][0]
            if abs(det) < 1e-10:
                # 特異行列の場合は現在の推定値を返す
                break
            
            HTW_H_inv = [
                [HTW_H[1][1] / det, -HTW_H[0][1] / det],
                [-HTW_H[1][0] / det, HTW_H[0][0] / det]
            ]
            
            # H^T W b を計算
            HTW_b = [sum(HT_W[i][k] * b[k] for k in range(len(b))) for i in range(2)]
            
            # Δx = (H^T W H)^(-1) H^T W b を計算
            delta_x = sum(HTW_H_inv[0][i] * HTW_b[i] for i in range(2))
            delta_y = sum(HTW_H_inv[1][i] * HTW_b[i] for i in range(2))
            
            # 位置を更新
            x_est -= delta_x
            y_est -= delta_y
            
            # 収束判定
            correction = math.hypot(delta_x, delta_y)
            if correction < convergence_threshold:
                break
                
        except Exception:
            # 数値計算エラーの場合は現在の推定値を返す
            break
    
    return (x_est, y_est)


def _robust_trilateration(pts, outlier_threshold=3.0):
    """ロバストな三辺測量（外れ値除去付き）
    
    RANSAC風のアプローチで外れ値を除去してから推定を行う。
    
    Args:
        pts: [(x, y, r), ...] 観測点の座標と推定距離のリスト
        outlier_threshold: 外れ値判定の閾値（標準偏差の倍数）
    
    Returns:
        (x, y) 推定位置、または None
    """
    if len(pts) < 3:
        return None
    
    # WLSで初期推定
    initial_est = _wls_trilateration(pts)
    if initial_est is None:
        return None
    
    x_est, y_est = initial_est
    
    # 各観測点からの残差を計算
    residuals = []
    for (xi, yi, ri) in pts:
        dist = math.hypot(x_est - xi, y_est - yi)
        residual = abs(dist - ri)
        residuals.append(residual)
    
    # 残差の中央値と MAD (Median Absolute Deviation) を計算
    sorted_residuals = sorted(residuals)
    median = sorted_residuals[len(sorted_residuals) // 2]
    mad = sorted([abs(r - median) for r in residuals])[len(residuals) // 2]
    
    # MADが0の場合はすべてのデータが一致しているので、そのまま返す
    if mad < 1e-6:
        return (x_est, y_est)
    
    # 外れ値を除去（修正Z-スコアを使用）
    threshold = outlier_threshold * 1.4826 * mad  # 1.4826はMADを標準偏差に変換する係数
    inliers = []
    for i, (xi, yi, ri) in enumerate(pts):
        if residuals[i] - median < threshold:
            inliers.append((xi, yi, ri))
    
    # インライアが十分にある場合は再推定
    if len(inliers) >= 3:
        result = _wls_trilateration(inliers)
        if result is not None:
            return result
    
    # インライアが少ない場合は初期推定を返す
    return (x_est, y_est)


@app.route('/cell_map')
def cell_map():
    """セルごとに「受信電力→距離」の円の交点を投票して基地局位置を推定して返す。
    クエリ引数:
      - ple: パス損失指数 n（既定 2.0）
      - window_sec: この秒数以内のログのみを対象（省略時は全期間）
      - ref_rssi: 参照距離 ref_dist[m] における RSSI[dBm]（既定 -40）
      - ref_dist: 参照距離[m]（既定 1.0）
      - bandwidth_m: 交点クラスタリング半径[m]（既定 150）
      - method: 推定方法
          * 'wls' - Weighted Least Squares（重み付き最小二乗法、高精度、既定）
          * 'robust' - ロバスト推定（外れ値除去付きWLS）
          * 'accum' - 交点投票法（従来の高度な方法）
          * 'centroid' - 加重重心法（シンプルな方法）
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
    method = request.args.get('method', default='wls', type=str)
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

        # 推定方法に応じて処理を分岐
        if method == 'wls':
            # Weighted Least Squares 法
            est_xy = _wls_trilateration(pts)
            if est_xy is None:
                est_latlon = centroid_estimate()
                out = {"cell_id": cell_id, "type": ctype, "lat": est_latlon[0], "lon": est_latlon[1], "count": len(logs)}
            else:
                est_lat, est_lon = _xy_to_ll(est_xy[0], est_xy[1], lat0, lon0)
                out = {"cell_id": cell_id, "type": ctype, "lat": est_lat, "lon": est_lon, "count": len(logs)}
            if debug_flag:
                out["debug"] = {"circles": debug_circles, "method": "wls"}
            result.append(out)
            continue

        elif method == 'robust':
            # ロバスト推定（外れ値除去付き）
            est_xy = _robust_trilateration(pts)
            if est_xy is None:
                est_latlon = centroid_estimate()
                out = {"cell_id": cell_id, "type": ctype, "lat": est_latlon[0], "lon": est_latlon[1], "count": len(logs)}
            else:
                est_lat, est_lon = _xy_to_ll(est_xy[0], est_xy[1], lat0, lon0)
                out = {"cell_id": cell_id, "type": ctype, "lat": est_lat, "lon": est_lon, "count": len(logs)}
            if debug_flag:
                out["debug"] = {"circles": debug_circles, "method": "robust"}
            result.append(out)
            continue

        # 以下は 'accum' 法（交点投票）
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
            out["debug"] = {"circles": debug_circles, "method": "accum"}
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
  <style>
    #map { height: 80vh; width: 100%; background: #f0f0f0; position: relative; }
    #controls { margin: 10px 0; }
    .filter-control { margin: 0 10px 10px 0; display: inline-block; }
    .filter-control label { margin-right: 5px; }
    .filter-control select, .filter-control input { padding: 5px; }
    #status { margin: 10px 0; padding: 10px; background: #e8f4f8; border: 1px solid #bee5eb; }
    .data-point { position: absolute; width: 10px; height: 10px; border-radius: 50%; z-index: 1000; cursor: pointer; }
    .heatmap-point { opacity: 0.7; }
    .pin-point { background: blue; }
    .base-station { background: red; width: 15px; height: 15px; }
  </style>
</head>
<body>
  <h3>Cell Logger Map - Heatmap View</h3>
  <div id="controls">
    <div class="filter-control">
      <label for="cellIdFilter">Cell ID Filter:</label>
      <select id="cellIdFilter">
        <option value="">All Cell IDs</option>
      </select>
    </div>
    <div class="filter-control">
      <label for="displayMode">Display Mode:</label>
      <select id="displayMode">
        <option value="heatmap">Heatmap</option>
        <option value="pins">Pins (Original)</option>
      </select>
    </div>
    <div class="filter-control">
      <button onclick="updateMap()">Refresh</button>
    </div>
  </div>
  <div id="status">Loading map data...</div>
  <div id="map">
    <div style="position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); text-align: center;">
      <p>Heatmap Visualization</p>
      <p id="mapInfo">Loading data...</p>
    </div>
  </div>
  
  <!-- Fallback implementation without external dependencies -->
  <script>
    var mapElement = document.getElementById('map');
    var statusElement = document.getElementById('status');
    var currentData = [];
    var currentMode = 'heatmap';
    
    function clearMap() {
      var points = mapElement.querySelectorAll('.data-point');
      points.forEach(function(p) { p.remove(); });
    }
    
    function getColorFromIntensity(intensity) {
      // Convert intensity (0-1) to color
      if (intensity > 0.8) return '#ff0000'; // red
      if (intensity > 0.6) return '#ff8000'; // orange  
      if (intensity > 0.4) return '#ffff00'; // yellow
      if (intensity > 0.2) return '#80ff00'; // yellow-green
      return '#0080ff'; // blue
    }
    
    function createDataPoint(lat, lon, intensity, info, isBaseStation) {
      var point = document.createElement('div');
      point.className = 'data-point ' + (currentMode === 'heatmap' ? 'heatmap-point' : 'pin-point');
      if (isBaseStation) point.className += ' base-station';
      
      // Simple positioning (would need proper map projection in real implementation)
      var x = ((lon - 135.0) * 10000 + 50) + '%';
      var y = ((35.0 - lat) * 10000 + 50) + '%';
      point.style.left = x;
      point.style.top = y;
      
      if (currentMode === 'heatmap' && !isBaseStation) {
        point.style.background = getColorFromIntensity(intensity);
        point.style.width = (intensity * 20 + 5) + 'px';
        point.style.height = (intensity * 20 + 5) + 'px';
        point.style.opacity = intensity;
      }
      
      point.title = info;
      point.onclick = function() { alert(info); };
      mapElement.appendChild(point);
    }

    function loadCellIds() {
      fetch('/cell_ids')
        .then(res => res.json())
        .then(cellIds => {
          var select = document.getElementById('cellIdFilter');
          // Clear existing options except "All"
          while (select.children.length > 1) {
            select.removeChild(select.lastChild);
          }
          // Add cell ID options
          cellIds.forEach(cellId => {
            var option = document.createElement('option');
            option.value = cellId;
            option.textContent = cellId;
            select.appendChild(option);
          });
          statusElement.innerHTML = 'Found ' + cellIds.length + ' cell IDs: ' + cellIds.join(', ');
        })
        .catch(err => {
          console.error('Failed to load cell IDs:', err);
          statusElement.innerHTML = 'Error loading cell IDs: ' + err.message;
        });
    }

    function updateMap() {
      clearMap();
      currentMode = document.getElementById('displayMode').value;
      var cellIdFilter = document.getElementById('cellIdFilter').value;
      
      statusElement.innerHTML = 'Loading data for mode: ' + currentMode + 
        (cellIdFilter ? ' (Cell ID: ' + cellIdFilter + ')' : ' (All cells)');
      
      if (currentMode === 'heatmap') {
        updateHeatmap(cellIdFilter);
      } else {
        updatePins(cellIdFilter);
      }
      
      // Always show estimated base stations
      updateBaseStations();
    }
    
    function updateHeatmap(cellIdFilter) {
      var url = '/heatmap_data';
      if (cellIdFilter) {
        url += '?cell_id=' + encodeURIComponent(cellIdFilter);
      }
      
      fetch(url)
        .then(res => res.json())
        .then(data => {
          document.getElementById('mapInfo').innerHTML = 
            'Heatmap: ' + data.length + ' data points' + 
            (cellIdFilter ? ' for Cell ID: ' + cellIdFilter : '');
          
          data.forEach(function(point) {
            var lat = point[0];
            var lon = point[1]; 
            var intensity = point[2];
            var info = 'Heatmap Point\\nLat: ' + lat + '\\nLon: ' + lon + '\\nIntensity: ' + intensity.toFixed(2);
            createDataPoint(lat, lon, intensity, info, false);
          });
          
          statusElement.innerHTML = 'Heatmap loaded: ' + data.length + ' points';
        })
        .catch(err => {
          console.error('Failed to load heatmap data:', err);
          statusElement.innerHTML = 'Error loading heatmap: ' + err.message;
        });
    }
    
    function updatePins(cellIdFilter) {
      var url = '/map_data';
      if (cellIdFilter) {
        url += '?cell_id=' + encodeURIComponent(cellIdFilter);
      }
      
      fetch(url)
        .then(res => res.json())
        .then(data => {
          document.getElementById('mapInfo').innerHTML = 
            'Pins: ' + data.length + ' data points' + 
            (cellIdFilter ? ' for Cell ID: ' + cellIdFilter : '');
          
          data.forEach(function(log) {
            if (log.lat != null && log.lon != null) {
              var info = 'Pin\\nType: ' + log.type + '\\nRSSI: ' + log.rssi + '\\nCell ID: ' + log.cell_id;
              createDataPoint(log.lat, log.lon, 0.5, info, false);
            }
          });
          
          statusElement.innerHTML = 'Pins loaded: ' + data.length + ' points';
        })
        .catch(err => {
          console.error('Failed to load pin data:', err);
          statusElement.innerHTML = 'Error loading pins: ' + err.message;
        });
    }
    
    function updateBaseStations() {
      fetch('/cell_map?debug=1')
        .then(res => res.json())
        .then(cells => {
          cells.forEach(function(cell) {
            if (cell.lat != null && cell.lon != null) {
              var info = 'Base Station\\nCell ID: ' + cell.cell_id + '\\nType: ' + cell.type + '\\nCount: ' + cell.count;
              createDataPoint(cell.lat, cell.lon, 1.0, info, true);
            }
          });
        })
        .catch(err => console.error('Failed to load base station data:', err));
    }

    // Initialize
    loadCellIds();
    updateMap();
    setInterval(function() {
      loadCellIds();
      updateMap();
    }, 30000); // 30秒ごとに更新
  </script>
</body>
</html>
    """


if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000)
