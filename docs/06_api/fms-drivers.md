# FMS 司機 API

LG-M3-02 工程契約依[核准 Q26–Q30](../98_decisions/2026-11-25-fms-driver.md)，機器契約為[獨立 OpenAPI](fms-drivers-openapi.yaml)，資料與一致性見[模型](../05_database/fms-driver-model.md)及[工程決策](../98_decisions/2026-11-25-fms-driver-engineering.md)。不修改原訂單或車輛契約，不提供派工、占用、配對、圖片或正式證件保存政策。本頁不代表實際測試已通過。

基底 `/api/v1`，同源 HTTPS HttpSession、既有 Secure／HttpOnly／SameSite=Lax cookie 與 CSRF。成功使用 `code="0000", message, data, timestamp(+08:00)`，不是 TOM 的 OK／traceId envelope。POST／PATCH 需既有 CSRF header；不提供 DELETE，framework 的方法拒絕與 CSRF 優先順序沿現行安全設定。

| 方法 | 路徑 | 正常登入角色／回應 data |
| --- | --- | --- |
| GET | `/fms/drivers` | ADMIN／DISPATCHER；Page DriverSummary |
| POST | `/fms/drivers` | ADMIN／DISPATCHER；201 Driver |
| GET | `/fms/drivers/{id}` | ADMIN／DISPATCHER；Driver |
| PATCH | `/fms/drivers/{id}` | ADMIN／DISPATCHER；Driver |
| GET | `/fms/driver-options` | ADMIN／DISPATCHER；Page DriverOption |
| GET | `/fms/driver-license-types` | ADMIN／DISPATCHER；DriverLicenseType[] |
| GET | `/fms/driver-account-options` | ADMIN／DISPATCHER；Page DriverAccountOption |
| GET | `/fms/drivers/me` | DRIVER；DriverSelf，從目前 actor 推導綁定 |

`/me` 精確 matcher 在一般 FMS manager matcher 前；不接受 id 或任何 query。未綁定回404，撤銷／過期 actor 回401，管理角色回403。正常 DRIVER 即使主檔 INACTIVE／LEFT 仍可讀自己；mustChangePassword 的既有強制改密碼屏障仍生效。本包不增加 DRIVER 對其他司機、車輛、CRM、訂單、帳號或 ADM 的讀取權。

## 精確 DTO 與最小化

所有 DTO key 明確固定，包含 nullable key；客戶端亦拒絕額外欄位。Page 為 `{content,page,size,totalElements,totalPages}`。

| DTO | key |
| --- | --- |
| Driver（22） | id, driverCode, name, phone, email, accountId, accountUsername, accountDisplayName, accountBindingValid, employmentStatus, dutyStatus, licenseType, licenseTypeCode, licenseTypeLabel, licenseTypeEnabled, licenseReference, licenseDueDate, version, createdAt, createdBy, updatedAt, updatedBy |
| DriverSummary（17） | Driver 排除 licenseReference、createdAt、createdBy、updatedAt、updatedBy |
| DriverSelf（12） | id, driverCode, name, phone, email, employmentStatus, dutyStatus, licenseType, licenseTypeCode, licenseTypeLabel, licenseTypeEnabled, licenseDueDate |
| DriverOption（9） | id, driverCode, name, licenseType, licenseTypeCode, licenseTypeLabel, licenseTypeEnabled, licenseDueDate, version |
| DriverLicenseType | id, code, label |
| DriverAccountOption | id, username, displayName, bound |

無 licenseType 時其 UUID／code／label 為 null，enabled 為 false；保留歷史停用類別的目前 metadata。不綁帳號時 accountId／username／displayName 為 null，bindingValid=false。已綁但目前無效的帳號保留關聯並標 false，不自動解綁；摘要只含目前 username／displayName，不回 role、密碼、authVersion 或其他 AccountDto。bound 只說明是否已有主檔占用，不洩露另一位司機 ID。

## 輸入與更新

POST 只必填 driverCode／name。其他欄可省略：employmentStatus 預設 ACTIVE、dutyStatus 預設 OFF_DUTY；phone／email／accountId／licenseType／licenseReference／licenseDueDate 預設 null。沒有聯絡方式、帳號或證照仍可建主檔；可建立歷史 LEFT 主檔。

PATCH 必須 version 與至少一個 mutable：name、phone、email、accountId、employmentStatus、dutyStatus、licenseType、licenseReference、licenseDueDate。省略保留原值，optional null 清除；name／兩個狀態不得 null。driverCode、id、audit、衍生摘要與未知欄位一律400。version 是0–9007199254740991的數學整數；每次確認成功 PATCH 加一版，含同值保存，版本上限409。updatedAt 至少比上一筆多一微秒。

