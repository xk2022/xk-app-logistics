# LG-M1 精確版本與相容性查核

日期：2026-11-25。工作包：LG-M1。這份文件記錄官方來源與套件登錄庫的可取得性查核；工程建置、整合測試及 CI 結果由本次交付的驗收紀錄另行記載。版本存在及符合宣告相依範圍，不代表已通過服務啟動或資料庫整合測試。

## 採用依據

- 公司規範固定參照 `xk2022/xplore-kaleidoscope` commit [`ae9bce5a95d1565d76e76a0414fde7d29a1ed881`](https://github.com/xk2022/xplore-kaleidoscope/tree/ae9bce5a95d1565d76e76a0414fde7d29a1ed881)，包含 APP 開發、部署、測試、維運、API 與開發流程規範。
- 保留[既有產品架構](architecture.md)的 Java 21、Spring Boot 4.1.x、PostgreSQL 17、Spring Data JPA 與 Flyway 方向。公司架構中的 Spring Boot 3.5.14 是待驗證基準；本次已確認 4.1.3 正式版本可取得，因此不需要降版或改變產品技術選型。
- 不引入 Spring AI、pgvector、登入、安全政策或業務 API；本工作包只建立工程基礎。既有[產品決議](../98_decisions/2026-11-25-foundation.md)及 API 契約維持效力。

## 後端與資料庫

| 元件 | 精確版本 | 鎖定方式／查核結果 |
| --- | --- | --- |
| Java | 21；環境 OpenJDK JRE 21.0.13；本機建置 Temurin JDK 21.0.12.1 | Maven `java.version=21`；Cloud 預裝僅 JRE，verify 使用 Maven 映像內 JDK 21.0.12.1；容器 runtime 為 Temurin 21.0.13，CI 使用 Temurin 21.0.13。均以 Java 21 release 編譯。 |
| Spring Boot | 4.1.3 | Maven parent；官方 Maven Central 正式發行可取得。 |
| Maven | 3.9.16 | Maven Wrapper 的 distribution URL。 |
| Maven Wrapper | 3.3.4 | 版本化 wrapper scripts 與 properties。 |
| Spring Framework | 7.0.11 | Spring Boot 4.1.3 BOM 管理。 |
| Spring Data BOM／JPA | 2026.0.3／4.1.3 | 由 Boot BOM 匯入 Data BOM，無自行覆寫版本。 |
| Hibernate ORM | 7.4.15.Final | Boot BOM 管理；結構採 Hibernate validate，migration 由 Flyway 管理。 |
| Flyway | 12.4.0 | Boot BOM 管理；使用 `spring-boot-starter-flyway` 與 `flyway-database-postgresql`。 |
| PostgreSQL JDBC | 42.7.14 | Boot BOM 管理。 |
| JUnit Jupiter | 6.0.3 | Boot BOM 管理。 |
| Testcontainers | 2.0.5 | Boot BOM 管理。 |
| PostgreSQL | 17.12-bookworm | Compose 與測試映像固定版本及 manifest digest。 |

PostgreSQL 映像：

```text
postgres:17.12-bookworm@sha256:1eff563f6c868b9b433566e9f3cf6e5c79fbd3b95bf70ec7277d376cdbfc50d7
```

官方 Spring Boot 4.1.3 系統需求為 Java 17–27、Maven 3.6.3 以上；Java 21 與 Maven 3.9.16 符合宣告範圍。Flyway 12.4.0 PostgreSQL 支援程式涵蓋 PostgreSQL 17；此項仍需用空庫 migration、health 與實際 PostgreSQL 整合測試驗證。PostgreSQL 官方版本政策列 17.12 為 17 支線當前修補版本，17 支線支援至 2029-11-08。

Maven 發行檔官方 `.sha512` 可取得；`.sha256` 端點回應 404。若 wrapper 設定 `distributionSha256Sum`，應先核對官方 SHA512，再計算下載檔的 SHA256，不能將不存在的校驗來源當作已驗證。

## 前端

| 元件 | 精確版本 |
| --- | --- |
| Node.js／npm | 24.22.3／11.19.1 |
| React／React DOM | 19.3.1／19.3.1 |
| TypeScript | 6.0.3 |
| Vite／`@vitejs/plugin-react` | 8.3.8／6.3.0 |
| Vitest／jsdom | 5.1.1／31.0.1 |
| ESLint／`@eslint/js` | 10.15.0／10.0.1 |
| `typescript-eslint` | 8.74.0 |
| `eslint-plugin-react-hooks`／`eslint-plugin-react-refresh` | 7.1.1／0.5.8 |
| `@testing-library/react`／`@testing-library/dom`／`@testing-library/jest-dom` | 16.3.3／10.4.2／8.0.0 |
| `@types/node`／`@types/react`／`@types/react-dom` | 24.19.2／19.3.1／19.3.0 |

直接相依採精確版本，遞移相依由 `frontend/package-lock.json` 鎖定，使用 `npm ci` 還原。查核使用官方 npm registry 的 `version`、`engines`、`peerDependencies` 及 `peerDependenciesMeta` 資料。

相容性決策：查核當時 TypeScript 最新正式版為 7.0.2，但 `typescript-eslint@8.74.0` 宣告支援 `>=4.8.4 <6.1.0`，因此採 6.0.3。這是工具鏈相容性鎖版，不改變 TypeScript 技術選型。Vite 8.3.8、React plugin 6.3.0、Vitest 5.1.1、ESLint 10.15.0、jsdom 31.0.1 的 Node engines 均接受 24.22.3；Vitest 接受 Vite 8，React Testing Library 接受 React 19。React plugin 的編譯器相關額外 peer dependencies 標為 optional。

E2E 工具查核到 `@playwright/test@1.65.1`，其 Node engines 為 `>=20`；已採用根目錄 `package.json` 與 `package-lock.json` 鎖定，測試設定位於 `tests/e2e/`。

## 可重現查核與官方來源

```sh
node --version
npm --version
java -version
npm view typescript@6.0.3 version engines --json
npm view typescript-eslint@8.74.0 engines peerDependencies --json
npm view vite@8.3.8 engines peerDependencies --json
npm view @vitejs/plugin-react@6.3.0 engines peerDependencies peerDependenciesMeta --json
npm view vitest@5.1.1 engines peerDependencies --json
```

- [Spring Boot 4.1 官方系統需求](https://docs.spring.io/spring-boot/4.1/system-requirements.html)
- [Spring Boot 4.1.3 BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.3/spring-boot-dependencies-4.1.3.pom)
- [Spring Data 2026.0.3 BOM](https://repo.maven.apache.org/maven2/org/springframework/data/spring-data-bom/2026.0.3/spring-data-bom-2026.0.3.pom)
- [Maven 版本登錄](https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/maven-metadata.xml)及 [3.9.16 發行檔 SHA512](https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip.sha512)
- [Maven Wrapper 版本登錄](https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/maven-metadata.xml)
- [Flyway 12.4.0 PostgreSQL 支援程式](https://github.com/flyway/flyway/blob/flyway-12.4.0/flyway-database/flyway-database-postgresql/src/main/java/org/flywaydb/database/postgresql/PostgreSQLDatabase.java)
- [PostgreSQL 版本支援政策](https://www.postgresql.org/support/versioning/)
- [Docker Hub 官方 PostgreSQL 17.12-bookworm metadata](https://registry.hub.docker.com/v2/repositories/library/postgres/tags/17.12-bookworm)
- [官方 npm registry](https://registry.npmjs.org/)；上述 `npm view` 以精確版本查詢。

本文件未將尚未執行的前端 lint/typecheck/test/build、Maven verify、容器啟動或 CI 標為通過；交付驗收應以受測 commit、實際命令及 workflow run 為證據。精確版本鎖定提供重現性，依賴安全檢查及後續更新仍由 CI 與維運流程持續執行。

## M4-05 CI 工具解析穩定化

Maven Dependency Plugin固定使用完整goal座標`org.apache.maven.plugins:maven-dependency-plugin:3.10.0:build-classpath`。3.10.0為本專案既有成功classpath步驟實際解析版本（Java21／Maven3.9.16），不是升級產品相依。固定group/artifact/version避免前綴掃描多個plugin group的動態metadata；Maven Central429仍不會被忽略。完整座標、本機classpath一致性及遠端四項CI依[M4-05補正證據](../09_testing/m4-05-ci-stabilization.md)核對。

## M4-06 真實背景瀏覽器驗證

Playwright1.65.1所附Chromium159.0.8154.6，配合Xvfb `2:21.1.7-3+deb12u13`／xauth `1:1.1.2-1.1`的本機原生預設context檢查，已實際確認分頁hidden→visible。單用headed仍受Playwright預設focus emulation影響，故背景子檢查使用官方connectOverCDP的noDefaults=true，未覆寫visibility或派送假事件。其他完整桌面／Pixel7操作仍使用原測試contexts。CI明確安裝xvfb／xauth並輸出當輪OS套件精確版本；實際整輪結果見[M4-06驗證](../09_testing/m4-06-validation.md)。

本機缺少系統安裝helper，Xvfb由APT驗證的Debian套件解壓到/tmp，沿用既有系統libs與xauth，未修改主機套件或sudo。下載使用受信CA及簽章驗證，不關閉TLS。測試用原生Chromium僅對本機DEV憑證公鑰SPKI設定信任，不使用全域忽略憑證錯誤；私有profile／CDP位址不進報告。
