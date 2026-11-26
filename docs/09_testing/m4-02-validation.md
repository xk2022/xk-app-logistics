# LG-M4-02 進度與私有照片驗證

狀態：本機驗收完成；遠端門檻另依[本包唯一工作分支交付收據](https://github.com/xk2022/xk-app-logistics/pulls?q=is%3Apr+head%3Afeat%2Flg-m4-02-progress-evidence+base%3Amain)記錄 source／PR／merge／PR及main四項 CI。前包精確收據見 [M4-01](m4-01-validation.md)；本包從 merge `daf26f979c1c03c078876f204a713578abe80a4e` 開工，先核對公司 `9213a3d8dacb8774d87c26cb736c4a4315779e95` 及 [工程採用](../98_decisions/2026-11-26-m4-02-engineering.md)。

| 驗收 | 實際狀態 |
| --- | --- |
| Frontend lint／typecheck／test／build | 已執行全過，590 tests／26 files、73 modules；98個來源／設定檔凍結。513.87kB／gzip144.32kB 的500kB advisory保留 |
| Backend clean verify／PostgreSQL | 最終完整139 unit＋330 integration＝469全過，0 failure／error／skip，8分27秒；194backend／155main輸入hash相同，全部舊M1～M4-01測試保留 |
| 真 TLS desktop／Pixel7 E2E | 32／32全過、13files、2workers、0retries、2.5分鐘；保留原30，兩個新案例驗進度／私有bytes／照片驗證／原次重試／本人隔離與競爭 |
| 原六份＋新照片進度 HTTP 契約 | 七份靜態全過，新5ops／25schemas、59正／291反／8引用防護／5原來源hash；17個CLI拒絕案例通過。實際TOM452／16files、Acceptance48／8、Vehicle174／2、Driver282／2、TMS400／8、Execution171／4、新Progress-evidence204／2；合計1,731／42files全過 |
| V10→V11、空庫／重啟、映像同源及普通預設拒絕 | Platform12／Default2全過；29舊領域表與Flyway原列保留、新4表零回填；隔離PG17.12空庫34表／V1–V11零業務資料、重啟一致；234後端class／71前端產品建置檔逐byte相同，JAR11份migration相同且無support／testpolicy／fixture |
| npm audit／SBOM／OSV／gitleaks／隱私／工具 | root與frontend audit各0；CycloneDX1.6／144components；OSV四來源409套件無已知弱點；90fixture憑證報告隱私通過、無密碼／trace，之後保存42body-only JSON與56原始PNG。staged／history gitleaks及最終工具結果見交付收據 |
| PR／main四項 CI、source／merge tree | 由本頁頂部唯一head交付收據記錄完整SHA、run／attempt／四項結果；不得以前包或本機結果代替 |

## 邊界

正式派工資格、D06 照片類型／數量／格式／大小及必填階段、D10 保存／清理、正式 provider／權限、現場例外、安全釋放及結案責任仍未核准。合成 PNG／數值／bytea 只驗工程流程，不代表正式選型或真實照片驗收。不修改舊 Repo，不部署、不做 M5；完整司機操作 UI 由 M4-06 交付。

## 重點 PostgreSQL 與 TLS 驗證

凍結194個backend輸入、155個main／pom輸入後，先執行 clean verify 重點套件：15 unit＋95 integration 全過，0 failure／error／skip，3分25秒；包括新 EvidenceIT40、普通default2、Platform12，以及原 ExecutionIT39／default2。這是重點執行結果，隨後同一凍結後端完成完整139＋330＝469全過（8分27秒），不以重點數量替代全量。

## M4-02 驗收追溯

來源：[EvidenceIT](../../backend/src/test/java/com/xksystem/logistics/tms/EvidenceIT.java)（40 例）、[EvidenceDefaultPolicyIT](../../backend/src/test/java/com/xksystem/logistics/tms/EvidenceDefaultPolicyIT.java)（兩種 recordedMode）、[EvidenceTest](../../backend/src/test/java/com/xksystem/logistics/tms/EvidenceTest.java)（8 個單元案例）、[PlatformIT](../../backend/src/test/java/com/xksystem/logistics/PlatformIT.java)（保留原 11 例，新增一例共 12）及 [progress-evidence.spec.ts](../../tests/e2e/progress-evidence.spec.ts)。下表只定位實際測試及驗收範圍；本包實際執行與最終 CI 結果須另依紀錄填寫，案例存在不等於通過。

| 驗收面向 | 具體案例 | 核對界線 |
| --- | --- | --- |
| 進度／證據版本／占用 | EvidenceIT `twoAdjacentProgressTransitionsUseExactVerifiedStageEvidenceWithoutChangingDispatchOrReleasingResources`（IMPORT／EXPORT／LOCAL）、`uploadAndProgressSerializeAtTheExactSourcePhaseWithoutAttachingOldVersionToTheNextStage`（兩方向） | 只允 DEPARTED→IN_PROGRESS→AWAITING_COMPLETION；證據須同階段／execution version，upload 不加執行版本，progress 恰加一版；上傳與進度競爭不錯掛下一階段，不改派工、占用、來源或解除依賴。 |
| 本人隔離／私有 binary | EvidenceIT `metadataPaginationAndPrivateBytesArePureFreshReadsWithNoPublicStorageIdentity`、`currentReboundDriverMayUseHistoricalUploaderEvidenceButOldActorCannotReadOrReplay`、`managementAndForeignDriverCannotReadMetadataPrivateBytesOrDriveAnyOfTheFiveOperations` | GET 無寫入、metadata 真分頁；管理角色無代理權。依目前 driver／assignment 授權，不以歷史 uploader actor 當所有權；bytes／長度／SHA 與 private/no-store／nosniff／無公開 URL、Range／304 捷徑以 runtime 對照，binary 不放入 JSON 回應蒐集。 |
| Fresh Session／來源先於收據 | EvidenceIT `logoutAheadOfQueuedEvidenceOperationInvalidatesCapturedSessionBeforeDataReceiptOrBytes`（POLICY／PROGRESS／UPLOAD／LIST／CONTENT）、`staleCurrentProofPrecedesEveryStoredUploadOrProgressReceipt`（SOURCE／OCCUPATION） | 真 DB gate 證明排隊後重新驗原 Session；登出、來源或完整占用失效先拒絕，不能取得 metadata、歷史 receipt 或私有 bytes，且不重定向新指派。 |
| 原次重播／canonical 身分 | EvidenceIT `originalUploadAndProgressReceiptsReplayAfterLaterPhaseAndCurrentPolicyProviderAreUnavailable`、`evidenceIdentitySetIsCanonicalButDuplicatesAreRejectedAndNewKeysDoNotContentDeduplicate`、`sameKeyInFlightIsBoundedBeforeCanonicalQueueAndCommitsOnlyOneBusinessResult`（UPLOAD／PROGRESS）；EvidenceTest `uploadHeaderVersionHasStrictTextGrammarAndHashContainsOnlyRequestedFacts` | 合法目前範圍確認後回原收據，先於今日 phase／version／policy/provider；hash 不含 server-derived stage。UUID 大小寫／順序正規為集合，語意重複拒絕；不同 key 不默默覆寫或內容去重，同 key 不重存或重做進度。 |
| 串流／PNG／multipart 前置解析 | EvidenceIT `strictProgressJsonAndRawUploadMetadataCannotSelectIdentityPhaseRequirementsOrProvider`、`actualPngSignatureCrcDecodeDimensionsAndByteRulesRejectWithoutLeavingObjectsOrReceipts`；EvidenceTest `actualStreamingCapIsEnforcedBeforeMaterializingTheRestOfAChunkedBody`、`rawUploadCsrfNeverParsesBodyParametersAndOtherRoutesKeepTheirExistingResolver`、`pngChecksCrcTruncationTrailingDataAndPixelCountBeforeDecoding` | 實際固定長度／chunked bytes 上限、CRC／格式／解碼／像素及 policy byte cap；multipart（完整／破損）與 form 在有效 CSRF 下 415，缺 CSRF 為已登入 403／匿名 401。新 raw 路徑不因 CSRF fallback 或參數解析先吃 body，舊路徑保留原 resolver。 |
| 獨立策略／provider 預設拒絕 | EvidenceDefaultPolicyIT `ordinaryRuntimeDoesNotEnablePhotoPolicyOrPrivateProviderFromTestClassesOrStoredSyntheticHistory`（SYNTHETIC_TEST_ONLY／UNCONFIRMED）；EvidenceIT `eachUnconfirmedCurrentDependencyIndependentlyRefusesNewWritesWithoutPretendingPhotosAreOptional`（POLICY／PROVIDER／DISPATCH） | 普通啟動不因 test classpath、DB 名稱或歷史合成模式開放。各依賴獨立拒絕；未知需求不是空清單 waiver。正向 PNG／數量／大小／像素規則僅為隔離合成工程值，未核准 D06、D10 或正式 provider。 |
| 全交易回滾／FK／不可變 | EvidenceIT `eachUploadStoreFailureRollsBackBlobMetadataAndReceiptAndOriginalKeyCanRetry`（三表）、`eachProgressWriteFailureKeepsOriginalPhotoAndEntirePriorExecutionUnchanged`（四寫入點）、`deferredUploaderForeignKeyFailureUsesEvidenceErrorNamespaceAndLeavesNoOrphanBytes`（兩表）、`transientProviderFailureIsSanitizedAndStoredPhotosEventsLinksAndReceiptsAreImmutable`、`databaseRejectsWrongContentDigestOrphanPrivateBytesAndCrossStageEvidenceAssociation` | 同 caller PostgreSQL 交易內，blob／metadata／receipt 或 progress／event／links 任一步失敗全部回滾、原 key 可重試；FK 用 E1806，provider／內部失敗只回安全摘要。實際 SHA、反向 deferred FK、同 assignment／phase／version 關聯與 UPDATE／DELETE 保護，不宣稱外部 storage 具相同原子性。 |
| V10→V11／真 TLS 全流程 | PlatformIT `upgradesV10ToV11PreservingAllPriorRowsAndDepartureReceiptsWithoutInventingEvidence`；progress-evidence.spec.ts `controlled progress and private immutable PNG evidence preserve current authority, receipts and occupancy over TLS`（desktop／Pixel 7） | 先建立一致 V10 出發與歷史收據、記 29 舊領域表及 Flyway 原列指紋，再驗 V11／34 表及新四表零回填；保留舊升級案例。真瀏覽器驗 private bytes、兩段進度、未知回應後原請求／CSRF 重試及權限／占用；完整 HTTP validators 結果另列。本包不交付新執行 UI、補件、完成／釋放或結案。 |

## 隔離、修正與交付界線

全部驗收順序為Maven結束後才啟Compose，沒有並行Testcontainers／第二組測試PG。TLS及空庫／重啟／映像同源證明完成後才產生90個私密0600憑證並立即啟E2E；七份HTTP及隱私全過，保存42份原回應JSON、56張原始回歸截圖後，才刪本包隔離stack／volume／privateenv／fixture／overlay。binary原照片只在記憶體對照bytes／SHA／瀏覽器decode，不存原照片／headers／cookie／CSRF／key於報告。隱私工具範圍為fixture密碼及trace，沒有冒稱能掃出所有token。

審查階段修復multipart／form前置解析：普通Boot停用未使用resolver，新rawupload不解析parameter map，該POST的CSRF只讀header，其他既有route委派原handler；實際wellformed／malformed multipart、urlencoded／缺CSRF及chunked上限均已由IT通過。資料庫補實際SHA256、雙向延遲FK無孤立blob與同階段版本選照FK。瀏覽器實跑前僅修正負例content的Accept為image/png，確實走私有讀取handler後檢查404／Range／query，原所有斷言保留；194個後端輸入不變，affected strictTS／noUnused及契約唯讀覆核通過後再跑全量32。沒有以測試fixture值作產品照片政策。

backend映像 `sha256:70432f853ba7fdfbcc1f8a9db46a9a9d3d63f973b961026dbc12ae71f35e940c`、frontend `sha256:7b9e4b981af4ee857473de2d5c01c426a67b8f7cde29765d6de65f423f0c478e`；234個main編譯class相同、71產品建置檔相同、11migration bytes相同，外掛僅7個明確白名單test class。本包沒有新增操作UI，截圖是既有工作台回歸；完整司機操作畫面留M4-06。普通DEV維持既有M3服務／資料，正常M4應用拒絕策略由DefaultPolicyIT真TLS獨立證明，不把普通DEV冒稱為本包新映像。

交付前公司最新再次核對 `9213a3d8dacb8774d87c26cb736c4a4315779e95`，與開工相同。未核准策略保持拒絕，不修改舊Repo、不正式部署；M4-03須本包PR及main四項全過才開始。

## 最終交付收據（M4-03 開工前核對）

[PR #24](https://github.com/xk2022/xk-app-logistics/pull/24)，source `b9647dfe4430e8d088362331c92d31de405c44a5`／merge `4681f13b4dd1dc324632848e7b119147e0e43068`，Git tree相同。[PR CI45775507970](https://github.com/xk2022/xk-app-logistics/actions/runs/45775507970)及[main CI45776966300](https://github.com/xk2022/xk-app-logistics/actions/runs/45776966300)均attempt1四項成功；遠端各139 unit＋330 integration、32 E2E全過、0測試略過。七HTTP實採本機1,731／PR1,733／main1,734（Execution171／173／174，其餘六份相同、皆42files），依各run實際競爭回應數量記錄。精確main head核對完成後才從merge開M4-03。
