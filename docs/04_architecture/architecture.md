# 技術架構｜Draft

依公司 [APP 開發規範](https://github.com/xk2022/xplore-kaleidoscope/blob/main/doc/04_engineering/app-development-standard.md) 採 Monorepo + Modular Monolith。

| 元件 | 基準 |
| --- | --- |
| frontend/ | React、TypeScript、Vite |
| backend/ | Java 21、Spring Boot、Maven、Spring Data JPA |
| DB | PostgreSQL 17、Flyway，Hibernate 驗證結構 |
| 認證 | 舊產品已採用同源 Session + CSRF；細部政策待確認 |
| infrastructure/ | Docker Compose、反向代理、環境隔離 |
| tests/e2e/ | Playwright |
| CI | GitHub Actions |

舊產品 ADR 已採用 Spring Boot 4.1.x，而中央架構文件的 3.5.14 是待驗證基準，並非強制鎖版。保留產品既有採用方向，M1 前核對官方可用版本、BOM、驅動及相容性；若需要改版記錄 ADR。不得在文件階段宣稱相依已驗證。本輪 M1 的精確版本及官方相容性查核見 [版本紀錄](m1-versions.md)，實測見 [M1 驗證](../09_testing/m1-validation.md)。

同源 /api 路由須在 DEMO／STG／PRD 驗證 cookie、HTTPS 與 CSRF。跨領域僅通過明確介面，禁止直接操作其他模組 repository；派工及釋放需要原子交易與競爭測試。先單租戶、不引入微服務，照片儲存提供者尚未定案。

來源：[技術決策](https://github.com/xk2022/codex-xk-logistic/blob/7092510c7e63a3f5781e1f0a24787a32e3248ac4/docs/TECHNOLOGY_AND_DATA_PROPOSAL.md)。
