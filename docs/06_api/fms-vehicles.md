# FMS 車輛 API｜LG-M3-01

依[Q21–Q25](../98_decisions/2026-11-25-fms-vehicle.md)及[工程決策](../98_decisions/2026-11-25-fms-vehicle-engineering.md)細化；機器契約為獨立 [FMS OpenAPI 0.1.0](fms-vehicles-openapi.yaml)。原訂單、UPMS、ADM、CRM 契約保持原樣。此頁是規格，不代替實際測試證據。

Base `/api/v1`，單公司、同源 HTTPS Session＋CSRF；僅正常登入且已完成強制改密的 ADMIN、DISPATCHER 可操作。DRIVER 拒絕全部 FMS 路徑，也沒有本人指派摘要 API。沿既有 `0000/message/data/timestamp`（+08:00）成功 envelope，錯誤沿 E04xx／E05xx 與可顯示繁中訊息，不套訂單 `OK/traceId` 格式。

| 方法／路徑 | 正常回應 data | 行為 |
| --- | --- | --- |
| GET /fms/vehicles | PageVehicle | 分頁列表、篩選及固定穩定排序 |
| POST /fms/vehicles | Vehicle，201 | plateNo＋vehicleType 必填；其餘可省略，administrativeStatus 預設 AVAILABLE |
| GET /fms/vehicles/{id} | Vehicle | 包含全行政狀態及目前車種 metadata 的完整明細 |
| PATCH /fms/vehicles/{id} | Vehicle | version＋至少一個可變欄位，真正部分更新 |
| GET /fms/vehicle-types | VehicleType[] | 僅 VEHICLE_TYPE 的有效選項；無 query，正常空目錄回 [] |
| GET /fms/vehicle-options | PageVehicleOption | 僅行政 AVAILABLE 選項；不是可派工／法規／占用資格 |

POST／PATCH 都需有效 X-CSRF-TOKEN；不存在 DELETE 或 IN_USE 寫入。除列表與 options 列出的 query，其餘操作不接受 query。未知／重複 query、未知 body 欄位、系統身份及非法 enum 均拒絕，不自動忽略。未實作操作的框架錯誤不作額外業務 API。

## 精確 DTO 與查詢

Vehicle 完整欄位：`id, plateNo, vehicleType, vehicleTypeCode, vehicleTypeLabel, vehicleTypeEnabled, capacityTon, registrationNo, registrationDate, inspectionDueDate, administrativeStatus, version, createdAt, createdBy, updatedAt, updatedBy`。

VehicleOption 完整欄位：`id, plateNo, vehicleType, vehicleTypeCode, vehicleTypeLabel, vehicleTypeEnabled, capacityTon, version`。VehicleType 為 `{id, code, label}`。`vehicleType` 本身是 ADM item UUID，欄位不叫 vehicleTypeId；code／label／enabled 是伺服器衍生的目前分類資訊，不能冒用寫入，也不代表已保存派工歷史快照。

PageVehicle／PageVehicleOption 均為 `{content, page, size, totalElements, totalPages}`。

| 查詢 | vehicles | vehicle-options |
| --- | --- | --- |
| page | 整數 0–100000，預設 0 | 相同 |
| size | 整數 1–100，預設 20 | 相同 |
| q | NFC＋Unicode 邊界 strip（含FEFF）後最多100 code points；plateNo 分支再依 NFC／upperROOT／NFC 轉換作 literal LIKE，不套車牌40字上限；registrationNo 以 DB lower(field) 對 DB lower(raw pattern)，%／_／反斜線皆 literal | 相同 |
| vehicleType | 可省略的完整 ADM item UUID | 相同 |
| administrativeStatus | AVAILABLE／MAINTENANCE／INACTIVE，可省略 | 不接受；固定只選 AVAILABLE |

兩個列表固定 `plateNo ASC, id ASC`，不接受 sort、enabled、owner、driver、occupancy 或其他額外查詢。options 不因 inspectionDueDate、車籍空值或停用的車種分類增加排除條件；即使 `vehicleTypeEnabled=false`，AVAILABLE 車輛仍可列入 options。呼叫者不能把這個選項當成已核准派工。

