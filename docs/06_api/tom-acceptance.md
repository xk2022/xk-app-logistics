# TOM 正式受理 API｜LG-M3-03

依[Q31–Q35](../98_decisions/2026-11-26-tom-acceptance.md)，只新增 `POST /api/v1/orders/{orderId}/accept`，機器契約為[獨立 OpenAPI](tom-acceptance-openapi.yaml)。保留[原五項 API](tom.md)、四份固定來源與既有回應；[模型](../05_database/tom-acceptance-model.md)及[工程決策](../98_decisions/2026-11-26-tom-acceptance-engineering.md)說明原子事件與重送。本文不是實際測試證據。

正常 ADMIN／DISPATCHER 操作伺服器本公司範圍，DRIVER拒絕。沿既有安全 HttpSession、JSESSIONID／XKSESSION同Session別名與CSRF；不新增身分、租戶、FMS資格、派工或資源占用。只有POST；已授權管理者的 `GET /orders/{id}/accept` 仍404，不把新路徑加入既有所有method的known-path400邏輯。

## 請求與成功

body精確為 `{ "expectedVersion": 0 }`，唯一必填欄位，0–9007199254740991數學整數；1／1.0／1e0同義，不接受null、字串、fraction或超界。嚴格JSON／UTF-8，拒duplicate key、trailing、未知欄位、不成對surrogate；無query。需有效 X-CSRF-TOKEN 及恰一個 Idempotency-Key，key為 `[A-Za-z0-9._:-]{1,128}`，區分大小寫。

成功固定HTTP200及必備 `Idempotency-Replayed: false|true`，不要求Location。body精確沿原 `{code:"OK",message,data:OrderDraftRead,traceId}`；data必為OPEN＋ACCEPTED，version等於**原請求**expectedVersion＋1。沒有timestamp、acceptedAt、actor或event欄位；UI不能拿updatedAt或本機時鐘捏造受理人／時間。新schema透過allOf重用原OrderEnvelope，只收窄狀態及version≥1；跨request的加一關係由client／IT／E2E直接比對，body-only schema無法單獨推知。

同key同body先fresh驗目前授權，成功重播原200及業務data，產生本次traceId，不再查今日前態／版本／客戶／完整性來否定原成功，不增加事件、revision或版本。新key對已受理訂單409。原POST／PUT key在後來受理後重播，仍回原DRAFT歷史結果；不得替換為現在ACCEPTED。用戶需明確重新讀取最新狀態。key範圍沿company scope＋actor＋POST＋canonical UUID path＋key；失敗不保存成功結果，D10未核准前不自動清理或換key。

## 新受理與錯誤

新key在同交易鎖定當前有效CRM客戶與fresh persisted完整聚合，驗expectedVersion一致、OPEN＋DRAFT、版本可加一，再執行**原**ORD-P01–P03 checker，必須COMPLETE。不能信任前次check，也不重新取得客戶名稱覆蓋歷史customerName。pending六項D04／D05仍保留，既不是新增全面封鎖，也不是核准例外；不要求pendingPolicyIds為空，不自行增加重量、櫃號、現場聯絡或日期例外。

| HTTP | 原code與資料 |
| --- | --- |
| 400 | VALIDATION_ERROR：協定／shape或完整性；完整性fieldErrors精確取原missingFields，data=null |
| 400 | INVALID_CUSTOMER：當前不存在／停用，`/customerId`／OF04／ORD-P01 |
| 401 | UNAUTHENTICATED |
| 403 | FORBIDDEN、CSRF_INVALID |
| 404 | ORDER_NOT_FOUND |
| 409 | VERSION_CONFLICT：data.actualVersion，`/expectedVersion`／AF01／VERSION |
| 409 | ORDER_NOT_EDITABLE：非OPEN＋DRAFT或safe版本上限；IDEMPOTENCY_KEY_REUSED／IN_PROGRESS、PROTECTED_RELATION沿原語意 |
| 500 | INTERNAL_ERROR，不暴露SQL／request／PII |

全部精確重用原ErrorEnvelope與code enum：code、message、data、traceId、fieldErrors，沒有新ACCEPTANCE_INCOMPLETE或其他模組E04xx。成功轉態、root/core audit、V+1完整revision、不可變event及成功重送同交易；任一步失敗全回滾，子項／任務／依賴不重建。

## UI與原五項相容

只可對已存、乾淨、OPEN＋DRAFT且沒有unknown／conflict／network操作的版本開啟受理確認；顯示單號、客戶與stored version，說明不可直接修改／撤回及不派工，再次明確確認才送。COMPLETE不能自動儲存或受理，過往check只供提示。儲存與受理互斥，unknown固定原id／body／version／key，不背景重試或產生新key；只允明確同次重試或明確離開／捨棄。

未知受理的重試若在replay前遭401／403／CSRF／shape／404等，不能據此宣稱原請求未提交。可證明已過replay查詢的400 INVALID_CUSTOMER、409 VERSION_CONFLICT／ORDER_NOT_EDITABLE／PROTECTED_RELATION才可解除原未知；400 VALIDATION_ERROR僅在非空fieldErrors全為stored-aggregate的 `/type`、`/customerId`、`/items`、`/tasks`、`/shipping`及其子路徑、非request fieldId且非FORMAT ruleId時可解除。協定／header／expectedVersion、IN_PROGRESS／KEY_REUSED仍凍結原次。這耦合後端優先順序，改順序或code需同步review；原草稿save的settlement規則不變。

成功／重播採完整server結果為baseline並立即唯讀。OPEN＋ACCEPTED才可顯示「已受理／待派車」，不加dispatch按鈕或FMS查詢。remote已ACCEPTED／closed時只比較及明確載入，不可把保留本機草稿偽裝成已受理；retain-local／rebase僅remote仍OPEN＋DRAFT時提供。舊列表query／計數不變，不加status filter或在單頁篩選造成錯總數。

原GET/list本來允ACCEPTED，舊PUT新key依OPEN＋DRAFT守衛拒絕；原acceptance-check保持純讀、operationalAcceptance永遠NOT_GRANTED，包含已ACCEPTED訂單，意思是**該check操作不執行受理**。實際狀態看order.acceptanceStatus，不能因此將已受理誤標成尚未授權。沒有派工、占用、撤回、取消、結案或部署入口。