driverCode 採 NFC → Unicode 邊界 strip（isWhitespace／isSpaceChar／U+FEFF）→ Locale.ROOT 大寫 → NFC，驗 canonical 1–40 code points。保留內部空白／連字號，不設國別規則；跨所有行政狀態唯一、不可重用且不可修改。name 採 NFC＋同樣 strip，1–200；phone≤50、email≤254、licenseReference≤100 code points，optional 空白轉null。拒控制字元與不成對 surrogate。輸入 schema 的 x-normalizedMinLength／MaxLength 是 canonical 限制，不用 raw maxLength 錯拒 padding 或 NFD。

email 是有界描述性聯絡文字，不套嚴格 ASCII email regex、不驗可投遞性；UI 使用 text／inputMode=email。licenseReference 是可空工作參考，不要求完整證照號碼或身分識別。licenseDueDate 為可空、合法 Gregorian `YYYY-MM-DD`（0001–9999），不轉 UTC；只可提示過去日期，不新增7／30天、法規資格或逾期禁用政策。

employmentStatus 固定 ACTIVE／INACTIVE／LEFT；dutyStatus 固定 ON_DUTY／OFF_DUTY。ACTIVE↔INACTIVE、兩者→LEFT；LEFT 可維護欄位或維持 LEFT，不能復職為 ACTIVE／INACTIVE（409 employmentStatus）。兩個行政欄位互相獨立，INACTIVE／LEFT＋ON_DUTY 仍可記錄；不得自動改 duty、停用帳號、解綁或撤銷 session。

新建／換成非空 licenseType 使用 ADM UUID，必須固定 DRIVER_LICENSE_TYPE 父子皆啟用；同一歷史 UUID 即使停用可保留維護，null 可清除。V7 只建立空父分類，沒有正式類別碼 seed。新建／換成非空 accountId 必須目前為 enabled、UPMS usable 的 DRIVER；有效暫時密碼／mustChangePassword 帳號可綁，過期暫時密碼不可。無效目標400 accountId、已被綁409 accountId；未改的原無效關聯可保留，維護或解綁不會變更 UPMS 生命週期。

## 查詢

| 端點 | 唯一允許 query |
| --- | --- |
| drivers 列表 | page、size、q、employmentStatus、dutyStatus、licenseType |
| driver-options | page、size、q、licenseType |
| driver-account-options | page、size、q |
| license-types、me、明細、POST、PATCH | 無 |

page 預設0、0–100000；size 預設20、1–100。q 預設空，NFC／邊界 strip後≤100 code points，保留大小寫。列表／options 固定 driverCode ASC、id ASC，沒有 sort 參數。driverCode 分支以 ROOT大寫／NFC 查字面 substring；name／phone／email 在 DB 對兩側 lower，`%`／`_`／反斜線皆 escape。不能搜尋 licenseReference 或帳號資料。未知或重複 query、重複 JSON key、trailing JSON、無效 UTF-8／enum／格式均400。

driver-options 只篩 ACTIVE && ON_DUTY，不加帳號、證照到期或類別啟用篩選；未綁帳號或停用類別仍可能回傳，僅代表行政狀態，不保證可派工、未占用或法規適格。account-options 由 UPMS 在分頁前選出全部 eligible 候選，以 username ASC／id ASC，q 字面大小寫不敏感查 username／displayName；FMS 只為當頁加 bound，不在分頁後刪資料。UI 停用已 bound 候選，原保存綁定例外；不接受 driverId／currentDriverId 或呼叫一般帳號列表替代。

## 錯誤與可信內部介面

400 E0400 欄位文字 map；401 E0401；403 E0403；404 E0404；409 E0409（driverCode／accountId／employmentStatus／version 問題可定位欄位，一般 DB 競爭可 null）；500 E0500、503 E0503 不回內部例外。data 為 null 或 string map，不加入 TOM actualVersion／traceId。無 idempotency-key 契約，網路中斷或500後不可自動重送 POST／PATCH；應保存本機並人工 GET 查核，409不得偷偷換版覆寫。

DriverQueries 是可信 server 查詢，非替 HTTP 用戶自動授權：findCurrent 回不可變現況 snapshot（id、driverCode、name、phone、email、兩狀態、licenseType／code／label／enabled、licenseDueDate、version），不含 licenseReference／audit／帳號敏感欄位。requireAdministrativelyAvailable 要求 caller 先完成授權／canonical、可寫 caller transaction，再 fresh lock source 至 commit，只檢查 ACTIVE && ON_DUTY，不保留資源。詳見工程決策；實際驗收結果由工作包驗證紀錄提供。
