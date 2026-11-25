# LG-M2-01 安全工程決策

日期：2026-11-25。範圍：UPMS。依 [Q06–Q10](2026-11-25-m2-access.md)、[M2-01 交接](../08_development/m2-01-upms-handoff.md)及固定歷史 [START-P01–P04](https://github.com/xk2022/codex-xk-logistic/blob/7092510c7e63a3f5781e1f0a24787a32e3248ac4/docs/FIRST_SLICE_STARTUP_PLAN.md)細化本次實作；本文不增加角色、業務操作或正式部署權限。公司規範基準為 `ae9bce5a95d1565d76e76a0414fde7d29a1ed881`。實測與受測 commit 另記，決策文字不能代替安全驗收。

## 相依與密碼

- 保留 Spring Boot 4.1.3；BOM 管理 Spring Security 7.1.3。使用 Spring 內建 PBKDF2，不引入第三方密碼演算法套件或外部認證服務。
- 明確採 PBKDF2-HMAC-SHA-256、600000 iterations、每筆安全隨機 16-byte salt、256-bit 輸出。儲存格式前綴為 `{pbkdf2-sha256-v1}`，識別完整參數組合，未辨識格式拒絕驗證；禁止 plaintext、noop、快速 SHA256 儲存帳號密碼。Security 的 `defaultsForSpringSecurity_v5_8()` 是 310000 次，不能用該預設冒充本設定。
- 密碼先做 Unicode NFC，再按 code point 計 15–128 字元；允許空格及 Unicode，拒絕不成對的 UTF-16 surrogate，避免輸出替代字元造成不同密碼映為相同雜湊。不 trim、不截斷、不要求大小寫／符號組合、不定期強制輪換。登入、初始化、復原、改密使用相同正規化方式。臨時密碼也遵守此規則。
- 雜湊成本依官方建議及歷史方案固定；記錄實際硬體與耗時，不為 CI 速度降低正式參數。此選型不是 FIPS、NIST 或其他認證聲明。

## 有限的本機常見密碼檢查

選用 SecLists 官方專案的 `Passwords/Common-Credentials/xato-net-10-million-passwords-100000.txt`，固定 commit `fcde9140df969b1e4d7bb056122646dace4ed4cc`；上游 MIT 授權原文隨資源保存。原始檔 781879 bytes、100000 筆，SHA256：

```text
1472aafa2561df5e3293aee252aee3ca660c12b399a283cf808bb01b39be388b
```

先 NFC，再只保留符合 15–128 code points 的整筆字串；產出排序去重的 UTF-8 SHA256 hex 集合，72 筆、4680 bytes，避免攜入不需要的大型原始清單。產出檔 SHA256：

```text
a0802be3bba37cde6697251c2a92cf2a9e66a4f4a5575a626d70b6a642db6493
```

實際檔案、授權、固定來源及重現腳本見 [resources/upms/passwords/SOURCE.md](../../backend/src/main/resources/upms/passwords/SOURCE.md)。初始化、設定新帳號密碼、復原及改密均檢查完整正規化後字串；不以子字串匹配拒絕所有含常見單字的長密碼。這裡的快速 SHA256 只索引公開弱密碼，與帳號 PBKDF2 儲存用途不同。

這是有限的離線常見密碼基線。大多數上游項目已被最低長度排除，72 筆長密碼仍不構成完整已外洩密碼資料庫，不能宣稱通過全面洩漏密碼驗證、保證未曾外洩或全面符合 NIST。服務不發送使用者密碼到第三方，也不在執行期下載清單；後續擴充清單須重新核對來源、授權、checksum 與驗收。

## 登入限流、受控初始化與復原

- 沿用 START 工程建議：同帳號在 15 分鐘內最多 10 次失敗，達限暫停登入 15 分鐘。不存在、停權、密碼錯誤、受限帳號對外使用相同 HTTP 401 與訊息，避免揭露帳號存在狀態。
- 已知及未知帳號的計數由單一 instance 記憶體管理。鍵先組成 `canonicalUsername:DBauthVersion`；未知帳號使用 `canonicalUsername:unknown`，再取 SHA256，不保留輸入的明文帳號。最多 10000 筆，先清理過期資料，滿容量時對尚未記錄的新識別 fail closed，不驅逐仍活動的鎖定紀錄，避免以大量未知帳號繞過鎖定。固定 128 個 stripe 鎖序列化同帳號登入檢查與計數。
- 成功登入清除目前認證版本的失敗計數。受控復原在 DB 遞增 `authVersion`，主 Web 程序下一次登入會使用新的計數空間，不依賴另一個 CLI 程序清除本機 Map；舊版本計數留待 TTL 過期，稽核中的「清除」表示舊版本計數不再適用。這可避免復原後的 15 分鐘臨時憑證被舊版本 15 分鐘鎖定耗盡；全域滿容量時仍維持 fail closed。
- 一般登入、失敗登入、顯示名稱編輯及重取 CSRF 不變更 `authVersion`。只有通過既有權限與交易守衛的憑證／角色／啟用狀態變更才會遞增版本；輸入 DTO 不接受指定版本來選擇限流空間，不能藉重試或普通登入繞過 10 次失敗限制。
- 首次管理員僅由具主機操作權的真人執行離線命令，帳號表全空才允許；不提供公開 bootstrap HTTP API、預設帳號或固定密碼。操作者與原因必填，密碼經隱藏 Console 輸入，不放命令列參數、範例環境檔或日誌。初始化仍受資料庫交易與 canonical lock 保護。
- 帳號建立／受控復原設定 `mustChangePassword`，臨時密碼最長 15 分鐘有效。首次登入只能取得必要本人狀態及 CSRF、改密、登出；不能操作 UPMS 管理 API。改密成功後使舊認證失效並要求重新登入。復原不提供匿名 HTTP 入口，不由 AI 發送臨時密碼。
- 稽核保存操作、時間、操作者／目標識別、原因或結果；不保存密碼、hash、Session ID、CSRF token 或完整認證 payload。

以上 15 分鐘臨時期限及限流數值是本次沿歷史提案採用的工程預設，不是官方強制值，也不授權新業務權限。單 instance 重啟會重置記憶體限流；正式部署前須另驗證持久或分散式防濫用策略，不宣稱這是多 instance 防護。

## Session、撤銷與交易

- 同源 Session + CSRF，不使用 JWT、remember-me 或權限聯集。一帳號只可有一個固定業務角色：`ADMIN`、`DISPATCHER`、`DRIVER`。
- Session 閒置 30 分鐘、登入起最多 8 小時，每帳號最多 3 個並存；第 4 次登入撤銷登入時間最早者，同時刻按建立順序處理。成功登入換 Session ID，防止 Session fixation。
- Session 及 CSRF 狀態只在單 instance 記憶體，不引入 Redis 或共用認證服務。程序重啟使所有人登出；DB 帳號及稽核保留。不得將此部署為未經驗證的多 instance 系統。
- DB 的 `authVersion` 隨停用、角色異動、復原及改密等認證變更遞增；後續請求核對帳號狀態及版本，舊 Session 不能繼續使用。使用者改密後本次及其他 Session 均須重新登入。
- 所有受保護資料寫入與撤銷交易先鎖同一筆 canonical singleton row，再處理帳號列，以固定鎖順序協調初始化、改密、角色／停用變更、復原及寫入前的版本檢查；防止已撤銷身分仍在競爭交易中提交。登入以固定 stripe 鎖協調同帳號驗證及失敗計數；登入結果攜帶 DB 認證版本，若與並行撤銷競爭而取得舊版本，後續請求即失效。這是本階段的小型單公司一致性取捨，會序列化相關資料寫入，不能據此宣稱高併發擴展能力。
- 保護最後一個啟用中的 ADMIN，拒絕將其停用或降級；這項完整性守衛不增加任何人的操作權限。帳號編輯另以 DTO `version` 驗證樂觀鎖，衝突回 HTTP 409。

## CSRF、Cookie 與 TLS

- 所有寫入，包括登入、登出及改密，都必須通過 CSRF 驗證。`GET /api/v1/auth/csrf` 提供 token 與 header 名稱；前端在記憶體持有 token，於初載、登入成功及登出成功後重新取得。`SameSite=Lax` 不能取代 CSRF。
- 此實作以 Servlet HttpSession 保存最小 `SessionPrincipal`，每次請求重新查核 DB 狀態、角色、`authVersion` 與期限，再建立當次 Spring Authentication；`NullSecurityContextRepository` 不快取可過期的 Spring Authentication。Spring 的外層 SecurityContext filter 於請求結束清除 thread-local context。登入明確執行 Session ID 輪換、併存限制及 CSRF 輪換，不能只設定 thread-local SecurityContext 就視為完成；若將來改採框架認證持久化流程，必須同時正確配置 SecurityContextRepository 與 SessionAuthenticationStrategy。
- Session Cookie 必須 `Secure`、`HttpOnly`、`SameSite=Lax`、`Path=/`。前端不讀取 Session Cookie，也不以 localStorage 保存認證權杖。
- DEV／CI 的瀏覽器流程透過本機自簽 TLS gateway（localhost:8443）同源提供前端及 `/api`。私鑰由本機／CI 產生並忽略，不提交、不充當正式憑證；gateway 移除來自用戶端的 `Forwarded` 並覆寫 `X-Forwarded-Proto`。後端採框架 forwarded header 支援，因此 DEV 後端只向 loopback 發布，容器內走受控網路；這個本機信任邊界不能當作可將後端直接公開的正式代理驗證配置。
- 不提供 HTTP 非 Secure Cookie 覆寫。伺服器整合測試也使用測試原始碼中產生的隔離 keystore 與真實 TLS；正常可執行套件不得提供能由一般環境參數打開的登入後門。瀏覽器 Secure Cookie、登入與 CSRF 驗收同樣在 TLS 路徑實測。

## 契約與驗證界線

新增 [UPMS 契約](../06_api/upms.md)，不修改既有 OpenAPI 0.2.0 訂單草稿契約、五項授權或 Q01–Q05。ADMIN 可管理本模組帳號；DISPATCHER／DRIVER 僅有本人認證流程及基礎工作台。CRM、ADM、TOM、派工及照片仍不在本包。

目前 Security matcher 明確保護 `/api/v1/auth/**` 與 `/api/v1/upms/**`，公開工程端點保留；其餘尚無業務實作的路徑不代表未來端點已獲公開授權。後續模組新增 API 時必須同步定義 matcher、後端權限及資料範圍測試，不可沿用目前的 fallback 就宣稱新模組安全；本包不提前更改不存在的訂單操作或原契約。

驗證須包括空庫及 V1→V2 升級、並發初始化、最後 ADMIN、版本衝突、單角色、未登入／停權／強制改密守衛、限流、過期／並存／撤銷、缺少與錯誤 CSRF、TLS Cookie、長密碼 blocklist 與 PBKDF2 成本。實際通過項目以驗收紀錄與 CI artifacts 為準；不以本 ADR 作為完成證據。

## 官方查核來源

查閱日：2026-11-25。

- [Spring Boot 4.1.3 BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.3/spring-boot-dependencies-4.1.3.pom)：Spring Security 7.1.3。
- [Spring Security 7.1.3 PBKDF2 實作](https://github.com/spring-projects/spring-security/blob/7.1.3/crypto/src/main/java/org/springframework/security/crypto/password/Pbkdf2PasswordEncoder.java)：salt、algorithm、iterations 及 256-bit 輸出構造器。
- [OWASP Password Storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)：PBKDF2-HMAC-SHA256 的 600000 次建議；PBKDF2 可行不代表系統取得 FIPS 認證。
- [NIST SP 800-63B-4](https://pages.nist.gov/800-63-4/sp800-63b.html#passwordver)：單因素至少 15 字元、Unicode code point、NFC、完整字串 blocklist、不強制組合或定期輪換、登入限流。
- [Spring Session 管理](https://docs.spring.io/spring-security/reference/7.1/servlet/authentication/session-management.html)：SecurityContext 保存、Session fixation、併存 Session 與自訂認證流程。
- [Spring CSRF](https://docs.spring.io/spring-security/reference/7.1/servlet/exploits/csrf.html)：session token repository、SPA 及登入／登出後重取 token。
- [SecLists 固定來源](https://github.com/danielmiessler/SecLists/blob/fcde9140df969b1e4d7bb056122646dace4ed4cc/Passwords/Common-Credentials/xato-net-10-million-passwords-100000.txt)及 [MIT 授權](https://github.com/danielmiessler/SecLists/blob/fcde9140df969b1e4d7bb056122646dace4ed4cc/LICENSE)。
