# ADM API 契約｜已採用基準（DTO 待細化）

沿既有 UPMS 的 /api/v1、JSON envelope、HTTP 狀態及同源 Session/CSRF；所有寫入須經後端角色守衛。詳細 DTO 及 OpenAPI 由實作前確認，原訂單 OpenAPI 保持原樣。

| 方法 | 路徑 | 用途 | 角色 |
| --- | --- | --- | --- |
| GET / POST | /adm/categories | 列表／新增 | ADMIN |
| PATCH | /adm/categories/{id} | 名稱、排序、啟停及 version | ADMIN |
| GET / POST | /adm/categories/{id}/items | 列表／新增 | ADMIN |
| PATCH | /adm/items/{id} | 標籤、排序、啟停及 version | ADMIN |
| GET / POST | /adm/parameters | 查詢／建立核定參數 | ADMIN |
| PATCH | /adm/parameters/{id} | 更新核定值、啟停及 version | ADMIN |
| GET | /adm/options/{categoryCode} | 僅啟用選項、穩定排序，限業務明確授權的分類 | ADMIN；其他角色需核准分類權限 |
| GET | /adm/change-logs | 分頁查詢異動 | ADMIN |

建立採 code/key、顯示名稱及適用型別；更新禁止改代碼與系統身份欄位。讀取包含 ID/version、啟停與排序；查詢限制分頁、排序白名單。正常回 200/201，無效欄位 400、未登入 401、無權限 403、不存在 404、重複或版本衝突 409。無 DELETE。未核准的非管理員分類權限預設不開放。
