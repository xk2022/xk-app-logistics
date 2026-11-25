# FMS 司機資料模型

依[核准 Q26–Q30](../98_decisions/2026-11-25-fms-driver.md)及[工程決策](../98_decisions/2026-11-25-fms-driver-engineering.md)，對應[API](../06_api/fms-drivers.md)與[獨立 OpenAPI](../06_api/fms-drivers-openapi.yaml)。本頁是工程結構與不變量，實際測試證據另列，不表示已部署。

V7 append-only 新增一張 fms_driver 與空的 ADM DRIVER_LICENSE_TYPE 分類；空庫共21張表（含 Flyway），V1–V6 不變。不 seed 真實或合成司機、帳號、正式證照類別，不建立派工、占用、配對、圖片、證件檔案或 lifecycle 自動化表，無 hard delete。

| DB 欄位 | 型別／不變量 |
| --- | --- |
| id | UUID PK，server產生 |
| driver_code | canonical文字1–40 code points，全表唯一、不可修改／重用 |
| name | NFC strip後1–200 code points |
| phone / email | nullable文字≤50／254；空白→null，不訂國別／投遞規則 |
| account_id | nullable UPMS account UUID FK，非空唯一；FK不等於有效授權 |
| employment_status | ACTIVE／INACTIVE／LEFT；建立預設ACTIVE |
| duty_status | ON_DUTY／OFF_DUTY；建立預設OFF_DUTY，與employment獨立 |
| license_type_id | nullable ADM item UUID FK；wire為licenseType，新增／換值驗固定DRIVER_LICENSE_TYPE父子啟用 |
| license_reference | nullable文字≤100，只存必要工作參考，不強求完整證件識別 |
| license_due_date | nullable SQL date／LocalDate，Gregorian 0001–9999 |
| version | optimistic version 0–9007199254740991，每次確認PATCH +1 |
| created_at / updated_at | server instant；updatedAt=max(now,old+1micro) |
| created_by / updated_by | UPMS actor UUID FK，server維護 |

driverCode canonical 為 NFC、Unicode邊界 strip（isWhitespace／isSpaceChar／U+FEFF）、Locale.ROOT大寫、再NFC；保留內部空白／連字號，拒控制與不成對 surrogate。其他文字採 NFC＋同樣 strip；required 空白拒絕、optional 空白轉null。輸入長度驗 canonical code points，不對 raw padding／NFD套輸出字數上限。沒有電子郵件／電話的嚴格國別或 ASCII 格式。自然日不轉UTC；新欄位局部 JDBC4.2 LOCAL_DATE，需以真 PostgreSQL date::text獨立核對1500／0001等舊日期，不依對稱讀寫證明正確。

ACTIVE↔INACTIVE，兩者可→LEFT；LEFT 可維護但不能復職（409），原因是復職流程尚未核准。任一 employment 與任一 duty 組合皆可記錄，不自動變 duty。行政選項只取 ACTIVE&&ON_DUTY；不以綁定、類別啟用或證照期限增加篩選，不宣稱真派工／法律適格。employment 編輯不變更帳號角色、密碼、enabled、綁定或 session。

licenseType 與 accountId 只保存身份，不複製類別／帳號完整資料。DTO 的 code／label／enabled 與 username／displayName／bindingValid 是目前公開服務 metadata；歷史停用分類及原無效帳號綁定可保留，不自動修主檔。無類別時 metadata null／false，無帳號時摘要 null／false。關聯非空時 DB唯一與 FK 最後防線仍需與 fresh domain guard並用；不能以 FK 或 UI候選代表授權。

## 交易與模組所有權

寫入採 UPMS canonical → fresh manager actor → PATCH fresh driver/version → 必要的新目標 account guard → 必要的 ADM category/item。UPMS 角色／停用／復原沿同 canonical 序列化；source guard 在 mandatory writable caller transaction鎖定並 refresh，持鎖至 commit，避免 L1 cache舊值。新／換帳號必須目前 enabled、DRIVER且UPMS usable，含有效暫時密碼帳號；過期暫時密碼不行。未換的原無效關聯可維持、解綁也不改帳號生命週期。

UPMS公開 DriverAccountQueries 擁有其 repository：findCurrent 提供最小 Summary(id,username,displayName,bindingValid)，candidates 在分頁／count前選 eligible Candidate(id,username,displayName)，requireEligibleForBinding要求可寫 caller transaction及fresh lock。FMS 只查自己 account_id 使用情況，為候選頁加 bound boolean，不在分頁後過濾、不得回另一位司機ID。ADM同樣由公開 category-bound介面查metadata與驗新引用，不跨 repository。

`/me` 使用可鎖定的 writable transaction但不寫業務資料：canonical → fresh normal DRIVER actor/authVersion → 依目前 accountId查綁定 → fresh driver lock → 最小本人DTO。與解綁／重綁／角色變更／停用／復原共用鎖序，不接受外部driverId、不cache session driverId；未綁定404、撤銷actor401。正常綁定DRIVER仍可看INACTIVE／LEFT本人，既有強制改密碼屏障不放寬。

公開 DriverQueries.findCurrent 回可信server immutable Snapshot(id,driverCode,name,phone,email,employmentStatus,dutyStatus,licenseType,licenseTypeCode,licenseTypeLabel,licenseTypeEnabled,licenseDueDate,version)，不含帳號、licenseReference、audit或credential。requireAdministrativelyAvailable要求caller先授權／canonical、可寫MANDATORY transaction、fresh driver lock持至commit；只驗ACTIVE&&ON_DUTY，沒有reservation。null查詢回empty，缺項404、不符合400 driverId，無caller tx或readOnly拒絕。既有VehicleQueries不變。

DTO資料最小化：22欄管理明細；17欄列表排除licenseReference與audit；12欄本人排除帳號、licenseReference、version與audit；9欄options排除聯絡、帳號、licenseReference與audit。精確key見API；不是僅在UI隱藏。正式證件保存／法規／出勤工時／復職／occupancy仍待核准。
