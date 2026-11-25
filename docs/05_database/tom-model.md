# OrderCore／TOM 模型｜LG-M2-04

依 [原模型](https://github.com/xk2022/codex-xk-logistic/blob/7092510c7e63a3f5781e1f0a24787a32e3248ac4/docs/FIRST_SLICE_DATA_MODEL.md)、[40 欄字典](first-slice-fields.csv)、[工程決策](../98_decisions/2026-11-25-tom-engineering.md)與使用者本工作包實作。V1–V4 不變，V5 為本包唯一新增 migration。實際物理型別及約束以 [V5 DDL](../../backend/src/main/resources/db/migration/V5__create_order_drafts.sql) 為準，執行結果另見 [驗證](../09_testing/m2-04-validation.md)。

OrderCore 是本次 TOM-owned 訂單聚合的共同領域模型／資料表，不建立一個空殼資料存取模組再由 TOM 跨寫；後續模組使用公開領域／服務介面。

OrderCore 與 TOM 共享同一 UUID：OrderCore 保存識別／技術單號／server company scope／lifecycle；TOM 保存 type／customerId／customerName 歷史快照／DRAFT／聚合版本及船務。項目屬一單，任務屬同單的一個項目；依賴可跨同單項目但不得指向別單、自己或形成環。

V5 精確新增七表：`order_core`、`tom_order`、`cargo_item`、`transport_task`、`task_dependency`、`order_draft_revision`、`api_idempotency`，連同 V1–V4 共十九表，不加額外鎖表。

所有應用跨模組讀寫經公開服務。DB 的 CRM／UPMS FK 只保護完整性，不代表可跨模組直接操作 repository。正常 migration 不建立任何訂單、客戶或測試帳號，也不加入派工、執行、照片或財務表。Spring Data JPA 的 owned repository fragment 在同一 JpaTransactionManager 交易內使用 JdbcTemplate 處理 bytea／JSON、複合 FK、差異圖及鎖定，不新增 ORM 框架或繞過交易。

| 邏輯責任 | 完整性 |
| --- | --- |
| OrderCore | UUID PK、唯一 orderNo、伺服器單公司範圍、lifecycle 只允許契約保留讀值 |
| TOM 訂單 | 同 UUID PK/FK、客戶 FK 與名稱快照、IMPORT／EXPORT／LOCAL、DRAFT／ACCEPTED 讀值、safe-integer 聚合版本 |
| 貨櫃／貨物項目 | 同單 clientRef 唯一、非 null sequence 唯一且正整數；nullable CONTAINER／CARGO，不設歷史櫃號唯一 |
| 運輸任務 | 唯一 taskNo、同單 clientRef、同單 item 複合 FK、起訖時間不逆向、requiredTask 僅 true／null |
| 前置依賴 | 同單兩組 task 複合 FK、不可自指；跨列無環由領域驗證，關係亦保存操作者及時間 |
| 不可變草稿版本 | order＋aggregateVersion 唯一，每個新成功版本保存完整讀回快照，無公開修改／刪除 API |
| 成功重送紀錄 | server scope＋actor＋method＋canonicalPath＋key 唯一；status／原業務 data／Location 與原子寫入同交易 |

所有資料由伺服器產生 createdAt／updatedAt／createdBy／updatedBy。HTTP 日期時間輸出最多六位小數秒；date 保持日期語意。items／tasks／predecessorRefs 的傳輸順序不是業務依賴，不能用排序位置取代前置圖。

客戶名稱與任務地點／聯絡文字為保存時快照，不因主檔更新而回填。shipping_present／contact_present 或等效表示保留 API 的 null 與空物件差異；十進位字串用 BigDecimal／numeric 或等效精確表示，未知值不以零代填。使用者自由文字用 UTF-8 bytea，版本 snapshot／重送 original_data 用 JSON 文本型別（不是 jsonb），可逆保存 U+0000，不因 PostgreSQL text／jsonb 限制靜默改寫 HTTP 值。可空物件 presence、關聯、enum、數值及 FK 仍以實際結構保護；不是以任意 JSON 取代完整性。日期時間保存已驗 wire 字面及 timestamptz(6) 瞬間，兼顧 offset／四位年份傳輸與精確時間比較。

完整替換保留當前同 ref 子項身份，只移除真正不存在於新草稿的未執行子項；歷史版本留存原值。若真正 FK 或前態禁止移除，整筆交易拒絕並回滾，不以 cascade 刪外部受保護資料。儲存期限／歷程清理仍待 D10，本包沒有定時刪除或永久保存政策核准。
