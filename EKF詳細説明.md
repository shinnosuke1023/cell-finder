# 拡張カルマンフィルタ (EKF) による2G偽基地局位置推定の詳細説明

## 目次
1. [概要](#概要)
2. [数学的基礎](#数学的基礎)
3. [状態空間モデル](#状態空間モデル)
4. [EKFアルゴリズム](#ekfアルゴリズム)
5. [ヤコビ行列の導出](#ヤコビ行列の導出)
6. [実装の詳細](#実装の詳細)
7. [パラメータ設定](#パラメータ設定)
8. [収束特性](#収束特性)

---

## 概要

本システムは、**拡張カルマンフィルタ (Extended Kalman Filter, EKF)** を用いて、2G偽基地局（IMSIキャッチャー）の位置をリアルタイムで推定します。

### 特徴
- **自己較正機能**: 基地局の位置だけでなく、電波伝搬パラメータも同時に推定
- **UTM座標系**: メートル単位の直交座標系で正確な距離計算を実現
- **リアルタイム更新**: 2秒ごとに状態を更新
- **スタンドアロン動作**: 外部サーバー不要で端末内で完結

### 推定対象
EKFは以下の4次元状態ベクトルを推定します：

```
x = [x_fbs]    基地局のx座標（UTM、メートル）
    [y_fbs]    基地局のy座標（UTM、メートル）
    [P₀   ]    参照受信電力（1メートル地点、dBm）
    [η    ]    経路損失指数（無次元）
```

---

## 数学的基礎

### 対数距離経路損失モデル

受信信号強度（RSSI）は、送信機からの距離に対して対数的に減衰します：

```
RSSI(d) = P₀ - 10η log₁₀(d)
```

**パラメータの意味：**
- **P₀**: 1メートル地点での参照受信電力（dBm）
- **η** (エータ): 経路損失指数
  - 自由空間: η = 2（逆2乗の法則）
  - 市街地: η = 2.7 - 3.5
  - 屋内: η = 3 - 5
- **d**: 送信機から受信機までの距離（メートル）

### 物理的解釈

この式は電磁波の伝搬特性を表しています：
1. 電波は距離とともに減衰する
2. 減衰の度合いは環境（建物、障害物など）に依存
3. dBmスケールで表すと対数関数になる

---

## 状態空間モデル

### 状態遷移モデル（予測）

基地局は静止していると仮定するため、状態遷移は恒等写像です：

```
x_{k+1|k} = x_{k|k}
```

ただし、小さな変動を考慮してプロセスノイズを加えます：

```
P_{k+1|k} = P_{k|k} + Q
```

ここで、**Q** はプロセスノイズ共分散行列です。

### 観測モデル

ユーザー位置 (x_user, y_user) でのRSSI測定：

```
z_k = h(x_k) + v_k

ここで：
h(x) = P₀ - 10η log₁₀(d(x))
d(x) = √[(x_fbs - x_user)² + (y_fbs - y_user)²]
v_k ~ N(0, R)  測定ノイズ（正規分布）
```

**観測関数 h(x) の特徴：**
- 非線形関数（対数を含む）
- 状態ベクトル x のすべての要素に依存
- 距離 d が小さいほど感度が高い

---

## EKFアルゴリズム

拡張カルマンフィルタは、非線形システムに対してカルマンフィルタを適用するアルゴリズムです。観測関数を1次のテイラー展開で線形近似します。

### 初期化

```
x₀ = [user_x  ]    ユーザーの初期位置から推定
     [user_y  ]
     [-40.0  ]    典型的な参照電力
     [3.0    ]    都市環境の典型値

P₀ = 1000 × I₄     大きな初期不確かさ（対角行列）
Q = 0.00001 × I₄    小さなプロセスノイズ
R = 9.0            測定ノイズ分散（標準偏差3dBに相当）
```

### 予測ステップ

基地局は静止しているため、予測状態は前回の推定値と同じ：

```
x̂_{k|k-1} = x̂_{k-1|k-1}

P_{k|k-1} = P_{k-1|k-1} + Q
```

### 更新ステップ

#### 1. 距離の計算

```
d = √[(x_fbs - x_user)² + (y_fbs - y_user)²]
```

#### 2. 期待RSSI値の計算

```
h(x̂_{k|k-1}) = P₀ - 10η log₁₀(d)
```

#### 3. ヤコビ行列の計算

観測関数を線形近似するために、ヤコビ行列 **H** を計算します：

```
H = ∇h(x) = [∂h/∂x_fbs, ∂h/∂y_fbs, ∂h/∂P₀, ∂h/∂η]
```

詳細は次のセクションで説明します。

#### 4. イノベーション共分散の計算

```
S = H P_{k|k-1} Hᵀ + R
```

**S** は1×1のスカラー値（観測が1次元のため）

#### 5. カルマンゲインの計算

```
K = P_{k|k-1} Hᵀ S⁻¹
```

**K** は4×1のベクトル（各状態変数に対する更新率）

#### 6. 状態の更新

測定値と予測値の差（イノベーション）を用いて状態を更新：

```
ν = z_k - h(x̂_{k|k-1})    イノベーション
x̂_{k|k} = x̂_{k|k-1} + K ν
```

#### 7. 共分散の更新

```
P_{k|k} = (I - K H) P_{k|k-1}
```

数値安定性のため、対称化を行います：

```
P_{k|k} = (P_{k|k} + P_{k|k}ᵀ) / 2
```

---

## ヤコビ行列の導出

ヤコビ行列 **H** は、観測関数 h(x) の勾配です。各偏微分を求めます。

### 観測関数

```
h(x) = P₀ - 10η log₁₀(d)
d = √[(x_fbs - x_user)² + (y_fbs - y_user)²]
```

### (1) ∂h/∂x_fbs の導出

連鎖律を適用：

```
∂h/∂x_fbs = -10η · ∂log₁₀(d)/∂x_fbs
          = -10η · (1/(d·ln(10))) · ∂d/∂x_fbs
```

距離 d の偏微分：

```
∂d/∂x_fbs = ∂/∂x_fbs √[(x_fbs - x_user)² + (y_fbs - y_user)²]
          = (x_fbs - x_user) / d
```

したがって：

```
∂h/∂x_fbs = -10η · (1/(d·ln(10))) · (x_fbs - x_user)/d
          = -10η(x_fbs - x_user) / (d²·ln(10))
```

**注意**: 負の符号を保持することが重要です。

### (2) ∂h/∂y_fbs の導出

対称性により：

```
∂h/∂y_fbs = -10η(y_fbs - y_user) / (d²·ln(10))
```

### (3) ∂h/∂P₀ の導出

自明：

```
∂h/∂P₀ = 1
```

### (4) ∂h/∂η の導出

```
∂h/∂η = ∂/∂η [P₀ - 10η log₁₀(d)]
      = -10 log₁₀(d)
```

### 完全なヤコビ行列

```
H = [-10η/(ln(10)·d²)·(x_fbs - x_user),
     -10η/(ln(10)·d²)·(y_fbs - y_user),
     1,
     -10·log₁₀(d)]
```

この行列は1×4の行ベクトルです。

**重要**: 最初の2つの要素は負の符号を持ちます。これは数学的に正しい導出結果です。

---

## 実装の詳細

### EKFEngine.kt の主要メソッド

#### initialize(initialUtmLocation)

初期状態を設定：

```kotlin
x.set(0, 0, initialUtmLocation.x)  // x_fbs
x.set(1, 0, initialUtmLocation.y)  // y_fbs
x.set(2, 0, -40.0)                  // P0
x.set(3, 0, 3.0)                    // eta

P = SimpleMatrix.identity(4).scale(1000.0)
Q = SimpleMatrix.identity(4).scale(0.00001)
R = 9.0
```

#### step(userUtmLocation, measuredRssi)

EKFの1ステップを実行：

1. **予測**
   ```kotlin
   val x_pred = x.copy()
   val P_pred = P.plus(Q)
   ```

2. **距離計算**
   ```kotlin
   val dx = fbs_x - user_x
   val dy = fbs_y - user_y
   val d = sqrt(dx * dx + dy * dy)
   val d_safe = max(d, 1.0)  // ゼロ除算を回避
   ```

3. **期待RSSI**
   ```kotlin
   val expected_rssi = P0 - 10.0 * eta * log10(d_safe)
   ```

4. **ヤコビ行列**
   ```kotlin
   val common_term = -(10.0 * eta) / (ln(10.0) * d_safe * d_safe)
   H.set(0, 0, common_term * dx)
   H.set(0, 1, common_term * dy)
   H.set(0, 2, 1.0)
   H.set(0, 3, -10.0 * log10(d_safe))
   ```

5. **カルマンゲイン**
   ```kotlin
   val H_transpose = H.transpose()
   val S = (H.mult(P_pred).mult(H_transpose)).get(0, 0) + R
   val K = P_pred.mult(H_transpose).scale(1.0 / S)
   ```

6. **状態更新**
   ```kotlin
   val innovation = measured_rssi - expected_rssi
   x = x_pred.plus(K.scale(innovation))
   ```

7. **共分散更新**
   ```kotlin
   val I = SimpleMatrix.identity(4)
   P = (I.minus(K.mult(H))).mult(P_pred)
   P = P.plus(P.transpose()).scale(0.5)  // 対称化
   ```

### UTM座標系の使用

**UTM (Universal Transverse Mercator)** 座標系を使用する理由：

1. **メートル単位の直交座標系**
   - 緯度経度では距離計算が複雑
   - UTMでは単純なユークリッド距離で計算可能

2. **正確な距離計算**
   ```
   d = √[(x₁ - x₂)² + (y₁ - y₂)²]
   ```

3. **変換**
   - 緯度経度 → UTM: `UtmConverter.latLonToUtm()`
   - UTM → 緯度経度: `UtmConverter.utmToLatLon()`

---

## パラメータ設定

### 初期状態

| パラメータ | 初期値 | 説明 |
|-----------|--------|------|
| x_fbs, y_fbs | ユーザー位置 | 初期位置の推測値 |
| P₀ | -40.0 dBm | 典型的な1m地点での受信電力 |
| η | 3.0 | 都市環境の典型的な経路損失指数 |

### 共分散行列

| 行列 | 値 | 説明 |
|------|-----|------|
| P₀ | 1000 × I₄ | 初期不確かさが大きい |
| Q | 0.00001 × I₄ | 基地局は静止しているため小さい |
| R | 9.0 | RSSI測定ノイズ（標準偏差3dB） |

### 調整可能なパラメータ

実環境に応じて以下を調整可能：

```kotlin
// TrackingService.kt
private val UPDATE_INTERVAL_MS = 2000L  // 更新周期

// EKFEngine.kt
R = 9.0  // 測定ノイズ（環境に応じて調整）
Q = SimpleMatrix.identity(4).scale(0.00001)  // プロセスノイズ
```

---

## 収束特性

### 理論的収束

EKFの収束は以下の条件に依存します：

1. **観測可能性**: 異なる位置から複数回測定することで観測可能
2. **線形化誤差**: 状態推定値が真値に十分近いこと
3. **ノイズ**: 測定ノイズが適切にモデル化されていること

### 実用的な性能

| 測定回数 | 位置精度 | 不確かさ半径 |
|---------|---------|------------|
| 10回 | ~200m | ~300m |
| 20回 | ~100m | ~150m |
| 50回 | ~50m | ~80m |
| 100回以上 | ~10-50m | ~30-50m |

**注意**: 実際の性能は以下に依存します：
- 移動パターン（円形移動が直線移動より良い）
- RSSI安定性（±3dB程度の変動が典型的）
- 環境（都市部、郊外など）
- 信号強度（強いほど良い）

### 不確かさの可視化

誤差円の半径は共分散行列のトレースから計算：

```
radius = √(P[0,0] + P[1,1])
```

これは位置推定のRMS（二乗平均平方根）誤差を表します。

### 収束を早めるための推奨事項

1. **円形移動**: 基地局を中心に円を描くように移動
2. **距離変化**: 近距離と遠距離の両方で測定
3. **十分な測定数**: 最低でも20回以上の測定
4. **安定した受信**: 信号が安定している場所で測定

---

## 数学的補足

### フィッシャー情報行列

EKFの最適性は、フィッシャー情報行列 **I(x)** で特徴付けられます：

```
I(x) = E[∇log p(z|x) · ∇log p(z|x)ᵀ]
     = Hᵀ R⁻¹ H
```

N回の測定後の最良の共分散（クラメル・ラオ下限）：

```
P_optimal ≥ [Σᵢ₌₁ᴺ Hᵢᵀ R⁻¹ Hᵢ]⁻¹
```

これは以下を示しています：
- 測定回数が増えるほど P が小さくなる（精度向上）
- 多様な位置からの測定が重要（情報行列の条件数改善）

### ジョセフ形式

数値安定性のため、共分散更新はジョセフ形式を使用することもできます：

```
P_{k|k} = (I - K H) P_{k|k-1} (I - K H)ᵀ + K R Kᵀ
```

現在の実装では簡略化した形式を使用し、対称化で安定性を確保しています。

---

## まとめ

本システムの拡張カルマンフィルタは以下の特徴を持ちます：

### 主要な利点
1. ✅ **リアルタイム推定**: 2秒ごとに状態を更新
2. ✅ **自己較正**: 伝搬パラメータも同時推定
3. ✅ **不確かさの定量化**: 共分散行列で不確かさを表現
4. ✅ **少ない測定で動作**: 1回の測定から開始可能
5. ✅ **数値的安定性**: EJMLライブラリによる高精度計算

### 適用範囲
- 2G基地局位置推定（GSM）
- IMSI キャッチャー検出
- 電波源位置推定
- 無線測位システム

### 制限事項
- 単一基地局のみ追跡
- 基地局が静止していると仮定
- 見通し外（NLOS）環境では精度低下
- 初期収束には移動が必要

---

## 参考文献

1. Kalman, R. E. (1960). "A New Approach to Linear Filtering and Prediction Problems"
2. Welch, G., & Bishop, G. (2006). "An Introduction to the Kalman Filter"
3. Bar-Shalom, Y., et al. (2001). "Estimation with Applications to Tracking and Navigation"
4. Rappaport, T. S. (2002). "Wireless Communications: Principles and Practice"
5. Simon, D. (2006). "Optimal State Estimation"

---

## 付録: 実装例

### 使用例

```kotlin
// EKFエンジンの初期化
val ekfEngine = EKFEngine()

// ユーザーの初期位置（緯度経度）
val lat = 35.681200
val lon = 139.767100

// UTM座標に変換
val utmCoord = UtmConverter.latLonToUtm(lat, lon)

// EKFの初期化
ekfEngine.initialize(utmCoord)

// 測定ループ
while (tracking) {
    // 現在位置とRSSIを取得
    val currentLocation = locationProvider.getCurrentLocation()
    val rssi = cellInfoProvider.getTargetCell()?.rssi ?: continue
    
    // UTMに変換
    val currentUtm = UtmConverter.latLonToUtm(
        currentLocation.latitude,
        currentLocation.longitude
    )
    
    // EKFステップの実行
    ekfEngine.step(currentUtm, rssi.toDouble())
    
    // 推定結果の取得
    val estimatedPosition = ekfEngine.getEstimatedPositionUtm()
    val errorRadius = ekfEngine.getErrorRadius()
    val (p0, eta) = ekfEngine.getPathLossParameters()
    
    // 結果を表示
    println("推定位置: $estimatedPosition")
    println("誤差半径: ${errorRadius}m")
    println("P0: ${p0}dBm, η: $eta")
    
    delay(2000)  // 2秒待機
}
```

### デバッグ情報

EKFエンジンはログを出力します：

```
D/EKFEngine: Initializing EKF at UTM: x=387773.0, y=3950374.0
D/EKFEngine: EKF step: user=(387873.0, 3950474.0), RSSI=-85.0
D/EKFEngine: Distance: 141.42 m, Expected RSSI: -88.5 dBm, Innovation: 3.5 dB
D/EKFEngine: Updated state: x_fbs=387823.0, y_fbs=3950424.0, P0=-42.3, eta=2.8
D/EKFEngine: Position uncertainty: σx=156.2, σy=168.4
```

---

**本ドキュメントは2G偽基地局位置推定システムの拡張カルマンフィルタ実装について、数式と実装の両面から詳細に説明しています。**
