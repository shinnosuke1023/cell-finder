"""Database layer for cell-finder server.

Two-tier architecture:
  - Realtime DB (cells.db by default): holds only the last RETENTION_HOURS of data.
  - Archive DB (archive.db by default): receives records older than RETENTION_HOURS.

Environment variables:
  RETENTION_HOURS  – how many hours to keep in the realtime DB (default: 3)
  ARCHIVE_INTERVAL – seconds between archive runs (default: 1800)
  REALTIME_DB      – path to the realtime SQLite file (default: cells.db)
  ARCHIVE_DB       – path to the archive SQLite file (default: archive.db)
"""

import os
import sqlite3
import threading
import time
import logging

logger = logging.getLogger(__name__)

RETENTION_HOURS = int(os.environ.get("RETENTION_HOURS", 3))
ARCHIVE_INTERVAL = int(os.environ.get("ARCHIVE_INTERVAL", 1800))
REALTIME_DB = os.environ.get("REALTIME_DB", "cells.db")
ARCHIVE_DB = os.environ.get("ARCHIVE_DB", "archive.db")

# Minimum number of deleted rows before triggering VACUUM (avoid frequent VACUUM on small deletes)
VACUUM_THRESHOLD = 100

_local = threading.local()


def get_db():
    """Return a per-thread SQLite connection to the realtime DB."""
    if not hasattr(_local, "db") or _local.db is None:
        conn = sqlite3.connect(REALTIME_DB)
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA busy_timeout=5000")
        conn.execute("PRAGMA synchronous=NORMAL")
        conn.execute("PRAGMA cache_size=-8000")
        conn.execute("PRAGMA temp_store=MEMORY")
        _local.db = conn
    return _local.db


def close_db():
    """Close the per-thread realtime DB connection."""
    db = getattr(_local, "db", None)
    if db is not None:
        db.close()
        _local.db = None


def _get_archive_db():
    """Return a new connection to the archive DB (not cached per thread)."""
    conn = sqlite3.connect(ARCHIVE_DB)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA busy_timeout=5000")
    conn.execute("PRAGMA synchronous=NORMAL")
    conn.execute("PRAGMA cache_size=-8000")
    conn.execute("PRAGMA temp_store=MEMORY")
    return conn


_SCHEMA_DDL = """
CREATE TABLE IF NOT EXISTS logs (
    timestamp INTEGER,
    lat REAL,
    lon REAL,
    type TEXT,
    rssi INTEGER,
    cell_id TEXT
);
CREATE INDEX IF NOT EXISTS idx_logs_timestamp ON logs (timestamp);
CREATE INDEX IF NOT EXISTS idx_logs_cell_id   ON logs (cell_id);
"""


def init_db():
    """Initialise both the realtime and archive databases."""
    for path in (REALTIME_DB, ARCHIVE_DB):
        conn = sqlite3.connect(path)
        conn.executescript(_SCHEMA_DDL)
        conn.commit()
        conn.close()
    logger.info("Databases initialised (realtime=%s, archive=%s)", REALTIME_DB, ARCHIVE_DB)


def archive_and_cleanup():
    """Move records older than RETENTION_HOURS from realtime DB to archive DB, then VACUUM if helpful."""
    cutoff_ms = int(time.time() * 1000) - RETENTION_HOURS * 3600 * 1000

    realtime = sqlite3.connect(REALTIME_DB)
    realtime.execute("PRAGMA journal_mode=WAL")
    realtime.execute("PRAGMA busy_timeout=5000")

    archive = _get_archive_db()

    try:
        old_rows = realtime.execute(
            "SELECT timestamp, lat, lon, type, rssi, cell_id FROM logs WHERE timestamp < ?",
            (cutoff_ms,)
        ).fetchall()

        if old_rows:
            archive.executemany(
                "INSERT INTO logs VALUES (?, ?, ?, ?, ?, ?)",
                old_rows
            )
            archive.commit()

            realtime.execute("DELETE FROM logs WHERE timestamp < ?", (cutoff_ms,))
            realtime.commit()

            logger.info("Archived %d rows (cutoff=%s ms)", len(old_rows), cutoff_ms)

            # VACUUM only when we deleted a meaningful number of rows
            if len(old_rows) >= VACUUM_THRESHOLD:
                realtime.execute("VACUUM")
                logger.info("VACUUM completed on realtime DB")
    except Exception:
        logger.exception("Error during archive_and_cleanup")
        realtime.rollback()
        archive.rollback()
    finally:
        realtime.close()
        archive.close()


def start_archive_timer():
    """Start a background daemon thread that calls archive_and_cleanup periodically."""

    def _run():
        while True:
            time.sleep(ARCHIVE_INTERVAL)
            archive_and_cleanup()

    t = threading.Thread(target=_run, daemon=True, name="archive-timer")
    t.start()
    logger.info("Archive timer started (interval=%ds, retention=%dh)", ARCHIVE_INTERVAL, RETENTION_HOURS)
    return t
