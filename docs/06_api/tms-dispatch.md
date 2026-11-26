# TMS 派工 API｜LG-M3-04 工程契約

依 [Q36–Q40](../98_decisions/2026-11-26-tms-dispatch.md)、[領域流程](../03_domain/tms-dispatch-workflow.md)及[模型](../05_database/tms-dispatch-model.md)定稿；機器契約為[獨立 OpenAPI 0.1.0](tms-dispatch-openapi.yaml)。原 TOM 五項、正式受理、UPMS、ADM、CRM、FMS 契約與 V1–V8 不變。本文記錄工程界線，不代替實際驗收證據。

## 操作與授權

所有路徑前綴為 `/api/v1`。前六項管理操作只限正常 ADMIN／DISPATCHER；本人操作只限正常 DRIVER，不擴大 ADM 權限。沿現有同源 HTTPS、單一 HttpSession 的 XKSESSION／JSESSIONID 別名、首次改密限制與安全撤銷。

| 方法 | 路徑 | 精確成功 data |
| --- | --- | --- |
| GET | `/tms/tasks` | `TaskPage` |
| GET | `/tms/tasks/{taskId}/dispatch` | `DispatchDetail` |
| POST | `/tms/tasks/{taskId}/dispatch` | `CommandResult` |
| POST | `/tms/dispatches/{id}/revoke` | `CommandResult` |
| POST | `/tms/dispatches/{id}/reassign` | `CommandResult` |
| GET | `/tms/resources` | `ResourcePage` |
| GET | `/tms/my-tasks` | `MyTaskPage` |

`/tms/resources` 是完成已採用資源選擇介面所需的有界候選查詢。候選與占用阻擋由伺服器提供；不將原 FMS 行政 options 誤當可派保證，也不修改原 FMS 路徑。

成功固定 HTTP 200，精確 company envelope `{code:"0000",message,data,timestamp}`；timestamp 為含 `+08:00` 的 ISO 8601。沒有 TOM 的 `OK`、`traceId` 或新加整單欄位。全部 DTO 欄位與 nullability 由 OpenAPI 列明，`additionalProperties:false`。

## 有界讀取

| 操作 | 可接受 query |
| --- | --- |
| tasks | `page`、`size`、`state`、`orderId`、`q` |
| task dispatch | `historyPage`、`historySize` |
| resources | 必填 `taskId`／`position`，選填 `assignmentId`／`page`／`size`／`q` |
| my-tasks | 僅 `page`／`size` |

page／historyPage 預設 0、範圍 0–100000；size／historySize 預設 20、範圍 1–100。`q` 經 NFC／Unicode 邊界 strip 後最多 100 code points，採字面搜尋，`%`／`_` 不當萬用字元。state 為 `UNASSIGNED`（預設）、`ASSIGNED` 或 `ALL`。資源 position 為 `DRIVER`、`MAIN`、`TRAILER`。UUID 為原身份，不接受 caller 指定 company scope。

未知、重複或非法 query 一律拒絕；不接受自由 sort。任務固定 taskNo／id 排序；歷史包含目前派工，以 assignedGeneration 由新至舊，再以 id 決定順序。Page 精確為 `{content,page,size,totalElements,totalPages}`，每頁最多 100 筆，不於單頁移除阻擋任務或占用資源來偽造總數。

資源 `assignmentId` 若提供，必須是同一 task 目前有效 ASSIGNED，才可在改派情境重用該次自身占用。`ResourceCandidate` 回明確 administrative／occupied／policy 狀態、最小占用識別及 blockingReasons；個別候選合格仍不保證整組可派，寫入須重新檢查完整組合。

## 指派、撤派與改派

三項 POST 不接受 query，需有效 CSRF 與恰一個 `Idempotency-Key`，key 精確 `[A-Za-z0-9._:-]{1,128}`、區分大小寫。body 僅接受嚴格 UTF-8 JSON 物件，拒重複 key、未知欄位、trailing token 或不成對 surrogate。

| 操作 | 精確 body |
| --- | --- |
| assign | `{expectedGeneration,driverId,mainVehicleId,trailerId}` |
| revoke | `{expectedGeneration,reason}` |
| reassign | `{expectedGeneration,driverId,mainVehicleId,trailerId,reason}` |

