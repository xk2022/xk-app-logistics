# LG-M4-02｜受控進度與私有照片契約

本包以 [Q41–Q47](../98_decisions/2026-11-26-m4-execution.md)、[M4 工作包](../08_development/m4-execution-handoff.md)及[獨立 OpenAPI 0.1.0](tms-progress-evidence-openapi.yaml)為準。前置 main 為 `daf26f979c1c03c078876f204a713578abe80a4e`；公司工程基準為 `xk2022/xplore-kaleidoscope@9213a3d8dacb8774d87c26cb736c4a4315779e95`。本文件是精確工程定義，不能代替實測證據或 D06／D10／正式照片提供者核准。

只新增 `DEPARTED→IN_PROGRESS→AWAITING_COMPLETION`、照片證據與策略介面。不提供完成、占用釋放、前置依賴解鎖、異常、補件、管理代操作、結案、UI、背景重送、清除或正式部署。[M4-01](tms-execution.md) 的兩項 API、十五欄詳情、三欄出發收據、原十四欄任務列表及所有 M1–M3 契約維持原樣；後續包以獨立契約增量提供。

## 五項 API

以下共同前綴為 `/api/v1/tms/my-tasks/{assignmentId}`。五項都限正常 DRIVER；ADMIN、DISPATCHER 不具代理權。身份、公司與目前司機綁定由伺服器取得，不接受 caller 身份覆寫。

| 方法 | 路徑後綴 | 請求 | 成功 |
| --- | --- | --- | --- |
| GET | `/progress-policy` | 無 query | `ProgressPolicyEnvelope` |
| POST | `/progress` | JSON `{expectedVersion,targetPhase,evidenceIds}` | `ProgressEnvelope` |
| POST | `/evidence` | raw PNG bytes | `EvidenceEnvelope` |
| GET | `/evidence` | 只允許 page、size | `EvidencePageEnvelope` |
| GET | `/evidence/{evidenceId}/content` | 無 query | 私有 `image/png` bytes |

成功固定 HTTP 200。JSON 使用精確 company envelope `{code:"0000",message,data,timestamp}`，timestamp 是有效 Gregorian ISO 8601 `+08:00`；不混入 TOM 的 OK／traceId。兩項 POST 需同源 HTTPS Session、CSRF 及恰一個區分大小寫的 `Idempotency-Key`，格式 `[A-Za-z0-9._:-]{1,128}`；成功必有 `Idempotency-Replayed:true|false`。不提供 Location、公用／預簽 URL 或 caller filename。

## DTO、版本與照片選擇

所有物件欄位必填，未知欄位拒絕。整數為安全數學整數 0–9007199254740991；JSON `1`／`1.0`／`1e0` 同義。UUID request／path 可大小寫，輸出與 ledger canonical path 使用 lowercase。

`ExecutionView` 精確沿用 M4-01 `{phase,version,departedAt}`，執行版本與 assignment version、task generation、TOM／FMS 版本獨立。WAITING 為 0／null，其餘階段版本至少 1、departedAt 非 null；不增加照片數量、事件或其他時間欄位。讀取 enum 保留 WAITING、DEPARTED、IN_PROGRESS、AWAITING_COMPLETION、COMPLETED，並不因此開放完成。

`ProgressCommand` 精確為 `{expectedVersion,targetPhase,evidenceIds}`，targetPhase 只接受 IN_PROGRESS、AWAITING_COMPLETION；新命令必須是當前相鄰轉移。evidenceIds 是最多 32 筆的 UUID 集合，此數值是技術資源上限；按 lowercase UUID 排序計算 hash，輸入順序不構成業務優先序。語意重複 UUID（包括大小寫差異）回 400，不默默去重。空陣列符合輸入型別，仍會因缺少必要證據而拒絕新進度。

`ProgressResult` 只有 `{assignmentId,taskId,execution}`。新成功的 phase 等於原 targetPhase、version 等於 expectedVersion+1，原 departedAt 保持；這是原次不可變收據，不是當前狀態。不得用歷史收據作下一次命令的最新版本。

`Evidence` 精確十欄：`{id,assignmentId,taskId,phase,executionVersion,mediaType,byteSize,sha256,uploadedAt,status}`。本包只在目前 DEPARTED／IN_PROGRESS 建立，phase 讀型別仍保留完整五值。mediaType 固定 image/png，byteSize 是實際正安全整數，sha256 是伺服器對完整原 bytes 計算的 64 位小寫 hex；uploadedAt 為伺服器時間，status 只會是 VERIFIED。歷史上傳 actor 內存供稽核，不回 DRIVER，也不另加「目前帳號必須等於原上傳 actor」的授權條件。

