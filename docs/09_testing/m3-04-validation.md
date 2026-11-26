# LG-M3-04 TMS 派工與資源占用驗證

## 基準與授權

前置 [PR #18](https://github.com/xk2022/xk-app-logistics/pull/18) source `f796287f08c185c5d19b71b09e1df49075741fec` 已逐份驗收 15 份文件、138 個相對連結，原契約與 V1–V8 不變。[CI 45734934106](https://github.com/xk2022/xk-app-logistics/actions/runs/45734934106) attempt 1 在測試前下載 Spring Boot Maven plugin 遭官方 Central 429，其餘三項成功；只重跑失敗工作，attempt 2 四項全過後合併。merge `5bb6aa82bde4e98de781ed9dbb9844df4e672b8f`；[main CI 45735908231](https://github.com/xk2022/xk-app-logistics/actions/runs/45735908231) attempt 1 四項全過，由此建立本工作分支。

公司規範固定 `xk2022/xplore-kaleidoscope@5be640ab11811b24706101ea13db73185e9ce4d8`，沿用精確技術版本與全部依賴，保留原契約與已採用決議。範圍見 [Q36–Q40](../98_decisions/2026-11-26-tms-dispatch.md)、[工程決策](../98_decisions/2026-11-26-tms-dispatch-engineering.md)及[獨立 OpenAPI](../06_api/tms-dispatch-openapi.yaml)。本包只有出發前派工 API／占用，完整 UI 須於本包合併及 main CI 全過後由 M3-05 整合。

## 實際檢查

| 項目 | 結果 |
| --- | --- |
| Frontend lint／typecheck／test／build | 全過；20 files／426 tests、62 modules，本包沒有前端產品變更 |
| Maven verify | 全量 369 通過＝124 unit＋245 integration，0 failure／error／skip，最終完整執行 5 分 51 秒；原 312 全保留 |
| 新 TMS 及 PostgreSQL | TmsIT 39、正常應用 DefaultPolicyIT 1、Platform 10 全過；另有 validation 7、synthetic policy 7、error privacy 2 單元；含 V8→V9 原 21 張領域表資料指紋與原始 NUL／decimal／offset 保留 |
| 真實 HTTPS 桌面／Pixel 7 E2E | 完整 26／26，2 workers、0 retries、1.9 分鐘，原 24 全保留；fixture 62 個互相獨立合成帳號，業務資料全部經正常 API 建立 |
| 原 TOM／受理／FMS 契約 | 四份原 TOM 來源 SHA-256 不變；原 TOM 實際 414／10 files、Acceptance 30／2、Vehicle 174／2、Driver 282／2 全過，既有 schema／validator 未改 |
| TMS 獨立契約 | 7 operations／42 schemas／4 source hashes、51 正例／113 反例／8 引用防護、17 CLI 失敗路徑安全拒絕；實際 HTTP 134／2 files 全過 |
| 安全與報告 | root／frontend npm audit 均 0；含測試的 CycloneDX SBOM 成功，OSV all-vulns 掃描 409 筆（3＋255＋7＋144）無已知弱點；全 history／staged gitleaks 與同批 E2E 隱私全過，沒有 scanner 排除 |
| 文件／工具與舊基準 | 12 份 Markdown、213 相對連結有效；actionlint、全 E2E strict TypeScript、diff check 全過；V1–V8、原契約／validator／依賴／測試保留 |
| GitHub 交付 | 本包 source／merge SHA、PR 及四項 CI／合併後 main 結果由交付 PR 與 GitHub 執行紀錄追蹤；全部成功後才進入 M3-05 |

開發首輪修正兩項新增測試相容性：OptionalAssert 不提供 doesNotContain，改以 Optional 值的布林結果保持相同斷言；合成 customerCode 的 UUID 原有小寫，不符合既有 CRM 大寫規則，改生成大寫測試代碼，不修改 CRM。修正後新模組診斷及全量 verify 重跑通過。

審查另確認 legacy ACCEPTED 來源仍可能保留 nullable itemKind，Java immutable Set 的 contains(null) 會誤回 500；改為既有 enum 守衛的 null-safe 比較，並加入實際 HTTP 409／原資料與占用 digest 不變的回歸，沒有發明標的類型或增加業務必填。TMS 沿既有 ErrorData.reason 明確區分 CSRF_INVALID／FORBIDDEN，補兩項安全單元與實際 HTTP 斷言；匿名 CSRF 仍 401／null，原 TOM/FMS envelope 不變。最終全量 369、26 E2E 與五份實際回應驗證均包含這些變更。

## 必驗行為

- 三類原始訂單圖由草稿→正式受理→首段指派，TOM 聚合指紋不變；DRAFT、來源版本／狀態不符、非法任務及前置未就緒拒絕。任何前置目前都沒有 M4 完成／交接證據，不能用撤派、到時或無占用放行。
- 同任務、司機、主車、拖板及跨 MAIN／TRAILER 共用車輛 namespace 的競爭只有一筆成功；資料庫 partial unique／同單 FK 與應用鎖序都驗證，不能只靠先查。
- 撤派原子釋放全部占用；改派保留舊 SUPERSEDED 與新 ASSIGNED、來源快照及原因。五張自有表各寫入／釋放階段、FK 與交換占用中途失敗整筆回滾，舊配置完全保留，同 key 可重試。
- task generation 與 assignment version 分開，最大值不溢位；三命令的同 key、重用、IN_PROGRESS、不同 actor／path、數學整數 canonical body 及歷史成功重播不重占用／不加事件。
- FMS 行政異動與派工兩方向競爭、占用來源配置守衛、同值／metadata 維護、歷史車牌／姓名與任務來源不被改名覆寫。沒有法規資格、預約、合載或自動釋放規則。
- 正常 ADMIN／DISPATCHER 管理、DRIVER 只看目前本人有效摘要；Session／CSRF／臨時密碼、撤權、解綁／改綁與撤改後隔離。本人不回整單、其他資源、證照、帳號、占用或歷史。
- TOM 同交易索引 sink 失敗時受理 root／revision／event／成功結果一併回滾；成功受理重播不重新建索引。公開窄 source lookup 有界、拒 read-only／無交易鎖呼叫，保留 NUL、原始 decimal、offset、null／空物件。
- 正常 LogisticsApplication 即使測試 class 在 classpath、資源符合合成命名且行政可用，仍只有 UNCONFIRMED，真 HTTP 候選／指派拒絕且 generation／派工／占用／事件／結果無副作用。合成 override 必須明確載入，不能以正式環境旗標開放。

## 隔離、映像與重現

依 [E2E README](../../tests/e2e/README.md) 重現。isolated project `xk-logistics-m3-04-e2e`、DB `xk_logistics_e2e`，PostgreSQL 5433／backend 8086／frontend 5174／HTTPS 8444；一般 DEV 資料卷保留。空庫 V1–V9／27 表、零帳號／客戶／訂單／索引／派工／占用／事件／重播結果，可信 TLS status UP；重啟後仍九份 migration，沒有重複初始化。

只有 `compose.e2e.yaml` 才外掛四個白名單 class：test launcher、plain synthetic policy 及編譯器產生的 inner classes。prepare 腳本不複製 Configuration、其他測試或 JUnit 套件；明確設定此公開 classpath 子樹目錄 0755、檔案 0644，已在 umask 077 驗證，不改私有 fixture 權限。每次政策建立查證實際 `current_database()=xk_logistics_e2e`。Cloud proxy／CA 只存在暫存 Dockerfile／overlay 與 BuildKit secrets，不入庫或 final image layer，CI 以 repo Dockerfile 重建。

後端映像 `sha256:597a2373e8c2f8d4aa2a5503ccc8be85788016734f16f2c77b45ead5c0b4206f`；實際讀回 JAR 九份 migration bytes 與工作樹一致，沒有 test policy、launcher、fixture／support／IT classes。前端映像 `sha256:757d201abff4825be6470e69ee162721265adc84075509e65513262bb47020ce`，本包無產品變更，49 份前端來源逐 byte 核對後重用；CI 仍從提交來源重建。共 180 份產品來源 hash 在 build 後至交付保持一致。

一般 DEV 保留原資料卷，以同一受測後端映像完成 V8→V9；可信 HTTPS 回 UP，九份 migration／27 表。原帳號／客戶／訂單／車輛／司機／受理事件升級前後均 0，五張新表亦 0；沒有載入合成 fixture。實查 entrypoint 仍為 java -jar /app/app.jar，沒有 /run/tms-e2e mount。完成 evidence 與隱私驗證後，僅清除本次隔離 stack／volume／私有帳密，保留一般 DEV；沒有正式部署。

Fixture 私有帳密位於報告之外、trace 關閉。18 份契約 response evidence 只含合成 `{schema,value}`，不含 headers、Cookie、CSRF、key 或密碼；報告已在 fixture 清除前通過隱私掃描，缺少／空 evidence 必失敗。經核對的逐類測試摘要與 public response evidence 保存於本次本機 artifacts，遠端 CI 保留自身報告。M3-04 沒有新增工作台畫面，不以原 UI 截圖冒充 M3-05 交付。

## 剩餘邊界

正式車種／駕照分類、配對、重量、檢驗、工時與現場例外仍未核准；production 新派工／改派維持拒絕，合成政策只證明工程流程。D04／D05／D10 與保存期限、UPMS 單 instance、既有維運／備份及覆蓋率數值門檻延續原邊界。本包只有出發前 ASSIGNED／REVOKED／SUPERSEDED，不實作 M4 出發、回報、照片、完成、結案或正式部署。M3-05 與 M3-06 按獨立 PR／CI／main CI 順序接續。
