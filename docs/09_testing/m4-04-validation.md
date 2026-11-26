# LG-M4-04 完成與有效釋放驗證

狀態：PR #26已合併，PR CI45798797894 attempt1與main CI45800016961 attempt2均四項成功。前包[PR #25](https://github.com/xk2022/xk-app-logistics/pull/25)的source508e1e41174623e39c25352d1ae7d77df3692c99／merge e55ee4ecdf98d6f314b60e45c96b0b18d9e5489e，PR CI45787301633及main CI45788390226 attempt1各四項成功後開始；公司再次核對9213a3d8dacb8774d87c26cb736c4a4315779e95。

[工程採用](../98_decisions/2026-11-26-m4-04-engineering.md)先釘住舊API相容、nullable completion marker、完整交易／reverse typed證明及兩個SQL封存入口；提案與source review不算runtime通過。原35份API／migration輸入hash保護。

| 驗收 | 實際狀態 |
| --- | --- |
| 前端 | lint／typecheck／718 tests（30 files）／build83 modules全過；113來源凍結；JS561.03kB／gzip157.28kB advisory |
| 後端／PostgreSQL | 完整無篩選clean verify 153 unit＋413 integration＝566全過，0 failure／error／skip，12:30；原重點14＋132全過5:12 |
| API | 九份靜態及2,244筆實際HTTP／76檔全過；新3ops／29schemas／57正／368反／8ref／7hash／17CLI通過 |
| 升級／初始化／重啟 | Platform14全過，V12→V13保留39領域表／照片／收據；真PG17.12空庫42表／13版／零業務，重啟一致，291正式class集合與bytes／82前端輸入／13migration同源 |
| 真TLS桌面／手機 | 36／15files全過，2workers／0retries，3.3m；前端與契約作者crossreview通過；110臨時憑證reportprivacy通過 |
| 安全／隱私／source同源 | npm兩端0；SBOM1.6／144；OSV409／4來源無issue；staged預檢gitleaks0／無排除；110憑證隱私及runtime同源全過；最終staged gitleaks 0 findings／無排除；commit history於交付PR及CI另核對 |
| source／PR／merge／四項PR及main CI | PR #26；source5112ae5842bd16c7a2f50c41cdc5ef83136a1534／merge a027dd1e38279449b6843fead9633236aa164b64；PR45798797894 a1／main45800016961 a2四過 |

正式安全確認者、D04／D05／D06／D10、provider、派工資格與結案責任未核准，正常策略維持拒絕，正向僅隔離合成。完整管理確認／DRIVER操作分別留M4-05／06；不做M5或正式部署。


## 首輪重點驗證與修正

首輪clean verify 1分28秒，14 unit通過；132 integration均因同一V13啟動語法錯誤報error，0 assertion failure／skip。Flyway明確記錄全部回滾；SQLSTATE42601 end-of-input定位validate_tms_completion第145行CASE第一個THEN被PL/pgSQL當IF終止符。只替CASE expression加一對括號，保留全部二三typed資源、FK、receipt及RR seals，不放寬檢查。原35份契約與migration不改；237backend／182main快照只更新這份V13。修正後必須重跑同一重點及完整驗證，首輪不列成功。

修正後同組重點驗證14 unit＋132 integration全過5分12秒，包含Platform14、Completion27＋normal Default1、原Exception51及Tms39。原來源快照237 backend／182 main／113 frontend、35舊契約migration、58HTTP/E2E及7系統輸入均匹配，沒有邊測改碼。


## 真實 HTTP 與操作證據

| 契約 | 實際回應 | 檔數 |
| --- | ---: | ---: |
| 原訂單 | 464 | 20 |
| 受理 | 56 | 12 |
| 車輛 | 188 | 6 |
| 司機 | 302 | 6 |
| 派工 | 472 | 12 |
| 本人詳情／出發 | 186 | 8 |
| 進度／照片 | 246 | 6 |
| 異常／補件 | 196 | 4 |
| 完成／有效解除 | 134 | 2 |
| 合計 | 2,244 | 76 |

