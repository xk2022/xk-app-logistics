# Showcase Demo｜實測與交付

狀態：首版來源 PR #32 與 Hub PR #31 已通過各自 PR／精確 main CI 並公開發布，公開核心8案全過；額外手機導覽發現返回 Hub 連結被既有小螢幕樣式隱藏，現以獨立修正 PR 補齊可見入口與回歸斷言。最終公開桌面／手機導覽收據依下節記錄，未完成修正驗收前不宣稱全部導覽已通過。最新使用者範圍仍僅 Showcase Demo、不建立 Render，完整 M5 暫緩。

## 基準與來源

- 規格 PR #31 source `c03e8cc404cb6ae0aeff26359596bdf126164761`／merge `14697495e9bded2661d6457dc845cb3cff56d68d`；PR45918607908 attempt1、main45919744208 attempt1 四項全過。
- Hub 初始 main `8f8a446a446219da2510b79741743cd13ad76df6`，22個既有入口中只更新物流；其餘21個設定保留。既有物流為 screen／source `070383e1ff76dcaaf16693e8fcd8d4f5c0bb915c`，回退須恢復該完整登錄並重新通過發布流程。
- 原 backend／API 共291份輸入、frontend144份原輸入與正常產品5個建置檔案均已固定 hash。原141份 frontend 輸入精確不變，只有 package.json、eslint.config.js、.dockerignore 三份建置 metadata 變動；新增展示源碼獨立，原產品 source／測試不改。

## 驗收門檻

| 層次 | 要求與目前紀錄 |
| --- | --- |
| Hub 原基準 | token-free 146 tests 通過；第一次繼承憑證時觸發既有拒絕守衛，改用規範要求的無 token 程序後通過，未修改守衛 |
| 產品回歸 | frontend lint／typecheck／1,159 tests／41files／normal build 全過，正常5檔逐byte相同；Maven clean verify161unit＋452IT＝613／36suites，0failure/error/skip、12:23；舊40真後端／PG E2E17files、2workers、0retries、3.9分鐘全過；真DR／十HTTP／原始完整inventory guard亦通過，詳下 |
| Demo 來源 | 64model＋12UI＝76新增測試全過；獨立TS／ESLint／build全過，31實際Rollup modules，不含App/main/API/test；JS284.90kB／gzip87.00、CSS41.02kB／gzip8.06 |
| Demo 真瀏覽器 | 最終8案／桌面與Pixel7各4，1worker／0retries、23.0秒全過；角色／三單型／多項目／依賴負例／閉環／重用／補件分離／NFD原文／重置／刷新／分頁及foreign storage保留；API/fetch/XHR/WS/beacon/CSP/console/failed resources0，Cookie/IDB/cache/SW0；手機主要控制≥44×44 |
| Hub 候選 | 完整201tests全過、0skip；精確來源CI gate、token-free完整22站建置、物流獨立smoke、既有transport及公開驗證待來源交付後實跑 |
| 遠端交付 | 物流 source PR／精確main四過後才 Hub pin；Hub PR／main發布及 live manifest／HTTPS／桌面手機均須真驗，不能以本機成品冒稱已部署 |

本輪 source 分支為 `feat/lg-m5-showcase-demo`；Hub 分支為 `feat/logistics-interactive-demo`。完整 source／merge SHA、CI run／attempt、實際測試數、畫面、公開網址與剩餘限制，完成後由各自 PR 收據及最終 workspace 報告記錄，避免將尚未發生結果當成功。

不建立付費方案、不修改舊物流 Repo、不新增正式政策或產品 API；不發布 MVP v1.0.0、不宣稱真實客戶 UAT、正式營運、遠端 DB／照片持久化或完整 M5 已完成。


## 實作複核與測試修正歷史

獨立複核修正三項初稿：resolve必須有本次明確補件提交證據、OPEN且無active request；受理依既有ORD-P02/P03完整性及合法合成期限；角色切換清除暫態安全勾選與資源候選。新增負例後64model／12UI全過，未修改正式服務或核准新政策。

首次browser8案均停在beforeEach：測試定位器同時匹配html與app的展示標記，尚未執行劇本；僅修測試為精確app容器，第二輪8全過20.6秒。JSON reporter原相對路徑落在config子目錄，修成固定root artifacts以納入最終隱私掃描，8再過20.7秒；再以頁頂長頁擷取避免fixed離屏skip-link在截圖中被合成到中段，增加手機44px幾何斷言，最終8全過23.0秒。沒有隱藏無障礙連結或改動展示邏輯；首輪log、第二輪實際JSON及各輪log保留，首輪JSON未另存。

初次build checker錯把計算產生的完整訂單號當成必存的JS literal；改以實際Rollup fixture module＋固定展示入口標記驗證，正常5檔比對與API／測試模組排除全保留，實際建置驗證通過。Python stdlib12項CHECK反向工具測試另實跑全過，與8個browser cases分開計數。npm root／production／frontend audit零漏洞，CycloneDX1.6實數144components；未以未跑的遠端CI或live結果當成功。


## 最終本機真後端整合結果

