# 本機開發環境

`compose.yaml` 提供 PostgreSQL；migration 唯一來源為 `backend/src/main/resources/db/migration/`。`compose.dev.yaml` 加入前後端與同源 TLS gateway，所有主機 port 綁定 loopback。UPMS 僅由 HTTPS 使用，Session Cookie 強制 Secure、HttpOnly、SameSite=Lax。

```sh
cp .env.example .env
# 在 .env 設定 POSTGRES_PASSWORD（僅本機使用，不提交）
./infrastructure/generate-dev-tls.sh
docker compose -f compose.yaml -f compose.dev.yaml up -d --build --wait
```

登入介面為 **https://localhost:8443**。DEV 憑證含 localhost／127.0.0.1 SAN，有效 30 天；私鑰只在 ignored `infrastructure/dev-tls/`，不提交或放入 artifacts。瀏覽器首次需信任這張本機開發憑證；它不是公開受信任憑證，也不可用於正式環境。腳本保留仍有效的既有憑證；到期時停止 gateway、移走舊檔再重建並重啟。

```sh
curl --cacert infrastructure/dev-tls/localhost.crt https://localhost:8443/api/v1/status
docker compose -f compose.yaml -f compose.dev.yaml logs backend
docker compose -f compose.yaml -f compose.dev.yaml down
```

DB 主機連線為 `localhost:5432/xk_logistics`，後端工程檢查為 `http://localhost:8084/actuator/health`。HTTP Vite 5173 僅供工程偵錯；帳號流程必須走 TLS gateway，不能以 HTTP 測試宣稱 Secure Cookie 通過。主機單獨執行前後端時，完整帳號流程亦須另接同源 TLS 代理；建議直接使用上述完整 Compose。

Gateway 固定 Nginx `1.30.8-alpine` 與官方 manifest digest `sha256:6437a63b5c529f0e06162b3f593c0154cff6fa2ea0f94fd7c27928cb399e022d`，已實拉並驗證版本。它覆寫客戶端的 forwarded headers，後端只應位於受信任代理後方；本機後端 port 僅 loopback，正式代理信任邊界仍需獨立部署驗收。若開發中重新建立 backend／frontend 容器造成 IP 改變，使用 `up -d --force-recreate gateway` 重新解析代理目標。

只啟動 DB、不開主機 port：`docker compose up -d --wait db`。需主機連線時用 `docker compose -f compose.yaml -f compose.dev.yaml up -d --wait db`。停止環境保留資料卷。需要重驗空庫時，先確認為可拋棄 DEV 資料，再執行 `down -v`；這會刪除該 Compose 專案的本機 DB。不同驗證使用獨立 `COMPOSE_PROJECT_NAME`／DB／port，避免共用資料。

UPMS E2E 僅使用 `xk_logistics_e2e` 隔離資料庫與 test-classpath fixture；fixture 不包含於產品 jar。測試憑證只寫暫存檔，不上傳報告，登入相關 trace 關閉；CI 生成自己的短期 DEV 憑證及隨機 DB 密碼。沒有公開初始化或測試 seed API。

`compose.prod.yaml` 為禁止啟動的交接佔位，尚無正式容器、HTTPS、機密、備份還原或部署設定，不可作為正式環境。DEMO／STG／PRD 需後續工作包獨立核准及驗收。