每個列出的欄位必填；`trailerId` 可為 null。主車與非 null 拖板不能是相同 UUID；改派不得以相同 driver／main／trailer 三元組製造 no-op 歷史。reason 為新 TMS 文字，NFC、Unicode 邊界 strip 後 1–500 code points，拒控制字元但允 LF／TAB／CR；保存正規化結果。OpenAPI 的 `x-normalizedMinLength/MaxLength` 描述 canonical 界線，不是假定 raw-string 長度。

每個 task 的 generation 初值 0；每次成功 assign／revoke／reassign 恰加 1。expectedGeneration 為 0–9007199254740991 的數學整數，1／1.0／1e0 等價；版本上限不能再成功增加。Assignment version 與 task generation 分開：建立為 version 0，僅一次終止流轉後為 version 1，不作 task 的併發 token。

只有 OPEN＋ACCEPTED 訂單的有效同單任務可新指派。任何前置依賴在 M4 尚無完成／交接證據前均阻擋；撤派、釋放、經過時間都不是前段完成。來源版本變動或不再受理須明確阻擋，不覆寫派工快照，也不重新詮釋 CRM 今日名稱／啟用狀態。

指派產生 ASSIGNED 與即時排他占用。撤派只對目前 ASSIGNED，轉 REVOKED 並原子釋放；釋放不要求今日新派資格或 OPEN 狀態。改派只對目前 ASSIGNED，舊 SUPERSEDED、新 ASSIGNED 與占用交換同交易。任何來源、版本、政策、約束或持久化失敗，原派工與原占用完整保留。

## 原子性、快照與重播

鎖順序為 canonical UPMS／fresh actor → 排序後 FMS 來源 → TOM 來源 → TMS task generation／assignment／reservation；依公開服務鎖定及 refresh，不跨模組直接讀寫 repository 或來源資料表。讀取的 hint 必須在鎖定後重驗。司機與 VEHICLE 分別排他；主車／拖板共用車輛 namespace，資料庫部分唯一約束同時保護一 task 一組有效派工與一 resource 一個有效占用。

完整命令結果、generation、assignment、reservations、不可變 event 與成功重送紀錄同交易提交。V9 的既有受理任務 identity backfill 只是可重建索引，不捏造任何歷史派工、事件、占用、人員或時間。快照與結果用 textual JSON 保存，保留原 TOM NUL、空白／NFD、decimal scale、offset、null 與空物件語意，不轉 jsonb 或用會解碼 NUL 的資料庫 JSON extraction。

每個成功 POST 必須回 `Idempotency-Replayed: false|true`，不要求 Location。成功重播先重新確認目前管理權限，再回原成功 status／data；timestamp 可更新。重播先於今日 generation、狀態、來源與政策檢查，不重新占用、加版或加事件。原派工日後撤／改派，歷史成功重播仍是原結果。

scope 為伺服器公司範圍＋actor＋method＋canonical UUID path＋key。失敗不保存成功結果；D10 未核准前不自動清除重送紀錄。未知結果保留原 key／body／expectedGeneration，只能明確同次重試，不能自動換 key 或背景重送。協定／形狀／授權錯誤不能據此推定原次已回滾。

## 管理與本人資料最小化

`TaskSummary` 為任務、訂單識別、來源 orderVersion、TMS generation、必要 route 名稱、前置／目前派工／政策阻擋摘要。`TaskSource` 僅當段的標的、路線、聯絡、planned times 與 requiredTask；沒有整單 remark、shipping、customer 或其他段依賴圖。

`Assignment` 保留當次 task、司機及主／拖板身份快照、來源版本、generation、管理 actor/time、狀態與原因；reservations 固定 2–3 筆、每 assignment 相關 events 固定 1–2 筆，歷史另分頁。主檔改名／聯絡修正不覆寫快照。選定快照、終止 assignment、event 與 reservation 取得資料不可改寫或硬刪；release 只能一次。

