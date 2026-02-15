# Copilot Instructions for CellFinder

## プロジェクト概要

CellFinderは、2G偽基地局（IMSIキャッチャー）の位置を推定するためのAndroidアプリケーションとFlaskサーバーで構成されています。

## 技術スタック

### Android アプリケーション
- **言語**: Kotlin
- **最小SDK**: API 26 (Android 8.0 Oreo)
- **アーキテクチャ**: MVVM (Model-View-ViewModel)
- **主要ライブラリ**:
  - EJML (Efficient Java Matrix Library) - 行列演算
  - Google Maps SDK - 地図表示
  - Google Play Services Location - 位置情報取得
  - AndroidX Lifecycle (ViewModel, LiveData)
  - Kotlin Coroutines - 非同期処理

### サーバー
- **言語**: Python
- **フレームワーク**: Flask
- **データベース**: SQLite

## コーディング規約

### Kotlin (Android)
- Kotlin公式のコーディング規約に従う
- Null安全性を活用する
- Coroutinesを使用した非同期処理を推奨
- LiveDataとViewModelを使用したMVVMパターン
- ログ出力には`android.util.Log`を使用

### Python (Server)
- PEP 8スタイルガイドに従う
- 関数とクラスにはdocstringを記述
- 型ヒントの使用を推奨

## ディレクトリ構造

```
cell-finder/
├── android/                    # Androidアプリケーション
│   └── app/src/main/java/com/example/cellfinder/
│       ├── EKFEngine.kt       # Extended Kalman Filter実装
│       ├── UtmCoord.kt        # UTM座標系変換
│       ├── LocationProvider.kt # 位置情報取得
│       ├── CellInfoProvider.kt # セル情報取得
│       ├── TrackingService.kt  # フォアグラウンドサービス
│       ├── MapViewModel.kt     # MVVM ViewModel
│       ├── MapFragment.kt      # 地図表示フラグメント
│       ├── MainActivity.kt     # メインアクティビティ
│       └── MapsActivity.kt     # 地図アクティビティ
└── server/                     # Flaskサーバー
    ├── server.py              # メインサーバー
    └── requirements.txt       # Python依存関係
```

## 主要コンポーネント

### EKFEngine
Extended Kalman Filterを使用して基地局の位置を推定します。
- 状態ベクトル: `[x_fbs, y_fbs, P0, eta]` (基地局位置、参照電力、パス損失指数)
- 対数距離パス損失モデル: `RSSI = P0 - 10·η·log₁₀(d)`

### TrackingService
フォアグラウンドサービスとして動作し、2秒ごとにEKFを更新します。

### UTM座標系
すべての計算はUTM (Universal Transverse Mercator) 座標系で行われます。

## コード生成のガイドライン

### Android開発時
1. 新しいActivityやFragmentを作成する際は、既存のMVVMパターンに従う
2. 位置情報や電話状態へのアクセスには適切なパーミッションチェックを実装
3. 行列演算にはEJMLライブラリを使用
4. 地図表示にはGoogle Maps SDKを使用

### サーバー開発時
1. 新しいエンドポイントは既存のパターン（`/log`, `/map_data`など）に従う
2. データベースアクセスには`get_db()`関数を使用
3. JSONレスポンスには`jsonify()`を使用

## テスト

- Androidアプリ: Android Studioでビルド・実行
- サーバー: `python server.py`で起動、`http://localhost:5000/map`で動作確認

## 注意事項

- 2G/GSMセル情報へのアクセスには`READ_PHONE_STATE`パーミッションが必要
- 位置情報の取得には`ACCESS_FINE_LOCATION`と`ACCESS_COARSE_LOCATION`パーミッションが必要
- 実機テストでは適切なパーミッション付与が必要
