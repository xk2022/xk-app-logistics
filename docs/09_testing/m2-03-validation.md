# LG-M2-03 CRM 驗證｜2026-11-25

## 基準與範圍

先驗收 [PR #7](https://github.com/xk2022/xk-app-logistics/pull/7) 的八份文件、55 個相對連結與 Q16–Q20；四項必要 CI 全部通過、無未解決 review thread，合併 SHA `b457265fab5a2343faf019eff6f4f12382b93058`。由該 main 建立 `feat/lg-m2-03-crm`。最新公司規範來源為 `xk2022/xplore-kaleidoscope@5be640ab11811b24706101ea13db73185e9ce4d8`；同步 APP 部署 v1.1 的環境文件，這次沒有建立雲端資源或部署。

實作客戶主檔、多聯絡人、多常用地址、啟停、分頁搜尋、有效選項、可信 server 查詢、ADMIN／DISPATCHER UI 與 Flyway V4。細節見 [CRM 契約](../06_api/crm.md)、[獨立 OpenAPI](../06_api/crm-openapi.yaml)、[模型](../05_database/crm-model.md)及 [工程決策](../98_decisions/2026-11-25-crm-engineering.md)。保留 V1–V3、原訂單 OpenAPI／角色矩陣／合成案例／欄位字典及已採用決議。

## 本機結果

| 檢查 | 結果 |
| --- | --- |
| 前端 lint／typecheck／test／build | 全部通過；77 項（原 58 項保留，新增 CRM API 7、UI 12），沒有新依賴 |
| Maven verify | 65 項全過：單元 7、ADM 整合 16、Platform 5、CRM 20、UPMS 17；0 失敗／錯誤／略過 |
| 同源 HTTPS E2E | 最終乾淨隔離環境單次 10／10 通過（30.2 秒）；保留原 8 項，新增 CRM 桌面／Pixel 7 各 1 項 |
| PostgreSQL 空庫／升級 | 隔離 Compose 空庫 V1–V4、精確 12 表／CRM 3 表與帳號／參數均 0、原 ADM 4 分類與 3 固定唯讀項目通過；V3→V4 保留既有帳號／密碼 hash／認證版本、ADM 定義／參數及異動通過 |
| 依賴與機密 | root／frontend npm audit 均 0 vulnerabilities；最終重產 Maven BOM 144 components，鎖定依賴共 402 筆套件紀錄、OSV 0 已知漏洞；staged gitleaks 無洩漏 |
| OpenAPI | 11 操作、105 個本地引用、17 個 DTO 結構正反例通過；輸入 NFC 後長度由服務驗證，沒有誤用 raw maxLength |
| 靜態 | actionlint 與 diff whitespace 通過；171 個相對連結無缺漏；E2E TypeScript（bundler）與 19 段 CI shell 語法通過 |
| 報告隱私 | 認證 trace 關閉、trace 檔 0；fixture 密碼及報告隱私檢查通過，CI 仍保留失敗拒絕上傳門檻 |

## 驗證邊界

- 客戶 UUID／唯一穩定大寫代碼，必要文字與可空值、NFC／Unicode 空白、phone 不限單國、taxId 不唯一、addressType 自由描述；嚴格 JSON／query 白名單，PATCH 只改提供的欄位、必須 version 及至少一個 mutable 欄位，沒有 DELETE。
- 分頁／排序界限與 id 穩定次排序、literal `%`／`_` 搜尋、有效選項強制 enabled；父子關係由伺服器設定，跨客戶操作拒絕、FK 與唯一性保護、版本衝突不覆寫較新資料。
- ADMIN／DISPATCHER 可管理單公司 CRM；DRIVER、未登入、首次強制改密、缺少／錯誤 CSRF 拒絕。DISPATCHER 不因 CRM 而取得 ADM 管理權。
- 寫入交易依 UPMS canonical／actor → customer → child 鎖順序重驗授權；鎖後重新讀取 managed entity，避免外層交易快取舊角色／客戶狀態。停用與改名、新引用查核的競爭測試只使用 CRM 來源與模擬交易。
- `CrmQueries` 只供可信 server 端呼叫，並非 HTTP 權限。`findCurrent` 可取停用歷史主檔；`requireEnabledForNewReference` 要求呼叫端已授權且存在可寫交易，持有客戶鎖至 caller commit。無交易／唯讀交易拒絕、缺少客戶 404、停用客戶 400 `customerId`。
- 回傳不可變來源副本，客戶改名不改既有副本。**尚未實作 TOM 或真正訂單快照寫入**；訂單持久化、停用／新訂單競爭整合驗收在 M2-04 完成，不將 CRM 模擬交易稱為訂單驗收。

## CI 與交付追蹤

本機驗收已完成，四項必要遠端品質檢查全部成功才合併。此處尚未宣稱遠端 CI 通過，實際受測 SHA、PR、run 與合併結果於執行後補錄。

## 可重現與風險

啟動見 [README](../../README.md)，隔離瀏覽器驗收見 [E2E README](../../tests/e2e/README.md)。正常 V4 不加入客戶、聯絡人、地址或合成測試 seed；fixture 僅在獨立 `xk_logistics_e2e` 資料庫執行，18 個合成帳號及 6 個合成 ADM 定義只用於隔離 E2E，CRM 客戶由 UI／HTTP 情境建立；憑證存權限 0600 的私有暫存檔。桌機與手機 CRM 長圖已檢視，沒有 viewport 溢出。

本機 Cloud 使用 `/tmp` Dockerfile／Compose overlay 與 BuildKit secret 掛載專用 Maven 代理及 CA，設定不入庫或映像層；GitHub 使用原始 Dockerfile。測試報告、SBOM 及掃描證據由 CI 保存 14 天，私鑰與 fixture 憑證不進報告。

稅務驗證例外、地址分類正式枚舉與資料保存政策仍待業務核准，未擅自加入。UPMS 單 instance Session／限流及 DEV 自簽憑證限制延續；正式可信代理、最小 DB 權限、備份還原、責任與覆蓋率數值門檻須獨立驗收。無 TOM、FMS、派工、正式客戶資料或正式部署。
