# LG-M2-05 訂單工作台驗證｜2026-11-25

## 基準與範圍

基於已合併 [PR #9](https://github.com/xk2022/xk-app-logistics/pull/9) 的 main `0766f703848719064c3e1a4488ec18d0a970b7ff`；前置 [main CI 45588635566](https://github.com/xk2022/xk-app-logistics/actions/runs/45588635566) 四項全部成功。公司最新規範為 `xk2022/xplore-kaleidoscope@5be640ab11811b24706101ea13db73185e9ce4d8`，已核對遠端與本機一致。

本包提供 ADMIN／DISPATCHER 訂單工作台、列表、新增、完整編輯、有效客戶查詢、多項目、多段任務與依賴、只讀完整性、版本衝突及原次保存重試。DRIVER 無整單入口或存取權。沿原五項 TOM API、Session／CSRF、V1–V5；不修改產品後端、原 API 契約或四份固定來源，不新增 migration。測試帳號準備只在 test classpath 擴充，無正常 seed。

[UI 規格](../07_ui-ux/order-workspace.md)、[工程決策](../98_decisions/2026-11-25-tom-ui-engineering.md)及[工作包](../08_development/m2-05-order-ui-handoff.md)描述行為。本文件於實際執行後更新結果；尚未執行的項目不視為通過。

## 執行狀態

| 檢查 | 實際結果 |
| --- | --- |
| 前端 lint／typecheck／test／build | 全部通過；11 files／155 tests，保留原 77；新增 API 33、工作台 13、Editor 24、Tasks 8 |
| Maven verify／原 211 項回歸 | 211／211 通過：單元 88、整合 123；零失敗／錯誤／略過，保留 UPMS／ADM／CRM／TOM |
| 真實 PostgreSQL／HTTPS 桌面與 Pixel 7 E2E | 最終全新隔離環境 18／18、1.1 分鐘、0 retry／skip；原 12 保留，新增 UI 每 viewport 3；桌面 1280×720、Pixel 7 412×839（DSF 2.625） |
| 原 schema／固定案例／實際 HTTP 回應 | 四個來源 SHA-256 一致；16 正例／25 反例／11 graph／15 role／40 欄通過；8 份 artifact、296 真實回應全過：Order 134、Page 74、Acceptance 28、Error 60，format checks 啟用 |
| 相依安全 | root／frontend npm audit 各 0；fresh Maven SBOM 144 components；OSV 四份 lock／SBOM 共 409 筆套件，0 已知漏洞；版本與鎖檔不變 |
| 秘密及報告隱私檢查 | 瀏覽器 artifact 隱私檢查通過，無 fixture 憑證／trace；staged 及完整 history（19 commits）gitleaks 均無洩漏，沒有規則排除 |
| GitHub CI／合併 | 最終 head 四項全成功才合併，合併後 main 再核對；最終 SHA／run／merge 與結果以交付 PR／GitHub 執行紀錄追蹤 |

## 必要流程與收斂

- ADMIN、DISPATCHER 各操作 IMPORT／EXPORT／LOCAL，真實建立、讀回與完整 PUT；多項目、多段、跨同單項目前置、重排後 refs／UUID／taskNo、客戶篩選與實際 5＋1 分頁均通過。DRIVER 無導覽、無訂單資料請求；五操作全部 403。
- 原生 DOM 不適合呈現的 NUL／CRLF 仍在未改欄位原樣保存；十進位字串、0 與未知、原 offset／六位微秒、shipping／siteContact null 與空物件、停用客戶及改名前的歷史快照皆有回歸。
- 版本衝突保留本機文字及 refs，沒有自動覆寫或重送；先讀完整伺服器版本、比較並明確二次確認 reload 或保留本機內容再保存。已存完整性與本機修改清楚區分；dirty／stale 禁止舊 index 跳轉，其他缺漏可定位到父區塊或展開的識別區，不變更草稿。
- POST、PUT 均經真後端成功提交後中止瀏覽器回應，再以錯誤 CSRF 送到真後端取得 403，最後用原 key／body 經瀏覽器成功 replay；原 UUID／版本及一次副作用不變。第一次已知拒絕與曾未知後的拒絕分開處理，沒有自動第 4 次請求。
- 獨立覆核曾發現舊版本比較可清 pending、以及未知保存之後 CSRF 拒絕可能誤解為先前未提交，均在最終版修復並加回歸。首次收斂的 18／18、284 responses 已通過；上述修正後重新建立空庫與 fixture，最終 18／18、296 responses 才作本次驗收依據。
- E2E strict TypeScript、actionlint、相對連結與 whitespace 通過；目前產品後端、所有生產相依與鎖檔、原四份契約及 V1–V5 全部不變。未增加套件或使用第三方 UI／字體服務。

本機最終前端映像為 `sha256:cae3d09fc20ef9243cbb4ae6ab35b1cd87c2e8c0c348d06581a70a80b786abdf`，其 31 個產品原始檔已與工作分支逐檔 SHA-256 比對一致。後端沿用已驗證的 M2-04 DEV 映像，產品程式未變；本機 Cloud 的 CA／proxy 掛載只在暫存 Dockerfile／Compose overlay，沒有入庫或映像層。遠端 CI 仍以儲存庫原始 Dockerfile 重建與重跑。

桌面／手機的列表、編輯、任務依賴、版本衝突、未知保存及歷史客戶共十二張合成畫面留作實際視覺證據；衝突與未知保存四張為最終 E2E 現場，其餘可重讀畫面以相同合成資料只讀重拍；功能報告與 response artifacts 未覆寫。功能與 schema 判定使用上述測試結果，不以截圖代替測試。

## 隔離與可重現

執行 [E2E README](../../tests/e2e/README.md) 與 repo 品質命令。一般 DEV 資料庫不放測試帳號／客戶／訂單。UI 使用獨立測試帳號群，避免原 UPMS／ADM／CRM／TOM 測試的改密、撤銷及 Session 數量互相干擾；每個情境建立自己的合成客戶。

本機另用 Compose project `xk-logistics-m2-05-e2e`、`xk_logistics_e2e` 資料庫及 HTTPS 8444／frontend 5174／backend 8086／PostgreSQL 5433，保留一般 DEV 8443／5432。fixture 私有檔置於報告目錄外，trace 關閉，報告經隱私檢查後才保留。驗收完成只移除指定隔離 project 的容器／資料卷及其 fixture，不清除一般 DEV 資料。

隔離驗收完成後，指定 project 容器／資料卷及私有 fixture／env 已清理。一般 DEV 只更新 frontend／gateway，HTTPS 狀態 UP；正常 `xk_logistics` 的帳號、CRM 客戶與訂單仍均 0，未混入合成測試資料。

正式 JAR 不含 fixture。截圖及 response artifacts 僅有合成業務資料，不記錄 Cookie、CSRF、密碼或原始認證 headers；不把本機代理、私鑰、環境檔與暫存 Cloud Dockerfile 入庫。

## 交付與 M2 狀態

M2-01 至 M2-05 的實作與本機首片驗收已完成；不代表 M3 正式受理或運輸執行已開發。交付合併仍以本次交付 PR 的最終受測 head 四項 CI 全成功為門檻，合併後再核對 main 的相同四項結果。最終 commit／run／merge SHA 在交付 PR 與 GitHub 歷史追蹤，避免用尚未執行的檢查作完成證據。

## 剩餘界線

D04／D05 業務例外及 D10 保存／清理政策尚未核准。完整性 COMPLETE 仍不是正式受理；本包沒有正式受理、派車、司機回報、照片、結案、FMS 或正式部署。UPMS 單 instance Session／限流限制延續；正式可信代理、最小 DB 權限、備份還原、維運責任與覆蓋率數值門檻需獨立驗收。Maven Central 的共享出口限流可能再發，既有後端 CI 使用官方替代端點及 strict checksums，不降低品質門檻。
