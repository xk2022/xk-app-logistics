# LG-M4-05 管理確認及TOM結案交接

前置M4-04 PR #26及exact main四項成功；依[七包交接](m4-execution-handoff.md)及[工程採用](../98_decisions/2026-11-26-m4-05-engineering.md)。獨立分支feat/lg-m4-05-management-closure。

交付九項管理操作、獨立E21／company契約、V14五表／47表、管理完成確認與訂單結案雙tab。ADMIN／DISPATCHER操作，DRIVER拒絕；原Session及角色公司每次重核。原五TOM API／M1～M4-04測試與V1～V13不改。原CLOSED可無audit，不補造歷史；requiredTask null不可自行豁免。

先凍結精確契約及來源，再重點與完整clean verify、前端四門檻、十份靜態validator。真PG空庫／V13→V14全41舊領域表保留／legacy CLOSED、restart及正式JAR無test policy；真TLS桌面與手機管理流程、權限、原POST未知／CSRF／版本／重送、原API CLOSED與歷史OPEN receipt、資源再用、跨scope及deferred故障回滾。保留全部舊測試，不用mock代替runtime。

成功後才Commit／Push／獨立PR；PR四CI→merge→exact main四CI，更新[驗證](../09_testing/m4-05-validation.md)與進度，再自動M4-06。正式確認／結案政策仍拒絕，正向僅隔離合成；無M5或正式部署。
