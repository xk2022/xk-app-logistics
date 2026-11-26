# LG-M3-03 TOM 正式受理驗收

## 基準與範圍

規格 [PR #16](https://github.com/xk2022/xk-app-logistics/pull/16) 的 source `2798645d687d227533efdd1fe0d8c95da2dbad8a` 已驗收：104 個相對連結有效，原四份訂單契約及 V1–V7 不變；[CI 45727940359](https://github.com/xk2022/xk-app-logistics/actions/runs/45727940359) attempt 1 四項成功後合併，merge `cf5ec0796d6eff2694eed1c5cb5a922bd1a63d39`，由此建立本包工作分支。

合併後 [main CI 45728842059](https://github.com/xk2022/xk-app-logistics/actions/runs/45728842059) 的其他三項成功，backend 在測試前下載 Spring Boot parent POM 時遭官方 alternate `repo1.maven.org` 回 HTTP 429。本包將 backend 改用與 SBOM、Docker build 一致的預設官方 Central repository identity，避免同一 Maven cache 因不同鏡像識別而重新要求 alternate 驗證；保留 strict checksums、完整測試、cache 與依賴版本。沒有修改 Maven 快取內部 metadata、測試門檻或 TLS。

公司最新規範：`xk2022/xplore-kaleidoscope@5be640ab11811b24706101ea13db73185e9ce4d8`。沿用既有精確技術版本，沒有新依賴。[Q31–Q35](../98_decisions/2026-11-26-tom-acceptance.md) 保持採用，增量契約及交易細節見[獨立 OpenAPI](../06_api/tom-acceptance-openapi.yaml)與[工程決策](../98_decisions/2026-11-26-tom-acceptance-engineering.md)。

本包僅明確受理、同交易重驗 ORD-P01–P03、OPEN＋DRAFT → OPEN＋ACCEPTED、版本／人員／伺服器時間／完整快照、重送一致性與唯讀待派車顯示；沒有派車、資源占用、撤回、司機回報、照片、結案或正式部署。

## 實際檢查

| 項目 | 結果 |
| --- | --- |
| Frontend lint／typecheck／test／build | 全過；20 files／426 tests，保留原 330；build 62 modules |
| Maven verify | 全部 312 通過＝108 unit＋204 integration，0 failure／error／skip，完整執行 4 分鐘；保留原 280 |
| PostgreSQL 初始化／升級／交易 | Platform 9、Acceptance IT 26 全過；V8／22 表（含 Flyway）、V7→V8 舊 20 張領域表指紋不變、不捏造既有 accepted 事件；詳細交易行為如下 |
| 真實 HTTPS 桌面／Pixel 7 E2E | 完整 24／24 通過（2 workers、0 retries、1.7 分鐘），原 22 全保留；新增桌面／手機受理流程 |
| 原五項 TOM 與增量受理契約 | 四份原來源 SHA-256 不變；受理靜態 1 operation／4 schemas、26 正例／33 反例／7 引用防護、9 項非法或缺少 evidence 拒絕；實際 HTTP：原 TOM 414／10 files、受理 30／2 files、Vehicle 174／2 files、Driver 282／2 files 全過 |
| 依賴／秘密／報告隱私 | root／frontend npm audit 均 0；CycloneDX SBOM 成功，OSV 掃描 409 筆（3＋255＋7＋144）無已知弱點；staged／完整 history 與同批 E2E 報告隱私全過，無 scanner 排除；新 validator 共用既有未修改的四來源 SHA-256 manifest，逐份獨立驗證 |
| 文件／工具／原基準保留 | 16 份 Markdown、215 相對連結有效；actionlint、E2E strict TypeScript、diff check 全過；V1–V7、四份原訂單契約、既有 FMS 契約／validator、依賴版本與全部原測試保留 |
| GitHub 交付 | 最終 head 四項 CI 全成功後才合併；source／merge SHA、run 與合併後 main 結果由交付 PR 及 GitHub 執行紀錄追蹤 |

新增測試首輪發現五個斷言問題，未列為通過：四項預期 JSON 人工建立的 LongNode 與 HTTP 解析 IntNode 物件相等判定不同，改以同一 JSON 解析正規化比較完整資料；另一項受理先提交、舊 PUT 使用過期版本時，原五 API 本來就先回 VERSION_CONFLICT，修正預期並保留原 PUT 優先順序。沒有修改產品行為、刪除測試或降低資料完整性斷言，修正後完整 verify 重跑成功。

新增 E2E 首輪為原 22 項通過、受理兩 viewport 在舊 PUT 拒絕檢查停止：測試請求缺少原契約的 `draft` 包裝，伺服器正確回 400。修正為原 `{expectedVersion,draft}` 後，重建本次隔離資料庫與合成帳號，重新執行全部 24 項；不修改舊 API 或略過原案例。

## 必驗行為

- ADMIN／DISPATCHER 明確二次確認受理；DRIVER 無導覽與六項訂單操作權限，Session、CSRF、停用／撤銷與 fresh actor 授權不變。
- 三類訂單原圖、多項目、任務與依賴；只有根狀態／version／updatedAt／updatedBy 改變，客戶歷史名稱與 child ID、原始空白、NUL、decimal、offset、null／空物件完整保留。
- 伺服器同交易重讀目前客戶及訂單，重驗原 ORD-P01–P03；先前 COMPLETE 不授權、不跳過重驗，pending 六項政策值不變。
- 客戶停用與受理兩方向競爭、來源 ID 變更 fail closed、雙重受理、不同 actor／key 競爭、受理與 PUT 兩方向競爭；版本上限與非法前態拒絕。
- 完整 snapshot 同時寫入新事件與原 revision；事件／revision 不可 UPDATE／DELETE，order 與版本唯一／FK 保護；revision、event、idempotency 任一階段失敗整筆回滾。
- 同 key 同內容回原受理結果與新 trace，不再次加版；重播前重驗身分。原成功 POST／PUT 在受理後仍回其歷史 DRAFT 快照，不能改寫原契約。
- UI 只從已保存且乾淨的草稿開始，不自動保存或受理。未知結果凍結原 id／body／version／key，明確重試；CSRF／協定／登入錯誤不推定原次未提交。
- 已受理內容唯讀；版本衝突先保留本機，遠端已受理不能 rebase 成可編輯草稿；待派車只是 OPEN＋ACCEPTED 顯示，沒有額外列表查詢參數或派車功能。

## 隔離與重現

依 [E2E README](../../tests/e2e/README.md) 與 repo 品質命令執行。isolated project `xk-logistics-m3-03-e2e`、DB `xk_logistics_e2e`，PostgreSQL 5433／backend 8086／frontend 5174／HTTPS 8444；一般 DEV 的資料卷保留。Cloud 代理／CA 僅存在暫存 Dockerfile、overlay 及 BuildKit secrets，不入庫或映像層；CI 使用 repo Dockerfile 重建，不降低 TLS／Cookie 保護。

一般 DEV 保留原資料卷，以相同受測映像完成 V7→V8 啟動；2026-11-26 UTC 的可信 HTTPS 回 UP，八份 migration／22 表。升級前後原帳號／客戶／訂單／車輛／司機均為 0，新事件為 0，沒有載入 E2E fixture。完成報告隱私與畫面保存後，僅清除本次 E2E stack／volume／私有帳密；一般 DEV 服務保留，沒有正式部署。

Fixture 在全空帳號表才建立 54 個合成帳號（原 48＋每 viewport 三種受理角色）；沒有客戶、訂單、車輛或司機種子，所有業務資料經真實 API 建立。私有合成帳密檔在報告外、trace 關閉；契約 artifact 僅合成 `{schema,value}`，沒有 Cookie、CSRF、密碼、key 或原始 headers。缺少／空 evidence 必須失敗；同批報告先通過隱私檢查才保留。

前端映像 49 份產品來源與工作樹逐 byte 一致；後端 JAR 八份 migration bytes 一致，沒有 fixture／runner／IT 類別。映像：backend `sha256:4a211a6ce8713629a2a55e4e01f238c121ef1d97b011e8caf0f7576a9f4a67e5`，frontend `sha256:757d201abff4825be6470e69ee162721265adc84075509e65513262bb47020ce`。CI 從提交來源重新建置。

同一輪 E2E 保存十二張原始 PNG：兩 viewport 的確認、三種受理後唯讀、未知結果與重播成功畫面。已實際檢視桌面確認及手機重播成功畫面，無遮擋或水平溢位；畫面不代替功能與契約測試。

## 剩餘邊界

D04／D05 未決現場政策與 D10 保存期限仍待核准；pending 不表示全面封鎖，也不表示例外已獲准。本包沒有事件修改／刪除／清理排程，不捏造歷史 actor／時間。事件 actor／acceptedAt 存在資料庫，現有五 API DTO 不新增欄位；前端不以瀏覽器時間或 updatedAt 冒充受理事件資訊。UPMS 單 instance 與既有備份／維運／覆蓋率數值門檻延續原邊界。未使用真實資料，未部署正式環境。
