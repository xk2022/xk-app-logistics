# M4 整合驗收報告

M4-01～06已完成獨立PR、合併及精確main四項CI；M4-07最終全部本機整合門檻通過，其遠端收據由[唯一head PR](https://github.com/xk2022/xk-app-logistics/pulls?q=is%3Apr+head%3Atest%2Flg-m4-07-integration-acceptance+base%3Amain)記錄。只有該PR及精確main四項成功後才算七包全部交付，不以前包結果代替。M1～M3舊API／回歸保留，正常未核准策略維持拒絕。

## 已交付工作包的精確收據

以下為已完成的遠端結果；M4-07自身以唯一head收據避免自我SHA循環，最終workspace報告另列完整source／merge值。CI欄為GitHub Actions run ID／attempt，全部成功是四項工作均成功；M4-05原main失敗明列，補正後才開始M4-06。

| 工作包／PR | Source SHA | Merge SHA | PR CI／attempt | main CI／attempt |
| --- | --- | --- | --- | --- |
| M4-01／[#23](https://github.com/xk2022/xk-app-logistics/pull/23) | `71dac55cdf568ddb6f46c043c0f74d81d3fafe07` | `daf26f979c1c03c078876f204a713578abe80a4e` | 45768556588／1 四過 | 45769255127／1 四過 |
| M4-02／[#24](https://github.com/xk2022/xk-app-logistics/pull/24) | `b9647dfe4430e8d088362331c92d31de405c44a5` | `4681f13b4dd1dc324632848e7b119147e0e43068` | 45775507970／1 四過 | 45776966300／1 四過 |
| M4-03／[#25](https://github.com/xk2022/xk-app-logistics/pull/25) | `508e1e41174623e39c25352d1ae7d77df3692c99` | `e55ee4ecdf98d6f314b60e45c96b0b18d9e5489e` | 45787301633／1 四過 | 45788390226／1 四過 |
| M4-04／[#26](https://github.com/xk2022/xk-app-logistics/pull/26) | `5112ae5842bd16c7a2f50c41cdc5ef83136a1534` | `a027dd1e38279449b6843fead9633236aa164b64` | 45798797894／1 四過 | 45800016961／2 四過 |
| M4-05／[#27](https://github.com/xk2022/xk-app-logistics/pull/27) | `7625d2817db6bbff036f7982c36a34705ca4cb00` | `28b26cd4068cfda642cb6ae4cf565884891491c0` | 45816721104／1 四過 | 45818321672／1（原main失敗，非最終交付） |
| M4-05 補正／[#28](https://github.com/xk2022/xk-app-logistics/pull/28) | `7324252264c2de39f1facc61fa77fd3a1e95d496` | `a879f93a7bf6838dc46d2fb8d5fde554f7bd5ae7` | 45821675514／1 四過 | 45823239922／1 四過 |
| M4-06／[#29](https://github.com/xk2022/xk-app-logistics/pull/29) | `3709bb5ce91aaf1ae05735eb90892e9e4ed7318e` | `e7c8acf1351caf379b7871c8078646671b307745` | 45844612553／2 四過 | 45847696751／1 四過 |

M4-04 main首輪及M4-06 PR首輪為Maven Central429測試前下載失敗，整輪結束後相同SHA僅重跑失敗工作；M4-05 focus時序失敗以獨立補正PR處理，原斷言保留，不將其當下載失敗重跑。所有失敗紀錄保留於各包驗證。

## M4-07 最終全量本機驗收

| 驗收項 | 最終實際結果 |
| --- | --- |
| 完整前端四項 | lint／typecheck／1,083 tests／39files／build，全過；手機CSS後完整重驗 |
| 未篩選Maven clean verify | 161unit＋452IT＝613／36suites，0failure／error／skip、12:56 |
| V9直接升V14 | 第16案通過；原15案／helpers精確保留、26舊領域表與Flyway不變、20新表空 |
| 空庫／重啟／映像 | PG17.12／47表／14versions、338正式classes／104前端輸入／14migration bytes精確同源 |
| 真桌面／手機E2E | 最終40／17files，2workers、0retries、4.6m；20各viewport、0flaky／unexpected／skipped；手機五鈕全寬≥180×44且不重疊 |
| populated restart及不同DB還原 | 真PG／backend重啟、完整custom dump／restore、普通Jar啟動零寫入及最後source，47表／完整邏輯schema／history／67照片全等 |
| 正常預設拒絕 | 真合法前態新確認／結案E2103、完成E2003、私有照片E1803無bytes；錯actual DB合成工廠拒啟、47不變 |
| 歷史／原次一致性 | OPEN收據3／完成重播4／確認重播3／結案重播1，歷史CLOSED與資源重用後本人窄資料保留 |
| 真Console | production Jar真PTY init／recover；authVersion恰＋2、兩舊Session撤銷、control／DRIVER隔離、首次改密、重複init拒絕、business不變 |
| 十HTTP／原始隱私 | 3,242真回應／125body-only JSON，150實際secret patterns原始guard全過後才匯出 |
| 工具／安全 | CHECK窄canonical12反向units必跑；npm0／OSV409packages0findings；actionlint／strictE2E TS／十靜態契約全過 |
| 證據／清理 | 78安全PNG＋8精選原桌面手機畫面；owned額外DB／runtime／dump／work、sourceCompose／volume及private files全部清理，一般DEV保留 |
| PR／精確main四CI | 由本包唯一head PR記完整source／merge／run／attempt與遠端實數；本機結果不代遠端 |

完整工具失敗歷史、修正範圍、最終映像與各bucket實數見[M4-07驗證](m4-07-validation.md)。前兩次DR工具失敗各保存原隱私通過證據；第三輪全過後真手機畫面發現窄鈕，修唯一scope CSS並新增可還原的bbox helper，第四新空庫完整重驗全過127.598秒。不能將第三輪130.939秒／68照片與最終127.598秒／67照片混算。

最終workspace證據 `/workspace/artifacts/lg-m4-07-evidence/` 含125 JSON、78 PNG、36 JUnit、source-proof、依賴／安全摘要及最終原畫面；全M4最終literal source／merge／CI收據另由交付報告列出。原dump、credential／hash rows、headers、PTY與原照片bytes不匯出。測試fixture133、source實際135accounts、另一init DB1account、最終150secret patterns各自計算。

## 正式限制與剩餘風險

正式派工資格、照片provider／保存及現場例外、安全釋放與管理確認／結案責任未核准，正常策略維持UNCONFIRMED拒絕。正向證據只屬隔離合成資料與actual-name-gated測試工廠，不能作正式業務放行。無正式客戶資料、舊repo修改、M5或正式部署。

UPMS Session／limiter仍為既有單instance設計，還原及跨CLI的authVersion證明不代表分散式部署驗收。備份還原僅邏輯app DB／PG17工具範圍，不證cluster-global roles／ACL、正式RTO／RPO或實體安全抹除。手機為Chromium Pixel7 viewport，非實體裝置；JS690.08kB／gzip191.51kB有500kB chunk advisory但build成功。Central下載偶有429，實際失敗與同SHA重跑分開記錄，未略過品質門檻。

## 逐包本機驗收數量

各數量是該包完整套件，不將多包累加當不同案例；E2E均保留舊案例且0 retries，HTTP是實際JSON回應驗證筆數，與測試案例數分開。

| 工作包 | 前端 | 後端 | 桌面＋手機 E2E | 本機 HTTP | PR／main HTTP |
| --- | ---: | ---: | ---: | ---: | --- |
| M4-01 | 590 | 418 | 30 | 1,489 | 1,490／1,490 |
| M4-02 | 590 | 469 | 32 | 1,731 | 1,733／1,734 |
| M4-03 | 718 | 530 | 34 | 1,949 | 1,949／1,947 |
| M4-04 | 718 | 566 | 36 | 2,244 | 2,244／2,244 |
| M4-05（含補正 #28） | 883 | 612 | 38 | 2,686 | 2,686／2,687（最終補正） |
| M4-06 | 1,083 | 612 | 40 | 3,075 | 3,083／3,078 |
| M4-07 | 1,083 | 613 | 40（最終0 retries） | 3,242／125檔 | 本包唯一head PR記實數 |

M4-07另有12個必跑stdlib CHECK比對反向單元測試，獨立於前後端及40個瀏覽器案例，不能將其算成52個瀏覽器案例。

前置規格[PR #22](https://github.com/xk2022/xk-app-logistics/pull/22)：source `53e308ac40911b7b0507376595b56cd0d0ea3b55`／merge `6e83663eac50256592e35713467d496cc5765235`，PR CI45759432687及main CI45760194712 attempt1四過後才開M4-01。
