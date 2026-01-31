# 数学的・理論的妥当性検証レポート

## 検証日時
2026-01-15

## 検証概要
本レポートは、CellFinderプロジェクトのドキュメントと実装コードについて、数学的・理論的妥当性を検証した結果をまとめたものです。

---

## 1. 検証結果サマリー

### 総合評価: ✅ 合格

| 項目 | 評価 | 備考 |
|------|------|------|
| EKF数学的正確性 | ✅ 正確 | |
| ヤコビ行列の導出 | ✅ 正確 | 符号も正しく実装 |
| カルマンゲイン計算 | ✅ 正確 | |
| 状態・共分散更新 | ✅ 正確 | |
| UTM座標変換 | ✅ 正確 | WGS84準拠 |
| 円交差法 | ✅ 正確 | |
| ドキュメント整合性 | ⚠️ 要更新 | EKF数学的検証結果.mdの内容が現状と矛盾 |

---

## 2. EKFEngine.kt の詳細検証

### 2.1. 対数距離経路損失モデル ✅

**数式**:
```
RSSI(d) = P₀ - 10η log₁₀(d)
```

**実装（行104-105）**:
```kotlin
val expected_rssi = P0 - 10.0 * eta * log10(d_safe)
```

**評価**: 標準的な経路損失モデルに完全に準拠しており、数学的に正確です。

### 2.2. 状態遷移モデル ✅

**数式**:
```
x_{k+1|k} = x_{k|k}        （基地局は静止）
P_{k+1|k} = P_{k|k} + Q    （プロセスノイズを加算）
```

**実装（行81-82）**:
```kotlin
val x_pred = x.copy()
val P_pred = P.plus(Q)
```

**評価**: 静止目標の追跡に適切なモデルが正しく実装されています。

### 2.3. ヤコビ行列 ✅

**数学的導出**:

観測関数 h(x) = P₀ - 10η log₁₀(d) に対して:

```
∂h/∂x_fbs = -10η(x_fbs - x_user) / (d²·ln(10))
∂h/∂y_fbs = -10η(y_fbs - y_user) / (d²·ln(10))
∂h/∂P₀ = 1
∂h/∂η = -10 log₁₀(d)
```

**実装（行121-129）**:
```kotlin
val d2 = d_safe * d_safe
val ln10 = ln(10.0)
val common_term = -(10.0 * eta) / (ln10 * d2)  // 負の符号が正しい

val H = SimpleMatrix(1, 4)
H.set(0, 0, common_term * dx)        // ∂h/∂x_fbs
H.set(0, 1, common_term * dy)        // ∂h/∂y_fbs
H.set(0, 2, 1.0)                     // ∂h/∂P0
H.set(0, 3, -10.0 * log10(d_safe))  // ∂h/∂eta
```

**評価**: ヤコビ行列の各要素が数学的導出と完全に一致しています。特に、位置に関する偏微分の**負の符号**が正しく実装されていることを確認しました。

### 2.4. カルマンゲイン ✅

**数式**:
```
S = H · P_{k|k-1} · Hᵀ + R
K = P_{k|k-1} · Hᵀ · S⁻¹
```

**実装（行137-141）**:
```kotlin
val H_transpose = H.transpose()
val S_matrix = H.mult(P_pred).mult(H_transpose)
val S = S_matrix.get(0, 0) + R
val K = P_pred.mult(H_transpose).scale(1.0 / S)
```

**評価**: スカラー観測の場合のカルマンゲイン計算が正確に実装されています。

### 2.5. 状態・共分散更新 ✅

**数式**:
```
x_{k|k} = x_{k|k-1} + K · (z_k - h(x_{k|k-1}))
P_{k|k} = (I - K · H) · P_{k|k-1}
```

**実装（行143-155）**:
```kotlin
// 状態更新
val K_times_innovation = K.scale(innovation)
x = x_pred.plus(K_times_innovation)

// 共分散更新
val I = SimpleMatrix.identity(4)
val K_times_H = K.mult(H)
P = (I.minus(K_times_H)).mult(P_pred)

// 対称化（数値安定性のため）
P = P.plus(P.transpose()).scale(0.5)
```

**評価**: 標準的なEKF更新式が正確に実装されています。対称化処理により数値的安定性も確保されています。

---

## 3. UTM座標変換（UtmCoord.kt）の検証 ✅

### 3.1. WGS84楕円体パラメータ

| パラメータ | 実装値 | 標準値 | 評価 |
|-----------|--------|--------|------|
| 赤道半径 a | 6378137.0 m | 6378137.0 m | ✅ |
| 第一離心率² e² | 0.00669438 | 0.00669437999... | ✅ |
| 縮尺係数 k₀ | 0.9996 | 0.9996 | ✅ |
| 偽東距 E₀ | 500000.0 m | 500000.0 m | ✅ |

### 3.2. UTM投影計算

緯度経度からUTM座標への変換、およびその逆変換が標準的なUTM投影式に従って正確に実装されています。

---

## 4. BaseStationEstimator.kt（円交差法）の検証 ✅

### 4.1. RSSI-距離変換

**数式**:
```
d = d_ref × 10^((RSSI_ref - RSSI) / (10n))
```

