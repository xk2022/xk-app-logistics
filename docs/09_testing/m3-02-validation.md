# LG-M3-02 FMS 司機驗證｜2026-11-25–26（Asia/Taipei）

## 基準與範圍

前置 [PR #13](https://github.com/xk2022/xk-app-logistics/pull/13) 的八份規格已逐份與獨立複核驗收：92 個相對連結有效，既有產品、migration、原 TOM 四來源及 ADM／CRM／FMS 車輛 OpenAPI 未改。[PR CI 45645229549](https://github.com/xk2022/xk-app-logistics/actions/runs/45645229549) attempt 1 四項成功後，以 expected-head `7aba367ae376b4e2c0a5069993d550ef47565a58` 合併至 `a1d72a0f99d5762f79bed2f85ccf32c4d6446b65`，由此建立 `feat/lg-m3-02-fms-driver`。

前置合併後 [main CI 45646017948](https://github.com/xk2022/xk-app-logistics/actions/runs/45646017948) attempt 2 成功。首輪其他三項成功，既有 Vehicle 元件測試一項在成功訊息出現、dirty effect 移除離開提示前做同步斷言，237／238 通過；只重跑失敗工作、同 SHA 全過。本包將該斷言改為等待最終畫面狀態，保留原本保存中不可離開與保存後提示消失的檢查，不修改車輛產品行為。

公司最新規範為 `xk2022/xplore-kaleidoscope@5be640ab11811b24706101ea13db73185e9ce4d8`。沿用既有精確技術版本，不新增依賴；Q26–Q30 保持採用，精確 DTO／狀態／鎖／最小化見 [司機 API](../06_api/fms-drivers.md)、[獨立 OpenAPI](../06_api/fms-drivers-openapi.yaml)及[工程決策](../98_decisions/2026-11-25-fms-driver-engineering.md)。

本包只有司機主檔、可選唯一帳號綁定、ADMIN／DISPATCHER 管理、DRIVER 本人唯讀與可信行政來源。行政可用僅 ACTIVE＋ON_DUTY，不承諾派車或法規資格；沒有正式受理、派車、資源占用、配對、證件圖片或正式部署，不使用真實司機資料。

## 實際檢查

| 項目 | 結果 |
| --- | --- |
| Frontend lint／typecheck／test／build | 全過；17 files／330 tests＝原 238＋Driver 82＋根導覽 10，build 58 modules |
| Maven verify | 全部 280 通過＝103 unit＋177 integration，0 failure／error／skip；原 243 全保留，完整重跑 5 分 5 秒 |
| PostgreSQL 初始化／升級／交易 | 全過；Platform 8、Driver IT 29，包含 V7／21 表、V6→V7 舊 19 表資料保留與合法新增空分類、唯一綁定、本人隔離／撤銷／並發、來源鎖與回滾 |
| 真實 HTTPS 桌面／Pixel 7 E2E | 單次完整 22／22 通過（2 workers、0 retries、2.0 分鐘），原 20 全保留；新增司機桌面／手機完整流程 |
| 原 TOM／FMS 車輛契約 | 原 TOM 四來源 SHA-256 一致，車輛 OpenAPI 與兩份既有 validator 未改；TOM 實際 296／8 files、車輛 174／2 files 全過，原靜態案例全保留 |
| FMS 司機獨立契約 | 靜態 8 operations／28 schemas、27 正例／40 反例／3 外部引用阻擋、8 種非法 evidence fail-path，以及實際 HTTP 282／2 files 全過 |
| 依賴／秘密／報告隱私 | root／frontend npm audit 均 0；CycloneDX SBOM 成功，OSV 掃描 409 筆套件紀錄（3＋255＋7＋144）無已知弱點；staged／完整 history（23 commits）gitleaks 與同批 E2E 報告隱私全過，沒有規則排除；鎖檔與 scanner 規則保持原樣 |
| 文件／工具與舊基準保留 | 13 份 Markdown、176 相對連結有效；actionlint、E2E strict TypeScript、diff check 通過；V1–V6、既有契約／validator／依賴與原測試全部保留 |
| GitHub 交付 | 最終 head 四項 CI 全成功後才合併；source／merge SHA、run 與合併後 main 結果由交付 PR 及 GitHub 執行紀錄追蹤 |

## 產品交付與遠端驗證追蹤

[產品 PR #14](https://github.com/xk2022/xk-app-logistics/pull/14) 已合併：source `01de1f26cf338502a1ddcb02b0e328bdb4332938`，產品 merge `f0299c35fdaf45a44993660ea3c169aa978ec76c`。[PR CI 45650752757](https://github.com/xk2022/xk-app-logistics/actions/runs/45650752757) attempt 1 四項成功；遠端再次確認 103 unit＋177 integration、22 E2E，以及 TOM 296／Vehicle 174／Driver 282 實際回應與報告隱私全部通過。

產品合併後 [push CI 45651314921](https://github.com/xk2022/xk-app-logistics/actions/runs/45651314921) 的前端、安全、Compose／瀏覽器三項成功。後端尚未執行測試，下載 Spring Boot parent POM 時被官方 Maven Central alternate 回 HTTP 429。單一失敗工作重跑、單一 job 重跑及既有 workflow_dispatch 入口均回內部 HTTP 500，實際 run 仍為 attempt 1，沒有將請求失敗誤記為已重跑。

本次文件補錄已完成產品的 PR／SHA／CI 證據，以正常文件 PR 再次觸發既有完整四項品質檢查。產品來源、依賴、API、migration 與測試門檻均維持上述受測版本；文件交付 PR 及其合併後 main 的最終 SHA／成功 run 由該 PR 的交付追蹤記錄，保留前次 429 與重跑介面失敗的歷史。

## 必驗行為

- UUID、canonical 唯一且不可變的司機編號、停用／離職不可重用；可不綁帳號管理。
- accountId nullable 唯一與可信 UPMS fresh role／有效性守衛；有效臨時帳號可綁，首次改密規則不變；失效原關聯可維護／解綁，不自動改 UPMS 生命週期。
- DRIVER 僅目前身分推導的本人資料，不接受其他 ID／查詢，不回帳號關聯、證照工作參考或 audit；換角色、停用、復原、撤銷、解綁／改綁的兩方向並發與本人隔離。
- 新／換證照類別檢查 ADM 父子啟用，歷史停用值仍可維護；空類別不阻擋建立最小主檔，無正式種子。
- employment／duty 分開記錄，不默改另一欄；LEFT 不開放復職；行政選項不增加帳號、分類或日期限制，鎖定來源不是占用。
- 日期 0001–9999、PostgreSQL 直接 `date::text`、部分更新、固定 Clock／同值版本、資料庫失敗整筆回滾、stale persistence context 與來源鎖。
- 角色導覽、本人載入失敗後清舊資料、分頁／字面搜尋、未知保存、409 明確重讀／確認、保存中及未儲存離開保護；桌面與手機可操作。

## 隔離與重現

依 [E2E README](../../tests/e2e/README.md) 及 repo 品質命令。test-only fixture 只用獨立帳號群及合成字典選項，不種司機／車輛／客戶／訂單；正式 JAR 不包含 runner／fixture。

本機隔離 project `xk-logistics-m3-02-e2e`，DB `xk_logistics_e2e`，PostgreSQL 5433／backend 8086／frontend 5174／HTTPS 8444；一般 DEV 5432／8443 的資料卷保留。Cloud 代理／CA 只用暫存 Dockerfile、overlay 及 BuildKit secrets，不入庫或映像層。CI 以儲存庫 Dockerfile 重建，不降低 TLS 或 Cookie 保護。

本機首次驗證因 Docker 儲存空間耗盡而中止：PostgreSQL 明確回報無法建立檔案／資料庫，health 因此正確回 503；不將該輪列為通過。停止該 Maven process tree，只清理先前未使用的 E2E 映像、閒置 build cache 與本次失敗空 stack，保留一般 DEV／資料卷及新受測映像，恢復約 7.3 GB 後從完整 verify 重跑，不略過案例。

前端映像 45 份產品來源與最終工作樹逐 byte 一致；後端 JAR 七份 migration bytes 一致，沒有 fixture／runner／IT 類別。映像追蹤：backend `sha256:ba693de637e101ee3481bfa01a5d95ba467feb4138ed1eb4e07cd15aa90f91a0`，frontend `sha256:ce9ce8914e8234b66b3856558fa81680f320e3b24148b35d11339cae5b4968d2`；本機驗收使用它們，CI 從提交來源重建。

一般 DEV 保留原資料卷，以同受測映像驗證 V6→V7 啟動；2026-11-26 00:00（+08:00）可信 HTTPS 回 UP、七份 migration／21 表。升級前後原帳號／客戶／訂單／車輛皆 0，新司機表也為 0，未載入 fixture；只有開發環境驗證，沒有正式部署。完成後本次 E2E stack／volume 與私有合成帳密已清除，一般 DEV 服務保留。

fixture 私有檔在報告外、trace 關閉；契約 artifact 僅合成 `{schema,value}`，不含 Cookie、CSRF、密碼或原始 headers。舊 TOM、車輛及新司機工具都驗實際回應；缺少／空證據必失敗，報告須通過隱私檢查才保留。結束只清指定隔離 stack／volume／暫存帳密，不清一般 DEV 資料。

本次 fixture 共 48 個合成帳號（原 36＋每 viewport 6 個新角色／候選），另有每 viewport 2 個合成駕照類別；沒有司機種子。單次完整 E2E 的管理明細、調度維護、封存類別行政選項與本人唯讀共八張桌面／手機 PNG 已保留並實際檢視，無遮擋或水平溢位。本人頁沒有綁定、證照工作參考或管理稽核欄位；功能判定仍以上述測試及 schema 為準，截圖不代替測試。

完整實測另修正兩項測試前置條件：字面百分號查詢先 URL 編碼；原 M3-01 的「後續未開發表不存在」檢查改為保留三張 TMS 表不存在，另驗本包合法新增的司機表存在且無種子。未改車輛產品行為、未刪除測試或降低來源／權限斷言，修正後完整 verify 重跑。

## 剩餘邊界

正式證照分類、國別證照格式、敏感證件保存／刪除、離職復職、工時、法定期限限制與資源占用仍待核准。本包不要求真實證件號／影像，不實作帳號生命週期自動化。UPMS 單 instance 與 D04／D05／D10、維運／備份／覆蓋率數值門檻延續既有邊界；無正式部署。