`EvidencePage` 精確為 `{content,page,size,totalElements,totalPages}`。page 預設 0、範圍 0–100000，size 預設 20、範圍 1–100；固定 uploadedAt ASC、id ASC。拒絕未知／重複 query。列出同一目前指派的已驗證照片，不限目前階段；不回 bytes／base64、檔名、帳號、司機、客戶、訂單、資源、儲存 object/key/path、URL 或事件歷史。

## 當前政策與未核准限制

`ProgressPolicyView` 精確七欄：`{assignmentId,taskId,execution,mode,nextPhase,requirements,blocker}`。mode 只為 UNCONFIRMED 或 SYNTHETIC_TEST_ONLY。blocker 只為 null、POLICY_NOT_CONFIRMED、UNSUPPORTED_PHASE。

任何非 null blocker 都要求 nextPhase 與 requirements 同為 null；不能用空照片清單或 0 表示未核准的免照片規則。UNCONFIRMED 一律 POLICY_NOT_CONFIRMED；同時有政策與階段阻擋時政策優先。SYNTHETIC_TEST_ONLY 亦可能因 provider 未就緒而 POLICY_NOT_CONFIRMED。無 blocker 才能回兩個合法相鄰 nextPhase 及非 null requirements。WAITING／AWAITING_COMPLETION／COMPLETED 沒有本包下一進度，回阻擋投影，不捏造完成門檻。

requirements 精確為 `{minimumCount,allowedMediaTypes,maxBytes,maxPixels}`。只有實查資料庫為 `xk_logistics_e2e`、同 scope 合成司機／任務、明確 test-source 外掛可回本包測試值：minimumCount=1、allowedMediaTypes=[image/png]、maxBytes=65536、maxPixels=4096。每個合法轉移都需同一指派、當前 from-phase、當前 executionVersion 的 verified PNG。這些數值不是正式分類、數量、格式、大小、必填階段、保存或提供者決策。

正常 EvidencePolicy 為 UNCONFIRMED，正常 EvidenceStorage 不可用；沒有 SQL／filesystem fallback 或 ENV／ADM／HTTP 核准開關。正向外掛需驗證真實 DB 名稱與合成 scope，且不在正式 JAR。新上傳／進度需目前及原派工模式、目前證據策略、私有 provider 同時符合隔離測試規則；原記錄 SYNTHETIC_TEST_ONLY 不代表目前批准。FMS 行政狀態不構成正式營運適格。

私有讀取權與下一階段規則分開：合法目前本人在 AWAITING_COMPLETION 仍可依當前合成 read-policy／provider 讀先前照片，即使 progress-policy 的 nextPhase 為 null；真正 UNCONFIRMED 則拒絕 bytes。政策查詢是當前資料，不能當成 caller 可提交的批准 token。

## Raw 上傳與私有讀取

上傳另需恰一個 `X-Execution-Version`，ASCII digits、0–MAX，禁止 leading zero（單獨 0 除外）、符號、指數、小數、空白或逗號。其文字 grammar 刻意比 JSON 數學整數嚴格。恰一個 Content-Type，只接受 image/png（HTTP type/subtype 大小寫同義），拒絕 media parameters、重複 Content-Type／Content-Encoding／命令 metadata，Content-Encoding 只可缺省或 identity。不接受 multipart、JSON metadata、evidenceType、caller phase／time／hash、filename 或儲存路徑。

以 bounded stream 讀取實際 bytes，不依 Content-Length 配置記憶體，也不在完整未限量讀取後才檢查。技術 stream cap 為 1048576 bytes；超限回應用程式 413／E0413／null。nginx 只為精確 UUID upload route 開 2m，其他路徑保留 16k；更大 edge 拒絕不宣稱為 application JSON。缺省／chunked／不實長度仍需實測。

新命令通過本人／版本／階段／政策後才做 PNG signature、chunk 邊界／順序／CRC、唯一終端 IEND／無 trailing data、有限正 dimensions、政策 byte／pixel cap、完整 decode 驗證。不可只信 MIME，也不 silently 轉碼、刪改 metadata 或改寫原證據。失敗不建立有效照片、blob 或成功收據；VERIFIED 是本包技術驗證，不是病毒掃描、法規認證或後續完成責任核准。

私有內容每次重驗目前本人與 read-policy／provider。成功使用 image/png、實際 Content-Length、`Cache-Control: no-store, private`、`X-Content-Type-Options: nosniff`、固定 `Content-Disposition: inline; filename="evidence.png"`。不回 ETag、Last-Modified、Accept-Ranges、Location 或 Content-Location，不提供 206／304／redirect。Range 拒絕 400；conditional headers 不得省略授權或產生 304。HEAD／其他未契約方法 405；拒絕始終為 company JSON，不回部分照片。所有新成功與錯誤回應均 private／no-store。

## 授權、重送與原子性

