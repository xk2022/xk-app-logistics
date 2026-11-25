# CRM API 契約｜已採用基準

沿 [UPMS](upms.md)／[ADM](adm.md) 的 /api/v1、同源 HTTPS Session + CSRF、HTTP 狀態及 envelope；CRM 全部操作限定正常登入 ADMIN、DISPATCHER，本公司資料。DRIVER 禁止存取客戶主檔，未核准的司機摘要留待派工工作包。

| 方法 | 路徑 | 用途 |
| --- | --- | --- |
| GET / POST | /crm/customers | 分頁查詢／新增 |
| GET / PATCH | /crm/customers/{id} | 明細／更新、啟停 |
| GET / POST | /crm/customers/{id}/contacts | 查詢／新增聯絡人 |
| PATCH | /crm/customers/{id}/contacts/{contactId} | 更新／啟停 |
| GET / POST | /crm/customers/{id}/addresses | 查詢／新增常用地址 |
| PATCH | /crm/customers/{id}/addresses/{addressId} | 更新／啟停 |
| GET | /crm/customer-options | 有效客戶選項（分頁搜尋） |

所有路徑相對 /api/v1。建立使用必要業務欄位；更新需 version，禁止覆寫 ID、客戶代碼、父關聯及操作者。查詢以 page/size/q/enabled、固定排序白名單和穩定次鍵為基準。子項父關聯由伺服器確認；停用父客戶不提供新增引用。無 DELETE、無公開客戶入口。正常 200/201，400 欄位、401 未登入、403 無權限、404 範圍內找不到、409 重複或版本衝突。詳細 DTO、驗證及獨立 CRM OpenAPI 於開工前定稿，原訂單 OpenAPI 0.2.0 保持原樣。