**実装（行20-28）**:
```kotlin
val n = maxOf(pathLossExponent, 0.1)
val d = refDistM * (10.0).pow((refRssiDbm - rssiDbm) / (10.0 * n))
return maxOf(1.0, minOf(d, 50_000.0))
```

**評価**: 対数距離経路損失モデルの逆変換として数学的に正確です。

### 4.2. 円の交点計算

二円の交点を求めるアルゴリズム（行57-99）は、標準的な幾何学的手法に基づいており、正確に実装されています。

### 4.3. 密度ベースのクラスタリング

交点の密度に基づいて最適な位置を推定する手法（行186-271）は、Mean-Shift法に類似した理論的に妥当なアプローチです。

---

## 5. ドキュメント整合性の検証

### 5.1. MATHEMATICAL_DERIVATIONS.md ✅

- ヤコビ行列の導出が正確
- 負の符号が正しく記載されている（行120-127）
- 実装との整合性あり

### 5.2. EKF詳細説明.md ✅

- 日本語での詳細な説明が正確
- ヤコビ行列の符号が正しく記載されている（行254-258）
- 実装例も正確（行310-314）

### 5.3. EKF_TRACKING_README.md ⚠️ 要確認

行99以降のヤコビ行列の記載:
```
H = [10η/(ln(10)·d²)·(x_fbs - user_x),
     10η/(ln(10)·d²)·(y_fbs - user_y),
     ...
```

**問題点**: 負の符号が記載されていません。

**正しい記載**:
```
H = [-10η/(ln(10)·d²)·(x_fbs - user_x),
     -10η/(ln(10)·d²)·(y_fbs - user_y),
     ...
```

### 5.4. EKF数学的検証結果.md ⚠️ 要更新

このドキュメントは過去のエラーを指摘していますが、**2026-01-15 時点では実装側は既に修正済み**であるため、ドキュメントの内容が現状と矛盾しています。

**具体的な問題**:
- 行89-111で「誤ったコード」として示されている内容は、2026-01-15 時点の最新版 EKFEngine.kt には存在せず、本ドキュメントは過去の実装状態を前提としていると考えられる
- 「早急な修正が必要」と記載されているが、その指摘対象となっていたコードは既に修正済みであり、現状に対してはドキュメントが古い情報になっている

**推奨対応**:
1. 検証結果ドキュメントを更新し、「本ドキュメントが参照している実装は過去のバージョンであり、当該問題は 2026-01-15 時点で修正済み」であることを明記する
2. または、ドキュメントを履歴として残す場合は、冒頭に「本ドキュメントで指摘している問題は既に解決済みであり、記載内容は当時の実装に基づく歴史的情報である」と追記する

---

## 6. 改善提案

### 6.1. ドキュメントの整合性確保（優先度: 高）

**EKF_TRACKING_README.md**の行99-103を修正し、負の符号を追加することを推奨します。

### 6.2. EKF数学的検証結果.mdの更新（優先度: 高）

修正が完了したことを反映するよう、ドキュメントを更新することを推奨します。

### 6.3. UTMゾーンの動的追跡（優先度: 中）

EKFEngine.kt（行173-178）でUTMゾーンがハードコードされています。初期化時のUTMゾーンを保存し、動的に使用することを推奨します。

### 6.4. Joseph形式の共分散更新（優先度: 低）

数値的安定性をさらに向上させるため、Joseph形式の使用を検討できます：

```kotlin
// Joseph形式（より数値的に安定）
val I_minus_KH = I.minus(K_times_H)
P = I_minus_KH.mult(P_pred).mult(I_minus_KH.transpose())
    .plus(K.mult(K.transpose()).scale(R))
```

ただし、現在の実装も対称化処理により実用上は問題ありません。

### 6.5. イノベーションゲーティング（優先度: 低）

異常値（外れ値）に対するロバスト性を向上させるため、イノベーションゲーティングの追加を検討できます：

```kotlin
val mahalanobisDistance = innovation * innovation / S
if (mahalanobisDistance > 9.0) {  // 3σ閾値
    Log.w(TAG, "Innovation too large, skipping update")
    return
}
```

---

## 7. 結論

本プロジェクトの**EKF実装は数学的に正確**であり、理論的にも妥当な設計となっています。

主要な検証結果:
1. ✅ EKFEngine.ktの実装は正確（ヤコビ行列の符号も正しい）
2. ✅ UTM座標変換はWGS84準拠で正確
3. ✅ 円交差法（BaseStationEstimator.kt）は理論的に妥当
4. ✅ server.pyの実装はAndroid版と整合性がある
5. ⚠️ 一部ドキュメント（EKF_TRACKING_README.md、EKF数学的検証結果.md）の更新が必要

**総合評価**: 実用レベルの品質であり、即座に使用可能な状態です。

---

## 参考文献

1. Kalman, R. E. (1960). "A New Approach to Linear Filtering and Prediction Problems"
2. Welch, G., & Bishop, G. (2006). "An Introduction to the Kalman Filter"
3. Bar-Shalom, Y., et al. (2001). "Estimation with Applications to Tracking and Navigation"
4. Rappaport, T. S. (2002). "Wireless Communications: Principles and Practice"
5. Simon, D. (2006). "Optimal State Estimation"

---

**検証者**: GitHub Copilot
**検証日**: 2026-01-15
