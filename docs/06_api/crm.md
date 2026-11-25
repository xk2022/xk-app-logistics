# CRM API 契約｜LG-M2-03

依 [Q16–Q20](../98_decisions/2026-11-25-crm.md)、[資料模型](../05_database/crm-model.md)及 [工程決策](../98_decisions/2026-11-25-crm-engineering.md)。獨立 [CRM OpenAPI](crm-openapi.yaml)描述本包機器契約；原訂單 [OpenAPI 0.2.0](openapi.yaml)、角色矩陣與快照欄位保持原樣。實際驗證與受測 commit 另記。

## 共通規則

- Base path `/api/v1`，同源 HTTPS Session + CSRF。全部 CRM HTTP 操作限正常登入 ADMIN、DISPATCHER、本公司資料；DRIVER 不讀客戶主檔，未核准司機摘要留待派工工作包。
- 所有 POST／PATCH 必須有效 CSRF；強制改密、停權、到期或已撤銷認證不能操作。CRM 角色範圍不擴及 ADM 或 UPMS 帳號管理。
- 成功 envelope 為 `code: "0000"`、繁中 `message`、`data`、ISO 8601 `timestamp`（`+08:00`）。HTTP 狀態及 code 共同判斷，錯誤不回傳 SQL、堆疊或憑證。
- DTO／query 嚴格白名單，未知或重複 query 拒絕；client 不能指定 ID、父關聯或審計操作者。UUID 是資源身份，customerCode 是穩定代碼，version 是資料樂觀鎖，並非 UPMS authVersion。
- 無 DELETE、公開客戶入口或硬刪除。未提供的方法由框架拒絕，仍先經 CSRF，不約定其錯誤 envelope。

## 端點

以下路徑均相對 `/api/v1`，身分均為 ADMIN 或 DISPATCHER。

| 方法 | 路徑 | 成功 HTTP／data |
| --- | --- | --- |
| GET | `/crm/customers` | 200／`Page<Customer>` |
| POST | `/crm/customers` | 201／`Customer` |
| GET | `/crm/customers/{id}` | 200／`Customer`，包含停用客戶的管理明細 |
| PATCH | `/crm/customers/{id}` | 200／`Customer`，partial 更新／啟停 |
| GET | `/crm/customers/{id}/contacts` | 200／`Page<Contact>` |
| POST | `/crm/customers/{id}/contacts` | 201／`Contact` |
| PATCH | `/crm/customers/{id}/contacts/{contactId}` | 200／`Contact` |
| GET | `/crm/customers/{id}/addresses` | 200／`Page<Address>` |
| POST | `/crm/customers/{id}/addresses` | 201／`Address` |
| PATCH | `/crm/customers/{id}/addresses/{addressId}` | 200／`Address` |
| GET | `/crm/customer-options` | 200／`Page<CustomerOption>`，只含啟用客戶 |

聯絡人／地址由獨立分頁端點讀取，不塞入 Customer 回應聚合。父客戶不存在或路徑父子關聯不符回 404，不能只靠全域子項 ID 命中別的客戶。停用客戶仍可管理主檔與子項，但不出現在有效客戶選項中。

## 分頁、搜尋與排序

Page data 為 `{content: T[], page: integer, size: integer, totalElements: integer, totalPages: integer}`。page 預設 0、範圍 0–1000000；size 預設 20、1–100。只接受單一 `sort=field,asc` 或 `field,desc`，再補 id 升冪作穩定次鍵，不受主要排序方向影響。

q 做 NFC／strip、最多 100 code points，為參數化字面 contains；`%`、`_` 等不是萬用字元。一般列表接受 page／size／q／enabled／sort；enabled 僅 true／false。options 只接受 page／size／q／sort，不能用 enabled 讀取停用來源。GET 明細及所有寫入不接受 query；未知、重複、超界或不合法 query 回 400。

| 列表 | q 欄位 | sort 白名單 | 預設 sort |
| --- | --- | --- | --- |
| customers | customerCode、name、taxId | customerCode、name、createdAt、updatedAt | customerCode,asc |
| contacts | name、title、phone、email | name、title、createdAt、updatedAt | name,asc |
| addresses | label、address、addressType | label、addressType、createdAt、updatedAt | createdAt,asc |
| customer-options | customerCode、name、taxId，且只查有效客戶 | customerCode、name | customerCode,asc |

空列表回 200、content=[]；沒有命中 q 不等於服務失敗。enabled 篩選不更改父客戶狀態或授權範圍。

## 回應 DTO

三種主檔共通欄位：`id: UUID`、`enabled: boolean`、`version: integer`、`createdAt/updatedAt: datetime`、`createdBy/updatedBy: UUID`。審計欄位由伺服器設定，沒有 client 覆寫入口。

