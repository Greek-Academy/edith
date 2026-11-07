# デプロイ手順（edith_rokid）

方式: **自宅サーバ上のDockerコンテナ + `cloudflared`（Cloudflare Tunnel）**
（[`../edith_mobile/DEPLOY.md`](../edith_mobile/DEPLOY.md)と同じ構成。公開後のURLをRokid側の
ブラウザ経路（AIUI Studio / Lumen的な仕組み）で開く、という使い方を想定）。

> 公開ホスト名は未確定。[`../edith_mobile`](../edith_mobile)が`edith.under18.dev`なので、
> ここでは例として **`edith-rokid.under18.dev`** を使う（実際のホスト名は運用時に決める）。

## edith_mobileとの違い（重要）

`edith_rokid`は`edith_mobile/src`を相対importで共有しているため、**Dockerのビルドコンテキストが
`edith_mobile`と`edith_rokid`の親ディレクトリ**になる（`edith_rokid/docker-compose.yml`の
`build.context: ..`）。単体の`edith_rokid/`ディレクトリだけをどこかへコピーしてもビルドできない。
必ず`edith_mobile`と`edith_rokid`を同じ親ディレクトリに置いた状態でデプロイすること。

## 1. イメージをビルドして起動

```bash
cd edith_rokid   # docker-compose.ymlがここにある（context: ..で親ディレクトリ全体を見る）

VITE_EDITH_AGENT_URL=https://agent.example.com \
VITE_EDITH_FACE_URL=https://face.example.com \
docker compose up -d --build
```

- ホスト側の **8081番**にマッピングされる（`edith_mobile`の8080番と衝突しないように別番号にしてある）。
- `edith_mobile`と同様、コンテナは平文HTTPで配信するだけでよい（TLS終端はCloudflare Tunnel側）。
- ビルドコンテキストが親ディレクトリ全体になるため、`/dev/.dockerignore`
  （`edith/`配下の無関係な大きなディレクトリを除外する設定）が効いていることを確認する。

## 2. `cloudflared` でingressを追加する

```yaml
ingress:
  - hostname: edith-rokid.under18.dev   # 実際に使うホスト名に置き換える
    service: http://localhost:8081
  - hostname: edith.under18.dev
    service: http://localhost:8080
  - service: http_status:404
```

## 3. 動作確認（現時点でできる範囲）

実機・Rokid開発者アクセスが無いため、以下のみ確認可能:

```bash
docker compose ps
curl -I http://localhost:8081/
# 公開URLをPCや普通のスマホブラウザで開き、edith_mobileと同じUIが表示されること
# （この時点では「普通のモバイルWebクライアント」として動くだけで、Rokid固有の検証はできない）
```

Rokid実機・AIUI Studio/Lumenのアクセスが取れたら、[`README.md`](./README.md)の
「未解決事項」を1つずつ検証する（公開URLをそのままRokid側のブラウザ経路で開いて試す）。

## 接続先（バックエンドURL）を後から変える場合

`edith_mobile`と同じく、`VITE_EDITH_AGENT_URL`/`VITE_EDITH_FACE_URL`はビルド時埋め込みのため、
コンテナ再ビルド無しで変えたい場合はアプリ内の接続先設定シートを使う。
