import "./style.css";
// edith_mobile と同じコア実装(App)をそのまま共有する（設計方針: まずはWebクライアントを
// Rokid側のブラウザ経路(AIUI Studio/Lumen的な仕組み)でも動かす。差分はカメラ取得のみ）。
import { App } from "../../edith_mobile/src/app";

// Rokidグラス側ブラウザ経路は通常カメラを1つ（グラス本体のカメラ）しか公開しないため、
// facingMode制約を付けずに既定カメラを使う（edith_mobileは'environment'=背面カメラを指定する）。
const app = new App({});
void app.start();
