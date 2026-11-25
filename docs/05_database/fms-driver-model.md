# FMS 司機模型｜已採用基準

| 欄位 | 說明 |
| --- | --- |
| id | UUID，由伺服器產生 |
| driverCode | 唯一穩定司機編號，包含停用／離職 |
| name / phone / email | 姓名、必要業務聯絡方式；格式於工程契約明定 |
| accountId | nullable，UPMS 帳號 FK，非空唯一；角色與有效性另由 UPMS 驗證 |
| employmentStatus | ACTIVE／INACTIVE／LEFT（工程命名候選） |
| dutyStatus | ON_DUTY／OFF_DUTY，與在職狀態分開 |
| licenseType / licenseReference / licenseDueDate | 類別、必要證照資訊及期限，自然日；最小化敏感資料 |
| version / audit | 樂觀鎖、建立／修改時間與操作者 |

建議 fms_driver 表，使用 Flyway V7，保持 V1–V6。駕照類別使用受控分類框架；未核准正式選項不種資料。無硬刪除、無任務占用或司機自行修改出勤接口；非在職不能視為行政可用。帳號綁定、解綁、變更及相關讀取使用 UPMS 公開可信服務與一致性交易；不能跨 repository，不能以 FK 或前端身分代替角色驗證。

本人 DTO 只回必要司機主檔資料，避免完整證照識別、帳號其他資料或管理稽核欄位外洩。未綁定或無有效授權回適當拒絕／查無資料。正式證件資訊保存政策尚待確認，不建立圖片上傳。詳細 DTO、型別及唯一約束於實作前定稿。
