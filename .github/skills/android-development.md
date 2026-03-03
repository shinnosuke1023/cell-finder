# Android開発 (Kotlin)

## 概要

CellFinderのAndroidアプリケーション開発に関するスキルとガイドラインです。

## 技術スタック

- **言語**: Kotlin
- **最小SDK**: API 26 (Android 8.0 Oreo)
- **アーキテクチャ**: MVVM (Model-View-ViewModel)

## 主要ライブラリ

| ライブラリ | 用途 |
|-----------|------|
| EJML | 行列演算（EKF実装） |
| Google Maps SDK | 地図表示 |
| Google Play Services Location | 位置情報取得 |
| AndroidX Lifecycle | ViewModel, LiveData |
| Kotlin Coroutines | 非同期処理 |

## アーキテクチャパターン

### MVVM構成
```
View (Activity/Fragment)
    ↓ observes
ViewModel (LiveData)
    ↓ uses
Model/Repository (Service, Provider)
```

### 主要コンポーネント

| ファイル | 役割 |
|---------|------|
| `MainActivity.kt` | メインUI、トラッキング開始/停止 |
| `MapsActivity.kt` | 地図表示画面 |
| `MapFragment.kt` | 地図フラグメント |
| `MapViewModel.kt` | 地図状態管理 |
| `TrackingService.kt` | フォアグラウンドサービス |
| `LocationProvider.kt` | 位置情報ラッパー |
| `CellInfoProvider.kt` | セル情報ラッパー |
| `EKFEngine.kt` | EKFアルゴリズム |
| `UtmCoord.kt` | UTM座標変換 |

## 必要なパーミッション

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

## コーディング規約

### Kotlin スタイル
- Kotlin公式コーディング規約に従う
- Null安全性を活用（`?.`, `?:`, `!!`の適切な使用）
- データクラスの活用
- 拡張関数の適切な使用

### 非同期処理
```kotlin
// Coroutinesを使用
viewModelScope.launch {
    val result = withContext(Dispatchers.IO) {
        // IO処理
    }
    // UIスレッドで処理
}
```

### ログ出力
```kotlin
import android.util.Log

Log.d(TAG, "Debug message")
Log.i(TAG, "Info message")
Log.e(TAG, "Error message", exception)
```

## コード例

### ViewModelの実装
```kotlin
class MapViewModel : ViewModel() {
    private val _state = MutableLiveData<TrackingState>()
    val state: LiveData<TrackingState> = _state

    fun updateState(newState: TrackingState) {
        _state.value = newState
    }
}
```

### Foreground Serviceの実装
```kotlin
class TrackingService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }
}
```

## ビルド・実行

```bash
# Android Studioで開く
cd android
# または
./gradlew assembleDebug
```

## テスト

- 実機テストが推奨（エミュレータでは電話状態APIが制限される）
- パーミッションの適切な付与が必要
