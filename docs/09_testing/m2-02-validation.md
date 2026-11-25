# LG-M2-02 ADM 驗證｜2026-11-25

## 基準與範圍

先驗收 [PR #5](https://github.com/xk2022/xk-app-logistics/pull/5) 的七份文件、相對連結、Q11–Q15 與公司最新規範；四個必要 CI job 全部通過，合併 SHA `2cdabf6e58f6512a55eb2a35b0b373070f28eec8`。本工作由該 main 建立 `feat/lg-m2-02-adm`。公司規範基準仍為 `ae9bce5a95d1565d76e76a0414fde7d29a1ed881`。

實作分類、項目、核准參數、啟停排序、異動紀錄、公開共用查詢服務、ADMIN 前端及 Flyway V3。細節見 [ADM 契約](../06_api/adm.md)、[獨立 ADM OpenAPI](../06_api/adm-openapi.yaml)、[資料模型](../05_database/adm-model.md)及 [工程決策](../98_decisions/2026-11-25-adm-engineering.md)。原訂單 OpenAPI、角色矩陣、合成案例及欄位字典保持原樣；V1／V2 migration 未修改。

## 最終本機結果

| 檢查 | 結果 |
| --- | --- |
| 前端 lint／typecheck／test／build | 全部通過；58 項（原 M1／UPMS 30 項完整保留，新增 ADM 28 項），無新依賴 |
| Maven verify | 44 項全部通過：單元 7、ADM 整合 16、Platform 4、UPMS 17；0 失敗／錯誤／略過 |
| 同源 HTTPS E2E | 桌面／Pixel 7 共 8 項通過；原 M1／UPMS 6 項保留，新增兩個 ADM 完整流程 |
| 空庫／升級 | PostgreSQL 17.12 空庫 V1–V3、重跑、V2→V3 保留原帳號／密碼 hash／認證版本通過 |
| 正常初始資料 | 精確 9 張表；4 分類、3 個固定唯讀枚舉；其他項目、核准參數目錄、參數、ADM 異動、UPMS 帳號與稽核均為空 |
| 同源 TLS | `curl --cacert infrastructure/dev-tls/localhost.crt https://localhost:8443/api/v1/status` 通過，未關閉 curl TLS 驗證 |
| 依賴 | root／frontend npm audit 均 0 vulnerabilities；CycloneDX BOM 144 components，OSV 合計 402 筆套件紀錄，0 已知漏洞 |
| 靜態與契約 | actionlint、CI shell、E2E TypeScript、相對連結及 diff whitespace 通過；ADM OpenAPI 12 操作／116 個本地引用及 9 個 DTO 結構正反例通過 |
| 報告 | 認證 trace 關閉；fixture 初始密碼／trace 隱私檢查通過，未通過此檢查時 CI 拒絕上傳報告 |

## 實際驗證情境

- 分類／項目建立、編輯、啟停、排序、唯一性、FK、版本衝突、穩定次排序、分頁界限、查詢與排序白名單、literal `%`／`_` 搜尋。
- code／key／categoryId／valueType／readOnly 等不可變或系統欄位不可由 PATCH 更動；所有未知 JSON 或 query 拒絕，沒有 DELETE 操作。
- 固定 `ORDER_TYPE` 與 `IMPORT`／`EXPORT`／`LOCAL` 唯讀；其他分類初始沒有正式項目。停用父分類／項目後不回新選項，歷史標籤仍可查。
- options 最終讀取以單一資料庫 statement 同時檢查父子 enabled；外層 transaction 已快取 parent、另一 transaction 再停用的情境也不回傳選項。
- 文字、數字、布林型別與核准目錄驗證；未知鍵、型別不符、超界、過多小數、科學記號、非精確布林字串及有限的明顯憑證內容拒絕。前後端以字串處理十進位值，避免浮點截斷。
- 畸形目錄的 JSON number／boolean 元素、重複、非 NFC／控制字元、憑證及不完整界限拒絕；不把元素隱式轉成字串。共用參數查詢重驗最新目錄、型別、值與 canonical 格式，失效時拒絕使用。
- 未登入、DISPATCHER／DRIVER、首次強制改密、缺少／錯誤 CSRF 不可管理 ADM。其他角色尚無核准分類讀取權限，options 也拒絕。
- ADM 寫入經 UPMS 公開授權 facade，在同一 transaction 鎖 canonical／actor 並重驗狀態與認證版本；實際 row lock 競爭中撤銷 actor 後，舊身分不可提交。
- 業務異動與 before／after／操作者紀錄同 transaction；以真實 PostgreSQL trigger 使稽核失敗，確認業務寫入亦回滾。
- 架構邊界驗證 ADM 不直接引用其他模組 repository；`AdmQueries` 為可信 server 內部介面，HTTP 仍必須經明確角色授權，不能把共用查詢當成非管理員讀取許可。
- UI 字典及參數流程、唯讀／空狀態、版本 409 後重讀再操作、401／403、分類切換競爭與異動 JSON 文字安全；正常空 catalog 不提供任意鍵／型別輸入。

## 可重現與交付證據

一般啟動見 [README](../../README.md)，隔離 E2E 準備見 [tests/e2e/README](../../tests/e2e/README.md)。不可直接在一般 DEV DB 執行 fixture。test-only runner 只接受 localhost 的 `xk_logistics_e2e` 空帳號資料庫；12 個合成帳號與 6 個分 viewport 的合成參數定義僅在隔離測試建立，產品 jar 沒有 seed 入口或可由環境旗標啟用的 fixture。

本機 Cloud 建置仍以 `/tmp` Dockerfile／Compose overlay 及 BuildKit secret 掛載專用 Maven 代理與 CA；設定未入庫或進入映像層。GitHub 使用儲存庫原始 Dockerfile。JUnit、SBOM／安全掃描、Playwright／Compose 報告由 CI 保存 14 天；fixture 憑證及開發 TLS 私鑰不進報告。

[Application quality workflow](https://github.com/xk2022/xk-app-logistics/actions/workflows/ci.yml) 包含四項必要品質檢查。交付時以 PR 的最新 head SHA／run 與合併紀錄核對，全部成功才合併；未執行的遠端 run 不列為通過。

## 剩餘界線

- **沒有核准的正式參數鍵或其他角色讀取分類權限。** 正常參數目錄／參數為空；本次完整實作及測試受控框架，沒有把測試定義當成正式核准值。後續新增目錄須另行核准 migration，沒有目錄寫入 API。
- 正式貨櫃／任務／車輛字典值仍待業務確認；固定訂單枚舉不由 ADM 控制。
- 憑證 pattern 防護為有限防線，不宣稱全面偵測秘密；用途及核准範圍仍須人工審議，ADM 不替代部署或安全配置。
- UPMS 的單 instance Session／限流與 DEV 自簽憑證限制延續；正式可信代理、最小 DB 權限、備份還原、人工復原責任及覆蓋率數值門檻仍待獨立驗收。
- 沒有 CRM、TOM、FMS、正式客戶資料或正式部署；後續工作包需另行交辦。
