# FMS 車輛 API｜已採用基準

Base /api/v1，沿 UPMS／CRM 同源 HTTPS Session + CSRF、HTTP 狀態與 envelope；ADMIN、DISPATCHER 正常登入才可操作本公司車輛。DRIVER 尚無整體車隊或本人指派 API；該摘要待 TMS 實作另行授權。

| 方法 | 路徑 | 用途 |
| --- | --- | --- |
| GET / POST | /fms/vehicles | 分頁列表／新增 |
| GET / PATCH | /fms/vehicles/{id} | 明細／修改主檔與管理狀態 |
| GET | /fms/vehicle-options | 主檔可用選項；不代表實際可派工 |
| GET | /fms/vehicle-types | 依業務限定車種選項，不擴大 ADM HTTP 權限 |

PATCH 需 version 與合法可變欄位，拒絕系統身份及非法狀態。未知欄位、錯誤查詢與無權限分別按既有模組規範處理；重複車牌、版本衝突回 409。列表支援有界分頁、字面搜尋、車種類型與管理狀態篩選、穩定排序。沒有 DELETE、使用中手動設定、派工／配對或正式檢驗合規判定。詳細 DTO、欄位限制及獨立 OpenAPI 於開工前確認，原訂單 API 不動。
