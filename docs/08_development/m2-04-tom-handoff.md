# LG-M2-04 TOM｜Implementation and validation

使用者已直接交辦本工作包，基準 main `070383e1ff76dcaaf16693e8fcd8d4f5c0bb915c`；CRM [PR #8](https://github.com/xk2022/xk-app-logistics/pull/8) 已合併且 [main 四項 CI](https://github.com/xk2022/xk-app-logistics/actions/runs/45563712633) 全部 success。公司規範最新固定至 `5be640ab11811b24706101ea13db73185e9ce4d8`。

1. 讀取 README、AGENTS、docs 索引、原首片五項 API／OpenAPI 0.2.0／40 欄字典／角色矩陣／合成案例與歷史模型。
2. 依原契約實作 OrderCore、TOM、項目、任務、依賴及 V5；保留 UUID/ref 身份、完整替換、聚合版本、歷史快照與重送。
3. 透過 UPMS／CRM 公開服務整合，完成 Session Cookie 名稱兼容、CSRF、三角色、客戶停用與建立／換客戶的同交易鎖定。
4. 保留原 UPMS、ADM、CRM 測試與 10 項 E2E；補 TOM 領域、PostgreSQL／HTTP／版本／重送／快照／競爭及 V4→V5 升級，實際回應驗原 schema。
5. 全部品質檢查通過後 commit、push、獨立 PR 並合併 main；更新實際證據及工作進度。

本包不實作正式受理、派車、司機回報、照片、結案或部署；完整操作 UI 在 M2-05。原四份契約來源保持原樣，當前結果見 [M2-04 驗證](../09_testing/m2-04-validation.md)，不改歷史註記來冒充執行結果。
