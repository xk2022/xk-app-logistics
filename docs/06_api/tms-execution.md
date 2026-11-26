# M4 API｜設計基準
新增獨立執行契約，保留 [M3 契約](tms-dispatch.md)（包括 /tms/my-tasks 最小摘要）、M2 訂單及 FMS 契約。各包先定義精確 method/path、DTO、OpenAPI、權限、錯誤回應及實際 HTTP 驗證，再實作。

| 操作群 | 責任 |
| --- | --- |
| 本人任務詳情／出發 | DRIVER 有效綁定及指派；禁止客戶端指定身份。 |
| 進度／證據 | 受控轉移、必要證據策略、私有上傳／授權讀取、伺服器驗證。 |
| 異常／補件 | 本人回報、受權管理端處理、保留歷史及補件。 |
| 完成／釋放 | 安全解除確認、交易內完成與唯一占用釋放。 |
| 確認／結案 | 受權管理端操作，TOM 重新確認任務與異常。 |

沿用 HTTPS、Session／CSRF、既有錯誤格式、版本與 idempotency 原則；重播必須重新核對權限，不可跨人取得歷史回應。拒絕未授權資料、非法狀態轉移、過期指派、缺證據及未核准政策。敏感資料與照片不得出現在列表、錯誤、日誌或公開連結。正式政策未核准時拒絕相關操作，正向契約測試只使用隔離合成資料。

## LG-M4-01 精確契約｜本人詳情與出發

本包機器契約為[獨立 OpenAPI 0.1.0](tms-execution-openapi.yaml)，僅新增以下兩項 DRIVER 操作。M3 七項派工 API、原 `/tms/my-tasks` 十四欄列表及全部 M1–M3 契約保持原樣；後續進度、照片、異常、完成、釋放及結案採各包新增獨立契約，不加入本包 DTO。

| 方法 | /api/v1 路徑 | 成功 data |
| --- | --- | --- |
| GET | `/tms/my-tasks/{assignmentId}` | `MyTaskExecutionDetail` |
| POST | `/tms/my-tasks/{assignmentId}/depart` | `DepartureResult` |

兩項皆限正常 DRIVER；ADMIN／DISPATCHER 不能代理操作。assignmentId 是本人目前指派的選擇器，不是可指定的身份。帳號、司機及公司範圍均由伺服器取得；兩項都不接受任何 query，包括 driverId、accountId、taskId、history、sort，重複／未知 query 一律拒絕。沿用同源 HTTPS、XKSESSION／JSESSIONID 單 HttpSession、安全撤銷及首次改密限制。

### 精確資料與獨立版本

成功固定 HTTP 200，company envelope 精確為 `{code:"0000",message,data,timestamp}`，timestamp 使用有效 Gregorian ISO 8601 `+08:00`，不加入 TOM 的 `OK`／`traceId`。

`MyTaskExecutionDetail` 平鋪原十四欄 MyTask，再增加一個 `execution` 物件，共十五個 top-level 欄位。十四欄為 assignmentId、taskId、taskNo、item、taskKind、pickupName、pickupAddress、deliveryName、deliveryAddress、siteContact、plannedStartAt、plannedEndAt、assignedAt、policyMode；值語意完全沿用原合約。原列表不增加 execution 欄位。

`execution` 精確為 `{phase,version,departedAt}`。phase 定義 WAITING、DEPARTED、IN_PROGRESS、AWAITING_COMPLETION、COMPLETED；後三值只是後續工作包的讀取 enum 預留，本包不提供其命令或宣稱完成其政策驗證。沒有 execution row 時，GET 純讀投影 WAITING／version 0／departedAt null，不建立資料、事件、actor 或虛假歷史。首次出發成功才保存 DEPARTED／version 1／非 null 伺服器時間。

execution version 與 assignment.version、task generation、TOM／FMS 版本完全獨立。所有整數皆為 0–9007199254740991 的安全數學整數；1／1.0／1e0 同義。非 WAITING 階段 version 至少 1，departedAt 非 null。後續 GET 可回同一原 schema 已定義的進度值，不能任意加欄。

`DepartureResult` 只有 `{assignmentId,taskId,execution}`；成功 execution 固定 DEPARTED／1／非 null departedAt。這是原次不可變成功收據，不是目前狀態或完整任務，不回 actor、事件、資源或其他人資料。兩項的所有物件欄位皆必填、未知欄位拒絕，nullable 以 OpenAPI 明列。

