import logging
import sqlite3
import time

from flask import Flask, jsonify, render_template, request
from flask_cors import CORS

from db import ARCHIVE_DB, close_db, get_db, init_db, start_archive_timer
from cell_cache import CellMapCache

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)

app = Flask(__name__)
CORS(app)


@app.teardown_appcontext
def _close_db(exception):
    """Close the per-thread DB connection at end of request."""
    close_db()


# ── initialisation ────────────────────────────────────────────────────────────
init_db()
start_archive_timer()

# ── cell_map cache ────────────────────────────────────────────────────────────
cell_cache = CellMapCache()
cell_cache.start()

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

    # Mark affected cell_ids as dirty in the cache
    dirty_ids = [c.get("cell_id") for c in cells if c.get("cell_id") is not None]
    if dirty_ids:
        cell_cache.mark_dirty(dirty_ids)

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

@app.route('/cell_map')
def cell_map():
    """セルごとに基地局位置の推定結果を返す。

    計算はバックグラウンドスレッドで定周期（デフォルト5秒）に行われ、
    このエンドポイントはキャッシュ済みの結果を即座に返す。
    /log で新しいデータが送られたセルIDだけが次の計算サイクルで再計算される。
    """
    cached = cell_cache.get_cached_result()
    logger.debug("cell_map: returning %d cached results", len(cached))
    return jsonify(cached)


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
