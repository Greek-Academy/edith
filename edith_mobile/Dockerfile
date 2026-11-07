# マルチステージビルド: Viteでビルド → nginxで静的配信（face_apiのDockerfileと同じ思想）
# 注意: VITE_*はViteのビルド時に埋め込まれる値なので、コンテナ起動時の環境変数ではなく
# ビルド引数として渡す必要がある（実行時に変えたい場合はアプリ内の接続先設定シートを使う）。
FROM node:20-slim AS build
ARG VITE_EDITH_AGENT_URL
ARG VITE_EDITH_FACE_URL
ENV VITE_EDITH_AGENT_URL=$VITE_EDITH_AGENT_URL
ENV VITE_EDITH_FACE_URL=$VITE_EDITH_FACE_URL
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
