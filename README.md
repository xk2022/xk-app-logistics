# XK Logistics v2.0｜貨運物流管理平台

| 項目 | 說明 |
| --- | --- |
| Repository | xk2022/xk-app-logistics |
| 類型 | xk-app（Web 應用系統） |
| 狀態 | development — LG-M2-03 CRM（驗證紀錄見下方） |
| 主要使用者 | 貨運公司管理員、調度員、司機 |
| 核心用途 | 貨運訂單、貨櫃、分段任務、派工、回報與結案 |
| 技術基準 | React / TypeScript / Vite、Java 21、Spring Boot、PostgreSQL 17、Flyway |
| 正式網址 | 尚未部署 |

## 文件與開發

- [文件總索引](docs/README.md)
- [產品需求](docs/01_product/prd.md)
- [MVP 與路線圖](docs/01_product/mvp-roadmap.md)
- [開發工作包](docs/08_development/development-plan.md)
- [CRM 工程交接](docs/08_development/m2-03-crm-handoff.md)
- [CRM API 契約](docs/06_api/crm.md) · [獨立 OpenAPI](docs/06_api/crm-openapi.yaml)
- [CRM 工程決策](docs/98_decisions/2026-11-25-crm-engineering.md)
- [ADM 工程交接](docs/08_development/m2-02-adm-handoff.md)
- [ADM API 契約](docs/06_api/adm.md) · [獨立 OpenAPI](docs/06_api/adm-openapi.yaml)
- [ADM 工程決策](docs/98_decisions/2026-11-25-adm-engineering.md)
- [UPMS 工程交接](docs/08_development/m2-01-upms-handoff.md)
- [UPMS 契約](docs/06_api/upms.md)
- [UPMS 工程決策](docs/98_decisions/2026-11-25-upms-engineering.md)
- [協作指引](AGENTS.md)

## 環境與版本

需要 Node.js 24.22.3／npm 11.19.1、Java 21、Docker Engine 與 Compose v2。Maven Wrapper 固定 Maven 3.9.16；React 19.3.1、TypeScript 6.0.3、Vite 8.3.8、Spring Boot 4.1.3、PostgreSQL 17.12。精確相依、官方相容性與公司規範基準見 [M1 版本紀錄](docs/04_architecture/m1-versions.md)。

Monorepo 採 Modular Monolith。`frontend/` 提供登入、本人密碼、角色工作台與 ADMIN 帳號管理；`backend/` 的 `platform` 提供工程端點，`upms` 負責帳號、固定角色、認證與稽核；`adm` 負責字典、核准參數與異動紀錄，透過 UPMS 公開授權介面核對身分；`crm` 提供客戶、聯絡人、常用地址與可信 server 共用查詢。未建立訂單等物流業務資料表。後續模組必須透過介面協作，不跨模組操作 repository。

## 啟動

```sh
cp .env.example .env
# 編輯 .env，設定只用於本機的 POSTGRES_PASSWORD
# Docker Compose 會讀取 .env；不要提交此檔案
./infrastructure/generate-dev-tls.sh
docker compose -f compose.yaml -f compose.dev.yaml up -d --build --wait
```

開啟 **https://localhost:8443**。DEV 憑證由本機生成、有效 30 天，只用於本機驗收；瀏覽器首次需要信任該本機憑證。登入使用同源 HTTPS，Session Cookie 強制 Secure／HttpOnly／SameSite=Lax；HTTP 5173 僅保留工程檢查，不作帳號操作。後端 http://localhost:8084/actuator/health 僅回報健康狀態，`/api/v1/status` 為同源前端整合端點。資料庫容器就緒後，後端啟動時由 Flyway 建立 `logistics` schema、V1 工程基線、V2 UPMS 帳號／稽核、V3 ADM 及 V4 CRM 結構，再由 Hibernate validate；沒有預設帳號、固定密碼或客戶種子資料。

以下主機執行方式只供工程偵錯。完整登入仍使用上述 HTTPS Compose；若自行組合主機前後端，須另接同源 TLS 代理，不可降低 Cookie 安全設定：

```sh
docker compose -f compose.yaml -f compose.dev.yaml up -d --wait db
# 終端一：設定 DB_PASSWORD，值與 .env 的 POSTGRES_PASSWORD 相同
cd backend
export DB_URL=jdbc:postgresql://localhost:5432/xk_logistics
export DB_USER=xk_dev
# 以本機環境或機密工具設定 DB_PASSWORD
./mvnw spring-boot:run
```

```sh
# 終端二
cd frontend
npm ci
npm run dev
```

Vite 將 `/api` 代理至 `http://localhost:8084`；容器透過 `API_PROXY_TARGET` 指向 backend。變更主機 port 時一併設定 DB_URL 與 API_PROXY_TARGET。環境參數及停止／重建資料說明見 [infrastructure](infrastructure/README.md)。

## 測試與 CI

```sh
cd frontend
npm ci
npm run lint
npm run typecheck
npm test
npm run build
```

```sh
cd backend
./mvnw --batch-mode verify
```

後端 verify 使用 Testcontainers 啟動獨立 PostgreSQL，必須有可用 Docker。測試不會略過整合測試，且不連外部或正式 DB。

E2E 必須使用隔離的 `xk_logistics_e2e` 空帳號資料庫與 test-only fixture，不能直接對一般 DEV DB 執行。請完整依照 [E2E README](tests/e2e/README.md) 準備獨立環境、私有暫存憑證及同源 TLS，再執行 `npm run test:e2e`。

