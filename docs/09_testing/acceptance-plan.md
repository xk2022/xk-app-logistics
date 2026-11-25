# 驗收計畫｜Draft

| 類別 | 必驗情境 |
| --- | --- |
| 領域 | 草稿合法性、多項目多段依賴、狀態守衛、撤改與結案 |
| DB／API | 空庫 Flyway、草稿重讀、重送、併發、交易回滾 |
| 授權 | 三角色的五項草稿 API、本人任務隔離、已核定 Session／CSRF |
| 前端 | 正常、缺漏、錯誤、手機上傳及重試 |
| 上線 | health、核心流程 smoke、備份還原、回滾與 UAT |

JUnit、Testcontainers、Vitest、Playwright 依中央 [APP 測試規範](https://github.com/xk2022/xplore-kaleidoscope/blob/main/doc/04_engineering/app-testing-standard.md)。完整驗收沿用[舊 A01–A14](https://github.com/xk2022/codex-xk-logistic/blob/7092510c7e63a3f5781e1f0a24787a32e3248ac4/docs/06-decisions-and-acceptance.md)及[首片驗收](https://github.com/xk2022/codex-xk-logistic/blob/7092510c7e63a3f5781e1f0a24787a32e3248ac4/docs/FIRST_SLICE_ACCESS_AND_TEST_PLAN.md)，不得以靜態文件檢查代替產品測試。所有產品測試目前 not_run。
