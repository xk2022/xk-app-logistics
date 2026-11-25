# 邏輯資料模型｜Draft

| 領域 | 概念實體 | 關係 |
| --- | --- | --- |
| 帳號 | Account、Role | 首片三角色，細部多角色待決 |
| 客戶 | Customer、Contact、Address | 客戶 1:N 訂單、聯絡人、常用地址；見 [CRM 模型](crm-model.md) |
| 訂單 | TransportOrder、ShippingDetails | 訂單 1:N 項目；船務適用性按類型 |
| 作業 | OrderItem、TransportTask、TaskDependency | 項目 1:N 任務；依賴同單無環 |
| 資源 | Driver、Vehicle | 任務派工引用司機及車輛；[FMS 車輛模型](fms-vehicle-model.md)、[FMS 司機模型](fms-driver-model.md) |
| 派工 | DispatchAssignment、ResourceReservation | 每任務至多一組有效派工；歷史另存 |
| 回報 | TaskEvent、PhotoEvidence | 任務／回報關聯證據，權限及保留待決 |

UPMS／ADM／CRM 與 [OrderCore／TOM](tom-model.md) 的實際表、索引及 V1–V5 已由對應工作包定稿與驗證；FMS 車輛 V6 已交付，司機 V7 待實作，其他後續領域尚未建表。歷史工程基準承接[首片詳細模型](https://github.com/xk2022/codex-xk-logistic/blob/7092510c7e63a3f5781e1f0a24787a32e3248ac4/docs/FIRST_SLICE_DATA_MODEL.md)及[40 筆欄位規格](https://github.com/xk2022/codex-xk-logistic/blob/7092510c7e63a3f5781e1f0a24787a32e3248ac4/docs/FIRST_SLICE_ORDER_SPEC.md)。UUID、審計欄位、聚合版本與重送依既有契約；新版空庫，不匯入所詢問的舊客戶、訂單與照片。
