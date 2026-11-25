# LG-M2-01 UPMS 驗證｜2026-11-25

## 基準與範圍

先驗收 [PR #3](https://github.com/xk2022/xk-app-logistics/pull/3)：七份文件、相對連結、Q06–Q10 及公司最新規範一致，四個必要 CI job 全部通過、沒有待解審查，合併 SHA `c4deb751ee4344c26d460e6aa46e78b2d0b28130`。本工作從該 main 建立 `feat/lg-m2-01-upms`。

本輪實作帳號與固定角色、本人資訊、登入登出、Session／CSRF、改密、受控離線初始化及復原、三角色工作台、ADMIN 管理與 Flyway V2。新增 [UPMS 契約](../06_api/upms.md)及 [安全工程決策](../98_decisions/2026-11-25-upms-engineering.md)，不修改原訂單 OpenAPI／角色矩陣／合成案例／40 筆欄位字典，也未開發 ADM、CRM、TOM 或部署正式環境。

## 最終本機驗證

| 檢查 | 結果與實際覆蓋 |
| --- | --- |
| 前端 `npm run lint`、`typecheck`、`test`、`build` | 全部通過；30 個 Vitest 案例，無新增前端依賴 |
| 後端 `./mvnw -B -ntp verify` | 通過；7 個單元 + 20 個真實 PostgreSQL 整合案例（Platform 3／UPMS 17），0 失敗／錯誤／略過 |
| Playwright HTTPS | 桌面與 Pixel 7 共 6 案例通過；同源 TLS、三角色首次改密／登入／登出、Cookie、CSRF、ADMIN 建帳、停用撤銷，以及 M1 服務成功／失敗／重試 |
| Compose 空庫 | V1／V2 成功、精確存在 `flyway_schema_history`、`upms_lock`、`upms_account`、`upms_audit`；初始化帳號與稽核均 0，不隨正常啟動 seed |
| 真正離線命令 | 以 PTY 執行產品 Docker jar 的 init／recover，在獨立 `xk_upms_cli_test` DB 成功；隱藏隨機密碼未被 echo，資料庫保留單一帳號、強制改密及兩類稽核 |
| TLS 路由 | `curl --cacert infrastructure/dev-tls/localhost.crt https://localhost:8443/api/v1/status` 成功；使用指定本機 CA 驗證，不停用 curl TLS 驗證 |
| 依賴／workflow | root 及 frontend `npm audit --audit-level=high` 均 0 vulnerabilities；CycloneDX SBOM 144 components；OSV 掃描 root 3／frontend 255／backend 144 共 402 筆套件紀錄，0 已知漏洞。actionlint、run shell `bash -n`、E2E TypeScript 與 `git diff --check` 通過 |
| UI 與報告 | 桌機／手機閱覽無版面阻擋或橫向溢出；認證 trace 關閉，報告掃描未包含初始合成 fixture 密碼 |

### 帳號、密碼及資料庫

- 空庫及既有 V1→V2 升級、migration 重跑、無預設使用者；原未實作訂單路徑仍不存在。
- 初始化並發只能一人成功；任何帳號已存在即拒絕再次初始化。非 TTY 拒絕離線命令，沒有公開 bootstrap／復原 endpoint。
- 密碼 NFC、15–128 code points、邊界及不成對 surrogate 拒絕；有限長密碼 blocklist 拒絕。
- 直接由儲存格式抽取 salt，獨立 PBKDF2-HMAC-SHA256／600000 次重算驗證，確認 16-byte salt／256-bit 輸出與不同隨機 salt。未知 hash 格式安全拒絕，DTO／稽核不含密碼或 hash。
- 固定單角色、ADMIN 專用管理、欄位／版本衝突、最後 ADMIN 的並發完整性守衛。

### Session、撤銷與失敗

- 真實 TLS Cookie 驗證 Secure／HttpOnly／SameSite=Lax；登入輪換 Session，舊 CSRF 不可重用；缺少／錯誤 CSRF 的登入與寫入被拒絕。
- 首次／復原臨時密碼 15 分鐘有效，強制改密前僅可操作必要本人認證功能；改密後所有 Session 失效，必須重登。
- 第四次登入撤銷最舊 Session；真實 session 以可控制 Clock 驗證閒置 30 分鐘、絕對 8 小時，不降低正式設定或等待數小時。
- 停用、換角色、改密及復原後舊認證失效。受保護寫入與撤銷共用資料庫 canonical lock；最後 ADMIN 競爭操作不會使管理員歸零。
- 10 次失敗／15 分鐘鎖定、未知帳號同外部錯誤、窗口清理、10000 keys flood 不驅逐活動限制。以外部 SQL 模擬另一離線程序更新認證版本，證明 Web 程序原有鎖不阻擋新臨時憑證。
- 真實 DB 拒連：已登入本人資訊、匿名登入及 status 皆 HTTP 503，使用 sanitized 公司 envelope；包含交易建立階段的 `CannotCreateTransactionException`，不以泛用 500 掩蓋 DB 不可用。
- 已移除 Boot 預設 user 自動設定；最終測試 log 不再出現 generated security credential。

### 雜湊成本

Cloud：Intel Xeon Platinum 8573C、5 vCPU、Temurin JDK 21.0.12.1。以實際 `UpmsConfiguration.passwordEncoder()`，2 次 warm-up、5 次量測：encode 中位 253.2 ms（140.4–292.8），verify 中位 224.6 ms（132.7–266.6）。每次均驗證匹配成功；未列印原密碼、salt 或 hash。共用測試硬體數字僅作工程成本證據，不是正式服務 SLA；沒有為加速測試降低 600000 次參數。

## CI 與交付追蹤

- 交付 [PR #4](https://github.com/xk2022/xk-app-logistics/pull/4)，目標 `main`。
- 工程實作受測 commit：`16c8e587924518b056327afc46db4f3340dfffc1`。
- 首輪 [Application quality run](https://github.com/xk2022/xk-app-logistics/actions/runs/45547159640)：Frontend quality、Backend verify、Dependency and secret checks、Compose and browser smoke 四項全部 **success**；原始 Dockerfile、27 項後端測試及 6 項 HTTPS E2E 均通過。
- 本次補錄僅修正 E2E 文件入口並記錄已通過的 CI；最終文件提交及合併後 main 仍由同一 workflow 驗證，最新受測 SHA、run 與 merge SHA 以 PR／GitHub 紀錄為準。合併前必須核對最新 head 的四項品質結果。

## 可重現命令及證據

啟動方式見 [README](../../README.md)及 [infrastructure](../../infrastructure/README.md)。後端測試報告在 `backend/target/{surefire,failsafe}-reports`；瀏覽器報告在 `playwright-report/`、`test-results/`。CI 上傳報告保存 14 天，測試秘密只在 runner 暫存檔，認證 trace 不上傳，DEV TLS 私鑰不入庫。

E2E seed runner 只在 `src/test`，必須指定 localhost 的 `xk_logistics_e2e` 空帳號資料庫，憑證以 0600 暫存 JSON 提供；不打包進產品 jar，也不提供一般啟動 profile／環境變數可開的測試後門。

本機 Docker 使用 Cloud 專用 `/tmp` Dockerfile／Compose overlay，以 BuildKit secret 掛載 Maven 代理與 CA；未入庫或進入映像層，TLS 驗證保持啟用。GitHub 使用原始 Dockerfile，最終結果以 [Application quality workflow](https://github.com/xk2022/xk-app-logistics/actions/workflows/ci.yml) 的受測 SHA／run 為準。未執行的遠端 run 不列為通過，四項品質 job 全部成功才合併。

## 剩餘界線

- Session 與限流為單 instance 記憶體；重啟使 Session 失效／限流重置，不宣稱多 instance 或持久防濫用保證。容量滿採 fail closed，可能暫時拒絕新登入。
- 常見密碼集合僅是 72 筆符合長度的有限離線基線，不宣稱全面外洩密碼覆蓋。
- DEV 憑證為本機自簽；正式 HTTPS、可信代理邊界、DB 最小權限、人工復原責任、備份還原與營運門檻仍須獨立部署驗收。
- 覆蓋率數值門檻尚未核准；未替尚未開發的物流業務宣稱通過。
- ADM／CRM／TOM、真實客戶資料與正式部署均不在本次交付，M2-02 需另行交辦。
