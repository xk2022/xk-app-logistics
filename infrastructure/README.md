# 本機開發環境

`compose.yaml` 僅提供 PostgreSQL，migration 唯一來源為 `backend/src/main/resources/db/migration/`。
`compose.dev.yaml` 加入前後端，所有公開 port 綁定 loopback；frontend 透過 `/api` 同源代理至 backend。

```sh
cp .env.example .env
# 在 .env 設定 POSTGRES_PASSWORD（僅本機使用，不提交）
docker compose up -d --wait db
docker compose -f compose.yaml -f compose.dev.yaml up -d --build --wait
docker compose -f compose.yaml -f compose.dev.yaml logs backend
docker compose -f compose.yaml -f compose.dev.yaml down
```

DB 預設 `localhost:5432/xk_logistics`、前端 `http://localhost:5173`、後端 `http://localhost:8084`。
只啟動 DB 時不開主機 port；需主機連線時使用 dev overlay：

```sh
docker compose -f compose.yaml -f compose.dev.yaml up -d --wait db
```

停止環境不會移除資料卷。需要重新驗證空庫時，先確認這是可拋棄的 DEV 資料，再執行 `down -v`；此命令會刪除該 Compose 專案的本機 DB。
不同測試使用不同 `COMPOSE_PROJECT_NAME` 與 port，避免共用資料卷。

`compose.prod.yaml` 為明確禁止啟動的交接佔位，尚無正式容器、HTTPS、機密、備份還原或部署設定。它覆寫 DB 啟動為失敗，不可作為正式環境使用。DEMO／STG／PRD 需後續工作包獨立核准與驗收。
