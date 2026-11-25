# LG-M2-05 TOM UI 工程決策

狀態：Draft（授權工作包內的工程細化，非新業務核准或測試證據）。日期：2026-11-25。基準 main `0766f703848719064c3e1a4488ec18d0a970b7ff`，公司最新規範 `5be640ab11811b24706101ea13db73185e9ce4d8`。

依使用者本工作包、ORD-P01–P03／ACC-P01–P02、[原 OpenAPI](../06_api/openapi.yaml)、[TOM 工程決策](2026-11-25-tom-engineering.md)與[工作台規格](../07_ui-ux/order-workspace.md)細化。公司 [APP 開發](https://github.com/xk2022/xplore-kaleidoscope/blob/5be640ab11811b24706101ea13db73185e9ce4d8/doc/04_engineering/app-development-standard.md)、[API 與資料](https://github.com/xk2022/xplore-kaleidoscope/blob/5be640ab11811b24706101ea13db73185e9ce4d8/doc/04_engineering/api-and-data.md)、[開發流程](https://github.com/xk2022/xplore-kaleidoscope/blob/5be640ab11811b24706101ea13db73185e9ce4d8/doc/04_engineering/development-workflow.md)及[測試規範](https://github.com/xk2022/xplore-kaleidoscope/blob/5be640ab11811b24706101ea13db73185e9ce4d8/doc/04_engineering/app-testing-standard.md)繼續有效。原訂單 envelope 特例沿用既有明確使用者授權，不用本輪重定 API。

## 完整模型與分離協定

TOM client 使用 `OK/message/data/traceId` 及 fieldErrors 的獨立解析，保留 HTTP status、業務 code、traceId 與 Idempotency-Replayed；既有 UPMS／ADM／CRM 的 `0000/timestamp` parser 保持自身契約。同源 Cookie 與 CSRF 共用既有認證，不另建 token／Session，也不由 JavaScript 讀 HttpOnly Cookie。

列表僅為摘要。編輯須先取得完整 OrderDraftRead，再以明確白名單建立 DraftData；不把伺服器身份、快照、狀態或 audit 欄送回。PUT 為完整替換，UI 必須保存所有可編輯欄位，即使它們收合或未在當前分區顯示。clientRef 是穩定身份，不以陣列索引、元件重新掛載或排序產生新 ref。

入口名稱為「訂單工作台」，只有正常 ADMIN／DISPATCHER 可以使用；DRIVER、匿名及強制改密不開放入口。列表／明細 GET 失敗顯示讀取錯誤，明細沒有完整資料時不掛載可編輯表單，也不以 summary 或空表單替代。401 交既有重新登入流程、403 明示權限不足；前端不因失敗狀態改變使用者角色或放寬守衛。UI 隱藏入口之外仍由原伺服器強制角色／Session／CSRF／資料範圍，不擴大 DISPATCHER 的 ADM 權限。

新增／編輯都不把完整性缺項變成額外存檔必填。只讀檢核固定對已保存版本工作；其回應不更新訂單狀態。保存／載入新基準清除舊檢核，晚到結果保留受檢版本與不一致警告。dirty 或版本不同時停用缺漏的 JSON Pointer 跳轉，避免已保存陣列的索引定位到本機重新排序後的其他子項。

## 無損表單

TOM 沿原字串，不套 CRM 的 NFC／trim。null 與 remark 空字串、整組 null 與 shipping／siteContact 物件、未知數值與已知零分別保存。輸入中的空白及控制字元依原契約處理，不因原生 input／textarea 的限制悄悄改寫既有值；不成對 surrogate 拒絕。

grossWeight／cbm 保持十進位字串，不經 JavaScript 浮點。日期保持 YYYY-MM-DD；日期時間保留已驗證的原 wire offset 與最多六位小數秒，支援原契約 ±23:59。輔助台北時間顯示與可編輯原值分開，不能用 Date／datetime-local 的毫秒與本地時區縮限讀寫範圍或無聲轉換。此為原 API 的相容要求，不新增現場時區、排程或截止例外政策。

列表系統 updatedAt 以台北時間顯示至分鐘，HTML time 的 dateTime 屬性保留原字串；這只改只讀呈現，不改伺服器值。業務日期時間編輯仍保留 raw 字串、offset 與微秒。

更換 type／itemKind 不自動刪掉原欄位。更換 customerId 才請伺服器取得新名稱；未換 ID 的停用／改名客戶仍可維護歷史草稿，不能以有效選項列表覆蓋歷史選取。containerType／taskKind 仍是自由文字；空 ADM 分類、示意名稱或候選碼不能轉成必填 enum。

## 請求身份與衝突

每次新保存凍結 method、canonical resource path、原 body 與 key。保存中防止重複提交；網路／回應結果不確定或寫入 HTTP 500 時保守保留該快照，只能明確重試相同操作。不得重新組出改變 null／省略、文字、陣列或 expectedVersion 的 body 再沿用 key；也不得自動換 key 以取得假成功。確認成功後採伺服器版本，不在前端猜測加版；重播是原結果，若要今日最新版另 GET。

建立成功後不由 Manager 切換 view／key 重新掛載 Editor。由同一 Editor 採用回應為內部 baseline，保留成功／重播 notice，下一次保存以該 id／version 發 PUT。Manager 只保留返回列表時的保存提示，不能把摘要或較早的外部 props 覆蓋 Editor 的已確認基準。

VERSION_CONFLICT 先讀取遠端並比較完整內容，再二次確認載入遠端，或明確保留本地內容、採用已讀版本作為下次保存基準；後者會說明完整替換可能覆寫他人修改／移除新子項，不自動保存，也不直接把 actualVersion 填回重送。每次新保存／重試清除舊 remote comparison 與確認框；busy、pending、reading 期間停用版本處理並在事件處理器再次守衛，避免舊比較繞過未決重送凍結。

IN_PROGRESS 保持原 key；KEY_REUSED 停止不一致重送；NOT_EDITABLE／PROTECTED_RELATION 不以刪除資料解決。錯誤 fieldErrors 依本次送出內容定位；未知路徑仍保留摘要。401 及 Editor 收到 FORBIDDEN 時交既有重新登入／清除流程，不能讓下一帳號沿用訂單或未決請求。CSRF_INVALID 可重新取 token，但原寫入不自動重播。

未決寫入的重試可能在 CSRF filter、取得 token 的前置請求或 shape 驗證就失敗；這些結果不能證明較早的寫入未成功。因此已標為 unresolved 的操作，遇 CSRF_INVALID、前置錯誤、VALIDATION_ERROR、ORDER_NOT_FOUND 或 500 仍凍結同一 key/body。除成功與 401／FORBIDDEN 的安全退出外，解除白名單僅為 `400 INVALID_CUSTOMER` 及 `409 VERSION_CONFLICT`／`ORDER_NOT_EDITABLE`／`PROTECTED_RELATION`。

白名單與現行 TomService 的順序有明確耦合：先以原 scope／actor／method／path／key 查 replay，沒有成功紀錄才會產生這四種 domain 拒絕；目前 key 沒有自動到期清理。若後端移動 replay 檢查、重用上述錯誤碼或更改 key 保存政策，必須同步審查前端的 unresolved 解除規則及回歸案例，不可只維持錯誤碼名稱便視為相容。VERSION_CONFLICT 解除未決後仍走人工比較與確認，並不自動用新版本覆寫。

欄位跳轉以可用的精確 JSON Pointer 為優先，再逐層回退到父區塊或表單；關閉的 details 先展開再聚焦及捲動。定位是呈現操作，不建立 nullable 物件、不清除欄位、不改變 draft；dirty／舊版完整性指標仍不得跳轉。

當前記憶體可保存未決快照；不使用 localStorage／sessionStorage、URL、log 或測試報告持久化業務草稿與憑證。不新增自動保存、離線 queue 或跨登入寫入重播。離開／重新整理會失去本地狀態，UI 必須說明並在結果不確定後先確認伺服器資料，不能假定原寫入已取消。跨頁面與關閉分頁的提示依瀏覽器能力實作，不宣稱能保證攔截所有離開事件。

## 仍未核准及驗證責任

COMPLETE 僅為三項完整性規則齊全，operationalAcceptance 仍為 NOT_GRANTED。D04_CONTACT、D04_CONTAINER_FORMAT、D04_WEIGHT、D04_DICTIONARY、D04_DEADLINE_EXCEPTION、D05_REQUIRED_TASK_WAIVER 仍顯示為未決；不以 UI 預設補上義務免除、聯絡必填、櫃號格式、重量、字典或期限例外。

正式受理、派工、司機回報、照片、結案、業務保存期限及部署均不在本包。前端／元件／真 HTTPS E2E／原契約及後端回歸依[交接規格](../08_development/m2-05-order-ui-handoff.md)驗收；實際執行命令、數量、受測 SHA、PR／CI 與剩餘風險另記錄，沒有以本文件取代測試證據。