| DTO | 其他欄位 |
| --- | --- |
| Customer | customerCode: string、name: string、taxId／phone／email: string 或 null |
| Contact | customerId: UUID、name: string、title／phone／email: string 或 null |
| Address | customerId: UUID、address: string、label／addressType: string 或 null |
| CustomerOption | 僅 id: UUID、customerCode: string、name: string、version: integer；不含聯絡／地址或審計明細 |

nullable 欄位沒有值時回 null。options 僅供選取，不能替代後續寫入交易中的有效客戶重新查核。

## 建立與 partial 更新

| 寫入 | 必填 | 可省略欄位 |
| --- | --- | --- |
| POST customers | customerCode、name | taxId、phone、email、enabled |
| POST contacts | name | title、phone、email、enabled |
| POST addresses | address | label、addressType、enabled |
| PATCH customers/{id} | version | name、taxId、phone、email、enabled |
| PATCH contacts/{contactId} | version | name、title、phone、email、enabled |
| PATCH addresses/{addressId} | version | label、address、addressType、enabled |

新增時 enabled 省略預設 true，nullable 文字省略／null／正規化後空白存 null。enabled 不接受 null；必要名稱／地址也不能為 null 或空白。

PATCH 除 version 外至少提供一個可變欄位，只有 version 的請求回 400。省略可變欄位表示保留原值，不是完整替換；null 只可清 nullable 欄位，空白 nullable 文字也會正規化為 null。customerCode、id、customerId 及系統審計欄位不屬可變欄位，夾帶不可變或未知欄位回 400。

例如只清客戶電話，不改名稱／email：

```json
{"version":3,"phone":null}
```

## 欄位驗證

- customerCode：`[A-Z0-9][A-Z0-9_-]{0,63}`，1–64；不 trim、不轉大小寫；全域唯一且建立後不可改。
- 客戶／聯絡人 name：NFC／strip 後 1–200 Unicode code points。名稱非唯一，不按名稱自動合併。
- taxId：nullable、最多 50，不套八碼／checksum、不要求唯一；電話 nullable、最多 50，不強制單一國家格式或 E.164。
- email：nullable、最多 254，僅基本格式檢查，不查 DNS、不寄信、不保證可送達。
- title／label：nullable、最多 100；address：NFC／strip 後 1–1000，可有 LF；addressType：nullable、最多 40 的描述文字，不是核准 enum、不預設 OTHER 或綁 ADM。
- 文字邊緣 strip 包含 isWhitespace／isSpaceChar 及 U+FEFF（含 NBSP／NNBSP／FEFF），必要值全空白拒絕；不成對 surrogate 也拒絕。address 的 CRLF 先轉 LF，允許 LF、拒絕單獨 CR，其餘欄位拒絕控制字元。version 為目前非負整數版本，衝突 409；未知欄位、錯型別或超界為 400。

不因缺統編、電話、email 或地址類型新增特殊必填，也不設統編唯一索引。所有資料僅使用合成測試內容，沒有真實客戶初始化資料。

## 啟停、交易與錯誤

客戶／子項停用不刪資料、不移轉父關聯；管理列表與明細可讀停用資料。有效客戶選項排除停用客戶。寫入在同一交易按 canonical／操作者→customer→child 鎖順序授權、驗版本並提交資料與審計欄位；失敗回滾，不自動重放衝突寫入。

| HTTP | code | 情境 |
| --- | --- | --- |
| 400 | E0400 | DTO／query／UUID／型別／長度錯誤，不可變或未知欄位 |
| 401 | E0401 | 未登入、到期或已撤銷 |
| 403 | E0403 | DRIVER／權限不足、強制改密、缺少或錯誤 CSRF |
| 404 | E0404 | 已授權請求找不到客戶／子項，或父子路徑不符 |
| 409 | E0409 | 重複 customerCode、版本或資料一致性衝突 |
| 500／503 | E0500／E0503 | 未預期錯誤／必要服務不可用；訊息不含內部診斷 |

## 原訂單契約與內部引用服務

原 customerId 已是 UUID；customerName 是不隨 CRM 改名回寫的歷史快照。同一 customerId 的草稿保存保留原名稱，明確換有效客戶才取新來源。CRM 不往舊 Order／Task DTO 新增 contactId／addressId、統編或 email；原任務地址及 siteContact 維持純文字欄位與既有容量。

可信內部 `CrmQueries.findCurrent(UUID)` 可回停用客戶 Snapshot 供歷史顯示，不是公開 HTTP 權限或新增引用守衛。`requireEnabledForNewReference(UUID)` 必須在呼叫者先授權的可寫交易內取得客戶鎖，回 immutable id／code／name／version 來源；code 對應 HTTP customerCode，呼叫端再於同一交易保存自己的快照。此內部守衛對不存在客戶回 404／E0404，停用來源回 400／E0400 的 customerId 欄位錯誤；無交易或唯讀交易拒絕，不自行另開交易補救。

CRM 本包不建立 TOM 表或寫入真正訂單快照；新引用與停用競爭的工程鎖測試不代替 M2-04 訂單整合驗收。FMS、派工、照片、計費、正式個資與部署仍未實作。
