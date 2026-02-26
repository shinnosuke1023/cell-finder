"""Background cache for /cell_map computation.

Runs a background thread that periodically recomputes cell positions.
Only recomputes cell_ids that have been marked dirty (new logs received).
Browser requests receive pre-computed results instantly.

Environment variables:
  CACHE_INTERVAL – seconds between recomputation cycles (default: 5)
"""

import logging
import math
import os
import sqlite3
import threading
import time

from db import REALTIME_DB

logger = logging.getLogger(__name__)

CACHE_INTERVAL = int(os.environ.get("CACHE_INTERVAL", 5))

# Default parameters used for cached computation
DEFAULT_PARAMS = {
    "ple": 2.0,
    "ref_rssi": -40.0,
    "ref_dist": 1.0,
    "bandwidth_m": 150.0,
    "window_sec": 3600,
    "method": "accum",
    "debug": 1,
}


# ── helper functions (same as server.py) ─────────────────────────────────────

def _rssi_to_distance_m(rssi_dbm: float, n: float, ref_rssi_dbm: float, ref_dist_m: float) -> float:
    n = max(n, 0.1)
    d = ref_dist_m * (10 ** ((ref_rssi_dbm - rssi_dbm) / (10.0 * n)))
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
    dx = x1 - x0
    dy = y1 - y0
    d = math.hypot(dx, dy)
    if d <= 1e-6 or d > r0 + r1 or d < abs(r0 - r1):
        return []
    a = (r0 * r0 - r1 * r1 + d * d) / (2 * d)
    h2 = r0 * r0 - a * a
    if h2 < 0:
        h2 = 0.0
    h = math.sqrt(h2)
    xm = x0 + a * dx / d
    ym = y0 + a * dy / d
    rx = -dy * (h / d)
    ry = dx * (h / d)
    p1 = (xm + rx, ym + ry)
    p2 = (xm - rx, ym - ry)
    denom = max(1e-6, min(r0, r1))
    w_angle = max(0.0, min(1.0, h / denom))
    if h <= 1e-6:
        return [(p1[0], p1[1], w_angle)]
    return [(p1[0], p1[1], w_angle), (p2[0], p2[1], w_angle)]


# ── per-cell computation ─────────────────────────────────────────────────────

def compute_cell_position(cell_id, ctype, logs, params):
    """Compute estimated position for a single cell_id from its observation logs.

    Args:
        cell_id: cell identifier
        ctype: cell type string (e.g. 'LTE', 'GSM')
        logs: list of dicts with keys 'lat', 'lon', 'rssi'
        params: dict with keys 'ple', 'ref_rssi', 'ref_dist', 'bandwidth_m', 'method', 'debug'

    Returns:
        dict with cell position estimate and optional debug info
    """
    ple = params["ple"]
    ref_rssi = params["ref_rssi"]
    ref_dist = params["ref_dist"]
    bandwidth_m = params["bandwidth_m"]
    method = params["method"]
    debug_flag = params["debug"]

    if len(logs) == 0:
        return {"cell_id": cell_id, "type": ctype, "lat": None, "lon": None, "count": 0}

    # Centroid fallback
    def centroid_estimate():
        sum_lat = sum_lon = sum_w = 0.0
        for log in logs:
            rssi_dbm = max(min(log["rssi"], -20.0), -140.0)
            p_mw = 10 ** (rssi_dbm / 10.0)
            w = p_mw ** (2.0 / max(ple, 0.1))
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
            out["debug"] = {"circles": [
                {"lat": l["lat"], "lon": l["lon"],
                 "radius_m": _rssi_to_distance_m(max(min(l["rssi"], -20.0), -140.0), ple, ref_rssi, ref_dist)}
                for l in logs
            ]}
        return out

    # Local plane processing
    lat0 = sum(l["lat"] for l in logs) / len(logs)
    lon0 = sum(l["lon"] for l in logs) / len(logs)

    pts = []
    debug_circles = []
    for log in logs:
        rssi_dbm = max(min(log["rssi"], -20.0), -140.0)
        d_m = _rssi_to_distance_m(rssi_dbm, ple, ref_rssi, ref_dist)
        x, y = _ll_to_xy_m(log["lat"], log["lon"], lat0, lon0)
        pts.append((x, y, d_m))
        if debug_flag:
            debug_circles.append({"lat": log["lat"], "lon": log["lon"], "radius_m": d_m})

    # Collect circle intersections
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
        return out

    # Density voting
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

    # Weighted average around best cluster
    sumx = sumy = sumw = 0.0
    for (xi, yi, wi) in intersections:
        d2 = (xi - cx) * (xi - cx) + (yi - cy) * (yi - cy)
        if d2 <= bw * bw:
            w_dist = 1.0 - math.sqrt(d2) / bw
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
        "count": len(logs),
    }
    if debug_flag:
        out["debug"] = {"circles": debug_circles}
    return out


# ── cache class ──────────────────────────────────────────────────────────────

