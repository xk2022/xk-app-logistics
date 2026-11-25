# 首片 API 基準｜已採用範圍

與歷史 v0.2.0 契約相容。新庫保存原始 [OpenAPI](openapi.yaml)、[角色矩陣 JSON](order-draft-access-policy.json)、[合成正反例](order-draft-contract-examples.json) 及[40 筆欄位字典](../05_database/first-slice-fields.csv)；四檔為固定來源原樣快照，內部歷史狀態及原路徑保留，不代表新庫實作。原始契約解說：[來源](https://github.com/xk2022/codex-xk-logistic/blob/7092510c7e63a3f5781e1f0a24787a32e3248ac4/docs/api/README.md)。

| 方法 | 路徑（/api/v1） | 功能 |
| --- | --- | --- |
| POST | /orders | 建立草稿 |
| GET | /orders | 列表查詢 |
| GET | /orders/{orderId} | 重讀完整聚合 |
| PUT | /orders/{orderId} | 草稿完整替換 |
| GET | /orders/{orderId}/acceptance-check | 只讀檢查三項已確認規則 |

只有 ADMIN、DISPATCHER 可在本公司範圍操作；DRIVER 不開放整單 API。只讀完整性不等於受理／派工。沿用穩定 clientRef、交易一致性、expectedVersion、重送及錯誤契約；詳細模型以[既有工程模型](https://github.com/xk2022/codex-xk-logistic/blob/7092510c7e63a3f5781e1f0a24787a32e3248ac4/docs/FIRST_SLICE_DATA_MODEL.md)為基準。精確安全政策見[角色與認證](../04_architecture/access-baseline.md)。

LG-M2-04 已依原五操作實作 API、DB 與合成案例驗收，結果見 [M2-04 驗證](../09_testing/m2-04-validation.md)及 [TOM 整合](tom.md)。四份固定來源保留原始歷史註記，不修改它們來冒充當前執行結果；M2-05 的完整草稿操作 UI 與首片整合實測見 [工作台規格](../07_ui-ux/order-workspace.md)及 [M2-05 驗證](../09_testing/m2-05-validation.md)，正式受理與派工仍屬後續。