## 輸入與部分更新

可變欄位僅 `plateNo, vehicleType, capacityTon, registrationNo, registrationDate, inspectionDueDate, administrativeStatus`；PATCH 另有必填 version，單送 version 不合法。省略的可變欄位保留原值；只有 capacityTon、registrationNo、registrationDate、inspectionDueDate 可用 null 清除。車牌／車種／狀態不可 null。version 為 0–9007199254740991，舊版或無法再遞增時回 409，不截斷或溢位。

新增 version=0；每次成功 PATCH 更新伺服器 audit 並加一版，即使可變值相同或只改十進位字串的 scale，也不可靜默略過保存。前端採用回應版本，不自行猜測遞增後重送。

| 欄位 | 工程契約 |
| --- | --- |
| plateNo | NFC→Unicode 邊界空白 strip（isWhitespace／isSpaceChar／U+FEFF）→Locale.ROOT 大寫→NFC；canonical 後1–40 code points且非空白，拒控制字元與不成對 surrogate。保留內部空白及連字號，不定國別 regex；輸入長度限制以正規化後計，不以 raw maxLength40 錯拒合法 padding／NFD。全表唯一，包含 INACTIVE。版本化更正車牌不改 UUID。 |
| vehicleType | 新增或明確換值須屬 VEHICLE_TYPE 且父分類／項目皆啟用；同一舊值即使已停用仍可維護其他車輛欄位。 |
| capacityTon | null 或最多32字元的非負 plain decimal 字串，格式 `(0|[1-9][0-9]*)(\.[0-9]+)?`。不接受 JSON number、負號、前置零、指數或浮點轉換；0是已知零、null未知，不猜車型上限，不以固定 scale 截斷；讀回以 toPlainString 保留 scale 與全部 digits。 |
| registrationNo | 可空文字，NFC＋同樣 Unicode strip 後最多100 code points；空白結果為 null，拒控制字元與不成對 surrogate，不加國別格式或特殊必填。 |
| registrationDate／inspectionDueDate | 可空有效 Gregorian YYYY-MM-DD、年份0001–9999；自然日不轉 UTC。不新增必填、日期先後、禁止未來日期或派工阻擋規則。 |
| administrativeStatus | 僅 AVAILABLE／MAINTENANCE／INACTIVE；IN_USE 由後續 TMS 有效占用推導，本包不產生。 |

原 V3 VEHICLE_TYPE 正常為空；ADMIN 使用既有 ADM 管理功能配置經確認的分類，DISPATCHER 只經 FMS 窄端點讀有效車種，空目錄時明示請管理員配置。不種入 TRACTOR／TRAILER／TRUCK 等正式碼，也不擴大其他 ADM HTTP 權限。車種停用不刪車輛；明細以 category-bound 歷史查詢回其 UUID／code／label，enabled 同時考慮父分類與項目。

## 錯誤與範圍

| HTTP | code／處理 |
| --- | --- |
| 400 | E0400：格式、未知欄位／query、車種無效與欄位 map；保留輸入供修正 |
| 401／403 | E0401／E0403：Session／角色／強制改密／HTTPS／CSRF；不顯示假成功或自動重送 |
| 404 | E0404：授權範圍內查無車輛 |
| 409 | E0409：canonical 車牌重複 data 為 plateNo 文字 map，版本衝突／上限為 version 文字 map，一般 DB 競爭衝突可為 null；重新讀取／明確確認，不自動覆寫 |
| 500／503 | E0500／E0503：安全摘要與服務不可用，不傳內部 SQL／堆疊 |

車籍與檢驗日期只作紀錄與提示；日期已過不能自動轉維修／停用，不宣稱違法或不可出車，也不自訂「即將到期」7／30天門檻。沒有司機、配對、預約、占用、釋放、正式受理或部署；可信跨模組引用服務的交易界線見[模型](../05_database/fms-vehicle-model.md)。
