# 專案狀態｜2026-11-26（Asia/Taipei）

- M0：PR #1 已驗收並合併，merge commit `3f86a25c7b8311d753a53754a4b5898113d92ea4`。靜態規格、契約及 Q01–Q05 保留。
- LG-M1：[PR #2](https://github.com/xk2022/xk-app-logistics/pull/2) 交付前後端工程基礎、Flyway 空庫基線、DEV Compose、測試與 CI；首輪 GitHub CI 四項全通過。實測、受測 SHA 與最終 CI／合併追蹤見 [M1 驗證](../09_testing/m1-validation.md)。
- 產品驗收：M1 驗收工程基礎，M2-01／02／03 分別驗收 UPMS／ADM／CRM；M2-04 已執行原首片訂單草稿契約及真實 HTTP schema 驗證。完整性檢查仍為 `NOT_GRANTED`，不代表正式受理或運輸執行功能通過。
- LG-M2-01：[PR #4](https://github.com/xk2022/xk-app-logistics/pull/4) 依已合併 PR #3 決策交付 UPMS，首輪遠端四項品質檢查全通過；帳號、固定角色、Session／CSRF、受控初始化／復原、角色 UI 及 V2 migration 的結果見 [M2-01 驗證](../09_testing/m2-01-validation.md)。
- LG-M2-02：[PR #6](https://github.com/xk2022/xk-app-logistics/pull/6) 依已合併 PR #5 及 Q11–Q15 交付 ADM 字典／核准參數框架，首輪四項遠端品質檢查全通過；結果見 [M2-02 驗證](../09_testing/m2-02-validation.md)。正式參數鍵及非 ADMIN 讀取分類仍未核准，維持關閉。
- LG-M2-03：[PR #8](https://github.com/xk2022/xk-app-logistics/pull/8) 依已合併 PR #7 及 Q16–Q20 交付 CRM 客戶、聯絡人、地址、角色介面與 V4，首輪四項遠端品質檢查全通過；實際本機／遠端品質結果及交付追蹤見 [M2-03 驗證](../09_testing/m2-03-validation.md)。
- LG-M2-04：[PR #9](https://github.com/xk2022/xk-app-logistics/pull/9) 依使用者交辦實作 OrderCore／TOM 與原五項 API，包含真正訂單快照、版本／重送及客戶停用競爭，首輪四項遠端 CI 全成功；實際狀態與交付追蹤見 [M2-04 驗證](../09_testing/m2-04-validation.md)。
- LG-M2-05：依本次交辦建立 ADMIN／DISPATCHER 訂單工作台、列表、新增與完整編輯、有效客戶查詢、多項目／多段／依賴、版本與原次重試；[實測與交付追蹤](../09_testing/m2-05-validation.md)。
- M2 完成狀態：M2-01 至 M2-05 的登入、核心主檔、五項草稿 API 與完整草稿 UI 已完成實作與本機首片驗收。M2-05 前端 155／後端 211／E2E 18／實際 HTTP schema 296 全過；最終合併與 main CI 追蹤見交付 PR，不代表正式受理、派工或完整運輸閉環已完成。
- LG-M3-01：依已合併 PR #11 與 Q21–Q25 實作 FMS 車輛主檔、ADM 分類、UPMS 權限、V6、管理介面及可信行政狀態來源查詢。前端 238／後端 243／E2E 20、原 TOM HTTP schema 296 與 FMS 174 全過；[實測與交付追蹤](../09_testing/m3-01-validation.md)，最終 head／merge／main CI 以交付 PR 追蹤。
- LG-M3-02：[產品 PR #14](https://github.com/xk2022/xk-app-logistics/pull/14) 已依已合併 PR #13 與 Q26–Q30 交付司機主檔、選擇性唯一 DRIVER 帳號綁定、本人唯讀、行政出勤、ADM 證照分類與 V7，首輪 PR CI 四項全過。本機前端 330／後端 280／E2E 22、實際 TOM 296／Vehicle 174／Driver 282 回應全過；實測與合併後 Maven Central 429／重跑介面失敗的處理見 [M3-02 驗證](../09_testing/m3-02-validation.md)，最終文件交付及 main CI 由後續驗收 PR 追蹤。
- LG-M3-03：依已合併 PR #16 與 Q31–Q35 實作獨立正式受理、原子版本／不可變事件／重送、受理後唯讀與待派車顯示；本機前端 426／後端 312／E2E 24 與實際 TOM 414／Acceptance 30／Vehicle 174／Driver 282 回應全過；實際驗收與交付狀態見 [M3-03 驗證](../09_testing/m3-03-validation.md)。
- LG-M3-04：已完成七項 TMS API、原子占用／撤改、不可變歷史、本人最小查詢與 V9；本機前端 426／後端 369／E2E 26、五份實際回應驗證全過。production 政策未核准維持拒絕，正向驗收僅明確隔離合成政策；交付與 main CI 見 [M3-04 驗證](../09_testing/m3-04-validation.md)。
- LG-M3-05：調度工作台、指派／撤改／歷史及 DRIVER 本人手機唯讀完成；本機前端 590／後端 369／E2E 28 與五份實際 HTTP 回應驗證全過，交付及 main CI 由 [M3-05 驗證](../09_testing/m3-05-validation.md)與產品 PR 追蹤。
- 下一工作：M3-04 PR #19 與 main CI 已四項全過，由 merge `7180c1f8894baf0d6b878c435ce879863817f2ed` 完成本機 [M3-05](../08_development/m3-05-dispatch-ui-handoff.md) → [M3-06](../08_development/m3-06-integration-handoff.md) 依序實作、驗收；司機回報、照片、結案及其他 FMS 尚未開發，原四份訂單契約來源保持原樣。
- 尚待確認：真實場景例外、照片提供者與保存、供應商／費用、正式帳號與部署、覆蓋率門檻與維運責任。無正式部署。
- 歷史主要任務：[codex-xk-logistic Issue #4](https://github.com/xk2022/codex-xk-logistic/issues/4)。不更動舊庫。
