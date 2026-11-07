import path from "node:path";
import { defineConfig } from "vite";

export default defineConfig({
  server: {
    host: true,
    fs: {
      // edith_mobile/src のコアロジック（api/camera/vision/tracking/hud/voice/config/app.ts）を
      // 直接import参照するため、兄弟ディレクトリ(リポジトリ直下)へのアクセスを許可する。
      allow: [path.resolve(__dirname, "..")],
    },
  },
  preview: {
    host: true,
  },
});