本人資料不回 order/customer/person/account/resource IDs、車牌、證照、actor/event/history、照片、私有儲存路徑或完整訂單。原 TOM NUL／NFD／空白、decimal 字串及 scale、原 offset／微秒、null 與存在但兩欄 null 的聯絡物件皆保留，不 trim、轉 number 或用瀏覽器毫秒時鐘改寫原值。policyMode 是當次派工記錄，並不保證目前出發政策獲准。

### 出發命令、政策與重播

POST 精確 body 為 `{expectedVersion}`，只屬 execution version，不接受原因、caller timestamp、actor／driver 身份、assignment version、task generation、證據或 policy 欄位。需有效 CSRF 與恰一個區分大小寫的 `Idempotency-Key`，精確 `[A-Za-z0-9._:-]{1,128}`。嚴格 UTF-8／JSON，拒 duplicate key、未知欄位、trailing token 或不成對 surrogate。

新出發只允許目前有效指派、當前來源與完整有效占用、實際 WAITING／0，且 recorded assignment policy 與 current DispatchEligibilityPolicy.mode 都是 SYNTHETIC_TEST_ONLY。其餘 policy 一律 POLICY_NOT_CONFIRMED；沒有新 env／ADM／HTTP 正式政策核准開關。不重定義車種、證照、載重、檢驗或工時資格，也不把合成出發當免照片或營運導入核准。GET 不因政策未核准而拒絕合法本人讀取。

每次操作先重新取得正常 DRIVER、目前 FMS 綁定、同一 current ASSIGNED、當前 TOM 來源版本及該次完整的二／三筆有效占用，才可讀資料或成功 receipt。非本人、失效／已撤改指派、來源或完整占用失效皆 opaque 404／null，不能用舊 assignment path 導向新指派，也不能讀舊 receipt。沿 canonical UPMS → 排序 FMS／綁定 → TOM → TMS assignment／anchor／occupations → execution 的鎖序，source hint 在鎖後重驗。

合法目前範圍確認後，同 actor／POST／canonical assignment path／key 的成功重播先於 phase、expectedVersion 與目前政策檢查；回原 HTTP 200／business data，timestamp 可更新，`Idempotency-Replayed:true`。新提交回 false；不要求 Location。原 execution／event／receipt 不增加，不再出發、不重新占用。新 key 的過期 version 或重複出發拒絕；失敗不記成功結果。

出發、唯一事件與成功命令紀錄同交易提交，任一步持久化失敗全回滾。出發不改 assignment status/version、task generation、reservation／release、TOM version 或 FMS 行政狀態。M3 撤改先提交則舊指派出發失敗；出發先提交則 M3 新撤改以原 E1603／INVALID_STATE 拒絕並保留占用。舊成功 M3 管理端歷史重播保持原語意，不把新 guard 插在其 replay 之前。

未知結果固定原 body／path／key，不背景重送或換 key。拒絕可能發生在 receipt lookup 前，401／403／404、CSRF、形狀或協定錯誤不能推定原次回滾。歷史 departure receipt 可仍是 DEPARTED／1，即使合法目前 GET 已是後續階段；收據與當前資料必須分開，下一命令前重新讀取。

### 新執行錯誤碼

精確 company ErrorEnvelope 為 `{code,message,data,timestamp}`。data 僅 null 或非空 `{reason?,actualVersion?,fields?}`；fields 為 string→string map，actualVersion 只屬 execution。沒有 actualGeneration、身份、SQL／cause、request body、key 或儲存資料。

| HTTP | code | 精確 reason／data |
| --- | --- | --- |
| 400 | E0400 | VALIDATION_ERROR＋非空 fields |
| 401 | E0401 | 未登入／匿名 CSRF，data null |
| 403 | E0403 | 已驗證的 CSRF_INVALID 或 FORBIDDEN，僅 reason；保留 null 相容性 |
| 404 | E0404 | opaque 本人目前範圍失效，data null |
| 405／415 | E0405／E0415 | method／media，data null |
| 409 | E1701 | VERSION_CONFLICT，另附 execution actualVersion |
| 409 | E1702 | INVALID_STATE，含匹配版本但非法階段／版本上限 |
| 409 | E1703 | POLICY_NOT_CONFIRMED |
| 409 | E1704 | IDEMPOTENCY_KEY_REUSED |
| 409 | E1705 | IDEMPOTENCY_IN_PROGRESS |
| 409 | E1706 | PROTECTED_RELATION |
| 500 | E0500 | 安全內部錯誤摘要，data null |

新 E17 namespace 不加入舊 E16 或 TOM 契約。獨立 schema／靜態工具不能證明實際身份、交易／鎖／回滾、path identity、HTTP status、replay header 或跨 request version+1；這些由產品 IT／E2E 直接驗證。未執行項仍標 not_run，不以本規格當實測。
