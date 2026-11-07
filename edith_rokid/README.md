# E.D.I.T.H Rokid

E.D.I.T.H の**Rokidスマートグラス向けWebクライアント**。
[`brilliant.xyz Halo`](https://brilliant.xyz/) は詐欺商品で実機入手が不可能になったため、
代替としてRokidのスマートグラスに対応する。バックエンド（[`../edith/backend`](../edith/backend)）
は変更なし。

## 実装方針（重要）

**独自実装ではなく、[`../edith_mobile`](../edith_mobile) のコア実装(`App`クラス一式)をそのまま
相対importで共有している。** 差分は「カメラ取得時にfacingMode制約を付けない」の1点のみ。

```ts
// edith_rokid/src/main.ts
import { App } from "../../edith_mobile/src/app";
const app = new App({}); // facingMode未指定 = 既定カメラ（グラス本体のカメラ）を使う
```

```ts
// 参考: edith_mobile/src/main.ts
const app = new App({ cameraFacingMode: "environment" }); // スマホの背面カメラ
```

これは [`../edith_mobile/README.md`](../edith_mobile/README.md) にある設計判断
「まずedith_mobileのWebクライアントをRokid側でも動かす方針で設計」をそのまま実装したもの。
`api/`・`camera/`・`vision/`・`tracking/`・`hud/`・`voice/`・`config/`・`app.ts` は一切複製せず
`edith_mobile/src` を直接参照する。複製しているのは `index.html`（同一のDOM要素IDを持つ必要がある）・
`style.css`（将来グラス側のHUD仕様に合わせて分岐させる前提）・`public/`（マニフェスト名のみ違う）・
Dockerまわりの3ファイルだけ。

> **注意**: `edith_mobile/src` 配下のファイル（特に`app.ts`が参照するDOM要素ID）を変更する際は、
> `edith_rokid/index.html` も同じIDを保っているか確認すること。

## ローカル開発

```bash
npm install
npm run dev
```

`vite.config.ts` で `server.fs.allow` に親ディレクトリを追加しているため、
`../edith_mobile/src/*` への相対importは開発サーバ上でもそのまま解決される
（`npx tsc -b` / `npm run build` でも動作確認済み）。

## デプロイ

Docker + cloudflaredでの公開手順は [`DEPLOY.md`](./DEPLOY.md) を参照。
**ビルドコンテキストが`edith_mobile`との親ディレクトリになる点に注意**
（`edith_mobile/src`を相対importで参照しているため）。

## 調査結果（Rokid公式/非公式の開発ルート、2026年時点）

公式ドキュメントは薄く未確定要素が多い。いずれも二次情報であり、Rokid公式ドキュメントでの
裏取りが必要。

| ルート | 種別 | 概要 |
| --- | --- | --- |
| **CXR SDK** | 公式 | Android向け。phone module + glasses moduleの構成で、グラスのカメラフレーム取得等が可能。 |
| **AIUI Studio** | 公式 | 2026年にグローバル展開。ブラウザから直接開発でき、実機を持っていなくてもハードウェアシミュレーションで動作確認できる。 |
| **Lumen** | 非公式・ベータ | ブラウザアプリから`getUserMedia`で直接グラスのカメラにアクセスできる実験的プロジェクト。装着者は一度だけ許可操作が必要。 |

出典:
- Rokid Glasses HUD update: microLED waveguide, SDK + app store, AI model choice — armdevices.net
- Rokid launches AIUI Studio globally at IFA Berlin 2026 — adnkronos.com
- awesome-rokid（コミュニティ製ドキュメント集、CXR SDK/YodaOSのリバースエンジニアリング情報を含む）

## 未解決事項（実機・開発者アクセスが無いと確定できない）

- このアプリをAIUI Studio / Lumen的なブラウザ経路で実際に開いた際、`getUserMedia`がグラスの
  カメラを返すか（＝`main.ts`の「facingMode未指定なら既定カメラ＝グラスのカメラ」という前提の検証）。
- グラスのHUD表示解像度・FOVに合わせた`index.html`/`style.css`のレイアウト調整
  （現時点ではスマホ版と全く同じレイアウトのまま）。
- 音声入力（Web Speech API / MediaRecorderフォールバック）やマイク権限がRokid側ブラウザで
  同じように動くか。
- 公式ドキュメント（Rokid Developer Portal）での仕様確認。
- 上記で問題が出た場合のみ、CXR SDKベースのネイティブ(Android)実装を検討する
  （その場合もAPI契約(`faceApi.ts`/`voiceAgentApi.ts`相当)は`edith_mobile`と同じにする）。

## 実機・開発者アクセスが取れたら

1. Rokid Developer Portal / AIUI Studio のアカウントを取得する。
2. このアプリ（`npm run build`の成果物、または`DEPLOY.md`のURL）をAIUI Studio / Lumen的な
   ブラウザ経路で開き、上記「未解決事項」を1つずつ潰す。
3. カメラ取得に問題があれば `src/main.ts` の`App`オプション、または
   `edith_mobile/src/camera/cameraSource.ts` 側に必要な分岐を追加する
   （既存の`CameraInitOptions`を拡張する形で、`edith_mobile`側の挙動は変えない）。
4. レイアウト調整が必要なら、まず `edith_rokid/src/style.css`（独自ファイル）から着手する。
