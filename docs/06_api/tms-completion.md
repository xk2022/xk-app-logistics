# LG-M4-04｜任務完成與實際資源解除契約

依 [Q41–Q47](../98_decisions/2026-11-26-m4-execution.md)、[M4 工作包](../08_development/m4-execution-handoff.md)及[獨立 OpenAPI 0.1.0](tms-completion-openapi.yaml)。前置 main 為 `e55ee4ecdf98d6f314b60e45c96b0b18d9e5489e`；公司基準為 `xk2022/xplore-kaleidoscope@9213a3d8dacb8774d87c26cb736c4a4315779e95`。這是精確工程契約，不能代替實測或正式安全確認者／D04／D05／D06／D10／provider／結案責任核准。

本包只新增 AWAITING_COMPLETION→COMPLETED、不可變完成 witness、同交易實際解除占用及真實前置完成查詢。不新增照片、豁免、交接、管理代確認、結案、清除、M5、完整 DRIVER UI 或正式部署；完整 DRIVER UI 留 M406。[M401](tms-execution.md)、[M402](tms-progress-evidence.md)、[M403](tms-exceptions.md) 與全部舊 API／schema／validator／V1–V12 原文保留。

## 舊紀錄與實際狀態

不把 COMPLETED 塞入舊 AssignmentStatus，不假造 REVOKED／SUPERSEDED／RELEASED 或第三個 M3 event。原 assignment 仍為 ASSIGNED／version0，generation、ended* 不變；原 reservations 的 acquired facts、releasedAt=null、releaseReason=null 與原成功重播 JSON 不變。

新 execution.COMPLETED＋完成 witness 才是完成與實際資源解除事實。V13 新 nullable completion_id 關聯後，實際占用判斷是 `released_at IS NULL AND completion_id IS NULL`，不是只看舊 JSON 的 null。保留原14欄 self-list SQL membership、排序、count/page，包含仍是同一 retained current ASSIGNED anchor 的 completed assignment，供後續新頁面 discovery；這是原始派工紀錄，不承諾仍占用。新16欄 GET 提供真實狀態。舊 M401／M402／M403 current-only detail／photo／DRIVER 收據在釋放後仍404，不放寬它們。管理端原 history/current/receipt 仍是原紀錄。

## 三項 API

共同前綴 `/api/v1/tms/my-tasks/{assignmentId}`，三項只限正常 DRIVER。ADMIN／DISPATCHER403，無 acting-as-driver。完全不接受 query，包括 history、identity、resource 或 duplicate selectors。

| 方法 | 路徑後綴 | 請求 | 成功 envelope |
| --- | --- | --- | --- |
| GET | `/completion` | 無 | CompletionDetailEnvelope |
| GET | `/completion-policy` | 無 | CompletionPolicyEnvelope |
| POST | `/complete` | `{expectedVersion,evidenceIds,confirmSafeRelease:true}` | CompleteMyTaskEnvelope |

成功固定 HTTP200，company envelope 精確 `{code:"0000",message,data,timestamp}`，有效 Gregorian timestamp 為 `+08:00`。所有新成功／錯誤 private／no-store。POST 需正常原 Session、header-only CSRF 與恰一個 case-sensitive `Idempotency-Key`，格式 `[A-Za-z0-9._:-]{1,128}`；成功必有 `Idempotency-Replayed:false|true`，無 Location。

JSON 從 InputStream 以實際16384 bytes 限量後才 materialize，拒 invalid UTF8／duplicate key／trailing token／unpaired surrogate／未知欄位。超限413／E0413／null。不新增 raw/photo/content endpoint。

## 精確 DTO 與版本

所有物件欄位必填，未知欄位拒絕，nullable 明列。整數為安全數學整數0–9007199254740991，JSON1／1.0／1e0 等價。輸入 UUID 可大小寫，輸出／ledger path 為 canonical lowercase。

