# 驗收計畫｜Draft

| 類別 | 必驗情境 |
| --- | --- |
| 領域 | 草稿合法性、多項目多段依賴、狀態守衛、撤改與結案 |
| DB／API | 空庫 Flyway、草稿重讀、重送、併發、交易回滾 |
| 授權 | 三角色的五項草稿 API、本人任務隔離、已核定 Session／CSRF |
| 前端 | 正常、缺漏、錯誤、手機上傳及重試 |
| 上線 | health、核心流程 smoke、備份還原、回滾與 UAT |

JUnit、Testcontainers、Vitest、Playwright 依中央 [APP 測試規範](https://github.com/xk2022/xplore-kaleidoscope/blob/main/doc/04_engineering/app-testing-standard.md)。完整驗收沿用[舊 A01–A14](https://github.com/xk2022/codex-xk-logistic/blob/7092510c7e63a3f5781e1f0a24787a32e3248ac4/docs/06-decisions-and-acceptance.md)及[首片驗收](https://github.com/xk2022/codex-xk-logistic/blob/7092510c7e63a3f5781e1f0a24787a32e3248ac4/docs/FIRST_SLICE_ACCESS_AND_TEST_PLAN.md)，不得以靜態文件檢查代替產品測試。UPMS／ADM／CRM 與 TOM 訂單草稿已執行真實 PostgreSQL／HTTP 測試，結果分別見 [M2-01](m2-01-validation.md)、[M2-02](m2-02-validation.md)、[M2-03](m2-03-validation.md)及 [M2-04](m2-04-validation.md)。本次桌面／手機訂單 UI 與首片整合的執行狀態見 [M2-05](m2-05-validation.md)；正式受理、派工、回報、照片、結案與上線檢查仍屬後續里程碑，不以草稿驗證視為通過。
