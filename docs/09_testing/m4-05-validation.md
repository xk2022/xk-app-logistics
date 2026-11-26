# LG-M4-05 管理確認與結案驗證

狀態：本機完整驗收通過：前端883／後端612／真TLS E2E38及十份HTTP2,686。source／PR／merge與遠端PR及main四項CI以本包唯一head收據記錄，不在提交前宣稱遠端成功。前置M4-04 PR #26／source5112ae5842bd16c7a2f50c41cdc5ef83136a1534／merge a027dd1e38279449b6843fead9633236aa164b64，PR45798797894 attempt1及main45800016961 attempt2四過；main首輪Maven Central429在測試前失敗，完成後僅重跑失敗Backend同SHA成功。

[工程採用](../98_decisions/2026-11-26-m4-05-engineering.md)、[工作包](../08_development/m4-05-management-closure-handoff.md)與[API](../06_api/tom-closure.md)。公司9213a3d8dacb8774d87c26cb736c4a4315779e95。凍結38舊API／migration來源；普通兩策略UNCONFIRMED、正向只actual-DB-gated隔離合成。

| 驗收 | 實際狀態 |
| --- | --- |
| 前端lint／typecheck／test／build | 修正歷史測試scope後四項全過；883 tests／34 files，126輸入凍結；JS615.40kB／gzip170.84kB advisory，build exit0 |
| Maven clean verify／PG交易／升級 | 完整無篩選161 unit＋451 integration＝612全過，36 suites、0 failure／error／skip，13:23；真PG17.12空庫47表／14版、零業務及重啟一致，V13→V14全41舊領域表含非空marker／照片／收據／legacy CLOSED保留 |
| 靜態與實際HTTP十契約 | 十份靜態與2,686筆實際JSON HTTP／96檔全過；新9ops／49schemas／99正／603反／8refs／8pins／17CLI |
| 真TLS桌面／手機／隱私 | 38／16files全過，Chromium桌面19＋Pixel7 viewport19，2workers／0retries，3.4m；HTML stats expected38／unexpected0／flaky0／skip0；120臨時密碼reportprivacy全過 |
| 安全掃描／正式JAR同源／圖片 | npm兩端0、SBOM1.6／144、OSV409／4來源0 issues；338正式class集合與bytes、91前端輸入及14migration同源，Jar無testpolicy／support／fixture；72張安全原圖已存，root檢視closure-desktop.png／closure-mobile.png |
| source／PR／merge／PR及main四項CI | 本機全部完成；遠端收據依[唯一head收據](https://github.com/xk2022/xk-app-logistics/pulls?q=is%3Apr+head%3Afeat%2Flg-m4-05-management-closure+base%3Amain) |

必要案例包含virtual pending無寫入、0→1管理確認無execution／TOM變更；allpersisted true／null任務完整性、整單所有占用／blocking exceptions、current source原文比較；CLOSED＋ACCEPTED／version+1／完整revision／customer snapshot／child IDs保留；legacy無稽核；typed ledger／manifest雙向／所有root與graph set seals／RR及rollback；錯版本單位、兩key競爭、原POST失回應→CSRF→原次重送、歷史receipt不重放副作用；普通政策拒絕、角色／公司／原Session及本人隔離；舊五API使用原TOM schema。逐項實際結果於完成後補入。

不核准正式安全確認者、派工、D04／D05／D06／D10、provider、保存／清除或結案責任；沒有M5或正式部署。


## 實測前獨立審查

九Web／安全路由及完整核心／V14均獨立唯讀審查通過。審查發現並在任何本包Maven／runtime前修正兩處：歷史收據原先只比部分識別，現以完整原fact／witness／context比對reason、actor、time、policy，不一致安全500且不帶會被映E2106的SQL cause；TOM原先直接查TMS私有表，現改公開OrderClosureFacts typed port，由TMS-owned JDBC adapter提供逐任務count及recorded確認時間。九API與DTO不改，review ACK不代替尚待的執行測試。

靜態十份契約validator已全過；新closure9ops／49schemas／12copied／8pins／99正／603反／8refs／17CLI guard通過。npm root全依賴及frontend audit各0。38舊API／migration hash仍完全相同。112份Markdown／660相對filelinks皆存在，actionlint與diff check通過；最終文件更新後再核對連結。

## 首輪失敗，尚不列通過

重點verify首輪5分15秒：20 unit全過，117 integration有28 assertion failures、0 errors／skip；Platform15、Completion27及Tms39全過，新增管理35／27fail及普通Default1／1fail皆先遇管理確認500。實際PG錯誤unsupported Unicode escape sequence，固定合成JSON只讀probe也證實擷取id會解碼無關reason的NUL。程序結束後才開始修正新ledger內部codec及revision固定ASCII metadata投影；完整原文字串、typed proof、原API及38舊hash不變。首輪log／summary保存，不把其餘已過案例算成全包成功。

前端首輪lint／typecheck通過，完整883 tests有881過／2失敗：原OrderManager對整個App的no結案操作查詢誤匹配新合法管理導覽。僅將原斷言精確限制至已斷言存在的order-workspace，另外驗新導覽可見且不預載兩項管理worklist；全部原13相關測試保留且重點13過，產品125其他frozen輸入不改。已保存首輪log，修正後完整四門檻重新執行。

NUL修正後重點第二輪4分12秒：22 unit全過、新Management36＋Default1、Platform15、Tms39全過；舊Completion容器在任何案例前被RootlessKit拒絕綁定33004（address already in use），故92個integration紀錄有0 assertion failures／1 container error／0 skip，不列整輪成功。沒有改產品便開始原定完整無篩選verify；該輪另一舊FmsDriver容器又於案例前遇33012同因。此為重複證實的啟動問題，磁碟仍5GB可用，與NUL／業務斷言無關。

Pinned Testcontainers2.0.5 bytecode檢視確認startupAttempts預設1、沒有等價runtime property；有界3總次只重試容器create/start，每次清舊container ID並重新請Docker分配動態埠，不重跑JUnit案例或斷言。正在等待本輪結束，再以最小fixture設定穩定化；不改daemon、全域sysctl、固定埠、業務流程或跳過CI。

完整首輪12分44秒最終為161 unit全過、423 integration紀錄中422過／1 container error／0 assertion failures／0 skip；唯獨FmsDriverIT的29個案例未開始，記為一個class-level啟動錯誤。因此沒有宣稱612全過。程序結束後，才授權20個PostgreSQL fixture僅新增`withStartupAttempts(3)`；預定逐檔移除該唯一字串比對原凍結bytes，證明原case／assert／版本pin／SQL不變。此為container startup三次總上限，非JUnit失敗重跑，三次仍失敗則如實失敗；後續必須重跑無篩選完整verify。

新管理client固定API請求加上redirect:error，避免先自動轉址再驗Location；只修改兩份新client／測試，104 targeted再過後完整四門檻883／34全過，124其他前端輸入不改。後端verify不讀frontend，263／203後端凍結仍有效。

有界startup設定後，原29個司機案例在完整verify全過；最終161＋451＝612／36 suites全過13分23秒，0 failure／error／skip，未重跑任何斷言。真TLS前先完成47表／14版空庫及重啟、338正式compiled main class集合與bytes、91前端來源／設定、14migration bytes核對，productionJar無support／policyfactory／fixture。其後才建立120臨時合成帳號，完整38 E2E仍執行中，尚不列runtime操作通過。

## 首輪真TLS與代理修正

38案首輪36舊案例通過、新管理兩viewport同因失敗，3.2分鐘／0 retries。16,385-byte JSON在Nginx全域16k被攔成HTML413，沒有抵達後端16,384-byte reader；collector如實拒絕JSON解析，沒有假造company錯誤。120個臨時密碼reportprivacy通過後，保存96份body-only回應及68張安全畫面到failed-run證據，才移除該隔離資料卷／env／overlay／fixture。

僅兩個完整UUID管理路徑提高代理coarse bound至32k，保留全域16k、原uploads2m、後端16,384-byte hard cap及全部原測試。路徑case-sensitive、ID允大寫hex，轉送標頭逐項同既有/api且清caller Forwarded；獨立審查與鎖定Nginx映像nginx-t通過，執行中config bytes與凍結來源相同。新fresh47／14零業務／重啟及338／91／14來源證明再過後才產第二組120憑證；同38案完整重跑。未修改後端612／前端883受測輸入，僅七系統輸入中的Nginx hash更新。

## 最終本機交付證據

| 契約 | 實際JSON回應 | 檔數 |
| --- | ---: | ---: |
| 原五訂單 | 482 | 22 |
| 受理 | 62 | 14 |
| 車輛 | 192 | 8 |
| 司機 | 308 | 8 |
| 派工 | 488 | 14 |
| 出發 | 192 | 10 |
| 進度／照片 | 270 | 8 |
| 異常／補件 | 210 | 6 |
| 完成／有效解除 | 164 | 4 |
| 管理確認／結案 | 318 | 2 |
| 合計 | 2,686 | 96 |

最後一輪38真TLS案例全過，包含全部原36及新增兩viewport管理確認／結案。兩個true／null任務都真派工、進度／照片、補件明確提交／管理處置、完成安全解除、分別0→1行政確認，再將TOM2→3及真正CLOSED＋ACCEPTED；整單零有效占用與blocking exceptions，未略過未指定任務或自動釋放。ADMIN／DISPATCHER雙版本衝突、Node代理實際提交後丟回應、真CSRF拒絕及原body／key明確重試、原歷史收據／當前GET分離、CRM改名／停用後歷史快照、舊五API及受理OPEN replay、資源他用後本人窄完成讀取／重送及新帳號actor namespace均已實跑。

普通管理確認與結案policy仍UNCONFIRMED。Default IT先驗普通管理確認拒絕，再僅test-local、actual-DB-gated管理wrapper短暫SYN，以真API建立合法確認事實，回UN；OrderClosurePolicy始終唯一普通bean、不override。兩真完成／兩真確認、零占用／零異常均已具備時，close仍E2103且零新witness。不能把此測試誤述為完全沒有任何管理fixture override；正式JAR無該wrapper或factory。

263 backend／203 main／126 frontend／38舊API migration／62 HTTP-E2E及7系統輸入凍結；最後代理修正只更新一份系統hash，nginx-t、live bindmount bytes與38E2E驗證，不改已通過612／883的受測應用程式。正式JAR338 main classes精確集合及bytes、91前端來源／設定、14migration bytes與本機一致。隔離映像backend d103e169e285ec49caebf70e3ae244330549ed1c430072434defee73dca0a66b／frontend b03be4d4ec8162ed77477aca4d2c749b8ed6f84bbeb36b4515cbf4a99570392a。

120密碼值與無trace檢查通過後保存96份原body-only JSON、72張安全screenshots，再清本包容器／資料卷／env／overlay／fixture；普通DEV完整保留。root已檢視實際desktop／mobile結案畫面：CLOSED／原來源2及目標3、兩個true／未指定任務皆已完成且確認、零占用／異常，raw script文字安全顯示，沒有私有照片或橫向溢出。

本機證據 `/workspace/artifacts/lg-m4-05-evidence/` 含JUnit36 suites、frontend/e2e/HTTP/privacy/runtime/初始化/SBOM/OSV/source manifests、首輪失敗與修正證據，以及closure-desktop.png／closure-mobile.png。staged與最終commit history機密掃描、PR及精確merge main四項CI由本包交付收據補記；全部四項main成功後才開M4-06。

正式派工、安全確認者、結案責任、D04／D05／D06／D10、provider／保存清理仍未核准，正常策略維持拒絕。UPMS單instance，無正式儲存或清除工作；JS615.40kB／gzip170.84kB有500kB advisory但build成功。完整DRIVER操作留M4-06，不做M5或正式部署。
