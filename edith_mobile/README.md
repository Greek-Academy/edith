# E.D.I.T.H Mobile

E.D.I.T.H のモバイルWebクライアント。**スマホの背面カメラをグラスのカメラとみなし**、
ブラウザだけで完結する形で顔認識・HUD表示・音声/テキスト指示を行う。
[`brilliant.xyz Halo`](https://brilliant.xyz/) は詐欺商品で実機入手が不可能になったため、
[`../edith/native/edith_glass`](../edith/native/edith_glass)（Flutter, Halo向けプレビュー実装）
に代わる新しいクライアントとして開発している。バックエンド（[`../edith/backend`](../edith/backend)）
は変更なしでそのまま利用する。

## 技術スタック

- Vite + TypeScript（フレームワーク無し）
- 顔検出: [`@mediapipe/tasks-vision`](https://www.npmjs.com/package/@mediapipe/tasks-vision)（クライアント側・端末内で完結）
- 音声認識: Web Speech API（対応ブラウザ）/ `MediaRecorder` + サーバ側STTへのフォールバック（iOS Safari等）
- 接続先（Face API / Voice Agent のURL）はアプリ内の接続先設定シートから変更可能（`localStorage`永続化）

## ローカル開発

```bash
npm install
npm run dev
```

バックエンド（`../edith/backend`）の起動方法は [`../edith/README.md`](../edith/README.md) を参照。
既定では `http://localhost:8010`(Voice Agent) / `http://localhost:8000`(Face API) を向く。
スマホ実機から同一LAN上でアクセスする場合は `http://<MacのLAN IP>:5173` を開き、
アプリ内の接続先設定（🔗アイコン）からバックエンドのLAN IPを設定する。

> カメラ取得(`getUserMedia`)はsecure contextが必要だが、`localhost`はブラウザ上
> secure context扱いになるため、`npm run dev`のままでも開発機では証明書なしで動く。
> スマホ実機で`http://<LAN IP>:5173`のような非localhostオリジンにアクセスする場合は
> ブラウザによってはカメラが拒否されることがある（その場合は本番相当のcloudflared経由で確認する）。

## ビルド・デプロイ

`https://edith.under18.dev/` で公開する（Docker + cloudflared、自宅サーバ）。
手順の詳細は [`DEPLOY.md`](./DEPLOY.md) を参照。

## ディレクトリ構成

```
src/
  main.ts                 # エントリ
  app.ts                  # 画面配線・状態管理（edith_glass の home_page.dart 相当）
  config/apiConfig.ts      # 接続先URLの永続化
  api/faceApi.ts           # Face API クライアント（/faces/detect, identify, register）
  api/voiceAgentApi.ts     # Voice Agent クライアント（/v1/agent/*）
  camera/cameraSource.ts   # getUserMedia抽象（背面カメラ優先・切替・フレーム取得）
  vision/faceDetector.ts   # クライアント側顔検出（MediaPipe tasks-vision）
  tracking/trackManager.ts # 複数人追跡のstickyロジック
  hud/                     # HUD状態・描画（canvas）
  voice/speechInput.ts     # 音声入力（Web Speech API / MediaRecorderフォールバック）
```

## 既知の制約 / 今後

- iOS Safari は `SpeechRecognition` 非対応のため、録音→`stt_service`経由のフォールバックに切替わる。
- 複数人の顔を同時追跡できるが、同時識別APIコール数は現状調整していない（人数が多いと識別間隔が延びる）。
- Rokidグラス対応は [`../edith_rokid`](../edith_rokid) を参照。このリポジトリの`src`一式を
  相対importでそのまま共有しており、差分は`App`のカメラ取得オプション（`cameraFacingMode`）のみ。
  `app.ts`を変更する際は、`edith_rokid/index.html`が参照しているDOM要素IDに影響しないか確認すること。
