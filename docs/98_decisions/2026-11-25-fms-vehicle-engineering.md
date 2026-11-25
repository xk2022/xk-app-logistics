# LG-M3-01 FMS 車輛工程決策

日期：2026-11-25。基準為[PR #11](https://github.com/xk2022/xk-app-logistics/pull/11) 合併 `82bc20c58ec656cc98485a8bfe3b643ca1bc3f05` 與[核准 Q21–Q25](2026-11-25-fms-vehicle.md)；公司最新規範固定 `5be640ab11811b24706101ea13db73185e9ce4d8`。本頁是授權工作包內的工程細化，不宣稱產品／CI／部署已驗證。規格見[獨立 OpenAPI](../06_api/fms-vehicles-openapi.yaml)、[API 說明](../06_api/fms-vehicles.md)與[模型](../05_database/fms-vehicle-model.md)。

遵循公司 [APP 開發](https://github.com/xk2022/xplore-kaleidoscope/blob/5be640ab11811b24706101ea13db73185e9ce4d8/doc/04_engineering/app-development-standard.md)、[API 與資料](https://github.com/xk2022/xplore-kaleidoscope/blob/5be640ab11811b24706101ea13db73185e9ce4d8/doc/04_engineering/api-and-data.md)、[開發流程](https://github.com/xk2022/xplore-kaleidoscope/blob/5be640ab11811b24706101ea13db73185e9ce4d8/doc/04_engineering/development-workflow.md)及[APP 測試](https://github.com/xk2022/xplore-kaleidoscope/blob/5be640ab11811b24706101ea13db73185e9ce4d8/doc/04_engineering/app-testing-standard.md)。不改原四份訂單來源、其他 M2 契約或已套用 V1–V5。

## 主檔行政狀態與後續占用分離

Q23 的可用／使用中／維修／停用業務顯示方向保留。此包只寫 AVAILABLE／MAINTENANCE／INACTIVE 三個行政狀態；「使用中」必須由後續 TMS 真正有效占用推導，不讓使用者在主檔手動設 IN_USE，也不建立假派工來展示狀態。

`/vehicle-options` 只過濾 AVAILABLE，不因停用車種、空車籍或檢驗日期增加排除規則；回 vehicleTypeEnabled 供呼叫者辨識目前分類。可信服務也只名為 requireAdministrativelyAvailable，不聲稱資源未被占用、法規合格或真正可派工，不產生 reservation。這避免把尚未核准的派工政策隱藏在主檔選項。

## 車種識別與 ADM 邊界

vehicleType 使用既有 ADM item UUID，避免以可顯示 label 或示意代碼當穩定身份。原 VEHICLE_TYPE 父分類正常存在但無項目，這是合法空狀態；ADMIN 使用既有 ADM 配置經確認車種，DISPATCHER 在 FMS 窄端點讀固定分類選項。不得自行把 TRACTOR／TRAILER／TRUCK 寫成正式 seed 或核准 enum，也不擴大 DISPATCHER 讀寫其他 ADM 分類的 HTTP 權限。

新建／換車種必須類別正確且父子皆啟用；同一已存 UUID 即使停用，仍可維護原車輛。車輛 DTO 顯示 category-bound 的目前 code／label／enabled metadata，停用不遺失身份；這不是保存過往派工快照。ADM 最小公開擴充保留 repository 所有權，歷史查詢與鎖定新引用守衛分開，不以查到 label 推定可新增引用。

寫入鎖序為 UPMS canonical→fresh actor→PATCH fresh vehicle/version→必要的 ADM category→item。新引用在同一可寫交易持有鎖直到提交，與分類停用序列化。未來呼叫 VehicleQueries 的模組同樣先完成自己的授權及 canonical 鎖，再取得 fresh vehicle 來源；該內部介面不替 HTTP 用戶授權，無可寫 caller transaction 時拒絕。

## canonical 車牌與精確值

車牌採 NFC、去除 Unicode 邊界空白（isWhitespace／isSpaceChar／U+FEFF）、Locale.ROOT 大寫、再 NFC，最後驗1–40 code points及非空白，拒控制字元／不成對 surrogate。保留內部空白與連字號，不訂國別 regex。輸入 schema 用明確 canonical 長度描述與 x-normalizedMin/MaxLength，不以 raw maxLength40 錯拒可經正規化合法的輸入。回應是 canonical 值，DB 全表唯一包含 INACTIVE；版本化車牌更正不更換 UUID。

capacityTon 用 nullable、最多32字元的非負 plain decimal 字串與 BigDecimal／不定 scale 的 numeric，避免浮點與固定 scale 捨入。0是已知零、null未知；沒有按車頭／拖板／貨車猜載重上限。registrationNo 可空，NFC／同樣 strip後最多100 code points，空白轉null；不猜國別或車籍必填例外。日期採可空 Gregorian YYYY-MM-DD（0001–9999），不轉 UTC；兩個 FMS 欄位局部標註 `@JdbcTypeCode(SqlTypes.LOCAL_DATE)` 走 JDBC 4.2，不改 M2 全域日期映射，並要求真 PostgreSQL date::text 驗證1582年前資料，不能以舊 GregorianCalendar 對稱讀寫掩蓋日期位移。

V6 的 numeric CHECK 同時拒絕 NaN／正負 Infinity、負值及 text表示超過32字元，讓可信 SQL 寫入也不能產生無法依既有 DTO讀回的值。這是已定 wire32字元與有限非負數的 DB 對齊，沒有建立物理／法規載重上限或固定 scale。

檢驗日期只記錄與自然日提示，不產生法定週期、證照適用性、未來日期／先後限制、逾期阻擋或7／30天預警政策。不能把可記錄日期當成合規驗證，也不依日期自動改行政狀態。

## HTTP、更新與驗收責任

六項 API 沿單公司正常 ADMIN／DISPATCHER、既有 HTTPS HttpSession＋CSRF 及 `0000/message/data/timestamp(+08:00)`。DRIVER 全部拒絕，本人指派摘要等待 TMS 授權。POST 只必填 plateNo／vehicleType，預設 AVAILABLE；PATCH 是 version＋至少一個 mutable 的真正 partial：省略保留、nullable null清除，未知或衍生欄拒絕。達 safe-integer version上限不能再遞增。沒有 DELETE，不建立額外業務歷程、司機或占用表。

每次成功 PATCH 都加一版並保存當次可變值及 audit，即使同值或只有 decimal scale改變。updatedAt 採 max(clock.now, previous+1micro)確保持久化更新，不以 BigDecimal 數值相同或固定測試時鐘略過新保存；此為交易／樂觀鎖工程語意，不加入現場業務狀態或假歷史紀錄。

列表與 options 固定 plateNo／id 升序，不開任意 sort。q 先做 NFC／Unicode strip（含FEFF）及100 code point界限；車牌分支另依 NFC／Locale.ROOT大寫／NFC 後作字面LIKE，讓原輸入如 ß 與 canonical SS 一致，不把查詢套入車牌40字驗證。車籍分支用 DB lower(field) 與 DB lower(raw pattern) 的同一大小寫規則；兩分支都將 %／_／反斜線視為字面，不能變成 wildcard。409 車牌重複以 plateNo 文字 map定位，版本衝突／上限用 version 文字 map；一般DB競爭衝突可為null，不加入訂單專屬 actualVersion／traceId。

V6 只新增 fms_vehicle，正常無車種／車輛／帳號 seed，測試合成分類與資料只進隔離環境。測試須涵蓋 V5 升級及原資料保留、空庫、真實唯一競爭／版本／鎖定與 ADM 停用競爭、精確小數與舊自然日、三角色、partial/null、可用選項與實際占用分離、手機／桌面主要流程及失敗回饋。實際命令、數量、受測 SHA、CI、PR 與剩餘風險由本包驗證紀錄提供，未執行不標通過；正式部署另行交辦。
