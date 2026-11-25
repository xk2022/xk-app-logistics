# LG-M2-02 ADM 工程決策

日期：2026-11-25。依 [Q11–Q15](2026-11-25-adm.md)及 [ADM 交接](../08_development/m2-02-adm-handoff.md)，從 PR #5 合併基準繼續實作。公司規範基準 `ae9bce5a95d1565d76e76a0414fde7d29a1ed881`；保留 M1 鎖版與 [UPMS 安全工程決策](2026-11-25-upms-engineering.md)。本文記錄工程細化，不替尚未核准的字典項目、正式參數鍵或非 ADMIN 權限作決定；實測與受測版本另記。

## 模組與授權

- ADM 負責雙層字典、核定參數值與異動紀錄；不實作 CRM、TOM、FMS、派工、照片、物流狀態機或正式部署。
- 所有本包 HTTP 讀寫均限正常登入的 ADMIN。DISPATCHER／DRIVER 尚未取得任何分類讀取權限，包含 `/adm/options/{categoryCode}`，一律拒絕；不能因端點名為 options 就公開給所有角色。
- 延續同源 HTTPS Session + CSRF。所有 POST／PATCH 均驗 CSRF；不提供 DELETE、definition 管理、任意狀態機編輯或安全政策管理 API。強制改密、到期或撤銷的認證不能操作 ADM。
- ADM 透過 UPMS 公開授權 facade 驗身分，禁止引用 UPMS internal repository。`requireAdminForWrite(SessionPrincipal)` 要求既有交易（MANDATORY）；在同一交易鎖 canonical singleton row，再鎖定並重新核對操作者啟用、單角色、mustChangePassword 及 authVersion。ADM 然後鎖定目標資料，與 UPMS 停權／角色／復原／改密採一致鎖順序。
- 後續業務模組只能透過 ADM 公開服務查有效選項，不能跨 repository。`AdmQueries` 是可信內部 lookup、不帶 SessionPrincipal，不會自動代替呼叫模組驗終端使用者角色；HTTP 權限在控制器／公開 UPMS guard 執行。未來非 ADMIN 分類權限必須另核准並加入伺服器端守衛與測試；本包不預先開放。

## 穩定代碼、停用與固定枚舉

- 分類 `code` 全域唯一；項目 `(categoryId, code)` 唯一。分類／項目代碼與參數 key 建立後不可改，項目不可換分類。未知 JSON 欄位、偽造系統身份及更新時夾帶不可變欄位皆回 400，不默默忽略。
- 無硬刪除，以 enabled 保留歷史資料。管理列表可見停用資料；options 僅回傳同時啟用的分類及項目，排序為 sortOrder、code、id。分類停用回空選項，不級聯改寫所有子項目的旗標。
- V3 建立唯讀 `ORDER_TYPE` 分類與既有 `IMPORT`、`EXPORT`、`LOCAL` 項目，標籤直接使用枚舉字串。分類與其固定項目不接受啟停、排序、名稱／標籤修改或新增項目；唯讀是系統身份，不能由 client 指定。
- 此展示對照不是訂單枚舉的可配置來源。原訂單 OpenAPI、角色矩陣、欄位字典及產品決議保持原樣。其他 `CONTAINER_TYPE`、`TASK_KIND`、`VEHICLE_TYPE` 只有空分類，不自行填正式項目。

## 參數核定目錄

只靠 UI 限制不足以區分正式業務參數與任意設定，因此在原四張 ADM 表之外加入只讀 `adm_parameter_definition` metadata。這是執行 Q14 的核定邊界，不代表批准任何新 key 或額外操作權限。

- 正常 V3 的 definition／parameter 表均空，沒有正式核准參數或預設值。ADMIN 只能讀目錄，不能經 HTTP 新增／改寫 definition；未來須以另經業務核准及審查的 migration 建立定義。
- 新增 `GET /api/v1/adm/parameter-definitions` 只讀端點，供 ADMIN UI 顯示可選 key、型別、用途與限制。空目錄須呈現明確空狀態，不能提供任意鍵值輸入作為替代。
- 參數 key／valueType 以複合 FK 連到 definition，建立時型別必須相同；更新不接受 key／valueType／限制欄位。未知、未核定或安全／部署用途的 key 拒絕建立。
- 目錄限制只接受 TEXT 長度區間或完整 allowedValues；NUMBER 必填有端點上下界及 decimalScale；BOOLEAN 不帶文字／數字限制。保留 namespace 防護以排除 AUTH、SESSION、SECURITY、SECRET、PASSWORD、TOKEN、CSRF、JWT、COOKIE、TLS、SSL、DATABASE、DATASOURCE、SMTP、CREDENTIAL、INFRA、PORT、HOST、URL、URI、ORIGIN、CORS、ITERATIONS、LOGIN、PBKDF2、RATE_LIMIT 等安全／基礎設施 token；valueText 也拒絕可識別的憑證／連線秘密格式。不得將此當作可辨識所有秘密內容的 DLP 保證。
- TEXT 做 NFC、不 trim，最多 2000 code points，再驗核定長度或整筆允許值。NUMBER 使用有限 plain decimal 字串及 BigDecimal，拒絕科學記號，最多 38 位精度／12 位小數，再驗定義的精度、上下界與小數位；canonical value 去除多餘尾零，以 plain string 保存。BOOLEAN 只接受小寫 true／false。
- 描述或數值不是儲存秘密的管道；不存憑證、認證狀態、固定狀態機、角色、限流或部署設定。正式 definition 核准時須確認用途非敏感；未核准前不因工程需要自行補預設 key。
- 測試在 `src/test` 的隔離 DB 插入 `SYNTHETIC_TEXT`、`SYNTHETIC_NUMBER`、`SYNTHETIC_BOOLEAN` 定義，驗三型別及 UI；不放入 V3、不打包 seed 入口、不提供一般 profile／環境變數可開的後門。測試 key 不變成正式產品核准值。