完整566案例涵蓋原M1～M4-03回歸與新增28個完成／普通策略案例及Platform第14升級案例。真PG驗證二／三資源exact markers、有效再用與舊收據不再釋放新owner、source成功收據封存原照片集合、terminal event不得加照片、完成後NULL-marker也不能補TRAILER、RR舊快照40001、報異常競爭、同physical換合法帳號的讀取與actor ledger隔離、原子／deferred故障全部回滾及未見證舊COMPLETED拒絕升級。普通CompletionPolicy為UNCONFIRMED，即使bool確認true且具備照片／待完成狀態也拒絕。

36 HTTPS desktop／Pixel7 E2E保留原34，新增完整持久照片／明確補件／處置／完成／有效解除／依賴解鎖／再用。受控代理真正向TLS後端提交後丟棄回應，接著真CSRF拒絕及同body/key重送成功，驗原結果不重複；FMS行政狀態改變後仍能窄讀本人原完成事實。舊M401／M402／M403 current-only本人route完成後404；原十四欄列表／歷史／管理收據保持。新UI僅真實指派記錄文字，沒有提前交付完整DRIVER表單。

42表／V1～V13零業務初始化與重啟、291正式compiled main class集合及bytes、82非測試前端來源與設定、13migration bytes逐一核對，正式JAR無support／testpolicy／fixture。映像為backend b1974b757eeeff82512bfb9c61325861849df5db04348543fdd75979f57954df／frontend ad93a21360afd20183cecc74b733b5cb0dd570a99c824a41e25005883c881e50。

真TLS及來源核對後才產生110個獨立fixture憑證，reportprivacy全過後保存76份原body-only JSON與68張screenshots，再清除本包容器／資料卷／env／overlay／fixture；普通DEV未變。原PNG僅記憶體檢查，不保存binary、headers、Cookie、CSRF、key或trace。root已檢視desktop／mobile實際指派記錄畫面，沒有把legacy null release當目前占用。

本機工作區證據 `/workspace/artifacts/lg-m4-04-evidence/` 包含JUnit33 suites、完整摘要／source manifests／SBOM／掃描／初始化／runtime／HTTP／privacy及原圖；交付圖為`completion-desktop.png`與`completion-mobile.png`，是完成後真實記錄畫面。CI另上傳同版本Maven／Playwright／HTTP artifacts，不以本機代遠端。

## 交付與剩餘風險

本包source／PR／merge及四項PR與main CI由[唯一head收據](https://github.com/xk2022/xk-app-logistics/pulls?q=is%3Apr+head%3Afeat%2Flg-m4-04-task-completion+base%3Amain)追蹤且已四過；精確main merge核對後已自動從a027dd1開始M4-05。

正式派工、D04／D05、D06／D10、provider、安全確認者／釋放責任及結案政策仍未核准，普通策略維持拒絕。沿用UPMS單instance，沒有正式儲存／清除工作。bundle561.03kB／gzip157.28kB有500kB advisory，build成功。管理確認／結案及完整DRIVER UI分別留M4-05／06，不做M5或部署。

## 合併後收據

[PR #26](https://github.com/xk2022/xk-app-logistics/pull/26)，source `5112ae5842bd16c7a2f50c41cdc5ef83136a1534`／merge `a027dd1e38279449b6843fead9633236aa164b64`；PR CI45798797894 attempt1及main CI45800016961 attempt2四項成功，source／merge tree一致。main attempt1僅Backend在測試前下載Spring Boot Maven plugin4.1.3 POM遇Maven Central HTTP429，另外三項成功；整個workflow完成失敗後，只重跑失敗job、同SHA且不改碼，attempt2 Backend153＋413＝566全過。PR及main均36 E2E／2,244 HTTP；main執行回應188／異常194，總量與本機一致。最終history scanner實際掃37 commits、0 findings、無排除。由該merge開始M4-05。
