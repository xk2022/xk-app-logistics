# TOM 受理資料模型｜LG-M3-03

沿既有[OrderCore／TOM](tom-model.md)、[Q31–Q35](../98_decisions/2026-11-26-tom-acceptance.md)，新增[獨立API](../06_api/tom-acceptance.md)及[工程決策](../98_decisions/2026-11-26-tom-acceptance-engineering.md)。V1–V7保持原樣，V8只新增 logistics.order_acceptance_event（共22表含Flyway），無seed、虛構歷史回填、派工、占用或配對表。本文描述不變量，不代替實際測試證據。

| 實體 | 本包變化 |
| --- | --- |
| OrderCore | 同UUID、OPEN不變，更新server audit |
| TOM root | DRAFT一次→ACCEPTED，aggregate version恰+1，更新server audit |
| Cargo／Task／Dependency | 保留UUID／taskNo／ref／陣列次序／原始值／created audit，不重建或重新配對 |
| order_draft_revision | 追加同一V+1完整受理後讀回snapshot，舊草稿revision不改 |
| order_acceptance_event | 每單至多一筆，受理版本與actor／時間／完整受理後snapshot不可變 |
| api_idempotency | 沿既有隔離與原始data保存，POST canonical path加/accept |

| 新event欄位 | 型別／不變量 |
| --- | --- |
| id | server UUID PK |
| order_id | 非空、UNIQUE、FK tom_order；每單至多一筆 |
| from_version / to_version | 非空safe integer；to=from+1；0≤from<to≤9007199254740991 |
| actor_id | 非空UPMS actor UUID FK，server fresh授權取得 |
| accepted_at | server Clock截微秒timestamptz，等於當次root.updatedAt |
| snapshot | 非空JSON（不是jsonb），完整受理後原OrderDraftRead，OPEN／ACCEPTED／toVersion |
| request_trace_id | 非空server本次trace，最多200字元，與revision同次保存 |

event的(order_id,to_version) FK對應revision唯一鍵，先寫revision再event；不能接收客戶端actor／時間。BEFORE UPDATE／DELETE trigger保護不可變，沒有公開修改／刪除／TRUNCATE API；隔離測試cleanup可TRUNCATE。不建立D10期限清理，不宣稱永久保存政策已核准。

snapshot精確包含當次完整原讀回結構：root、shipping、items、tasks、predecessor refs／IDs、各server身份及歷史customerName。與V+1 revision相等，而非只存request、只存缺欄結果或重取CRM名稱。沿JSON文本保存escaped U+0000、null與空物件、文字／offset字面及精確decimal；不用jsonb或會解碼NUL的JSON extraction CHECK破壞舊HTTP表示。結構／FK／版次仍受DB約束，完整snapshot由application與測試驗證。

## 新受理交易與重送

read授權→strict shape／hash／key→既有tuple advisory try-lock→成功key lookup。重播仍fresh canonical授權／公司範圍，回原200業務結果與新traceId；先於今日version／state／customer／完整性，不另寫event／revision／版本。原POST／PUT重播在受理後仍回其歷史DRAFT結果，不讀當前ACCEPTED取代原結果。

新key：UPMS canonical＋fresh manager actor→觀察scoped order/customerId→CRM公開requireEnabledForNewReference鎖有效source→fresh locked order_core＋tom_order→確認customerId未變→expectedVersion一致、OPEN＋DRAFT、版次未達上限→原AcceptanceChecker重驗locked persisted完整聚合→只在COMPLETE轉態。CRM只驗eligibility，不取新name覆寫原快照；受理不變更客戶／任務內容。若可信外部SQL在觀察與鎖定間改了customerId，fail closed409，不用未鎖source放行。

native明確version+1即使fixed Clock仍恰加一次；acceptedAt為截微秒server now，root／core audit同次更新，created audit與子項不變。新狀態、V+1 revision、event及success replay同一transaction；任一保存失敗全回滾。客戶停用／改名、草稿PUT、角色／撤銷及兩次受理依canonical／CRM／root source locks有明確先後，不信任較早check，不跨模組repository。

新key重複受理409，已受理舊草稿PUT新key拒絕，無撤回／重開。原check仍無任何寫入、NOT_GRANTED保持原值；此值不是order當前受理狀態。新API／舊GET/list DTO不加入event metadata，UI也不能用updatedAt或client time代作正式受理事件。pending D04／D05六ID不新增全面禁用，也不授予例外，仍只執行已採ORD-P01–P03。
