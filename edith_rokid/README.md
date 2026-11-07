# E.D.I.T.H Rokid

E.D.I.T.H の**Rokidスマートグラス向けクライアント**。
[`brilliant.xyz Halo`](https://brilliant.xyz/) は詐欺商品で実機入手が不可能になったため、
代替としてRokidのスマートグラスに対応する。バックエンド（[`../edith/backend`](../edith/backend)）
は変更なし。

実機（Rokid Glasses、ディスプレイ付き2025/2026モデル）を入手できたため、**正式な
Androidアプリ（[`android/`](./android)）を実装済み**。これがメインの実装。
Web版（ブラウザ経由、下記）は実機未検証の代替手段として残してある。

## Android版（`android/`、メイン実装）

### 重要な調査結果: Rokid公式SDK(CXR SDK)は不要

公式ドキュメントが薄いため、community製の [`buildwithfenna/rokid-docs`](https://github.com/buildwithfenna/rokid-docs)
と実働サンプル [`zero2005x/RokidAIAssistant`](https://github.com/zero2005x/RokidAIAssistant) を実際に
フェッチして確認した結果:

- **Rokid Glasses本体はAndroid 12ベース（YodaOS, SDK 32）の通常のAndroid機**。
  グラスのカメラは**標準の`android.hardware.camera2` API**で直接取得できる
  （`externalCameraId ?: backCameraId ?: frontCameraId`の優先順位で「external」＝グラス本体カメラを選ぶ。
  [`GlassesCameraSource.kt`](./android/app/src/main/java/com/edith/rokid/camera/GlassesCameraSource.kt)参照）。
- Rokid公式の **CXR-S SDK**（`com.rokid.cxr:cxr-service-bridge`, maven.rokid.com）は
  **メッセージング/接続状態監視用**であり、カメラ・ディスプレイには関与しない。
- 今回のユースケース（スマホとのペアリング無し、グラス単体でバックエンドに直接HTTPで喋る）では
  **CXR SDK自体が不要**（Rokidへの申請・契約も不要）。`android/`配下はRokidの依存を一切含まない、
  **普通のAndroidアプリ**。
- インストールはRokid公式の**開発用ケーブル**（磁気充電ポートがデータポートを兼ねる。付属の
  充電専用ケーブルではADB不可）で接続し、「Hi Rokid」アプリでADBを有効化した上で`adb install`。

→ `edith_mobile`（Web版）で確立済みのロジック（API契約・顔追跡sticky・HUD状態遷移）を
そのままKotlinに移植している（コード共有ではなく、同じ仕様の再実装）。

### 技術スタック

- Kotlin, Android Gradle Plugin 8.7.3, Kotlin 2.0.21
- compileSdk 34 / targetSdk 34 / minSdk 28（実機はSDK 32なので問題なし）
- カメラ: `android.hardware.camera2`（Rokid SDK不要）
- 顔検出: `com.google.mediapipe:tasks-vision`（Android版 FaceDetector、`edith_mobile`と同じ
  `blaze_face_short_range`モデルを`assets/`に同梱）
- 通信: OkHttp マルチパートPOST。エンドポイント・フィールド名は`edith_mobile/src/api/*.ts`と同じ契約
- 音声: `AudioRecord`でWAV録音→`voice_assistant_agent`の`/v1/agent/audio`へ送信
  （YodaOSにGoogleの`SpeechRecognizer`バックエンドが載っているか未確認のため、確実な方式を採用）
- 設定: `SharedPreferences`でAPI接続先を保存

### ビルド・実機インストール

[`android/DEPLOY.md`](./android/DEPLOY.md) を参照。このセッションでは
`./gradlew assembleDebug` が成功することまで確認済み（コンパイルエラー無し）。
実機への`adb install`・実際の動作確認（グラス上でのカメラ・顔検出・識別・HUD表示）は未実施
（Rokid公式の開発用ケーブルが必要なため、ユーザー側で実施）。

### ディレクトリ構成

```
android/
  app/src/main/
    AndroidManifest.xml
    java/com/edith/rokid/
      MainActivity.kt                 # edith_mobileのapp.ts相当
      camera/GlassesCameraSource.kt   # Camera2、external>back>front優先
      vision/ClientFaceDetector.kt    # MediaPipe tasks-vision FaceDetector
      tracking/{Track,TrackManager,FaceBox}.kt
      api/{FaceApi,VoiceAgentApi}.kt  # edith_mobileと同じAPI契約
      hud/{HudView,HudState}.kt
      voice/SpeechInput.kt            # AudioRecord→WAV
      config/ApiConfig.kt             # SharedPreferences
    assets/blaze_face_short_range.tflite
    res/layout/activity_main.xml
```

### 未検証（実機が必要）

- グラス本体のカメラIDが実際に`LENS_FACING_EXTERNAL`として列挙されるか（`backCameraId`/`frontCameraId`
  にフォールバックした場合の映り方・画角）
- `AudioRecord`でのマイク録音が実機で問題なく動くか
- MediaPipe tasks-visionの顔検出がグラス本体の非力なチップで実用的なフレームレートで動くか
  （`DETECT_INTERVAL_MS=200`は暫定値。重ければ伸ばす）
- APKサイズが約60MB（MediaPipeのネイティブライブラリ+tfliteモデル込み）。グラスのストレージ容量次第では
  `minifyEnabled`/ABI分割などの最適化が必要になる可能性

## Web版（このディレクトリ直下の`index.html`・`src/`等、代替手段）

実機検証前に用意していたブラウザ経由の案。`android/`以外の、`edith_rokid/`直下にある
`index.html`・`src/`・`package.json`・`Dockerfile`等一式がこれにあたる。Android版が実機で動くなら
不要になる可能性が高いが、AIUI Studio（公式、ブラウザから開発可能なプラットフォーム）やLumen
（非公式、`getUserMedia`でグラスのカメラにアクセス）など、Rokid側にもブラウザ経路の開発手段が
あるため残してある。

`src/`配下は[`../edith_mobile`](../edith_mobile)のコアロジックを相対importでそのまま共有
（差分はカメラ取得オプションのみ）。詳細は[`DEPLOY.md`](./DEPLOY.md)（Web版のデプロイ手順）を参照。

### 調査結果（Rokid公式/非公式のブラウザ開発ルート、参考情報）

| ルート | 種別 | 概要 |
| --- | --- | --- |
| **AIUI Studio** | 公式 | 2026年にグローバル展開。ブラウザから直接開発でき、ハードウェアシミュレーションで動作確認できる。 |
| **Lumen** | 非公式・ベータ | ブラウザアプリから`getUserMedia`で直接グラスのカメラにアクセスできる実験的プロジェクト。 |

出典: Rokid Glasses HUD update (armdevices.net) / Rokid launches AIUI Studio globally at IFA Berlin 2026 (adnkronos.com)
