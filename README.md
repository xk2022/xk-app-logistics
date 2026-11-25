# XK Logistics v2.0｜貨運物流管理平台

| 項目 | 說明 |
| --- | --- |
| Repository | xk2022/xk-app-logistics |
| 類型 | xk-app（Web 應用系統） |
| 狀態 | development — LG-M3-02 FMS 司機主檔（驗證紀錄見下方） |
| 主要使用者 | 貨運公司管理員、調度員、司機 |
| 核心用途 | 貨運訂單、貨櫃、分段任務、派工、回報與結案 |
| 技術基準 | React / TypeScript / Vite、Java 21、Spring Boot、PostgreSQL 17、Flyway |
| 正式網址 | 尚未部署 |

## 文件與開發

- [文件總索引](docs/README.md)
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

Monorepo 採 Modular Monolith。`frontend/` 提供登入、本人密碼、角色工作台與 ADMIN 帳號管理；`backend/` 的 `platform` 提供工程端點，`upms` 負責帳號、固定角色、認證與稽核；`adm` 負責字典、核准參數與異動紀錄，透過 UPMS 公開授權介面核對身分；`crm` 提供客戶、聯絡人、常用地址與可信 server 共用查詢。`tom` 的 OrderCore 與訂單草稿聚合保存項目、任務、依賴、客戶歷史快照、版本與成功重送紀錄，僅開原五項 API。`fms` 提供車輛與司機主檔、受控分類選項、DRIVER 本人唯讀介面及後續模組可使用的行政狀態查詢；不處理派工或資源占用。後續模組必須透過介面協作，不跨模組操作 repository。

## 啟動

```sh
cp .env.example .env
# 編輯 .env，設定只用於本機的 POSTGRES_PASSWORD
# Docker Compose 會讀取 .env；不要提交此檔案
./infrastructure/generate-dev-tls.sh
docker compose -f compose.yaml -f compose.dev.yaml up -d --build --wait
```

開啟 **https://localhost:8443**。DEV 憑證由本機生成、有效 30 天，只用於本機驗收；瀏覽器首次需要信任該本機憑證。登入使用同源 HTTPS，Session Cookie 強制 Secure／HttpOnly／SameSite=Lax；HTTP 5173 僅保留工程檢查，不作帳號操作。後端 http://localhost:8084/actuator/health 僅回報健康狀態，`/api/v1/status` 為同源前端整合端點。資料庫容器就緒後，後端啟動時由 Flyway 建立 `logistics` schema、V1 工程基線、V2 UPMS 帳號／稽核、V3 ADM、V4 CRM、V5 OrderCore／TOM、V6 FMS 車輛及 V7 FMS 司機主檔結構，再由 Hibernate validate；沒有預設帳號、固定密碼或客戶種子資料。

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

完整性檢核針對已保存的版本顯示缺漏；若要檢查本機修改，須先保存。即使顯示完整，也不代表正式受理：`operationalAcceptance` 仍為 `NOT_GRANTED`。本階段沒有派車、司機回報、照片或結案操作。桌面／手機及真實後端／PostgreSQL 的實測結果見 [M2-05 驗證](docs/09_testing/m2-05-validation.md)。

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

UPMS 沿 Q06–Q10 與新契約交付；既有訂單 OpenAPI、角色矩陣、合成案例、欄位字典與產品決議保持原樣。ADM 依 Q11–Q15、CRM 依 Q16–Q20 交付；TOM 依原五 API 草稿契約、M2-05 草稿 UI 交付；FMS 車輛依 Q21–Q25、司機主檔依 Q26–Q30 交付。正式受理、派工、資源占用、司機回報、照片、結案及其他 FMS 功能未開發，後續工作包須獨立交辦。

`compose.prod.yaml` 為禁止啟動的佔位設定；M1 無自動部署。正式環境仍需獨立核准 HTTPS、機密、備份還原、權限與驗收。不使用真實客戶資料、不提交秘密、不更動舊庫。
