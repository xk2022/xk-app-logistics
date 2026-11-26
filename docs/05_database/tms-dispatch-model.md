# TMS 派工模型｜已採用基準

| 實體 | 責任 |
| --- | --- |
| DispatchAssignment | UUID、同單 orderId/taskId、driverId、mainVehicleId、optional trailerId、狀態、version、當次不可變資源快照、actor/time |
| ResourceReservation | assignmentId、resourceKind（DRIVER/VEHICLE）、resourceId、取得及釋放時間／原因；有效狀態排他 |
| DispatchEvent | assignmentId、動作、前後版本／關聯、actor/time、原因；不可變 |
| Idempotency | 延續 actor／path／key 隔離及原子成功結果；依新契約確認可重用機制 |

表名及精確 DDL 於工程設計定稿，優先 V9 append-only；不更動 V1–V8。車輛主／拖板共用 VEHICLE 排他 namespace，一段一組有效派工以 DB 約束保護。FK 驗證同單任務及資源來源；以可信公開服務執行授權、鎖及 refresh；統一與既有 UPMS canonical、TOM／FMS 鎖序相容的交易順序。併發、重送、撤改派、主檔異動與回滾須以真 PostgreSQL 驗證。

歷史占用、車牌、司機及任務識別不受主檔改名覆寫；不可硬刪、不自動清理（D10 尚待核准）。不新增 M4 回報／照片／結案表。
