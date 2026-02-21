# CellFinder

CellFinder は、Android スマートフォンで周辺の携帯基地局（セルタワー）の電波情報を収集し、基地局の位置を推定・可視化するシステムです。

## 概要

- **Android アプリ (Kotlin)**: バックグラウンドで GSM / WCDMA / LTE / 5G NR の基地局情報（セル ID・RSSI）と GPS 位置情報を定期収集し、サーバーへ送信します。拡張カルマンフィルタ (EKF) による高精度な位置推定エンジンも搭載しています。
- **Flask サーバー (Python)**: 受信した観測データを SQLite に保存し、RSSI→距離変換と円交点アルゴリズムで基地局位置を推定します。Leaflet.js ベースの Web 地図でヒートマップやピン表示を提供します。

## 主な機能

| 機能 | 説明 |
|------|------|
| マルチテクノロジー対応 | GSM / WCDMA / LTE / 5G NR の基地局を検出 |
| 基地局位置推定 | RSSI ベースの対数距離モデルと円交点クラスタリングで基地局位置を三角測量 |
| EKF エンジン | 拡張カルマンフィルタによる高精度な位置・パスロス推定 |
| Web 地図可視化 | ヒートマップ / ピン表示モードを切り替え可能 |
| 2 層データベース | リアルタイム DB（直近 3 時間）とアーカイブ DB（長期保存）の自動ローテーション |

## ローカル開発

```bash
cd server
pip install -r requirements.txt
python server.py
# ブラウザで http://localhost:5000/map を開く
```

## Azure App Service デプロイ手順

### 1. リソース作成

```bash
az group create --name cell-finder-rg --location japaneast

az appservice plan create \
  --name cell-finder-plan \
  --resource-group cell-finder-rg \
  --sku B1 \
  --is-linux

az webapp create \
  --name <YOUR_APP_NAME> \
  --resource-group cell-finder-rg \
  --plan cell-finder-plan \
  --runtime "PYTHON:3.11"
```

### 2. スタートアップコマンド設定

```bash
az webapp config set \
  --name <YOUR_APP_NAME> \
  --resource-group cell-finder-rg \
  --startup-file "startup.sh"
```

### 3. デプロイ

```bash
cd server
az webapp up \
  --name <YOUR_APP_NAME> \
  --resource-group cell-finder-rg \
  --runtime "PYTHON:3.11"
```

### 4. Android アプリの SERVER_URL 変更

`android/app/src/main/java/…/CellFinderService.kt` の `SERVER_URL` を
`https://<YOUR_APP_NAME>.azurewebsites.net` に変更してビルドし直す。

### 5. 環境変数（任意）

| 変数名              | 説明                                   | デフォルト |
|---------------------|----------------------------------------|-----------|
| `RETENTION_HOURS`   | リアルタイム層の保持時間（時間）         | `3`       |
| `ARCHIVE_INTERVAL`  | アーカイブ実行間隔（秒）                | `1800`    |
| `REALTIME_DB`       | リアルタイム DB パス                    | `cells.db`|
| `ARCHIVE_DB`        | アーカイブ DB パス                      | `archive.db`|

```bash
az webapp config appsettings set \
  --name <YOUR_APP_NAME> \
  --resource-group cell-finder-rg \
  --settings RETENTION_HOURS=6 ARCHIVE_INTERVAL=3600
```

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.
