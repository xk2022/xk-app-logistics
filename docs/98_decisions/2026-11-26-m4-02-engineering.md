# M4-02 進度與私有照片｜工程採用

狀態：Approved（本包工程選擇）；日期：2026-11-26。依 [Q41–Q47](2026-11-26-m4-execution.md) 與使用者全部 M4 授權。D06 照片規則、D10 保存／清理、正式 provider／現場例外／安全釋放與結案責任沒有因此核准。先凍結契約再實作；精確 wire 以[獨立 API](../06_api/tms-progress-evidence.md)／[OpenAPI](../06_api/tms-progress-evidence-openapi.yaml)為準。

## 基線及相容性

前包 [PR #23](https://github.com/xk2022/xk-app-logistics/pull/23) source `71dac55cdf568ddb6f46c043c0f74d81d3fafe07`／merge `daf26f979c1c03c078876f204a713578abe80a4e`，PR CI45768556588／main CI45769255127 attempt1 均四過後，從該 main 建立 `feat/lg-m4-02-progress-evidence`。公司最新 `9213a3d8dacb8774d87c26cb736c4a4315779e95`，開工重讀 APP 開發、測試、API、安全與工作流程規範。保留 V1–V10、原訂單等契約、原 M3 七項操作、十四欄本人摘要、M4-01 十五欄詳情及三欄出發收據，E16／E17 不擴欄。新 API 使用獨立 E18 契約。

## 進度與證據

只開放 DEPARTED→IN_PROGRESS→AWAITING_COMPLETION。JSON 命令精確為 expectedVersion、targetPhase、evidenceIds；ID 是 lowercase UUID 排序後的數學集合，重複（包括大小寫不同）拒絕，陣列先後不構成業務優先順序。最多32是技術命令上限。進度成功只增加 execution.version 一次、保留 departedAt，追加事件／選定證據關聯／成功收據；不改 assignment、generation、TOM／FMS 或占用，也不解前置依賴。

照片 POST 為 bounded raw bytes，恰一 X-Execution-Version、Content-Type、Idempotency-Key；不接受檔名、類型分類、路徑、呼叫者時間、actor、phase 或 client hash。phase／version 由伺服器共同鎖內取得。上傳本身不增加 execution version；成功 metadata、原始 bytes、收據同 PostgreSQL 交易，對外只有 VERIFIED，失敗沒有可滿足要求的 pending／failed／部分檔案。另 key 可新增另一筆原始證據，沒有覆寫或任意內容去重。

Metadata 精確十欄：id、assignmentId、taskId、phase、executionVersion、mediaType、byteSize、sha256、uploadedAt、status。uploader actor 及 policy snapshot 存在內部；actor 僅稽核，不新增「目前登入者必須等於歷史上傳人」規則。讀取與進度選照依目前 driver 綁定、同次有效 assignment、phase／version／VERIFIED 及目前策略檢查；原 actor 的命令收據仍 actor scoped。

## 未確認策略與隔離替身

ExecutionEvidencePolicy 與 EvidenceStorage 分開；普通應用預設 UNCONFIRMED／unavailable，沒有 ENV／profile／ADM／HTTP 核准開關或 SQL fallback。歷史派工的合成 mode 不等於現在照片政策核准。新 upload／progress 同時要求當前及歷史派工合成模式、目前獨立照片策略／provider、相同合成 scope。

正向 bean 只在 test sources／明確外掛 launcher，policy 與 provider 各自實查 SELECT current_database() 必須為 xk_logistics_e2e，並限制 IT／DESKTOP／MOBILE 合成 driver／task scope。合成規則為上述兩個 from-phase 各至少1張、image/png、65536 bytes、4096 pixels；這些不是正式 D06 標準。政策 GET 的七欄投影在未確認或不支援階段時 nextPhase／requirements 均為 null，不能以0或空清單豁免照片。

內容讀取權與是否存在下一進度規則獨立：AWAITING_COMPLETION 的 nextPhase 為不支援，但仍可在目前合成 read policy／provider 允許下讀先前照片；UNCONFIRMED 仍拒 bytes。照片規則與正式 provider 另經核准後才能新增，不能把 test provider 當正式選型。

## 真正有界及私有讀取

原始 InputStream 依實際 bytes 讀取並限制 limit+1，硬傳輸上限1048576（工程資源界線）；不能只信任 Content-Length，不能先無界 @RequestBody 配置。僅新 upload UUID 路徑將 DEV nginx 限額設2m，讓應用 1MiB 上限能回413 JSON；其餘全域16k保留。超過 edge 2m 的拒絕是 edge 行為，不冒稱 application envelope。重複 headers、media parameters、非 identity encoding 拒絕。

只在新命令、目前策略確認後驗 PNG signature／chunk 邊界與順序／CRC／唯一 IEND／無尾隨資料／尺寸與 pixel 上限，再有限解碼；保留完整原 bytes 與 SHA256，不重新編碼或假稱防毒／法律證據認證。

GET content 每次先重驗本人 Session／目前指派，再查證據及目前 read policy／provider、核對 stored length／hash。回 image/png、固定 server filename、no-store/private、nosniff；無公開 URL、provider key、客戶或帳號資料、cache validator、304／206／redirect；Range 拒絕、HEAD405。JSON 只最小 metadata，binary 不放報告或 base64。

## 鎖、重送及交易保護

延續 canonical UPMS→fresh actor＋原 HttpSession→目前綁定 driver→排序 vehicle→TOM 來源→TMS anchor／assignment／完整有效占用→execution／evidence。歷史收據之前仍重驗這些條件；失效／跨人404、不跟隨改派；登出／撤權401，管理角色不代理司機。

hash 不含目前 phase／policy／server clock／generated ID。照片以原 requested version、normalized media、實際長度／完整 bytes hash；進度以 normalized version／target／sorted unique IDs。fresh scope 後先比對原收據，才檢查今日版本／phase／policy。合法重播回原結果，階段已前進也不重寫照片或事件；不同內容同 key 拒絕，正在處理 key 有界拒絕，未知結果保留原次重試。

V11 追加 tms_execution_evidence、tms_execution_evidence_object、tms_execution_event_evidence、tms_execution_evidence_command 四表（最新34），全部 immutable／無回填。進度使用 V10 event／command，保持原出發 codec／結果相容。合成 private bytea adapter 必須同 DataSource、同 caller transaction，禁止 auto-commit／REQUIRES_NEW；metadata、blob、receipt 失敗整筆回滾，原 key 可重試。沒有 FS／outbox／清理工作或已接受證據刪除；未來非交易 provider 必須另有耐久 prepare/finalize／補償協定，不能直接替換後宣稱跨系統 ACID。

本包沒有完成、安全釋放、補件／處置、管理結案、完整手機 UI、M5 或正式部署。[實測紀錄](../09_testing/m4-02-validation.md)保留真實測試與失敗，不以本工程採用文件代替執行結果。

實作防禦界線：產品端只驗最低張數1..32、已實作PNG codec、正byte上限不超過1MiB及正pixel上限不超過1,048,576，以限制命令／配置；1／65536／4096 的具體規則只由 test policy 回傳，普通應用沒有預設規則。DB additionally 以實際SHA256／雙向延遲FK禁止孤立blob，事件選照以同assignment／from-phase／from-version複合FK配對；這些是完整性保護，不是正式照片政策。

傳輸審查修正：停用目前沒有任何已支援 API 使用的 Boot multipart resolver；新 raw upload 不呼叫 Servlet getParameterMap，CSRF 僅該 POST path 由 header 讀 token，避免安全／MVC 層先解析 form body 才到 bounded reader。其餘既有路徑沿原 CSRF handler，不放寬 Session／CSRF 檢查。真 TLS 的 wellformed／malformed multipart、urlencoded 與缺 CSRF 情境由本包 IT 驗證，不能用後端 byte[] 長度檢查冒稱 pre-handler 有界。
