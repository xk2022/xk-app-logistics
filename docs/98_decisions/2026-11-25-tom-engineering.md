# LG-M2-04 TOM 工程決策

日期：2026-11-25。來源為使用者本工作包、已採用 ORD-P01–P03／ACC-P01–P02、[首片 API](../06_api/first-slice.md)及固定至 `7092510c7e63a3f5781e1f0a24787a32e3248ac4` 的[歷史資料模型](https://github.com/xk2022/codex-xk-logistic/blob/7092510c7e63a3f5781e1f0a24787a32e3248ac4/docs/FIRST_SLICE_DATA_MODEL.md)與[API 說明](https://github.com/xk2022/codex-xk-logistic/blob/7092510c7e63a3f5781e1f0a24787a32e3248ac4/docs/api/README.md)。公司規範固定至最新 `5be640ab11811b24706101ea13db73185e9ce4d8`。精確實作、測試與提交證據另見 [M2-04 驗證](../09_testing/m2-04-validation.md)，本文件不代替執行結果。

## 契約與授權

使用者明確要求「保留原契約，不以其他模組的回應格式覆蓋」。因此五項訂單 API 保留 `{code:"OK",message,data,traceId}`，錯誤加 `fieldErrors`，不加 `timestamp` 或改成 `0000`／`E04xx`。這是公司通用 envelope 的既有專案契約特例。安全 filter、CSRF、未登入與內部錯誤也必須使用原訂單錯誤 enum／格式；UPMS、ADM、CRM 保持既有 envelope。

[原 OpenAPI 0.2.0](../06_api/openapi.yaml)、[角色矩陣](../06_api/order-draft-access-policy.json)、[合成案例](../06_api/order-draft-contract-examples.json)及[40 筆欄位字典](../05_database/first-slice-fields.csv)四檔保持原樣，其內歷史 `not_run`／`not_implemented` 不是當前執行狀態。現在的驗收結果只記在新證據文件與 CI。

採已核准單公司、單角色。正常 ADMIN／DISPATCHER 可操作本公司所有訂單，不加入 owner-only 限制；DRIVER 拒絕五項整單 API。公司範圍是伺服器固定 `SINGLE_COMPANY`，不接受 body、query 或 header 冒用，也不藉本包實作多租戶。列表、明細、計數及重送均在同一伺服器範圍。

## 單一 Session 的兩個契約名稱

原訂單契約為 `JSESSIONID`，既有 UPMS／ADM／CRM 使用 `XKSESSION`。保留一個 Tomcat HttpSession 與相同 Session ID，透過 CookieProcessor 在容器選取 Session 前將單獨的舊名稱解析為 canonical 名稱；不另建 Session 儲存、token 或認證系統。登入、CSRF 初始 Session 與登出／改密後的新匿名 Session 同步鏡像兩個 Cookie，均為 Secure、HttpOnly、SameSite=Lax、Path=/。

若兩名稱或重複 Cookie 包含不同 ID，認證前拒絕並過期兩個用戶端 Cookie，允許下次重新初始化匿名 Session／登入；不選擇或撤銷任一伺服器 Session。相同 ID 可並存。登入輪換、閒置／最長期限、認證版本、停用、登出、改密及並存上限仍由原 UPMS 控制。Spring Security 7.1.3 使用明確 authentication strategy，移除互斥的顯式 IF_REQUIRED（預設語意不變），不在每個已登入請求自動再輪換 Session／清除 CSRF。登入仍手動 invalidate／changeSessionId／重發 CSRF。撤銷後的 CSRF 拒絕若由容器建立新匿名 Session，只在非 Cookie 衝突且有原生 Set-Cookie、無 AUTH 的情況同步別名；不鏡像一般業務回應來掩蓋輪換問題。只允許 Cookie Session tracking，不以 URL `;jsessionid=` 作認證，啟動守衛拒絕由設定改成 URL tracking。

## 聚合、身份與保存

OrderCore 負責共同識別及 lifecycle；TOM 負責訂單類型、客戶快照、DRAFT、聚合版本及項目／任務。跨模組使用公開服務，不直接引用其他模組 repository。新單只建立 OPEN＋DRAFT、version=0；CANCELLED／CLOSED／ACCEPTED 保留讀值，不提供這些轉態。

UUID 與技術單號均由伺服器產生，不採用合成範例的 DEMO 單號作正式編碼。技術識別不代表已核准營業流水號規則。項目／任務各自以同單 `clientRef` 穩定定位；當前仍存在的 ref 保留 UUID／taskNo。移除後在後續版本再新增同字串屬新子項，產生新 UUID／taskNo，歷史版本仍留原值。

PUT 是整張替換。省略可選值變 null、省略集合變空；空 shipping／siteContact 物件與整組 null 分別保存。不以陣列位置推定前置依賴。依賴可跨同單不同項目，須存在、非自己、無環。保留子項不能因實作全刪重建而誤觸受保護 FK；序號交換不得因中間狀態造成假衝突。沒有正式執行／派工表，受保護關聯以真正資料庫 FK 及前態守衛拒絕，不能建立假派工資料來宣稱整合完成。

每次新成功 PUT（即使內容相同）恰加一個聚合版本並保存不可變快照；成功重播不加。版本上限為 9007199254740991，達上限的新保存回 409 ORDER_NOT_EDITABLE，不寫入超界版本。移除草稿不刪 CRM／UPMS、歷史版本或外部受保護資料。

## CRM 快照與交易

首次建立／明確換客戶，先經 UPMS 公開授權及 CRM `requireEnabledForNewReference`，在同一可寫交易持有有效客戶鎖直到訂單、版本與重送紀錄原子提交。客戶停用／改名與新引用有明確序列化次序，不留下查完有效後再被停用的競爭窗口。

相同 customerId 的後續 PUT 保留歷史 customerName，即使主檔已改名或停用，仍可維護未執行草稿；只有明確換成另一有效客戶才取新快照。歷史讀取不以今日主檔名稱覆寫保存值。只讀 acceptance-check 另查目前客戶有效性，停用則列 ORD-P01 缺口，不把歷史可讀等同可新增引用。

讀取完整聚合與只讀檢核須對同一版本工作，避免頭版本與子項來自不同提交。列表內容與計數亦使用一致讀取。寫入、不可變版本與成功重送紀錄同交易，任何中途失敗均回滾。

## 重送與原始值

重送鍵範圍為 server scope＋actor＋HTTP method＋無 query 的 canonical path＋區分大小寫的 key。UUID 路徑正規化。先驗當前角色／Session／CSRF／存取權限，再查 key；同 key 同 body 的成功紀錄重播原 status、業務 data、Location，本次產生新 traceId。重播可為舊版，不能先因舊 expectedVersion、已改名或停用客戶拒絕合法重播。

同 key 不同 body 回 IDEMPOTENCY_KEY_REUSED；正在處理以有界鎖／try-lock 回 IDEMPOTENCY_IN_PROGRESS，不執行第二次副作用。重送處理鎖使用 tuple 的 SHA-256 派生 signed 64-bit PostgreSQL advisory transaction try-lock；完整 tuple 另有 DB UNIQUE。64-bit 鎖碰撞最多保守回短暫 IN_PROGRESS，不放寬資料唯一性或造成第二次副作用。失敗不留成功紀錄，可同 key 重試。未核准 D10 清理期限前不自動過期或復用 key，也不稱為已核准永久保存政策。

hash 依原契約：嚴格 JSON、拒絕重複 property；property 依 Unicode code point 遞迴排序、UTF-8 緊湊 JSON、SHA-256。保留原字串、陣列順序、null 與省略差異；不套 CRM 的 NFC／trim／空白轉 null。JSON 數學整數可用 1、1.0、1e0 等表示，通過精確範圍驗證後 canonical 為十進位整數；不以浮點截斷，極大指數先比較範圍，避免不必要的大整數配置。

原契約可表示的 U+0000 以可逆資料庫表示保存，不靜默刪除或另加 schema 外的業務拒絕；不成對 surrogate 不能形成有效 Unicode／UTF-8，應明確拒絕，不以 replacement character 製造 hash 碰撞。重量／體積仍為非負十進位字串與 BigDecimal，null 與已知零不同。日期時間含 offset、微秒最多六位；契約允許 ±23:59，不能因 Java ZoneOffset 的 ±18h 上限而縮限。使用 JDBC 4.2 OffsetDateTime 綁定真正瞬間，避免 java.sql.Timestamp／GregorianCalendar 在舊日期的歷史曆法位移；以 PostgreSQL EXTRACT(epoch) 獨立驗證。date-only 使用 LocalDate，不轉 UTC 午夜。

## 只讀檢核與界線

checkedRules 固定 ORD-P01、ORD-P02、ORD-P03，逐項回 JSON Pointer／40 欄 fieldId 或集合規則／ruleId／繁中訊息。COMPLETE 只代表三項已採用完整性。pendingPolicyIds 保留 D04_CONTACT、D04_CONTAINER_FORMAT、D04_WEIGHT、D04_DICTIONARY、D04_DEADLINE_EXCEPTION、D05_REQUIRED_TASK_WAIVER；operationalAcceptance 永遠 NOT_GRANTED。

不把候選 taskKind／containerType 當正式 enum，不要求櫃號或重量提前必填，不用零代未知，不開放 requiredTask=false 免除。正式受理、派車、司機回報、照片、結案與部署不在本包；完整操作 UI 留 M2-05。
