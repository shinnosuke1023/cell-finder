# EKF（拡張カルマンフィルタ）ベースの位置推定

## 概要

Extended Kalman Filter (EKF) を使用して、単一の2G偽基地局（IMSIキャッチャー）の位置をリアルタイムで推定します。

## 機能

- **自己キャリブレーション**: P0（参照電力）とη（パス損失指数）を自動調整
- **UTM座標系**: 精密な距離計算のためUTM座標系を使用
- **不確実性の可視化**: 共分散行列から誤差円を表示
- **ユーザー軌跡**: 測定点の移動履歴を地図上に表示

## 技術詳細

### 状態ベクトル
```
x = [x_fbs, y_fbs, P0, eta]ᵀ
```
- `x_fbs, y_fbs`: 基地局位置（UTM座標、メートル）
- `P0`: 1m距離での参照電力（dBm）
- `eta`: パス損失指数（通常2-4）

### 観測モデル
対数距離パス損失モデル:
```
RSSI = P0 - 10·η·log₁₀(d)
```

### ヤコビアン行列
```
H = [∂h/∂x_fbs, ∂h/∂y_fbs, ∂h/∂P0, ∂h/∂eta]
```

## 関連ファイル

- `android/app/src/main/java/com/example/cellfinder/EKFEngine.kt` - EKFアルゴリズム実装
- `android/app/src/main/java/com/example/cellfinder/UtmCoord.kt` - UTM座標変換
- `android/app/src/main/java/com/example/cellfinder/TrackingService.kt` - トラッキングサービス

## 使用例

```kotlin
// EKFエンジンの初期化
val ekfEngine = EKFEngine()
ekfEngine.initialize(initialUtmLocation)

// 測定値の更新
ekfEngine.step(userUtmLocation, measuredRssi)

// 推定位置の取得
val estimatedPosition = ekfEngine.getEstimatedPosition()
val errorRadius = ekfEngine.getErrorRadius()
```

## パフォーマンス特性

| 測定回数 | 精度 |
|---------|------|
| 10回 | ~100-500m |
| 50回 | ~10-100m |

更新間隔: 2秒