- CompletionDetail 精確16欄：原 saved safe14（assignmentId、taskId、taskNo、item、taskKind、pickupName、pickupAddress、deliveryName、deliveryAddress、siteContact、plannedStartAt、plannedEndAt、assignedAt、policyMode）＋execution＋completion。execution 沿原三欄五階段 ExecutionView；completion 在非 COMPLETED 時必為 null，COMPLETED 時必非 null、version至少4。這不是修改原15欄。
- safe14 從該原 assignment source 讀取，即使資源已他用、來源日後關閉，也不串新 owner／新 task。原 TOM NUL／NFD／空白／decimal字串scale／offset微秒／null與存在的all-null contact 保留；沒有 order/customer/account/driver/resource IDs、車牌、audit、event、URL／path／filename、bytes／base64。
- CompletionWitness 精確5欄：`{id,completedAt,releasedAt,policyMode,safeReleaseConfirmed}`。id 為新 opaque UUID，兩個 server timestamp 是同一次成功 transaction instant／相同字面；policyMode 為原 SYNTHETIC_TEST_ONLY，safeReleaseConfirmed=true。輸出 safeReleaseConfirmed 刻意不同於輸入 confirmSafeRelease：記錄明確請求確認及合成 server 決策，不批准真實 DRIVER 的正式安全確認責任。actor 與資源身份內存，不上 wire。
- CompletionResult 精確4欄：`{assignmentId,taskId,execution,completion}`。execution.phase=COMPLETED、version=原 expectedVersion+1且至少4、departedAt原值不變，completion非 null。這是原次不可變歷史成功，不是目前 GET 或舊 reservation payload。
- CompleteRequest 精確3欄：`{expectedVersion,evidenceIds,confirmSafeRelease}`。expectedVersion 只屬 EXECUTION，與 exception／assignment／generation 分開；confirmSafeRelease 只允 literal true，false／缺少／其他型別400。evidenceIds 為 UUID 數學集合，按 lowercase 檢查語意重複後排序計 hash，重複400、不默默去重；最多32是技術界線。空集合符合型別，但 new miss 時缺照片 E2007；成功需恰等於 server 要求的 immutable readiness selected set。
- CompletionPolicyView 精確8欄：`{assignmentId,taskId,execution,mode,canComplete,blocker,requirements,requiredEvidenceIds}`。mode 是 CURRENT CompletionPolicy；canComplete 當且僅當 blocker=null。blocker 限 POLICY_NOT_CONFIRMED／UNSUPPORTED_PHASE／UNRESOLVED_EXCEPTIONS／EVIDENCE_REQUIRED／EVIDENCE_INVALID／ALREADY_COMPLETED。任何 blocked 都要求 requirements、requiredEvidenceIds 同為 null，非[]／零照片 waiver。ready 只在 AWAITING／mode SYNTHETIC_TEST_ONLY，回非 null requirements及 canonical sorted unique IDs1–32，供明確選照。
- CompletionRequirements 精確6欄：`{evidenceBasis,minimumCount,allowedMediaTypes,maxBytes,maxPixels,requiresSafeReleaseConfirmation}`，只在合成測試回 `LATEST_AWAITING_TRANSITION,1,[image/png],65536,4096,true`。這些是明列工程測試值，非正式照片類型、階段、大小、數量或安全責任核准。

已完成時 ALREADY_COMPLETED 優先，requirements／IDs null，即使今日 mode=UNCONFIRMED；witness.policyMode 仍是原歷史 SYNTHETIC_TEST_ONLY，不把它升格為今日允許。未完成時今日 policy/provider/safety 未核准先 POLICY_NOT_CONFIRMED；confirmed錯階段 UNSUPPORTED_PHASE；未處置異常或缺／壞證據回相應 blocker。投影不是批准 token，新寫入逐次重驗。

## 必要證據與未核准安全政策

原 M402 普通上傳只允 DEPARTED／IN_PROGRESS，不擴至 AWAITING，也不造假異常取得補件。本包獨立 CompletionPolicy 採具名合成規則：同 assignment 最新 immutable IN_PROGRESS→AWAITING event，toVersion=目前 execution version，其既有 event_evidence links 明確選定的照片集合。

新完成 body IDs 必須恰等於該 canonical set；每張仍須同 assignment／task／driver、VERIFIED、durable bytes／length／hash 符合規則。必要時沿 bounded immutable links 重驗接受的進度鏈，不以 elapsed time、無占用、AWAITING、RESOLVED 或任意同 task 照片替代。AWAITING 補件照片保留，但不暗換此 readiness proof。缺少集合 E2007，foreign／missing／stale／錯 transition／變更集合／壞 bytes 統一 E2008，不回私有 IDs。

只依 event FK 尚不足：必須同時有原 canonical progress path 的 immutable success receipt，scope company／原 actor／assignment／task／driver及原 data 中 IDs／execution target phase/version 均相符。V11 只禁止 row update/delete，不能單獨防 late append；V13 seal 在原 success receipt 後禁止新增該 readiness selected set，且禁止把舊照片重鏈到 COMPLETED event。

