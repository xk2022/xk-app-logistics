# FMS 司機 API｜已採用基準

沿 UPMS／FMS 車輛的 /api/v1、同源 HTTPS Session + CSRF、HTTP 狀態及 envelope。管理端點限正常登入 ADMIN、DISPATCHER，本公司資料；本人端點限正常登入 DRIVER，後端由目前 actor 推導綁定主檔，不接受外部司機 ID。

| 方法 | 路徑 | 用途 |
| --- | --- | --- |
| GET / POST | /fms/drivers | 分頁列表／新增 |
| GET / PATCH | /fms/drivers/{id} | 明細／更新與行政出勤狀態 |
| GET | /fms/driver-options | 行政可用司機選項，不保證可派工 |
| GET | /fms/driver-license-types | 固定分類窄查詢，不放寬一般 ADM 權限 |
| GET | /fms/drivers/me | DRIVER 本人必要資料 |
| GET | /fms/driver-account-options | 限已授權的綁定候選摘要，不回一般帳號列表或敏感欄位 |

PATCH 需 version、至少可變欄位；禁止覆寫 UUID、司機代碼、操作者等系統欄位。帳號選擇性一對一關聯，變更需驗目前身分及唯一性、與並發角色異動協調；不新增 UPMS 管理權，不接受非 DRIVER 或不可用帳號。管理者綁定操作若需未核准的額外授權，先記錄並確認。本人資料不得跨人查詢。400 驗證、401 未登入、403 無權限、404 找不到、409 重複或版本衝突；不提供 DELETE。正式 DTO 與獨立 OpenAPI 在實作前確認，既有訂單契約不變。
