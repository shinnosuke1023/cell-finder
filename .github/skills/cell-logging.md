# セルログ収集・可視化

## 概要

モバイルデバイスからセル情報（RSSI、位置、セルID）を収集し、ヒートマップやピンマーカーとして地図上に可視化します。

## 機能

- **セルログ収集**: Androidデバイスからのセル情報受信
- **データ永続化**: SQLiteデータベースへの保存
- **ヒートマップ表示**: RSSI強度に基づく色分け表示
- **ピン表示**: 個別の観測点をマーカーで表示
- **セルIDフィルタリング**: 特定のセルIDでのフィルタリング

## APIエンドポイント

### `/log` (POST)
セルログデータを受信・保存します。

**リクエストボディ**:
```json
{
  "timestamp": 1700000000000,
  "lat": 35.6812,
  "lon": 139.7671,
  "cells": [
    {
      "type": "GSM",
      "rssi": -85,
      "cell_id": "12345"
    }
  ]
}
```

### `/map_data` (GET)
地図表示用の観測ログを取得します。

**パラメータ**:
- `cell_id`: フィルタリングするセルID（オプション）

### `/heatmap_data` (GET)
ヒートマップ用のデータ（`[lat, lon, intensity]`形式）を取得します。

### `/cell_ids` (GET)
利用可能なセルIDのリストを取得します。

### `/map` (GET)
地図表示UIページを返します。

## データベーススキーマ

```sql
CREATE TABLE logs (
  timestamp INTEGER,
  lat REAL,
  lon REAL,
  type TEXT,
  rssi INTEGER,
  cell_id TEXT
)
```

## 関連ファイル

### サーバー側
- `server/server.py` - APIエンドポイント実装
- `server/cells.db` - SQLiteデータベース

### Android側
- `android/app/src/main/java/com/example/cellfinder/CellFinderService.kt` - セルログ送信サービス
- `android/app/src/main/java/com/example/cellfinder/CellInfoProvider.kt` - セル情報取得
- `android/app/src/main/java/com/example/cellfinder/CellDatabase.kt` - ローカルデータベース

## 使用例

### Androidからのログ送信
```kotlin
val cellData = mapOf(
    "timestamp" to System.currentTimeMillis(),
    "lat" to location.latitude,
    "lon" to location.longitude,
    "cells" to listOf(
        mapOf("type" to "GSM", "rssi" to -85, "cell_id" to "12345")
    )
)
// POST to /log endpoint
```

### サーバーでのデータ取得
```bash
# 全データの取得
curl http://localhost:5000/map_data

# 特定のセルIDでフィルタリング
curl "http://localhost:5000/map_data?cell_id=12345"

# ヒートマップデータの取得
curl http://localhost:5000/heatmap_data
```
