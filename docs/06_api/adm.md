# ADM API 契約｜LG-M2-02

依 [Q11–Q15](../98_decisions/2026-11-25-adm.md)、[資料模型](../05_database/adm-model.md)及 [工程決策](../98_decisions/2026-11-25-adm-engineering.md)。獨立 [ADM OpenAPI](adm-openapi.yaml)提供機器契約；不修改原訂單 [OpenAPI 0.2.0](openapi.yaml)、五項操作或角色矩陣。實測及受測版本由驗證紀錄另記。

## 共通規則

- Base path `/api/v1`，同源 HTTPS Session + CSRF；所有 POST／PATCH 都須有效 CSRF header。成功 envelope 為 `code: "0000"`、繁中 `message`、`data` 及 `+08:00` ISO 8601 `timestamp`；HTTP 狀態與 code 一起判斷。
- 本包全部端點限正常登入 ADMIN，包括 options。未登入 401；DISPATCHER／DRIVER、強制改密或其他權限不足 403。沒有核准非 ADMIN 的任何分類讀取權限，也不提供任意參數讀取。
- DTO 只接受明列欄位；未知欄位、偽造 readOnly／ID／操作者或 PATCH 夾帶 code／key／valueType／categoryId 等不可變欄位皆回 400。
- UUID 為資源識別；DTO `version` 是非負整數樂觀鎖，不能當作 UPMS 認證版本使用。建立／更新時間為 ISO 8601 datetime，與 envelope timestamp 分開。
- 不提供 DELETE、definition 寫入、物流業務或安全政策 API。DELETE 為 405；如缺 CSRF，會先被寫入安全檢查拒絕。

## 端點

以下路徑均相對 `/api/v1`，身分均為 ADMIN。

| 方法 | 路徑 | 成功 HTTP／data |
| --- | --- | --- |
| GET | `/adm/categories` | 200／`Page<Category>` |
| POST | `/adm/categories` | 201／`Category` |
| PATCH | `/adm/categories/{id}` | 200／`Category` |
| GET | `/adm/categories/{id}/items` | 200／`Page<Item>`；分類不存在 404 |
| POST | `/adm/categories/{id}/items` | 201／`Item` |
| PATCH | `/adm/items/{id}` | 200／`Item` |
| GET | `/adm/parameter-definitions` | 200／`Definition[]`，key 升冪，只讀目錄 |
| GET | `/adm/parameters` | 200／`Page<Parameter>` |
| POST | `/adm/parameters` | 201／`Parameter`，key 必須已核定 |
| PATCH | `/adm/parameters/{id}` | 200／`Parameter` |
| GET | `/adm/options/{categoryCode}` | 200／完整 `Item[]`，只含有效選項；未知分類 404，分類停用為 `[]` |
| GET | `/adm/change-logs` | 200／`Page<ChangeLog>` |

options 依 sortOrder、code、id 升冪，不接受 query。分類及項目都須 enabled；管理列表仍可顯示停用資料，不以刪除清除歷史。`ORDER_TYPE` 與其固定項目唯讀，不能新增項目、調整名稱／標籤、排序或啟停；唯讀異動回 400。

## 分頁、搜尋與排序

`Page<T>` 的 data：

```json
{"content":[],"page":0,"size":20,"totalElements":0,"totalPages":0}
```

- page 預設 0、範圍 0–1000000；size 預設 20、1–100，皆為無前置零的整數字串。超界、不合法、未知或重複 query key 皆回 400。
- sort 格式為單一 `field,asc` 或 `field,desc`，不接受多重 sort。分類／項目／參數補 code／key、id 升冪作穩定次鍵；異動預設 occurredAt desc、id desc。
- 分類、項目、參數列表可用 q（NFC／strip、最多 100 code points）及 enabled（true／false）。q 為字面 contains，`%`、`_` 等不當萬用字元。
- 異動列表只接受 page、size、sort、targetType、targetId；targetType 為 CATEGORY／ITEM／PARAMETER，targetId 為 UUID。
- parameter-definitions 及 options 不接受任何 query，避免默默忽略未支援條件。

| 列表 | q 比對欄位 | sort 白名單 | 預設 sort |
| --- | --- | --- | --- |
| categories | code、name | sortOrder、code、name | sortOrder,asc |
| items | code、label | sortOrder、code、label | sortOrder,asc |
| parameters | key、name | key、name | key,asc |
| change-logs | 無 q | occurredAt | occurredAt,desc |

## 回應 DTO

