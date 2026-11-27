# XK Logistics v2.0｜貨運物流管理平台

| 項目 | 說明 |
| --- | --- |
| Repository | xk2022/xk-app-logistics |
| 類型 | xk-app（Web 應用系統） |
| 狀態 | development — M4 已交付；目前先交付離線 Showcase Demo，完整 M5 暫緩 |
| 主要使用者 | 貨運公司管理員、調度員、司機 |
| 核心用途 | 貨運訂單、貨櫃、分段任務、派工、回報與結案 |
| 技術基準 | React / TypeScript / Vite、Java 21、Spring Boot、PostgreSQL 17、Flyway |
| 正式網址 | 尚未部署 |
| Showcase 入口 | [XK Logistics Demo](https://showcase.xplorekaleidoscope.com/logistics/)（既有入口；互動版本發布以交付收據為準） |

## 文件與開發

- [文件總索引](docs/README.md)
- [Showcase 操作手冊](docs/07_ui-ux/showcase-demo.md) · [採用範圍](docs/98_decisions/2026-11-27-showcase-demo.md) · [實測與交付收據](docs/09_testing/showcase-demo-validation.md)
- [M4-07 整合交接](docs/08_development/m4-07-integration-handoff.md) · [驗證](docs/09_testing/m4-07-validation.md) · [M4 整合報告](docs/09_testing/m4-integration-acceptance.md)
- [司機執行工作台](docs/08_development/m4-06-driver-ui-handoff.md) · [工程採用](docs/98_decisions/2026-11-26-m4-06-engineering.md) · [驗證](docs/09_testing/m4-06-validation.md)
- [管理確認及結案](docs/08_development/m4-05-management-closure-handoff.md) · [API](docs/06_api/tom-closure.md) · [驗證](docs/09_testing/m4-05-validation.md)
- [M4 七包交接](docs/08_development/m4-execution-handoff.md) · [M4-01 交接](docs/08_development/m4-01-driver-departure-handoff.md) · [M4-01 驗證](docs/09_testing/m4-01-validation.md)
- [M4 工程定義](docs/98_decisions/2026-11-26-m4-01-engineering.md) · [M4 API](docs/06_api/tms-execution.md)
- [M3 整合驗收報告](docs/09_testing/m3-integration-acceptance.md) · [M3-06 驗證](docs/09_testing/m3-06-validation.md)
- [調度工作台交接](docs/08_development/m3-05-dispatch-ui-handoff.md) · [M3-05 驗證](docs/09_testing/m3-05-validation.md)
- [TMS 派工交接](docs/08_development/m3-04-dispatch-handoff.md) · [TMS API／OpenAPI](docs/06_api/tms-dispatch.md)
- [派工工程決策](docs/98_decisions/2026-11-26-tms-dispatch-engineering.md) · [M3-04 驗證](docs/09_testing/m3-04-validation.md)
- [正式受理交接](docs/08_development/m3-03-tom-acceptance-handoff.md) · [受理 API／OpenAPI](docs/06_api/tom-acceptance.md)
- [受理工程決策](docs/98_decisions/2026-11-26-tom-acceptance-engineering.md) · [M3-03 驗證](docs/09_testing/m3-03-validation.md)
- [FMS 司機交接](docs/08_development/m3-02-fms-driver-handoff.md) · [司機 API／OpenAPI](docs/06_api/fms-drivers.md)
- [司機工程決策](docs/98_decisions/2026-11-25-fms-driver-engineering.md) · [M3-02 驗證](docs/09_testing/m3-02-validation.md)
- [FMS 車輛交接](docs/08_development/m3-01-fms-vehicle-handoff.md) · [車輛 API／OpenAPI](docs/06_api/fms-vehicles.md)
- [車輛工程決策](docs/98_decisions/2026-11-25-fms-vehicle-engineering.md) · [M3-01 驗證](docs/09_testing/m3-01-validation.md)
- [產品需求](docs/01_product/prd.md)
- [MVP 與路線圖](docs/01_product/mvp-roadmap.md)
- [開發工作包](docs/08_development/development-plan.md)
- [訂單工作台規格](docs/07_ui-ux/order-workspace.md) · [M2-05 交接](docs/08_development/m2-05-order-ui-handoff.md)
- [M2-05 驗證](docs/09_testing/m2-05-validation.md)
- [TOM 工程交接](docs/08_development/m2-04-tom-handoff.md)
- [TOM API 整合](docs/06_api/tom.md) · [原 OpenAPI 0.2.0](docs/06_api/openapi.yaml)
- [TOM 工程決策](docs/98_decisions/2026-11-25-tom-engineering.md)
- [CRM 工程交接](docs/08_development/m2-03-crm-handoff.md)
- [CRM API 契約](docs/06_api/crm.md) · [獨立 OpenAPI](docs/06_api/crm-openapi.yaml)
- [CRM 工程決策](docs/98_decisions/2026-11-25-crm-engineering.md)
- [ADM 工程交接](docs/08_development/m2-02-adm-handoff.md)
- [ADM API 契約](docs/06_api/adm.md) · [獨立 OpenAPI](docs/06_api/adm-openapi.yaml)
- [ADM 工程決策](docs/98_decisions/2026-11-25-adm-engineering.md)
- [UPMS 工程交接](docs/08_development/m2-01-upms-handoff.md)
- [UPMS 契約](docs/06_api/upms.md)
- [UPMS 工程決策](docs/98_decisions/2026-11-25-upms-engineering.md)
- [協作指引](AGENTS.md)

## 環境與版本

需要 Node.js 24.22.3／npm 11.19.1、Java 21、Docker Engine 與 Compose v2。Maven Wrapper 固定 Maven 3.9.16；React 19.3.1、TypeScript 6.0.3、Vite 8.3.8、Spring Boot 4.1.3、PostgreSQL 17.12。精確相依、官方相容性與公司規範基準見 [M1 版本紀錄](docs/04_architecture/m1-versions.md)。

Monorepo 採 Modular Monolith。`frontend/` 提供登入、本人密碼、角色工作台與 ADMIN 帳號管理；`backend/` 的 `platform` 提供工程端點，`upms` 負責帳號、固定角色、認證與稽核；`adm` 負責字典、核准參數與異動紀錄，透過 UPMS 公開授權介面核對身分；`crm` 提供客戶、聯絡人、常用地址與可信 server 共用查詢。`tom` 的 OrderCore 與訂單草稿聚合保存項目、任務、依賴、客戶歷史快照、版本與成功重送紀錄，提供原五項 API 與獨立正式受理操作。`fms` 提供車輛與司機主檔、受控分類選項、DRIVER 本人唯讀介面及可信行政狀態查詢。`tms` 透過 TOM／FMS／UPMS 公開介面實作分段派工、原子資源占用、撤改、歷史及本人最小任務摘要；各模組透過介面協作，不跨模組操作 repository。

## 啟動

```sh
cp .env.example .env
# 編輯 .env，設定只用於本機的 POSTGRES_PASSWORD
# Docker Compose 會讀取 .env；不要提交此檔案
./infrastructure/generate-dev-tls.sh
docker compose -f compose.yaml -f compose.dev.yaml up -d --build --wait
```

開啟 **https://localhost:8443**。DEV 憑證由本機生成、有效 30 天，只用於本機驗收；瀏覽器首次需要信任該本機憑證。登入使用同源 HTTPS，Session Cookie 強制 Secure／HttpOnly／SameSite=Lax；HTTP 5173 僅保留工程檢查，不作帳號操作。後端 http://localhost:8084/actuator/health 僅回報健康狀態，`/api/v1/status` 為同源前端整合端點。資料庫容器就緒後，後端啟動時由 Flyway 建立 `logistics` schema、V1 工程基線、V2 UPMS 帳號／稽核、V3 ADM、V4 CRM、V5 OrderCore／TOM、V6 FMS 車輛、V7 FMS 司機、V8 TOM 不可變受理事件、V9 TMS 派工／占用、V10 獨立執行／事件／成功收據、V11 私有照片／選照關聯／原上傳收據、V12 異常與補件、V13 完成及有效解除、V14 管理確認及訂單結案結構，再由 Hibernate validate；沒有預設帳號、固定密碼或客戶種子資料。

以下主機執行方式只供工程偵錯。完整登入仍使用上述 HTTPS Compose；若自行組合主機前後端，須另接同源 TLS 代理，不可降低 Cookie 安全設定：

```sh
docker compose -f compose.yaml -f compose.dev.yaml up -d --wait db
# 終端一：設定 DB_PASSWORD，值與 .env 的 POSTGRES_PASSWORD 相同
cd backend
export DB_URL=jdbc:postgresql://localhost:5432/xk_logistics
export DB_USER=xk_dev
# 以本機環境或機密工具設定 DB_PASSWORD
./mvnw spring-boot:run
```

```sh
# 終端二
cd frontend
npm ci
npm run dev
```

Vite 將 `/api` 代理至 `http://localhost:8084`；容器透過 `API_PROXY_TARGET` 指向 backend。變更主機 port 時一併設定 DB_URL 與 API_PROXY_TARGET。環境參數及停止／重建資料說明見 [infrastructure](infrastructure/README.md)。

## 測試與 CI

```sh
cd frontend
npm ci
npm run lint
npm run typecheck
npm test
npm run build
```

```sh
cd backend
./mvnw --batch-mode verify
```

後端 verify 使用 Testcontainers 啟動獨立 PostgreSQL，必須有可用 Docker。測試不會略過整合測試，且不連外部或正式 DB。

E2E 必須使用隔離的 `xk_logistics_e2e` 空帳號資料庫與 test-only fixture，不能直接對一般 DEV DB 執行。請完整依照 [E2E README](tests/e2e/README.md) 準備獨立環境、私有暫存憑證及同源 TLS，再執行 `npm run test:e2e`。

CI 每次 PR／main push 執行前端品質檢查、後端 verify、依賴與機密檢查、Compose 與 Playwright smoke；報告作為 workflow artifacts 保存。實際命令與驗收結果見 [M1 驗證紀錄](docs/09_testing/m1-validation.md)。覆蓋率門檻尚待核准；目前以必要案例與不可略過的整合測試為門檻。UPMS 的真實 HTTPS、三角色、Session／CSRF 與 migration 驗證見 [M2-01 驗證](docs/09_testing/m2-01-validation.md)。

## 首次管理員與帳號復原

正常服務啟動不會建立帳號。具主機操作權且經責任人核實的真人，先啟動 DB，再以互動終端執行：

```sh
# 只在帳號表全空時成功，沒有公開 HTTP 初始化入口
docker compose -f compose.yaml -f compose.dev.yaml run --rm -it backend --upms.command=init
# 受控人工復原，必須記錄操作者識別及原因
docker compose -f compose.yaml -f compose.dev.yaml run --rm -it backend --upms.command=recover
```

依提示輸入操作者、原因及目標帳號，密碼由隱藏輸入取得，不放命令參數、環境變數或 log。臨時密碼 15 分鐘有效，首次登入僅能讀本人資訊、改密或登出；改密後須重新登入。憑證交付由真人處理，不自動寄送。初始化／復原會留下不含秘密的稽核。

一般 ADMIN 可在 UI 建立帳號、調整顯示名稱／單一角色／啟用狀態，固定角色為 ADMIN、DISPATCHER、DRIVER；不可移除最後一名啟用 ADMIN。停用、換角色、復原與改密會使舊認證失效。Session 閒置 30 分鐘／登入最長 8 小時／最多 3 個，超過時撤銷最舊 Session，不提供 remember-me。

密碼採 NFC 一致化後 15–128 個 Unicode code point、不 trim；PBKDF2-HMAC-SHA256 600,000 次與每筆隨機 salt，拒絕有限的常見密碼集合；未宣稱完整外洩密碼覆蓋。詳細工程參數與單 instance 限制見工程決策。

## ADM 字典與核准參數

ADMIN 登入並完成首次改密後，可由「系統管理」管理分類、項目、啟停、排序及異動紀錄。代碼建立後不可修改，沒有硬刪除操作；更新須附 version，衝突時重新讀取後再操作。分類停用後，其項目不再列入可供新引用的 options，歷史資料仍保留。

V3 只初始化固定 `ORDER_TYPE`／`IMPORT`、`EXPORT`、`LOCAL` 的唯讀對照，以及 `CONTAINER_TYPE`、`TASK_KIND`、`VEHICLE_TYPE` 空分類。它們不改變既有固定業務枚舉。沒有匯入正式字典項目或客戶資料。

系統參數只可使用核准目錄中的鍵、型別、用途與界限，支援文字／數字／布林；不可存秘密、認證、安全政策或部署設定。**目前尚無核准正式參數鍵，正常啟動的參數目錄為空**。目錄沒有寫入 API，後續須以另行核准的 migration 加入；隔離 E2E fixture 的合成定義不會進入一般啟動。

所有 ADM API 目前僅 ADMIN；DISPATCHER／DRIVER 尚無核准分類讀取權限，預設拒絕。未來模組只能透過 ADM 公開查詢服務取啟用選項或歷史標籤，不跨 repository，也不以 ADM 取代固定狀態機或角色。

ADM 的資料庫、HTTP、前端與 E2E 證據見 [M2-02 驗證](docs/09_testing/m2-02-validation.md)；原 UPMS 測試與訂單契約保留。

## CRM 客戶管理

正常登入並完成首次改密的 ADMIN／DISPATCHER 可由「客戶管理」建立及維護本公司客戶、多位聯絡人與多組常用地址，使用分頁、搜尋、啟停與有效客戶選項。DRIVER 無客戶主檔權限；ADM 仍僅 ADMIN。客戶使用 UUID 及唯一、建立後不變的大寫代碼；更新須附 version，遇到 409 應重新載入後再操作。

客戶名稱、聯絡人姓名與地址內容為必要文字；taxId 可空且非唯一，電話不限定單國格式，地址類型是可空描述文字，不是核准業務枚舉。沒有硬刪除。停用客戶仍保留歷史主檔與子項，從有效選項移除並拒絕新引用查核；CRM 主檔仍可維護。

後續 TOM 須透過 `CrmQueries` 公開服務，在已授權的寫入交易中查核有效客戶並保存當時快照；M2-03 交付來源與鎖定介面；M2-04 的真正訂單寫入與競爭驗收結果見下節及對應證據。客戶改名不得覆寫既有訂單 `customerName`。V4 不加入客戶或測試 seed。驗收證據見 [M2-03 驗證](docs/09_testing/m2-03-validation.md)。

## TOM 訂單草稿 API

M2-04 提供原 OpenAPI 0.2.0 五項 API：POST／GET `/api/v1/orders`、GET／PUT `/api/v1/orders/{orderId}`、GET `/api/v1/orders/{orderId}/acceptance-check`。M2-05 的訂單工作台整合這五項操作，沒有新增或更改訂單端點。

正常 ADMIN／DISPATCHER 可管理本公司草稿，DRIVER 不可讀寫整單。寫入需要同源 HTTPS Session、X-CSRF-TOKEN 與 Idempotency-Key。訂單契約使用 JSESSIONID，與既有 XKSESSION 是同一 HttpSession 的兩個安全 Cookie 名稱；衝突身分拒絕。原成功 `OK`／traceId 與錯誤 fieldErrors 格式保留，UPMS／ADM／CRM 的 envelope 不受影響。

建立只需 type 與有效 customerId，允許零項目／零任務；多項目與多段以穩定 clientRef 關聯，前置須同單存在、非自己且無環。PUT 是完整替換、須 expectedVersion，保留仍存在的 ref UUID／taskNo。新成功保存恰加一版，重播回原業務結果與新 traceId、不再加版。歷史快照及重送鍵未核准清理政策前不自動刪除或復用。

CRM 建立／換客戶查核與訂單寫入同交易協調停用，保存當時 customerName；主檔改名不回填舊單，同 customerId 編輯保留歷史名稱。只讀完整性檢查只驗 ORD-P01–P03，operationalAcceptance 永遠 NOT_GRANTED，沒有正式受理或派工副作用。

[TOM 契約整合](docs/06_api/tom.md)與[資料模型](docs/05_database/tom-model.md)記完整規則，[M2-04 驗證](docs/09_testing/m2-04-validation.md)記實際結果。CI 另以[原 schema 工具](tests/contracts/README.md)校驗固定來源與桌機／手機的真實合成 HTTP 回應。

## 訂單工作台

ADMIN／DISPATCHER 登入後由「訂單工作台」查詢、新增及編輯本公司訂單；DRIVER 沒有整單入口或資料存取權。列表以類型、客戶篩選，支援分頁與最近更新／單號排序。從有效客戶查詢選擇新單客戶；歷史訂單保留原客戶名稱，即使客戶已停用也可維護原草稿。

新增先選進口、出口或國內運輸及有效客戶，即可保存最小草稿。再逐步補齊船務、多貨櫃／貨物、每段任務及前置依賴；不把未決現場規則增加為草稿必填。編輯先讀完整資料，再以原版本完整保存，既有項目與任務維持相同識別。十進位重量／材積、日期、含時區與微秒的時間及原文字保持原值，不借用 CRM 的 trim／NFC 規則。

保存遇到連線中斷、逾時或無法確認的回應時，保留該次請求內容及重送識別，讓使用者明確重試同一次保存；不自動換 key 新增另一單。版本衝突保留本機內容，不自動覆寫或再次保存；重新讀取伺服器版本需明確確認捨棄本機內容。未儲存或結果未確認時，離開頁面會先提示；表單與重送資訊僅在記憶體，關閉／重新整理頁面不會持久保存。

完整性檢核針對已保存的版本顯示缺漏；若要檢查本機修改，須先保存。完整性檢核不會受理：`operationalAcceptance` 仍為 `NOT_GRANTED`，表示此檢核不授予受理操作；實際受理狀態以訂單的 `acceptanceStatus` 為準。本階段沒有派車、司機回報、照片或結案操作。桌面／手機及真實後端／PostgreSQL 的實測結果見 [M2-05 驗證](docs/09_testing/m2-05-validation.md)。

## TOM 正式受理

正常 ADMIN／DISPATCHER 可對已保存、沒有未儲存內容或未確認操作的 OPEN＋DRAFT 訂單，明確執行「正式受理訂單」並再次確認。伺服器會在同一交易重新查核目前身分、客戶有效性、聚合版本與 ORD-P01–P03；不信任先前完整性結果，也不在保存草稿或顯示 COMPLETE 時自動受理。未決 D04／D05 清單不被當成新增全面阻擋或例外放行。

唯一新操作為 `POST /api/v1/orders/{orderId}/accept`，JSON 只有 `expectedVersion`，需要 Session／CSRF 與 Idempotency-Key；成功 HTTP 200 沿原 OrderEnvelope，回 OPEN＋ACCEPTED 與恰加一版。原五項 API 與四份固定契約不變；GET／列表可讀既有 schema 已允許的 ACCEPTED，原草稿 PUT 新請求被既有守衛拒絕。只讀 acceptance-check 即使對已受理訂單仍回 NOT_GRANTED；這不會覆蓋訂單本身的受理狀態。

受理保留客戶歷史名稱、項目／任務 UUID、taskNo、依賴與原內容。受理人員、server 時間、前後版本及完整受理後快照存入 V8 不可變事件，同版快照加入既有版本歷程，並與根狀態及成功重送紀錄原子提交；本階段不新增事件讀取 API 或把事件欄位塞入舊 Order DTO。

同 key、同內容的成功重送先重驗目前授權，再回原成功結果，不重複建事件或加版；舊成功 POST／PUT 在後續受理後仍可重播其歷史草稿結果。未知受理結果保留原 request／key，只能明確重試同一次，不自動換 key、保存或覆寫。版本衝突需明確讀取比較；遠端已受理時不能以本機未保存草稿重新基底。受理後前端唯讀，顯示「已受理／待派車」，此顯示不產生派工或資源占用。實際契約與驗收見上述受理文件。

## FMS 車輛管理

ADMIN／DISPATCHER 登入後由「車輛管理」查詢、新增及維護本公司車輛；DRIVER 沒有車隊入口或整體資料權限。列表支援車牌／車籍號字面搜尋、車種、管理狀態與分頁。車牌經 NFC、邊界空白移除及大寫正規化後全庫唯一，停用也不重用；更正車牌須帶版本，車輛 UUID 保持不變。不限定國別格式，不自動刪除內部空白或連字號。

車種由既有 ADM 的 `VEHICLE_TYPE` 分類配置。正常環境沒有預設正式車種碼；ADMIN 須在「系統管理」建立經業務核准的車頭、拖板或貨車分類，再到車輛頁選用。DISPATCHER 使用 FMS 的限定車種選項，不因此取得其他 ADM 管理權限。車種或其分類停用後不供新增／更換引用，原車輛仍可保留相同車種維護，畫面保留目前歷史代碼／名稱及停用提示。

載重以精確十進位字串記錄噸數，0 與未知分開，不用浮點或自訂車型重量上限。車籍號、登記日期及檢驗期限可空，日期只作記錄及已過日期提示，不判定法定檢驗合規，也不自動改狀態或阻擋派車。管理狀態只開「可用」「維修」「停用」；「使用中」須由之後的 TMS 占用推導，本階段沒有該操作。

明細更新帶 version，衝突時保留本機輸入，先讀最新資料、明確確認後再編輯，不自動覆寫或重送。保存期間與未儲存離開會保護本機內容；不確定保存結果時先查詢伺服器資料，不能顯示假成功。表單不保存至瀏覽器 storage。

`/fms/vehicle-options` 與 `VehicleQueries.requireAdministrativelyAvailable` 只表示主檔行政狀態為 AVAILABLE，仍回傳車種目前啟用資訊；不把檢驗日期、停用字典或未實作的占用當成自訂派車資格。未來 TMS 呼叫者仍須自行授權及執行經核准的占用／派車政策。實際 API、模型與驗收見上述 FMS 文件，V6 不種車輛或正式車種資料。

## FMS 司機管理

正常 ADMIN／DISPATCHER 可由「司機管理」查詢、新增及維護本公司司機主檔。UUID 由伺服器產生；司機編號經 NFC、邊界空白移除與大寫正規化後唯一，建立後不可修改，停用／離職也不重用。姓名必填，聯絡方式、駕照類別／工作參考及期限、UPMS 帳號均可空；沒有預設司機或正式證照類別資料。

V7 只新增空的 ADM `DRIVER_LICENSE_TYPE` 分類框架。ADMIN 可配置經核准的選項；DISPATCHER 透過 FMS 窄查詢使用有效項目，不取得一般 ADM 管理權。歷史停用分類仍可原值維護，日期只記錄與提示，不代表法規資格。

司機主檔與登入帳號分開，可選擇性綁定唯一、目前有效的 DRIVER 帳號。候選只顯示帳號／名稱摘要與已綁定標記，不提供其他帳號資料或 UPMS 管理操作。有效臨時帳號可先綁定，首次改密限制仍照常生效。停用或換角色後的舊關聯保留並提示失效，可解綁或更換；新綁定仍須由伺服器重新驗證與資料庫唯一約束保護。

在職（ACTIVE）、停用（INACTIVE）、離職（LEFT）與行政在勤／不在勤分開記錄，不自動變更另一欄或帳號生命週期。離職可維護既有資料；復職流程尚未核准，本階段不提供離職轉回在職／停用。行政可用選項及 `DriverQueries.requireAdministrativelyAvailable` 只檢查 ACTIVE 且 ON_DUTY，不以帳號、分類啟用或駕照日期自訂派車資格，也不建立占用或車輛配對。

DRIVER 由「本人司機資料」唯讀查詢目前帳號綁定的必要資料。伺服器每次由目前身分推導，不接受其他司機 ID，不回證照參考、帳號關聯或管理稽核欄位。帳號失效或解綁後不得繼續查到原關聯資料；司機主檔行政狀態本身不會自動停用登入帳號。管理保存沿用版本衝突、未知結果與未儲存／保存中離開保護。實際契約、交易與驗收見上述 FMS 司機文件。

## 範圍與部署

UPMS 沿 Q06–Q10 與新契約交付；既有訂單 OpenAPI、角色矩陣、合成案例、欄位字典與產品決議保持原樣。ADM 依 Q11–Q15、CRM 依 Q16–Q20 交付；TOM 依原五 API 草稿契約、M2-05 草稿 UI 與 Q31–Q35 明確正式受理交付；FMS 車輛依 Q21–Q25、司機主檔依 Q26–Q30 交付。TMS 依 Q36–Q40 交付出發前分段派工、原子占用、撤改與工作台／本人唯讀，M3-06 完成整合驗收；正式資格維持未核准。司機回報、照片、完成與結案已列入本次授權的 M4-02～07，依序獨立交付；其他未授權 FMS 功能仍待另行交辦。

`compose.prod.yaml` 為禁止啟動的佔位設定；M1 無自動部署。正式環境仍需獨立核准 HTTPS、機密、備份還原、權限與驗收。不使用真實客戶資料、不提交秘密、不更動舊庫。

## TMS 派工與占用（M3-04）

ADMIN／DISPATCHER 可查詢已受理任務、伺服器資源候選、目前派工與有界歷史，並透過獨立 API 指派、撤派及改派。每段以獨立 generation 控制版本，司機與所有車輛位置使用資料庫排他占用；撤改、釋放／取得、版本、事件及成功重播結果同交易，失敗保留原派工。M3-04 API 已以 PR #19 交付；M3-05 整合完整工作台。

只接受 OPEN＋ACCEPTED 的同單任務，後段須有可驗證的前置完成／交接證據；M3-04 交付時尚無 M4 完成證據，不能以撤派、到時或無占用放行後段；後續只採獨立 M4 合法完成證據。DRIVER 本人端點每次依目前有效帳號與綁定，只回目前當段必要路線、聯絡及標的，不回整單、其他資源或歷史。

**正式適格政策尚未核准，一般 DEV／正式 JAR 的新派工與改派預設拒絕。** FMS 行政可用不保證車種、配對、駕照、載重、檢驗或工時適格。正向工程驗收僅在 `xk_logistics_e2e` 載入另外編譯、明確合成的 test-only 政策，回應及快照標示 `SYNTHETIC_TEST_ONLY`；正式 JAR 沒有 override、launcher 或可開放資格的環境參數。隔離啟動依 [E2E README](tests/e2e/README.md) 加 `compose.e2e.yaml`，一般 DEV 不加該檔。

占用中的 FMS 資源不能失去行政可用或直接修改配置依據；未出發任務須先合法撤派／改派，已出發不得沿舊流程解除占用；名稱／聯絡校正仍保留歷史快照。V9 只為既有已受理任務衍生派工索引，沒有派工／占用／業務種子。D04／D05／D10 及正式政策仍待核准。M3-04 當時未包含出發、完成、回報或照片；本次依後續 M4 授權逐包交付，沒有正式部署。

## 調度工作台與本人任務（M3-05）

ADMIN／DISPATCHER 登入後進入「調度工作台」，查詢已受理任務的待派／已派狀態，查看當前派工、資源占用與分頁歷史；選定司機、主車及選用拖板後明確確認指派。改派／撤派需填寫原因，資源候選與阻擋理由由後端提供。一般 DEV 的正式資格未核准，新指派／改派維持關閉；未出發的有效派工仍可合法撤派；已出發的新撤改由後端拒絕。

寫入結果未知時保留原次操作、內容、版本與 key，僅由使用者明確重試。CSRF 更新不會自動重送；確認成功的歷史收據與目前派工分開，必須重新讀取目前狀態才進行下一操作。衝突不自動覆寫，離開／登出須確認捨棄本機未決內容，請求傳送中無法離開。

DRIVER 登入後可進入「本人運輸任務」唯讀頁；原「本人司機資料」另行保留。本人任務只讀目前帳號綁定的有效派工，重新讀取、切頁、撤權或無綁定後清除舊可見路線／聯絡資料，沒有整單、資源、歷史或回報入口。畫面驗收使用隔離合成資料，依 [操作與證據](docs/09_testing/m3-05-validation.md) 重現；沒有新增環境設定、API、migration、依賴或正式部署。

## M3 整合驗收（M3-06）

M3-01 車輛、M3-02 司機、M3-03 正式受理、M3-04 原子派工占用及 M3-05 調度／本人任務介面已分包交付。本包對最終版本重跑前後端、PostgreSQL、五份 HTTP 契約與桌面／手機全量回歸，並補驗 V8 舊已受理資料升級至 V9 後的真 HTTPS 派工與重播。本包實測前端 590、後端 369、桌面／手機 E2E 28 與五份契約 1,244 筆 HTTP 回應全過，空庫／重啟及 V8→V9 真 HTTPS 流程通過。各包 PR／SHA／CI、畫面證據與剩餘限制見上述報告及交付收據；M3 完成時停在後續規格決議；現依新核准 Q41–Q47 與 M4 七包逐包交付，不部署。

驗收邊界為出發前工程流程；正式車種／駕照／配對及法規適格仍待核准，一般服務的新指派／改派預設拒絕。M3-06 當時停在 M4 規格決議；現有 M4 明確授權涵蓋後續出發、回報、照片與完成，仍不部署。

## M4 運輸執行

Q41–Q47 與 M4-01～07 已獲使用者授權，依七包交接逐包獨立 PR／CI／合併／main CI 驗收。本包 M4-01 新增本人有效任務詳情與出發 API、獨立執行版本及 V10，不修改原 M3 十四欄本人摘要。完整手機操作 UI 在 M4-06 交付；精確契約、未核准政策拒絕與目前實測狀態見上述文件。

M4-01 本機 frontend 590／backend 418／desktop＋Pixel 7 E2E 30 與六份實際 HTTP 契約 1,489 筆全過；V1–V10／30 表與升級、重啟、本人隔離及登出／撤改競爭已驗證。遠端交付以 [M4-01 PR 收據](https://github.com/xk2022/xk-app-logistics/pulls?q=is%3Apr+head%3Afeat%2Flg-m4-01-driver-departure+base%3Amain)及其 PR／main 四項 CI 為準，main 四過才進入 M4-02。

## M4-02 進度與私有照片

本包依[交接](docs/08_development/m4-02-progress-evidence-handoff.md)／[獨立 API](docs/06_api/tms-progress-evidence.md)加入本人進度與照片接口；實際執行狀態與結果見[驗證紀錄](docs/09_testing/m4-02-validation.md)。只開放 DEPARTED→IN_PROGRESS→AWAITING_COMPLETION，照片不能覆寫，也不會因上傳或進度自動釋放人車／解除前置。原本人列表、詳情、出發及既有模組契約不擴欄。

正常啟動的照片策略／provider 維持 UNCONFIRMED／unavailable，沒有正式照片上傳或進度豁免開關。正向測試只在隔離 xk_logistics_e2e，用 test-classpath 政策及同 PostgreSQL 交易的私有 bytea 替身；PNG、數量、大小及像素值均為合成工程數值，不代表 D06／D10 或正式 provider 核准。不執行照片保存清理、正式安全釋放／結案、M5 或部署。完整手機操作 UI 依 M4-06 交付。

M4-02 本機前端590／後端469（139 unit＋330 integration）／桌面與Pixel7 E2E32、七份實際HTTP契約1,731筆全過；空庫34表／V1–V11與重啟一致、234後端class／71前端產品建置檔逐byte同源、90憑證報告隱私通過後清理隔離環境。遠端 source／merge／四項PR及main CI 以[本包交付收據](https://github.com/xk2022/xk-app-logistics/pulls?q=is%3Apr+head%3Afeat%2Flg-m4-02-progress-evidence+base%3Amain)為準；main四過才開M4-03。

## M4-03 異常處理與明確補件

[異常契約](docs/06_api/tms-exceptions.md)、[獨立 OpenAPI](docs/06_api/tms-exceptions-openapi.yaml)、[工作包](docs/08_development/m4-03-exceptions-handoff.md)、[驗收](docs/09_testing/m4-03-validation.md)。新增13項獨立 API、V12六表及 ADMIN／DISPATCHER「異常處理」工作台，保留原始回報、補件要求、新私有照片、明確提交、管理處置及不可變歷史；DRIVER 完整手機操作於 M4-06 交付。異常版本與執行版本獨立，上傳不等於提交，處置不會完成任務、釋放人車或解鎖依賴。

一般新異常政策仍 UNCONFIRMED；正式例外、照片規則／provider／保存、安全解除及結案責任未核准，不新增開放參數。正向只在真實隔離測試 DB 載入明確 test-only 策略。管理 UI 區分目前事實與歷史收據，保留版本衝突原文並明確比較／採用；未知結果只重試同次請求，GET或讀照失敗不判定另一個POST回滾。私有預覽在關閉／撤權時清除，沒有公開照片網址。

本機前端718／後端530（146 unit＋384 integration）／真實TLS桌面及手機E2E34（0 retries）、八份實際HTTP1,949筆／58檔全過；40表／V1～V12空庫與重啟、升級及100憑證報告隱私已驗證。最終受測source／merge／PR與main四項CI以[本包交付收據](https://github.com/xk2022/xk-app-logistics/pulls?q=is%3Apr+head%3Afeat%2Flg-m4-03-exceptions+base%3Amain)及上述驗收文件為準；main四項通過才開始M4-04。

M4-03交付完成：PR #25／source`508e1e41174623e39c25352d1ae7d77df3692c99`／merge`e55ee4ecdf98d6f314b60e45c96b0b18d9e5489e`，PR CI45787301633與main CI45788390226 attempt1四項均成功；遠端各718／530／34，HTTP PR1,949／main1,947。

## M4-04 任務完成與有效資源釋放

[新契約](docs/06_api/tms-completion.md)、[工作包](docs/08_development/m4-04-task-completion-handoff.md)、[工程採用](docs/98_decisions/2026-11-26-m4-04-engineering.md)及[驗證進度](docs/09_testing/m4-04-validation.md)。由已通過CI的M4-03 main依序實作獨立完成API、原子有效解除、可信前置完成查詢與V13，不修改舊派工狀態／release欄位或API。舊UI明示指派紀錄，完整本人操作於M4-06交付；本機前端718／後端566（153 unit＋413 integration）／真TLS desktop＋Pixel7 E2E36（0 retries）與九份實際HTTP2,244／76檔全過；42表／13版本及重啟、39舊領域表升級、291正式class同源與110憑證隱私已驗。遠端source／merge／PR及main四項CI由[本包收據](https://github.com/xk2022/xk-app-logistics/pulls?q=is%3Apr+head%3Afeat%2Flg-m4-04-task-completion+base%3Amain)核對，main四過才開M4-05。正式安全確認者／照片／provider與派工政策未核准，維持拒絕；無M5或正式部署。

## 管理完成確認及訂單結案

ADMIN／DISPATCHER的「確認與結案」提供完成紀錄及管理確認、整單任務檢視與結案；原reason保留、來源版本與原次重試分開顯示。requiredTask未指定不代表可略過；命名合成驗收要求所有任務完成且管理確認、整單無有效占用及未解異常。正式確認與結案策略未核准，正常環境維持拒絕，不以checkbox當授權。舊CLOSED無新audit仍可讀，介面明示未有本階段紀錄；原五API及歷史OPEN重送格式保留。完整DRIVER操作留下一包M4-06。

LG-M4-05本機驗收：前端883／後端612／真TLS桌面與Pixel7 E2E38（0 retries）、十份HTTP2,686全過，PG17.12／47表／14版與重啟已驗；完整收據、首輪修正及畫面證據見[驗證紀錄](docs/09_testing/m4-05-validation.md)。未確認正式政策維持拒絕。


## M4-06／07 本人操作與全量整合驗收

DRIVER「本人運輸任務」支援出發、私有照片、進度、異常與補件、完成並安全釋放；管理端另有異常處置、完成確認與整單結案。手機五項任務操作為全寬單欄且觸控高度至少44px。未知結果保留原body／bytes／版本／key，由本人明確重試；衝突不覆寫，登出／撤權／背景切換會清私有資料與預覽。正常資格、照片provider／保存、現場例外、安全及管理責任未核准，維持UNCONFIRMED拒絕；正向只在actual-DB-gated隔離合成環境。

M4-06 PR #29已完成PR及精確main四CI。M4-07最終本機 lint／typecheck／1,083 frontend tests／build、613 backend tests、12工具units、40桌面／Pixel7 HTTPS E2E（0 retries）、十HTTP3,242筆／125檔及原始150secret patterns隱私全過。真V9→V14升級、47表／67照片完整重啟與不同DB還原、普通production Jar預設拒絕／歷史重播、真Console init／recover與隔離清理全過。所有原契約／Migration保留。逐包SHA、PR、CI、失敗修正及限制見[M4完整驗收報告](docs/09_testing/m4-integration-acceptance.md)；最後一包遠端交付由[唯一head PR收據](https://github.com/xk2022/xk-app-logistics/pulls?q=is%3Apr+head%3Atest%2Flg-m4-07-integration-acceptance+base%3Amain)記錄，不以本機代遠端。未開發M5或正式部署。


## Showcase Demo 先行

使用者最新範圍為「暫時只完成showcase demo部署」，先不建立 Render 資源。展示使用既有 XK Showcase Hub `/logistics/`，提供 ADMIN／DISPATCHER／DRIVER 三種合成 persona，以及草稿、受理、派工、本人出發／照片標記／進度、異常補件、完成、管理確認與結案。所有操作只存在當前分頁記憶體，無真實登入、API、付款、照片上傳或持久化；請勿輸入真實資料。刷新或明確重置會還原種子，重置不清除其他展示的儲存資料。

正常產品入口及 build 不載入展示程式。展示採獨立 `frontend/showcase/`、`npm --prefix frontend run build:showcase` 與 `dist-showcase/`；正常 Docker／API／Session／CSRF 與正式拒絕策略保留。`npm run test:showcase` 以實際建置執行桌面／手機劇本及網路／儲存隔離檢查。具體操作與本機指令見展示手冊。

來源與 Hub 分別透過 PR／CI／合併／精確 main 驗證後，才更新不可變 source SHA 並公開驗證。來源交付由[Demo source PR](https://github.com/xk2022/xk-app-logistics/pulls?q=is%3Apr+head%3Afeat%2Flg-m5-showcase-demo+base%3Amain)，Hub 交付由[整合 PR](https://github.com/xk2022/xk-website-showcase/pulls?q=is%3Apr+head%3Afeat%2Flogistics-interactive-demo+base%3Amain)記錄，不以本機結果冒稱已更新公開站點。

完整 LG-M5-01～07、Vercel／Render／Neon 產品部署、正式人車資格／照片政策與客戶 UAT 仍未完成或核准；本次不建立這些雲端資源，不發布 MVP v1.0.0，不進行正式營運部署。
