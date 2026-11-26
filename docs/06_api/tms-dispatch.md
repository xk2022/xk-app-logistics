# TMS API｜已採用基準

獨立 OpenAPI，以下路徑為工程基準，定稿後開工。原 TOM 五項及正式受理契約不變。

| 方法 | /api/v1 路徑 | 用途 |
| --- | --- | --- |
| GET | /tms/tasks | 有界分頁、已受理任務及派工摘要 |
| GET | /tms/tasks/{taskId}/dispatch | 當前派工與歷史摘要 |
| POST | /tms/tasks/{taskId}/dispatch | 人工指派 |
| POST | /tms/dispatches/{id}/revoke | 出發前撤派 |
| POST | /tms/dispatches/{id}/reassign | 出發前改派 |
| GET | /tms/my-tasks | 由當前 DRIVER 帳號與有效綁定推導本人有效派工 |

前五項限正常登入 ADMIN／DISPATCHER，本人端點只限正常 DRIVER，不接收 driverId、不回整單、其他資源或證件敏感資訊。本人可見欄位限當段必要標的、路線、聯絡及作業指示；實作前按 ACC-P02 最小化。撤改後舊本人權限立即失效。所有寫入沿同源 HTTPS Session／CSRF、Idempotency-Key 及版本控制；重送不可重建占用，未知結果不可盲目換 key，歷史成功重播先驗當前權限。錯誤分辨未登入、無權限、查無、非法前態、版本／資源衝突；新 schema 與 code 依本模組獨立契約定稿。

沒有司機回報／出發／完成／照片／取消整單或正式部署 API。FMS options 僅行政來源；TMS 候選須再查占用、前置與已核准資格，不自動擴大 ADM 讀取權限。
