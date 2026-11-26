# LG-M4-03｜異常、補件與處置契約

依 [Q41–Q47](../98_decisions/2026-11-26-m4-execution.md)、[M4 七包交接](../08_development/m4-execution-handoff.md)及[獨立 OpenAPI 0.1.0](tms-exceptions-openapi.yaml)。前置 M402 main 為 `4681f13b4dd1dc324632848e7b119147e0e43068`，公司基準為 `xk2022/xplore-kaleidoscope@9213a3d8dacb8774d87c26cb736c4a4315779e95`。此為工程契約，不能代替實測或 D04／D05／D06／D10／正式 provider／安全解除／結案責任核准。

本包只提供 DRIVER 原始異常回報、管理端要求補件、不可變新照片、明確提交、管理處置與歷史，以及 ADMIN／DISPATCHER 管理 UI。完整 DRIVER UI 留 M406。沒有業務分類、豁免、完成、釋放、依賴解鎖、結案、重開、刪除、背景重送或部署。[M401](tms-execution.md)、[M402](tms-progress-evidence.md) 與全部原契約／validator／V1–V11 原文保留；不暗加舊進度阻擋規則或擴舊上傳兩階段。

## 13 項獨立 API

以下路徑均含 `/api/v1`。D=`/tms/my-tasks/{assignmentId}/exceptions`，M=`/tms/exceptions`。正常角色與原 Session 每次重新驗證，拒絕指定 driver／account／company 身份；管理端不能代理 self route。

| 角色 | 方法與路徑 | 請求 | 成功 envelope |
| --- | --- | --- | --- |
| DRIVER | GET D | page、size、status? | ExceptionSummaryPageEnvelope |
| DRIVER | POST D | `{expectedExecutionVersion,description}` | ExceptionRecordEnvelope |
| DRIVER | GET D/{exceptionId} | 無 query | ExceptionDetailEnvelope |
| DRIVER | GET D/{exceptionId}/history | page、size | DriverExceptionHistoryPageEnvelope |
| DRIVER | POST D/{exceptionId}/supplement-requests/{requestId}/evidence | raw PNG＋雙版本 header | SupplementUploadEnvelope |
| DRIVER | POST D/{exceptionId}/supplement-requests/{requestId}/submit | `{expectedVersion,note,evidenceIds}` | ExceptionRecordEnvelope |
| ADMIN／DISPATCHER | GET M | page、size、status?、assignmentId? | ExceptionSummaryPageEnvelope |
| ADMIN／DISPATCHER | GET M/{exceptionId} | 無 query | ExceptionDetailEnvelope |
| ADMIN／DISPATCHER | GET M/{exceptionId}/history | page、size | ManagerExceptionHistoryPageEnvelope |
| ADMIN／DISPATCHER | POST M/{exceptionId}/supplement-requests | `{expectedVersion,reason}` | ExceptionRecordEnvelope |
| ADMIN／DISPATCHER | POST M/{exceptionId}/resolve | `{expectedVersion,reason}` | ExceptionRecordEnvelope |
| ADMIN／DISPATCHER | GET M/{exceptionId}/evidence | page、size | SupplementEvidencePageEnvelope |
| ADMIN／DISPATCHER | GET M/{exceptionId}/evidence/{evidenceId}/content | 無 query | 私有 image/png bytes |

成功全部 HTTP200，JSON 精確 company envelope `{code:"0000",message,data,timestamp}`，有效 Gregorian timestamp 為 `+08:00`。POST 需有效 Session／CSRF、恰一個 case-sensitive `Idempotency-Key`，格式 `[A-Za-z0-9._:-]{1,128}`；成功必有 `Idempotency-Replayed:true|false`，無 Location。跨角色 route 403。

