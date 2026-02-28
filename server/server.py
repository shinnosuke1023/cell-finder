import collections
import logging
import math
import sqlite3
import threading
import time

from flask import Flask, jsonify, render_template, request
from flask_cors import CORS

from db import ARCHIVE_DB, REALTIME_DB, RETENTION_HOURS, close_db, get_db, init_db, robust_connect, start_archive_timer
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


# ── async write queue ─────────────────────────────────────────────────────────
_write_queue = collections.deque()       # thread-safe appendleft / extend
_write_queue_lock = threading.Lock()     # for draining
FLUSH_INTERVAL = 0.25                    # seconds


def _flush_write_queue():
    """Background thread: drains the queue and bulk-inserts into SQLite."""
    conn = None

    def _ensure_conn():
        nonlocal conn
        if conn is not None:
            return conn
        try:
            conn = robust_connect(REALTIME_DB)
        except Exception:
            logger.warning("write-flush: DB not ready, will retry")
            conn = None
        return conn

    while True:
        time.sleep(FLUSH_INTERVAL)
        # Drain the queue
        rows = []
        with _write_queue_lock:
            while _write_queue:
                rows.append(_write_queue.popleft())
        if not rows:
            continue
        c = _ensure_conn()
        if c is None:
            # Put rows back so they aren't lost
            _write_queue.extend(rows)
            continue
        t0 = time.monotonic()
        try:
            c.executemany("INSERT INTO logs VALUES (?, ?, ?, ?, ?, ?)", rows)
            c.commit()
            elapsed = time.monotonic() - t0
            logger.info("Flushed %d rows in %.3fs", len(rows), elapsed)
        except Exception:
            logger.exception("Error flushing write queue (%d rows)", len(rows))
            conn = None  # reset connection on error


_flush_thread = threading.Thread(target=_flush_write_queue, daemon=True, name="write-flush")
_flush_thread.start()
logger.info("Write queue flush thread started (interval=%.2fs)", FLUSH_INTERVAL)


# ── TTL cache for read endpoints ──────────────────────────────────────────────
class TTLCache:
    """Simple thread-safe TTL cache for read endpoint results."""

    def __init__(self, ttl_sec=3.0):
        self._ttl = ttl_sec
        self._lock = threading.Lock()
        self._store = {}  # key -> (expire_time, value)

    def get(self, key):
        """Return cached value or None if expired/missing."""
        with self._lock:
            entry = self._store.get(key)
            if entry and time.monotonic() < entry[0]:
                return entry[1]
        return None

    def put(self, key, value):
        with self._lock:
            self._store[key] = (time.monotonic() + self._ttl, value)


_read_cache = TTLCache(ttl_sec=3.0)


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

    # Build rows and enqueue (no DB lock needed)
    rows = []
    dirty_ids = []
    gsm_cells = []
    for cell in cells:
        cell_id = cell.get("cell_id")
        cell_type = cell.get("type")
        rssi = cell.get("rssi")

        # Skip cells with abnormal RSSI (e.g. INT32_MAX = 2147483647 means unavailable)
        if rssi is not None:
            try:
                rssi_val = int(rssi)
            except (TypeError, ValueError):
                rssi_val = None
            if rssi_val is None or rssi_val > 0 or rssi_val < -200:
                logger.debug("Skipping cell with invalid RSSI=%s (cell_id=%s)", rssi, cell_id)
                continue

        rows.append((timestamp, lat, lon, cell_type, rssi, cell_id))
        if cell_id is not None:
            dirty_ids.append(cell_id)
        if cell_type == "GSM":
            gsm_cells.append(cell_id)

    # Enqueue for async write — returns immediately
    _write_queue.extend(rows)
    logger.debug("Enqueued %d cell records", len(cells))

    # Mark affected cell_ids as dirty in the cache
    if dirty_ids:
        cell_cache.mark_dirty(dirty_ids)

    # Log warning when GSM cells are detected
    if gsm_cells:
        logger.warning(
            "GSM ALERT: %d GSM cell(s) detected at lat=%s, lon=%s, cells=%s",
            len(gsm_cells), lat, lon, gsm_cells,
        )

    return jsonify({"status": "ok"})


@app.route('/alerts')
def alerts():
    """Return recent GSM alerts (last 1 hour) derived from the logs table."""
    cached = _read_cache.get("alerts")
    if cached is not None:
        return jsonify(cached)

    db = get_db()
    one_hour_ago_ms = int(time.time() * 1000) - RETENTION_HOURS * 3600 * 1000
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

    result = list(grouped.values())
    _read_cache.put("alerts", result)
    return jsonify(result)


