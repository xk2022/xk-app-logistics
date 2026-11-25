# 開發工作包｜Draft

| 工作包 | 內容 | 依賴 |
| --- | --- | --- |
| LG-M0 | 既有需求、決策與新庫文件 | M0 文件已整合 |
| LG-M1 | 前後端殼、精確版本、Compose、Flyway、隔離 CI | 工程實作與驗證見 [M1 驗證](../09_testing/m1-validation.md) |
| LG-M2 | 登入、核心主檔、五項草稿 API 與 UI | M1、首片契約 |
| LG-M3 | 受理、資源調度與派工 | M2、派工及占用例外決策 |
| LG-M4 | 手機照片、回報、異常、釋放與結案 | M3、照片與責任決策 |
| LG-M5 | DEMO、STG、UAT、維運及 PRD 交付準備 | M4、部署及責任核准 |

M1 應依中央 APP Repo 結構建立 frontend/、backend/、infrastructure/、tests/e2e/、.github/workflows/、compose.yaml、compose.dev.yaml、compose.prod.yaml、.env.example。M1 已建立工程骨架；後續業務功能另行交辦。每工作包需獨立 PR、驗收證據，實作與部署另行交辦。

M1 已完成。M2 五包的實作與本機首片驗收已完成；最終 M2-05 PR／main CI 與受測版本見 [M2-05 驗證](../09_testing/m2-05-validation.md)。各包獨立交付如下：

| 工作包 | 範圍 |
| --- | --- |
| LG-M2-01 | [UPMS 帳號與權限](m2-01-upms-handoff.md)；本輪實作／[驗證紀錄](../09_testing/m2-01-validation.md) |
| LG-M2-02 | [ADM 字典與參數](m2-02-adm-handoff.md)（Q11–Q15 已核准，本輪實作／[驗證紀錄](../09_testing/m2-02-validation.md)） |
| LG-M2-03 | [CRM 客戶主檔](m2-03-crm-handoff.md)（Q16–Q20 已核准，本輪實作／[驗證紀錄](../09_testing/m2-03-validation.md)） |
| LG-M2-04 | [TOM 訂單草稿與五項 API](m2-04-tom-handoff.md)（本輪實作／[驗證紀錄](../09_testing/m2-04-validation.md)） |
| LG-M2-05 | [訂單工作台與首片整合驗收](m2-05-order-ui-handoff.md)（[驗證紀錄](../09_testing/m2-05-validation.md)） |

全部沿用 M1 技術基準，逐包獨立交辦。

## M3 工作包規劃

| 工作包 | 範圍 |
| --- | --- |
| LG-M3-01 | [FMS 車輛管理](m3-01-fms-vehicle-handoff.md)，Q21–Q25 已核准，實作與驗收見 [M3-01 驗證](../09_testing/m3-01-validation.md) |
| LG-M3-02 | FMS 司機管理，待決議 |
| LG-M3-03 | TOM 正式受理，待決議 |
| LG-M3-04 | TMS 派工與有效占用，待決議 |
| LG-M3-05 | TMS 調度工作台 |
| LG-M3-06 | 訂單與派車整合驗收 |
