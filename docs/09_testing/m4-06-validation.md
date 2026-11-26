# LG-M4-06｜司機執行工作台驗證

狀態：本機完整驗收通過，PR／main 遠端四項門檻待本包交付收據確認。基礎`a879f93a7bf6838dc46d2fb8d5fde554f7bd5ae7`，前包PR #27／補正 #28，最終main CI45823239922 attempt1四過；本包獨立分支feat/lg-m4-06-driver-execution-ui。公司最新f1be01c0c5d8bc5b1238f250838e336bef47b149品牌過渡決議已核對，工程規範不變。

[工作包](../08_development/m4-06-driver-ui-handoff.md)與[工程採用](../98_decisions/2026-11-26-m4-06-engineering.md)。保留41份既有API／migration精確hash、M1～M4-05全部案例；新client避免碰既有FMS driverApi。

後端263／main203輸入已凍結：只改test-only E2eFixtureMain新增10個獨立帳號、fixture總數130，不改產品後端；完整未篩選clean verify已完成：161unit／16suites＋451IT／20suites＝612，0failure／error／skip，14:03。263backend／203main受測hash仍一致。最終完整前端、真TLS E2E、十HTTP、初始化／重啟／同源byte proof與原始報告隱私均已實跑通過，詳細數字及先前失敗保留於下方。

正向只用隔離合成策略與私有測試provider；正式policy仍UNCONFIRMED。普通與補件PNG上傳／提交／完成的真提交後丟回應、CSRF後明確原次重試、兩版本單位、原文與照片生命週期、背景及失敗登出、完成後再用資源／整單結案與本人隔離為本包增量驗收。不得用fake業務回應或GET代替原次POST證明。

