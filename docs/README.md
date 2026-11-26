# 文件索引｜M0 / M1 / M2 / M3 / M4

日期：2026-11-26（Asia/Taipei）。依 APP 規範使用 docs/；數字前綴分類。文件狀態：Draft、In Review、Approved、Deprecated。Q01–Q47 已核准；本批細化規格保留歷史決策的效力，未決例外另列。

| 分類 | 文件 |
| --- | --- |
| 00_overview | [M0 狀態](00_overview/status.md) |
| 01_product | [PRD](01_product/prd.md)、[MVP 與路線圖](01_product/mvp-roadmap.md) |
| 02_requirements | [功能目錄](02_requirements/feature-catalog.md) |
| 03_domain | [領域與流程](03_domain/workflow.md)、[TMS 流程](03_domain/tms-dispatch-workflow.md) |
| 04_architecture | [技術架構](04_architecture/architecture.md)、[角色與認證](04_architecture/access-baseline.md)、[M1 精確版本](04_architecture/m1-versions.md) |
| 05_database | [邏輯模型](05_database/logical-model.md)、[首片欄位字典](05_database/first-slice-fields.csv)、[ADM 模型](05_database/adm-model.md)、[CRM 模型](05_database/crm-model.md)、[OrderCore／TOM 模型](05_database/tom-model.md)、[TOM 受理模型](05_database/tom-acceptance-model.md)、[FMS 車輛模型](05_database/fms-vehicle-model.md)、[FMS 司機模型](05_database/fms-driver-model.md)、[TMS 模型](05_database/tms-dispatch-model.md) |
| 06_api | [TMS API](06_api/tms-dispatch.md)／[獨立 OpenAPI](06_api/tms-dispatch-openapi.yaml)、[FMS 司機 API](06_api/fms-drivers.md)／[獨立 OpenAPI](06_api/fms-drivers-openapi.yaml)、[FMS 車輛 API](06_api/fms-vehicles.md)／[獨立 OpenAPI](06_api/fms-vehicles-openapi.yaml)、[TOM 契約整合](06_api/tom.md)、[TOM 正式受理](06_api/tom-acceptance.md)／[獨立 OpenAPI](06_api/tom-acceptance-openapi.yaml)、[CRM 契約](06_api/crm.md)／[獨立 OpenAPI](06_api/crm-openapi.yaml)、[ADM 契約](06_api/adm.md)、[UPMS 契約](06_api/upms.md)、[首片 API](06_api/first-slice.md)、[OpenAPI](06_api/openapi.yaml)、[角色契約](06_api/order-draft-access-policy.json)、[合成案例](06_api/order-draft-contract-examples.json) |
| 07_ui-ux | [頁面規劃](07_ui-ux/sitemap.md)、[訂單工作台](07_ui-ux/order-workspace.md)、[調度工作台](07_ui-ux/dispatch-workspace.md) |
| 08_development | [開發工作包](08_development/development-plan.md)、[M1 交接](08_development/m1-bootstrap-handoff.md)、[M2-01 交接](08_development/m2-01-upms-handoff.md)、[M2-02 交接](08_development/m2-02-adm-handoff.md)、[M2-03 交接](08_development/m2-03-crm-handoff.md)、[M2-04 交接](08_development/m2-04-tom-handoff.md)、[M2-05 交接](08_development/m2-05-order-ui-handoff.md)、[M3-01 交接](08_development/m3-01-fms-vehicle-handoff.md)、[M3-02 交接](08_development/m3-02-fms-driver-handoff.md)、[M3-03 交接](08_development/m3-03-tom-acceptance-handoff.md)、[M3-04](08_development/m3-04-dispatch-handoff.md)、[M3-05](08_development/m3-05-dispatch-ui-handoff.md)、[M3-06](08_development/m3-06-integration-handoff.md) |
| 09_testing | [M3-06 驗證](09_testing/m3-06-validation.md)、[M3-05 驗證](09_testing/m3-05-validation.md)、[M3-04 驗證](09_testing/m3-04-validation.md)、 [驗收計畫](09_testing/acceptance-plan.md)、[首片驗收案例](09_testing/first-slice-acceptance.md)、[M1 驗證](09_testing/m1-validation.md)、[M2-01 驗證](09_testing/m2-01-validation.md)、[M2-02 驗證](09_testing/m2-02-validation.md)、[M2-03 驗證](09_testing/m2-03-validation.md)、[M2-04 驗證](09_testing/m2-04-validation.md)、[M2-05 驗證](09_testing/m2-05-validation.md)、[M3-01 驗證](09_testing/m3-01-validation.md)、[M3-02 驗證](09_testing/m3-02-validation.md)、[M3-03 驗證](09_testing/m3-03-validation.md)、[M3 整合驗收](09_testing/m3-integration-acceptance.md) |
| 10_deployment | [環境策略](10_deployment/environments.md) |
| 11_operations | [營運及商業化](11_operations/operations.md) |
| 12_migration | [舊庫盤點與移轉](12_migration/legacy-assessment.md) |
| 98_decisions | [TMS 工程決策](98_decisions/2026-11-26-tms-dispatch-engineering.md)、 [產品基準與待決](98_decisions/2026-11-25-foundation.md)、[M2 帳號與權限決策](98_decisions/2026-11-25-m2-access.md)、[UPMS 工程決策](98_decisions/2026-11-25-upms-engineering.md)、[ADM 採用決策](98_decisions/2026-11-25-adm.md)、[ADM 工程決策](98_decisions/2026-11-25-adm-engineering.md)、[CRM 採用決策](98_decisions/2026-11-25-crm.md)、[CRM 工程決策](98_decisions/2026-11-25-crm-engineering.md)、[TOM 工程決策](98_decisions/2026-11-25-tom-engineering.md)、[TOM UI 工程決策](98_decisions/2026-11-25-tom-ui-engineering.md)、[FMS 車輛決策](98_decisions/2026-11-25-fms-vehicle.md)、[FMS 車輛工程](98_decisions/2026-11-25-fms-vehicle-engineering.md)、[FMS 司機決策](98_decisions/2026-11-25-fms-driver.md)、[FMS 司機工程](98_decisions/2026-11-25-fms-driver-engineering.md)、[TOM 受理決策](98_decisions/2026-11-26-tom-acceptance.md)、[TOM 受理工程](98_decisions/2026-11-26-tom-acceptance-engineering.md)、[TMS 決策](98_decisions/2026-11-26-tms-dispatch.md) |

