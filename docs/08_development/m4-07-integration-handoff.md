# LG-M4-07｜M4 全量整合驗收

基礎 `e7c8acf1351caf379b7871c8078646671b307745`，前包PR #29 source3709bb5，PR CI45844612553 attempt2與精確main CI45847696751 attempt1四項成功後開始。公司規範核對f1be01c0c5d8bc5b1238f250838e336bef47b149，品牌過渡保留既有資產；工程規範未變。使用者已核准Q41–Q47與全部M4，無新增業務政策核准。

依[M4工作包](m4-execution-handoff.md)、[工程採用](../98_decisions/2026-11-26-m4-07-engineering.md)及[驗證紀錄](../09_testing/m4-07-validation.md)執行。保留全部既有案例及41份API／Migration精確內容；不新增endpoint、schema、V15、正式provider或產品後端功能；手機驗收發現的既有單欄要求只修scope CSS並保留舊FMS樣式。正常派工、照片、例外、安全釋放、管理確認／結案策略維持UNCONFIRMED拒絕。

## 升級與資料完整性

新增單一Platform第16案例：真V9含26舊領域表正資料與獨立legacy CLOSED訂單，直接升V14恰5 migration，最終47表。舊欄位／UUID／原文／客戶名稱快照／成功收據／Flyway原checksum不變，20新表為空、completion marker為NULL。保留原15案與固定target，另以full47重複validate／migrate0證明冪等；不將其稱為真重啟，也不造舊完成或結案見證。

完成全40桌面／手機E2E後，以普通HTTPS API建立獨立H／P／C／A四cohort，global三帳號及IT分類由合成測試scope使用；資料庫仍actual-name gated。H包含所有persisted true／null任務真完成確認結案；P完成待確認、C完成確認但仍OPEN可結案、A仍有效AWAITING且有照片與完整占用。同人車依真完成釋放後順序重用，A最後保留占用，不依賴前輪UI帳號重綁。

停止本包writer，先以相同資料卷實際重啟PG17與backend，再於任何新登入前比較47表全欄／Flyway／照片及結構。以PG17 custom dump完整備份到私有檔，single-transaction還原至不同名稱、同encoding／collation的空DB；啟動前比較完整47表、照片SHA／長度、函式、索引、FK／延遲性及trigger邏輯，不比跨DB catalog OID或自動RI trigger名稱。不repair、不停trigger、不人工修資料。

## 正常還原與帳號復原

還原服務使用相同production image、普通java-jar，無test classpath／provider。API-only localhost TLS8445，無前端或公開照片。證明正常policy仍UNCONFIRMED：P新確認與C新結案E2103、A新完成E2003、有效照片content E1803無bytes；前態須真正符合其餘條件，不能以CLOSED錯前態代替。保留原actor／body／key的舊成功重播及完成窄資料，不新增業務事實。

以stdlib PTY及named one-shot Docker執行正式JAR init／recover真Console，合成自動化驗收不冒稱真人責任核實。獨立init DB無預設帳號；restore web JVM持續運行時驗recover的authVersion、舊Session／舊密碼失效、首次改密及control Session隔離。正常登入只允明列audit append、account與lock全等；recover／改密只允victim密碼與temporary／authVersion／version／update欄位按真次數變動，其他帳號與全部非UPMS／Flyway完整保持，不泛排除UPMS。

## CI 與證據隱私

fixture只新增global三普通帳號，原130完整保留，實跑133已核對；無業務種子。舊UPMS瀏覽器另透過真API新增帳號並產生改密／失敗登入用密碼，故實際DB帳號數與fixture identities分開記；syntheticPassword先以跨worker有界鎖及0600原子寫入完整inventory才回傳，不改舊40案例。fixture建立後、瀏覽器前先原子0600複製完整私有secret inventory，每一新密碼傳送前先追加，即使失敗或已被取代也保留到最終guard。原fixture與inventory路徑分開；診斷先收集，最終guard掃描瀏覽器／test-results／artifacts與嵌入ZIP後才可上傳。

required Compose drill在完整E2E後、十HTTP契約與隱私上傳前執行，非local-only。外部0600 owned-state於create前記錄意圖，finally與always cleanup-only只清本包容器／不同DB／私有備份等，先清額外資源再Compose down；inventory最後刪。公有證據僅aggregate摘要、十既有body-only JSON及安全合成截圖，不留dump、密碼／hash原列、headers／cookie、PTY或照片binary。清理不聲稱實體抹除或正式DR RTO／RPO。

本機前端四項、未篩選Maven、真TLS40E2E、drill、十HTTP、安全掃描及隱私全過後，獨立PR／四CI／merge／精確main四CI完成本包。無M5、舊repo修改或正式部署；[最終M4報告](../09_testing/m4-integration-acceptance.md)逐包列收據及剩餘風險。

最終本機全部通過：1,083前端／613後端／12工具units／40真E2E、DR127.598秒、HTTP3,242／125檔、原始150隱私；詳細47表／67照片、Console、手機幾何、失敗修正與owned清理收據見本包驗證。遠端必須本包PR及精確main四過，不能以本機代替。
