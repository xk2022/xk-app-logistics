# LG-M4-07｜全量整合驗證

狀態：最終來源全部本機品質門檻通過，獨立 PR／合併／精確 main 四項 CI 由[本包唯一 head 收據](https://github.com/xk2022/xk-app-logistics/pulls?q=is%3Apr+head%3Atest%2Flg-m4-07-integration-acceptance+base%3Amain)記錄；遠端未完成前不稱已交付。基礎 `e7c8acf1351caf379b7871c8078646671b307745`，前包 PR #29／PR45844612553 attempt2／main45847696751 attempt1 四過後開始。公司最新規範核對 `f1be01c0c5d8bc5b1238f250838e336bef47b149`；品牌過渡允許既有資產，工程規範未變。依[交接](../08_development/m4-07-integration-handoff.md)與[工程採用](../98_decisions/2026-11-26-m4-07-engineering.md)執行。

## 最終本機結果

| 門檻 | 實際結果 |
| --- | --- |
| 前端完整四項 | lint、typecheck、1,083 tests／39 files、build 全過；最終手機 CSS 修改後完整重驗 |
| 後端未篩選 clean verify | 161 unit＋452 IT＝613／36 suites，0 failure／error／skip；12:56，BUILD SUCCESS、exit0 |
| CHECK 比對反向單元測試 | Python stdlib 12 項全過，列必要 CI |
| 最終 HTTPS E2E | 40／17 files，桌面與 Pixel7 各20；2 workers、0 retries、4.6m；HTML expected40，unexpected／flaky／skipped0 |
| 真還原與 Console 演練 | 全部通過127.598秒；非僅 migrate0、健康檢查或元件替身 |
| 十份原 HTTP 契約 | 真實回應3,242筆／125份 body-only JSON 全過 |
| 原始完整 inventory 隱私 | 診斷收集後、匯出前，150個實際 secret patterns 掃描原始報告通過；非只檢 redacted 副本 |
| 安全與靜態 | npm 全量／production／frontend audit0；CycloneDX1.6／144 components；OSV四輸入409 packages、0 findings；十契約靜態、actionlint、嚴格 noUnused E2E TypeScript 通過 |

Maven 保留既有 fork JVM 退出等候30秒提示；613個 XML 案例零錯誤且程序成功，不將提示稱案例失敗或略過。Vite103 modules、JS690.08kB／gzip191.51kB、CSS74.26kB／gzip12.03kB；500kB chunk advisory 保留。

HTTP bucket 實數：order507／25檔、acceptance71／17、vehicle197／11、driver315／11、tms519／17、execution202／13、progress_evidence400／11、exception306／8、completion322／7、closure403／5。回應數、瀏覽器案例數與 secret patterns 分別計算。

## 來源、升級與映像

41份原契約／Migration 精確 hash 不變；203份產品後端輸入與 M4-06 全等。263份 backend 只變兩個 test-only Java：global 三帳號 fixture 與 Platform 單一新增153行第16案例，移除 method 可精確還原原15案例及 helpers 原檔 hash。144份前端僅一份 scope CSS 變動，其他143原 bytes 保留。原16個瀏覽器 spec 不變；execution-ui.spec 僅新增手機幾何 helper 及一個 call，剝除新增部分可還原原檔，全部40既有流程／斷言保留。另固定68個 HTTP／E2E、8個 runtime／CI 輸入。

真 V9→V14 案例已在完整 verify 通過：26舊領域表正資料與 Flyway 共27表、legacy CLOSED 訂單，直接恰5 migrations 至47表。原 UUID、欄位、raw NUL／NFD、微小十進位、時間 offset、客戶快照、成功收據及 Flyway checksum 不變；20新表空、舊 reservation 新 completion marker 為 NULL。最新47表再次 validate／migrate0 無寫入、結構 seal 保留；不補造舊 M4 完成／結案見證。

PG17.12 空庫47表／14版本、零業務資料、backend 真重啟、TLS UP 與18匿名路由401全過。production JAR 的338 main classes 集合與 bytes、104前端產品輸入、14 migration bytes 與受測來源精確相符；無 support／fixture／test policy。最終 backend image `sha256:c6f225ec563228bd1ee670c42732e2620ff85ff09d12e30e07d3925a4299ebef`；frontend image `sha256:f1f818286ff24b38c23ef6ec0ddfd01c6e5fd32612a3a4663c257869d26d944d`；JAR SHA256 `c80cae3388471862967d92f3faaa6605814ac321af78933d5c0ffe27b19d8d7a`。

## 全量還原與正式預設拒絕

40 E2E 後，以真 HTTPS API 建 H（真 CLOSED）、P（完成待確認）、C（確認且 OPEN 可結案）、A（有效 AWAITING、照片及占用）四 cohort，同人車依真完成釋放後重用。停 writer，實際重啟同卷 PG／backend，PG17 custom dump 還原至另一名稱空 DB。在重啟後、還原啟動前、普通 JAR 啟動後、最後 source 均比較47表全欄 PK 排序 hash／count、Flyway／history、完整邏輯結構及67個實際私有照片物件 bytes／長度／SHA，全部相同。

結構含 CHECK／flags、函式、索引、FK／延遲性／validated、trigger 邏輯／enabled；只排除跨 DB catalog OID 與系統自生 RI trigger 名稱。僅 canonical 已觀測的兩種直接 `= ANY` literal-only unbounded varchar→text ARRAY 無損形式，值、順序、欄位、運算子、其他 SQL 與6項 constraint metadata 保留。未知／bounded varchar／函式／混合運算式／引號內假 ARRAY 不變；12反向測試確保真正差異不忽略。

錯 actual DB 的合成工廠拒啟且47表不變。正常服務用相同 production image、普通 java-jar，無 test classpath／provider，啟動無寫入，目前策略全 UNCONFIRMED。獨立合法前態驗新管理確認 E2103、新結案 E2103、新完成 E2003、有效私有照片 content E1803 無 bytes，未以錯前態／404代替。歷史 OPEN 原成功收據3、完成重播4、確認重播3、結案重播1，歷史 CLOSED 與資源重用後窄本人資料均保留。

## 真 Console、帳號與隱私

stdlib PTY、具名 one-shot Docker 及正式 JAR 執行 init／recover，真 Console guard 保留。restore 的普通 web JVM 持續運行：recover＋本人首次改密令 victim authVersion 恰＋2、兩舊 Session401、舊密碼失效、首次改密 barrier；control ADMIN Session、DRIVER 不受影響，舊 limiter namespace 不重用。

normal auth 的 account／lock 全列不變，audit 僅明列 append；recover／改密只允 victim 的 password_hash、must_change_password、temporary_password_expires_at、auth_version、version、updated_at 按兩操作變動，原 audit／其他帳號／所有非 UPMS、Flyway、照片與結構全等。另一 init DB 首次 ADMIN 恰1、version0、temporary15分鐘、重複 init 拒絕無寫入、首次改密及重登入通過。未以等待15分鐘冒稱到期實測，自動化合成操作者不冒稱真人責任核實。

fixture identities133，舊 UPMS 真 API 另新增2帳號，所以 source 帳號列135；init DB 的1帳號是另一 scope。150 secret patterns＝133 fixture＋12 browser 產生值＋4 Console／改密值＋1 DB 連線值。每新值使用前入完整 private inventory，兩 worker 有界鎖／0600 原子替換；並行12值 probe 無漏寫、raw NUL／NFD 基底保留、外人 lock 不刪、missing env 拒絕。診斷先收集，guard 掃原報告／test-results／artifacts／嵌入 ZIP，通過才匯出。

## 原畫面、證據與清理

workspace `/workspace/artifacts/lg-m4-07-evidence/` 保存最終125份 body-only JSON、78張安全合成 PNG、36份 JUnit、來源 hash／依賴報告／aggregate。選8張桌面／手機原圖：本人待出發、結案資源重用後本人完成、管理確認、整單結案。無 headers／cookies／密碼／hash 原列、dump、PTY 或照片 binary。手機五鈕各寬≥180 CSS px、高≥44、≥90%可用寬、同 x／同寬且依序不重疊；root 實看最終原 PNG 確認全寬單欄。這是 Chromium Pixel7 viewport，非實體手機／Safari。

演練 finally 清完 owned 額外容器、兩額外 DB、備份與私有 workdir；最後150原始 guard／匯出後，再實跑 cleanup-only 冪等通過，移除本包 source Compose／volume／私有 fixture、env、overlay、inventory、state 與 Xauthority。一般 DEV 四容器及資料卷保留；未 prune、不宣稱實體抹除。

## 誠實失敗歷史與完整重驗

1. 首輪40 E2E 全過，DR29.037秒因還原 schema 比對失敗；重啟47表／67照片全等。兩次限縮診斷證明只有29個 enum-like CHECK 反解形式不同，資料／設定／其他結構相同。加入窄 canonical＋12必要單元案例，產品／Migration 不改；146原始隱私通過才存 `first-drill-failure/`。
2. 第二新空庫40全過、還原47表／schema／68照片全等，DR59.018秒因工具過早快取 H 結案前 task 投影失敗。改於全部 cohort／H 結案／A 重用後擷取 current／history；正常模式只按真 service 次序改 policyMode UN、插入單一 POLICY_NOT_CONFIRMED，其餘版本／欄位／原因／收據精確保留。146隱私過才存 `second-drill-failure/`。
3. 第三新空庫40全過、DR130.939秒全部通過、HTTP3,241／125檔、150原始隱私通過。root 實看手機發現五鈕窄排換行，依既有手機單欄規格修唯一 scope CSS：≤680px 且僅 `.driver-execution-workspace` 全寬／44px，舊 FMS 共用樣式與桌面不變。加幾何斷言、原40流程保留；該次完整證據存 `pre-mobile-layout-pass/`。
4. 最終 CSS 再跑全部前端四項，第四新空庫完整40 E2E／DR／十 HTTP／150原始隱私全過，見首表。67及前輪68是各輪實際照片數，皆逐物件全比，未混稱同一輪。

所有失敗修正後以新空庫完整重驗，未略過或盲重跑斷言。PR／main 收據記完整 SHA、run／attempt 與實際遠端數量；[M4完整報告](m4-integration-acceptance.md)列前六包精確收據。正式資格、照片／保存、現場例外、安全及責任仍未核准，維持拒絕；無舊 repo 修改、M5 或正式部署。