正常 CompletionPolicy 為 UNCONFIRMED，即使 checkbox=true、異常已處置、FMS AVAILABLE或資料名稱像測試也拒新完成／釋放。正向 factory 只在 test sources／明確 launcher，實查 actual DB=`xk_logistics_e2e` 與同 IT／DESKTOP／MOBILE 合成 driver/task scope，獨立確認 dispatch／evidence／provider／completion／safety決策；不在 production JAR，沒有 ENV／ADM／HTTP 核准開關。client true值只是一個請求 attestation，不是 server 安全決策或正式確認者授權。

## Live 與完成後的窄授權

LIVE：fresh正常 DRIVER、原有效 Session、目前 FMS 綁定同原 physical driver、同一 current ASSIGNED anchor/company、目前 OPEN／ACCEPTED TOM source/version、精確完整2／3 effective occupations。GET純讀不造 row；只有 AWAITING 可以新 complete。

COMPLETED：fresh正常 DRIVER、原 Session、目前仍綁同原 physical driver、同 retained current ASSIGNED anchor/company，以及有效不可變 witness／terminal execution／typed exact release markers。不是任意撤改歷史。不要依今日 FMS availability、active occupations、provider/policy或仍 OPEN／同版本 TOM source授權；資源可能已他用，訂單日後可關閉。顯示的是原安全來源，不跟新 owner。失效範圍404／null，腐敗／不完整完成事實安全500，不偽造成功。

同 physical driver 合法改綁另一正常帳號可讀此窄完成資料，但 ledger 按 actor 分開，不能重播原 actor 的成功。改成另一 physical driver、停用／改角色／logout／解除綁定則撤銷。新 actor 使用相同 key 是自己的 ledger miss，走 E2001／E2002，不借其他人 receipt，也不只因 actor 不同而把合法同driver metadata變404。

POST 順序為 bounded shape→preliminary fresh actor/key try-lock→live或valid-completed scope→receipt/hash→僅 miss 檢版本／前態／今日政策／阻擋／照片／安全。原成功 replay 必先於今日 phase/version/provider/policy/sourceopen／occupations，回原200/data/replayedtrue，不再事件或釋放。完成 scope 必能在 lookup 前成立，否則第一次成功釋放資源後永遠無法重播。缺 witness 不能只憑鬆散 receipt 繞過。

新 key expected3對完成version4回E2001(actual4)，matching expected4回E2002；同key改body E2004，in-flight E2005。舊 M401／M402／M403 DRIVER 收據仍可依舊 current guard404，不把此窄例外擴過去。原管理 M3／M403成功收據維持原歷史授權及 JSON。

## V13 原子資源解除與 SQL seals

新增 tms_completion、tms_completion_command兩表，40→42；原 reservation 只增 nullable completion_id（初始NULL）與同 assignment composite FK。active partialunique 改為 `released_at IS NULL AND completion_id IS NULL`，保留 defaultNULL 的舊 direct-SQL重複占用保護，不另 active index／雙寫／回填。所有 TMS occupation／resource／self-current及 FMS guard 用同 predicate，舊 history/DTO不加入欄位。

reservation UPDATE 只有兩條互斥路徑：舊 legacy release 在 old/new completion_id均NULL才可沿原規則修改 released_at/reason；新 completion 路徑只允NULL→合法同 assignment witness，所有舊欄含released_at/reason不變。completed row不可再改／刪，legacy-released不可標完成；INSERT攜 nonNULL marker禁止。必須匹配精確 reservation IDs／positions／resource identity，恰2或3，不能只按resourceId盲改。

完成後即使completion_id=NULL，也禁止同 assignment再INSERT reservation（含原本空缺的TRAILER）。BEFOREINSERT先鎖 assignmentroot及既有 reservation rows，重驗terminal/witness，讓 stale RR無法漏看新完成；另保留deferredINSERT／exact2/3 proof。readiness event_evidence BEFOREINSERT也鎖同root／必要changedexecution row，驗 typed event與原 success receipt；既有產品順序是 executionUPDATE→eventINSERT→sortedlinksINSERT→progresscommandINSERT，所以只允最初 receipt前加入，之後全拒。用 json ->/->>，不可把 raw TOM source轉jsonb。

witness 以 typed/composite FK固定原 readiness IN_PROGRESS→AWAITING/sourceVersion及完成 AWAITING→COMPLETED/version+1、terminal execution、同 assignment/task/driver。原 V11 immutable links 提供照片關聯，不添第三表或放寬舊 phase/version FK。own completion ledger保存完整4欄，不能用舊3欄departure/progress codec重解。commit前需 witness/event/execution/ledger/exact2或3 markers一致，原 success receipt及 selected-set proof不能只有 loose JSON。

