# M4 資料模型｜設計基準
採增量 Flyway migration（從 V9 後下一可用版本開始），不改寫既有 migration。

| 概念實體 | 責任 |
| --- | --- |
| Execution | 指派 UUID、任務來源、執行階段、獨立版本、時間；每次有效指派最多一個執行紀錄。 |
| ExecutionEvent | 追加式事件、狀態前後、操作者、伺服器時間、理由及關聯。 |
| Evidence | 任務／指派及階段關聯、私有儲存引用、上傳者、時間、驗證狀態；不暴露儲存路徑或敏感資訊。 |
| Exception / Supplement | 異常及補件關聯、版本、歷史與處理結果，保留原始資料。 |
| Completion / Closure | 安全釋放條件、已驗證證據、確認者與時間；結案歸 TOM。 |

名稱與精確欄位依程式現況定義。既有 assignment version、generation、reservation 語意及 API 保持相容，執行版本獨立。沿用 UPMS→排序 FMS→TOM→TMS 鎖順序與來源重讀；以交易、約束、版本及重送一致性防止重複事件與早退占用。儲存與 DB 失敗需可恢復，不得讓未驗證檔案滿足完成條件。未核准正式 provider／保存／清除策略，不執行真實資料刪除。

## M4-01 精確基線

V10 新增 tms_execution、tms_execution_event、tms_execution_command，並對既有 assignment 身分配對加一致性唯一約束；不回填、不改原 V1–V9／派工／占用資料。首次出發建立 DEPARTED/version1，未出發 GET 為虛擬 WAITING/version0，不寫資料。執行、不可變事件與原成功收據同交易，獨立版本；詳見[本包工程定義](../98_decisions/2026-11-26-m4-01-engineering.md)及 migration 唯一來源。後續進度、證據、釋放及結案各包再增量 migration，不預建業務紀錄。

## M4-02 增量結構

V11 追加 tms_execution_evidence、tms_execution_evidence_object、tms_execution_event_evidence、tms_execution_evidence_command 四張空表（最新共34），不改 V1–V10／舊資料或回填證據。metadata／原始私有 bytes／成功收據同交易；進度沿用 V10 execution／event／command，追加 immutable event↔evidence 關聯。個別上傳不升 execution.version，不改原 assignment／generation／reservation。

合成 bytea adapter 只能用同 DataSource／caller transaction，正常 provider unavailable，不代表正式儲存選型；保存／清理未核准，沒有刪除工作。各 identity／phase／version／hash／不可變約束以 migration 及[本包工程採用](../98_decisions/2026-11-26-m4-02-engineering.md)為準。

## M4-03 增量結構

V12新增tms_exception／event／command／supplement_request／supplement_evidence／event_evidence六表，最新40；異常獨立counter、原始UTF8 bytea及json snapshot、typed/compositeFK／immutable history，無回填。既有Evidence只增phaseCHECK允AWAITING，原M4-02POST仍僅兩phase；photos共用同caller transaction provider，不另建公開儲存。33舊領域表及照片／收據保留之實測依[本包驗證](../09_testing/m4-03-validation.md)，不以DDL存在代替測試。

## M4-04 增量結構

V13新增tms_completion／tms_completion_command兩表，最新42；既有reservation只增nullable completion_id，有效索引predicate統一released_at IS NULL AND completion_id IS NULL，舊欄位／收據不改。完整typed terminal／event／witness／ledger／精確二三marker有互反deferred及reverse證明；新reservation INSERT及原選照link集合另封存，不能晚插破壞完成或歷史。具體DDL與[工程採用](../98_decisions/2026-11-26-m4-04-engineering.md)為準，無偽造完成backfill。
