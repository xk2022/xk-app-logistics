# M3 最終整合驗收報告｜LG-M3-06

**M3-01～06 工程實作與本機整合驗收完成。** 本包重跑前端 590、後端 369、真 HTTPS 桌面／手機 E2E 28 與五份實際契約 1,244 筆回應，全部通過。最終 source、PR CI、merge 與合併後 main CI 以[唯一工作分支交付收據](https://github.com/xk2022/xk-app-logistics/pulls?q=is%3Apr+head%3Atest%2Flg-m3-06-integration+base%3Amain)的實際 PR／Checks 為準；四項 PR CI 成功後才合併，再核對 main 四項成功。下列本機結果不替代遠端交付條件。

本包基礎 main 為 `7acbd43f5e098a877af6a1458e5b32d81e390f41`，工作分支 `test/lg-m3-06-integration`。依 [AGENTS](../../AGENTS.md)、[M3-06 handoff](../08_development/m3-06-integration-handoff.md)、[Q36–Q40](../98_decisions/2026-11-26-tms-dispatch.md)及[TMS 工程決策](../98_decisions/2026-11-26-tms-dispatch-engineering.md)，只補整合驗收與所見缺陷，不新增未核准業務規則。

M3-06 開工公司規範固定為 `xk2022/xplore-kaleidoscope@e0229b97acb2bf6c2d83f8155665f68b11c27d71`，採用新索引下的 [APP 測試規範](https://github.com/xk2022/xplore-kaleidoscope/blob/e0229b97acb2bf6c2d83f8155665f68b11c27d71/06_engineering/app-testing-standard.md)、[開發流程](https://github.com/xk2022/xplore-kaleidoscope/blob/e0229b97acb2bf6c2d83f8155665f68b11c27d71/06_engineering/development-workflow.md)及[維運規範](https://github.com/xk2022/xplore-kaleidoscope/blob/e0229b97acb2bf6c2d83f8155665f68b11c27d71/07_operations/app-operations-standard.md)。M3-05 的 `69b3df5` 基準與後續 `d03cd67` 查核保留於該包紀錄；本次至 `e0229b9` 為公司知識工具／索引更新，APP 工程、安全與維運核心要求未變，不回寫歷史基準。

## 各階段交付追蹤

下表為已核對的歷史交付。source 是該 PR 最終 head；merge 是 main 上的合併提交。所列成功 run 均已核對相應 head 與四項工作：Frontend quality、Backend verify、Compose and browser smoke、Dependency and secret checks。M3-06 是本包重新執行的結果，並非沿用前包數字。

| 工作包 | PR／source SHA | main merge SHA | PR CI／合併後 main CI | 該包本機驗收摘要 |
| --- | --- | --- | --- | --- |
| M3-01 FMS Vehicle | [#12](https://github.com/xk2022/xk-app-logistics/pull/12)；`cdc463e1585710bafb6ee0153ee1fb803a53f900` | `714ed42b3aad3397325d83205f1ce9ada9d9fad5` | [45641861002](https://github.com/xk2022/xk-app-logistics/actions/runs/45641861002) attempt 1；[45642737677](https://github.com/xk2022/xk-app-logistics/actions/runs/45642737677) attempt 1，均四項成功 | 前端 238、後端 243、E2E 20；[M3-01 紀錄](m3-01-validation.md) |
| M3-02 FMS Driver 產品 | [#14](https://github.com/xk2022/xk-app-logistics/pull/14)；`01de1f26cf338502a1ddcb02b0e328bdb4332938` | 產品 merge `f0299c35fdaf45a44993660ea3c169aa978ec76c` | [45650752757](https://github.com/xk2022/xk-app-logistics/actions/runs/45650752757) attempt 1 四項成功；首次產品 main run 的失敗另列於下文 | 前端 330、後端 280、E2E 22；[M3-02 紀錄](m3-02-validation.md) |
| M3-02 文件交付與最終 main | [#15](https://github.com/xk2022/xk-app-logistics/pull/15)；`e2a1b9fee6e8e104a10867a629b001ecc7ee691d` | 最終 merge `dd0df19b22d5a7678a228946e9705ee4f303f728` | [45652969933](https://github.com/xk2022/xk-app-logistics/actions/runs/45652969933) attempt 1；[45654062071](https://github.com/xk2022/xk-app-logistics/actions/runs/45654062071) attempt 1，均四項成功 | 另立文件交付，保留產品與前次遠端失敗歷史，不把 #15 誤稱為產品 PR |
| M3-03 TOM Acceptance | [#17](https://github.com/xk2022/xk-app-logistics/pull/17)；`f8837b3408b868c2cf94c5f14978c67bef472fdb` | `0bf51129ba8e019a247b5d0f16ef2f23b3bd3c0d` | [45733060990](https://github.com/xk2022/xk-app-logistics/actions/runs/45733060990) attempt 1；[45733649573](https://github.com/xk2022/xk-app-logistics/actions/runs/45733649573) attempt 1，均四項成功 | 前端 426、後端 312、E2E 24；[M3-03 紀錄](m3-03-validation.md) |
| M3-04 TMS 派工／占用 | [#19](https://github.com/xk2022/xk-app-logistics/pull/19)；`2d35b7fbcf2846ff01f603c4fe6fbe0c19eb7247` | `7180c1f8894baf0d6b878c435ce879863817f2ed` | [45744058200](https://github.com/xk2022/xk-app-logistics/actions/runs/45744058200) attempt 1；[45744753604](https://github.com/xk2022/xk-app-logistics/actions/runs/45744753604) attempt 2，均四項成功 | 前端 426、後端 369、E2E 26；[M3-04 紀錄](m3-04-validation.md) |
| M3-05 TMS 調度工作台 | [#20](https://github.com/xk2022/xk-app-logistics/pull/20)；最終 source `997950eb1ee80982bc455e29e8a8d7ecb52cdcbd` | `7acbd43f5e098a877af6a1458e5b32d81e390f41` | [45751323496](https://github.com/xk2022/xk-app-logistics/actions/runs/45751323496) attempt 2；[45752348554](https://github.com/xk2022/xk-app-logistics/actions/runs/45752348554) attempt 1，均四項成功 | 前端 590、後端 369、E2E 28；[M3-05 紀錄](m3-05-validation.md) |
| M3-06 最終整合驗收 | [交付 PR／最終 head](https://github.com/xk2022/xk-app-logistics/pulls?q=is%3Apr+head%3Atest%2Flg-m3-06-integration+base%3Amain) | 同 PR 的 merge SHA 收據 | 同 PR 記錄四項 PR CI／merge 後 main CI 及實際 head；不猜自身 SHA | 本包前端 590、後端 369、E2E 28、實際 HTTP 1,244 全過；[本包紀錄](m3-06-validation.md) |

剩餘 M3 的規格前置為 [PR #18](https://github.com/xk2022/xk-app-logistics/pull/18)：source `f796287f08c185c5d19b71b09e1df49075741fec`，15 份文件／138 個相對連結驗收，原契約與 V1–V8 不變。[45734934106](https://github.com/xk2022/xk-app-logistics/actions/runs/45734934106) 首輪 backend 在測試前下載官方 Central Maven plugin 遭 429，其餘三項成功；只重跑失敗工作，attempt 2 四項成功後合併 `5bb6aa82bde4e98de781ed9dbb9844df4e672b8f`。[main 45735908231](https://github.com/xk2022/xk-app-logistics/actions/runs/45735908231) attempt 1 四項成功，再依 04→05→06 逐包推進。

## 遠端失敗與修正歷史

M3-02 產品合併後 [main 45651314921](https://github.com/xk2022/xk-app-logistics/actions/runs/45651314921) 的其他三項成功；backend 在測試前下載 Spring Boot parent POM 遭官方 Maven Central alternate 回 429。單一失敗工作、單一 job 重跑與 workflow_dispatch 入口均回內部 500，實際 run 仍 attempt 1。#15 以正常交付重新觸發完整 CI，最終 PR／main 成功；原 429 與重跑介面失敗保留，不改記為已成功重跑。

M3-05 初始 source `719228cbb738d7c63821da114557e4bd7f559878` 的 [PR CI 45749066236](https://github.com/xk2022/xk-app-logistics/actions/runs/45749066236) 三項成功，backend 的既有 `UpmsIT.lastEnabledAdministratorCannotBeDisabledOrDemotedEvenWithConcurrentRequests` 回 `[200,403]`，未通過原 200／401／409 狀態集合。成功停用後撤銷 Session，較慢請求若尚未通過 CSRF，可合法回 403；沒有更改此產品行為，也未擴大測試允許集合。

修正以 PostgreSQL `upms_lock` gate 及獨立 autocommit observer，確認兩個寫入交易均已通過 auth／CSRF 並等待 canonical lock，再釋放競爭；保留原 17 個案例、恰一成功／最後管理員，以及版本、目前 Session、唯一 ACCOUNT_UPDATE 審計等斷言。最終 source 為上表 `997950eb…`。其 [45751323496](https://github.com/xk2022/xk-app-logistics/actions/runs/45751323496) attempt 1 又在測試前遇官方 Central plugin 429，其餘三項成功；同 head 只重跑失敗工作，attempt 2 四項成功，合併後 main attempt 1 四項成功。兩種失敗原因分別記錄，沒有以盲目重跑掩蓋測試缺陷。

## M3-A01～A08 追溯矩陣

來源索引：[TmsIT](../../backend/src/test/java/com/xksystem/logistics/tms/TmsIT.java)（既有 39 例）、[TmsDefaultPolicyIT](../../backend/src/test/java/com/xksystem/logistics/tms/TmsDefaultPolicyIT.java)（1 例）、[TomAcceptanceIT](../../backend/src/test/java/com/xksystem/logistics/tom/TomAcceptanceIT.java)（26 例）、[PlatformIT](../../backend/src/test/java/com/xksystem/logistics/PlatformIT.java)（10 例）。本包全量 Maven verify 實際重跑上述案例，數量均保留；新增升級後 HTTP 斷言延伸既有 PlatformIT，不增加鏡像案例或虛增計數。

真 UI 場景為 [dispatch-ui.spec.ts](../../tests/e2e/dispatch-ui.spec.ts) 的 `Dispatch UI joins accepted orders, explicit resource commands, replay and current-driver mobile privacy over TLS`，desktop／Pixel 7 各一例；以下簡稱 UI 場景。原 [dispatch.spec.ts](../../tests/e2e/dispatch.spec.ts) 為獨立 API 場景，不能用它代替工作台畫面驗收。兩者帳號群獨立，所有業務資料經真實 API 建立。

| ID | 驗收重點與既有具體證據 | M3-06 最終狀態 |
| --- | --- | --- |
| M3-A01 | 草稿→受理→首段派工：TmsIT `acceptedOriginalOrderGraphsDispatchOnlyTheirReadyFirstSegmentWithoutRewritingTom`（三類）、`draftMissingAndIneligibleSourceCannotCreateAnAssignmentOrAcceptedIdentityIndex`、`acceptedTaskIndexHasHonestBoundedPagesLiteralSearchAndDependencyBlockers`；AcceptanceIT `allThreeOriginalCompleteGraphsAcceptExactlyOnceWithImmutableServerEventAndRevision`。UI 場景實際從訂單 UI 受理轉入 TMS 指派，司機頁看到本人任務。 | 通過：後端三類及受理整合；desktop／Pixel 7 真 UI |
| M3-A02 | 真 PostgreSQL 競爭：TmsIT `competingAssignmentsNeverShareTaskDriverOrVehicleAcrossPositions` 五類競爭涵蓋同任務／司機／主車／拖板／跨位置；`databaseConstraintsEnforceSameOrderExclusiveVehicleNamespaceAndImmutableHistory` 驗 DB 約束。UI 場景另驗已占用候選仍列出且不可選；UI 的順序操作不冒稱並發證據。 | 通過：五類真 PostgreSQL 競爭與 DB 約束；UI 占用顯示 |
| M3-A03 | 原子撤改與留史：TmsIT `revokeAndReassignAtomicallyReleaseResourcesKeepHistoryAndFollowCurrentDriverBinding`、`reassignAcquisitionFailureRestoresReleasedOldReservationsAndTerminalTransition`。UI 場景由另一管理角色改派、明確撤派，核對舊 SUPERSEDED／REVOKED、原因與釋放占用，歷史七筆以 5＋2 真分頁。 | 通過：原子撤改／失敗回滾及兩 viewport 真 UI |
| M3-A04 | 重送／版本／回滾：TmsIT `inProgressSameKeyCannotQueueBehindCanonicalOrCreateAnotherResult`、`allThreeCommandReplaysKeepHistoricalDataWithoutReoccupyingOrAddingEvents`、`everyAssignmentEventReservationAndReplayFailureRollsBackTheWholeCommandAndAllowsSameKeyRetry`、`realForeignKeyFailureUsesProtectedRelationAndRollsBackGenerationAssignmentReservationsEventAndReplay`、`acceptedIdentitySinkFailureRollsBackTheOriginalAcceptanceAndItsSuccessfulReplayDoesNotReseed`。UI 場景三命令各真提交後失落回應，以原 key／body／generation 明確重播；首次 assign 重試先遭真 CSRF 403 仍凍結；stale409 保留原因，明確重新讀取採用才續作。 | 通過：全部故障點與重播整合；三命令未知結果／CSRF／409 真 UI |
| M3-A05 | 行政來源／資格：TmsIT `administrativeSourceChangeWinningCanonicalLockPreventsTheQueuedDispatch`、`dispatchWinningCanonicalLockKeepsItsSourcesAvailableAndRejectsTheQueuedDisqualification` 各三種競爭，以及 `occupiedQualificationFieldsAreProtectedWhileUnchangedValuesRetainOriginalFmsSemantics`。DefaultPolicyIT `ordinaryApplicationRejectsSyntheticLookingResourcesWithoutExplicitTestPolicyImport` 證明正常應用即使資源像合成資料仍拒絕。UI 場景驗未核准候選與前置阻擋；元件驗 production 新派工禁用、合法撤派不受今日來源阻擋。 | 通過：雙向來源競爭、預設政策拒絕；正式政策仍未核准 |
| M3-A06 | 角色／本人隔離：TmsIT `sevenRoutesEnforceNormalRolesCsrfAndExactActorDerivedSelfBoundary`、`freshBindingNotCopiedAccountIdentityControlsCurrentSelfVisibility`、`bindingChangeAndCurrentSelfReadSerializeInBothDirections`、`queuedManagerRevocationAndHistoricalReplayBothRequireFreshAuthorization`。UI 場景驗撤改後舊本人資料移除、404 解綁／改綁、401 撤權，以及真 logout 回應尚待交付時 DOM 已卸載。 | 通過：角色與 fresh binding 整合、兩 viewport 與元件晚回應隔離 |
| M3-A07 | desktop／mobile 操作、空／載入／錯誤／unknown：UI 場景驗六段任務 5＋1、歷史 5＋2、blocked、409、403、明確重試、鍵盤／labels／overflow並保存當前／歷史／本人／確認／unknown 畫面。元件另驗讀取失敗、背景隱去、晚回應、不以歷史收據冒充目前狀態及離開保護。 | 通過：590 前端測試、兩 viewport 真 UI、10 張原始 PNG |
| M3-A08 | 空庫／升級／契約：PlatformIT `initializesEmptyPostgresWithPlatformUpmsAdmCrmAndNoSeedAccountsCustomersOrParameters` 與固定 V1→V9 升級鏈；`upgradesV8ToV9PreservingEveryPriorDomainRowAndIndexingOnlyOpenAcceptedTasks` 保留 21 張舊領域表指紋。本包接續在指紋驗證後，以真 HTTPS 驗舊已受理任務的升級後讀取／首段派工。五份 HTTP validators、品質與隱私／安全檢查須同批重跑。 | 通過：V1–V9／27 表、21 舊表指紋及升級後真 HTTPS；五契約與全回歸 |

與上述 UI 場景互補的具體元件案例如下，避免只依截圖或 mock 宣稱交易通過：

- [Dispatch.test.tsx](../../frontend/src/Dispatch.test.tsx)：`requires a second confirmation and sends exact generation and explicit null trailer`、`retains conflict reason, never adopts actual generation, and cancellation cannot bypass the stale gate`、`keeps historical replay as a receipt and requires a successful explicit fresh read before new actions`、`aborts a historical read started before an unknown command and preserves the original retry`。
- [DispatchHistory.test.tsx](../../frontend/src/DispatchHistory.test.tsx)：`pages through assignment generations 5, 3 and 1 without requiring the current assignment on every page`、`aborts an inflight history read on disabled and ignores its late changed-generation response`、`clears the previous page on a read failure and retries only explicitly`。
- [MyTasks.test.tsx](../../frontend/src/MyTasks.test.tsx)：`hides old route/contact before refreshing and keeps them removed after an empty result`、`removes background data, aborts superseded reads and ignores late results after returning`、`aborts on unmount and never expires a new session from a discarded response`。
- [DispatchNavigation.test.tsx](../../frontend/src/DispatchNavigation.test.tsx)：`disables an existing leave prompt while the same dispatch retry is in flight`、`removes private own-task content when authorization expires`、`unmounts private own tasks immediately during logout and shows the login page on success`。
- [tmsApi.test.ts](../../frontend/src/tmsApi.test.ts)及[tmsModel.test.ts](../../frontend/src/tmsModel.test.ts)：原數字／文字／nullable 語意、七路徑、完整回應與重播驗證；只有具明確理由的 business409 可解除原未知，協定／auth／CSRF／shape／key 錯誤不推定先前未提交。

## 本包補驗與最終執行紀錄

本包不為重複既有行為增加鏡像測試。升級補驗延伸原 PlatformIT 的 V8→V9 案例：先建立完整合成 V8 fixture，取得 21 張舊領域表指紋，再執行 V9 並確認指紋完全不變；V9 只衍生既有 OPEN＋ACCEPTED 任務索引，不捏造派工／占用／事件。後續才以隔離測試用 plain factory／短生命週期 HTTPS context，讀取舊受理任務、指派首段、重播同次結果，核對沒有額外占用／事件及 TOM／CRM 異動。測試命名與隨機帳密於 V8 fixture 階段建立，不在取指紋後修改來源來湊可派組合。此補驗已於 focused 及完整 Maven verify 通過；使用真 Clock，並斷言沒有 MutableTestClock／@TestConfiguration bean。只有四個白名單測試 class 外掛至隔離服務，production JAR 不含測試政策或 fixture。

| 最終品質項目 | M3-06 實際結果 |
| --- | --- |
| 前端 lint／typecheck／全部 Vitest／build | 本包全過；26 files／590 tests、73 modules；單 JS 513.87 kB／gzip 144.32 kB，advisory 保留 |
| Maven verify／PostgreSQL／升級後 HTTP | 本包 124 unit＋245 integration＝369 全過，0 failure／error／skip，5 分 41 秒；另先跑 focused 7 unit＋11 integration 全過（54.342秒），已含升級後真 HTTP |
| 真 HTTPS desktop Chromium／Pixel 7 全量 E2E | 28／28 全過，11 files、2 workers、0 retries、2.2 分鐘；10 張調度／本人任務原始 PNG，已檢視桌面目前派工與手機本人任務 |
| 原 TOM、Acceptance、Vehicle、Driver、TMS 五份 HTTP 驗證 | TOM 424／12 files、Acceptance 32／4、Vehicle 174／2、Driver 282／2、TMS 332／4；共 1,244／24 files；原四來源 SHA-256 相同，TMS 靜態 7 ops／42 schemas／51 正例／113 反例／8 引用防護通過 |
| npm audit／CycloneDX SBOM／OSV all-vulns／gitleaks | root／frontend npm audit 各 0；CycloneDX 1.6 含 144 resolved components；OSV all-vulns 四來源 409 套件無已知弱點；staged 與完整 history gitleaks 結果由交付收據記錄，不新增 scanner 排除 |
| 報告隱私／fixture 隔離／正式 JAR 無測試政策 | 70 個獨立合成憑證、fixture mode 0600；同批報告清除 fixture 前隱私檢查通過，沒有憑證／trace；JAR 九份 migration bytes 相同，沒有測試政策／launcher／fixture／support classes |
| 空庫、重啟、V1–V9／27 表、DEV 資料保留與清理 | PG 17.12 V1–V9／27 表，空庫全業務零資料、重啟前後一致；一般 DEV 原資料與正常 JAR／無測試掛載維持；本次隔離 stack／volume／私有 fixture、env、overlay 於隱私驗證後清除 |
| 文件連結／actionlint／E2E strict TypeScript／diff | actionlint、全部 E2E strict TypeScript、git diff --check 通過；80 份 Markdown／454 個相對檔案連結通過 |
| 最終 source SHA／PR／PR CI | 見[唯一 head 交付 PR](https://github.com/xk2022/xk-app-logistics/pulls?q=is%3Apr+head%3Atest%2Flg-m3-06-integration+base%3Amain)的最終 head、四項 Checks 與完整 source SHA；以本報告的提交內容識別受測版本 |
| merge SHA／main CI／最終 head 核對 | 同[交付 PR 收據](https://github.com/xk2022/xk-app-logistics/pulls?q=is%3Apr+head%3Atest%2Flg-m3-06-integration+base%3Amain)記錄實際 merge／main run／attempt／四項結果，核對 head 及 source／merge 相同 tree；遠端完成前不記成功 |

供比對的 M3-05 歷史基線為前端 590、後端 369＝124 unit＋245 integration、E2E 28（2 workers／0 retries／2.3 分鐘）。同批五份實際 HTTP 為 TOM 424／12 files、Acceptance 32／4、Vehicle 174／2、Driver 282／2、TMS 332／4；共 24 份、1,244 筆 response evidence，70 個合成帳號的報告隱私通過。本包已獨立重跑並取得相同數量，原始回應來自本次全新隔離資料庫及 fixture；前包紀錄仍保留其各自受測 source。重現依 [E2E README](../../tests/e2e/README.md)及[根 README](../../README.md)，原 M2／M3 全部案例與契約持續保留。

M3-05 的 Vite 建置曾提示單一 JS 513.87 kB／gzip 144.32 kB，超過預設 500 kB advisory。提示未被關閉，也未調高門檻；沒有已核准的效能數字門檻，不能將 build 成功當作效能驗收已通過。本包實測為相同 513.87／144.32 kB，後續套件切分與效能預算仍列追蹤。

## 證據與重現

本次原始 PNG 包含 desktop／Pixel 7 的 confirmation、current、history、driver、unknown 各一份；實際 HTTP 保存為 24 份 body-only `{schema,value}` JSON。交付 PR／main 的 `compose-browser-reports` artifact 保留該 run 的 Playwright 報告、test-results 及服務診斷，僅隱私檢查通過才允許保留；`security-reports` 保留掃描結果。本機另存本包 summary、202 產品輸入 hashes、162 後端來源核對結果、初始化／重啟與一般 DEV 保留證據；不把前包畫面冒作本包。

本包的 202 產品／建置輸入 bytes 與 M3-05 完全相同；重用 backend `sha256:597a2373e8c2f8d4aa2a5503ccc8be85788016734f16f2c77b45ead5c0b4206f`、frontend `sha256:03d7f93910d5407970ca26f1cf57b6cb4f58215661177c611a42fa72efa7b134`。本次另逐檔驗證映像內全部 71 個前端來源／建置輸入（包含產品 68 個），測試 launcher 的四個外掛 class 由本次最終 Maven 輸出重新產生。

## 完成定義與剩餘限制

**合成工程驗收通過不等於正式資格核准或正式上線。** M3 完成只涵蓋車輛／司機主檔、訂單正式受理與出發前 ASSIGNED／REVOKED／SUPERSEDED 調度流程；各包 PR、來源、四項 CI、merge 與 main CI 必須逐包可追溯。

正式車種／駕照分類、主車拖板配對、重量、檢驗、工時與現場例外仍未核准。正常 production 的新指派／改派維持 `UNCONFIRMED` 並拒絕；只有明確載入且核對實際 DB 名稱 `xk_logistics_e2e` 的 test-only 政策可回 `SYNTHETIC_TEST_ONLY`，不能由正式環境旗標、ADM 參數或 HTTP 操作批准。行政 AVAILABLE／ACTIVE＋ON_DUTY 不是正式適格承諾。

D04／D05 未決政策及 D10 保存期限不因 M3 交付而視為核准；沒有藉 immutable 歷史擅定永久保存，也沒有自動清理。UPMS 單 instance、儲存／備份還原／RPO-RTO／保存週期、費用、維運責任與覆蓋率數值門檻延續原限制，不能以本機 health 或測試通過替代正式營運準備。

前置任務沒有 M4 完成／交接證據時一律不放行；撤派、經過時間與無占用不是完成證據。沒有出發、司機回報、照片、運輸完成、結案、預約、合載或自動釋放；不提前實作 M4，不使用真實客戶／司機資料，不進行正式部署。M3-06 完成後停在後續規格決議，不自動擴大授權範圍。
