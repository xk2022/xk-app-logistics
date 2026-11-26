# TOM 正式受理 API｜已採用基準

新增獨立契約，保留原 [五項 API](tom.md) 與四份固定來源。擬定 `POST /api/v1/orders/{orderId}/accept`，正常登入 ADMIN／DISPATCHER 本公司範圍，Session／CSRF、Idempotency-Key 與 expectedVersion 按 TOM 慣例。DRIVER 不開放整單或受理。

成功回 200，使用 TOM OrderEnvelope 相容資料顯示 OPEN＋ACCEPTED 及新版本；受理事件細節另由獨立契約明定。重送同 key 同內容返回原成功業務結果且無額外轉態，先確認當前授權；不同 body 或處理中依既有衝突語意。新 key 對已受理訂單不得再次建立事件。新受理須在同交易重驗前態、版本、有效客戶、ORD-P01–P03 及現有必要關聯；不信任先前 acceptance-check 結果。

失敗使用相容 TOM 錯誤語意，缺項可定位欄位，前態／版本／重送衝突 409；新操作的精確 schema、錯誤碼及欄位由獨立 OpenAPI 定稿。舊 GET/POST/PUT/check 的 schema 與既有行為保持相容；acceptance-check 仍純讀、NOT_GRANTED，不增加隱性受理。受理後 PUT 按原 OPEN+DRAFT 守衛拒絕。無派工、撤回、取消或結案入口。