- 真實隔離PG17.12空庫47表／14versions、零業務資料、重啟及TLS正常；18匿名路由401。production JAR338mainclasses與本機受測bytes精確相同、14Migration bytes不變，無support／fixture／test policy。115份frontend建置context輸入與映像相符，其中包含獨立Demo源碼；正常產品輸出另以5檔byte相同證明未載入Demo。
- 舊40 E2E全過後的真DR114.608秒全部通過：populated PG／backend重啟、PG17 custom dump到另一DB、普通Jar啟動零寫入及最後source皆47表／完整邏輯schema／history／68實際私有照片bytes／長度／SHA全等。錯actual DB的test工廠拒啟、全部47不變。
- 正常Jar合法前態新管理確認／結案E2103、新完成E2003、有效私有照片content E1803且無bytes；歷史OPEN收據3、完成重播4、確認重播3、結案重播1及資源重用後窄讀保留。
- production Jar真PTY recover／init：victim authVersion恰＋2、兩舊Session撤銷、control／DRIVER隔離、舊limiter namespace不重用、首次改密及重複init拒絕、business不變。133fixture、source135account rows、另一init DB1account分開計算；自動化合成操作者不代表真人責任核實。
- 十原HTTP契約真回應3246／125份body-only JSON全部通過：order507、acceptance71、vehicle197、driver315、TMS519、execution203、progress/evidence402、exception308、completion321、closure403。回應筆數不當作案例數。
- 診斷收集後，以133fixture＋12browser＋4Console/改密＋1DB共150個實際secret patterns掃描原始report、test-results、artifacts及嵌入ZIP，全過才保存125 JSON／78真後端安全PNG／6離線Demo PNG與摘要。不以redacted副本代門檻；不匯出credentials／hash原列、dump、PTY或私有照片binary。
- finally＋cleanup-only冪等＋sourceCompose down已清理本包owned額外DB／runtime／work／dump／volume及private env/fixture/inventory/state/authfile，保留正常DEV四容器與資料卷；不宣稱實體抹除、cluster-global roles/ACL或正式RTO/RPO已驗收。
- 本機安全檢查npm三範圍0漏洞；OSV四實際輸入3＋255＋7＋144＝409packages零finding；actionlint／文件相對links／diffcheck及提交前gitleaks另按最終收據記錄。來源PR四項成功後才合併；精確merge main四項成功後才更新Hub pin。

完整本機證據workspace `/workspace/artifacts/logistics-showcase-demo/`；公開展示圖與實際HTTPS URL必須待Hub正常部署後另驗。source／Hub各輪遠端HTTP、DR、照片與耗時採各輪實數，不拿上述本機數字代填。


## 首次公開發布與手機導覽修正

- 首版來源 PR #32 source `278b8040ba0b3422ffeb189587c30bd311792665`／merge `9b226ada240f3fb964b2f568bdbff76c690ef8ec`，tree相同；PR45925506062及精確push/main45926373215 attempt1四項全成功、無rerun。main實數frontend1159／backend613（161＋452、0failure/error/skip）／工具12／真後端40（3.0m）／離線8（17.3s）／HTTP3252、125份family回應；PR輪HTTP3244，與本機3246分開。
- Hub同步最新06100f0e的Rotaract治理後，以完整209tests／0skip、無憑證全22站、其他21完整registry／manifest objects相同、transport51runtimefiles相同＋fresh smoke通過。歷史transport source suite45564113822為REUSED_NOT_RERUN。實際本機Hub輸出22sites／69pages／276assets／370requests＋source8（20.6s）全過，初始縮圖80,679bytes已檢視。
- Hub PR #31 source `e6867d0310c891787957033ac3ad171695b6fca8`／merge `6af6967dc1657b69a6a0b90dc663423340e6d899`，tree相同；PR45927603296與main45927903981 attempt1必要resolve/build成功，main另含freshness／Pages／公開verifier成功，22sites／69pages／276assets。PR deploy與兩個opt-in publisher的條件skip均依設計，不當作部署成功。
- 首版實際HTTPS核心8案22.1秒全過；獨立Hub卡片→Demo→角色→返回Hub導覽，桌面通過，手機因`.sidebar-foot`受共用640px規則隱藏而找不到可見返回連結。保留原始失敗log與畫面，不以核心8成功替代此失敗。修正僅針對獨立展示入口，不改正常產品CSS／API／後端。
- 修正保留全部8案原劇本，再增加返回連結可見／精確href／手機44px檢查；公開後另實跑桌面與手機完整返回導覽。新的[source修正收據](https://github.com/xk2022/xk-app-logistics/pulls?q=is%3Apr+head%3Afix%2Fshowcase-mobile-hub-navigation+base%3Amain)與[Hub重新發布收據](https://github.com/xk2022/xk-website-showcase/pulls?q=is%3Apr+head%3Afix%2Flogistics-mobile-hub-navigation+base%3Amain)記錄精確SHA、各輪CI、實際測試與最終畫面；不借用首輪成功當新版本交付。

修正本機全量前端首輪1158／1159通過，既有 `DriverExecution.test.tsx` CSRF恢復案等待收據失敗。根因為pending標題在resume microtask前已存在、retry仍disabled，測試先點擊而被原生按鈕忽略；未改案例單獨選跑1案通過（另28未選，不當全量成功）。經獨立複核，只在原explicit retry前等待按鈕enabled，保留原兩次fetch0／收據／恰1POST全部斷言，不加timeout／sleep／重試或skip，正常產品code不改。最終原frontend144輸入中140 byte-identical；原3份metadata外只多這項測試等待條件，原291 backend／API全部不變。先前141不變紀錄屬首版，不套用到此修正。

手機修正最終本機 lint／typecheck／全量1159tests／41files（25.95s）／正常與獨立build全過，正常5檔byte-exact，31modules／7展示檔、JS284.95kB／CSS41.01kB。新入口可見／href／44px回歸加在原8案及每次role切換，桌面＋Pixel7共8全過20.8s、0retry；新手機圖已檢視。backend／API291份byte-identical，本輪本機不重跑相同後端，保留此前613真PG結果；新PR及精確main仍須完整四項CI、613及全部回歸另實跑，通過後才Hub新pin。
