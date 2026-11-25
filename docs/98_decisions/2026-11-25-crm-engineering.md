# LG-M2-03 CRM 工程決策

日期：2026-11-25。依 [Q16–Q20](2026-11-25-crm.md)及 [CRM 交接](../08_development/m2-03-crm-handoff.md)，由 PR #7 合併基準繼續實作。公司最新規範固定至 `5be640ab11811b24706101ea13db73185e9ce4d8`；部署規範 v1.1 更新非商業 DEMO 平台方向，本包只做 DEV／隔離 CI，不建立 hosting、付費資源或部署證據。

保留 Java 21／Spring Boot 4.1.3 等 M1 鎖版、UPMS Session／CSRF 與 ADM 治理。本文記錄工程細化，不把尚未核准的稅務、地址分類、特殊必填或資料保存期限視為產品決策；實際測試與受測 commit 另記。

## 身份與資料界線

- 單公司。客戶 UUID 為穩定身份，customerCode 唯一且建立後不可改；客戶名稱不唯一，不按名稱、統編、電話或 email 自動合併。
- 一客戶可有多個聯絡人與常用地址。子項的 customerId 由路徑及伺服器設定，不能跨客戶移轉；錯誤父子組合不以全域子項 ID 命中其他客戶資料。
- 不提供硬刪除。客戶／子項停用保留原 ID 及顯示資料；停用客戶仍可維護主檔及子項，不能提供新的有效客戶引用。管理列表和歷史讀取與有效選項是不同用途。
- 正常 Flyway V4 不加入客戶、聯絡人、地址或合成 seed；不改已套用 V1–V3。隔離測試資料只在測試來源及指定合成 DB 建立，不提供產品 seed flag 或一般環境可開的後門。
- 不收集或提交真實客戶／司機個資，不做外部地址查詢、統編查核、email 驗證信或第三方資料同步。

## 授權與鎖順序

- CRM HTTP 全部操作限正常登入 ADMIN、DISPATCHER；DRIVER 不讀客戶主檔，沒有公開客戶入口。這項角色擴充只用於 CRM，不改 ADM 的 ADMIN-only 或 UPMS 帳號管理權限。
- 延續同源 HTTPS Session + CSRF；所有 POST／PATCH 驗 CSRF。強制改密、到期、停用或撤銷認證不得操作 CRM。
- CRM 使用 UPMS 公開 CustomerManager 授權 facade，不引用其 internal repository。寫入在同一交易先取得 canonical／操作者鎖並重新核對角色、啟用、mustChangePassword、authVersion，再依客戶、子項順序鎖定 CRM 資料；與 UPMS 撤銷和客戶停用維持一致順序。
- 更新要求目前非負 version，採真正 partial PATCH：至少提供一個可變欄位，未提供的欄位保留，null 只清 nullable 欄位；version-only 回 400。UUID、customerCode、父關聯、系統審計欄位及操作者不能由更新 DTO 覆寫；衝突回 409，不能無條件覆蓋或自動重放。
- 本包審計是三張 CRM 表的 createdAt／updatedAt／createdBy／updatedBy，操作者由已驗證身分提供，與資料變更同交易，失敗整筆回滾。不新增 CRM 前後值異動表或完整歷程 API，不保存密碼、Session／CSRF 或完整認證 payload；正式資料保存期限仍未核定，不宣稱正式個資維運政策完成。

## 最小欄位規則

以既有訂單文字快照容量作相容邊界：客戶名稱、聯絡人名稱最多 200 code points，電話最多 50，地址最多 1000。customerCode 採 `[A-Z0-9][A-Z0-9_-]{0,63}`，不 trim、不轉大小寫；統編最多 50，聯絡人 title／地址 label 最多 100。其餘精確欄位、空值與 query 規則見 [CRM API](../06_api/crm.md)及 [模型](../05_database/crm-model.md)，採 NFC／strip、一致的型別與長度驗證，拒絕不成對 surrogate 及不合規控制字元。

邊緣空白以 isWhitespace／isSpaceChar 及 U+FEFF 判斷，包含 NBSP／NNBSP／FEFF，對齊 Java／JavaScript 的非空文字邊界；必要名稱不能由這類空白構成。地址輸入 CRLF 先轉 LF，允許內部 LF，單獨 CR 仍拒絕；不以截斷方式修補過長文字。

- 統編可空、非唯一，不新增八碼／校驗碼、獨占或稅務例外規則。
- email 可空，最多 254 字元，僅做基本格式檢查，不查 DNS、不寄信、不聲稱地址可送達。
- 電話保持可描述的文字，不強制特定國家的手機、區碼或 E.164 規則；不因沒有電話／email 自行新增特殊必填條件。
- addressType 是 nullable、最多 40 字元的描述文字，不採公司／提貨／送貨／其他等候選值作核准 enum，不自動設 OTHER 或綁定 ADM 分類。之後若需正式地址分類，須另核准並處理相容性。
- 不新增計費、信用額度、稅務身份、地理座標、司機摘要或多公司欄位；這些不因 CRM 主檔工作包而獲核准。

