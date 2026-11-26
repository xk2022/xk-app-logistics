# LG-M4-04 工程採用｜任務完成與有效資源釋放

工程範圍依已核准Q45及[M4交接](../08_development/m4-execution-handoff.md)，不核准正式安全確認者、D04／D05／D06／D10、provider或結案責任。基礎main `e55ee4ecdf98d6f314b60e45c96b0b18d9e5489e`：M4-03 PR #25 CI45787301633／main CI45788390226 attempt1四項全過後開始。公司規範再次核對`9213a3d8dacb8774d87c26cb736c4a4315779e95`，無依賴或技術版本升級。

## 採用

- 新獨立三項DRIVER API：GET本人completion、GET completion-policy、POST complete。Detail16、Policy8、Result4、Witness5、Requirements6；精確定義以[新契約](../06_api/tms-completion.md)及OpenAPI為準。舊五項訂單、M3派工及M4-01～03全部契約與V1～V12原文保留。
- 完成只允AWAITING_COMPLETION→COMPLETED／執行版本+1。原assignment仍ASSIGNED／version0／generation不變，原reservation acquired／releasedAt／releaseReason不改；不假造REVOKED、撤派釋放或新舊API enum。新immutable completion witness才是完成及有效解除證明。
- V13新增tms_completion／tms_completion_command兩表，共42表；reservation只加nullable completion_id。有效排他唯一索引及所有目前占用消費者統一使用released_at IS NULL AND completion_id IS NULL。完成只標記同次精確二／三筆原資源列，不能按resourceId盲寫或刪除歷史。
- DB保護只允原撤改釋放或一次completion標記兩條互斥UPDATE。完成後禁止任何新reservation，即使新marker為NULL或多一個TRAILER；trigger鎖assignment及既有reservation rows，堵住REPEATABLE_READ舊快照，另有deferred最終完整集合檢查。既有完成／釋放列不可再改刪。
- terminal execution／typed readiness與completion events／witness／成功ledger／完整二或三筆marker由typed FK、互反deferred關係與reverse constraint triggers同交易保護；任一SQL／provider／commit故障全部回滾，不造半完成、假收據或空占用。V13遇舊COMPLETED孤立資料拒絕升級，不虛構backfill。
- 舊進度照片上傳仍只原兩phase。獨立合成完成策略明確採用最新IN_PROGRESS→AWAITING成功事件的原選照集合，重新驗同assignment／task／driver、VERIFIED、私有bytes／hash及PNG；沒有新上傳路由、不自造異常、不重標phase或套用補件豁免。
- V11單列immutable不代表集合已封存。新link INSERT trigger鎖同root、重驗typed event及原actor／company／assignment／task／driver／POST／exact progress path與原JSON execution phase／version的成功receipt；原流程event→links→ledger同交易，原success之後禁止追加。completion proof也必須該原成功receipt；COMPLETED event一律不能加event_evidence。不用jsonb解碼raw TOM。
- 普通CompletionPolicy維持UNCONFIRMED，checkbox true不能核准安全解除。正向僅外部test-only launcher、實際隔離DB與同scope合成context，且dispatch／evidence／provider／獨立安全策略均確認。正式JAR不含test factory或核准開關。
- 新完成後窄讀取／重播只限目前正常帳號、原有效Session、同physical driver目前綁定及同原current ASSIGNED anchor。可讀原safe14與terminal witness，不跟隨已重用資源的新owner；不要求今日provider或TOM仍開放。舊M4-01～03 current-only本人入口仍404，無全域放寬。
- 同physical driver換合法帳號可讀窄事實，但ledger仍actor scoped，MISS依版本／狀態E2001／E2002拒絕，不查其他人的key。完成policy.mode是目前策略，ALREADY_COMPLETED優先；witness.mode是原不可變事實，不能升級今日核准。
- 舊十四欄本人列表保留同current ASSIGNED anchor的原membership／page／count，供M4-06探索；不新增第四route或欄位。既有UI明示指派及撤改紀錄，不把null legacy release或404當完成／占用證明。管理完成畫面留M4-05，DRIVER完整操作留M4-06。
- TOM-owned TaskCompletionFacts窄port由TMS純JDBC adapter實作；TOM驗同單依賴圖／sourceVersion，全部前置必須有真完成witness才ready。無反向service constructor cycle、跨模組私有SQL、計時／釋放／RESOLVED捷徑或自動派工。
- 新E20錯誤僅同frozen POST於success-ledger MISS後的E2001／02／03／07／08／09能結清該次未套用；key衝突／in-progress／FK／共同碼／transport仍未知。GET不結清另一個POST。原Session於body讀取前捕捉，POST header-only CSRF，實際串流16KiB+1上限，無body參數預解析。

## 驗證要求

[本包驗證](../09_testing/m4-04-validation.md)另記真實結果。升級比較固定原reservation欄位並另驗新marker全NULL，保留歷史fixed-target測試；不用新增NULL欄位造成的row_to_json差異誤判資料遺失，也不刪除舊斷言。重點／完整Maven與全部前端檢查之後才啟動隔離Compose，空庫／重啟／同源、110憑證、36桌面手機E2E及九份HTTP、隱私／掃描逐項以實測核對，不以本決議當通過。尚未實作M4-05／06、M5或正式部署。
