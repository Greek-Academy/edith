# デプロイ手順（edith_rokid/android）

Rokid Glasses実機への**正式なAndroidアプリ**としてのインストール手順。
Rokid公式SDK(CXR SDK)は使っていないため、特別な申請やアカウントは不要
（詳細は[`../README.md`](../README.md)の「重要な調査結果」を参照）。

## 前提

- Android Studio（Ladybug 2024.2 以降を推奨）、または `gradlew` が動く環境（JDK 17〜21、Android SDK）
- Rokid Glasses実機
- **Rokid公式の開発用ケーブル**（グラス付属の充電専用ケーブルはデータ通信非対応。磁気充電ポートが
  データポートを兼ねる専用ケーブルが別途必要。入手方法はRokidのサポート/開発者窓口に問い合わせる）

## 1. グラス側でADBを有効化する

1. 「Hi Rokid」アプリ（スマホ側コンパニオンアプリ）を開く。
2. 開発者向け設定からADBデバッグを有効化する
   （正確なメニュー名・手順は機種・ファームウェアのバージョンにより異なる可能性がある。
   Rokid公式サポートに確認すること）。

## 2. 開発用ケーブルでPCに接続する

グラスの磁気充電ポートに開発用ケーブルを挿し、PCのUSBポートに接続する。
標準の充電ケーブルでは`adb devices`に出てこないので注意。

```bash
adb devices
# グラスのシリアル番号が一覧に出ればOK
```

## 3. ビルド

```bash
cd edith_rokid/android
./gradlew assembleDebug
```

生成物: `app/build/outputs/apk/debug/app-debug.apk`
（このセッションで`assembleDebug`の成功は確認済み。実機インストール・実機上での動作は未確認）。

## 4. インストール

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
# 更新時（アンインストールせず上書き）
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 5. 起動・初回設定

1. グラス上でアプリ（アプリ名: E.D.I.T.H）を起動する。
2. カメラ・マイクの権限ダイアログが出たら許可する。
3. 右上の⚙︎アイコンから接続先（Voice Agent URL / Face API URL）を設定する。
   既定値は`http://localhost:8010` / `http://localhost:8000`なので、
   バックエンドをグラスと同じネットワーク上の別ホストで動かしている場合は
   そのLAN IPまたは公開URLに変更する（バックエンドのCORS設定は変更不要、
   [`../../edith_mobile/README.md`](../../edith_mobile/README.md)参照）。

## ログの確認（`adb logcat`）

```bash
adb logcat | grep -i edith
```

## 既知の制約（実機検証前の時点）

- カメラIDの選定ロジック（`LENS_FACING_EXTERNAL`優先）が実機でグラス本体のカメラを正しく
  選べているかは未検証。もし違うカメラが選ばれる場合は
  [`app/src/main/java/com/edith/rokid/camera/GlassesCameraSource.kt`](./app/src/main/java/com/edith/rokid/camera/GlassesCameraSource.kt)
  の`selectCameraId()`を調整する。
- 顔検出の間隔（`MainActivity.kt`の`DETECT_INTERVAL_MS`）はグラスのチップ性能次第で調整が必要になる可能性がある。
- APKサイズが約60MB（MediaPipeのネイティブライブラリ+tfliteモデル込み）。グラスのストレージ容量を圧迫する場合は
  `minifyEnabled true` + ABI分割の検討が必要。
