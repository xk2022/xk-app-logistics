# LG-M4-06 工程採用

狀態：使用者已授權Q41–Q47與M4全部工作包；本文件只固定實作及驗收選擇，不核准正式業務例外。基線與公司規範見[工作包](../08_development/m4-06-driver-ui-handoff.md)。

- 既有FMS `driverApi.ts`保持，新增獨立`driverExecutionApi.ts`與`driverExecutionModel.ts`，避免覆寫舊client。原14欄列表探索與16欄完成詳情各自parser；全部舊API契約／schema／migration不改。
- 七POST各自精確原契約與版本單位，private opaque意圖保存原次內容。錯誤可信來源綁定同一已送POST；不使用共通錯誤或另一操作的結果解除未知狀態。CSRF可更新，body／key／原版本不可自動改。
- typed array不對非空buffer做freeze；先複製原bytes為immutable Blob，再await digest。public意圖不含原文、Blob、key或全客戶來源，私有capability於退休時刪除。404清資料後只留下不可重試的未知標記。
- active scope不等於可送出：另有背景／登出等待的暫停gate，await後及fetch前檢查。StrictMode新setup用新scope；暫停保原意圖但不自動續送。前景fresh資料是observation，不能默默採用為舊命令token。
- 同一account工作區在logoutPending保持掛載及必要草稿，保護DOM與URL清除；普通失敗恢復原次內容。真正成功／失效／換actor／離開清除。App與auth transport用epoch及可撤銷signal同時保護送出與回覆，不以畫面epoch代替實際送出檢查。
- PNG成功bytes不寫報告；只收body-only JSON合成回應、安全截圖與aggregate結果。實際完成、資源重用、結案後窄讀及原次重試維持既有服務語意。手機指Chromium Pixel7 viewport驗收，不假稱實體裝置測試。

未核准的派工、安全／釋放、管理確認／結案責任、照片保存與現場例外維持UNCONFIRMED。無M5、正式部署、背景佇列或正式provider。實際測試及失敗修正見[驗證](../09_testing/m4-06-validation.md)。

共用CSRF helper取消檢查變嚴後，原結案client只補一個送出前abort分類guard，恢復既有REQUEST_ABORTED語意及104舊測試，不改HTTP、已送命令不確定性或舊呼叫介面。這是相容性補正，未重寫舊client。
