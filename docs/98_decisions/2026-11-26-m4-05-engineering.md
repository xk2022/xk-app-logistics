# LG-M4-05 管理確認及結案工程採用

狀態：Approved（工程細化）；依已核准Q41–Q47／M4授權。公司基準9213a3d8dacb8774d87c26cb736c4a4315779e95。此文件不核准正式安全確認者、派工資格、照片保存或結案責任。

1. 管理確認與訂單結案為兩個獨立不可變事實。完成確認行政版本0→1，不修改execution／TOM版本、不再次釋放資源；結案從OPEN＋ACCEPTED變真正CLOSED＋ACCEPTED，TOM版本加一。OrderCore沒有version，不新增假版本。原五項訂單API格式及原成功重送快照完整保留。
2. 普通ManagementCompletionPolicy與OrderClosurePolicy均UNCONFIRMED。正向僅test classpath、實際current_database()為xk_logistics_e2e及同公司合成cohort；正式JAR無策略factory。不得藉checkbox、歷史SYNTHETIC事實或行政AVAILABLE推定正式資格。
3. 原requiredTask只有true／null，null表示未指定，沒有可略過決議。命名合成策略要求所有persisted任務真實完成並各自管理確認、整單全部assignment／anchor無有效占用及未解異常。不自動撤派、取消、補造照片、豁免或釋放。不在結案當新客戶選擇重新檢查CRM啟用，保留受理名稱快照。
4. 新九操作採獨立[API契約](../06_api/tom-closure.md)及[OpenAPI](../06_api/tom-closure-openapi.yaml)。新成功company 0000／timestamp；舊TOM OK／traceId不變。ADMIN／DISPATCHER，每次讀／寫／重送核實原Session、現角色與server公司；DRIVER403。
5. 管理確認expectedVersion為行政計數，結案expectedVersion為TOM聚合版本。E2101只帶對應actualConfirmationVersion或actualOrderVersion其中一個，不混execution或generic actualVersion。請求各exact3欄、16,384-byte bounded stream、strict UTF8／duplicate／trailing／unknown拒絕；singlecase-sensitive key、header-only CSRF，保存原reason Unicode／NUL／空白，不trim或NFC。回應no-store/private，成功mandatory replay header。
6. 新V14五表：tms_completion_confirmation／command及tom_order_closure／task／command，最新47表。新fact與自身ledger reciprocal、typed same-source full manifest、core CLOSED／TOM target ACCEPTED版本與完整CLOSED revision關聯；source指不可變受理版，不能指會遞增的current TOM版本。新時點／actor取server，完整revision沿原json字面保存，經::text交Java解碼，避免jsonb或PG text解碼NUL。
7. 舊合法CLOSED可能沒有新audit，保持LEGACY_CLOSED、closure／counts／closedAt為null、history空，不回填actor／時間或拒絕升級。只有新owned witness為RECORDED，不能宣稱全CLOSED都有新稽核。新task review只允OPEN＋ACCEPTED當前事實或RECORDED CLOSED不可變manifest；legacy／DRAFT／CANCELLED回409 E2102，原GET仍可讀實際資料。
8. 新owned結案後封存兩root所有欄位、整個item／task／dependency集合、typed TMS assignment／anchor/current pointer及晚到有效占用／blocking exception。依實際OLD與NEW兩scope與dependency雙端點推導，不信caller orderId；legacy無owned witness不套全域禁止。INSERT可先鎖changed TOM root，UPDATE／DELETE的BEFORE ROW已持tuple鎖，必須sorted roots NOWAIT／try-lock failclosed，不能宣稱trigger字母序改變heap鎖順序。RR舊快照以變更TOM版本root產生serialization failure。
9. Java透過TOM-owned OrderReviewQueries／OrderClosureFacts及TMS-owned typed adapters協作，禁止cross-private SQL及建構cycle；canonical UPMS→TOM root→stable TMS roots，不在TOM後再取FMS。驗證completed保存Source與當前accepted Source完整raw語意一致，不能只比ID／版本／count。
10. 成功ledger在新寫政策／現態之前查詢；歷史manager讀／重送不依當前driver binding、FMS可用性或今天policy重核原決議。損壞stored proof為無SQL cause的安全500，不誤當E2106。只有原POST已過成功ledger MISS的E2101／02／03／07／08／09／10可排除先前未知；GET及key／inprogress／protected／auth／CSRF／transport／schema錯誤均不能證明舊POST未提交。
11. 前端獨立client與管理雙tab，凍結原body／key／來源baseline後才awaitCSRF；核對原source／counter／reason／receipt／replay header才確認。原收據與當前GET分開，先fresh GET才新指令。未知僅同body/key重試，CSRF refresh不自動POST，401／FORBIDDEN清保護資料但不宣稱回滾；busy／dirty／unknown跨頁、tab、登出有guard。無client proof digest／manager照片API／新正式例外政策。

[驗證](../09_testing/m4-05-validation.md)記錄實際結果；本採用文件不代表測試已執行。M4-06／07必須等本包PR與main四項CI全過。


## 首輪實測後的 NUL 相容修正

PG17.12實證：對json取id也會解碼無關reason的`\u0000`，因此不能直接對含原文NUL的完整成功收據作欄位擷取。僅兩份新owned ledger採明確版本的內部codec，reason／displayName為canonical Base64 UTF8、事實仍保原bytea；Java嚴格解碼並比完整原fact／witness／context，對外依舊原文字串。SQL保留typed reciprocal proof，並對encoded原文、actor／time／policy／version比對，不修改任何舊ledger／API／migration。

完整CLOSED revision原JSON保持不變；僅在暫時、固定ASCII識別／狀態metadata檢核投影中，以等長、相同引號／反斜線奇偶性的`\u0000`→`\ufffd`替換避免PG嘗試生成NUL text。此投影只讀id／version／lifecycleStatus／acceptanceStatus四欄，不能把含NUL的非法key／值變成合法UUID／decimal／CLOSED／ACCEPTED；不讀任意reason／orderNo／source、不持久化或回傳、不宣稱全JSON語意相同。原四項SQL檢核、typed FK及Java完整closed snapshot equality全保留，不以hash取代。新增canonicalcodec及投影反例後重新完整驗證。

代理僅兩個精確UUID管理路徑給32k coarse transport allowance，讓16,385-byte請求抵達原16,384-byte bounded reader取得company413；全域16k與原private uploads2m不變。nginx location按path而非method，非契約方法仍由原授權與405限制；不新增操作。兩新client固定請求採redirect:error，不先跟隨轉址後再驗Location。
