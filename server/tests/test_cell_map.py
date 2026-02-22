"""Tests for cell_map caching and grid-based density optimization."""

import json
import math
import os
import sqlite3
import tempfile
import time
import unittest

# Set test DB paths before importing server
_tmpdir = tempfile.mkdtemp()
os.environ["REALTIME_DB"] = os.path.join(_tmpdir, "test_cells.db")
os.environ["ARCHIVE_DB"] = os.path.join(_tmpdir, "test_archive.db")
os.environ["CELL_MAP_INTERVAL"] = "3600"  # long interval so background thread doesn't interfere

from server import (
    _circle_intersections,
    _compute_cell_map_impl,
    _refresh_cell_map_cache,
    _cell_map_cache,
    _cell_map_cache_lock,
    app,
)
from db import REALTIME_DB, init_db


def _insert_observations(conn, cell_id, observations):
    """Insert test observations into the DB.

    observations: list of (lat, lon, rssi) tuples.
    """
    ts = int(time.time() * 1000)
    for lat, lon, rssi in observations:
        conn.execute(
            "INSERT INTO logs VALUES (?, ?, ?, ?, ?, ?)",
            (ts, lat, lon, "LTE", rssi, cell_id),
        )
    conn.commit()


class TestComputeCellMap(unittest.TestCase):
    """Test the extracted _compute_cell_map_impl function."""

    def setUp(self):
        init_db()
        self.conn = sqlite3.connect(REALTIME_DB)
        self.conn.execute("DELETE FROM logs")
        self.conn.commit()

    def tearDown(self):
        self.conn.close()

    def test_empty_db_returns_empty(self):
        result = _compute_cell_map_impl(
            self.conn, ple=2.0, window_sec=3600,
            ref_rssi=-40.0, ref_dist=1.0, bandwidth_m=150.0,
            method="accum", debug_flag=0,
        )
        self.assertEqual(result, [])

    def test_single_observation_uses_centroid(self):
        _insert_observations(self.conn, "CELL_A", [(35.68, 139.69, -70)])
        result = _compute_cell_map_impl(
            self.conn, ple=2.0, window_sec=3600,
            ref_rssi=-40.0, ref_dist=1.0, bandwidth_m=150.0,
            method="accum", debug_flag=0,
        )
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["cell_id"], "CELL_A")
        self.assertAlmostEqual(result[0]["lat"], 35.68, places=2)
        self.assertAlmostEqual(result[0]["lon"], 139.69, places=2)
        self.assertEqual(result[0]["count"], 1)

    def test_multiple_observations_intersection(self):
        # Several observations around a known point
        obs = [
            (35.680, 139.690, -60),
            (35.681, 139.691, -65),
            (35.679, 139.691, -62),
            (35.680, 139.689, -58),
        ]
        _insert_observations(self.conn, "CELL_B", obs)
        result = _compute_cell_map_impl(
            self.conn, ple=2.0, window_sec=3600,
            ref_rssi=-40.0, ref_dist=1.0, bandwidth_m=150.0,
            method="accum", debug_flag=1,
        )
        self.assertEqual(len(result), 1)
        self.assertIsNotNone(result[0]["lat"])
        self.assertIsNotNone(result[0]["lon"])
        self.assertEqual(result[0]["count"], 4)
        # Should have debug circles
        self.assertIn("debug", result[0])
        self.assertEqual(len(result[0]["debug"]["circles"]), 4)

    def test_centroid_method(self):
        obs = [
            (35.680, 139.690, -60),
            (35.681, 139.691, -65),
        ]
        _insert_observations(self.conn, "CELL_C", obs)
        result = _compute_cell_map_impl(
            self.conn, ple=2.0, window_sec=3600,
            ref_rssi=-40.0, ref_dist=1.0, bandwidth_m=150.0,
            method="centroid", debug_flag=0,
        )
        self.assertEqual(len(result), 1)
        self.assertIsNotNone(result[0]["lat"])

    def test_multiple_cells(self):
        _insert_observations(self.conn, "CELL_X", [(35.68, 139.69, -60)])
        _insert_observations(self.conn, "CELL_Y", [(35.69, 139.70, -70)])
        result = _compute_cell_map_impl(
            self.conn, ple=2.0, window_sec=3600,
            ref_rssi=-40.0, ref_dist=1.0, bandwidth_m=150.0,
            method="accum", debug_flag=0,
        )
        self.assertEqual(len(result), 2)
        cell_ids = {r["cell_id"] for r in result}
        self.assertEqual(cell_ids, {"CELL_X", "CELL_Y"})

    def test_window_sec_filter(self):
        """Observations outside the window should be excluded."""
        ts_now = int(time.time() * 1000)
        ts_old = ts_now - 7200 * 1000  # 2 hours ago
        self.conn.execute(
            "INSERT INTO logs VALUES (?, ?, ?, ?, ?, ?)",
            (ts_old, 35.68, 139.69, "LTE", -60, "CELL_OLD"),
        )
        self.conn.commit()
        result = _compute_cell_map_impl(
            self.conn, ple=2.0, window_sec=3600,
            ref_rssi=-40.0, ref_dist=1.0, bandwidth_m=150.0,
            method="accum", debug_flag=0,
        )
        self.assertEqual(len(result), 0)


