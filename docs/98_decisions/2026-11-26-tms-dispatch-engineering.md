# TMS 派工工程決策｜LG-M3-04

日期：2026-11-26。依 [Q36–Q40](2026-11-26-tms-dispatch.md)與 [PR #18](https://github.com/xk2022/xk-app-logistics/pull/18)，保留未決法規／現場政策及 M4 邊界；公司規範固定 `5be640ab11811b24706101ea13db73185e9ce4d8`。沿用既有精確版本，不新增依賴。本頁為授權範圍內的工程細化，實際測試另由驗收紀錄回報。

## 版本、來源與模組邊界

TMS 擁有每段任務的派工 generation，初值 0，指派／撤派／改派各成功一次加 1；所有命令須提供 `expectedGeneration`，安全整數上限沿既有 9007199254740991。Assignment 本身 version 由 0 在唯一終止轉態變 1，不作任務 concurrency token。改派建立新 UUID，原紀錄標 SUPERSEDED；撤派標 REVOKED，均保留原資源及來源快照。完全相同人車組合的改派視為無操作，400 拒絕，不製造新歷史。撤／改原因必填，新的 TMS 文字採 NFC、外側 Unicode 空白移除、1–500 字元，不改 TOM 原始資料正規化規則。

TOM 新增公開窄 `TaskQueries` 及 `AcceptedTaskIndexSink` SPI，由 TMS 實作索引接收；受理新成功與索引同交易，失敗連受理一併回滾，不改原受理 wire 或歷史重播。TMS 自有索引負責待派／已派的 filter、count 與分頁，再經 TOM 公開批次查詢 hydrate 最多 100 個來源，禁止跨模組存取 repository／SQL 表或先分頁再排除造成錯誤總數。V9 只衍生既有 OPEN＋ACCEPTED 任務的識別與來源版本索引，不捏造派工、占用、事件或受理人。

新派工仍鎖定並重驗目前 OPEN＋ACCEPTED 同單任務、來源版本及前置；任何前置尚無 M4 完成／交接證據就阻擋。撤派、時間經過、無占用都不是前置完成。受理後的 CRM 改名／停用不重寫訂單快照。索引所指來源若失去有效狀態，保留歷史並標示阻擋，不偷偷在單頁排除或准許新派工。

## 未核准適格與合成政策

Production `DispatchEligibilityPolicy` 預設全部 `POLICY_NOT_CONFIRMED`，沒有環境開關、ADM 參數或 HTTP 操作可核准正式組合。FMS AVAILABLE／ACTIVE＋ON_DUTY 只證明行政來源，不能當成派遣適格。

正向工程驗收使用 **test source** 的 `SyntheticDispatchPolicyConfiguration` 與 `TmsE2eApplication`；只透過隔離測試 classpath 載入，啟動查證 `current_database() = xk_logistics_e2e`，正式 JAR 不含 override／launcher。合成司機編號必須符合 `SYNTHETIC_TMS_(IT|DESKTOP|MOBILE)_D[0-9]+`，車種必須為同 scope 的 `SYNTHETIC_TMS_<scope>_(MAIN|TRAILER|SHARED)`，任務類型也須同 scope 的 `SYNTHETIC_TMS_<scope>_` 前綴。合成車種類別也必須目前啟用；MAIN／TRAILER 僅接受相應類別及 SHARED。SHARED 是專門驗證跨位置排他的人工測試分類，不是正式車頭／拖板配對決議。快照及回應明示 `SYNTHETIC_TEST_ONLY`，不宣稱重量、檢驗、駕照或工時已合規。

## 占用、鎖序與 FMS 協調

新寫入順序為：目前角色驗證、嚴格 body／key／canonical hash、自有 key try-lock、成功重播查詢；未命中才取得 UPMS canonical 與 fresh actor、排序的司機／車輛來源鎖、TOM 來源鎖、TMS 任務 generation 與派工／占用鎖。舊資源讀取僅供鎖定提示，鎖後必須重驗；主車與拖板使用同一車輛 namespace。資料庫 partial unique 保護每段唯一 ASSIGNED 與每個未釋放資源，不能只靠應用層先查再寫。

改派在同交易釋放舊占用、標記舊派工、新增新派工及占用、更新 generation、事件與成功結果，任何步驟失敗原占用完整保留。撤派鎖當前來源但不要求今日適格或可用，避免無法釋放已降級來源；本包 ASSIGNED 僅代表出發前狀態，沒有出發或假完成入口。

FMS 擁有 `MasterDataChangeGuard` SPI，TMS 實作自有占用查核，避免 FMS import TMS internal 或反向 repository 存取。占用中的資源不得失去車輛 AVAILABLE／司機 ACTIVE＋ON_DUTY；另保守拒絕實際變更車種、載重、檢驗期限或司機駕照類別／期限，保留該次配置依據而不發明法規門檻。同值與容量數值相等的格式變更保留舊規則；名稱／聯絡等校正仍可進行，不改歷史快照。拒絕沿原 FMS E0409 envelope，不覆蓋舊契約。綁定／解綁／改綁仍由 FMS 管理，本人權限每次依目前綁定推導。

## 契約、歷史與本人最小化

七項 TMS 操作使用獨立 OpenAPI 與公司 `code/message/data/timestamp` envelope；新增有界的 `/tms/resources` 候選查詢，以便伺服器回行政、占用與政策阻擋，而非擴大 ADM 權限。所有新命令固定 HTTP 200 與 `Idempotency-Replayed`；重播沿 actor／method／canonical path／key 隔離，先 fresh 授權，回原業務結果，不因後來撤改而重新占用。未知結果不能盲目換 key。既有 ErrorData.reason 明確區分已登入 CSRF_INVALID 與 FORBIDDEN，避免前端誤登出而丟失安全重試，或誤保留撤權後資料；匿名 CSRF 仍 401／null，不靠訊息猜原因。精確 DTO、錯誤碼與分頁以獨立 OpenAPI 為準，原 TOM／FMS 契約保持原樣。

TMS 以 JdbcTemplate 執行自有表 SQL，加入既有 JpaTransactionManager 的同一資料來源交易；保留 Spring Data JPA 的來源模組與技術基準，沒有跨模組 repository 或獨立提交。此方式延續 TOM 對原始 JSON／精度的保留，也便於明確鎖序及 partial unique；實際共同回滾必須由 PostgreSQL 交易測試證明。

V9 新增五張 TMS 自有表：`tms_task_dispatch`、`tms_assignment`、`tms_resource_reservation`、`tms_dispatch_event`、`tms_idempotency`，全庫 27 表（含 Flyway）。版本／FK／唯一性與不可變 trigger 防護歷史；JSON snapshot 使用 textual json 保留原 TOM NUL、decimal、offset、null／空物件，不以 jsonb 或會解碼 NUL 的資料庫 JSON 擷取破壞資料。D10 未核准前沒有硬刪或清理 API。

DRIVER 本人端點使用可鎖交易，canonical → fresh 正常 DRIVER → 目前 FMS 綁定 → 本人目前 ASSIGNED；無綁定回 404，有綁定無任務回空頁。只回當段標的、路線、必要聯絡、作業種類與時間，不回訂單／客戶／司機／帳號／資源識別、整單備註、證照、管理稽核、占用或歷史。撤改、解綁、角色／啟用及 Session 撤銷後，舊權限立即失效，不快取本人識別。

M3-04 只交付 API 與占用；完整工作台在其 PR／main CI 全過後才由 M3-05 整合。M3-06 再做全量整合驗收，不以本頁作為已通過證據。