| DTO | 欄位 |
| --- | --- |
| Category | id: UUID、code: string、name: string、enabled: boolean、sortOrder: integer、readOnly: boolean、version: integer、createdAt／updatedAt: datetime |
| Item | id: UUID、categoryId: UUID、code: string、label: string、enabled: boolean、sortOrder: integer、readOnly: boolean、version: integer、createdAt／updatedAt: datetime |
| Parameter | id: UUID、key: string、name: string、valueType: TEXT／NUMBER／BOOLEAN、valueText: string、description: string、enabled: boolean、version: integer、createdAt／updatedAt: datetime |
| Definition | key: string、name: string、valueType、description: string、minLength／maxLength: integer 或 null、allowedValues: string[]、minValue／maxValue: decimal string 或 null、decimalScale: integer 或 null |
| ChangeLog | id: UUID、targetType: CATEGORY／ITEM／PARAMETER、targetId: UUID、action: CREATE／UPDATE、actorId: UUID、actorUsername: string、occurredAt: datetime、before: object 或 null、after: object |

ChangeLog 新增的 before 為 null；更新包含異動前後 DTO 快照。操作者由伺服器身分取得，不接受 client 提供。不存在修改／刪除稽核的 API。

## 請求 DTO 與驗證

所有列出的請求欄位均須提供；description 可為空字串。

| 寫入 | 必要欄位 |
| --- | --- |
| POST categories | code、name、enabled、sortOrder |
| PATCH categories/{id} | name、enabled、sortOrder、version |
| POST categories/{id}/items | code、label、enabled、sortOrder |
| PATCH items/{id} | label、enabled、sortOrder、version |
| POST parameters | key、name、valueType、valueText、description、enabled |
| PATCH parameters/{id} | name、valueText、description、enabled、version |

- code／key：2–64 字元，格式 `[A-Z][A-Z0-9_]{1,63}`，不 trim、不轉大小寫；分類 code 全域唯一，項目 code 在分類內唯一，參數 key 唯一。
- name／label：NFC／strip 後 1–80 Unicode code points。description：NFC／strip 後 0–500 code points，可有 LF 換行，拒絕其他控制字元。
- 所有文字拒絕不成對 UTF-16 surrogate。名稱／標籤不允控制字元；valueText 可有 LF，但拒絕其他控制字元及可識別的憑證／連線秘密格式。
- enabled 必須為 boolean；sortOrder 為 0–1000000 的整數。更新的 version 為目前非負整數版本，舊版本回 409，不覆寫別人的變更。
- readOnly 不可由請求指定。固定分類及其項目拒絕所有管理異動；一般資源也不得更名 code／key 或更換 categoryId／valueType。
- valueText 做 NFC、不 trim，最多 2000 code points，再驗核定定義。TEXT 使用長度範圍或完整 allowedValues；NUMBER 格式為 `-?(0|[1-9][0-9]*)(\.[0-9]+)?`，無正號、前置零或科學記號，以 BigDecimal 驗最多 38 位精度／12 位小數、定義的小數位與含端點上下界，回應保存去尾零的 plain decimal；BOOLEAN 只接受小寫 `true`／`false`。

## 核定參數與初始化界線

目前沒有核准的正式參數 key。正常 V3 的 `GET parameter-definitions` 為 `[]`，參數列表為空；POST 任意未知 key 皆拒絕，ADMIN 不能自行經 HTTP 建立 definition 或覆寫其型別／限制。UI 應提示等待核定目錄，不以任意鍵值表單繞過。

未來正式定義須先取得業務核准，再透過審查的 migration 加入只讀目錄。TEXT 定義限定長度或允許值；NUMBER 定義限定上下界與小數位；BOOLEAN 為確定的 true／false。安全政策、憑證、角色、狀態機與部署配置不屬本參數管理範圍。

隔離測試可使用 `SYNTHETIC_TEXT`／`SYNTHETIC_NUMBER`／`SYNTHETIC_BOOLEAN` 目錄驗工程能力；它們不是正式參數，不進正常 migration 或產品 seed。其他正式字典項目與非 ADMIN 分類權限也仍未核准。

## 錯誤契約與一致性

下表適用本文件已定義的操作；未提供的 DELETE 由框架拒絕，仍先經 CSRF 安全檢查，不約定其錯誤 envelope。

| HTTP | code | 情境 |
| --- | --- | --- |
| 400 | E0400 | DTO／query／UUID／型別／範圍不合法、未知欄位、不可變或唯讀異動、未核定 key |
| 401 | E0401 | 未登入、登入到期或已撤銷 |
| 403 | E0403 | 角色不足、強制改密守衛、缺少／錯誤 CSRF |
| 404 | E0404 | 已授權請求找不到目標 |
| 409 | E0409 | 唯一鍵重複、資料或樂觀鎖衝突 |
| 500／503 | E0500／E0503 | 未預期錯誤／必要服務不可用，訊息已去除內部診斷 |

成功 200／201；錯誤 data 為欄位訊息物件或 null，不回傳 SQL、堆疊、憑證或內部身份。異動及稽核同交易，任一失敗整筆回滾。前端只依實際成功回應更新資料，衝突要求重讀，不自動重送覆寫。

後續模組可經 ADM 公開服務讀有效選項及歷史標籤，不跨 repository；此內部介面不等同公開 HTTP 權限。業務新增引用的權限與停用一致性仍需在後續工作包的交易內驗證，本包不提前實作或核准物流業務。
