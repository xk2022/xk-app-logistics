# 開發工作包｜Draft

| 工作包 | 內容 | 依賴 |
| --- | --- | --- |
| LG-M0 | 既有需求、決策與新庫文件 | M0 文件已整合 |
| LG-M1 | 前後端殼、精確版本、Compose、Flyway、隔離 CI | Q01–Q05 已採用；精確相依待驗證 |
| LG-M2 | 登入、核心主檔、五項草稿 API 與 UI | M1、首片契約 |
| LG-M3 | 受理、資源調度與派工 | M2、派工及占用例外決策 |
| LG-M4 | 手機照片、回報、異常、釋放與結案 | M3、照片與責任決策 |
| LG-M5 | DEMO、STG、UAT、維運及 PRD 交付準備 | M4、部署及責任核准 |

M1 應依中央 APP Repo 結構建立 frontend/、backend/、infrastructure/、tests/e2e/、.github/workflows/、compose.yaml、compose.dev.yaml、compose.prod.yaml、.env.example。當前僅規格文件，不製造空目錄充數。每工作包需獨立 PR、驗收證據，實作與部署另行交辦。

[M1 工程初始化交接](m1-bootstrap-handoff.md) 為下一工作入口。M1 不含業務 API 或正式部署。
