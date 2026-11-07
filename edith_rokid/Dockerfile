# edith_rokid は edith_mobile/src のコアロジックを相対importで共有しているため、
# ビルドコンテキストは「このファイルがあるディレクトリ」ではなく、edith_mobile/edith_rokidの
# 親ディレクトリ(`..`)にする必要がある。docker-compose.yml が context: .. を指定しているので、
# `docker compose build` で実行する限り意識しなくてよい（直接 `docker build` する場合は
# リポジトリ直下で `docker build -f edith_rokid/Dockerfile .` のように実行すること）。
FROM node:20-slim AS build
WORKDIR /app

COPY edith_mobile/package.json edith_mobile/package-lock.json* ./edith_mobile/
RUN cd edith_mobile && npm install
COPY edith_mobile ./edith_mobile

COPY edith_rokid/package.json edith_rokid/package-lock.json* ./edith_rokid/
RUN cd edith_rokid && npm install
COPY edith_rokid ./edith_rokid

# 注意: VITE_*はViteのビルド時に埋め込まれる値（実行時に変えたい場合はアプリ内の接続先設定シートを使う）。
ARG VITE_EDITH_AGENT_URL
ARG VITE_EDITH_FACE_URL
ENV VITE_EDITH_AGENT_URL=$VITE_EDITH_AGENT_URL
ENV VITE_EDITH_FACE_URL=$VITE_EDITH_FACE_URL

WORKDIR /app/edith_rokid
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/edith_rokid/dist /usr/share/nginx/html
COPY edith_rokid/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
