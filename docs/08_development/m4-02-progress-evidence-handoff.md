# LG-M4-02｜進度與私有照片交接

前包 PR #23／main CI45769255127 attempt1 四項成功後，從 `daf26f979c1c03c078876f204a713578abe80a4e` 開本包。先讀 AGENTS、README、docs 索引、公司最新工程規範、[Q41–Q47](../98_decisions/2026-11-26-m4-execution.md)、[本包工程採用](../98_decisions/2026-11-26-m4-02-engineering.md)及[獨立 API](../06_api/tms-progress-evidence.md)。

交付五項本人 API、兩個相鄰進度、不可變照片／私有 bytes、獨立政策與 provider fail-closed、V11 四表；保留全部舊 API／測試。正向只用實查隔離 DB 的 test-only 策略與 transaction-aware bytea 替身；正式 D06／D10／provider 未核准，不開放正式照片或依賴動作。完整操作 UI 留 M4-06。

驗收：實際有界 raw／PNG／私有讀取、角色與晚撤權、同原請求重播及 phase 競爭、各寫入點與 deferred commit 整筆回滾、不可覆寫／新增原證據、V10→V11 舊29領域表與 Flyway 列保留、最新34表空庫／重啟、真 TLS 桌面／Pixel7、七份實際 HTTP validators、安全／隱私及全量回歸。依[驗證紀錄](../09_testing/m4-02-validation.md)記實際數量。PR四過才 merge，main四過才開 M4-03；不得提前完成／安全釋放／結案或正式部署。
