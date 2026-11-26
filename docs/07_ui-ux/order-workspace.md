# 訂單工作台｜LG-M2-05

狀態：Draft（本工作包的 UI 工程規格，不新增業務決議）。日期：2026-11-25。基準為 `0766f703848719064c3e1a4488ec18d0a970b7ff` 與公司規範 `5be640ab11811b24706101ea13db73185e9ce4d8`。

沿用[頁面規劃](sitemap.md)、[原 OpenAPI 0.2.0](../06_api/openapi.yaml)、[40 欄字典](../05_database/first-slice-fields.csv)、[角色矩陣](../06_api/order-draft-access-policy.json)、[合成案例](../06_api/order-draft-contract-examples.json)及[TOM 整合說明](../06_api/tom.md)。四份原契約來源保持原樣；原檔中的歷史狀態不代表本輪執行結果。本頁描述應有行為，沒有宣稱尚未執行的 UI 測試通過。

## 角色、頁面與動作

正常登入且已完成強制改密的 ADMIN、DISPATCHER 可進入「訂單工作台」，操作本公司訂單；DRIVER 不顯示入口，也不能藉直接 API 請求取得整單。前端入口不是安全邊界，伺服器仍執行 Session、角色及資料範圍守衛。

工作台包含列表、新增、完整明細／編輯及已儲存版本的只讀完整性結果。列表入口為「新增訂單」「開啟訂單」；表單以「建立草稿」「保存草稿」保存，另有「檢查已保存版本」及讀取伺服器版本比較。M2-05 的這些草稿動作不提供受理、取消、結案、派車、出發、照片、刪除訂單或資源占用。CANCELLED、CLOSED、ACCEPTED 在 M2-05 為保留讀值，M3-03 的獨立受理操作見下節；不可用狀態欄任意轉態。

桌面可用表格及分區表單；手機以可閱讀的卡片或可捲動區域呈現。每個控制項具可見標籤，動態項目／任務具清楚群組名稱，操作後保留合理焦點。載入、空列表、讀取失敗、儲存中、儲存成功、結果不確定及版本衝突須可區別；API 失敗不能呈現假成功。錯誤摘要與欄位提示應可由鍵盤及輔助技術讀取，不能僅以顏色傳達。

## 列表與唯讀資料

列表只呼叫 `GET /api/v1/orders`，合法查詢如下。改變篩選或每頁筆數時回到第一頁，重新整理不隱藏目前的篩選條件。

| 查詢 | 契約 |
| --- | --- |
| page | 從 0 開始的非負整數；預設 0，原 API 沒有額外上界 |
| size | 1–100；預設 20 |
| sort | 僅 `updatedAt,desc`（預設）、`updatedAt,asc`、`orderNo,asc`、`orderNo,desc`；伺服器再以 id 穩定排序 |
| type | 省略或 IMPORT／EXPORT／LOCAL |
| customerId | 省略或完整 UUID；是篩選，不是變更訂單客戶 |

不傳 q、status、owner、company、任意排序欄位、空的篩選值或重複 query。畫面可限制提供的分頁控制形式，不能宣稱 API 有未定義的 page 配額，也不能以不安全數值轉換改寫頁碼。

列表顯示單號、類型、歷史客戶名稱、生命週期、草稿／受理讀值、聚合版本及異動時間；列表回應是 summary，不能當完整草稿保存。開啟一筆訂單須另外 GET 完整明細，成功後才允許編輯。明細 GET 載入失敗時顯示錯誤、重新載入及返回列表，不用 summary 或空白新增表單替代；列表讀取失敗不顯示舊結果假冒成功。401 交既有流程重新登入，403 明示無權限；匿名、DRIVER 與強制改密不因載入失敗而獲得操作權限。

列表的系統 updatedAt 顯示台北時間至分鐘，`time.dateTime` 保留伺服器原字串。這是只讀顯示，不是修改精度；表單中的業務日期時間仍以 raw 字串編輯，保留 offset 與微秒。

伺服器管理且不送回寫入 body 的欄位為訂單 id／orderNo／customerName／lifecycleStatus／acceptanceStatus／version／createdAt／updatedAt／createdBy／updatedBy，項目 id，以及任務 id／taskNo／orderId／itemId／predecessorTaskIds。PUT 另以讀取版本填 `expectedVersion`，不是把 `version` 塞進 draft。

## 草稿欄位

「可空短文字」表示 null 或 1–200 個 Unicode code point、至少有一個非空白字元。可空地址上限 1000，電話上限 50。這些是既有傳輸界線，不是現場作業配額。文字保留使用者原值，不套 CRM 的 trim、NFC 或純空白轉 null；只含空白的短文字應顯示格式錯誤。空值選擇／清除欄位可明確表示未知 null，不能把未知補成假值。

