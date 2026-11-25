# CRM 邏輯資料模型｜已採用基準

| 表 | 欄位 |
| --- | --- |
| crm_customer | UUID id、唯一 customer_code、name（最多200字以符合既有訂單快照）、tax_id、phone、email、enabled、version、審計欄位 |
| crm_contact | UUID id、customer_id FK、name、title、phone、email、enabled、version、審計欄位 |
| crm_address | UUID id、customer_id FK、label、address、address_type（公司／提貨／送貨／其他為工程候選）、enabled、version、審計欄位 |

客戶代碼唯一且穩定；客戶名稱非唯一；統編可空，未核准唯一性與特例不自行假設。客戶與子項保留停用歷史，不提供硬刪除，不跨客戶移轉子項。所有新增與更新透過交易、後端授權及樂觀鎖，實作時確定欄位格式與長度並記錄。

CRM 提供公開服務查詢有效可引用客戶及名稱快照原始資料；未來 TOM 在同一交易以鎖定或等效方式協調與客戶停用的競爭，不能先查後寫而失去一致性。已保存的訂單、地址、聯絡資料歷史不受主檔變更覆寫。此工作包不建立 TOM 表或改原 OpenAPI。