page 預設0、範圍0–100000；size 預設20、範圍1–100。status 只可單一 OPEN／NEEDS_SUPPLEMENT／RESOLVED，缺省為全部授權狀態。未知／重複 query 一律400，沒有 taskNo、company、任意 sort。摘要排序 reportedAt DESC、id ASC；歷史 toVersion DESC、id ASC；照片 uploadedAt ASC、id ASC。空／超末頁仍回真實頁資訊。

## 獨立版本與受控狀態

異常 version 與 execution、assignment、generation、TOM／FMS 完全獨立。整數皆為安全數學整數0–9007199254740991，JSON 1／1.0／1e0 同義。輸入 UUID 可大小寫，輸出與 ledger path 一律 canonical lowercase。

| 操作 | 前態 | 後態／異常版本 |
| --- | --- | --- |
| 回報 | 目前 DEPARTED／IN_PROGRESS／AWAITING_COMPLETION | OPEN／0 |
| 管理要求補件 | OPEN | NEEDS_SUPPLEMENT／+1 |
| 每張新補件照片 | NEEDS_SUPPLEMENT＋同 active request | NEEDS_SUPPLEMENT／+1 |
| 明確提交 | NEEDS_SUPPLEMENT＋同 active request | OPEN／+1，清 active pointer |
| 管理處置 | OPEN＋核准的合成決策、無 active request | RESOLVED／+1 |

WAITING／COMPLETED 不能新增回報。blocking 由 status!=RESOLVED 推導，沒有 caller blocking／waive／allowRelease 欄位。上傳不是提交，提交不是處置，RESOLVED 不是安全釋放。新照片升異常版本，避免管理端以舊 review token 忽略新資料；所有操作均不改 execution 或資源。計數上限拒絕，不溢位。

## 精確資料與原始事實

所有欄位必填、未知欄位拒絕；nullable 以 OpenAPI 明列。taskNo 是原來源值；原始文字不 trim、NFC、合併或覆寫。

- ExceptionSummary 精確9欄：`{id,assignmentId,taskId,taskNo,reportedPhase,status,version,blocking,reportedAt}`。
- ExceptionRecord 精確14欄：`{id,assignmentId,taskId,taskNo,reportedPhase,reportedExecutionVersion,description,status,version,blocking,reportedAt,resolvedAt,resolutionNote,activeRequest}`。reportedPhase 僅三個合法回報階段，reportedExecutionVersion>=1。RESOLVED 才有非 null resolvedAt／resolutionNote 且 blocking=false；NEEDS_SUPPLEMENT 才有 activeRequest；原 description 永不變成處置 reason。
- ActiveSupplementRequest 精確5欄：`{id,reason,requestedAt,uploadedCount,evidenceIds}`。IDs 是目前 request 的 verified 新照片 canonical sorted set，uploadedCount 等於其長度；最多32張是工程資源界線，不是 D06 正式數量。滿32的新 key 上傳拒絕 E1902，原 key 收據仍可重播。此 bounded IDs 支援 reload 後選照，不掃描全歷史。
- ExceptionDetail 精確3欄：`{exception,execution,actions}`。execution 沿用原三欄完整五階段 view，讓管理歷史日後可見 COMPLETED；不是擴 reportedPhase。
- ExceptionActions 精確6欄：`{mode,requestSupplement,resolve,uploadSupplement,submitSupplement,requirements}`。四個 action 各為 `{allowed,blocker}`，allowed 當且僅當 blocker=null；阻擋碼限 FORBIDDEN／POLICY_NOT_CONFIRMED／INVALID_STATE／SUPPLEMENT_REQUIRED／EVIDENCE_REQUIRED。任何 allowed=true 都需 SYNTHETIC_TEST_ONLY；不適用角色固定 FORBIDDEN，UNCONFIRMED 不能放行。照片 action 放行需非 null requirements、active request 與合法當前階段；OPEN／RESOLVED 的 requirements 為 null。這是當前提示，不是批准 token。
- SupplementUploadResult 精確6欄：`{exceptionId,assignmentId,taskId,version,requestId,evidence}`。version=原異常 token+1（首次至少2），evidence.executionVersion=原執行 token；evidence 精確沿用原10欄。新 route 可在 AWAITING_COMPLETION 上傳，不擴原 M402 上傳限制。
- SupplementEvidence 精確3欄：`{requestId,submitted,evidence}`，submitted 是 immutable 提交關聯的當前投影；uploaded-but-unsubmitted 仍不算補件完成。管理端只取得該異常明確關聯的照片，不默認整單／公司全部照片。
- 所有 page 精確 `{content,page,size,totalElements,totalPages}`，content 最多100。JSON 無帳號／actor／driver／order／customer／resource ID、車牌、路徑、filename、URL 或 bytes／base64；管理 audit actor 例外僅如下兩欄。

