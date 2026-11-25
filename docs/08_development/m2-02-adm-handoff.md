# LG-M2-02 ADM｜開發交接草案

基準 main 268bab8136d943d891f24e6651ebbb848e7288f0，UPMS 已合併。須先確認 [Q11–Q15](../98_decisions/2026-11-25-adm.md) 後方可實作。

1. 閱讀 AGENTS、APP 公司規範、[資料模型](../05_database/adm-model.md)、[API](../06_api/adm.md)與既有 UPMS 契約。
2. 在現有模組化架構內建立 ADM、Flyway 新 migration、DTO、Service、資料驗證、權限及稽核。
3. 建立管理介面：字典分類、字典項目、參數、異動紀錄；提供明確空狀態、錯誤及版本衝突回饋。
4. 後續業務模組僅透過 ADM 公開服務查詢，不跨 repository。
5. 驗收 PostgreSQL 空庫及升級、唯一性、代碼不可變、停用、樂觀鎖、角色授權、參數型別、稽核原子性、前端與 E2E，保留 UPMS 原測試。
6. CI 全通過後依核准範圍 Commit、Push、PR；合併後更新狀態和驗收證據。

不實作 CRM/TOM/FMS，不新增正式資料、不更動訂單契約、不部署；合成字典值不得宣稱真實業務核准。所有產品測試尚未執行。
