# LG-M4-05｜管理完成確認與 TOM 結案契約

依 [Q41–Q47](../98_decisions/2026-11-26-m4-execution.md)、[M4 工作包](../08_development/m4-execution-handoff.md)及[獨立 OpenAPI 0.1.0](tom-closure-openapi.yaml)。前置 main 為 `a027dd1e38279449b6843fead9633236aa164b64`，公司基準為 `xk2022/xplore-kaleidoscope@9213a3d8dacb8774d87c26cb736c4a4315779e95`。本文是工程契約，不是正式確認責任、D04–D06／D10、照片／provider、未完成工作處理或正式營運核准；實測另附驗收證據。

管理確認是獨立、不可變的行政事實 counter0→1，不變更 execution、assignment、generation、資源或 TOM。訂單結案才把 OrderCore lifecycle 設為 CLOSED、保留 ACCEPTED，TOM aggregate version 恰+1並追加完整 CLOSED revision。兩者分開授權及留存。沒有自動釋放／撤派／取消／照片豁免、新管理照片 API、M406 完整 DRIVER UI、M5 或正式部署。

## 相容性與真實狀態

原 [TOM 五項 API](openapi.yaml) 的 OrderSummary／OrderDraftRead 本來允 OPEN／CANCELLED／CLOSED；普通 GET/list 如實回 CLOSED，仍用原 `{code:"OK",message,data,traceId}`。原[受理收據](tom-acceptance-openapi.yaml)仍是歷史 OPEN+ACCEPTED；舊 create／PUT／accept 成功重播回原始 OPEN 快照，不改寫為今日狀態。新操作獨立 company envelope，不能覆蓋舊 TOM 錯誤格式。

舊 CLOSED 無本包 witness 是合法事實，包含既有升級與 SQL 回歸前態。不得要求所有 CLOSED 有新審計、拒絕此類升級或補造 actor／closedAt／policy。新 GET 明示 LEGACY_CLOSED、closure/counts=null、history空、closedAt=null；新 close ledger MISS 拒 already CLOSED。新 task-review 對 legacy CLOSED／DRAFT／CANCELLED 故意409 E2102，原普通訂單 GET 仍可讀實際資料。

requiredTask 原值只允 true|null；null 是未指定，非已批准 optional。合成結案要求所有 persisted tasks 真完成並各有行政確認，不更動旗標、略過任務或自行處置未完成工作。整單各次 assignment 的有效占用與阻擋異常都須零；不把他單重用的同一 resource 算到本單。舊 API／八份來源／V1–V13／validator 原文保留。

## 九項新 API

共同前綴 `/api/v1`，正常 ADMIN／DISPATCHER 限定，DRIVER403。逐次核對 fresh 帳號／角色、原有效 HttpSession 及 server company；無 acting-as-driver／caller tenant／actor／資源／policy override。

| 方法 | 路徑 | 請求 | 成功 envelope |
| --- | --- | --- | --- |
| GET | `/tms/completions` | page,size,state?,orderId? | CompletionConfirmationSummaryPageEnvelope |
| GET | `/tms/completions/{completionId}/confirmation` | 無 query | CompletionConfirmationDetailEnvelope |
| POST | `/tms/completions/{completionId}/confirm` | expectedVersion,confirmCompletion:true,reason | ConfirmCompletionEnvelope |
| GET | `/tms/completions/{completionId}/confirmation/history` | page,size | ManagementConfirmationHistoryPageEnvelope |
| GET | `/orders/closure-worklist` | page,size,state? | ClosureSummaryPageEnvelope |
| GET | `/orders/{orderId}/closure` | 無 query | OrderClosureEnvelope |
| GET | `/orders/{orderId}/closure/tasks` | page,size | ClosureTaskReviewPageEnvelope |
| GET | `/orders/{orderId}/closure/history` | page,size | OrderClosureHistoryPageEnvelope |
| POST | `/orders/{orderId}/close` | expectedVersion,confirmClosure:true,reason | CloseOrderEnvelope |

所有成功 HTTP200，精確 `{code:"0000",message,data,timestamp}`；timestamp 為有效 Gregorian +08:00，成功／錯誤皆 `Cache-Control:no-store, private`。POST 需 header-only CSRF、恰一個 case-sensitive Idempotency-Key `[A-Za-z0-9._:-]{1,128}`，成功必有 Idempotency-Replayed:true|false，無 Location。只接受 application/json／UTF-8，可 identity Content-Encoding；實際 InputStream 最多16384 bytes，先取得原 Session、再有限量讀取，超限413 E0413/null。拒無效 UTF-8／duplicate JSON key／trailing token／unpaired surrogate／未知欄位。

