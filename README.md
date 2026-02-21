CellFinder project
Android app (Kotlin) + Flask server sample.
Build Android app with Android Studio; edit SERVER_URL in CellFinderService.kt to point to your server IP.
Start server: python server.py
Open http://<server_ip>:5000/map to see markers.

## Local Development

```bash
cd server
pip install -r requirements.txt
python server.py
# Open http://localhost:5000/map
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
