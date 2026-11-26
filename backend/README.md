# XK Logistics 後端｜LG-M1 / M2 / M3-01–03

Java 21、Spring Boot 4.1.3、Maven 3.9.16（Wrapper 3.3.4）。相依由 Spring Boot BOM 統一管理：Spring Security 7.1.3、Spring Data JPA 4.1.3、Hibernate 7.4.15.Final、Flyway 12.4.0、PostgreSQL JDBC 42.7.14、JUnit 6.0.3、Testcontainers 2.0.5。完整版本依據及專案啟動見[根目錄 README](../README.md)。

## 執行與測試

本機需 JDK 21；執行整合測試另需可存取的 Docker daemon。從本目錄執行：

```sh
./mvnw -B -ntp verify
```

`verify` 執行單元測試、建置，以及使用獨立 PostgreSQL 17.12 容器的整合測試。Docker 不可用時測試會失敗，不會默默略過。報告在 `target/surefire-reports/` 與 `target/failsafe-reports/`。

依[根目錄啟動方式](../README.md)啟動開發資料庫後，設定本機環境；密碼須與本機 `.env` 一致，勿提交：

```sh
export DB_URL=jdbc:postgresql://localhost:5432/xk_logistics
export DB_USER=xk_dev
read -rs DB_PASSWORD
export DB_PASSWORD
./mvnw spring-boot:run
```

