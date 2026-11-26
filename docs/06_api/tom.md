# TOM 訂單草稿 API｜LG-M2-04

唯一機器契約仍是原 [OpenAPI 0.2.0](openapi.yaml)。本頁記錄與已完成 UPMS／CRM 整合的工程行為，不取代或修改該檔、角色矩陣、合成案例與欄位字典。契約中的歷史狀態標記原樣保留，實際執行狀態見 [M2-04 驗證](../09_testing/m2-04-validation.md)。

| 方法（/api/v1） | 正常回應 | 行為 |
| --- | --- | --- |
| POST /orders | 201 OrderEnvelope | 建立 OPEN＋DRAFT、version=0；type 與有效 customerId 即可，項目／任務可空 |
| GET /orders | 200 OrderPageEnvelope | 本公司分頁列表，type／customerId 篩選及四個排序白名單，id 固定次序 |
| GET /orders/{orderId} | 200 OrderEnvelope | 完整讀回聚合，含穩定 ref／UUID、客戶歷史名稱、項目／任務及依賴 |
| PUT /orders/{orderId} | 200 OrderEnvelope | expectedVersion＋完整 draft 替換，只允許未執行 OPEN＋DRAFT |
| GET /orders/{orderId}/acceptance-check | 200 AcceptanceCheckEnvelope | 同版本只讀 ORD-P01–P03 完整性，不受理或派工 |

正常登入 ADMIN／DISPATCHER 操作本公司全部訂單，DRIVER 拒絕；強制改密或已撤銷身分不得操作。沿原 UPMS Session＋CSRF，同一 HttpSession 接受 JSESSIONID／XKSESSION 兩個安全 Cookie 名稱，衝突 ID 拒絕。POST／PUT 必須有有效 X-CSRF-TOKEN 及 Idempotency-Key。Cookie 安全與名稱相容見 [工程決策](../98_decisions/2026-11-25-tom-engineering.md)。

成功固定 `code: "OK"`、`message`、`data`、本次 `traceId`，沒有 timestamp。錯誤加 fieldErrors（path／fieldId／ruleId／message），data 為 null 或版本衝突的 actualVersion；不回 Entity、堆疊、認證資訊或內部 SQL。UPMS／ADM／CRM 的 `0000`／timestamp 格式不擴用到這五項操作。

| HTTP | code |
| --- | --- |
| 400 | VALIDATION_ERROR、INVALID_CUSTOMER |
| 401 | UNAUTHENTICATED |
| 403 | FORBIDDEN、CSRF_INVALID |
| 404 | ORDER_NOT_FOUND |
| 409 | VERSION_CONFLICT、ORDER_NOT_EDITABLE、PROTECTED_RELATION、IDEMPOTENCY_KEY_REUSED、IDEMPOTENCY_IN_PROGRESS |
| 500 | INTERNAL_ERROR |

標準 HTTP HEAD 沿 GET 路由與授權但不傳 body，不算額外業務操作。未知 JSON 欄位、重複 property、非法寫入 method／media／query、壞 UUID、錯誤型別與範圍均拒絕；不以其他模組的錯誤 enum 回應。集合、ref、數量、十進位字串、日期時間等限制直接依原 schema，不把示意作業／櫃型值或未決現場例外當成額外 enum。

列表預設 page=0、size=20（最大100），sort 僅 updatedAt,desc／asc、orderNo,asc／desc；固定再以 id 排序。沒有預先增加 q、status、owner、company 或其他篩選參數。

PUT 省略可選值變 null、省略 items／tasks／predecessorRefs 變空。shipping／siteContact 的空物件保留為各欄 null 的物件，與整組 null 不同。既有 ref 保留 UUID／taskNo，新 ref 由伺服器產生；關係只可指向這次完整草稿內的同單標的。序號有值為正整數且同單唯一，前置不可失聯／自己／成環；陣列順序不代表執行先後。

相同 customerId 保存歷史 customerName；主檔停用後仍可讀及維護原未執行草稿。建立或明確換客戶必須於同交易鎖定有效 CRM 主檔，保存當時名稱。acceptance-check 則會把今日已停用客戶列為 ORD-P01 缺口。

新成功 PUT 恰加一次版本；即使資料相同，新 key 仍是一次新保存。Idempotency-Replayed=false 代表新成功，true 重播原業務結果且產生新 traceId；POST 重播保留原 201 與 Location。重播先驗當前權限與 key，不先以原 expectedVersion 或客戶今日狀態拒絕。相同 key 不同 body／處理中 409，失敗可同 key 重試；期限未核准前不自動清理或復用。

完整性檢核逐一對應原 ORD-P01–P03 及 40 欄 fieldId／JSON Pointer。缺項列 missingFields；COMPLETE 時必為空。pendingPolicyIds 保留相關未決例外，operationalAcceptance 永遠 NOT_GRANTED，且檢核不寫版本、稽核或訂單狀態。保留單號及 lifecycle／acceptance 讀值不代表提供轉態 API。

後續工作：正式受理採獨立 [M3-03 API 規格](tom-acceptance.md)，其實作尚未完成；本頁所記五項草稿契約不被替換。
