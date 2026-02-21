import logging
import math
import sqlite3
import time

from flask import Flask, g, jsonify, render_template, request
from flask_cors import CORS

from db import ARCHIVE_DB, close_db, get_db, init_db, start_archive_timer

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)

app = Flask(__name__)
CORS(app)

# In-memory storage for the latest connected cell ID reported by the device
_connected_cell_id = None


@app.teardown_appcontext
def _close_db(exception):
    """Close the per-thread DB connection at end of request."""
    close_db()


# ── initialisation ────────────────────────────────────────────────────────────
init_db()
start_archive_timer()

@app.route('/log', methods=['POST'])
def log():
    data = request.get_json(silent=True)
    if not data:
        return jsonify({"error": "invalid or missing JSON body"}), 400

    # lat / lon validation
    lat = data.get("lat")
    lon = data.get("lon")
    try:
        if lat is not None and not (-90.0 <= float(lat) <= 90.0):
            return jsonify({"error": "lat out of range [-90, 90]"}), 400
        if lon is not None and not (-180.0 <= float(lon) <= 180.0):
            return jsonify({"error": "lon out of range [-180, 180]"}), 400
    except (TypeError, ValueError):
        return jsonify({"error": "lat and lon must be numeric"}), 400

    cells = data.get("cells", [])
    if not isinstance(cells, list):
        return jsonify({"error": "cells must be a list"}), 400
    if len(cells) > 200:
        return jsonify({"error": "cells list exceeds maximum length of 200"}), 400

    timestamp = data.get("timestamp")
    # タイムスタンプが秒の場合はミリ秒に変換、未指定なら現在時刻（ms）
    if timestamp is None:
        timestamp = int(time.time() * 1000)
    elif isinstance(timestamp, (int, float)) and timestamp < 10_000_000_000:  # 10桁台なら秒とみなす
        timestamp = int(timestamp * 1000)
    else:
        timestamp = int(timestamp)

    db = get_db()
    c = db.cursor()
    for cell in cells:
        c.execute("INSERT INTO logs VALUES (?, ?, ?, ?, ?, ?)",
                  (timestamp, lat, lon,
                   cell.get("type"),
                   cell.get("rssi"),
                   # None はそのまま渡し、DB では NULL になる
                   cell.get("cell_id")))
    db.commit()
    logger.debug("Logged %d cell records", len(cells))

    # Update connected cell ID if provided
    global _connected_cell_id
    connected = data.get("connected_cell_id")
    if connected is not None:
        _connected_cell_id = connected

    # Log warning when GSM cells are detected
    gsm_cells = [c for c in cells if c.get("type") == "GSM"]
    if gsm_cells:
        logger.warning(
            "GSM ALERT: %d GSM cell(s) detected at lat=%s, lon=%s, cells=%s",
            len(gsm_cells),
            lat,
            lon,
            [c.get("cell_id") for c in gsm_cells],
        )

    return jsonify({"status": "ok"})


@app.route('/alerts')
def alerts():
    """Return recent GSM alerts (last 1 hour) derived from the logs table."""
    db = get_db()
    one_hour_ago_ms = int(time.time() * 1000) - 3600 * 1000
    rows = db.execute(
        "SELECT timestamp, lat, lon, rssi, cell_id FROM logs "
        "WHERE type = 'GSM' AND timestamp > ? ORDER BY timestamp",
        (one_hour_ago_ms,),
    ).fetchall()

    # Group GSM records by (timestamp, lat, lon)
    grouped = {}
    for ts, lat, lon, rssi, cell_id in rows:
        key = (ts, lat, lon)
        if key not in grouped:
            grouped[key] = {"timestamp": ts, "lat": lat, "lon": lon, "gsm_cells": []}
        grouped[key]["gsm_cells"].append({"cell_id": cell_id, "rssi": rssi})

    return jsonify(list(grouped.values()))


@app.route('/map_data')
def map_data():
    """通常の観測ログを返す（地図表示用）"""
    db = get_db()
    c = db.cursor()
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
    db = get_db()
    c = db.cursor()
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
    db = get_db()
    c = db.cursor()
    one_hour_ago_ms = int(time.time() * 1000) - 3600 * 1000
    c.execute("SELECT DISTINCT cell_id FROM logs WHERE timestamp > ? AND cell_id IS NOT NULL ORDER BY cell_id", (one_hour_ago_ms,))
    rows = c.fetchall()
    cell_ids = [row[0] for row in rows]
    return jsonify(cell_ids)