@app.route('/map_data')
def map_data():
    """通常の観測ログを返す（地図表示用）

    デフォルトでサーバーサイドで25mグリッド集約を行い、レスポンスサイズを大幅に削減する。
    ?raw=1 を指定すると全行を返す（後方互換性）。
    """
    cell_id_filter = request.args.get('cell_id', None)
    raw_mode = request.args.get('raw', '0') == '1'
    cache_key = f"map_data:{cell_id_filter}:{raw_mode}"

    cached = _read_cache.get(cache_key)
    if cached is not None:
        return jsonify(cached)

    db = get_db()
    one_hour_ago_ms = int(time.time() * 1000) - RETENTION_HOURS * 3600 * 1000

    params = [one_hour_ago_ms]
    query = "SELECT timestamp, lat, lon, type, rssi, cell_id FROM logs WHERE timestamp > ?"

    if cell_id_filter:
        query += " AND cell_id = ?"
        params.append(cell_id_filter)

    rows = db.execute(query, tuple(params)).fetchall()

    if raw_mode:
        result = [
            {"timestamp": r[0], "lat": r[1], "lon": r[2],
             "type": r[3], "rssi": r[4], "cell_id": r[5]}
            for r in rows
        ]
    else:
        # Server-side 25m grid aggregation (matches frontend gridBucket)
        GRID_M = 25.0
        grid = {}
        for ts, lat, lon, ctype, rssi, cell_id in rows:
            if lat is None or lon is None:
                continue
            rssi_val = rssi if rssi is not None else -100
            lat_deg = GRID_M / 111320.0
            lon_deg = GRID_M / (111320.0 * math.cos(lat * math.pi / 180.0))
            gi = int(lat / lat_deg) if lat_deg > 0 else 0
            gj = int(lon / lon_deg) if lon_deg > 0 else 0
            key = (gi, gj)
            prev = grid.get(key)
            if prev is None or rssi_val > prev["rssi"]:
                grid[key] = {
                    "timestamp": ts, "lat": lat, "lon": lon,
                    "type": ctype, "rssi": rssi_val, "cell_id": cell_id
                }
        result = list(grid.values())

    _read_cache.put(cache_key, result)
    return jsonify(result)


@app.route('/heatmap_data')
def heatmap_data():
    """ヒートマップ用のデータを返す"""
    cell_id_filter = request.args.get('cell_id', None)
    cache_key = f"heatmap_data:{cell_id_filter}"

    cached = _read_cache.get(cache_key)
    if cached is not None:
        return jsonify(cached)

    db = get_db()
    one_hour_ago_ms = int(time.time() * 1000) - RETENTION_HOURS * 3600 * 1000

    params = [one_hour_ago_ms]
    query = "SELECT lat, lon, rssi FROM logs WHERE timestamp > ?"

    if cell_id_filter:
        query += " AND cell_id = ?"
        params.append(cell_id_filter)

    rows = db.execute(query, tuple(params)).fetchall()

    # ヒートマップ用のデータ形式 [lat, lon, intensity]
    heatmap_points = []
    for lat, lon, rssi in rows:
        if lat is not None and lon is not None:
            rssi_val = rssi if rssi is not None else -100
            intensity = max(0.1, min(1.0, (rssi_val + 120) / 100))
            heatmap_points.append([lat, lon, intensity])

    _read_cache.put(cache_key, heatmap_points)
    return jsonify(heatmap_points)


@app.route('/cell_ids')
def get_cell_ids():
    """利用可能なセルIDのリストを返す"""
    cached = _read_cache.get("cell_ids")
    if cached is not None:
        return jsonify(cached)

    db = get_db()
    one_hour_ago_ms = int(time.time() * 1000) - RETENTION_HOURS * 3600 * 1000
    rows = db.execute(
        "SELECT DISTINCT cell_id FROM logs WHERE timestamp > ? AND cell_id IS NOT NULL ORDER BY cell_id",
        (one_hour_ago_ms,),
    ).fetchall()
    cell_ids = [row[0] for row in rows]
    _read_cache.put("cell_ids", cell_ids)
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
    cached = _read_cache.get("stats")
    if cached is not None:
        return jsonify(cached)

    db = get_db()
    realtime_count = db.execute("SELECT COUNT(*) FROM logs").fetchone()[0]

    try:
        conn = sqlite3.connect(ARCHIVE_DB)
        archive_count = conn.execute("SELECT COUNT(*) FROM logs").fetchone()[0]
        conn.close()
    except Exception:
        archive_count = None

    result = {
        "realtime_count": realtime_count,
        "archive_count": archive_count,
    }
    _read_cache.put("stats", result)
    return jsonify(result)


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
