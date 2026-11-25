# LG-M1 工程初始化驗證｜2026-11-25

## 範圍與受測基準

先驗收並合併 [PR #1](https://github.com/xk2022/xk-app-logistics/pull/1)，merge commit `3f86a25c7b8311d753a53754a4b5898113d92ea4`。M0 的 25 份文件已核對連結、JSON、40 筆欄位、五項 OpenAPI 操作、15 筆角色政策及 Schema 正反例；這是靜態規格驗證，並非物流產品功能通過。

M1 自該 main 建立 `feat/lg-m1-bootstrap`；實作僅含 UI Shell、health/status、空庫 Flyway、DEV Compose、測試及 CI。公司規範基準、官方相容性與精確相依見 [版本紀錄](../04_architecture/m1-versions.md)。既有 OpenAPI、角色矩陣、合成案例與欄位字典未修改。

## 本機實測

| 項目 | 命令／情境 | 結果 |
| --- | --- | --- |
| 前端 | `npm ci`、`npm run lint`、`npm run typecheck`、`npm test`、`npm run build` | 通過；11 個 Vitest 案例，含載入、成功、HTTP／網路／格式錯誤、重試、逾時與卸載中止 |
| 後端 | `./mvnw -B -ntp verify` | 通過；1 個單元及 2 個真實 PostgreSQL Testcontainers 整合案例，0 失敗／略過 |
| Flyway | 全新 PostgreSQL 17.12、V1、validate、重跑 migration、重啟 backend | 通過；`logistics` 僅有 `flyway_schema_history`，V1 成功一次，無業務表或種子資料 |
| 服務端點 | `/actuator/health`、前端同源 `/api/v1/status` | 成功；status 包含 `0000`、繁中訊息與 `+08:00` 時間；其他 Actuator 與 orders 路徑為 404 |
| 真實失敗 | 停止 DEV DB 後呼叫 health/status，再恢復 DB | 兩端點皆 HTTP 503；status 回 `E0503`／`DOWN`，不包含內部診斷；恢復後回 200 |
| 瀏覽器 | `npm run test:e2e`，Chromium 桌面及 Pixel 7 | 4 個案例通過；同源真實連線、失敗及重試；桌面 1280×720／手機 393×727 無橫向溢出 |
| 依賴 | root/frontend `npm audit --audit-level=high` | 均為 0 vulnerabilities，包含開發相依 |
| Maven 依賴 | CycloneDX 2.9.3 SBOM + OSV 2.7.1 | SBOM 133 components；OSV 合計掃描 391 筆套件紀錄（root 3、frontend 255、Maven 133），0 漏洞 |
| 秘密 | Gitleaks 8.30.1 Git 歷史掃描 | 未發現洩漏；新提交仍由 CI 重新掃描 |
| Workflow | actionlint 1.7.12、run steps `bash -n` | 通過；action SHA 與官方 tag 核對一致 |

Cloud 的標準 Docker 建置初次因 Maven 不讀取環境代理而失敗；本機以 `/tmp` 暫存 Dockerfile／Compose overlay 和 BuildKit secret 掛載代理設定及 CA，保留 TLS 驗證。這些 Cloud 專用設定未入庫，也未進入映像層。GitHub CI 使用儲存庫原始 Dockerfile，結果以 workflow 為準。

Cloud 預裝 Java 21.0.13 為 JRE，本機 verify 使用 Maven 映像內 JDK 21.0.12.1；容器 runtime 與 CI 目標為 21.0.13，編譯 release 21。前端為 Node 24.22.3／npm 11.19.1。測試未連正式資源。

## CI 與交付追蹤

- 交付 PR：[PR #2](https://github.com/xk2022/xk-app-logistics/pull/2)，目標 `main`。
- 工程實作受測 commit：`fb85e79d0cd9e7a34d4a11e5e3e298390908f6d4`。
- 首輪 [GitHub Actions run #1](https://github.com/xk2022/xk-app-logistics/actions/runs/45540816190)：四項 job 全部 **success**，包含原始 Dockerfile 完整建置、PostgreSQL 空庫與重啟、Maven verify、桌面／手機 4 項 E2E、依賴與新提交秘密掃描。
- 本次補錄僅更新交付文件；最終文件提交及合併後 main 的 CI 由同一 workflow 自動執行，最新狀態與 merge SHA 以 PR／run 紀錄為準。合併前仍須核對最新 head 的全部品質 job。

[M1 quality workflow](https://github.com/xk2022/xk-app-logistics/actions/workflows/ci.yml) 設有四個不可忽略的品質 job：Frontend quality、Backend verify、Dependency and secret checks、Compose and browser smoke。PR 與 main push 都執行；無部署步驟。PR 合併前必須核對受測 head、四個 job 的實際結果與 diff。

JUnit、SBOM、OSV／Gitleaks、Playwright 與 Compose 診斷作為 CI artifacts 保存 14 天。精確受測 SHA、run、PR 與合併結果由 GitHub 不可變執行紀錄及交付回報追蹤；尚未執行的 run 不列為通過。

## 限制及下一階段

- 未實作物流業務、登入、正式 Session／CSRF 政策；M0 首片契約案例尚未作為產品 API 執行。
- DEV DB 帳號為本機初始化用途；正式 least privilege、HTTPS、備份還原與權限需後續部署工作包驗收。
- `compose.prod.yaml` 僅為禁止啟動的交接佔位，無正式部署。
- 覆蓋率數值門檻未核准；目前使用必跑案例與真實整合檢查，不宣稱業務覆蓋率。
- 無真實客戶資料、供應商採購或正式資源。M2 與未決安全細節另行交辦。
