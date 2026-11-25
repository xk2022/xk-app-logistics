# XK Logistics 後端｜LG-M1

Java 21、Spring Boot 4.1.3、Maven 3.9.16（Wrapper 3.3.4）。相依由 Spring Boot BOM 統一管理：Spring Data JPA 4.1.3、Hibernate 7.4.15.Final、Flyway 12.4.0、PostgreSQL JDBC 42.7.14、JUnit 6.0.3、Testcontainers 2.0.5。完整版本依據及專案啟動見[根目錄 README](../README.md)。

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

服務預設在 `http://localhost:8084`；可用 `SERVER_PORT` 改埠。標準 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD` 亦可覆寫連線設定，Compose 採此方式。

## 範圍與模組邊界

`platform` 只提供 `/actuator/health`、`/api/v1/status` 及伺服器產生的請求識別碼。Health 僅公開狀態；status 依資料庫健康回傳 HTTP 200／503、繁中訊息與 `+08:00` timestamp。尚無登入或物流業務 API。

Flyway 是唯一結構來源，建立 `logistics` schema 與歷程表；`V1__initialize_platform.sql` 僅記錄空庫基線，不建立業務表或資料。Hibernate 採 `validate`，禁止以自動 DDL 取代 migration。已套用的 migration 不可修改，未來以新版本遞增。

未來業務模組依領域建立套件，僅透過明確的公開介面協作，不跨模組存取 repository。M2 業務與安全設定須另依既有契約及已核准決議實作。
