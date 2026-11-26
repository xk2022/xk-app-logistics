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
| LG-M3-02 | [FMS 司機管理](m3-02-fms-driver-handoff.md)，Q26–Q30 已核准，實作與驗收見 [M3-02 驗證](../09_testing/m3-02-validation.md) |
| LG-M3-03 | [TOM 正式受理](m3-03-tom-acceptance-handoff.md)，Q31–Q35 已核准，實作與驗收見 [M3-03 驗證](../09_testing/m3-03-validation.md) |
| LG-M3-04 | [TMS 派工與有效占用](m3-04-dispatch-handoff.md)，Q36–Q40 已核准，實作與交付見 [M3-04 驗證](../09_testing/m3-04-validation.md) |
| LG-M3-05 | [TMS 調度工作台](m3-05-dispatch-ui-handoff.md)，PR #20 與 main CI 四過；完整交付見 [M3-05 驗證](../09_testing/m3-05-validation.md) |
| LG-M3-06 | [訂單與派車整合驗收](m3-06-integration-handoff.md)，最終 590／369／28 與五份 HTTP 契約全過；逐包交付見 [M3 最終報告](../09_testing/m3-integration-acceptance.md) |

2026-11-26 使用者採用剩餘 M3 工作，按 04→05→06 依序獨立驗收、PR、合併；未決政策與部署仍不自動核准。M3-01～06 工程實作與本機整合驗收完成；本包交付 PR／main 四過收據為遠端完成依據。M3 當時停在 M4 規格決議；現依新 M4 明確授權逐包交付。


## M4 七工作包

Q41–Q47及全部七包已授權；前置規格PR #22與main四項CI成功後，依[M4交接](m4-execution-handoff.md)逐包獨立分支／PR／本機及CI全過／合併／精確main四過，才進下一包。

| 工作包 | 工程與交付 |
| --- | --- |
| LG-M4-01 | 本人詳情與出發；PR #23及main四過 |
| LG-M4-02 | 進度與私有照片；PR #24及main四過 |
| LG-M4-03 | 異常、補件與管理處置；PR #25及main四過 |
| LG-M4-04 | 完成與原子有效占用解除；PR #26及main四過 |
| LG-M4-05 | 管理確認與整單結案；PR #27＋補正#28及最終main四過，原失敗保留 |
| LG-M4-06 | 本人七命令／隱私預覽／原次重試與手機；PR #29及main四過 |
| LG-M4-07 | [全量交接](m4-07-integration-handoff.md)；最終本機1,083／613／12工具units／40 E2E、HTTP3,242、150原始隱私及真47表還原／Console全部通過；最後PR／精確main四CI由[實際收據](../09_testing/m4-07-validation.md)記錄 |

各包source／merge／CI run／attempt、測試數量與剩餘風險見[M4完整驗收報告](../09_testing/m4-integration-acceptance.md)。M4-07只修驗收發現的既有手機單欄要求，保留舊流程與API／Migration；沒有新業務政策核准。正常策略維持拒絕，不開M5、不動舊repo、不部署正式環境。
