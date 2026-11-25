# FMS 車輛模型｜LG-M3-01

依[已核准 Q21–Q25](../98_decisions/2026-11-25-fms-vehicle.md)、[獨立 API](../06_api/fms-vehicles.md)與[工程決策](../98_decisions/2026-11-25-fms-vehicle-engineering.md)細化。V6 只新增 `logistics.fms_vehicle`，保留 V1–V5；含 Flyway 歷程表共20表。不加司機、配對、派工、占用、預約、檢驗規則或額外歷程表。本文件不宣稱 DDL／交易測試已通過。

| 邏輯／儲存欄位 | 責任與完整性 |
| --- | --- |
| id | 伺服器 UUID 主鍵，車牌更正不變 |
| plateNo／plate_no | canonical 車牌、全表 UNIQUE，包含 INACTIVE；正規化後1–40 code points，不加國別 regex或移除內部空白／連字號 |
| vehicleType／vehicle_type_id | ADM item UUID FK；服務限制 VEHICLE_TYPE 分類及新引用時父子皆有效，FK不代替分類與啟用檢查 |
| capacityTon／capacity_ton | nullable、有限非負 BigDecimal／無固定精度 scale 的 PostgreSQL numeric；DB 同時限制其 text表示最多32字元以保持既定wire界線，不截斷、捨入或用0代未知 |
| registrationNo／registration_no | nullable、NFC＋Unicode strip 後最多100 code points，空白轉null，不加國別格式 |
| registrationDate／registration_date | nullable Gregorian 自然日，0001–9999，不混入時區 |
| inspectionDueDate／inspection_due_date | 相同自然日；只記錄提示，不據日期判定派工或法規資格 |
| administrativeStatus／administrative_status | AVAILABLE／MAINTENANCE／INACTIVE；不儲存人工 IN_USE |
| version | JPA @Version 樂觀鎖，HTTP 0–9007199254740991，拒絕舊版本及上限遞增 |
| created_at／updated_at／created_by／updated_by | 伺服器時間與 UPMS actor UUID，建立／異動責任欄，不接受客戶端冒用 |

canonical 車牌為 NFC→Unicode 邊界 strip（Character.isWhitespace／isSpaceChar／U+FEFF）→Locale.ROOT 大寫→NFC；按最終 code point 數驗長度、非空白、控制字元與 Unicode 有效性。DB 唯一約束保護競爭新增／更正；重複不因停用而容許復用。不匯入正式車籍或測試種子。

每次成功 PATCH 明確更新 audit 並加一版。updatedAt 取伺服器目前時間與前值加一微秒的較晚者，避免同時鐘讀值使同值保存被 JPA 略過，也讓只改 BigDecimal scale 的新值真正保存；不由前端傳送系統時間或版本增量。

vehicleTypeCode／vehicleTypeLabel／vehicleTypeEnabled 不由用戶端或車輛資料列任意覆寫，經 ADM 的 category-bound 歷史查詢取得。它們是目前分類 metadata，並非當年時點的派工快照；label 可隨 ADM 維護顯示，穩定身份是 item UUID。停用父分類或項目時 metadata.enabled=false，但舊車輛仍可讀及維護；只有新增／明確換車種重新要求啟用。

## 交易與模組界線

FMS 寫入先透過 UPMS 公開 facade 取得 canonical 協調鎖、鎖後重新載入操作者並驗認證；PATCH 再鎖後 refresh 車輛及比對 version。只有新增／換車種時呼叫 ADM 的可寫 MANDATORY 新引用守衛，依 category→item 順序鎖後 refresh，核對分類、父子啟用並持鎖至同一交易提交，與 ADM 停用／改名序列化。

ADM repository 保留在 ADM 內；FMS 不直接讀寫其 repository。公開 `findItem(categoryCode,itemId)` 回包含停用資料的不可變 ItemSnapshot（id／categoryId／categoryCode／code／label／enabled／version）；`requireEnabledItemForNewReference(categoryCode,itemId)` 只供已授權且持有 canonical 鎖的可寫呼叫者，不能以歷史 label 查詢充當新引用資格。DB FK只保護關聯，不授權跨模組操作。

原 V3 VEHICLE_TYPE 為空分類，正常 V6 仍不種正式碼值。ADMIN 沿既有 ADM 配置；FMS `/vehicle-types` 是固定分類窄代理，僅對本包已授權角色提供 id／code／label，不開放一般 ADM 查詢。合成車種僅在隔離測試資料中。

## 可信共用查詢

`VehicleQueries.Snapshot` 是不可變來源：id／plateNo／vehicleType／vehicleTypeCode／vehicleTypeLabel／vehicleTypeEnabled／capacityTon／administrativeStatus／version。`findCurrent(UUID)` 可讀所有行政狀態；`requireAdministrativelyAvailable(UUID)` 必須在呼叫者已授權、已持 canonical 鎖的可寫交易執行，鎖後 refresh 車輛，只檢查 administrativeStatus=AVAILABLE 並持鎖到呼叫者提交。缺少交易或唯讀交易明確拒絕，找不到來源404、非AVAILABLE為400 vehicleId欄位問題。

此服務與 `/vehicle-options` 都不額外檢查車種啟用、檢驗日期、車籍、實際占用或司機配對；AVAILABLE 仍可帶停用分類 metadata。它們不是「可派工」保證，也不預約或占用資源。未來 TMS 須另行授權、查核有效占用及保存當次車牌／來源快照；本包不預先建立這些資料或默默回填歷史。

日期保存／讀回須保持 Gregorian 自然日，包含1500等1582年前合法年份；兩個 FMS LocalDate 欄位局部使用 `@JdbcTypeCode(SqlTypes.LOCAL_DATE)`，採 JDBC 4.2 自然日綁定，不改其他模組全域設定。驗收須以 PostgreSQL date::text 獨立確認日期，不能以舊 JDBC 混合曆法對稱讀寫掩蓋位移。實際型別與約束以 V6 migration 及對應驗證紀錄為準；本模型只記本包工程責任，不代替執行證據。
