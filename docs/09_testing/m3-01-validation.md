# LG-M3-01 FMS 車輛驗證｜2026-11-25

## 基準與邊界

前置 [規格 PR #11](https://github.com/xk2022/xk-app-logistics/pull/11) 八份文件已審查，81 個相對連結有效，未更動 M2 程式／四份契約；[PR CI 45632115226](https://github.com/xk2022/xk-app-logistics/actions/runs/45632115226) 四項全成功後以 expected-head `a370e7d1000cae1864347d27187415ea91ddc298` 合併至 `82bc20c58ec656cc98485a8bfe3b643ca1bc3f05`。[main CI 45632934936](https://github.com/xk2022/xk-app-logistics/actions/runs/45632934936) 成功。從該 main 建立 `feat/lg-m3-01-fms-vehicle`。

公司最新規範仍為 `xk2022/xplore-kaleidoscope@5be640ab11811b24706101ea13db73185e9ce4d8`。沿用 M1 固定技術版本，沒有新增生產或測試依賴；新 FMS 使用獨立 [OpenAPI](../06_api/fms-vehicles-openapi.yaml)、[API／欄位契約](../06_api/fms-vehicles.md)與[工程決策](../98_decisions/2026-11-25-fms-vehicle-engineering.md)，不覆蓋原 TOM envelope 或 M2 契約。

本包只有車輛主檔、管理狀態、ADM 車種、UPMS 權限、UI 與可信來源查詢。AVAILABLE 是行政狀態，不是實際可派車承諾；不實作司機管理、使用中、占用／配對、正式受理、派車、回報、照片、結案或正式部署。沒有正常車輛或正式車種 seed。

## 實際執行

| 檢查 | 結果 |
| --- | --- |
| 前端 lint／typecheck／test／build | 全部通過；14 files／238 tests，保留原 M2 155，新增車輛 75 與導覽保護 8 |
| Maven verify | 全部通過；243 tests（96 unit＋147 integration），原 M2 211 全保留；零失敗、錯誤或略過 |
| PostgreSQL／空庫／升級／交易 | 通過；23 FMS integration、Platform 7，包含 V5→V6 舊 18 表資料指紋保留、20 表空庫與鎖／回滾競爭 |
| 真實 HTTPS 桌面／Pixel 7 E2E | 20／20 通過（workers 2、retries 0、1.4 分鐘）；保留原 18，新增每 viewport 一套完整車輛流程 |
| 原訂單契約來源 | 四份固定來源 SHA-256 一致，未修改原驗收工具 |
| 原 TOM 靜態與 HTTP 契約 | 16 正例／25 反例／11 graph／15 role／40 欄及 8 份 artifact、296 真實回應全過 |
| FMS 靜態與 HTTP 契約 | 六操作／19 schema／20 正例／25 反例／3 外部引用阻擋，以及 2 份 artifact、174 真實回應全過 |
| 安全、隱私及依賴 | root／frontend npm audit 均 0；CycloneDX SBOM 成功，OSV 掃描 409 筆套件紀錄（3＋255＋7＋144）無已知弱點；版本與鎖檔不變；artifact 隱私、staged 及完整 history（21 commits）gitleaks 通過，沒有規則排除 |
| GitHub CI／交付 | 最終 head 四項全成功才合併，合併後 main 再核對；最終 SHA／run／merge 與結果以交付 PR／GitHub 執行紀錄追蹤 |

## 必驗行為

- UUID 不變、車牌 canonical 唯一（包含停用）、Unicode／大小寫／空白邊界及更正；無國別車牌或車籍格式假設。
- 新增／換車種檢查 VEHICLE_TYPE 分類與父子啟用；同車種被停用仍可維護，且 options 不額外猜測派工資格。分類停用與新增引用、車輛狀態與來源鎖的真實交易競爭。
- 載重小數字串與未知／0、32 字元邊界、保留 scale；歷史自然日直接以 PostgreSQL `date::text` 核對，不只做 Java 對稱讀寫。
- 真 partial PATCH、版本衝突／安全整數上限、固定 Clock 的同值及 scale-only 保存、資料庫失敗整筆回滾及 audit；無非法身份或 IN_USE 欄位寫入。
- ADMIN／DISPATCHER 六項 API、DRIVER 拒絕、強制改密／撤銷／Session／CSRF／HTTPS；不擴大其他 ADM HTTP 權限。
- 前端完整載入、字面搜尋、分頁、三種合成分類、歷史停用分類、日期提示、明確版本重讀／確認、未知結果不假成功、不自動重送、保存中及未儲存離開保護。

## 隔離與重現

依 [E2E README](../../tests/e2e/README.md) 及 repo 品質命令。使用 test-classpath 的合成帳號／ADM 車種；一般 DEV 不種資料。FMS 使用每 viewport 獨立角色群與合成車種類型，避免 M2 改密、撤銷、Session 限額或跨 viewport 分類停用干擾。fixture 不含車輛／客戶／訂單 seed，產品 JAR 不得包含測試 runner。

本機另用 project `xk-logistics-m3-01-e2e`、DB `xk_logistics_e2e`，PostgreSQL 5433／backend 8086／frontend 5174／HTTPS 8444；測試不占用一般 DEV 5432／8443。Cloud 專用代理／CA 僅以暫存 Dockerfile／overlay 與 BuildKit secrets 使用，不入庫或映像層。CI 使用儲存庫 Dockerfile 重建。

本機一般 DEV 在測試後保留原資料卷，以受測映像驗證 V5→V6 啟動：六份 migration、20 表、HTTPS 狀態 UP；帳號／客戶／訂單／車輛均 0，未載入測試帳號。前端映像內 37 個產品來源檔與最終工作樹一致；正式 JAR 不含 fixture／runner，V6 bytes 一致。這是開發環境驗證，沒有正式部署。

fixture 私有檔在報告目錄外，trace 關閉；response artifacts 僅 `{schema,value}` 合成業務資料，不記錄 Cookie、CSRF、密碼或原始 headers。原 TOM 和獨立 FMS 驗收工具均須驗實際回應，缺少／空白證據必失敗，報告通過隱私檢查才可保留。完成後只清理指定隔離 project／volume／fixture，不清除一般 DEV 資料。

桌面與 Pixel 7 的車輛明細／歷史分類列表共四張 PNG 來自最終同批 E2E，已實際檢視表單、長車牌、日期邊界、零載重、封存分類、選項與窄螢幕換行，沒有遮擋或水平溢位。圖片、報告及合成 response artifacts 由 CI artifact 保留；功能判定以上述測試與 schema 結果為準。

首輪新增後端測試修正了 fingerprint 主鍵與既有 HTTP／CSRF 前置狀態的預期，未改動原 M2 產品規則。首輪 E2E 原 18 項通過，新增兩項在停用字典項目時因測試請求漏送既有必填 sortOrder 被正確拒絕；補齊後重建隔離資料庫／fixture，完整重跑 20 項全過，未略過或降低斷言。

## 剩餘政策

正式車種碼、國別車籍要求、法定載重、檢驗周期／期限阻擋、車頭拖板配對、資源占用及 DRIVER 本人指派政策仍待後續核准。本次日期提示不是合規判定，主檔可用不是派車資格。UPMS 單 instance 限制、D04／D05／D10 與正式維運／備份／覆蓋率數值門檻延續原邊界；無正式部署。
