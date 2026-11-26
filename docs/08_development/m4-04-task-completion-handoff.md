# LG-M4-04｜任務完成與有效資源釋放

依[M4總交接](m4-execution-handoff.md)、[Q45](../98_decisions/2026-11-26-m4-execution.md)、[本包工程採用](../98_decisions/2026-11-26-m4-04-engineering.md)及[新獨立契約](../06_api/tms-completion.md)。從已通過四項PR及main CI的M4-03 merge e55ee4ecdf98d6f314b60e45c96b0b18d9e5489e開始，分支feat/lg-m4-04-task-completion。

交付三項本人完成API、V13／42表、typed完成witness與有效占用解除、無阻擋異常／必要原證據／獨立安全策略、TOM可信依賴解鎖及最小真實舊UI紀錄文字。精確二／三資源與execution／events／ledger同交易；receipt封存選照集合、禁止完成後任何新reservation、RR舊快照、原成功重播後資源再用及故障回滾均須真PostgreSQL驗證。

保留全部舊契約及測試，原十四欄列表仍保留current anchor，舊本人current-only入口完成後404；新16欄窄讀取才可看原完成事實。不得偽造舊status／release、擅准安全確認者或照片例外，不提前管理結案、完整DRIVER UI、M5或部署。

[驗收紀錄](../09_testing/m4-04-validation.md)：本機全過→commit→push→獨立PR→四項CI成功→merge→核對該main四項成功，才開始M4-05；不逐包等待使用者確認。