DriverExceptionEvent 精確11欄：`{id,exceptionId,type,fromStatus,toStatus,fromVersion,toVersion,occurredAt,note,requestId,evidenceIds}`。type 僅 REPORTED、SUPPLEMENT_REQUESTED、SUPPLEMENT_EVIDENCE_UPLOADED、SUPPLEMENT_SUBMITTED、RESOLVED。建立為 null/null→OPEN/0；其餘按表各+1。上傳 note=null、恰一照片；回報／要求／處置的照片集合空；提交有選定照片及 raw note。request／upload／submit 必有 requestId，回報／處置為 null。note 分別保留原回報、要求、提交或處置文字，不是任意 payload。

ManagerExceptionEvent 為相同11欄加 `actor={role,displayName}` 當時快照（displayName 沿既有 UPMS 1–80 code points），不回 actor accountId；DRIVER 歷史沒有這個成員。原 actor 保留在內部稽核，並非目前綁定的另一條授權規則。

description 為1–2000 code points，reason／note 為1–1000；只檢查 nonblank（Character.isWhitespace／SpaceChar／FEFF 等價集合），保留有效 NUL、NFD、首尾空白與換行，不修改有效原值。拒絕 invalid UTF8、unpaired surrogate、duplicate key、trailing token、未知欄位。以 UTF8 bytea／json 保存，避免 text／jsonb 的 NUL 損失。不同 raw 文字是不同 hash；JSON property order／escape 字面不同但值相同不影響 hash。React 純文字 escape；管理理由明示 DRIVER 可見，沒有暗藏 staff-note 欄位。

## 補件照片、提交與原子保存

新 raw route 恰一個 X-Exception-Version 及 X-Execution-Version，都是 ASCII digits safe integer，無 leading zero（0除外）、符號、指數、小數、逗號或空白。先核異常 token，再核執行 token。恰一 Content-Type=image/png，type/subtype 大小寫同義；拒 media parameters、重複 Content-Type／Content-Encoding／命令 metadata、非 identity encoding；不接受 multipart、caller phase／clock／hash／type／filename／儲存 path。

JSON 命令必從 InputStream 以實際16384 bytes 限量，再 materialize；不是先無上限 @RequestBody 再檢查。raw 同樣實際 stream cap1048576，含 chunked／缺省或不實 Content-Length；新 nested upload 才加入 exact-route nginx2m／header-only CSRF，其他路徑16k。PNG signature／chunk／CRC／唯一終端 IEND／無 trailing data／dimensions-before-decode／pixel／byte／decode 驗證沿既有技術能力，原 bytes 不重編碼或覆寫。

上傳 hash 只含 canonical path、原雙 token、normalized media、實際長度與完整 SHA256，不含當前 phase、policy、clock 或新 UUID。每張新照片＋原 Evidence10 metadata＋request link＋exception version/event＋receipt 同 caller PG transaction；任一步或 deferred FK 失敗全回滾，沒有 PENDING／FAILED／orphan 被算有效，也不升 execution。

