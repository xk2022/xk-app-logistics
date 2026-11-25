# LG-M2-03 CRM｜Ready for assignment

基準 main：6dd797fe774779257acf8336b0abb5a589ae643a；ADM PR #6 已合併。Q16–Q20 已核准。先合併本文件 PR，再從最新 main 建立功能分支。

1. 閱讀 AGENTS、公司 APP 規範、[CRM 決議](../98_decisions/2026-11-25-crm.md)、[模型](../05_database/crm-model.md)、[API](../06_api/crm.md)及既有訂單契約。
2. 定稿 DTO、獨立 CRM OpenAPI、欄位限制與最小業務規則；未核准業務例外不得自行定案。
3. 實作 CRM 模組、Flyway V4（不改 V1–V3）、交易、唯一性、版本與授權，保留歷史資料；使用 UPMS 已有授權介面，跨模組透過公開服務。
4. 前端客戶列表、明細／編輯、聯絡人及常用地址、啟停、分頁搜尋和有效客戶選項。提供空狀態、錯誤與版本衝突回饋。
5. 驗收空庫與升級、唯一性、多聯絡人／地址、父子一致性、停用與引用查核、越權拒絕、版本與交易、真實 PostgreSQL／HTTP、桌面與手機 E2E；保留 UPMS、ADM 測試。
6. CI 全通過後 Commit、Push、PR，依核准流程合併；更新 README、驗收證據與狀態。

全部測試目前 not_run。正常 migration 不加入真實客戶或合成測試 seed；不開發 TOM、FMS、派工、照片、計費或部署，不改原訂單契約。客戶快照真正寫入與停用／新訂單競爭整合驗收在 M2-04 完成，不在 CRM 先宣稱訂單驗收完成。