| 訂單欄位 | 字典 ID | 輸入與呈現 |
| --- | --- | --- |
| type | OF03 | IMPORT／EXPORT／LOCAL，建立必填；變更後重新檢視適用完整性提示，不自動清掉原船務或項目資料 |
| customerId | OF04 | 建立必須選有效 CRM 客戶；保存 UUID，名稱快照由伺服器產生 |
| customerReference | OF06 | 可空短文字；不取代系統單號 |
| remark | OF07 | null 或最多 4000 code point；空字串及純空白是有效字串，須與 null 區分 |
| shipping | SF01–SF07 | 可整組 null，或保留一個欄位皆可空的物件；空物件不能默默變整組 null |
| items | IF01–IF10 | 項目陣列，可空；每項須有穩定 clientRef |
| tasks | TF01–TF14 | 任務陣列，可空；每段須有 clientRef 及本次草稿內的 itemRef |

| 船務欄位 | 字典 ID | 輸入 |
| --- | --- | --- |
| shippingCompany | SF01 | 可空短文字 |
| vesselVoyage | SF02 | 可空短文字 |
| pol | SF03 | 可空短文字；不是陸運起點 |
| pod | SF04 | 可空短文字；不是陸運終點 |
| eta | SF05 | 可空、含 offset 的日期時間；不得以 etd 代填 |
| etd | SF06 | 可空、含 offset 的日期時間；不得以 eta 代填 |
| blNo | SF07 | 可空短文字 |

| 項目欄位 | 字典 ID | 輸入 |
| --- | --- | --- |
| clientRef | IF01 對應身份 | 1–64 字 ASCII；首字英數，其餘英數、`_`、`-`，同單項目內唯一 |
| sequence | IF02 | null 或 1–2147483647 整數；有值時同單唯一，不以陣列位置代填 |
| itemKind | IF03 | null／CONTAINER／CARGO |
| containerType | IF04 | 可空短文字；候選櫃型不是核准 enum，不因缺 ADM 選項而拒絕草稿 |
| containerNo | IF05 | 可空短文字；不新增櫃號格式、歷史唯一或提前必填規則 |
| packageQty | IF06 | null 或 1–2147483647 整數；不用 0 代未知 |
| grossWeight | IF07 | null 或非負十進位字串，單位 kg；不使用浮點 |
| cbm | IF08 | null 或非負十進位字串，單位 m³；不使用浮點 |
| lastPickupDate | IF09 | null 或有效 `YYYY-MM-DD` 日期；不轉 UTC 午夜 |
| deliveryCutoffAt | IF10 | 可空、含 offset 的日期時間 |

| 任務欄位 | 字典 ID | 輸入 |
| --- | --- | --- |
| clientRef | TF01 對應身份 | 與項目相同 ref 格式；同單任務內唯一，項目與任務各有自己的命名空間 |
| itemRef | TF04 | 本次完整草稿內的項目 clientRef；選項顯示可辨識項目標籤，值保持穩定 ref |
| taskKind | TF05 | 可空短文字；作業候選名稱不是已核准 enum |
| pickupName | TF06 | 可空短文字 |
| pickupAddress | TF07 | 可空地址文字 |
| deliveryName | TF08 | 可空短文字 |
| deliveryAddress | TF09 | 可空地址文字 |
| siteContact | TF10 | 整組 null 或 `{name,phone}`；name 可空短文字、phone 可空電話文字，保留空物件與整組 null 的差別 |
| plannedStartAt | TF11 | 可空、含 offset 的日期時間 |
| plannedEndAt | TF12 | 可空日期時間；兩者皆有值時，按真正瞬間比較，不得早於開始 |
| predecessorRefs | TF13 | 同單任務 clientRef 陣列，可空、不可重複／失聯／自指／成環；可跨同單不同項目 |
| requiredTask | TF14 | 僅 null（未知）或 true（明確必要）；不提供 false／免除選項 |

新項目／任務可由 UI 產生符合格式的技術 ref，但載入、重新排序、刪除其他列及切換分區時不得重生既有 ref。ref 不作營業流水號。改 ref 在契約中等同移除舊子項並新增身份，不能在一般編輯中暗中發生。

移除項目可能影響任務的 itemRef；移除任務可能影響前置參照。先清楚列出影響，讓使用者調整關聯或明確移除受影響草稿內容，不靜默刪除其他工作。任何仍失聯的圖不得保存。沒有訂單硬刪除 API；表單中的新增／移除先只改本地草稿，送出完整 PUT 才是一次保存。

## 原值、未知值與時間