99_archive 按需要建立。引用的舊規格固定至來源 commit；確認有變更時以新決策記錄差異。


## M4 採用規格
- [2026-11-26-m4-execution.md](98_decisions/2026-11-26-m4-execution.md)
- [tms-execution-workflow.md](03_domain/tms-execution-workflow.md)
- [tms-execution-model.md](05_database/tms-execution-model.md)
- [tms-execution.md](06_api/tms-execution.md)
- [driver-execution-workspace.md](07_ui-ux/driver-execution-workspace.md)
- [m4-execution-handoff.md](08_development/m4-execution-handoff.md)
- [m4-integration-acceptance.md](09_testing/m4-integration-acceptance.md)

### M4-01 工程與實測

- [本人詳情／出發交接](08_development/m4-01-driver-departure-handoff.md)
- [M4-01 工程採用](98_decisions/2026-11-26-m4-01-engineering.md)
- [M4-01 驗證](09_testing/m4-01-validation.md)

### M4-02 工程與驗證

- [進度與私有照片交接](08_development/m4-02-progress-evidence-handoff.md)
- [M4-02 工程採用](98_decisions/2026-11-26-m4-02-engineering.md)
- [獨立 API](06_api/tms-progress-evidence.md)／[OpenAPI](06_api/tms-progress-evidence-openapi.yaml)
- [M4-02 驗證](09_testing/m4-02-validation.md)

### M4-03 工程與驗證

- [異常與補件交接](08_development/m4-03-exceptions-handoff.md)
- [工程採用](98_decisions/2026-11-26-m4-03-engineering.md)
- [獨立API](06_api/tms-exceptions.md)／[OpenAPI](06_api/tms-exceptions-openapi.yaml)
- [M4-03驗證](09_testing/m4-03-validation.md)

### M4-04 工程與驗證

- [完成契約](06_api/tms-completion.md)及[獨立OpenAPI](06_api/tms-completion-openapi.yaml)
- [工程採用](98_decisions/2026-11-26-m4-04-engineering.md)
- [工作包](08_development/m4-04-task-completion-handoff.md)
- [完成及有效釋放驗證](09_testing/m4-04-validation.md)

### M4-05 工程與驗證

- [管理確認及結案交接](08_development/m4-05-management-closure-handoff.md)
- [工程採用](98_decisions/2026-11-26-m4-05-engineering.md)
- [獨立API](06_api/tom-closure.md)／[OpenAPI](06_api/tom-closure-openapi.yaml)
- [驗證](09_testing/m4-05-validation.md)

- [M4-05 main CI焦點穩定化](09_testing/m4-05-ci-stabilization.md)