@app.route('/connected_cell')
def get_connected_cell():
    """現在接続中のセルIDを返す"""
    return jsonify({"connected_cell_id": _connected_cell_id})


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
      - window_sec: この秒数以内のログのみを対象（省略時は 3600 秒＝1時間）
      - ref_rssi: 参照距離 ref_dist[m] における RSSI[dBm]（既定 -40）
      - ref_dist: 参照距離[m]（既定 1.0）
      - bandwidth_m: 交点クラスタリング半径[m]（既定 150）
      - method: 'accum'（交点投票。既定） or 'centroid'（従来の加重重心）
      - debug: 1 でデバッグ情報（各観測円）を返す
    重複排除:
      - 同一 (cell_id, type, lat, lon) のレコードが複数ある場合は、最新 (timestamp が最大) のみ利用。
    """
    db = get_db()
    c = db.cursor()

    # パラメータ取得
    ple = request.args.get('ple', default=2.0, type=float)
    window_sec = request.args.get('window_sec', default=3600, type=int)
    ref_rssi = request.args.get('ref_rssi', default=-40.0, type=float)
    ref_dist = request.args.get('ref_dist', default=1.0, type=float)
    bandwidth_m = request.args.get('bandwidth_m', default=150.0, type=float)
    method = request.args.get('method', default='accum', type=str)
    debug_flag = request.args.get('debug', default=1, type=int)

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


@app.route('/history')
def history():
    """アーカイブ層から過去データを検索する。
    クエリ引数:
      - cell_id: セルIDでフィルタ（省略可）
      - hours: 過去何時間分を返すか（デフォルト 24）
      - limit: 最大返却行数（デフォルト 1000、最大 10000）
    """
    cell_id_filter = request.args.get('cell_id', None)
    hours = request.args.get('hours', default=24, type=int)
    limit = min(request.args.get('limit', default=1000, type=int), 10000)

    cutoff_ms = int(time.time() * 1000) - hours * 3600 * 1000
    params = [cutoff_ms]
    query = (
        "SELECT timestamp, lat, lon, type, rssi, cell_id "
        "FROM logs WHERE timestamp > ?"
    )
    if cell_id_filter:
        query += " AND cell_id = ?"
        params.append(cell_id_filter)
    query += " ORDER BY timestamp DESC LIMIT ?"
    params.append(limit)

    try:
        conn = sqlite3.connect(ARCHIVE_DB)
        rows = conn.execute(query, tuple(params)).fetchall()
        conn.close()
    except Exception:
        logger.exception("history query failed")
        return jsonify({"error": "database error"}), 500

    result = [
        {"timestamp": r[0], "lat": r[1], "lon": r[2],
         "type": r[3], "rssi": r[4], "cell_id": r[5]}
        for r in rows
    ]
    return jsonify(result)


@app.route('/stats')
def stats():
    """リアルタイム層とアーカイブ層のレコード数を返す。"""
    db = get_db()
    realtime_count = db.execute("SELECT COUNT(*) FROM logs").fetchone()[0]

    try:
        conn = sqlite3.connect(ARCHIVE_DB)
        archive_count = conn.execute("SELECT COUNT(*) FROM logs").fetchone()[0]
        conn.close()
    except Exception:
        archive_count = None

    return jsonify({
        "realtime_count": realtime_count,
        "archive_count": archive_count,
    })


# ── global error handlers ─────────────────────────────────────────────────────

@app.errorhandler(400)
def bad_request(e):
    return jsonify({"error": "bad request", "message": str(e)}), 400


@app.errorhandler(404)
def not_found(e):
    return jsonify({"error": "not found", "message": str(e)}), 404


@app.errorhandler(500)
def internal_error(e):
    logger.exception("Unhandled exception")
    return jsonify({"error": "internal server error"}), 500


@app.route('/map')
def map_page():
    """Leafletで地図を表示するページ"""
    return render_template('map.html')


if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000)