source／PR／merge及四CI可由[本包唯一head收據](https://github.com/xk2022/xk-app-logistics/pulls?q=is%3Apr+head%3Afeat%2Flg-m4-06-driver-execution-ui+base%3Amain)核對，合併後精確main四項成功才進M4-07。下列記錄實際數量、失敗修正與安全截圖位置；不將規劃或discovery當執行通過，不做M5或正式部署。

## 前期獨立檢查（保留當時 checkpoint）

登出／共用auth transport針對性10案例已通過，涵蓋aborted sender零fetch、CSRF等待後取消零POST、晚回不污染新Session token及原UPMS transport回歸；ESLint已過。這不是完整前端測試通過。十份契約static、actionlint、116MD／688相對連結及root全依賴／production與frontend npm audit零漏洞已過；當時真HTTP與瀏覽器仍未執行，最終結果見下方。

新舊root導覽／登出整合36項（4files）針對性測試全過，含新14個本人導覽、原12個派工導覽、原6個UPMS transport及新4個logout transport。API／model owner152項（135＋17）、strict TypeScript／noUnused／ESLint通過，獨立來源複核ACK；當時完整前端品質尚未執行，不能把以上數量相加當全量結果。

## 全前端首輪及相容性修正

完整lint／typecheck已過，Vitest首輪39files／1,083項中1,082過、1項原closureApi取消測試失敗，build因串接門檻尚未執行。共用CSRF helper新增abort檢查後會更早拒絕，原結案client將CSRF等待中取消分類為NETWORK_ERROR；沒有送POST，uncertain仍false。僅在原catch最前檢查已aborted並使用既有REQUEST_ABORTED分類；全部104原測試與新共用取消保護、非取消錯誤／body／key均不動。獨立來源複核、104 targeted及ESLint通過，144前端manifest僅更新closureApi一檔；完整四項重新執行後才記通過。首輪原log保留於frontend-first-csrf-abort.log。

## 完整前端最終結果

相容性補正後，lint／typecheck／1,083 tests（39files）／build全過，103 modules，JS690.08kB／gzip191.51kB；500kB chunk advisory保留，build成功。新增15項root本人導覽（含spinner回歸）亦在這輪全量實跑。144 frontend／263 backend／203 main／41既有API migration／63 HTTP-E2E／7系統輸入全部hash相符，整體與最後相容性delta來源複核均ACK。該前端run結束時隔離Docker映像建置中，真TLS／40E2E與HTTP仍待執行；後續最終實跑見下方，不能以discovery或本機unit代替。

## 首輪真TLS背景檢查與runner修正

完整40案首輪為38舊案通過、2新增UI案失敗，3.7m／0 retries。兩案都在真分頁切換後仍visible，正如guard要求而失敗；沒有fake visibility或跳過。130憑證值隱私檢查先通過，才保存114份body-only JSON與74張安全截圖到first-e2e-failure，然後清隔離容器／資料卷／三私有檔；普通DEV保留。首輪未當全量成功，也未對partial suite宣稱完整HTTP驗證。

Xvfb有視窗模式仍有Playwright預設focus emulation，另一CDPsession無法移除原session的模擬。以獨立原生Chromium＋官方connectOverCDP(noDefaults=true)預設context已真驗hidden→visible；private profile finally清理，無假事件或visibility override。將同一私有照片背景子檢查放在本人第3個正常Session，完成後正常登出，原主桌面／Pixel7七POST流程及全部斷言保留。CI用xvfb-run headed／2workers／0retries並明確備妥xauth。產品144／263／203與API／migration均未變，runner修正後須新空庫／130新fixture完整再驗，不能以bare probe代替完整E2E。

## 第二輪手機登入與報告隱私

第二輪39項通過、1項新增手機案在原生context登入前失敗，4.4m／0 retries；桌面完整七操作與真實hidden／latebytes已通過。未出現busy／錯誤且密碼仍在表單，定位為原生鍵盤焦點／提交前提未成立，不把它改成後端政策例外。原報告隱私guard正確拒絕：一個臨時憑證值出現在兩份失敗Markdown快照，未匯出或上傳原始報告。僅在明示sanitized副本替換已知憑證值後，原封不動的130值guard才通過；原始不安全report目錄已刪，保存original-report-privacy-REJECTED.log與redaction範圍，不冒稱原報告通過。

原生登入改為填憑證前先驗真前景／focus，再聚焦按鈕與Enter、觀察同源真POST回應；只有200／0000／DRIVER／本人id才可ready。填入密碼後不做會先快取ARIA的matcher斷言；失敗先清欄位並轉空白頁，才由主案判定。已實測Pixel7尺寸393×851的真登入、本人識別、登出及profile清理均成功；不假稱實體手機。產品144／263／203輸入完全未變，修正僅test helper／spec／README；嚴格noUnused與40案探索、獨立來源複核通過，第三輪仍必須新資料、完整40與未經redaction的原始report privacy通過。

## 第三輪與最終完整結果

第三輪38舊案過、2新增案失敗，4.2m／0 retries，原始130憑證隱私guard通過，保存third-e2e-failure。桌面是歷史GET尚在loading即讀原文的測試等待時序；改為等原欄位完整rawEqual，不減斷言。手機單獨登入通過、雙worker時失焦，獨立原生視窗改用各自Xvfb display，避免另一worker真bringToFront搶實體鍵盤；保留真hidden／visible與完整七POST，不用假事件或visibility override。產品、API與migration全未改。

第四輪使用新空庫／新130帳號，全17files／40項（桌面20、Pixel7 viewport20）通過，4.5m、2workers、0 retries；HTML內40 expected、0 unexpected／flaky／skipped。未經redaction的原始報告130憑證值guard通過後，才匯出116份body-only JSON及78張安全截圖。全部十份HTTP契約實驗證3,075筆：

| 契約 | 回應 | JSON檔 |
| --- | ---: | ---: |
| 原訂單 | 488 | 24 |
| 受理 | 66 | 16 |
| 車輛 | 196 | 10 |
| 司機 | 314 | 10 |
| 派工 | 500 | 16 |
| 出發執行 | 199 | 12 |
| 進度照片 | 378 | 10 |
| 異常補件 | 305 | 8 |
| 完成 | 280 | 6 |
| 管理確認結案 | 349 | 4 |

實際PG17.12空庫47表／14versions／零業務資料、backend重啟與TLS健康通過，18匿名路由401。production JAR共338 main class完整集合及bytes與本機受測輸入一致，無support／synthetic policy／fixture；104前端產品輸入與14migration bytes完全相符。映像backend0667d93e98b3a918b247a0c99a1ffcb24a100660b0ff0d5603e9ef5f2f35de18、frontend828105eee201a4bc450afc0e01bffecc887d6fa650e534060ce6b1108338563b。

root全依賴／production與frontend npm audit零；CycloneDX1.6實際144components，OSV四明確輸入共409packages（3＋255＋7＋144）零finding。staged／history機密掃描、actionlint與文件連結於commit前執行。沒有以套件掃描代替業務驗收。

安全證據位於workspace `/workspace/artifacts/lg-m4-06-evidence/`：frontend／backend／E2E／HTTP摘要、36份JUnit suites、來源manifest、116 JSON與78 PNG。桌面／手機主畫面為driver-execution-desktop.png、driver-execution-mobile.png；完成、原次恢復與結案後資源再用為driver-completed-recovery-desktop.png、driver-completed-recovery-mobile.png，均為真實後端畫面，保留失敗登出提示與業務結果的區別。無照片binary、帳號憑證、headers或可下載公開照片URL。

隔離Compose容器、owned資料卷、fixture／env／overlay及本包Xauthority清理，普通DEV四服務與資料卷保留。清理僅聲明本工作包可見檔案／資源移除，不聲稱實體安全抹除。

## 剩餘限制與交付門檻

正式派工資格、照片provider／保存、現場例外、安全釋放及管理確認／結案責任仍UNCONFIRMED，正常策略預設拒絕；正向只隔離合成。手機為Chromium Pixel7 viewport而非實體裝置，JS690.08kB為既有build advisory；無正式部署。獨立PR四CI及精確merge main四CI成功才開始M4-07，不將本機成功當遠端成功。
