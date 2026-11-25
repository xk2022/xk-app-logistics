# XK Logistics 後端｜LG-M1 / LG-M2-01 / LG-M2-02 / LG-M2-03

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

Session 使用 `XKSESSION`，固定 Secure、HttpOnly、SameSite=Lax、Path=/；不允許透過環境或 profile 降級。每帳號最多三個 Session，超過時撤銷最早登入者；閒置 30 分鐘、登入後最長 8 小時，改密、復原、停用或角色異動使舊認證失效。POST／PATCH 含登入、改密與登出皆檢查 CSRF；登入與登出後重新取得 token。

開發 TLS gateway 覆寫轉送協定標頭，後端採 framework forwarded headers。此信任只適用於受控反向代理：不得將後端埠直接對外公開，正式環境必須限制可達代理並覆寫／清除用戶端轉送標頭。僅經 HTTPS 的請求可使用 UPMS。Session Registry 與登入限流為單 instance 記憶體實作，不支援多 instance；正式部署前須另驗證分散式撤銷、限流與信任邊界。

密碼採 NFC、15–128 個 Unicode code point、PBKDF2-HMAC-SHA256 600,000 次、16-byte salt 與 256-bit 輸出。有限常見密碼集合固定來源及授權見 [SOURCE.md](src/main/resources/upms/passwords/SOURCE.md)。完整工程政策及限制見[安全 ADR](../docs/98_decisions/2026-11-25-upms-engineering.md)與[API 契約](../docs/06_api/upms.md)。

## 範圍與模組邊界

`platform` 只提供 `/actuator/health`、`/api/v1/status` 及伺服器產生的請求識別碼。Health 僅公開狀態；status 依資料庫健康回傳 HTTP 200／503、繁中訊息與 `+08:00` timestamp。`upms` 提供固定角色、帳號管理與同源認證；`adm` 提供分類、字典項目、核准參數與異動紀錄；`crm` 提供客戶、聯絡人及常用地址主檔。尚未實作訂單或派工等物流流程。

Flyway 是唯一結構來源，建立 `logistics` schema 與歷程表；V1 保留原空庫基線，V2 增加帳號、audit 與單一協調鎖資料列，V3 增加 ADM 五表，V4 增加 CRM 三表。Migration 不建立使用者帳號或客戶種子。Hibernate 採 `validate`，禁止以自動 DDL 取代 migration。已套用的 migration 不可修改，未來以新版本遞增。

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

本階段只驗證這項引用介面與鎖定契約，不建立 TOM 表、訂單端點或實際訂單快照，不宣稱訂單流程已通過驗收。後續 TOM 必須在同一交易使用來源並保存快照；客戶改名不能回寫既有訂單快照。全部正常 V4 migration 不含客戶個資或合成測試紀錄。