## 交易與稽核

資料變更和 JSONB 前後 DTO 快照同一交易提交，操作者來自已驗證的 UPMS 身分，不能由 request 偽造。新增 before 為 null，更新包含前後資料；不把帳號密碼、Session ID、CSRF 或內部憑證放入 ADM 稽核。稽核失敗時回滾變更，不回報成功。

更新需要非負 `version`；在授權及目標鎖定後比較目前版本，衝突回 409。資料庫唯一鍵及 FK 提供最後一致性約束；不修改既有 V1/V2，以新增 V3 驗空庫、升級、重跑與約束。之後修正已套用 schema 必須另加 migration，不改已發布版本內容。

稽核只查詢、不可經 HTTP 修改／刪除。本階段沒有訂單等消費者；options 查詢結果不能當作後續業務寫入時仍有效的保證。未來新增引用時須在其交易中重新驗證權限與啟用狀態，不以這次讀取驗收替代完整性驗證。

## 查詢與錯誤契約

- 列表預設 page=0、size=20，page 0–1000000、size 1–100；只接受單一 `sort=field,direction` 白名單，補 code／key／id 穩定次鍵。異動預設 occurredAt desc、id desc。拒絕未知或重複 query key、非法分頁與排序，不把任意使用者欄位轉成資料庫排序表達式。
- q 做 NFC／strip，最多 100 code points，使用參數化的字面 contains 比對；`%`、`_` 等不作擴張萬用字元。enabled 僅為列表篩選，不代表可讀取未授權資料。異動記錄僅允 targetType／targetId 篩選。
- HTTP／envelope 沿公司基準；成功 200／201，無效欄位 400、未登入 401、未授權 403、目標不存在 404、重複或版本衝突 409；唯讀異動 400，無 DELETE 為 405（寫入仍先驗 CSRF）。服務失敗採 sanitized 500／503，不回傳內部例外或 SQL。
- [ADM API](../06_api/adm.md)與獨立機器契約描述本包新增操作；不更動原訂單 OpenAPI。前端依實際回應呈現空目錄、停用、衝突、權限與重試，不顯示假成功。

## 驗證界線

本包須驗 V3 空庫／V2 升級、唯一鍵及 FK、不可變／唯讀、停用選項、分頁／穩定排序／字面搜尋、ADMIN／非 ADMIN、失效／強制改密認證、CSRF、目錄空狀態、三型別邊界、稽核回滾、前端及 TLS E2E，並保留 UPMS／M1 測試。實際結果及限制以驗證紀錄為準，不因規格存在就標示完成。

剩餘待核准：正式字典項目、正式參數 key／用途／限制／初值、非 ADMIN 分類權限及後續業務引用；此外正式部署與維運仍另行核准。這些待決不授權新增合成正式值，也不阻止以隔離測試驗證工程能力。

## 工程參考

- [公司 APP 規範](https://github.com/xk2022/xplore-kaleidoscope/blob/ae9bce5a95d1565d76e76a0414fde7d29a1ed881/doc/04_engineering/app-development-standard.md)與 [API／資料規範](https://github.com/xk2022/xplore-kaleidoscope/blob/ae9bce5a95d1565d76e76a0414fde7d29a1ed881/doc/04_engineering/api-and-data.md)。
- [PostgreSQL 17 約束](https://www.postgresql.org/docs/17/ddl-constraints.html)：唯一鍵、FK 與 CHECK；需要拒絕 NULL 的條件不能只靠會回傳 unknown 的 CHECK 表達式。
- [Spring Data JPA Locking](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html)與 [Spring 交易 propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)：明確交易與鎖定邊界。
