# ADM 資料模型｜LG-M2-02

依 [Q11–Q15](../98_decisions/2026-11-25-adm.md)、[ADM 工程決策](../98_decisions/2026-11-25-adm-engineering.md)與 [API 契約](../06_api/adm.md)。本文件細化工程模型，不核准正式參數鍵、字典項目或非 ADMIN 分類讀取權限；實際驗證另記。

## 結構與身份

資料位於 `logistics` schema，由新增 Flyway V3 建立。既有 V1/V2、UPMS 帳號及原訂單契約不改寫。

| 表 | 邏輯欄位與約束 |
| --- | --- |
| adm_dict_category | UUID `id`；唯一且不可變 `code`；`name`、`enabled`、`sort_order`、`read_only`、樂觀鎖 `version`；`created_at`、`updated_at` |
| adm_dict_item | UUID `id`；不可變 `category_id` FK 與 `code`；`label`、`enabled`、`sort_order`、`read_only`、`version`、建立／更新時間；`(category_id, code)` 唯一 |
| adm_parameter_definition | 不可變 `key` 為目錄身份；`name`、`value_type`、`description`；TEXT 長度／允許值、NUMBER 上下界／小數位等核定限制；無一般管理寫入 API |
| adm_parameter | UUID `id`；唯一且不可變 `parameter_key`；`(parameter_key, value_type)` 複合 FK 指向 definition；`name`、`value_text`、`description`、`enabled`、`version`、建立／更新時間；DTO 對外欄位名為 key |
| adm_change_log | UUID `id`；`target_type`、`target_id`、`action`、`actor_id`、`actor_username`、`occurred_at`，以及 JSONB `before_value`／`after_value` 快照（DTO 為 before／after）；actor_id FK 保留 UPMS 帳號參照完整性；只新增、不提供修改／刪除 API |

UUID 用於資源識別；`code`／`key` 用於穩定業務代碼，不因名稱或排序調整而變動。`version` 為資料樂觀鎖，與 UPMS 的 `authVersion` 不同，不能用來切換認證或登入限流版本。

## 欄位與生命週期

- `code`／`key`：2–64 個 ASCII 字元，格式 `[A-Z][A-Z0-9_]{1,63}`；不自動 trim 或轉大小寫。建立後不能修改，項目也不能移往其他分類。
- 名稱／標籤：NFC、strip 後 1–80 個 Unicode code points。說明：NFC、strip 後 0–500 code points，可包含 LF 換行，拒絕其他控制字元。
- `sort_order`：0–1000000 的整數。更新必須提供目前非負整數 `version`，衝突回 409，不能靜默覆寫。
- 不提供硬刪除。停用資料保留原 ID、代碼及顯示資訊，管理列表仍可查閱；有效選項必須同時符合分類啟用與項目啟用。
- 分類停用不改寫其下所有項目的啟用旗標。重新啟用分類時，項目仍按自身旗標決定是否成為有效選項；歷史名稱／代碼不被清除。
- `read_only` 由系統資料身份決定，不由請求 DTO 指定。唯讀分類及其固定項目拒絕名稱、標籤、排序、啟停或新增項目等管理異動。

## 參數核定目錄

`adm_parameter_definition` 是只讀核定目錄，不是可由 ADMIN 任意增加的參數管理功能。V3 正常初始化時 definition 與 parameter 表均為空；目前沒有任何正式核准 key。未來必須先取得業務核准，再以經審查的 migration 新增目錄定義及其限制，才能建立該 key 的值；本工作包不推定任何正式名稱或預設值。

目錄僅允許 `TEXT`、`NUMBER`、`BOOLEAN`：

| 型別 | 驗證與儲存 |
| --- | --- |
| TEXT | `value_text` 做 NFC、不 trim，最多 2000 code points；再套用 definition 的 minLength／maxLength 或完整字串 allowedValues，不以子字串比對 |
| NUMBER | 使用 `-?(0\|[1-9][0-9]*)(\.[0-9]+)?` plain decimal 字串，不接受正號、前置零或科學記號；以 BigDecimal 驗證有限精度、小數位及含端點上下界，最大 38 位精度／12 位小數，再受 definition.decimalScale 限制；去除多餘尾零後以 plain string 儲存 |
| BOOLEAN | 僅接受小寫 `true`／`false`，不把其他文字或數字轉成布林 |

參數 key FK 防止不存在的目錄身份；應用層檢查建立時的 valueType 與目錄一致，更新不能改 key／型別。目錄限制不是由一般值 DTO 覆寫的欄位。不得以參數表保存憑證、Session／CSRF、密碼雜湊、安全限流、角色、狀態機、連線字串或部署配置。

隔離測試可在 `src/test` 向指定合成資料庫加入 `SYNTHETIC_TEXT`、`SYNTHETIC_NUMBER`、`SYNTHETIC_BOOLEAN` 定義以驗證三型別。這些不是正式核准 key，不進 V3 正常種子，不提供產品 seed flag 或 definition 寫入端點。

## 唯讀展示與初始資料

- `ORDER_TYPE` 僅展示既有固定枚舉 `IMPORT`、`EXPORT`、`LOCAL`，標籤使用原枚舉字串；分類及項目均唯讀。展示資料不成為訂單狀態機或枚舉的控制來源，也不推定額外翻譯／業務意義。
- `CONTAINER_TYPE`、`TASK_KIND`、`VEHICLE_TYPE` 只建立空分類，不填入正式項目。合成測試值只存在隔離 fixture，不能當作現場業務核定值。
- 正常初始化不建立參數 definitions／值、客戶、訂單、車輛或派工資料。

V3 基線為 4 個分類、3 個唯讀枚舉項目，definition、parameter、change_log 均 0 筆。分類顯示名稱只描述工程空分類，不代表其下正式項目已核准。

## 交易、稽核與模組邊界

ADM 不直接操作 UPMS repository。寫入交易先呼叫 UPMS 公開授權 facade，在同一交易取得 canonical lock 並重新鎖定／核對操作者啟用、單角色、強制改密及 authVersion，再鎖定目標 ADM 資料；停權或撤銷不能與舊認證寫入競爭而越過守衛。

資料異動與 `adm_change_log` 的前後 DTO 快照同交易提交；新增的 before 為 null，更新保存異動前後可公開的模型資料及操作者身份。不儲存帳號密碼、Session／CSRF 或內部憑證。稽核寫入失敗時整筆業務異動回滾，不回報假成功。

`AdmQueries` 是可信伺服器模組使用的公開查詢介面，不接收 SessionPrincipal、不自動執行終端使用者角色驗證。HTTP 仍由控制器及公開 UPMS guard 授權；內部使用者必須在自己的業務流程先完成權限與範圍判斷，再呼叫有效選項／歷史標籤／啟用參數查詢，不能跨 repository。歷史標籤查詢可讀停用資料，並非重建任意時間點的舊標籤快照；前後值歷史由稽核保存。

選項查詢不是預先授權未來的業務寫入或歷史引用驗收；新增引用的權限、停用一致性及交易驗證，仍須在後續工作包獨立完成。actor_id 的 schema FK 只維持參照完整性，不授權 ADM 直接操作 UPMS repository。
