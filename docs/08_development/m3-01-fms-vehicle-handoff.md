# LG-M3-01 FMS 車輛管理｜Ready for assignment

基準 main：776ee3455429ee5db32eef33ddc54872cd2a38cb；M2-05 PR #10 已合併。[Q21–Q25](../98_decisions/2026-11-25-fms-vehicle.md) 已核准。先合併本次規格 PR，再從最新 main 建立開發分支。

1. 閱讀 AGENTS、公司 APP 規範、[模型](../05_database/fms-vehicle-model.md)、[API](../06_api/fms-vehicles.md)、ADM 與 UPMS 契約。
2. 定稿獨立 OpenAPI、欄位驗證及分類來源；沒有業務核准的正式車種值不得自行種入。
3. 實作 FMS 車輛主檔、Flyway V6、交易、唯一車牌、樂觀鎖與權限；以可信服務提供主檔可引用查詢，後續 TMS 再整合實際占用。
4. UI 車輛列表／明細／新增編輯／狀態、檢驗期限顯示、分頁與篩選、空／錯誤／版本衝突。
5. 驗收 PostgreSQL 空庫及 V5 升級、唯一性、車種與載重／日期、停用、角色、非法狀態、前後端及手機/桌面 E2E。保留 M2 原測試及四份訂單契約。
6. CI 四項全部通過才 Commit、Push、PR、依核准流程合併；更新 README、進度與實際驗收證據。

不增加真實車籍、司機或客戶資料，不開發司機、正式受理、派工、照片、正式部署；不得將管理狀態當成真實派工可用性。產品驗收尚未執行。
