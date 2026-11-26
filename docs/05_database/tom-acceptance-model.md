# TOM 受理資料模型｜已採用基準

沿既有 [OrderCore/TOM](tom-model.md) 聚合及 V1–V7；增量 Flyway V8 擬建立不可變受理事件（表名由工程定稿），不修改歷史 migration。

| 實體 | 責任 |
| --- | --- |
| OrderCore | 同一 UUID、lifecycle 保持 OPEN |
| TOM | acceptanceStatus 由 DRAFT 一次轉 ACCEPTED；聚合版本恰加一 |
| AcceptanceEvent | orderId、受理前後版本、actorId、acceptedAt、不可變當次完整聚合快照；每訂單至多一筆 |
| 原重送紀錄 | 沿用既有 actor／method／path／key 隔離及原子結果保存 |

交易先驗目前權限，按既有模組鎖順序協調 CRM 有效引用、訂單聚合與版本；確保客戶停用／草稿保存／同單受理競爭不產生不一致。成功受理與事件、版本及重送紀錄原子提交；失敗全回滾。歷史草稿快照、ID、名稱與任務依賴保留，不重新建任務。不得由客戶端指定 actor 或時間，不新增占用表、派工或正式資料。已受理舊草稿 PUT 拒絕，不建立撤回轉態。本模型為工程基準，尚無 DDL 或實測。
