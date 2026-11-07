# E.D.I.T.H Rokid（設計メモ・スケルトン）

対象デバイスだった [`brilliant.xyz Halo`](https://brilliant.xyz/) は詐欺商品で実機入手が
不可能になった。代替として、**mobile（[`../edith_mobile`](../edith_mobile)）を最優先**で開発しつつ、
将来的に **Rokidのスマートグラス**でも同じ体験を動かせるようにする。

## 方針

`edith_mobile` は「カメラ取得元を差し替え可能なWebクライアント」として設計してある
（`edith_mobile/src/camera/cameraSource.ts` の `facingMode: 'environment'` の部分が差し替え点）。
Rokid実機・開発者アクセスが手に入った段階で、同じコードベースをRokid側のブラウザ経路で動かす、
または必要ならこのディレクトリに専用のエントリ/ビルド設定を追加する方針とする。
**今回のスコープでは実機が無いため、本実装はしない。**

## 調査結果（2026年時点、公式ドキュメントは薄く未確定要素が多い）

| ルート | 種別 | 概要 |
| --- | --- | --- |
| **CXR SDK** | 公式 | Android向け。phone module + glasses moduleの構成で、グラスのカメラフレーム取得等が可能。 |
| **AIUI Studio** | 公式 | 2026年にグローバル展開。ブラウザから直接開発でき、実機を持っていなくてもハードウェアシミュレーションで動作確認できる。 |
| **Lumen** | 非公式・ベータ | ブラウザアプリから`getUserMedia`で直接グラスのカメラにアクセスできる実験的プロジェクト。装着者は一度だけ許可操作が必要。 |

出典（いずれも二次情報であり、Rokid公式ドキュメントでの裏取りが必要）:
- Rokid Glasses HUD update: microLED waveguide, SDK + app store, AI model choice — armdevices.net
- Rokid launches AIUI Studio globally at IFA Berlin 2026 — adnkronos.com
- awesome-rokid（コミュニティ製ドキュメント集、CXR SDK/YodaOSのリバースエンジニアリング情報を含む）

## 未解決事項

- 公式ドキュメント（Rokid Developer Portal）での仕様確認（カメラ権限モデル、フレームレート、解像度）
- グラスのHUD表示解像度・FOVに合わせた `edith_mobile` 側のUIスケール調整
- AIUI Studio / Lumen経由で`edith_mobile`がそのまま動くかの実機検証
- CXR SDK経由のネイティブ実装が必要になった場合、`edith_mobile`とロジックを共有する方法（API契約は同じなので、カメラ取得層とHUD描画層だけ作り直す想定）

## 実機・開発者アクセスが取れたら

1. Rokid Developer Portal / AIUI Studio のアカウントを取得し、公式ドキュメントで上記未解決事項を確認する。
2. `edith_mobile` をそのままAIUI Studio（またはLumen的なブラウザ経路）で動かせるか検証する。
3. 動けばこのディレクトリに設定ファイル（ビルドターゲット等）を追加するだけで済ませる。
   動かない場合のみ、CXR SDKベースのネイティブ実装をこのディレクトリに追加する。
