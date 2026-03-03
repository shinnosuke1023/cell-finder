# 円交差法による基地局位置推定

## 概要

RSSI値から推定した距離円の交点を使用して、基地局の位置を推定するサーバーサイドアルゴリズムです。

## 機能

- **対数距離パス損失モデル**: RSSIから距離を推定
- **円交差アルゴリズム**: 複数の距離円の交点を計算
- **重み付き投票**: 交差角に基づく重み付けで精度向上
- **リアルタイムAPI**: REST APIで推定結果を提供

## 技術詳細

### 距離推定モデル
```python
d = ref_dist * 10^((ref_rssi - rssi) / (10 * n))
```
- `ref_dist`: 参照距離（デフォルト: 1m）
- `ref_rssi`: 参照RSSIレベル（デフォルト: -40dBm）
- `n`: パス損失指数（デフォルト: 2.0）

### アルゴリズムフロー
1. 各観測点でRSSIから距離を推定
2. 観測点を中心とした距離円を生成
3. すべての円ペアの交点を計算
4. 交点のクラスタリングと投票
5. 最も密度の高い領域の加重平均を出力

## APIエンドポイント

### `/cell_map`
セルIDごとの推定基地局位置を返します。

**パラメータ**:
| パラメータ | デフォルト | 説明 |
|-----------|-----------|------|
| `ple` | 2.0 | パス損失指数 |
| `window_sec` | 全期間 | 対象期間（秒） |
| `ref_rssi` | -40 | 参照RSSI（dBm） |
| `ref_dist` | 1.0 | 参照距離（m） |
| `bandwidth_m` | 150 | クラスタリング半径（m） |
| `method` | accum | 推定方法（accum/centroid） |
| `debug` | 0 | デバッグ情報の出力 |

**レスポンス例**:
```json
[
  {
    "cell_id": "12345",
    "type": "GSM",
    "lat": 35.6812,
    "lon": 139.7671,
    "count": 25
  }
]
```

## 関連ファイル

- `server/server.py` - サーバー実装（`/cell_map`エンドポイント）
- `android/app/src/main/java/com/example/cellfinder/BaseStationEstimator.kt` - Android側実装

## 使用例

```bash
# 基地局位置の取得
curl "http://localhost:5000/cell_map?ple=2.5&debug=1"

# 特定の時間窓内のデータのみ使用
curl "http://localhost:5000/cell_map?window_sec=3600"
```