新增只要求 type＋有效 customerId；零項目／零任務可保存，完整性缺漏不應變成草稿存檔必填。PUT 的所有可編輯欄位由完整明細建立並白名單序列化；省略可選純量會清成 null、省略集合會清成空。收合、隱藏或不適用欄位仍須保留讀回值，不能以畫面是否顯示決定刪除。

十進位欄位全程保留字串，最多 32 字元，格式為 `(0|[1-9][0-9]*)(\.[0-9]+)?`；不接受負數、指數、前置零或空字串。`"0"` 是已知零、null 是未知；不得 `Number()`／`parseFloat()`／四捨五入再輸出。成功讀回可由後端去尾零，UI 不承諾保留顯示小數位數。數量及序號是精確整數；version 在 0–9007199254740991，不得截斷或自動遞增後送出。

日期時間必須保留合法 RFC3339 wire 字串：四位年份、有效日曆、秒 00–59、小數秒最多六位、`Z` 或明確 offset（小時 00–23、分鐘 00–59，含 ±23:59）。不接受無 offset、閏秒或無聲截斷小數。原值不經 JavaScript Date 或 datetime-local 自動往返；若使用方便選時器，須另有保留微秒與 offset 的表示，不能縮限原 API 可編輯值。台北時間可作輔助顯示，不能把轉換後字面覆寫尚未變更的欄位。日期欄仍保存日期語意。

原契約允許的 U+0000 與其他字串原值不能被表單靜默刪除；不成對 surrogate 則明確拒絕。若原生輸入元件不能無損呈現既有字元，須保留模型原值並明示，不能讀取後未改欄位卻在保存時損失資料。React 以文字呈現資料，不當 HTML 執行。

## 客戶歷史與完整性

新增及明確換客戶使用 CRM 的有效客戶選項；UI 顯示選項載入／失敗狀態，不用合成客戶替代。編輯既有同 customerId 時保留歷史 customerName，即使主檔改名或停用；不能因該 ID 不在今日有效選項中就清掉選取或強制換客戶。換成另一客戶才由伺服器取得新名稱快照。

完整性面板只對已保存版本呼叫 acceptance-check，顯示回傳 version；未保存編輯不能冒充受檢資料。保存或載入新版本後清除舊結果；晚到結果若版本不同則明示警告。當本機 dirty 或受檢版本與基準不同，仍可讀缺漏說明，但停用舊 JSON Pointer 的欄位跳轉，避免索引對到已移動的其他項目／任務。ORD-P01 為類型與目前客戶引用資格；P02 為項目、序號、標的、必要任務與路線／圖；P03 為進出口船務及對應櫃運期限，精確規則沿[TOM 說明](../06_api/first-slice.md)。

缺漏以 message、JSON Pointer 與字典 ID 對應欄位；集合缺口可用 ORD-P02。顯示 COMPLETE／INCOMPLETE 的繁中說明、三個 checkedRules、全部 pendingPolicyIds 與「檢核本身不授予受理權限（NOT_GRANTED），實際狀態以訂單為準」。COMPLETE 不會自動執行受理；M2-05 未提供受理按鈕，M3-03 的明確操作見下節。聯絡、字典、重量、櫃號、期限例外與必要義務免除仍待核准，不自行增加條件。

## 保存、重送與錯誤

寫入沿同源 HTTPS Session＋CSRF；Cookie 由瀏覽器處理，不由 JavaScript 讀取 HttpOnly Cookie。TOM 成功為 `OK/message/data/traceId`，錯誤另有 fieldErrors；不可使用要求 `0000/timestamp` 的 UPMS envelope parser。400 欄位路徑以本次送出的陣列索引定位；未匹配路徑仍列在摘要，不悄悄丟棄。跳轉先找可用的精確欄位，否則沿 JSON Pointer 回退到最近可聚焦父區塊，最後回表單；先展開關閉的 details 再聚焦／捲動，不為定位自行新增 shipping／siteContact、改變 null 或修改草稿。

每次確定的新保存建立一個 key 與不可變的 method／path／body 快照。傳送中禁用重複提交及影響該快照的編輯。逾時、斷線、無法解讀成功回應及寫入 HTTP 500 均保守視為結果不確定，保留原 key 與原 body，讓使用者明確重試同一次操作；不能自動換 key 造成第二張訂單或第二次加版，也不能帶修改後資料沿用舊 key。僅保留於當前已登入工作空間的記憶體，不把草稿、憑證或 token 存入 localStorage、URL、log 或測試報告。

建立成功後維持同一個 Editor，由其採用伺服器回應為內部 baseline，保留當次成功／重播提示；後續保存使用該訂單 ID 與版本發 PUT。不因 Manager 切換 view／key 重新掛載，導致建立結果提示或內部版本被清空。返回列表後再開啟時仍重新 GET 完整明細。