提交綁定 path 中原 requestId，body 為 `{expectedVersion,note,evidenceIds}`。UUID 依 lowercase 去語意重複檢查、排序成集合，最多32；不 silently dedupe。空集合可到 ledger miss 後 E1907。只選此 request／exception／assignment／目前 driver 的新 verified artifacts，檢查 durable bytes/hash 與獨立補件策略。提交不擅自要求照片等於「今天 execution phase/version」；只有上傳雙 token 綁其觀察時點。原 request、未選照片及 selected links 全留歷史，沒有改貼舊 M402 照片當新回覆。

正常 ExceptionPolicy UNCONFIRMED。只有 test-source／明確測試 launcher 實查 `current_database()='xk_logistics_e2e'`、同 IT／DESKTOP／MOBILE 合成 scope 的外掛可正向，且不進 production JAR。合成 requirements 明列 count1／PNG／65536 bytes／4096 pixels；處置需至少一次含 durable verified 新照片的明確補件提交、無 active request 與 fresh 決策。這不是正式現場例外、照片、保存或釋放規則；無 ENV／ADM／HTTP 核准開關、正式 provider 或 fallback。

## 歷史授權、重播與阻擋查詢

DRIVER 每次讀／寫／重播先驗正常帳號、原有效 Session、目前 FMS 綁定、同一 current ASSIGNED、當前 OPEN／ACCEPTED TOM source/version 及完整二／三筆有效占用，再看 receipt。失效／foreign opaque404／null，不重導新指派；換合法帳號仍以目前同一 physical driver 範圍判定，原上傳 actor 僅 audit，收據仍按 actor 分開。

管理 list／detail／history／metadata 先 fresh normal ADMIN／DISPATCHER＋原 Session＋server company exception record，不依目前 driver binding、occupation 或未完成來源，因此後續完成／結案不抹去管理歷史。管理私有 bytes 再驗獨立目前 manager read-policy／provider、recorded narrow context 及該異常照片關聯，不 proxy self route。

新管理寫入僅在 receipt miss 才做 current assignment/source/occupation 守衛；管理歷史成功重播在 fresh manager/session/company 授權及 hash 後，先於今日 phase/status/version/policy/currentoccupation。DRIVER 仍保留較嚴 current scope 在所有收據之前。request 歸屬可在 lookup 前證明，但不能要求它仍 active，否則 submit／再要求補件後會遺失舊合法收據。

鎖序 UPMS／原Session→排序FMS／綁定→TOM→TMS root／assignment／occupations→execution→exception→request/evidence→event/ledger；hint 鎖後重讀，不先鎖 exception 再回 FMS/TOM。advisory key try-lock 不等於授權。合法成功重播回原 business data/status、timestamp可更新，無再次寫入；失效本人不能讀舊收據。

正向競爭只用合法前態：已有 fulfilled supplement 的 OPEN 才測 REQUEST vs RESOLVE；已有照片的 NEEDS_SUPPLEMENT 才測 UPLOAD vs SUBMIT。不能為了競爭測試容許 NEEDS_SUPPLEMENT 直接 resolve。late upload／submit 對已 OPEN／RESOLVED 的版本／前態拒絕，不產生 blob。

可信 ExceptionQueries.lockBlockingForAssignment 需 MANDATORY writable caller transaction，自身取得／重驗同一 tms_assignment root FOR UPDATE（零異常亦然），再按 UUID 鎖現有異常，回 `{assignmentId,unresolvedCount}`。呼叫端保持既有鎖序；report 同 root，避免 count0 phantom。no-tx／read-only／腐敗未知狀態拒絕，不假造0；不是 max(exception.version) 全域 token。後續完成需持 root 至自己的 policy／commit，本包不執行釋放。

## E19 與未知結果

ErrorEnvelope 精確 `{code,message,data,timestamp}`，data 僅 null 或非空 `{reason?,actualVersion?,actualExecutionVersion?,fields?}`。fields 為非空 string→string；不回 actualGeneration、SQL/cause、body/key/hash、身份或 storage 資料。