query 必須恰為各表列允許集合，拒未知／重複參數。新 page 預設0、範圍0–100000；size 預設20、範圍1–100，不收任意 sort/q/company。confirmation state=PENDING|CONFIRMED|ALL，預設PENDING；closure state=OPEN|RECORDED|LEGACY_CLOSED|ALL，預設OPEN，OPEN worklist 只取 OPEN+ACCEPTED。固定 completion completedAt DESC,id ASC；closure updatedAt DESC,id ASC；task taskNo ASC,id ASC；history time DESC,id ASC。舊 TOM pagination 上限不改。

## DTO 與獨立版本單位

所有欄位必填，nullable 明列、unknown 拒絕。整數為數學安全整數0–9007199254740991，JSON1／1.0／1e0 等價。輸入 UUID 可大小寫，輸出 canonical lowercase。reason 為1–1000 Unicode codepoints／非全 Java whitespace、SpaceChar 或 FEFF，保留有效 NUL／NFD／leading/trailing／newline 原值，不 trim/NFC，不收 client time；React 預設文字 escaping。原 taskNo、orderNo、來源 typed descriptor 的 raw 語義不改。

| DTO | 精確欄位 |
| --- | --- |
| CompletionConfirmationSummary（11） | completionId,assignmentId,taskId,taskNo,orderId,orderNo,sourceOrderVersion,completedAt,version,state,confirmedAt |
| ManagementConfirmationFact（6） | id,version,confirmedAt,policyMode,reason,actor |
| CompletionConfirmationDetail（5） | record,execution,completion,confirmation,policy |
| CompletionConfirmationResult（6） | completionId,assignmentId,taskId,sourceOrderVersion,version,confirmation |
| ClosureSummary（9） | orderId,orderNo,type,lifecycleStatus,acceptanceStatus,version,auditState,updatedAt,closedAt |
| OrderClosureView（9） | orderId,orderNo,lifecycleStatus,acceptanceStatus,version,auditState,closure,policy,counts |
| ClosureCounts（6） | taskCount,completedTaskCount,confirmedTaskCount,blockingExceptionCount,effectiveOccupationCount,sourceOrderVersion |
| OrderClosurePolicyView（4） | mode,canClose,blocker,requirements |
| ClosureRequirements（4） | taskBasis,requiresManagerConfirmation,requiresNoBlockingExceptions,requiresNoEffectiveOccupations |
| ClosureTaskReview（14） | orderId,sourceOrderVersion,taskId,taskNo,requiredTask,assignmentId,completionId,executionPhase,executionVersion,completionVerified,confirmationVersion,confirmedAt,blockingExceptionCount,effectiveOccupationCount |
| OrderClosureWitness（9） | id,sourceVersion,version,closedAt,policyMode,managerConfirmed,reason,taskCount,actor |
| OrderClosureResult（6） | orderId,orderNo,lifecycleStatus,acceptanceStatus,version,closure |

所有 page 精確 `{content,page,size,totalElements,totalPages}`，content最多100，總頁數／範圍與內容一致。actor 精確 `{role,displayName}`，只 ADMIN|DISPATCHER，保留歷史名稱1–80字，不回 accountId。

確認 GET 無 fact 時虛擬 version0/PENDING/confirmedAt=null，不新增 row／attempt；有 fact 必 version1/CONFIRMED/time，fact.version=1／原 policyMode=SYNTHETIC_TEST_ONLY。detail.execution 是原 COMPLETED view、version至少4；completion 是 [M404 原五欄 witness](tms-completion.md)，id/time 對齊 record。counter0 confirmation=null，counter1必非null。confirm body 精確三欄；expectedVersion 只屬行政 counter，非 execution4 或 TOM來源1。成功 version=原counter+1=1，reason/來源IDs與原次請求匹配。

confirmation policy 精確 `{mode,canConfirm,blocker}`，mode 是 CURRENT ManagementCompletionPolicy。已有 fact 時 ALREADY_CONFIRMED 優先，即使今日 UNCONFIRMED；尚未確認時 unconfirmed先 POLICY_NOT_CONFIRMED。其餘 blocker ORDER_NOT_OPEN／SOURCE_VERSION_MISMATCH／UNRESOLVED_EXCEPTIONS／PROOF_INVALID。新 MISS 要真完成、同 accepted typed source／OPEN+ACCEPTED及 policy 允許；不能只比 IDs/version，須比較原 completion saved source 的原值。不以今日 CRM 名稱／啟用狀態重寫或重新審核既有 accepted source。

