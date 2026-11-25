# LG-M2-04 TOM 驗證｜2026-11-25

## 基準與範圍

[CRM PR #8](https://github.com/xk2022/xk-app-logistics/pull/8) 已合併至 `070383e1ff76dcaaf16693e8fcd8d4f5c0bb915c`；[main run 45563712633](https://github.com/xk2022/xk-app-logistics/actions/runs/45563712633) 四項全成功。從此 main 建立 `feat/lg-m2-04-tom`。公司最新規範仍為 `xk2022/xplore-kaleidoscope@5be640ab11811b24706101ea13db73185e9ce4d8`。

本包實作 OrderCore／TOM 訂單草稿、多項目、多段任務、依賴、客戶名稱歷史快照、聚合版本、成功重送、只讀完整性、Flyway V5 及 UPMS／CRM 整合。只提供原 OpenAPI 0.2.0 五操作，不提供正式受理／派工等轉態或完整 UI。規則見 [TOM API](../06_api/tom.md)、[模型](../05_database/tom-model.md)與[工程決策](../98_decisions/2026-11-25-tom-engineering.md)。

四份原契約來源及 V1–V4 保持原樣。來源中的歷史 `not_run`／`not_implemented` 並未改寫，實際成果由本文件與受測 SHA／CI 證據確認。

## 執行狀態

| 檢查 | 實際結果 |
| --- | --- |
| 原 schema／圖／角色／字典 | 16 正例、25 反例、11 graph、15 角色綁定、40 欄全部通過；四個 SHA-256 來源一致 |
| 新測試工具相容性 | CPython 3.12.14／Linux x86_64；七個固定版本及 wheel SHA-256 在乾淨 venv 以 require-hashes 離線安裝並通過契約案例 |
| 新測試依賴掃描 | fresh Maven SBOM 144 components；OSV 原 npm／Maven 402＋Python 7＝409 筆套件紀錄，0 已知漏洞；root／frontend npm audit 均 0 |
| Session alias 編譯 | Java 21／Boot 4.1.3／Tomcat 11.0.27 編譯與真實 TLS HTTP J-only 流程、輪換、衝突恢復、撤銷後匿名 CSRF 復原通過；tracking guard 拒 URL／混合模式 |
| 前端 lint／typecheck／test／build | lint／typecheck／77 項測試／build 全過，前端產品碼未修改 |
| Maven verify | 最新完整 211／211 通過：單元 88（原 7＋TOM 81）；整合 123（TOM 64、Platform 6、ADM 16、CRM 20、UPMS 17）；0 失敗／錯誤／略過，保留原 65 |
| PostgreSQL 空庫／升級／交易 | 隔離 Compose 空庫 V1–V5／19 表／TOM 七表與帳號／CRM／參數零資料、HTTPS 通過；V4→V5 保留 UPMS／ADM／CRM 舊資料及完整交易測試通過 |
| 同源 HTTPS E2E | 最終乾淨環境單次 12／12 通過（35.3 秒）；保留原 10，新增 TOM 桌面／Pixel 7 各一例 |
| 實際 HTTP 原 schema | 120／120 真實回應全過（桌面／手機各 60）：OrderEnvelope 56、OrderPageEnvelope 8、AcceptanceCheckEnvelope 14、ErrorEnvelope 42；format checks 啟用 |
| 報告隱私 | fixture 憑證與 trace 隱私檢查通過；認證 trace 關閉，response JSON 只有合成業務 data，沒有 Cookie／CSRF／headers／密碼 |
| 靜態與秘密 | actionlint、CI shell 語法、E2E strict TypeScript、相對連結及 diff whitespace 通過；staged gitleaks 無洩漏，沒有提交 fixture 憑證／TLS 私鑰／產出檔 |
| GitHub CI／交付 | [PR #9](https://github.com/xk2022/xk-app-logistics/pull/9) 首輪四項全 success；受測 SHA／run 詳見下節 |

## 必要情境

- IMPORT／EXPORT／LOCAL 的原三類合成資料，包含多項目、多段與同單跨項目前置，禁止重複 ref／序號、失聯／自指／環及逆向時間。
- 完整 PUT 的省略／null／空物件、保 ref UUID/taskNo、序號交換、任務同單移項目、移除及重建 ref、不可變版本、保護 FK 與中途失敗原子回滾。
- safe integer 範圍、數學整數 canonical、十進位精度／未知零、日期與 ±23:59 offset／六位微秒、原 Unicode／空字串／NUL，不做隱性 NFC 或截斷。
- 同 key 同 body 重播原 201／200、Location／data，fresh traceId，不增加版本／歷程；property 順序及整數等價、陣列／null／省略差異、key 跨 actor／method／path 隔離、同 key 處理中與不同 body 409。
- JDBC 4.2 OffsetDateTime 綁定保留 proleptic Gregorian 瞬間；真 PostgreSQL EXTRACT(epoch) 驗 1500 年、±23:59 offset 與 123456 微秒，不以 java.sql.Timestamp 的對稱讀寫掩蓋 Gregorian cutover 位移。
- 真實 PostgreSQL 中客戶停用／建立競爭、新引用同交易、主檔改名不改歷史、相同停用客戶可編輯舊草稿、明確換客戶才取新快照；重播不先重驗已舊 expectedVersion 或今日客戶狀態。
- 三角色五操作完整矩陣；未登入、強制改密、失效／撤銷、CSRF、TLS；JSESSIONID-only 真容器 Session、同 ID alias、輪換／登出、衝突 Cookie 及 URL Session 拒絕。全部錯誤保持原 TOM envelope。
- 每個 acceptance-check 對同一版聚合執行，missingFields 對應原欄位與規則、COMPLETE／INCOMPLETE 正反例、pendingPolicyIds 及 NOT_GRANTED；不修改狀態、版本或產生派工。

## CI 與交付追蹤

- 工程受測 commit：`4cbe2cecafdbf9d645dd68dca884dccff121dd6e`，交付 [PR #9](https://github.com/xk2022/xk-app-logistics/pull/9)。
- 首輪 [Application quality run 45583679300](https://github.com/xk2022/xk-app-logistics/actions/runs/45583679300) 四項全部 success、attempt 1：Frontend quality、Backend verify、Dependency and secret checks、Compose and browser smoke。使用儲存庫原始 Dockerfile，前端 77／後端 211／E2E 12／實際 HTTP schema 120 全部通過。
- GitHub ubuntu-24.04 的 CPython 3.12.3 以相同 wheel hash 成功安裝七個套件並通過全部固定／實際契約檢查，與本機 CPython 3.12.14 的同 3.12 ABI 相容性已實測，不以假定代替記錄。
- 本機完整 16 commits 的 gitleaks 無洩漏；正式 JAR 不含 fixture、測試資料或診斷入口。秘密掃描曾把公有契約 digest 誤認為 key，已改用標準 sha256sum manifest 表示，同一組 digest／掃描規則保持原樣，沒有關閉或排除安全檢查。
- 隔離 TOM E2E 資料卷及私有 0600 fixture 憑證已清理。一般 DEV 的原 V4 資料庫升級 V5、十九表及可信本機 TLS 狀態通過；帳號／CRM 客戶／TOM 七表／參數定義與參數仍全 0，未混入測試資料。
- 本機 Cloud 最終 backend image 為 `sha256:730f00df6c83f384a6ca5707c3cfc0a6befb3ab28e8d3690f890813e83a0ed39`，只作 DEV 驗收。Cloud 的暫存 Dockerfile／Compose overlay 用 BuildKit secret 掛載專用 Maven 代理與 CA，設定不入庫或映像層；遠端 CI 使用原始 Dockerfile。沒有發佈或正式部署映像。
- 本次文件提交只補錄證據；最終 head 及合併後 main 仍執行相同四項檢查。最新 head／run／merge SHA 以 PR 與 GitHub 執行紀錄為準，四項全成功才合併，未略過測試或降低門檻。

## 可重現

一般啟動與受控首次管理員見 [README](../../README.md)，隔離 HTTPS 驗收見 [E2E README](../../tests/e2e/README.md)。TOM 不加入前端操作入口；完整訂單 UI 留至 M2-05。

[契約檢查工具](../../tests/contracts/README.md)使用原 OpenAPI、固定合成案例及 browser context 的實際回應；七個測試用套件為 PyYAML 6.0.3、jsonschema 4.26.0、jsonschema-specifications 2025.9.1、referencing 0.37.0、attrs 26.1.0、rpds-py 2026.6.3、typing-extensions 4.16.0。所有 wheel 及 transitive 版本固定，生產 Maven/npm 依賴未增加。CI 使用相同 requirements 並納入 OSV。

正常 V5 不種帳號、客戶或訂單。所有驗證只用隔離 PostgreSQL、Testcontainers 或指定 xk_logistics_e2e；fixtures 不進產品 jar／一般 DEV volume。認證 trace 關閉，報告經 fixture 憑證隱私檢查才可上傳；不得記錄 Cookie／CSRF／密碼。

## 剩餘界線

D04 字典／聯絡／重量／櫃號／船務與期限例外、D05 義務免除及 D10 保存／清理政策尚未核准。本次不擅定枚舉或現場必填、不自動過期重送鍵，不宣稱永久保存已獲核准。UPMS 單 instance Session／限流限制延續。正式可信代理、最小 DB 權限、備份還原、維運責任與覆蓋率數值門檻仍需獨立驗收。

沒有正式受理、派車、司機回報、照片、結案、正式資料或部署。M2-05 才提供完整訂單操作 UI。
