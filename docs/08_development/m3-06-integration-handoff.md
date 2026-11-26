# LG-M3-06 訂單與派車整合驗收｜整合驗收

前置：M3-05 合併及 main CI 全過。按 [M3 驗收](../09_testing/m3-integration-acceptance.md) 執行真 PostgreSQL、HTTP 契約、桌面／手機 E2E 全量回歸；驗證空庫、升級、交易一致性及 UI 權限。對所見缺陷在本工作包修正但不新增未核准業務規則。記錄最終 SHA、CI、限制與 M3 完成證據；逐包 PR／合併，不部署。未測項標 not_run；完成後停在 M4 規格決議，不自動擴大。

前置 PR #20 與 main CI 已四項成功，由 `7acbd43f5e098a877af6a1458e5b32d81e390f41` 開始本包。實際執行見 [M3-06 驗證](../09_testing/m3-06-validation.md)及[M3 報告](../09_testing/m3-integration-acceptance.md)。
