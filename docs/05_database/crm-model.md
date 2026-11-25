# CRM 資料模型｜LG-M2-03

依 [Q16–Q20](../98_decisions/2026-11-25-crm.md)、[API](../06_api/crm.md)及 [工程決策](../98_decisions/2026-11-25-crm-engineering.md)。本包只建立單公司客戶、聯絡人與常用地址，不核准正式個資、特殊必填、稅務／保存政策或地址類型 enum。

## 結構與身份

新增 Flyway V4，在 `logistics` schema 建立三張表，不修改既有 V1–V3。正常 migration 無客戶或合成 seed。

| 表 | 邏輯欄位與約束 |
| --- | --- |
| crm_customer | UUID id；唯一且不可變 customer_code；name；nullable tax_id／phone／email；enabled；非負樂觀鎖 version；建立／更新時間及操作者 |
| crm_contact | UUID id；不可變 customer_id FK；name；nullable title／phone／email；enabled；version；建立／更新時間及操作者 |
| crm_address | UUID id；不可變 customer_id FK；nullable label／address_type；address；enabled；version；建立／更新時間及操作者 |

共通審計欄位為 `created_at`、`updated_at`、`created_by`、`updated_by`；操作者 UUID FK 指向 UPMS 帳號，DTO 使用 camelCase。操作者與時間由服務端設定，client 不能覆寫。這些 FK 保留 schema 參照完整性，不授權 CRM 直接操作 UPMS repository。

本包審計為建立／更新時間及身分，與資料變更同交易；不另建 CRM 前後值異動記錄表或公開歷程 API，不宣稱已建立正式個資保存／清除政策。

UUID 是主檔身份，customerCode 是穩定可辨識代碼；客戶名稱及統編不唯一。不得以名稱、統編、電話或 email 自動去重、合併或推定同一客戶，也不將 customerCode 改成原訂單 customerId。

## 欄位規則

| 欄位 | 工程限制 |
| --- | --- |
| customerCode | `[A-Z0-9][A-Z0-9_-]{0,63}`，1–64 個 ASCII 大寫字母／數字／底線／連字號，開頭為字母或數字；不 trim、不轉大小寫；建立後不可改 |
| 客戶／聯絡人 name | NFC／strip 後 1–200 Unicode code points；名稱非唯一 |
| taxId | nullable，最多 50；空白正規化為 null，不套八碼、checksum 或唯一性 |
| phone | nullable，最多 50；不強制單一國家格式或 E.164 |
| email | nullable，最多 254；基本格式檢查，不查 DNS、不寄信、不保證可送達 |
| 聯絡人 title／地址 label | nullable，最多 100 |
| address | NFC／strip 後 1–1000，可有 LF 換行 |
| addressType | nullable 描述文字，最多 40；不是公司／提貨／送貨／其他的核准 enum，也不預設 OTHER 或連到 ADM 字典 |
| enabled／version | enabled 為 boolean；version 為非負整數，與 UPMS authVersion 不同 |

文字先 NFC／strip 再按 code point 計長度；strip 包含 Java isWhitespace／isSpaceChar 的邊緣空白及 U+FEFF（含 NBSP／NNBSP／FEFF），必要值不得全為空白。拒絕不成對 surrogate；address 輸入 CRLF 轉 LF 後驗證，允許 LF、拒絕單獨 CR，其他欄位拒絕控制字元。新增時 nullable 文字省略或空白存 null；PATCH 省略則保留既有值。不因電話、email、統編或地址類型缺少而擅加特殊必填。新增客戶僅必填 customerCode／name，聯絡人僅 name，地址僅 address；enabled 省略預設 true。

更新為真正 partial PATCH：version 必填，且至少提供一個可變欄位；只有 version 回 400。省略的可變欄位保留，null 只可清除 nullable 欄位。不能修改 id、customerCode、customerId、審計身分或時間；不把未傳欄位當成完整替換而清空。

## 父子、啟停與交易

不提供硬刪除，也不跨客戶移轉聯絡人／地址。子項路徑的 customerId 與資料 FK 必須一致；錯誤父子組合回 404，不能只靠全域子項 ID 忽略父關聯。

客戶停用後仍可管理其主檔與子項，保留歷史顯示；不級聯改寫所有子項 enabled。有效客戶選項只包含啟用客戶。停用與「可建立新訂單引用」是獨立守衛，不以管理列表能看到資料就推定仍可新增引用。

寫入同一交易先經 UPMS 公開 CustomerManager facade，取得 canonical／操作者鎖並重驗 ADMIN 或 DISPATCHER、啟用、mustChangePassword、authVersion，再依 customer→child 順序鎖定目標並驗 version。操作者撤銷、客戶啟停與子項寫入不以先查後寫拆散；資料、審計欄位與版本一起提交，失敗整筆回滾。

## 公開查詢與引用來源

`CrmQueries` 是可信伺服器模組使用的公開介面，不帶終端使用者權限，也不提供新的 HTTP 入口：

- `findCurrent(UUID)` 回 Optional 的 immutable Snapshot，可包含停用客戶來源以支援既有資料顯示；不能拿它作新引用的啟用檢查。
- `requireEnabledForNewReference(UUID)` 要求已有可寫交易（MANDATORY），無交易或唯讀交易拒絕；鎖定後 refresh 客戶，避免同交易先前載入的舊欄位繞過 enabled／版本檢查，再回 immutable Snapshot。不存在回 404／E0404，停用回 400／E0400 的 customerId 欄位錯誤。Snapshot 欄位為 id／code／name／version，其中 code 對應 HTTP 的 customerCode；保留已驗證的 stored 原值，不默默修正不合規主檔。
- 呼叫者須先完成自己模組的授權，再使用引用鎖，於相同交易把來源寫入自己的聚合；不得跨 CRM repository 或另開交易補寫。

## 原訂單快照相容性

原 OpenAPI 的 customerId 已是 UUID；customerName 是 1–200 字元快照。相同 customerId 保存時維持原名稱快照，明確改為另一有效客戶才取新來源；CRM 改名或停用不回寫舊訂單。

原 Task 的 pickupName／deliveryName、pickupAddress／deliveryAddress 與 siteContact.name／phone 是文字欄位，容量分別受 200／1000／200／50 等既有上限約束。CRM 不向原 DTO 加 contactId／addressId，也不自動同步已保存文字、統編或 email。

本包不建立 TOM 表、不寫入真正訂單快照。以交易鎖測試驗證引用來源邊界，真正新訂單與停用競爭、快照保存及既有訂單行為由 M2-04 整合驗收。
