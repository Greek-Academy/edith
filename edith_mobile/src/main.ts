import "./style.css";
import { App } from "./app";

if ("serviceWorker" in navigator) {
  // PWAとしてホーム画面に追加できるよう、将来的にはオフラインshellをキャッシュする
  // service workerをここに追加する（現時点ではマニフェストのみでインストール可能）。
}

// スマホの背面カメラをグラスのカメラとみなす（edith_rokidではfacingMode指定なしで既定カメラを使う）。
const app = new App({ cameraFacingMode: "environment" });
void app.start();
