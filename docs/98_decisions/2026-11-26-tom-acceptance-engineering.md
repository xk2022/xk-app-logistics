# LG-M3-03 TOM 正式受理工程決策

日期：2026-11-26。依[Q31–Q35](2026-11-26-tom-acceptance.md)與[PR #16](https://github.com/xk2022/xk-app-logistics/pull/16) 合併 `cf5ec0796d6eff2694eed1c5cb5a922bd1a63d39`。公司最新規範固定 `5be640ab11811b24706101ea13db73185e9ce4d8`；沿既有版本／依賴，四原契約、V1–V7及舊validators不變。這是授權範圍內的工程細化，不是已執行測試或部署證據。見[新API](../06_api/tom-acceptance.md)、[獨立OpenAPI](../06_api/tom-acceptance-openapi.yaml)與[模型](../05_database/tom-acceptance-model.md)。

遵循公司[APP開發](https://github.com/xk2022/xplore-kaleidoscope/blob/5be640ab11811b24706101ea13db73185e9ce4d8/doc/04_engineering/app-development-standard.md)、[API與資料](https://github.com/xk2022/xplore-kaleidoscope/blob/5be640ab11811b24706101ea13db73185e9ce4d8/doc/04_engineering/api-and-data.md)、[流程](https://github.com/xk2022/xplore-kaleidoscope/blob/5be640ab11811b24706101ea13db73185e9ce4d8/doc/04_engineering/development-workflow.md)及[測試](https://github.com/xk2022/xplore-kaleidoscope/blob/5be640ab11811b24706101ea13db73185e9ce4d8/doc/04_engineering/app-testing-standard.md)。保留TOM原回應的專案特例，不擴派工、資源占用、車／司機配對、撤回、取消、結案、影像或正式部署，不使用真實資料。

## 單一新操作，原契約精確相容

只新增POST /api/v1/orders/{orderId}/accept，正常ADMIN／DISPATCHER本公司範圍；Session／CSRF、雙Cookie單HttpSession、安全撤銷及DRIVER拒絕不變。body只expectedVersion，沿原safe數學整數及strict JSON／UTF-8／hash；無query，恰一Idempotency-Key。GET同accept路徑仍沿原授權後404，不為方便擴成all-method操作或新增metadata GET。

新success HTTP200，精確原OrderEnvelope，allOf只收窄OPEN／ACCEPTED／version≥1，Idempotency-Replayed必為false／true文字。version=原expectedVersion+1由client／IT／E2E跨請求比對；不使用非標準JSON Schema $data，body-onlyartifact不假稱可驗此關係。acceptedAt／actor／event只存DB，不加進舊OrderDraftRead／list或新success；Q35要求保存，沒有授權額外事件查詢。UI不能以updatedAt或本機時鐘臆造受理事件。

新error完全重用原ErrorEnvelope與enum，不增ACCEPTANCE_INCOMPLETE、E04xx、timestamp。完整性400 VALIDATION_ERROR的fieldErrors deterministic沿原checker missingFields；無效customer400 INVALID_CUSTOMER。前態／maxversion409 ORDER_NOT_EDITABLE，版本409 VERSION_CONFLICT含actualVersion；重送／FK／security錯誤沿原碼。舊GET/list本來能讀ACCEPTED，POST仍只建DRAFT、PUT新key仍限OPEN＋DRAFT，不改原四來源或錯誤格式。

## 只讀check與真正受理分離

AcceptanceChecker原ORD-P01–P03與六pending ID保持原值。COMPLETE不是自動受理；新操作在同交易fresh persisted aggregate重新查核，不能信任早先check。pending IDs非空不是新全面封鎖，也不等於核准D04／D05例外，不擅自必填重量／櫃號／聯絡、造正式字典或期限豁免。

原acceptance-check對已受理訂單仍純讀、operationalAcceptance=NOT_GRANTED，意思是check本身不授予轉態，不否認order.acceptanceStatus=ACCEPTED。UI清楚區分此值與真正訂單狀態；只有OPEN＋ACCEPTED才顯示已受理／待派車，不建dispatch按鈕或FMS資格查詢。

## 原子事件與來源鎖序

嚴格shape／hash／key後沿advisory try-key-lock與原success replay lookup，先於canonical等待，IN_PROGRESS有界拒絕。新key再canonical＋fresh manager actor→觀察scoped customerId→CRM公開有效source lock→fresh order_core／tom_order lock→核對customerId仍相同、expectedVersion、前態與上限→原checker COMPLETE。CRM僅驗eligibility，絕不重取name覆寫customerName；可信SQL若改source identity便fail closed409。客戶停用／改名、PUT、角色／復原／撤銷、雙受理須在source／canonical／root鎖下序列化，避免L1舊資料與查完後競爭。

只更新root acceptanceStatus、明確version+1及root/core audit，不重建children，不改UUID／taskNo／clientRef／predecessor／陣列／文字／decimal／日期／null presence或created audit。server Clock截微秒，acceptedAt=root.updatedAt；native+1在fixed Clock仍進一版。當次完整受理後snapshot同時存原revision V+1及新event，最後remember200，全transaction任一步失敗全回滾。

V8唯一新表order_acceptance_event，全庫22表含Flyway；order_id唯一、from/to safe且to=from+1、actor FK、server acceptedAt／request trace及完整JSON snapshot，composite FK對應先插入的revision。BEFORE UPDATE／DELETE immutable trigger，無修改／刪除API、無假歷史回填／seed。JSON而非jsonb保U+0000，避免會decode NUL的DB JSON extraction；完整snapshot由application／tests驗證。D10期限未核准，不排程清理，也不聲稱永久保存政策已准。

## 重播與未知結果不可猜測

相同key同內容在fresh目前授權後重播原200data與本次traceId，不先驗今日客戶、版本、前態或完整性，且無event／revision／版次副作用。新key對已受理409。舊POST／PUT原key在受理後仍重播歷史DRAFT，不改成最新accepted；UI提示可明確重讀，不偷偷篡改API。

受理只能從persisted clean OPEN＋DRAFT開始，與save／unknown／conflict／network操作互斥；二次明確確認，不auto-save或COMPLETE自動受理。unknown凍結原id/body/version/key，不自動重送或換key，先保本機，只允明確同次retry或明確離開／捨棄。重試若401／403／CSRF／format／404仍不能證明原次未提交，安全退出也不宣稱未受理。

只有後端replay後的INVALID_CUSTOMER400、VERSION_CONFLICT／ORDER_NOT_EDITABLE／PROTECTED_RELATION409可明確解除prior unknown。VALIDATION_ERROR400須非空fieldErrors全部定位stored aggregate的type／customerId／items／tasks／shipping（及子路徑）、非request fieldId／FORMAT ruleId才可解除；header／shape／expectedVersion仍不能。IN_PROGRESS／KEY_REUSED維持原次。此settlement依賴後端優先序，變更code／path／順序需同步review前端與測試；原save規則不改。

成功完整adopt server baseline並唯讀；remote ACCEPTED／closed只能比較／明確載入，不能用保留本機內容讓畫面假裝已受理。retain-local／rebase僅remote OPEN＋DRAFT。busy包括已開的離開確認，操作中不可捨棄；成功清舊dirty提示。列表filters/count保持原樣，不以單頁篩掉DRAFT造錯總數。

## 獨立驗收與保留來源

新validator只讀增量OpenAPI及hash守衛原四來源，原openapi.yaml僅exact allowlist的in-memory資源，任意其他relative／HTTP／file／resourceID不得取回。使用不變hash-pinned test-only套件及Decimal數學整數，不改舊order／vehicle／driver validators。accepted GET／list／check／PUT錯誤繼續進原artifact由舊validator驗；accept body-only acceptance-contract-responses.json獨立驗，missing／empty必fail。靜態schema不能證明source locks、ORD完整性或真HTTP已跑。

測試要求三type完整受理、缺欄／停用客戶、版本／前態／max、same/different key／actor、PUT與受理並發、source lock兩方向、stale actor、fixed Clock、NUL／decimal／日期／null／refs完整snapshot、event／revision／replay全回滾及event不可變。真PG空庫／V7→V8保原20domain表fingerprint；原280backend／330frontend／22E2E與原契約不削弱，新desktop/mobile另兩案。實際數量、受測SHA、CI與交付只由驗證紀錄回報，不以本頁當結果。