每次新保存／重試先清除舊 remote comparison 與確認框。busy、pending 或 reading 期間，版本比較的讀取、載入及保留本機內容處理有停用與事件守衛，不能以舊比較結果清掉未決重送快照。衝突時先讀取遠端、比較雙方完整草稿，再二次確認「捨棄本機」或「保留本機草稿，使用此版本繼續」；後者只更新下一次保存的基準，不自動發送，並提示完整替換可能覆寫遠端修改／移除遠端新增子項。

一旦先前寫入結果不確定，後續重試若遇 CSRF 403、取得 CSRF 前置失敗、VALIDATION_ERROR、ORDER_NOT_FOUND 或另一個 500，仍保留「前一次結果未決」及原 key/body；本次未通過不代表先前沒有成功。除了成功回應（新成功或重播）與 401／FORBIDDEN 安全清除，目前僅 `400 INVALID_CUSTOMER`、`409 VERSION_CONFLICT`、`409 ORDER_NOT_EDITABLE`、`409 PROTECTED_RELATION` 可解除這個未決狀態。這個白名單依賴現行 TomService 先查成功重送紀錄、沒有命中後才產生上述四碼，且沒有自動清除 key 的期限；後端若改處理順序、碼的用途或保存政策，前後端必須一起重新覆核，不能只憑 HTTP 4xx 放開新保存。解除後的版本衝突仍須人工比較／確認，不自動覆寫。

| 結果 | 畫面下一步 |
| --- | --- |
| 新成功／成功重播 | 採伺服器回應，顯示已保存版本；重播可為舊快照，另有明確「重新載入最新資料」動作，不把原快照當最新保證 |
| 400 VALIDATION_ERROR／INVALID_CUSTOMER | 保留輸入，顯示摘要與欄位問題；只有沒有前次未決狀態或符合上述解除規則時，才可修正後作新的保存快照 |
| 401 UNAUTHENTICATED | 清除訂單與未決記憶體狀態，交既有流程重新登入；不把前一帳號結果帶給下一帳號 |
| 403 CSRF_INVALID／FORBIDDEN | 明示安全驗證或權限問題；CSRF 可重新取得但不自動重播，也不能解除前次未決寫入；FORBIDDEN 走安全清除流程 |
| 404 ORDER_NOT_FOUND | 顯示查無可存取訂單，不以 fallback 假資料填表；不據此解除前次未決寫入 |
| 409 VERSION_CONFLICT | 保留本地內容；讀取與比較遠端後二次確認載入，或明確保留本機內容採用讀取版本再決定保存，不自動以 actualVersion 覆蓋並再送 |
| 409 IDEMPOTENCY_IN_PROGRESS | 保留相同快照與 key，提供稍後明確重試；不生成新 key |
| 409 IDEMPOTENCY_KEY_REUSED | 停止這次重送並說明內容不一致，不默默換 key 掩蓋錯誤 |
| 409 ORDER_NOT_EDITABLE／PROTECTED_RELATION | 顯示原因並重新確認最新狀態；不移除受保護資料或自動改成其他操作 |
| 500 INTERNAL_ERROR／連線不確定 | 顯示可操作訊息及 traceId（若有）；不顯示內部細節，保留同次重試所需快照 |

離開有未保存內容或未決寫入的表單前，應先讓使用者理解將失去的內容；不得悄悄重送或宣稱伺服器已取消。未決狀態若因離開／重新整理而丟失，必須先查最新列表／明細確認結果再決定新保存，不能假設未建立。

## M3-03 明確正式受理

ADMIN／DISPATCHER 對 clean 已保存 OPEN＋DRAFT 可進入兩段確認，顯示單號、客戶、受理前版本及受理後不可編輯的說明；不隱式保存，也不以 COMPLETE 或 pendingPolicyIds 作轉態憑證。伺服器重驗目前 ORD-P01–P03 與有效客戶，缺項以原 JSON Pointer／fieldId 定位。

HTTP 200／成功重播後必須採用完整 accepted Order 為 Editor 基準，明細唯讀、列表與明細顯示待派車；不新增狀態查詢參數、頁內假分頁或派車操作。事件人員／時間留在後端，不用登入名稱或 updatedAt 冒充事件欄位。

保存與受理互斥；未知結果固定原 key／body，只能明確重試同一次。新的 CSRF／格式拒絕不能當成先前未知受理已取消；只有契約明確在 replay 查詢後產生的拒絕能解除不確定狀態。網路活動中連已開啟的離開提示也不可丟棄，成功後清除過期提示。遠端非 OPEN＋DRAFT 時不可保留本機草稿重新基底，只能比較並明確載入真正的伺服器資料。契約與實測見 [正式受理 API](../06_api/tom-acceptance.md)及 [M3-03 驗證](../09_testing/m3-03-validation.md)。
