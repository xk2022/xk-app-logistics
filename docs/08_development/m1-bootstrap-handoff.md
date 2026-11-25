# M1 工程初始化交接｜執行與驗收

工作包：LG-M1。日期：2026-11-25。Q01–Q05 已採用；本輪已授權執行 LG-M1。原交接範圍保留，實際交付與結果見 [M1 驗證](../09_testing/m1-validation.md)。目標：建立可重現、可驗證的工程基礎，非完成物流業務功能。

## Codex 工作範圍

1. 先讀 README、AGENTS、docs/README.md、[架構](../04_architecture/architecture.md)、[決議](../98_decisions/2026-11-25-foundation.md) 與中央 APP 規範。
2. 核對 Java 21／Spring Boot（歷史 4.1.x 方向，中央版本基準待驗證）、Maven、React/TypeScript/Vite、PostgreSQL 17/Flyway 的實際相依組合；依官方資料鎖定相容版本。遇版本衝突記錄 ADR，不擅改產品選型。
3. 建立 frontend、backend、infrastructure、tests/e2e、.github/workflows、compose.yaml、compose.dev.yaml、compose.prod.yaml、.env.example；Maven Wrapper 與前端 lockfile 納入版本管理。
4. 前端僅殼層及服務狀態、後端僅最小 health 與 Flyway 空庫初始化，無真實客戶資料、固定正式密碼、正式登入或物流業務 API。
5. 建立隔離 CI：前端 lint/typecheck/test/build、後端 Maven verify，PostgreSQL 整合測試、必要安全與依賴檢查；不連正式服務、不部署、不產生付費資源。
6. 更新 README（實際啟動、設定、測試與環境）及測試證據；工作分支 PR 交付，依中央合併流程辦理。

## Definition of Done

| 驗收 | 證據 |
| --- | --- |
| 版本可重現 | 精確相依清單、lockfile、wrapper |
| 前端 | lint、typecheck、test、build |
| 後端 | Maven verify、health、空庫 Flyway |
| CI | 實際 workflow run、必要檢查通過 |
| 文件 | README 命令、環境設定、已知限制 |
| 風險界線 | 無正式資源、秘密、客戶資料或未授權功能 |

M2 需在 M1 驗收後獨立交辦；先完成精確帳號／安全政策及必要主檔契約，再依[首片 API](../06_api/first-slice.md)實作。後續派工、照片、財務與正式部署不在 M1。
