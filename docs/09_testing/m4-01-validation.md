# LG-M4-01 本人詳情與出發驗證

狀態：已合併且 PR／main 四項 CI 全過；最終 source／PR／merge／四項 PR 及 main CI 依[本包唯一工作分支交付收據](https://github.com/xk2022/xk-app-logistics/pulls?q=is%3Apr+head%3Afeat%2Flg-m4-01-driver-departure+base%3Amain)記錄，不以歷史通過替代本包執行。

## 前置與規範

規格 [PR #22](https://github.com/xk2022/xk-app-logistics/pull/22)，source `53e308ac40911b7b0507376595b56cd0d0ea3b55`，merge `6e83663eac50256592e35713467d496cc5765235`；[PR CI 45759432687](https://github.com/xk2022/xk-app-logistics/actions/runs/45759432687)及[main CI 45760194712](https://github.com/xk2022/xk-app-logistics/actions/runs/45760194712)均 attempt 1、四項成功後才開本包。規格審查十份 Markdown／145 個相對檔案連結有效，只有文件變更。

開工時核對公司 local／origin main `e0229b97acb2bf6c2d83f8155665f68b11c27d71`；已讀 README、AGENTS、docs 索引、M4 handoff、Q41–Q47 與公司開發／測試／API／工作流程／安全／部署／維運規範。[本包工程採用](../98_decisions/2026-11-26-m4-01-engineering.md)先凍結精確新契約，舊契約與 V1–V9 保留。

## 實際執行

| 項目 | 結果 |
| --- | --- |
| Frontend lint／typecheck／test／build | 本包全過；26 files／590 tests、73 modules；98 個前端來源／設定檔 hash 前後一致。單 JS 513.87 kB／gzip 144.32 kB 的預設 500 kB advisory 保留，效能預算仍未核准 |
| Maven clean verify／PostgreSQL | 最終 Java 21／Maven 3.9.16 strict-checksums clean verify：131 unit＋287 integration＝418 全過，0 failure／error／skip，6 分 48 秒；174 個後端輸入 hash 前後一致，全部舊 M1～M3 案例保留 |
| 獨立執行契約與原五份實際 HTTP 契約 | 全過：TOM 446／14 files、Acceptance 44／6、Vehicle 174／2、Driver 282／2、TMS 386／6、Execution 157／2；共 1,489／32 files。新靜態契約 2 ops／22 schemas、32 正／111 反／8 引用防護／4 原來源 hashes 全過，17 CLI 拒絕案例通過 |
| 空庫／重啟／V9→V10 舊資料保留 | focused Platform 11 全過，含 V9→V10 26 舊領域表／原 Flyway 列指紋與 V8→V9 舊驗收後明確升 V10；隔離 V1–V10／30 表空庫、零業務種子與重啟一致性已過 |
| 真 TLS desktop／Pixel 7 E2E | 最終 30／30 全過，12 files、2 workers、0 retries、2.4 分鐘；原 28 保留。前兩輪 fixture 修正與失敗紀錄在下方，未放寬原 API |
| npm audit／SBOM／OSV／gitleaks／報告隱私 | root／frontend npm audit 均 0；新 CycloneDX 1.6／144 components；OSV all-vulns 四來源 409 套件無已知弱點；80 憑證報告隱私通過、無 fixture 密碼／trace；staged 及完整 history gitleaks 結果由交付收據記錄，不新增排除 |
| actionlint／E2E strict TS／文件連結 | 全過；90 份 Markdown／506 個相對檔案連結有效；E2E strict＋noUnused，discovery 30 tests／12 files（不等於 runtime 通過） |
| 最終 source／PR／merge／PR 及 main CI | 以[唯一 head 交付 PR](https://github.com/xk2022/xk-app-logistics/pulls?q=is%3Apr+head%3Afeat%2Flg-m4-01-driver-departure+base%3Amain)的完整 source／merge SHA、run／attempt／四項結果為準；PR 四過才合併，再核對 main 四過與相同 tree |

## 邊界

正式派工及新出發模式保持 UNCONFIRMED 拒絕，正向僅現有明確外掛、實查獨立 DB 名稱的合成政策。未核准照片／釋放／結案／保存／現場例外不因本包改為允許；沒有提前 M4-02 功能、M5 或部署。

## 交叉審查修正

新增明確的目前 HttpSession 證明，在 canonical lock 內驗證本人請求；登出也在同鎖下 flush audit 並失效原 Session，再回原匿名 CSRF／雙 Cookie。避免請求先過 Filter／CSRF、後排隊時沿用已登出的 principal；不撤銷其他登入、不增加全帳號 authVersion。新增路徑 matcher 僅兩個精確形狀，保留舊本人列表非 GET 權限矩陣；新詳情明確拒絕 HEAD。相關真 HTTPS／鎖競爭在 focused ExecutionIT 39 及已完成的完整 run 均通過；本包最後完整 clean verify 418 也已全過。

Session invalidation 無法隨 DB rollback 復原；若 audit 已 flush、Session 已失效後才遇極少見 deferred commit 失敗，logout 可能回錯誤而原 Session 仍已結束，採安全拒絕，不恢復舊認證。

## 首輪驗證與修正歷史

首輪 focused 14 unit 全過、92 integration 中 90 通過；只有 ExecutionDefaultPolicyIT 兩個模式案例的 ADM 前置建立回 400，原因是測試 UUID 截斷後含連字號，不符合既有字典代碼規則。修正僅移除 fixture 連字號後取十位大寫 hex，保留兩模式及全部拒絕／零異動斷言；新 ExecutionIT 39、Platform 11 與原 TMS 40 個 integration 首輪均通過。後續完整 run 與修正另列，最終 418 全過已實際核對，未把首輪失敗記為成功。

隔離前端 Docker 建置曾遇雲端代理 SELF_SIGNED_CERT_IN_CHAIN；以私有 BuildKit 掛載主機信任 CA 補足 Node 建置信任鏈，沒有關閉 TLS 驗證或改依賴／提交的 Dockerfile。後端映像已由凍結的產品來源建置；fixture 修正不改產品來源，runtime 真 TLS 的實際執行與 fixture 修正歷史另列。

第一輪完整 clean verify 曾因 root 並行啟動隔離 Compose 與 Testcontainers、VFS 儲存達 32 GB 上限而失敗：FmsVehicleIT 的 22 個 error 及後續 context error 明確為 PostgreSQL／Docker `No space left on device`。已停止該失敗 run，保存原始紀錄，清除本包零資料隔離 stack／volume／私密檔、可重建 cache 與未使用的舊 PG17.10 測試映像；一般 DEV 與資料保留。程式不變，改成完整 Maven 結束後再啟 Compose，必須重新全量驗證，不將環境失敗當成功。

空間修復後完整 clean verify 跑完 131 unit＋287 integration：只有 FmsVehicleIT 的既有架構案例仍預期最新 27 表而實際為 30，其他 417 全過、0 error／skip。修正只將該 latest schema 預期精確更新 30；原 23 個案例、禁止跨模組 repository 斷言與歷史 migration 斷言全部保留。後續完成本包真後端 E2E，再於同一凍結產品完成最終 clean verify，418 全過（6 分 48 秒）。

首輪瀏覽器 28 過／2 新案例失敗（2 workers、0 retries、2.3 分鐘），兩平台皆因測試把 TOM 原輸入 cbm=0.00 當作輸出，未遵守既有 BigDecimal 正規化尾零的行為。M4 讀回 0 與已受理／指派來源一致，產品不變；測試改為精確 canonical 零及完整 item 對照既有指派來源。首輪 80 憑證報告隱私通過後才清理重建隔離環境，重新全量 E2E，原失敗紀錄保留。

第二輪瀏覽器仍 28 過／2 新案例失敗（2.4 分鐘），已通過出發／歷史重播／HEAD 等檢查後，下一筆 fixture 以空字串聯絡人名稱建立 TOM 訂單，被既有 TF10/FORMAT 規則正確拒絕。僅將正向 fixture 與三處預期改為有效、保留 NFD 與首尾空白的合成文字；null／全 null 聯絡物件及其餘斷言保留，後端完全不變。第二輪 80 憑證隱私檢查通過後重建資料庫再驗。

交付前重新核對公司最新 `9213a3d8dacb8774d87c26cb736c4a4315779e95`；相對開工 `e0229b9`，核心工程規範只有既有歷史連結路徑修正，安全、API、測試、部署與維運規則未改。保留開工版本歷史。

## 最終瀏覽器與隔離證據

修正後完整 30 E2E／六份 HTTP 共 1,489 筆全過，80 憑證報告隱私通過後才清除本包隔離容器、volume、私密 env／fixture／overlay。保存 32 份 body-only JSON 與十張 desktop／Pixel 7 調度及本人列表原始 PNG；M4-01 沒有新增操作 UI，完整手機操作由 M4-06 交付。

映像 backend `sha256:625b34c5561b6f824ea3a3039bf542f31ae5eb76c956ac1a68d60c49fd437570`、frontend `sha256:60ef287132b007ccc7094125eba1b8c551aa623aad87a2db8188ebd9956ed056`；71 個前端產品／建置檔逐 byte 同源，後端 209 個編譯 class 與本機輸出逐 byte 相同。production JAR 十份 migration bytes 完全相同，沒有 support／test policy／launcher／fixture；隔離只掛四個白名單 test class。全部 fixture 修正只涉及測試資料／預期值，141 個後端 main 輸入及 98 個前端來源／設定檔不變。

## M4-01 驗收追溯

證據來源：[ExecutionIT](../../backend/src/test/java/com/xksystem/logistics/tms/ExecutionIT.java)（39 例）、[ExecutionDefaultPolicyIT](../../backend/src/test/java/com/xksystem/logistics/tms/ExecutionDefaultPolicyIT.java)（兩種 recordedMode）、[PlatformIT](../../backend/src/test/java/com/xksystem/logistics/PlatformIT.java)（保留原 10 例並新增 V9→V10，共 11 例）及 [execution.spec.ts](../../tests/e2e/execution.spec.ts)。下表定位實際案例；最終完整 Maven／E2E／CI 結果以本頁執行紀錄為準，不以案例存在代替通過。

| 驗收面向 | 具體案例 | 核對內容 |
| --- | --- | --- |
| 本人最小資料／GET 無異動 | ExecutionIT `detailIsMinimalCurrentProjectionAndWaitingReadDoesNotInventExecutionFacts`、`anotherPersonsOrMissingAssignmentIsOpaqueForBothDetailAndCommand`、`successfulReceiptRequiresCurrentBindingAndCannotFollowAccountToAnotherDriver` | 舊列表十四欄、新詳情十五欄；保留 NUL／NFD／精度／原時間與 contact 語意；WAITING 不寫入，跨人／失效綁定不洩露詳情或收據。 |
| 狀態／獨立版本 | ExecutionIT `departureCreatesOneIndependentServerAuditedTransitionAndNoDispatchOrSourceMutation`（IMPORT／EXPORT／LOCAL）、`mathematicalZeroIntegersShareCanonicalRequestIdentity`、`competingNewDepartureKeysShareAssignmentLockAndOnlyOneVersionWins` | 只建立 DEPARTED/version1，伺服器時間及操作者可核對；數學整數等價，多 key 競爭僅一次成功，不改原派工／來源。 |
| 重送與歷史收據 | ExecutionIT `sameKeyProcessingIsBoundedBeforeCanonicalWriteQueueAndOnlyOneReceiptCommits`、`originalReceiptSurvivesCurrentDepartedVersionAndPolicyButNewCommandsDoNotRepeatTransition`、`originalSuccessfulManagerReceiptStillReplaysAfterItsCurrentDriverDeparts`（ASSIGN／REASSIGN） | 同 key 有界處理、hash 衝突與原結果重播；已出發／政策改變不重做；原 M3 管理收據仍可合法重播，不重新占用或產生事件。 |
| 角色／嚴格輸入／預設拒絕 | ExecutionIT `onlyNormalCurrentDriverCanReadOrDepartAndSecurityUsesCompanyErrorShape`、`strictShapeRawUtf8QueryHeadersAndProtocolRefuseWithoutAnyExecutionWrites`；ExecutionDefaultPolicyIT `ordinaryStartupAllowsOwnedDetailButRefusesNewDepartureRegardlessOfRecordedAssignmentMode`（SYNTHETIC_TEST_ONLY／UNCONFIRMED） | 管理角色無代理權，Session／CSRF、UUID、JSON／UTF-8、query、header 與方法／媒體限制；普通應用只讀本人詳情，兩種歷史模式的新出發均 E1703 且零異動。 |
| 撤改／目前來源／完整占用 | ExecutionIT `departedTransactionWinsAgainstNewManagerWithdrawalWithoutReleasingOrExchangingResources`、`managerWithdrawalWinsAndOldDriverPathNeverRetargetsReplacement`（各 revoke／reassign）、`invalidCurrentSourceOrCompleteOccupationIsOpaqueEvenBeforeHistoricalReplay` | 真 PostgreSQL 雙向競爭：出發先則 E1603 且占用保留；撤改先則舊路徑 404、不跟隨新派工；來源版本／關閉／缺占用均在收據前拒絕。 |
| 整筆回滾／DB 保護 | ExecutionIT `storageFailureAtAnyInsertRollsBackEntireDepartureAndTheOriginalKeyCanRetry`（三表）、`commitTimeDeferredForeignKeyUsesExecutionNamespaceAndRollsBackReceiptAndTransition`（兩表）、`committedExecutionSnapshotEventsAndCommandReceiptCannotBeRewrittenOrDeleted`、`databaseRejectsInventedInitialProgressWrongTransitionAndCrossAssignmentReceiptIdentity` | 各 insert 與 commit-time FK 失敗整筆回滾、原 key 可重試；出發事實／事件／收據不可覆寫或刪除，拒絕虛構初態、非法轉移及跨派工身分配對。 |
| 排隊撤權／登出先後 | ExecutionIT `queuedDepartureRechecksFreshActorAndBindingUnderTheSharedCanonicalLock`、`logoutAheadOfQueuedDriverOperationInvalidatesTheCapturedSessionBeforeAnyDetailOrReceipt`（DETAIL／DEPART／REPLAY）、`departureAheadOfLogoutCompletesOnceBeforeThatSessionIsInvalidated` | 真 DB gate 與獨立 lock observer 證明順序；canonical 內重驗身分、綁定及原 Session。登出先則 401／無收據與異動；出發先則恰一提交，隨後原 Session 失效。 |
| 升級／真 TLS 桌面與手機 | PlatformIT `initializesEmptyPostgresWithPlatformUpmsAdmCrmAndNoSeedAccountsCustomersOrParameters`、`upgradesV8ToV9PreservingEveryPriorDomainRowAndIndexingOnlyOpenAcceptedTasks`、`upgradesV9ToV10PreservingEveryExistingRowIncludingActiveDispatchHistoryWithoutExecutionBackfill`；execution.spec.ts `current DRIVER detail and departure preserve authority, version, replay and dispatch race safety over TLS`（desktop／Pixel 7） | V10／30 表空庫、新三表零回填、26 舊領域表及 Flyway 原列保留；原 V8→V9 證明完成後明驗 runtime 升 V10。真瀏覽器同源 API 驗本人權限、HEAD 405、回應失落後原請求重播／CSRF、撤改競爭及十四欄列表；全量 E2E 的六份實際 HTTP 驗證結果另列本頁。本包沒有新增執行 UI 或 M4-02 以後功能。 |

## 最終交付收據（M4-02 開工前核對）

[PR #23](https://github.com/xk2022/xk-app-logistics/pull/23)，source `71dac55cdf568ddb6f46c043c0f74d81d3fafe07`，merge `daf26f979c1c03c078876f204a713578abe80a4e`，兩者 Git tree 相同。[PR CI 45768556588](https://github.com/xk2022/xk-app-logistics/actions/runs/45768556588)／[main CI 45769255127](https://github.com/xk2022/xk-app-logistics/actions/runs/45769255127) 均 attempt 1、四項成功；遠端各 131 unit＋287 integration、30 E2E、1,490 HTTP／32 檔（execution 158；本機 157），0 測試失敗／略過。採集數量依該次競爭回應紀錄，沒有把本機數量冒充 CI；其餘五份 HTTP 數量一致。main 精確 head 核對完成才建立 M4-02 分支。
