# LG-M3-05 調度工作台驗證

狀態：本機驗收、PR 與合併後 main 四項 CI 均完成。最終 source `997950eb1ee80982bc455e29e8a8d7ecb52cdcbd`，merge `7acbd43f5e098a877af6a1458e5b32d81e390f41`。

## 基準

前置 [PR #19](https://github.com/xk2022/xk-app-logistics/pull/19) source `2d35b7fbcf2846ff01f603c4fe6fbe0c19eb7247`，PR CI `45744058200` attempt 1 四項成功；merge `7180c1f8894baf0d6b878c435ce879863817f2ed` 的 main CI `45744753604` attempt 2 四項成功後，才建立本工作分支。

開工時核對公司最新規範 `xk2022/xplore-kaleidoscope@69b3df52247011ca0b1da6317e899044b9471a42`，已讀遷移後 06_engineering 的開發、測試、API、交付與部署，以及安全、AI 使用及維運入口；核心工程規則沿用原基準。AGENTS 引用更新到現行位置。保留 Q01–Q40、原五項 TOM／正式受理／FMS 與七項 TMS 契約、全部 migration 與依賴。

## 範圍與操作

管理端整合分頁任務、伺服器人車候選、占用、指派確認、撤改原因及歷史；本人頁單獨整合目前有效任務。操作規則見 [工作台規格](../07_ui-ux/dispatch-workspace.md) 與 [TMS 契約](../06_api/tms-dispatch.md)。新指派／改派的正式適格維持 UNCONFIRMED 拒絕；僅外掛 test-only 合成政策與隔離 DB 可驗正向流程，沒有政策核准介面。

未知結果保留原次請求，CSRF 更新不自動重送，歷史收據不作目前占用。權限失效清除敏感資料；本人頁重新讀取即隱去舊路線及聯絡資料，防止晚到回應回填。

## 實際檢查

| 項目 | 實際結果 |
| --- | --- |
| 前端 lint／typecheck／test／build | 全過；26 files／590 tests，原 426 保留；Vite 73 modules |
| 後端 Maven verify | 124 unit＋245 integration＝369 全過，0 failure／error／skip，修正後最終完整執行 5 分 49 秒；162 個後端來源檔 hash 在執行前後相同 |
| PostgreSQL／映像 | 隔離 V9／27 表空庫、全部業務表與派工表零種子、重啟一致性全過；backend 131 個產品來源與 M3-04 完全相同，重用已驗收映像；新 frontend 映像的 68 個產品檔逐 byte 驗證相同，202 個產品／建置輸入凍結 |
| 真實 HTTPS／E2E | 完整 28／28 通過，11 files、2 workers、0 retries、2.3 分鐘；原 26 保留，70 個私有合成帳號，桌機與 Pixel 7 真 UI／真後端／PG |
| 實際 HTTP 契約／隱私 | 五份全過：原 TOM 424／12 files、Acceptance 32／4、Vehicle 174／2、Driver 282／2、TMS 332／4，合計 1,244 筆／24 files；同批報告隱私檢查通過，沒有 fixture 帳密或 trace |
| 安全／工具 | root／frontend npm audit 均 0；含測試 SBOM 成功、OSV all-vulns 掃 409 套件無已知弱點；actionlint、全 E2E strict TS 通過；staged gitleaks 已通過，無 scanner 排除；完整 history 提交後以交付 PR 留存結果 |
| GitHub 交付 | [PR #20](https://github.com/xk2022/xk-app-logistics/pull/20)；[PR CI 45751323496](https://github.com/xk2022/xk-app-logistics/actions/runs/45751323496) attempt 2 四過；[main CI 45752348554](https://github.com/xk2022/xk-app-logistics/actions/runs/45752348554) attempt 1 四過，head 等於上述 merge，受測 source 與合併樹相同後才開始 M3-06 |

原 Driver account-conflict 焦點測試改為等待同一 toHaveFocus 斷言，避免錯誤文字進 DOM 與 passive effect 之間競速；未改產品或減少原斷言。整檔 focused 29／29 通過，後續完整前端 590／590、PR 及 main 四項 CI 也全過。

## 限制

正式派工適格與 D04／D05／D10 等原未決邊界保留。沒有 M4 出發、完成、司機回報、照片、結案、自動釋放、正式資料或部署。M3-06 僅在本包合併且 main CI 四項成功後開始。

## 狀態與競速回歸

新測試增加 164 項：API client 67、操作元件 18、model 36、本人任務 21、歷史 10、App 導覽 12。保留原測試與斷言；沒有新增 production 依賴或降低檢查。

獨立 review 後修正三種實際邊界並加入回歸：首次／未知後 CSRF_INVALID 都保留原命令；更新 CSRF 若明確授權失效則清敏感內容，不吞成可繼續的 CSRF；命令回應驗證本次正規化原因。歷史讀取晚回不得阻擋已凍結的原次重試，分離新操作的 stale 守衛與既有重試，同時 abort／忽略停用後晚回的歷史查詢。歷史收據與 fresh current 分開，讀取失敗維持禁止新操作。

Vite 成功建置時回報單一 JS 513.87 kB／gzip 144.32 kB，超過預設 500 kB 提示；未調高或關閉提示。這是套件切分／效能預算的後續評估，沒有已核准的效能數值門檻，不能當作效能驗收通過。

## 真實操作與畫面證據

`dispatch-ui.spec.ts` 在兩個 viewport 各跑一次完整場景：未受理不入列表，經訂單 UI 明確受理後出現 6 段任務、列表 5＋1 分頁；ADMIN 指派、DISPATCHER 改派、舊版本 409 保留原因，明確讀取／採用最新版本後撤派。伺服器阻擋占用、未核准候選與未就緒前置；7 筆歷史以 5＋2 真分頁並展開原生 details。

指派、改派與撤派各由真後端先提交，再由本機 TLS fault injector 丟棄回應；明確原 key／body／generation 重試取回相同完整結果。首個指派重試先經真 CSRF 403，仍維持同次凍結，不自動送出。本人頁驗有效派工、撤改後空頁、改綁後 404／資料隔離、401 與伺服器已完成登出但回應尚未交付時立即清除 DOM；沒有整單／資源／歷史入口。

每 viewport 保存確認、當前派工、歷史、本人與未知結果共 5 張實際畫面。Root 直接檢視 desktop-current 與 mobile-driver，獨立前端 review 亦檢視 mobile-confirmation／mobile-unknown／desktop-history，未見溢位或內容遮擋；全場景檢查可存取標籤、鍵盤操作與水平溢位。10 張截圖與 24 份只有 `{schema,value}` 的公開合成回應、migration／映像／來源 hash 摘要，保存於本次本機 `artifacts/lg-m3-05-evidence/`；CI 保留自身完整報告 14 天。不以截圖代替交易證據，不存 headers、Cookie、CSRF、key 或密碼。

實際 frontend 映像 `sha256:03d7f93910d5407970ca26f1cf57b6cb4f58215661177c611a42fa72efa7b134`；backend 重用 `sha256:597a2373e8c2f8d4aa2a5503ccc8be85788016734f16f2c77b45ead5c0b4206f`，因 131 個 backend 產品來源逐 byte 完全相同。正常 DEV 未掛載測試政策；完整驗收使用 `compose.e2e.yaml` 的隔離 launcher，業務資料只透過 API 建立。

同批報告隱私驗證後，已清除本包隔離容器／資料卷、私有 fixture／env／session overlay；一般 DEV 與受測映像、公開合成證據保留。

## 首輪 PR CI 的併發測試修正

[PR #20 首輪 CI 45749066236](https://github.com/xk2022/xk-app-logistics/actions/runs/45749066236) 的 frontend／security／Compose-E2E 成功；backend 只有既有 `UpmsIT.lastEnabledAdministratorCannotBeDisabledOrDemotedEvenWithConcurrentRequests` 失敗，兩回應為 `[200,403]`，原斷言僅接受 200／401／409。原產品在成功停用後撤銷 Session，較慢請求若尚未經 CSRF 可合法回 E0403；不修改該安全行為，不將失敗 run 改記成功，也不盲目重跑。

此測試改先持有 PostgreSQL `upms_lock`，以獨立 autocommit observer 實際確認兩個寫入交易都已通過 auth／CSRF 並等待 canonical lock，再釋放競爭；不是固定 sleep 猜排程，也未加寬原狀態集合。保留 17 個案例、前段兩個 409 與恰一成功／最後一個管理員，另核對回應 code／時區、winner／loser 身分、帳號與認證版本、目前 Session 及唯一 ACCOUNT_UPDATE 審計。Gate／observer／future 均有界，失敗 finally 先釋放 gate 再等 executor。

修正後 focused 5 unit＋17 integration 全過（1 分 03 秒）；修正後完整 verify 再次通過 124 unit＋245 integration＝369，0 failure／error／skip，5 分 49 秒。只有測試排程及斷言變更，202 個產品／建置輸入與 frontend／E2E 測試保持先前受測位元組，沒有業務例外或 API／migration／依賴變更。

最終 PR run 45751323496 attempt 1 僅 backend 在測試前遭 official Central plugin 429，其餘三項成功；同 head 只重跑失敗工作，attempt 2 四項成功。前一 source 的 run45749066236 併發測試失敗另保留於上述紀錄，不混記為下載失敗。合併前再查公司 `d03cd673c10a8f7c0427c558c562112aa4d34a8c`，APP 工程、安全與維運核心相同。普通 DEV 已套用同受測前端，可信 HTTPS 正常，V9／27表及原全部領域計數保留，正常 java-jar 且無測試政策掛載。