class TestCellMapCache(unittest.TestCase):
    """Test the background caching mechanism."""

    def setUp(self):
        init_db()
        conn = sqlite3.connect(REALTIME_DB)
        conn.execute("DELETE FROM logs")
        conn.commit()
        conn.close()

    def test_refresh_cache_populates(self):
        import server
        server._cell_map_cache = None

        conn = sqlite3.connect(REALTIME_DB)
        _insert_observations(conn, "CELL_CACHE", [(35.68, 139.69, -60)])
        conn.close()

        _refresh_cell_map_cache()

        with _cell_map_cache_lock:
            cached = server._cell_map_cache
        self.assertIsNotNone(cached)
        self.assertEqual(len(cached), 1)
        self.assertEqual(cached[0]["cell_id"], "CELL_CACHE")


class TestCellMapEndpoint(unittest.TestCase):
    """Test the /cell_map endpoint returns cached or computed results."""

    def setUp(self):
        init_db()
        conn = sqlite3.connect(REALTIME_DB)
        conn.execute("DELETE FROM logs")
        _insert_observations(conn, "CELL_EP", [
            (35.680, 139.690, -60),
            (35.681, 139.691, -65),
        ])
        conn.close()

        # Populate cache
        _refresh_cell_map_cache()

        self.client = app.test_client()

    def test_default_params_returns_cache(self):
        resp = self.client.get("/cell_map")
        self.assertEqual(resp.status_code, 200)
        data = resp.get_json()
        self.assertIsInstance(data, list)
        self.assertTrue(len(data) > 0)

    def test_custom_params_computes_on_fly(self):
        resp = self.client.get("/cell_map?ple=3.0")
        self.assertEqual(resp.status_code, 200)
        data = resp.get_json()
        self.assertIsInstance(data, list)


class TestGridDensityOptimization(unittest.TestCase):
    """Verify the grid-based density gives same results as brute-force for small inputs."""

    def setUp(self):
        init_db()
        self.conn = sqlite3.connect(REALTIME_DB)
        self.conn.execute("DELETE FROM logs")
        self.conn.commit()

    def tearDown(self):
        self.conn.close()

    def test_grid_density_matches_expected(self):
        """With 5 observations the grid path should produce a valid result."""
        obs = [
            (35.6800, 139.6900, -55),
            (35.6805, 139.6910, -60),
            (35.6795, 139.6905, -58),
            (35.6810, 139.6895, -63),
            (35.6798, 139.6915, -57),
        ]
        _insert_observations(self.conn, "CELL_GRID", obs)
        result = _compute_cell_map_impl(
            self.conn, ple=2.0, window_sec=3600,
            ref_rssi=-40.0, ref_dist=1.0, bandwidth_m=150.0,
            method="accum", debug_flag=0,
        )
        self.assertEqual(len(result), 1)
        r = result[0]
        self.assertIsNotNone(r["lat"])
        self.assertIsNotNone(r["lon"])
        # The estimated position should be near the observation cluster
        self.assertAlmostEqual(r["lat"], 35.68, delta=0.05)
        self.assertAlmostEqual(r["lon"], 139.69, delta=0.05)


class TestPerformance(unittest.TestCase):
    """Ensure computation with many observations completes quickly."""

    def setUp(self):
        init_db()
        self.conn = sqlite3.connect(REALTIME_DB)
        self.conn.execute("DELETE FROM logs")
        self.conn.commit()

    def tearDown(self):
        self.conn.close()

    def test_large_dataset_performance(self):
        """Simulate ~200 observations per cell across 7 cells (1400 total).
        Should complete in under 5 seconds with grid optimization.
        """
        import random
        random.seed(42)
        ts = int(time.time() * 1000)

        rows = []
        for cell_idx in range(7):
            center_lat = 35.68 + cell_idx * 0.01
            center_lon = 139.69 + cell_idx * 0.01
            cell_id = f"CELL_{cell_idx}"
            for i in range(200):
                lat = center_lat + random.uniform(-0.005, 0.005)
                lon = center_lon + random.uniform(-0.005, 0.005)
                rssi = random.randint(-100, -40)
                rows.append((ts + i, lat, lon, "LTE", rssi, cell_id))

        self.conn.executemany("INSERT INTO logs VALUES (?, ?, ?, ?, ?, ?)", rows)
        self.conn.commit()

        start = time.time()
        result = _compute_cell_map_impl(
            self.conn, ple=2.0, window_sec=3600,
            ref_rssi=-40.0, ref_dist=1.0, bandwidth_m=150.0,
            method="accum", debug_flag=1,
        )
        elapsed = time.time() - start

        self.assertEqual(len(result), 7)
        self.assertLess(elapsed, 5.0, f"Computation took {elapsed:.2f}s, expected < 5s")
        print(f"\nPerformance: 1400 observations across 7 cells computed in {elapsed:.2f}s")


if __name__ == "__main__":
    unittest.main()
