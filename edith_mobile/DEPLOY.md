# デプロイ手順（edith_mobile）

公開URL: **`https://edith.under18.dev/`**
方式: **自宅サーバ上のDockerコンテナ + `cloudflared`（Cloudflare Tunnel）**

```
[スマホのブラウザ]
      │ HTTPS (edith.under18.dev)
      ▼
[Cloudflare]  ← TLS終端はここ
      │ Tunnel（cloudflaredが自宅サーバから張る）
      ▼
[自宅サーバ] cloudflared コンテナ ──ingress──▶ edith_mobile コンテナ (nginx, 80番)
                                              │
                                              ▼
                                   face_api(:8000) / voice_assistant_agent(:8010)
                                   （同じ自宅サーバ、または別途公開済みのURL）
```

## 前提

- Docker / Docker Compose が使える自宅サーバ
- Cloudflareアカウントに `under18.dev` ゾーンが登録済み
- `cloudflared` のトンネル（未作成なら事前に1つ作成しておく）

## 1. イメージをビルドして起動

バックエンド（`face_api` / `voice_assistant_agent`）の公開先URLを**ビルド時**に指定する
（`VITE_EDITH_*`はViteのビルド時埋め込み値のため。詳細は後述）。

```bash
cd edith_mobile

# バックエンドを同じ自宅サーバ上でDocker起動している場合、コンテナ名やLAN IPを指定。
# すでに別ホスト名で公開済みならそのURLを指定する。
VITE_EDITH_AGENT_URL=https://agent.example.com \
VITE_EDITH_FACE_URL=https://face.example.com \
docker compose up -d --build
```

- コンテナは内部的に80番ポートで配信し、ホスト側の **8080番** にマッピングされる
  （`docker-compose.yml`の`ports: ["8080:80"]`）。
- コンテナは平文HTTPで配信するだけでよい。**TLS終端はCloudflare Tunnel側が行う**ため、
  コンテナ自体に証明書を置く必要はない。

## 2. `cloudflared` でトンネルに ingress を追加する

自宅サーバで動かしている（または新規に用意する）`cloudflared`のトンネル設定
（`config.yml`、またはCloudflare Zero Trustダッシュボードの「Public Hostname」）に、
以下のルールを追加する。

```yaml
# 例: cloudflared の config.yml
ingress:
  - hostname: edith.under18.dev
    service: http://localhost:8080   # edith_mobileコンテナが公開しているホスト側ポート
  # ...他サービスのingressルールがあれば続ける...
  - service: http_status:404          # 最後は必ずcatch-all
```

Dockerネットワークで`cloudflared`も同じ`docker compose`内に同居させている場合は、
`http://edith_mobile:80`（コンテナ名、サービス名ベースの名前解決）を指定してもよい
（その場合は`cloudflared`コンテナと`edith_mobile`コンテナを同一ネットワークに置くこと）。

トンネルのトークン・認証情報（`cloudflared tunnel login`で発行される証明書や
トンネルのcredentials jsonなど）は**リポジトリにコミットしない**。サーバ上に直接配置するか、
`.env`経由で読み込む運用にする。

## 3. 動作確認

```bash
# コンテナが起動しているか
docker compose ps

# ローカルから直接（Cloudflareを経由せず）疎通確認
curl -I http://localhost:8080/

# 本番URLで確認（スマホ実機のブラウザで開く）
# → https://edith.under18.dev/ を開き、背面カメラ許可 → 顔検出枠が出ること、
#    テキスト指示（例:「田中さんを覚えて」）→ capture_faceアクションが処理されることを確認。
```

## 接続先（バックエンドURL）を後から変える場合

`VITE_EDITH_AGENT_URL` / `VITE_EDITH_FACE_URL` は**ビルド時にVite側へ埋め込まれる値**
（`Dockerfile`で`ARG`として受け取り、静的ファイルに焼き込まれる）。コンテナを再ビルドせずに
接続先を変えたい場合は、アプリ内の**接続先設定シート**（🔗アイコン）から変更する
（`localStorage`に保存され、次回アクセス時も保持される）。

## 再デプロイ（更新時）

```bash
cd edith_mobile
git pull   # または最新のソースを反映
docker compose up -d --build
```

ダウンタイムを避けたい場合は、`docker compose up -d --build` の前に新タグでビルドして
ヘルスチェック後に切替える、などの手当てを別途検討する（現状はシンプルな単一コンテナ構成）。