class CellMapCache:
    """Thread-safe background cache for cell_map results."""

    def __init__(self, db_path=None, interval=None):
        self._db_path = db_path or REALTIME_DB
        self._interval = interval or CACHE_INTERVAL
        self._cache = {}          # cell_id -> computed result dict
        self._dirty_cells = set() # cell_ids that need recomputation
        self._all_dirty = True    # True = full recomputation needed (startup)
        self._lock = threading.Lock()
        self._params = dict(DEFAULT_PARAMS)
        self._last_computed = 0.0  # timestamp of last computation

    def mark_dirty(self, cell_ids):
        """Mark cell_ids as needing recomputation. Called from /log."""
        with self._lock:
            for cid in cell_ids:
                if cid is not None:
                    self._dirty_cells.add(cid)

    def get_cached_result(self):
        """Return the current cached results as a list.

        Returns:
            list[dict]: list of cell position estimates
        """
        with self._lock:
            return list(self._cache.values())

    def _fetch_logs_for_cells(self, cell_ids=None):
        """Fetch observation logs from DB, optionally filtered to specific cell_ids.

        Returns:
            dict: cell_id -> {"type": str, "logs": [{"lat", "lon", "rssi"}]}
        """
        conn = sqlite3.connect(self._db_path)
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA busy_timeout=5000")

        window_sec = self._params["window_sec"]
        cutoff_ms = int(time.time() * 1000) - window_sec * 1000

        if cell_ids is not None and len(cell_ids) > 0:
            # Fetch only specified cell_ids
            placeholders = ",".join("?" for _ in cell_ids)
            query = (
                f"SELECT cell_id, type, lat, lon, rssi, timestamp FROM logs "
                f"WHERE cell_id IS NOT NULL AND timestamp > ? "
                f"AND cell_id IN ({placeholders})"
            )
            params = [cutoff_ms] + list(cell_ids)
        else:
            query = (
                "SELECT cell_id, type, lat, lon, rssi, timestamp FROM logs "
                "WHERE cell_id IS NOT NULL AND timestamp > ?"
            )
            params = [cutoff_ms]

        rows = conn.execute(query, tuple(params)).fetchall()
        conn.close()

        # Deduplicate: keep latest per (cell_id, type, lat, lon)
        latest_rows = {}
        for cell_id, ctype, lat, lon, rssi, ts in rows:
            if lat is None or lon is None:
                continue
            key = (cell_id, ctype, lat, lon)
            prev = latest_rows.get(key)
            if prev is None or ts > prev[-1]:
                latest_rows[key] = (cell_id, ctype, lat, lon, rssi, ts)

        # Group by cell_id
        by_cell = {}
        for cell_id, ctype, lat, lon, rssi, _ts in latest_rows.values():
            try:
                rssi_dbm = float(rssi)
            except Exception:
                continue
            by_cell.setdefault(cell_id, {"type": ctype, "logs": []})
            by_cell[cell_id]["logs"].append({"lat": lat, "lon": lon, "rssi": rssi_dbm})

        return by_cell

    def _recompute(self):
        """Run one computation cycle: full or incremental."""
        with self._lock:
            all_dirty = self._all_dirty
            dirty_cells = set(self._dirty_cells)
            self._dirty_cells.clear()
            self._all_dirty = False

        if all_dirty:
            # Full recomputation
            logger.info("Cache: full recomputation starting...")
            t0 = time.monotonic()
            by_cell = self._fetch_logs_for_cells(cell_ids=None)

            new_cache = {}
            for cell_id, info in by_cell.items():
                result = compute_cell_position(
                    cell_id, info["type"], info["logs"], self._params
                )
                new_cache[cell_id] = result

            with self._lock:
                self._cache = new_cache
                self._last_computed = time.time()

            elapsed = time.monotonic() - t0
            logger.info(
                "Cache: full recomputation done – %d cells in %.2fs",
                len(new_cache), elapsed,
            )

        elif dirty_cells:
            # Incremental recomputation
            t0 = time.monotonic()
            by_cell = self._fetch_logs_for_cells(cell_ids=dirty_cells)

            with self._lock:
                for cell_id in dirty_cells:
                    if cell_id in by_cell:
                        info = by_cell[cell_id]
                        result = compute_cell_position(
                            cell_id, info["type"], info["logs"], self._params
                        )
                        self._cache[cell_id] = result
                    else:
                        # Cell has no logs in window anymore – remove from cache
                        self._cache.pop(cell_id, None)
                self._last_computed = time.time()

            elapsed = time.monotonic() - t0
            logger.info(
                "Cache: incremental recomputation – %d dirty cells in %.2fs",
                len(dirty_cells), elapsed,
            )
        # else: nothing dirty, skip

    def _background_loop(self):
        """Background thread main loop."""
        # Initial full computation
        try:
            self._recompute()
        except Exception:
            logger.exception("Cache: error during initial recomputation")

        while True:
            time.sleep(self._interval)
            try:
                self._recompute()
            except Exception:
                logger.exception("Cache: error during background recomputation")

    def start(self):
        """Start the background computation thread."""
        t = threading.Thread(target=self._background_loop, daemon=True, name="cell-cache")
        t.start()
        logger.info(
            "Cell cache started (interval=%ds, params=%s)",
            self._interval, self._params,
        )
        return t