close expectedVersion 只屬 TOM aggregate；OrderCore 沒有 version。close body 精確 `{expectedVersion,confirmClosure:true,reason}`，無 client/public digest、completion-ID array 或 waiver。witness.sourceVersion 是原 accepted source，witness.version=source+1，結果 CLOSED+ACCEPTED；原 typed task graph／子項 IDs／CRM 客戶快照保留。managerConfirmed=true 記錄明確請求及具名合成決策，不等於正式法律安全責任核准。

## 當前條件、歷史來源與 legacy

OrderClosureView.auditState 為 NOT_CLOSED／RECORDED／LEGACY_CLOSED。RECORDED 必 CLOSED+ACCEPTED／nonnull witness、targetversion一致；LEGACY_CLOSED必 closure/counts=null／history空；NOT_CLOSED涵蓋 OPEN/CANCELLED，closure=null。ClosureSummary.closedAt只從本包真 witness 取得，legacy不猜 updatedAt。counts 只在 OPEN+ACCEPTED 非null，sourceOrderVersion等當前TOM來源；terminal/inapplicable為null，非假歷史統計。

TaskReview200只限 OPEN+ACCEPTED 的 current facts 或 RECORDED CLOSED+ACCEPTED 的 immutable manifest。每筆 orderId/sourceOrderVersion 必與 request order 及 OPEN counts.sourceOrderVersion／RECORDED closure.sourceVersion一致，不能拿 close 後 targetversion 誤查。未派任務 assignment/completion可null、無execution虛擬WAITING0；completionVerified須真 typed proof，counter0/1與confirmedAt相符。RECORDED一律從儲存的完整manifest讀，不拼今日owner／新task；legacy不製造過去proof。

canClose iff blocker=null。ready需 current synthetic policy、OPEN+ACCEPTED、非MAX版本、全部 persisted tasks 真完成＋行政確認、零整單 effective reservations及阻擋異常。blocked requirements=null，非空集合/false/0 waiver。blocker 限 ALREADY_CLOSED／POLICY_NOT_CONFIRMED／ORDER_NOT_OPEN／ORDER_NOT_ACCEPTED／TASKS_INCOMPLETE／CONFIRMATION_REQUIRED／UNRESOLVED_EXCEPTIONS／RESOURCES_OCCUPIED／PROOF_INVALID。已 CLOSED 的 ALREADY_CLOSED優先於今日unconfirmed；MAX不能advertise ready，合成投影 PROOF_INVALID/null，新POST MISS E2102。

requirements 只有明示隔離測試回 `ALL_PERSISTED_TASKS,true,true,true`。正常 ManagementCompletionPolicy、OrderClosurePolicy 均 UNCONFIRMED；checkboxtrue、歷史SYNTH、無占用、RESOLVED或FMS AVAILABLE不批准新操作。合成factory只能test sources／明確launcher，實查 actual DB=xk_logistics_e2e 及既有 same-scope synthetic task/driver context，production JAR不存在，無 ENV／ADM／HTTP 核准開關。

## 交易、不可變證據與 SQL seals

V14 只新增 tms_completion_confirmation、tms_completion_confirmation_command、tom_order_closure、tom_order_closure_task、tom_order_closure_command，42→47表。confirmation own ledger、immutable administrative fact與closure own ledger／完整taskmanifest分開；不改既有completion／execution／reservation／原api_idempotency。讀取 counter0 不造DB紀錄。

新 witness 的 typed FK 綁 core(id,company,CLOSED)、tom_order(order_id,targetVersion,ACCEPTED)、完整 CLOSED revision及accepted source revision；sourceVersion不能 FK mutable當前TOM版本。manifest恰全 persisted taskset／原true-null flag／同order-source assignment-completion-confirmation，own reciprocal receipt／immutable fact／完整revision／root終態在commit相符。可存私有canonical digest輔助完整性，但不回wire、不當client授權token。所有deferred/SQL/receipt失敗全回滾，不能留下半結案或重釋放資源。

只對新 OWNED closure 套集合與snapshot seals：整單graph(item/task/dependency)、late assignment／active reservation／blocking exception及被引用assignment/current-anchor UPDATE、core/TOM所有raw source/audit欄位不再漂移。依actual typed identity派生order，涵蓋OLD+NEW／依賴兩端／move，不盲信caller order；legacy CLOSED無owned witness仍保持舊SQL／upgrade前態。禁止late append破壞manifest、零占用或零阻擋。