沿既有 UPMS→目前 Session→排序 FMS／綁定→TOM→TMS anchor／assignment／完整 occupation→execution→evidence 鎖序。每次取得正常 DRIVER、目前有效綁定、同一 current ASSIGNED、OPEN／ACCEPTED 的當前 TOM source／version、完整二／三筆有效占用，鎖後重驗 hint。非本人、已撤改／來源或占用失效、foreign evidence 一律 opaque 404／null；不把舊 path 重導新指派。GET 純讀，不建立 attempt、access event 或任何種子資料。

命令順序為安全／shape／bounds→canonical actor/method/path/key try-lock→fresh current scope→原成功 receipt/hash→僅在 miss 時檢查版本、階段、當前政策與證據。重播保留原 data/status，timestamp 可更新，不重做 phase、目前政策或 provider bytes 讀取；合法上傳可在進度推進後重播原 metadata。失效本人不得取得舊收據。401／CSRF／404／shape 拒絕不能證明先前未知交易回滾；未知結果固定原 body／path／key。

上傳 hash 包含 normalized expectedVersion、normalized mediaType、實際 byteSize 與原 bytes 的 SHA256，不含目前 phase、政策、clock、隨機識別、Content-Length 或 transfer chunks。同 key 不同 bytes／version 拒絕；新 key 可以追加另一張原證據，不作跨 key 的隱性內容去重。上傳不升 execution version，不寫進度 event。

進度 hash 使用 canonical targetPhase、數學版本與排序後唯一 UUID 集合。所有選定照片必須同目前 assignment／task／driver、VERIFIED、同當前 phase/version、實際 durable bytes/hash 並符合當前規則；不能暗自找其他照片補足。缺少有效數量為 EVIDENCE_REQUIRED；foreign／不存在／未驗證／過期階段／版本／bytes 不一致統一 EVIDENCE_INVALID，不列出私有 ID。event link 按 canonical UUID 順序記錄。

V11 新增 `tms_execution_evidence`、`tms_execution_evidence_object`、`tms_execution_event_evidence`、`tms_execution_evidence_command` 四表，不改 V1–V10／不回填。測試 bytea provider 必與 metadata／receipt 使用同一 transaction-aware JDBC／JpaTransactionManager；blob＋verified metadata＋上傳收據同交易，進度＋event＋links＋原收據同交易，任一步失敗全回滾。原照片、連結與收據禁止覆寫／刪除，actor／時間／關聯內存。未來外部 provider 不能冒用此介面宣稱跨儲存 ACID。上傳與進度競爭序列化，遲到的舊版本照片不能附到新階段；進度不改原 assignment、generation、占用、TOM／FMS 或前置依賴。

## 獨立錯誤與驗收證據

ErrorEnvelope 精確 `{code,message,data,timestamp}`；data 僅 null 或非空 `{reason?,actualVersion?,fields?}`。fields 為非空 string→string map，actualVersion 只屬 execution。不得回 actualGeneration、SQL／cause、原 body、key、hash、actor 或 storage 資料。

| HTTP | code | data／reason |
| --- | --- | --- |
| 400 | E0400 | VALIDATION_ERROR＋fields，含無效 PNG／政策 byte、pixel 超限 |
| 401 | E0401 | null |
| 403 | E0403 | null 相容，或僅 reason=CSRF_INVALID／FORBIDDEN |
| 404／405／413／415／500 | E0404／E0405／E0413／E0415／E0500 | null |
| 409 | E1801 | VERSION_CONFLICT＋actualVersion |
| 409 | E1802 | INVALID_STATE |
| 409 | E1803 | POLICY_NOT_CONFIRMED |
| 409 | E1804 | IDEMPOTENCY_KEY_REUSED |
| 409 | E1805 | IDEMPOTENCY_IN_PROGRESS |
| 409 | E1806 | PROTECTED_RELATION |
| 409 | E1807 | EVIDENCE_REQUIRED |
| 409 | E1808 | EVIDENCE_INVALID |

新 E18 不加入舊 E16／E17；M3 已出發撤改仍依原 E1603 拒絕，舊原成功管理收據保持重播。DB／provider 暫時故障回安全 E0500，不作其他儲存 fallback。

獨立[驗證器](../../tests/contracts/validate_progress_evidence_contract.py)只接收非空 `progress-evidence-contract-responses.json` 的公開合成 `{schema,value}`，五個 JSON envelope 名稱與上表一致；二進位成功不放 JSON／base64／照片 artifact。bytes/hash/media/cache/status/current authority 由真實 HTTP IT／E2E 直接斷言。body-only schema不能證明 Session／目前範圍、原子回滾、GET零寫入、header、path identity、跨 request 版本+1 或正式政策已核准。靜態與 CLI 工具驗證不是實際營運驗收；實測數量與結果由本包驗收文件另行記錄。
