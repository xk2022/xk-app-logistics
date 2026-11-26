# LG-M4-01 執行詳情與出發｜工程採用

基礎：[Q41–Q47](2026-11-26-m4-execution.md)與[七包交接](../08_development/m4-execution-handoff.md)，規格 PR #22 的 source `53e308ac40911b7b0507376595b56cd0d0ea3b55`／merge `6e83663eac50256592e35713467d496cc5765235`。公司工程基準為 `xk2022/xplore-kaleidoscope@e0229b97acb2bf6c2d83f8155665f68b11c27d71`。以下是授權範圍內的工程定義，不替代未核准照片、資格、安全釋放、結案責任或保存政策。

## 契約與版本

- 新增獨立本人任務詳情 GET `/api/v1/tms/my-tasks/{assignmentId}`、出發 POST 同路徑 `/depart`；精確 DTO、錯誤與權限以[執行 API](../06_api/tms-execution.md)及[獨立 OpenAPI](../06_api/tms-execution-openapi.yaml)為準。
- 舊 `/tms/my-tasks` 維持十四欄摘要；新詳情十五欄，僅增加 `execution={phase,version,departedAt}`。不提供客戶、整單、其他人、車牌、資源或帳號識別及事件操作者。
- 狀態列舉固定 `WAITING`、`DEPARTED`、`IN_PROGRESS`、`AWAITING_COMPLETION`、`COMPLETED`；本包只開放 `WAITING→DEPARTED`。其餘階段的 API 與門檻由後續包增量交付，不因列舉存在而宣稱已實作。
- 未出發為虛擬 `WAITING/version0/departedAt=null`，GET 不建立資料；首次出發才建立 `DEPARTED/version1`。執行版本獨立於派工 generation、assignment version、TOM／FMS 版本，不改寫原快照。
- POST 僅接受 `expectedVersion`、單一 Idempotency-Key 及既有 Session／CSRF；結果只含 assignmentId、taskId、execution。結果是操作當時的不可變收據，不代表最新狀態。

## 交易、授權與重送

每次重新驗證正常 DRIVER、目前帳號綁定、目前本人有效指派、OPEN＋ACCEPTED 的 TOM 來源版本及完整未釋放占用；無效或跨人範圍回不洩露資料的 404，管理角色不取得代理司機操作權。沿 UPMS→排序 FMS→TOM→TMS 的鎖序，事件、執行與成功收據同交易。

成功歷史重播先重驗上述目前範圍與內容 hash，再回原收據，不重新套用歷史 expectedVersion、階段或今日政策，也不再寫事件。解除／改綁、失效帳號、撤改後的舊指派不可取得收據；不能因早先合法操作而沿用快取授權。新命令的處理中 key 不排隊重作。失敗不記成功收據，所有資料庫失敗整筆回滾。

新出發只在目前及原指派的政策模式皆為 `SYNTHETIC_TEST_ONLY` 時開放，沿用既有明確外掛與實查 `xk_logistics_e2e` 的測試隔離。正常服務仍 `UNCONFIRMED`，拒絕新出發與原有新派工／改派；不新增正式批准旗標、ADM 參數、車種／駕照／工時等未核准判準。

## 舊派工共存

新撤派／改派在既有鎖與目前指派檢查後、任何資源變動前檢查執行狀態；已出發拒絕，沿舊 TMS 契約 `E1603/INVALID_STATE`。舊成功管理命令重播順序保留，不能在歷史收據前插入新的狀態守衛。

出發與撤改以共同鎖序序列化：撤改先贏則舊指派詳情／出發 404、沒有執行資料；出發先贏則新撤改失敗且占用保留。出發不釋放占用、不增加派工 generation、不解除前置依賴，不假造完成或交接。

V10 新增 `tms_execution`、`tms_execution_event`、`tms_execution_command` 三表，另對既有 assignment 的 id／task／driver 身分配對新增唯一約束；事件與成功收據追加且不可覆寫，不回填任何出發、人員、時間或執行紀錄。V1–V9 與原契約位元組保持不變，升級保留既有 26 張領域表與 Flyway 原有列。完成／釋放日後須處理舊 ASSIGNED 與占用 schema 的相容性，不能用 REVOKED／SUPERSEDED 冒充完成；本包不預作此變更。