服務預設在 `http://localhost:8084`；可用 `SERVER_PORT` 改埠。此內部 HTTP 埠可檢查 health/status，UPMS 必須從[同源 TLS 開發入口](../README.md)存取。標準 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD` 亦可覆寫連線設定，Compose 採此方式。

## 受控初始化與復原

正常啟動不建立任何帳號。具主機權限、經責任人核實的真人，在連向正確資料庫的互動終端執行：

```sh
java -jar target/xk-logistics-0.1.0-SNAPSHOT.jar --upms.command=init
java -jar target/xk-logistics-0.1.0-SNAPSHOT.jar --upms.command=recover
```

兩個命令為各自獨立操作；初始化只在帳號表全空時允許，復原要求目標帳號已啟用。程式會詢問操作者、原因、帳號與隱藏的臨時密碼；初始化另問顯示名稱。不得將密碼放進命令、環境範例、log 或聊天。沒有互動終端時會在啟動 Spring 前拒絕；離線模式不開啟 HTTP server。`UPMS_COMMAND` 等環境變數不會觸發此流程。

臨時密碼 15 分鐘內有效，首次登入只能讀本人狀態／CSRF、改密或登出。改密成功須重新登入；復原與改密撤銷所有既有認證。人員核實、臨時密碼交付及操作責任由真人處理；audit 只保存操作者、目標、原因、事件及時間，不保存秘密。

## Session 與反向代理邊界

Session 使用 `XKSESSION`，並以 `JSESSIONID` 相容原訂單契約；兩名指向同一個 HttpSession，不建立第二套認證。兩個 cookie 值不同時拒絕認證並清除瀏覽器兩名 cookie，不選擇其中一方或撤銷其他人的 server Session。Cookie 固定 Secure、HttpOnly、SameSite=Lax、Path=/，只允許 COOKIE tracking；不允許透過環境或 profile 降級。每帳號最多三個 Session，超過時撤銷最早登入者；閒置 30 分鐘、登入後最長 8 小時，改密、復原、停用或角色異動使舊認證失效。寫入含登入、改密、登出與訂單 POST／PUT 皆檢查 CSRF；登入與登出後重新取得 token。

每次請求重新查核帳號後建立當次 Authentication；框架採 `requireExplicitAuthenticationStrategy(true)`，只有既有登入控制器執行 Session／CSRF 輪換。不能把每次查核誤當新登入而自動更換 ID，造成同一 Session 的兩名 cookie 失配。

開發 TLS gateway 覆寫轉送協定標頭，後端採 framework forwarded headers。此信任只適用於受控反向代理：不得將後端埠直接對外公開，正式環境必須限制可達代理並覆寫／清除用戶端轉送標頭。僅經 HTTPS 的請求可使用 UPMS。Session Registry 與登入限流為單 instance 記憶體實作，不支援多 instance；正式部署前須另驗證分散式撤銷、限流與信任邊界。

密碼採 NFC、15–128 個 Unicode code point、PBKDF2-HMAC-SHA256 600,000 次、16-byte salt 與 256-bit 輸出。有限常見密碼集合固定來源及授權見 [SOURCE.md](src/main/resources/upms/passwords/SOURCE.md)。完整工程政策及限制見[安全 ADR](../docs/98_decisions/2026-11-25-upms-engineering.md)與[API 契約](../docs/06_api/upms.md)。

## 範圍與模組邊界

`platform` 只提供 `/actuator/health`、`/api/v1/status`、伺服器產生的請求識別碼及中性錯誤呈現介面。Health 僅公開狀態；status 依資料庫健康回傳 HTTP 200／503、繁中訊息與 `+08:00` timestamp。`upms` 提供固定角色、帳號管理與同源認證；`adm` 提供分類、字典項目、核准參數與異動紀錄；`crm` 提供客戶、聯絡人及常用地址主檔；`tom` 提供原首片訂單草稿、只讀完整性檢查與獨立明確受理；派工與資源占用屬後續。

Flyway 是唯一結構來源，建立 `logistics` schema 與歷程表；V1 保留原空庫基線，V2 增加帳號、audit 與單一協調鎖資料列，V3 增加 ADM 五表，V4 增加 CRM 三表，V5 增加訂單聚合及技術歷程七表。Migration 不建立使用者帳號、客戶或訂單種子。Hibernate 採 `validate`，禁止以自動 DDL 取代 migration。已套用的 migration 不可修改，未來以新版本遞增。

未來業務模組依領域建立套件，僅透過明確的公開介面協作，不跨模組存取 repository。M2 業務與安全設定須另依既有契約及已核准決議實作。

## ADM 管理與共用查詢

ADM HTTP 路徑為 `/api/v1/adm`，目前全部僅正常登入 ADMIN 可用，沿用 HTTPS、Session、CSRF 與強制改密限制。契約見 [ADM API](../docs/06_api/adm.md)；原訂單 OpenAPI 與固定枚舉維持原樣。

分類及項目代碼、參數鍵與型別建立後不可變更。更新必須附目前 `version`，衝突回 409；未知欄位、系統欄位或未支援查詢參數回 400。列表預設每頁 20、最多 100 筆，排序欄位依資源白名單檢查並加入穩定次鍵。無 DELETE API，停用保留資料與歷史查詢。

V3 僅建立 `ORDER_TYPE` 的唯讀 IMPORT／EXPORT／LOCAL 對照，標籤與固定枚舉字串相同；CONTAINER_TYPE、TASK_KIND、VEHICLE_TYPE 為空分類。正式字典項目未在本階段核准，不以合成 fixture 充當正式種子。

參數必須引用唯讀 `adm_parameter_definition` 核准目錄，並符合型別、文字枚舉／長度、數字範圍與小數位數或布林限制。正式目錄與參數表預設為空，沒有建立／修改目錄的 HTTP API；正式鍵只能透過另行核准的 migration 加入。拒絕認證、安全及基礎設施設定鍵，並阻擋部分明顯憑證格式；這些有限檢查不能取代真人核准用途，也不能保證任意文字永不含秘密。

後續模組只注入 [AdmQueries](src/main/java/com/xksystem/logistics/adm/AdmQueries.java)，使用 `enabledOptions`、`historicalLabel` 與 `enabledParameter`；不可引用 ADM repository 或 entity。啟用選項以單一資料庫查詢檢查父分類及項目皆啟用，依排序、代碼及 ID 穩定排序；歷史標籤可查停用資料。啟用參數讀取會重驗目前核准定義，失效值明確拒絕，避免傳回過期治理資料。內部介面不等同公開 HTTP 授權，呼叫模組仍須執行自己的操作與資料範圍守衛。

ADM 寫入先經 UPMS 公開授權 facade，在同一交易取得既有 canonical／操作者鎖並重驗認證版本，再鎖定 ADM 目標；不跨模組存取 UPMS repository。變更與前後值 audit 同交易提交，audit 失敗會回滾原變更。測試 fixture 僅存在 `src/test` 與隔離資料庫，不提供正式 seed 開關。

## CRM 主檔與引用邊界

`/api/v1/crm` 只允許正常登入 ADMIN、DISPATCHER 操作，DRIVER 拒絕；這項授權不擴大 ADM 權限。沿用 HTTPS、Session、CSRF、強制改密及標準錯誤 envelope。客戶代碼唯一且不可變，聯絡人及地址可一對多，父關聯不可移轉；沒有 DELETE。詳細 DTO 及查詢白名單見 [CRM 契約](../docs/06_api/crm.md)。

新增客戶僅必填代碼與名稱，聯絡人僅姓名必填，地址僅內容必填。其他欄位可空，啟用預設 true。PATCH 採部分更新，需目前 `version` 及至少一個可變欄位；未提供欄位保持原值，nullable 欄位可用 null 或空白清除。拒絕未知欄位、ID、代碼、父關聯、操作者及未知查詢參數。資料保存建立／更新時間及操作者 UUID。

文字採 NFC 並去除邊緣 Unicode 空白，名稱與地址不能僅包含空白。客戶與聯絡人名稱最多 200、電話 50、地址 1000 個 Unicode code point，保持原訂單快照欄位相容；電話可保留國際區碼與分機文字。統編可空、非唯一，未施加單一國家稅碼或檢查碼規則。電子郵件只做基本文字格式檢查，最多 254 字，不寄送郵件。地址類別為最多 40 字的可空描述文字，未定義新的業務枚舉。

停用不會讓主檔及其聯絡人／地址停止維護。有效客戶選項強制排除停用資料；`customer-options` 不接受 `enabled` 參數。後續模組只透過 [CrmQueries](src/main/java/com/xksystem/logistics/crm/CrmQueries.java) 查詢，不存取 CRM repository。

`findCurrent` 回傳目前主檔來源，包含停用客戶；它不是既有訂單的歷史快照儲存。`requireEnabledForNewReference` 必須由呼叫者的可寫交易執行，呼叫者應先取得自己的 UPMS 授權／canonical 鎖，再由 CRM 鎖住客戶列。鎖後重新載入資料，避免持久化上下文快取使停用或改名檢查失真；傳回不可變 `Snapshot(id, code, name, version)`，並持鎖到呼叫者提交，以協調停用及改名競爭。

LG-M2-03 只驗證這項引用介面與鎖定契約；LG-M2-04 由 TOM 在同一交易使用來源並保存名稱快照，客戶改名不回寫舊訂單。同客戶草稿替換保留既有名稱，明確換客戶才重新取得有效來源。`isEnabled` 是只讀完整性檢查介面，不能取代新增引用的鎖定查核。正常 migration 不含客戶個資或合成測試紀錄。

## TOM 首片訂單草稿

五項操作依[原 OpenAPI 0.2.0](../docs/06_api/openapi.yaml)：POST／GET `/api/v1/orders`、GET／PUT `/api/v1/orders/{orderId}`、GET `/api/v1/orders/{orderId}/acceptance-check`。僅正常登入 ADMIN、DISPATCHER 可操作本公司資料。原成功 `OK/message/data/traceId` 及錯誤 `fieldErrors` 格式保留，包括安全 filter、CSRF、資料庫失敗及路由錯誤；不套用 UPMS 的 `0000/timestamp` envelope。

V5 的 OrderCore 與 TOM 是同一個持久化聚合：`order_core`、`tom_order`、`cargo_item`、`transport_task`、`task_dependency`、`order_draft_revision`、`api_idempotency`。Spring Data 的 TOM 專屬 repository fragment 以原生 SQL 處理聚合差異更新、延後唯一性檢查及可逆型別，參與同一個 JpaTransactionManager 交易；沒有跨 UPMS／CRM repository。OrderCore 是共用識別與生命週期模型，表的本片寫入責任仍由此聚合擁有，不建立空殼模組再跨寫其 repository。

建立只有 type 與有效 customerId 必填；PUT 為完整替換，未提供的可選純量為 null、集合為空。當前相同 clientRef 保留 UUID／taskNo，移除後另次新增則產生新身份；保存陣列順序，不把順序當作依賴。序號交換可在同一交易完成；失聯、自指、循環或逆向時間整筆拒絕。受保護外鍵阻止移除時回 409，版本、歷程及重送紀錄皆回滾。此包不建立派工、執行或資產表。

TOM 文字與 hash 保留原字串、null、欄位省略及陣列順序，不沿用 CRM 的 trim／NFC 或空白轉 null。原契約容許的 U+0000 使用 UTF-8 bytea 自由文字欄與 textual JSON 歷程可逆保存；不無聲刪除或替換。日期時間先嚴格驗證最多六位小數及原契約 ±23:59 offset，typed timestamptz(6) 以 JDBC 4.2 OffsetDateTime 保存瞬間，避免舊 Timestamp／Calendar 在 1582 年前偏移；wire 欄保留合法讀回表示，系統時間明確截至微秒。重量／體積以 BigDecimal／numeric 保存，讀回允許去除尾零。

POST／PUT 需有效 CSRF 與單一 Idempotency-Key。先檢查目前權限，再以公司範圍、操作者、method、canonical path、key 嘗試 transaction advisory lock；同 key 處理中立即回 409，不先等待全域寫入鎖。成功命中先重驗授權再重播原業務結果與狀態，traceId 每次更新；不先驗舊 expectedVersion 或客戶目前啟用性。新寫入取得 UPMS canonical／操作者鎖、必要的 CRM 客戶鎖及訂單根鎖，與聚合、不可變版本快照、成功重送紀錄原子提交。D10 保存及清理政策尚未核准，沒有自動到期或重用 key，也不宣稱已核准永久保存。

GET 明細、清單及完整性檢查使用同一 repeatable-read snapshot，避免版本、子項或 count 混版。原 page 無上界，採 BigInteger 並先 count；超出資料範圍直接回合法空頁，不把任意上限塞入契約。只讀檢查固定回 ORD-P01–P03、未決政策及 `NOT_GRANTED`，不更動狀態、不授予受理或占用資源。細節見 [TOM 工程決策](../docs/98_decisions/2026-11-25-tom-engineering.md)與[資料模型](../docs/05_database/tom-model.md)。

## FMS 車輛主檔（M3-01）

`fms` 擁有 V6 的 `fms_vehicle`，沿公司 envelope、UPMS Session／CSRF 與 ADMIN／DISPATCHER 本公司授權；DRIVER 全拒絕。新增／更換車種只透過 ADM 的限定歷史查詢及 writable MANDATORY 來源鎖介面，檢查 VEHICLE_TYPE 分類與父子啟用；同車種停用後仍可維護舊主檔。鎖序為 canonical／fresh actor → vehicle → ADM category／item，不跨 repository。

車牌 canonical 後唯一，包含 INACTIVE；PATCH 真部分更新、版本安全整數及單調修改時間確保同值／只改十進位 scale 仍保存一次。`capacity_ton` 是無固定 scale 的 numeric，額外檢查有限、非負及 wire 32 字元，不 round；兩個 LocalDate 欄位局部使用 JDBC 4.2，避免 Gregorian cutover，未更動舊模組映射。

可信 `VehicleQueries.findCurrent` 回不可變參照快照；`requireAdministrativelyAvailable` 需 caller writable transaction、先授權與 canonical 鎖，刷新並鎖住車輛直到提交，只檢管理狀態 AVAILABLE。它不檢查占用、法定車籍／檢驗或派車資格，也不擴充任何 DRIVER HTTP 權限。

V6 不種車輛或正式車種碼，也不建立司機、派工、占用、配對或額外歷史表。API 詳見 [FMS 契約](../docs/06_api/fms-vehicles.md)，空庫／V5→V6／交易／HTTP 的實際結果見 [M3-01 驗證](../docs/09_testing/m3-01-validation.md)。

## FMS 司機主檔（M3-02）

V7 新增 `fms_driver` 與空 `DRIVER_LICENSE_TYPE` 分類，保留 V1–V6；司機編號不可變且唯一，nullable `account_id` 以 FK／UNIQUE 保護選擇性一對一。完整管理明細、列表摘要、行政選項與本人資料使用不同 DTO，避免列表／本人外洩證照工作參考與 audit。

帳號候選與綁定只透過 UPMS 窄介面；FMS 不讀 UPMS repository。候選由 UPMS 分頁，再由 FMS 對該頁標記是否已綁，不在分頁後過濾而破壞 totals。新綁定與寫入依 canonical → fresh actor → driver/version → target account → ADM 分類／項目鎖協調，已失效的相同關聯可保留維護。UPMS 角色／停用／復原仍使用既有 canonical，不新增生命週期副作用。

GET `/fms/drivers/me` 僅 DRIVER，安全 matcher 優先於 FMS 管理路徑。以可鎖定但無業務寫入的交易，canonical → fresh actor → 目前綁定 driver，與解綁／角色異動協調；不快取 driverId，不接受外部識別或查詢。正常身分即使主檔 INACTIVE／LEFT 仍可讀必要本人資料；停帳、撤銷、改角色或解綁依當前授權拒絕。

可信 `DriverQueries` 只提供目前主檔來源與 ACTIVE＋ON_DUTY 的 writable MANDATORY 鎖定查核，不回證照參考或 credential、不判定法規／派車或占用。LEFT 暫不轉回其他 employment；duty 與 employment 不互相自動變更。詳細規則見 [FMS 司機契約](../docs/06_api/fms-drivers.md)、[工程決策](../docs/98_decisions/2026-11-25-fms-driver-engineering.md)及 [M3-02 驗證](../docs/09_testing/m3-02-validation.md)。

## TOM 正式受理（M3-03）

新 POST `/api/v1/orders/{orderId}/accept` 沿 TOM 同源 Session／CSRF、角色與 Idempotency-Key，body 僅 expectedVersion，200 回原 OrderEnvelope。舊 GET／列表／POST／PUT／check DTO 與四份固定契約原樣；GET accept 仍沿未知路徑404，只新增 POST JSON 操作與僅 POST 的媒體格式錯誤處理。

新 key 依 advisory try-key-lock → UPMS canonical／fresh actor → CRM 有效來源 → 訂單根鎖協調。受理只修改根狀態／版本／audit，重新對鎖定聚合驗 ORD-P01–P03，保留歷史 customerName 與子項。前後觀察的 customerId 若不一致則拒絕；不以先前 check 或未核准例外授權。

V8 的 `order_acceptance_event` 每單唯一、版本恰加一、完整 JSON 快照以 composite FK 連結新 revision；UPDATE／DELETE 由 trigger 拒絕，不種事件或替歷史資料捏造人員／時間。使用 textual json 保留 U+0000；不加會解碼自由文字的 jsonb/extraction 檢查。事件、同版 revision、根狀態與 idempotency 同交易提交／回滾，沒有事件改寫或清理 API。

成功 replay 先驗目前授權，跳過今日客戶、前態、原版本與完整性，返回原業務結果／新 traceId。舊 POST／PUT 歷史 replay 在之後受理仍有效；新 key 的 PUT 或再受理由狀態／版本拒絕。公開範圍不包含派工、占用、撤回或正式部署；詳細契約、模型與證據見 [受理 API](../docs/06_api/tom-acceptance.md)、[受理模型](../docs/05_database/tom-acceptance-model.md)及 [M3-03 驗證](../docs/09_testing/m3-03-validation.md)。

## TMS 出發前派工（M3-04）

[獨立 TMS API](../docs/06_api/tms-dispatch.md) 使用公司 envelope，原 TOM 五項與正式受理 envelope 保持不變。V9 新增五個 TMS 自有表與衍生任務索引；generation、每段唯一有效派工、跨主車／拖板位置的 VEHICLE 排他與 DRIVER 排他同交易保護，事件與歷史快照不可改寫。沒有 M4 出發／完成證據與時間預約占用。

TOM 公開 `TaskQueries` 與同交易 `AcceptedTaskIndexSink`；TMS 只對自有表分頁、查占用與保存結果，再透過公開 port 取得 bounded source，不存取其他模組 repository。FMS 公開來源鎖／目前本人綁定與自有 `MasterDataChangeGuard` SPI，TMS 實作占用守衛，保留原 FMS DTO、查詢與行政可用語義。詳見[工程決策](../docs/98_decisions/2026-11-26-tms-dispatch-engineering.md)。

正式政策預設 `UNCONFIRMED`，不能以環境旗標或 ADM 參數解鎖。獨立 E2E 才掛載 test source 的 launcher 與合成 policy（實查 DB 必為 `xk_logistics_e2e`）；正式 JAR 不含這些類別。一般 DEV 仍由原 `LogisticsApplication` 啟動。精確操作、原始字串／decimal／offset 保留、未知重播、升級與回滾證據見 [M3-04 驗證](../docs/09_testing/m3-04-validation.md)。
