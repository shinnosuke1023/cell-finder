package com.example.cellfinder

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

data class CellLog(
    val timestamp: Long,
    val lat: Double?,
    val lon: Double?,
    val type: String?,
    val rssi: Int?,
    val cellId: String?
)

data class EstimatedBaseStation(
    val cellId: String,
    val type: String?,
    val lat: Double?,
    val lon: Double?,
    val count: Int
)

class CellDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "CellDatabase"
        private const val DATABASE_NAME = "cell_finder.db"
        private const val DATABASE_VERSION = 1
        
        private const val TABLE_LOGS = "logs"
        private const val COLUMN_ID = "id"
        private const val COLUMN_TIMESTAMP = "timestamp"
        private const val COLUMN_LAT = "lat"
        private const val COLUMN_LON = "lon"
        private const val COLUMN_TYPE = "type"
        private const val COLUMN_RSSI = "rssi"
        private const val COLUMN_CELL_ID = "cell_id"
    }

    override fun onCreate(db: SQLiteDatabase) {
        Log.d(TAG, "Creating database tables")
        val createTableQuery = """
            CREATE TABLE $TABLE_LOGS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_TIMESTAMP INTEGER,
                $COLUMN_LAT REAL,
                $COLUMN_LON REAL,
                $COLUMN_TYPE TEXT,
                $COLUMN_RSSI INTEGER,
                $COLUMN_CELL_ID TEXT
            )
        """.trimIndent()
        db.execSQL(createTableQuery)
        Log.d(TAG, "Database tables created successfully")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.d(TAG, "Upgrading database from version $oldVersion to $newVersion")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_LOGS")
        onCreate(db)
    }

    fun insertCellLog(cellLog: CellLog): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_TIMESTAMP, cellLog.timestamp)
            put(COLUMN_LAT, cellLog.lat)
            put(COLUMN_LON, cellLog.lon)
            put(COLUMN_TYPE, cellLog.type)
            put(COLUMN_RSSI, cellLog.rssi)
            put(COLUMN_CELL_ID, cellLog.cellId)
        }
        
        val id = db.insert(TABLE_LOGS, null, values)
        Log.d(TAG, "Inserted cell log with ID: $id")
        return id
    }

    fun insertCellLogs(cellLogs: List<CellLog>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            cellLogs.forEach { cellLog ->
                val values = ContentValues().apply {
                    put(COLUMN_TIMESTAMP, cellLog.timestamp)
                    put(COLUMN_LAT, cellLog.lat)
                    put(COLUMN_LON, cellLog.lon)
                    put(COLUMN_TYPE, cellLog.type)
                    put(COLUMN_RSSI, cellLog.rssi)
                    put(COLUMN_CELL_ID, cellLog.cellId)
                }
                db.insert(TABLE_LOGS, null, values)
            }
            db.setTransactionSuccessful()
            Log.d(TAG, "Inserted ${cellLogs.size} cell logs")
        } finally {
            db.endTransaction()
        }
    }

    fun getRecentCellLogs(windowMinutes: Int = 60): List<CellLog> {
        val cutoffTime = System.currentTimeMillis() - (windowMinutes * 60 * 1000)
        val db = readableDatabase
        val cursor = db.query(
            TABLE_LOGS,
            null,
            "$COLUMN_TIMESTAMP > ?",
            arrayOf(cutoffTime.toString()),
            null,
            null,
            "$COLUMN_TIMESTAMP DESC"
        )

        val cellLogs = mutableListOf<CellLog>()
        cursor.use {
            while (it.moveToNext()) {
                val cellLog = CellLog(
                    timestamp = it.getLong(it.getColumnIndexOrThrow(COLUMN_TIMESTAMP)),
                    lat = if (it.isNull(it.getColumnIndexOrThrow(COLUMN_LAT))) null 
                          else it.getDouble(it.getColumnIndexOrThrow(COLUMN_LAT)),
                    lon = if (it.isNull(it.getColumnIndexOrThrow(COLUMN_LON))) null 
                          else it.getDouble(it.getColumnIndexOrThrow(COLUMN_LON)),
                    type = it.getString(it.getColumnIndexOrThrow(COLUMN_TYPE)),
                    rssi = if (it.isNull(it.getColumnIndexOrThrow(COLUMN_RSSI))) null 
                           else it.getInt(it.getColumnIndexOrThrow(COLUMN_RSSI)),
                    cellId = it.getString(it.getColumnIndexOrThrow(COLUMN_CELL_ID))
                )
                cellLogs.add(cellLog)
            }
        }
        
        Log.d(TAG, "Retrieved ${cellLogs.size} recent cell logs")
        return cellLogs
    }

    fun getCellLogsGroupedByCell(windowMinutes: Int = 60): Map<String, List<CellLog>> {
        val logs = getRecentCellLogs(windowMinutes)
        return logs.filter { it.cellId != null }
                   .groupBy { it.cellId!! }
    }

    fun clearOldData(retentionHours: Int = 24) {
        val cutoffTime = System.currentTimeMillis() - (retentionHours * 60 * 60 * 1000)
        val db = writableDatabase
        val deletedRows = db.delete(TABLE_LOGS, "$COLUMN_TIMESTAMP < ?", arrayOf(cutoffTime.toString()))
        Log.d(TAG, "Cleared $deletedRows old records")
    }
    
    fun clearAllLogs(): Int {
        val db = writableDatabase
        val deletedRows = db.delete(TABLE_LOGS, null, null)
        Log.d(TAG, "Cleared all $deletedRows log records")
        return deletedRows
    }
}