CI 每次 PR／main push 執行前端品質檢查、後端 verify、依賴與機密檢查、Compose 與 Playwright smoke；報告作為 workflow artifacts 保存。實際命令與驗收結果見 [M1 驗證紀錄](docs/09_testing/m1-validation.md)。覆蓋率門檻尚待核准；目前以必要案例與不可略過的整合測試為門檻。UPMS 的真實 HTTPS、三角色、Session／CSRF 與 migration 驗證見 [M2-01 驗證](docs/09_testing/m2-01-validation.md)。

## 首次管理員與帳號復原

正常服務啟動不會建立帳號。具主機操作權且經責任人核實的真人，先啟動 DB，再以互動終端執行：

```sh
# 只在帳號表全空時成功，沒有公開 HTTP 初始化入口
docker compose -f compose.yaml -f compose.dev.yaml run --rm -it backend --upms.command=init
# 受控人工復原，必須記錄操作者識別及原因
docker compose -f compose.yaml -f compose.dev.yaml run --rm -it backend --upms.command=recover
```

依提示輸入操作者、原因及目標帳號，密碼由隱藏輸入取得，不放命令參數、環境變數或 log。臨時密碼 15 分鐘有效，首次登入僅能讀本人資訊、改密或登出；改密後須重新登入。憑證交付由真人處理，不自動寄送。初始化／復原會留下不含秘密的稽核。

一般 ADMIN 可在 UI 建立帳號、調整顯示名稱／單一角色／啟用狀態，固定角色為 ADMIN、DISPATCHER、DRIVER；不可移除最後一名啟用 ADMIN。停用、換角色、復原與改密會使舊認證失效。Session 閒置 30 分鐘／登入最長 8 小時／最多 3 個，超過時撤銷最舊 Session，不提供 remember-me。

密碼採 NFC 一致化後 15–128 個 Unicode code point、不 trim；PBKDF2-HMAC-SHA256 600,000 次與每筆隨機 salt，拒絕有限的常見密碼集合；未宣稱完整外洩密碼覆蓋。詳細工程參數與單 instance 限制見工程決策。

## ADM 字典與核准參數

ADMIN 登入並完成首次改密後，可由「系統管理」管理分類、項目、啟停、排序及異動紀錄。代碼建立後不可修改，沒有硬刪除操作；更新須附 version，衝突時重新讀取後再操作。分類停用後，其項目不再列入可供新引用的 options，歷史資料仍保留。

V3 只初始化固定 `ORDER_TYPE`／`IMPORT`、`EXPORT`、`LOCAL` 的唯讀對照，以及 `CONTAINER_TYPE`、`TASK_KIND`、`VEHICLE_TYPE` 空分類。它們不改變既有固定業務枚舉。沒有匯入正式字典項目或客戶資料。

系統參數只可使用核准目錄中的鍵、型別、用途與界限，支援文字／數字／布林；不可存秘密、認證、安全政策或部署設定。**目前尚無核准正式參數鍵，正常啟動的參數目錄為空**。目錄沒有寫入 API，後續須以另行核准的 migration 加入；隔離 E2E fixture 的合成定義不會進入一般啟動。

所有 ADM API 目前僅 ADMIN；DISPATCHER／DRIVER 尚無核准分類讀取權限，預設拒絕。未來模組只能透過 ADM 公開查詢服務取啟用選項或歷史標籤，不跨 repository，也不以 ADM 取代固定狀態機或角色。

ADM 的資料庫、HTTP、前端與 E2E 證據見 [M2-02 驗證](docs/09_testing/m2-02-validation.md)；原 UPMS 測試與訂單契約保留。

## CRM 客戶管理

正常登入並完成首次改密的 ADMIN／DISPATCHER 可由「客戶管理」建立及維護本公司客戶、多位聯絡人與多組常用地址，使用分頁、搜尋、啟停與有效客戶選項。DRIVER 無客戶主檔權限；ADM 仍僅 ADMIN。客戶使用 UUID 及唯一、建立後不變的大寫代碼；更新須附 version，遇到 409 應重新載入後再操作。

客戶名稱、聯絡人姓名與地址內容為必要文字；taxId 可空且非唯一，電話不限定單國格式，地址類型是可空描述文字，不是核准業務枚舉。沒有硬刪除。停用客戶仍保留歷史主檔與子項，從有效選項移除並拒絕新引用查核；CRM 主檔仍可維護。

後續 TOM 須透過 `CrmQueries` 公開服務，在已授權的寫入交易中查核有效客戶並保存當時快照；本階段只驗證來源與鎖定介面，真正訂單寫入及競爭整合驗收留至 M2-04。客戶改名不得覆寫既有訂單 `customerName`。V4 不加入客戶或測試 seed。驗收證據見 [M2-03 驗證](docs/09_testing/m2-03-validation.md)。

## 範圍與部署

UPMS 沿 Q06–Q10 與新契約交付；既有訂單 OpenAPI、角色矩陣、合成案例、欄位字典與產品決議保持原樣。ADM 依 Q11–Q15、CRM 依 Q16–Q20 交付；TOM、FMS、派工與照片未開發，後續工作包須獨立交辦。

`compose.prod.yaml` 為禁止啟動的佔位設定；M1 無自動部署。正式環境仍需獨立核准 HTTPS、機密、備份還原、權限與驗收。不使用真實客戶資料、不提交秘密、不更動舊庫。
