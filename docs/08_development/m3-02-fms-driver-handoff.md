# LG-M3-02 FMS 司機管理｜Ready for assignment

基準 main：714ed42b3aad3397325d83205f1ce9ada9d9fad5；M3-01 PR #12 已合併。[Q26–Q30](../98_decisions/2026-11-25-fms-driver.md) 已核准。先合併本次規格 PR，再從最新 main 開分支。

1. 閱讀 AGENTS、最新公司 APP 規範、[模型](../05_database/fms-driver-model.md)、[API](../06_api/fms-drivers.md)、UPMS／ADM 公開服務及車輛工程規格。
2. 定稿獨立 OpenAPI、DTO、資料最小化、合法行政／出勤狀態及帳號關聯守衛。不擅定未核准的證照政策。
3. 實作 FMS 司機主檔、Flyway V7、交易、唯一性、樂觀鎖、角色限制與可信行政可用查詢；不建立真實派工占用。
4. 實作 ADMIN／DISPATCHER 管理頁、DRIVER 本人唯讀頁；支援分頁、搜尋、狀態及證照期限提示、無資料、失敗、版本衝突。
5. 驗收 PostgreSQL 空庫與 V6→V7、唯一編號／綁定、無帳號可管理、跨人讀取拒絕、角色／撤銷／解綁一致性、並發、日期及狀態、桌面與手機 E2E；保留所有 M2／M3-01 測試和契約。
6. CI 四項全過後 Commit、Push、PR，依核准流程合併；更新 README、驗收紀錄及進度。

本次不實作正式受理、派車、資源占用、配對、照片或正式部署，不使用真實司機資料，不把行政可用或期限提示稱為法規資格。產品驗收仍 not_run。