可信Java writer先拿canonical UPMS／原Session→TOM root→sorted TMS roots/rows，closure port不再倒鎖FMS或回調TaskQueries／CompletionService。TOM-owned OrderReviewQueries／OrderClosureFacts透過pure owned Jdbc adapters，避免跨repository／constructor循環。所有整單檢核持root至commit，零筆也不能免共享根鎖。

PostgreSQL UPDATE/DELETE executor在BEFORE ROW前已鎖target tuple，不能聲稱trigger lexical order能把TOM放在該implicit鎖前。INSERT guard可先鎖changed TOMroot再TMS；UPDATE/DELETE guard用sorted OLD+NEW roots NOWAIT／try-lock，競爭時failclosed/有界abort，不反向等待。RR stale changed-root snapshot須serialization-fail；lexical order只約束explicit guard。封存新owned facts，不全域推導CLOSED必有audit。

## 成功重播、錯誤與 unknown

順序：bounded shape/key→fresh normal manager／原Session／company record→同actor/method/canonicalpath/key/hash原成功→MISS才version/state/currentpolicy/完整source/proof。歷史管理read/replay不依DRIVER binding／今日FMS availability／provider／資源新owner／source仍open／今日policy。samekey改raw reason是不同hash；JSON escape/property order與安全整數1/1.0/1e0等價。成功收據是原次證據，不能替代fresh GET。

| HTTP409 code | reason |
| --- | --- |
| E2101 | VERSION_CONFLICT，恰一個 route-specific counter |
| E2102 | INVALID_STATE |
| E2103 | POLICY_NOT_CONFIRMED |
| E2104 | IDEMPOTENCY_KEY_REUSED |
| E2105 | IDEMPOTENCY_IN_PROGRESS |
| E2106 | PROTECTED_RELATION |
| E2107 | TASKS_INCOMPLETE |
| E2108 | CONFIRMATION_REQUIRED |
| E2109 | UNRESOLVED_EXCEPTIONS |
| E2110 | RESOURCES_OCCUPIED |

ErrorEnvelope仍company四欄，data為null或非空exact `{reason?,actualConfirmationVersion?,actualOrderVersion?,fields?}`。E2101要求EXACTONE actualConfirmationVersion0/1（confirm路由）或actualOrderVersion（close路由），無generic actualVersion／actualExecutionVersion／generation；strict client按命令context拒錯單位。E2111不存在。400 E0400必VALIDATION_ERROR+非空string fields；401/404/405/413/415/500 data=null；403 null相容或reason-only CSRF_INVALID|FORBIDDEN。腐敗storedtypedproof安全500，不因SQLcause假分類為業務關聯拒絕；不洩SQL／IDs／key／body／digest／storage。

只有同一frozen POST body/path/key，且確定success-ledger MISS後發生E2101/02/03/07/08/09/10，才能結清先前unknown。E2104/05/06、common/auth/CSRF/shape/transport/schema/witness及所有GET錯誤皆不是回滾證據。first CSRF refresh不自動POST；refresh401/403須safe exit清敏感資料，保留不確定寫入語義。不背景換key、不以uncertain=false單獨結清。

管理UI需在await CSRF前clone/freeze request及原baseline，確認ack IDs/sourcecounter/rawreason/200/replayheader；close核orderId/orderNo/CLOSED+ACCEPTED/原TOM+1/witnesssource/taskCount。原收據與fresh資料分開，fresh GET後才准下一新command，GET不settle未知POST；保dirty/busy/unknown導覽與logoutguard。只新增管理確認／結案workspace，完整DRIVER UI留M406。

## 契約驗證邊界

獨立 [validator](../../tests/contracts/validate_closure_contract.py)檢查本包9操作／49schemas／12原component與8來源pins、strict條件DTO、正反例、offline refs及nonempty body-only `closure-contract-responses.json`。只接受本頁九個success envelope與ErrorEnvelope的public synthetic `{schema,value}`；不捕捉request/key/Cookie/token/header/SQL/私有storage／image/base64。顯式公開actor role/displayName僅合成回應事實，不含accountID。

普通CLOSED GET/list仍在原order artifact用OrderEnvelope／OrderPageEnvelope；舊accept replay仍在原acceptance artifact用OPEN窄schema。靜態工具不證明fresh原Session、實際16KiB stream、整單集合封存、RR/transaction/deferredFK、全量rawrevision、typed source一致、headers/status或真HTTP。這些必須由後端PostgreSQL與真HTTPS desktop/mobile E2E及全CI提供實測證據。
