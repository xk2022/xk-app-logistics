# LG-M3-04 派工與占用｜Ready for assignment

基準 main 0bf51129ba8e019a247b5d0f16ef2f23b3bd3c0d。M3-03 PR #17 已合併。先合併本規格 PR；閱讀 AGENTS、公司規範、[決策](../98_decisions/2026-11-26-tms-dispatch.md)、[流程](../03_domain/tms-dispatch-workflow.md)、[模型](../05_database/tms-dispatch-model.md)、[API](../06_api/tms-dispatch.md)。

定稿獨立 OpenAPI、狀態、版本及鎖序；實作手動指派、原子排他占用、前置守衛、撤改留史、最小本人查詢及 V9。驗收真 PostgreSQL 的同任務／司機／主車／拖板競爭、跨車輛位置排他、撤改派回滾、重送、授權、主檔異動；保留 V1–V8 和所有原契約。沒有正式政策的組合不可宣稱可正式派遣；使用合成驗收並記錄未決限制。前端完整工作台留至 M3-05。四項 CI 全通過後 PR／合併、更新驗收及狀態，再接 M3-05。
