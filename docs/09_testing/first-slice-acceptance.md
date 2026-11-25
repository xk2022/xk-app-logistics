# M2 首片驗收計畫

M2-04 已執行 JUnit／Testcontainers 與真實 HTTP 草稿契約測試；本包執行 Vitest 與桌面／Pixel 7 Playwright 前端整合驗收，實際進度見 [M2-05 驗證](m2-05-validation.md)。使用隔離空庫與合成案例，不執行正式受理。

| ID | 驗收 |
| --- | --- |
| LG-F01 | 最小草稿：類型＋有效客戶成功保存與重讀 |
| LG-F02 | 進口：船務、提單、多櫃與多段任務 |
| LG-F03 | 出口：船務、ETD、交櫃期限 |
| LG-F04 | 國內一般貨物不要求船務，必要件數與路線校驗 |
| LG-F05 | 同單穩定 ref、依賴無環及完整替換，非法資料整次拒絕 |
| LG-F06 | 版本衝突、重送、交易回滾與歷史快照 |
| LG-F07 | 管理員／調度員本公司資料；司機整單拒絕；重播不得越權 |
| LG-F08 | 只讀完整性回傳缺漏，不改狀態、不占用資源 |
| LG-F09 | 前端對缺漏、API 失敗、空列表明確呈現 |
| LG-F10 | 空 DB 可由 Flyway 重建；測試資料隔離 |

[合成案例 JSON](../06_api/order-draft-contract-examples.json) 和 [OpenAPI](../06_api/openapi.yaml) 是測試輸入，不是運行證據。歷史靜態檢查報告：[來源](https://github.com/xk2022/codex-xk-logistic/blob/7092510c7e63a3f5781e1f0a24787a32e3248ac4/docs/api/CONTRACT_CHECKS.md)。M2-04 的真實服務／PostgreSQL 結果見 [M2-04 驗證](m2-04-validation.md)，M2-05 的 UI 結果、受測版本與交付追蹤見上述實測紀錄。