| HTTP | code | data／reason |
| --- | --- | --- |
| 400 | E0400 | VALIDATION_ERROR＋fields |
| 401／404／405／413／415／500 | E0401／E0404／E0405／E0413／E0415／E0500 | null |
| 403 | E0403 | null相容，或reason-only CSRF_INVALID／FORBIDDEN |
| 409 | E1901 | VERSION_CONFLICT，恰一 actualVersion（異常）或 actualExecutionVersion（執行） |
| 409 | E1902 | INVALID_STATE，含版本／技術照片數上限 |
| 409 | E1903 | POLICY_NOT_CONFIRMED |
| 409 | E1904 | IDEMPOTENCY_KEY_REUSED |
| 409 | E1905 | IDEMPOTENCY_IN_PROGRESS |
| 409 | E1906 | PROTECTED_RELATION |
| 409 | E1907 | SUPPLEMENT_REQUIRED |
| 409 | E1908 | EVIDENCE_INVALID |

report 衝突只回執行 actual；雙 header 先異常，不能混淆 token。foreign／缺少／過期／wrong-request／未驗證照片統一 E1908，不列出私有 ID。

只對同一 frozen POST/body/path/key 的未知嘗試，E1901／02／03／07／08 是明確 post-success-ledger-miss 拒絕白名單，可判該次未套用；產品必保證這個順序。E1904／05／06、全部共同碼、transport／parse／schema／header／witness 失敗仍未知。GET 或照片讀取錯誤不能結清另一個 POST。401／FORBIDDEN 清敏感資料及 raw pending，但不宣稱回滾；CSRF_INVALID 只 refresh，不自動重送，refresh401/403傳遞 auth loss。原收據不能當當前 state；下一個新命令前 fresh GET。

管理端使用獨立嚴格 parser，核 source IDs、status、version+1、原始 reason／resolutionNote、request identity 等成功見證，再標成功。上傳區分異常 token 與執行 token。保留 dirty／busy／unknown／navigation／logout 守衛，照片 objectURL 在關閉／登出撤銷，無公用連結或本機持久化。

## 私有內容、資料庫與證據

新管理內容路由只回 verified PNG：實際 Content-Length、`Cache-Control: no-store, private`、`X-Content-Type-Options: nosniff`、固定 `Content-Disposition: inline; filename="evidence.png"`。無 ETag／Last-Modified／Accept-Ranges／Location／Content-Location／206／304／redirect。Range400，conditional仍驗權回200，HEAD405；Accept:image/png 的錯誤仍 company JSON，不回部分 bytes。所有新成功／錯誤 private/no-store。

V12 六新表為 tms_exception、tms_exception_event、tms_exception_command、tms_exception_supplement_request、tms_exception_supplement_evidence、tms_exception_event_evidence，共40表；無 backfill。只以新 migration 增量容許舊 evidence phase CHECK 的 AWAITING，不改 V11 原文或原資料。composite identity/FK、raw facts／原照片／events／links／receipts不可變、每 request 一次提交與正確版本由 DB／交易守護。無 D10 清除作業。

[獨立驗證器](../../tests/contracts/validate_exception_contract.py)驗非空 `exception-contract-responses.json`，只含公開合成 `{schema,value}` JSON bodies；八個 envelope 名稱如上表及 ErrorEnvelope，成功 binary完全排除。沒有 request／key／Cookie／CSRF／headers／filename／original PNG／trace。bytes/hash/media/cache/status及權限、版本跨 request、交易回滾、零GET寫入、future blocker鎖，由真實 PostgreSQL／HTTPS IT／E2E驗，不能以 body-only schema 或靜態例子替代。policy/provider切換及底層 duplicateheaders／chunked／Content-Length誤報是可控 bean/socket 的 IT 證據，不能冒稱瀏覽器 fetch 能偽造。實測結果另記驗收文件。
