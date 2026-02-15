# SKILL.md - CellFinder Project Skills

## プロジェクト概要

CellFinderは、モバイル基地局（特に2G偽基地局/IMSIキャッチャー）の位置をリアルタイムで推定・可視化するためのシステムです。

## 主要機能

### 1. EKF（拡張カルマンフィルタ）ベースの位置推定
- **機能**: 単一の2G偽基地局の位置をリアルタイムで推定
- **技術**: Extended Kalman Filter (EKF)
- **特徴**:
  - 自己キャリブレーション機能（P0、η自動調整）
  - UTM座標系での精密計算
  - 不確実性の可視化（誤差円）
  - ユーザー軌跡の表示

### 2. 円交差法による基地局位置推定
- **機能**: RSSI値から推定した距離円の交点を使用して基地局位置を推定
- **技術**: 対数距離パス損失モデル + 円交差アルゴリズム
- **エンドポイント**: `/cell_map`

### 3. セルログ収集・可視化
- **機能**: モバイルデバイスからのセル情報収集とヒートマップ/ピン表示
- **エンドポイント**: `/log`, `/map_data`, `/heatmap_data`

## 技術スキル

### Android開発 (Kotlin)
- MVVM アーキテクチャ設計
- Foreground Service の実装
- Google Maps SDK の統合
- 位置情報・電話状態APIの活用
- Coroutines による非同期処理
- EJML による行列演算

### バックエンド開発 (Python/Flask)
- RESTful API の設計・実装
- SQLite データベース管理
- 地理空間データ処理
- ヒートマップデータの生成

### 数学・アルゴリズム
- 拡張カルマンフィルタ (EKF)
- 対数距離パス損失モデル
- UTM座標系変換
- 円交差計算
- 重み付き重心計算

## API エンドポイント

| エンドポイント | メソッド | 説明 |
|---------------|---------|------|
| `/log` | POST | セルログデータの送信 |
| `/map_data` | GET | 観測ログの取得（地図表示用） |
| `/heatmap_data` | GET | ヒートマップデータの取得 |
| `/cell_ids` | GET | 利用可能なセルIDリストの取得 |
| `/cell_map` | GET | 推定基地局位置の取得 |
| `/map` | GET | 地図UIページ |

## 動作要件

### Android アプリ
- Android 8.0 (API 26) 以上
- GPS/位置情報サービス有効
- 2G/GSMネットワーク（偽基地局検出用）
- 必要なパーミッション:
  - `ACCESS_FINE_LOCATION`
  - `ACCESS_COARSE_LOCATION`
  - `READ_PHONE_STATE`
  - `FOREGROUND_SERVICE`

### サーバー
- Python 3.x
- Flask 2.3.2+
- SQLite3

## 使用例

### EKF トラッキングの開始
1. アプリを起動し、必要なパーミッションを許可
2. 「Start EKF Tracking」をタップ
3. 「Show Map」を開き、「EKF Tracking」モードを選択
4. エリア内を移動して測定を収集
5. 推定位置の収束を観察

### サーバーの起動
```bash
cd server
pip install -r requirements.txt
python server.py
# ブラウザで http://localhost:5000/map を開く
```

## パフォーマンス特性

### EKF 収束特性
- 10測定後: ~100-500m精度
- 50測定後: ~10-100m精度
- 更新間隔: 2秒

### サーバー
- 軽量SQLiteデータベース
- リアルタイムデータ更新
- 30秒ごとの自動リフレッシュ（Web UI）

## 今後の拡張可能性

- マルチターゲットトラッキング（複数基地局対応）
- パーティクルフィルタの導入
- 地図ベースのモーションモデル
- 履歴データの再生機能
- 結果のエクスポート機能
