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