## 原訂單契約相容性

原 [OpenAPI 0.2.0](../06_api/openapi.yaml)、[角色矩陣](../06_api/order-draft-access-policy.json)、[合成案例](../06_api/order-draft-contract-examples.json)及 [欄位字典](../05_database/first-slice-fields.csv)保持原樣。

| 原契約 | CRM 邊界 |
| --- | --- |
| customerId | 原本就是 UUID；CRM UUID 身份可供後續新引用，不改原型別或加入另一套客戶 ID |
| customerName | 訂單保存的 1–200 字元名稱快照；CRM 改名不回寫舊訂單 |
| 同 customerId 的草稿保存 | 原契約要求保留原名稱快照；只有明確改為另一個有效客戶才取新快照，不以 CRM 讀取結果覆蓋歷史 |
| pickupName／deliveryName、pickupAddress／deliveryAddress | 現有任務文字欄位；地址上限 1000，不加入 CRM addressId 或自動同步關聯 |
| siteContact.name／phone | 既有可空文字快照，容量分別 200／50；不把 CRM contactId、email 或其他欄位塞進舊 DTO |

CRM 主檔的名稱、聯絡與地址變更不主動修改已保存的訂單／任務文字。CRM 本包沒有 TOM 表、訂單 API、訂單快照寫入或真正新訂單競爭驗收，不能宣稱訂單產品驗收通過。

## 供後續模組使用的引用服務

`CrmQueries` 是可信伺服器內部介面，不是公開 HTTP 權限，也不替呼叫模組驗終端使用者角色。`findCurrent(UUID)` 可取停用來源供既有資料顯示，不能當作新增引用驗證。後續 TOM 在新增／明確替換客戶引用時，須先完成自己業務授權，再於同一個可寫交易使用 MANDATORY 的 `requireEnabledForNewReference(UUID)` 鎖定有效客戶，取得 immutable Snapshot（id／code／name／version）；code 對應 HTTP customerCode。

鎖定有效客戶與 CRM 停用／改名共享客戶列鎖，交易結束前不能變成先查後寫的競爭窗口。呼叫端取得名稱後仍須自行把快照寫入自己的聚合，不跨 CRM repository、不靠讀取後另開交易補寫。既有相同 customerId 的歷史快照處理仍依原訂單契約，不推定所有歷史讀取都必須通過「新增有效引用」檢查。

客戶與 UPMS 寫入操作者在鎖定後 refresh，避免同一 persistence context 已載入的舊啟用／角色／版本狀態被沿用。Snapshot 驗證名稱容量、正規化與代碼格式後保留 stored 原值，不默默改寫不合規主檔來產生新快照來源。

引用方法拒絕無交易或唯讀交易；不存在客戶為 404／E0404，停用客戶為 400／E0400 的 customerId 欄位錯誤。這是供後續聚合使用的內部錯誤邊界，不提前實作或改寫 TOM API。

本包以資料庫交易及鎖定服務測試驗證該工程邊界；真正的 TOM 新增引用、停用競爭與快照不可回寫驗收留待 M2-04。

## 交付與待決

新 [CRM API](../06_api/crm.md)與獨立 CRM OpenAPI 描述本包操作；不修改舊訂單機器契約。驗證涵蓋 V4 空庫／升級、唯一代碼、名稱／統編非唯一、父子一致性、啟停、版本、角色與失效認證、CSRF、稽核回滾、引用鎖、前端及 TLS E2E，保留既有 UPMS／ADM 測試。

正式客戶資料、統編特殊規則、聯絡人／地址特殊必填、正式地址分類、司機可見摘要、個資保存與刪除政策、計費及部署均未因本包核准。只回報實際執行證據，不以模型／契約文字代替驗收。

## 參考

- [公司 APP 開發規範](https://github.com/xk2022/xplore-kaleidoscope/blob/5be640ab11811b24706101ea13db73185e9ce4d8/doc/04_engineering/app-development-standard.md)與 [部署規範 v1.1](https://github.com/xk2022/xplore-kaleidoscope/blob/5be640ab11811b24706101ea13db73185e9ce4d8/doc/04_engineering/app-deployment-standard.md)。
- [PostgreSQL 17 constraints](https://www.postgresql.org/docs/17/ddl-constraints.html)及 [explicit locking](https://www.postgresql.org/docs/17/explicit-locking.html)：唯一鍵、父子 FK 與交易列鎖。
- [Spring transaction propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)：引用服務要求既有交易，不自行拆成另一筆提交。
