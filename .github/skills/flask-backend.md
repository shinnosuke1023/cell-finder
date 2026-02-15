# Flask バックエンド開発

## 概要

CellFinderのサーバーサイド開発に関するスキルとガイドラインです。

## 技術スタック

- **言語**: Python 3.x
- **フレームワーク**: Flask 2.3.2+
- **データベース**: SQLite3

## プロジェクト構成

```
server/
├── server.py           # メインアプリケーション
├── requirements.txt    # Python依存関係
└── cells.db           # SQLiteデータベース
```

## 依存関係

```
Flask==2.3.2
flask-cors==3.0.10
```

## データベース管理

### 接続管理
```python
def get_db():
    """Get a database connection for the current request context."""
    if 'db' not in g:
        g.db = sqlite3.connect(DATABASE)
    return g.db

@app.teardown_appcontext
def close_db(exception):
    """Close database connection at the end of request."""
    db = g.pop('db', None)
    if db is not None:
        db.close()
```

### スキーマ
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

## APIエンドポイント一覧

| エンドポイント | メソッド | 説明 |
|---------------|---------|------|
| `/log` | POST | セルログデータの送信 |
| `/map_data` | GET | 観測ログの取得 |
| `/heatmap_data` | GET | ヒートマップデータの取得 |
| `/cell_ids` | GET | セルIDリストの取得 |
| `/cell_map` | GET | 推定基地局位置の取得 |
| `/map` | GET | 地図UIページ |

## コーディング規約

### PEP 8 スタイル
- インデント: 4スペース
- 行の最大長: 79文字（推奨）
- 関数とクラスにはdocstringを記述

### 型ヒント
```python
def _rssi_to_distance_m(rssi_dbm: float, n: float, ref_rssi_dbm: float, ref_dist_m: float) -> float:
    """対数距離モデルでRSSIから距離を計算"""
    pass
```

### JSONレスポンス
```python
from flask import jsonify

@app.route('/example')
def example():
    return jsonify({"status": "ok", "data": result})
```

## コード例

### 新しいエンドポイントの追加
```python
@app.route('/new_endpoint', methods=['GET', 'POST'])
def new_endpoint():
    """新しいエンドポイントの説明"""
    if request.method == 'POST':
        data = request.get_json()
        # 処理
        return jsonify({"status": "ok"})
    else:
        db = get_db()
        c = db.cursor()
        c.execute("SELECT * FROM logs")
        rows = c.fetchall()
        return jsonify([dict(row) for row in rows])
```

### クエリパラメータの取得
```python
@app.route('/example')
def example():
    param_str = request.args.get('param', default='default', type=str)
    param_int = request.args.get('count', default=10, type=int)
    param_float = request.args.get('value', default=1.0, type=float)
    # ...
```

## 地理空間計算

### 緯度経度からメートル座標への変換
```python
def _ll_to_xy_m(lat: float, lon: float, lat0: float, lon0: float):
    R = 6371000.0
    rad = math.pi / 180.0
    x = R * math.cos(lat0 * rad) * (lon - lon0) * rad
    y = R * (lat - lat0) * rad
    return x, y
```

### 円交差計算
```python
def _circle_intersections(x0, y0, r0, x1, y1, r1):
    """2円の交点を計算"""
    # 実装詳細は server.py を参照
    pass
```

## 起動・テスト

### サーバーの起動
```bash
cd server
pip install -r requirements.txt
python server.py
```

### 動作確認
```bash
# ブラウザで開く
open http://localhost:5000/map

# APIテスト
curl http://localhost:5000/map_data
curl http://localhost:5000/cell_ids
```

## パフォーマンス特性

- 軽量SQLiteデータベース
- リアルタイムデータ更新
- 30秒ごとの自動リフレッシュ（Web UI）
- 1時間以内のログのみをクエリ（メモリ効率）
