# ADM 邏輯模型｜Draft

| 表 | 欄位與約束 |
| --- | --- |
| adm_dict_category | UUID id、唯一 code、name、enabled、sort_order、version、審計欄位 |
| adm_dict_item | UUID id、category_id FK、code、label、enabled、sort_order、version、審計欄位；(category_id,code) 唯一 |
| adm_parameter | UUID id、唯一 key、name、value_type、value_text、description、enabled、version、審計欄位 |
| adm_change_log | UUID id、目標類別及 ID、動作、操作者、時間、前後值 |

代碼不可變，停用不刪除。分類停用時其項目不可供新引用；歷史仍可顯示。參數型別須驗證，拒絕不符合核定用途的鍵值；不可存憑證或替代部署設定。異動與稽核同交易，樂觀鎖防止覆寫。資料庫約束由下一個 Flyway 版本建立，不改既有 V1/V2 或訂單契約。

候選種子：訂單類型的固定枚舉唯讀展示；CONTAINER_TYPE、TASK_KIND、VEHICLE_TYPE 分類先建空分類或僅隔離 fixture，正式項目需確認。固定狀態機、角色、認證、安全限流等不於 ADM 編輯。
