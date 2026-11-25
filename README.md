# XK Logistics v2.0｜貨運物流管理平台

| 項目 | 說明 |
| --- | --- |
| Repository | xk2022/xk-app-logistics |
| 類型 | xk-app（Web 應用系統） |
| 狀態 | development — LG-M1 工程基礎（驗證紀錄見下方） |
| 主要使用者 | 貨運公司管理員、調度員、司機 |
| 核心用途 | 貨運訂單、貨櫃、分段任務、派工、回報與結案 |
| 技術基準 | React / TypeScript / Vite、Java 21、Spring Boot、PostgreSQL 17、Flyway |
| 正式網址 | 尚未部署 |

## 文件與開發

- [文件總索引](docs/README.md)
- [產品需求](docs/01_product/prd.md)
- [MVP 與路線圖](docs/01_product/mvp-roadmap.md)
- [開發工作包](docs/08_development/development-plan.md)
- [M1 工程初始化交接](docs/08_development/m1-bootstrap-handoff.md)
- [協作指引](AGENTS.md)

## 環境與版本

需要 Node.js 24.22.3／npm 11.19.1、Java 21、Docker Engine 與 Compose v2。Maven Wrapper 固定 Maven 3.9.16；React 19.3.1、TypeScript 6.0.3、Vite 8.3.8、Spring Boot 4.1.3、PostgreSQL 17.12。精確相依、官方相容性與公司規範基準見 [M1 版本紀錄](docs/04_architecture/m1-versions.md)。

Monorepo 採 Modular Monolith。`frontend/` 為 UI 殼層，`backend/` 的 `platform` 模組僅提供工程端點，未建立物流領域資料表或業務 API。後續模組必須透過介面協作，不跨模組操作 repository。

## 啟動

```sh
cp .env.example .env
# 編輯 .env，設定只用於本機的 POSTGRES_PASSWORD
# Docker Compose 會讀取 .env；不要提交此檔案
docker compose -f compose.yaml -f compose.dev.yaml up -d --build --wait
```

開啟 http://localhost:5173。後端 http://localhost:8084/actuator/health 僅回報健康狀態，`/api/v1/status` 為同源前端整合端點。資料庫容器就緒後，後端啟動時由 Flyway 建立 `logistics` schema 與 V1 空庫基線，再由 Hibernate validate；沒有業務表、帳號或種子客戶資料。

需要主機熱更新開發時，先啟動 DB，再開兩個終端：

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

```sh
# Repo 根目錄；先依上方啟動完整 Compose
npm ci
npx playwright install chromium
npm run test:e2e
```

CI 每次 PR／main push 執行前端品質檢查、後端 verify、依賴與機密檢查、Compose 與 Playwright smoke；報告作為 workflow artifacts 保存。實際命令與驗收結果見 [M1 驗證紀錄](docs/09_testing/m1-validation.md)。覆蓋率門檻尚待核准；目前以必要案例與不可略過的整合測試為門檻。

## 範圍與部署

本階段未實作登入、Session／CSRF 政策、物流功能或五項草稿 API。既有 OpenAPI、角色矩陣、合成案例、欄位字典及 Q01–Q05 保持原樣。M2 必須獨立交辦並先核准精確安全政策。

`compose.prod.yaml` 為禁止啟動的佔位設定；M1 無自動部署。正式環境仍需獨立核准 HTTPS、機密、備份還原、權限與驗收。不使用真實客戶資料、不提交秘密、不更動舊庫。