DRIVER 由 fresh 正常帳號與目前有效 FMS 綁定推導本人有效 ASSIGNED。未綁或無效綁定回 404，有效綁定無派工回空頁；不能傳 driverId、accountId、orderId 或搜尋條件。撤／改派、解綁、改綁、角色變更、停用與 logout 後，舊權限不再有效。

`MyTask` 精確只有 assignmentId、taskId、taskNo、item、taskKind、pickupName、pickupAddress、deliveryName、deliveryAddress、siteContact、plannedStartAt、plannedEndAt、assignedAt、policyMode。不回 order/customer/driver/account/resource IDs、車牌、證照、audit、history、reservations、generation 或整單備註，管理端也不能假借本人 endpoint 查他人。

## 適格政策與 FMS 協調

Production `DispatchEligibilityPolicy` 一律回 POLICY_NOT_CONFIRMED；沒有 env flag、ADM parameter 或 production HTTP 可核准政策。只有 test-source 的明確合成政策可在 `xk_logistics_e2e` 安全 gate 下使用 SYNTHETIC_TEST_ONLY；test configuration／launcher 不進正式 JAR，不宣稱檢驗、載重、工時或法律適格。

PolicyMode 為 UNCONFIRMED／SYNTHETIC_TEST_ONLY；blockingReasons 為 SOURCE_NOT_ACCEPTED、PREREQUISITE_NOT_READY、POLICY_NOT_CONFIRMED、ALREADY_ASSIGNED、ADMIN_UNAVAILABLE、RESOURCE_OCCUPIED。沒有已核准組合的正常環境必須拒絕新指派／改派；撤派仍可釋放原有出發前派工。

FMS 於 canonical 與 fresh 來源鎖後呼叫自身定義的公開 guard。占用中的車輛不得失去 AVAILABLE，司機不得失去 ACTIVE＋ON_DUTY；也拒絕實際變更車種／載重／檢驗期限或駕照類別／期限，以保留選定來源基礎，不新增法定門檻。未變值／no-op 保留原 FMS 語意；姓名／聯絡修正及帳號綁定維護允許，派工歷史不被覆寫。衝突沿原 FMS E0409／field-map envelope，不改成 TMS 格式。

## 錯誤

精確 `{code,message,data,timestamp}`。data 為 null，或僅含非空組合 `{reason?,actualGeneration?,fields?}`；fields 是 string→string map，不回 SQL、exception cause、request body 或秘密。

| HTTP | code | reason／語意 |
| --- | --- | --- |
| 400 | E0400 | VALIDATION_ERROR＋fields；shape/query/validation/no-op |
| 401／403／404 | E0401／E0403／E0404 | 未登入／含 CSRF 無權／查無，data 可 null |
| 405／415 | E0405／E0415 | method／media type 語意 |
| 409 | E1601 | VERSION_CONFLICT，另含 actualGeneration |
| 409 | E1602 | RESOURCE_OCCUPIED |
| 409 | E1603 | INVALID_STATE，含來源版本、前態、generation 上限 |
| 409 | E1604 | PREREQUISITE_NOT_READY |
| 409 | E1605 | POLICY_NOT_CONFIRMED |
| 409 | E1606 | MASTER_UNAVAILABLE |
| 409 | E1607／E1608 | IDEMPOTENCY_KEY_REUSED／IDEMPOTENCY_IN_PROGRESS |
| 409 | E1609 | PROTECTED_RELATION |
| 500 | E0500 | 安全內部錯誤摘要 |

全部 business 409 必須附表中精確 reason，VERSION_CONFLICT 亦需 actualGeneration。沒有 M4 出發、完成、異常、照片、司機回報、取消整單、正式部署或資料清理 API。

### 403 的安全原因

不新增狀態碼或欄位，使用既有 `data.reason`：已登入請求的 CSRF 拒絕為 `CSRF_INVALID`，角色／目前授權拒絕為 `FORBIDDEN`。只回原因，不回帳號或來源資料。匿名 CSRF 最終仍為 401／null。用戶端不能從 message 猜測：CSRF 只更新 token 並保留原未知請求等待明確重試，FORBIDDEN／401 清敏感畫面並重新授權，不能宣稱原次未提交；缺少／未知 reason 也採保守安全退出。schema 保留既有 nullable ErrorData 相容性。