canonical UPMS／原Session→排序FMS／綁定→TOM root/source→TMS anchor/assignment/reservations→execution→ExceptionQueries root/blockers→原證據/policy→terminal event/witness/markers/receipt。全部同 caller transaction，任何 SQL／provider／receipt／deferredFK失敗全回滾至原 AWAITING與完整 effective occupations，照片不變。無REQUIRES_NEW／外部provider替代／清除。拒絕升級前無合法 witness的COMPLETED，不捏造 backfill；舊列 fingerprint 比較固定原欄，另驗新欄全NULL。

## 阻擋、前置完成與合法競爭

同 transaction 的 ExceptionQueries 即使零案件也鎖 shared root；任何 nonRESOLVED/pending request阻擋E2009，腐敗狀態不得回0。report也拿同root，避免count0 phantom。RESOLVED仍不等於安全解除。

TOM 使用自己定義的 TaskCompletionFacts consumer port，由 TMS只依Jdbc的 query adapter實作，不注入 CompletionService／TaskQueries造成循環，也不跨模組SQL。TOM驗同單graph，分批最多100 IDs但不縮舊業務依賴上限，要求每個前置 exact order/task/sourceOrderVersion真完成 witness。無occupancy／legacy release／timer／AWAITING／RESOLVED不算，沒有假handover。readfacts不重評今日政策；新派工仍需自己的正常適格策略，沒有自動派工。

same-key完成只一次新200，另一replay或IN_PROGRESS；不同key只一提交。report先則complete阻擋，complete先則late old-current report404；resolve先可讓後續complete通過，complete先見OPEN只能拒絕。FMS變更在占用時仍拒絕，complete先釋放則後續合法變更／他單派工可成功。舊新撤改對出發／完成仍E1603，原管理成功收據可重播；不捏造舊M402 unsupported phase的正向競爭。

## E20、成功見證與驗收證據

ErrorEnvelope 精確 `{code,message,data,timestamp}`；data僅null或非空`{reason?,actualVersion?,fields?}`，actualVersion只EXECUTION，不收actualExecutionVersion／exception／generation。fields非空string→string；無IDs／count／body／key／hash／SQL／storage diagnostics。

| HTTP | code | data／reason |
| --- | --- | --- |
| 400 | E0400 | VALIDATION_ERROR＋fields |
| 401／404／405／413／415／500 | E0401／E0404／E0405／E0413／E0415／E0500 | null |
| 403 | E0403 | null相容或reason-only CSRF_INVALID／FORBIDDEN |
| 409 | E2001 | VERSION_CONFLICT＋execution actualVersion |
| 409 | E2002 | INVALID_STATE |
| 409 | E2003 | POLICY_NOT_CONFIRMED |
| 409 | E2004 | IDEMPOTENCY_KEY_REUSED |
| 409 | E2005 | IDEMPOTENCY_IN_PROGRESS |
| 409 | E2006 | PROTECTED_RELATION |
| 409 | E2007 | EVIDENCE_REQUIRED |
| 409 | E2008 | EVIDENCE_INVALID |
| 409 | E2009 | UNRESOLVED_EXCEPTIONS |

只對同一 frozen complete POST/body/path/key，E2001／02／03／07／08／09是保證 success-ledger-miss 後拒絕的 settlement白名單。E2004／05／06、共同碼、scope／shape／CSRF／auth／transport／malformed response仍未知；GET／policy錯誤不結另一POST。authloss清敏感資料但不宣稱回滾；CSRF只refresh不自動POST，refresh401/403傳遞安全退出。

後續 typed client須在awaitCSRF前freeze原body/path/key及safe source/execution，核結果4欄 IDs、COMPLETED／原version+1／原departedAt、witness5／原mode／trueconfirmation／相同server時間，以及200/replay header，再確認成功。原收據與current read分開，下一新操作先fresh read/policy；M404不提前實作M406client/UI。

[獨立驗證器](../../tests/contracts/validate_completion_contract.py)只驗非空 `completion-contract-responses.json` 公開合成`{schema,value}`；名稱為 CompletionDetailEnvelope、CompletionPolicyEnvelope、CompleteMyTaskEnvelope、ErrorEnvelope。舊setup/order/acceptance/TMS/execution/progress/exception仍用原artifacts與validators。無原PNG／request／key／Cookie／CSRF／headers／privateaudit／trace。schema可證16/4/5/8/6形狀、raw14、nullable／policy／整數／相同時間，不能證明physicalbinding、鎖、source完成identity、realbytes、SQLseals或exactmarker原子性；IT／E2E直接驗。靜態與本文不能當runtime或正式安全政策通過，實際驗收另記文件。
