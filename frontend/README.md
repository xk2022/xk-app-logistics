# Frontend

React + TypeScript + Vite 的同源工作空間。M1 服務狀態頁保留於登入頁「系統狀態」入口；M2-01 提供 Session 登入、登出、本人狀態、強制改密、三角色工作台及 ADMIN 帳號管理；M2-02 加入 ADMIN 系統管理；M2-03 提供 ADMIN／DISPATCHER 客戶管理；M2-05 加入訂單工作台、三類草稿、多項目與多段任務編輯。

```sh
npm ci
npm run dev
npm run lint
npm run typecheck
npm test
npm run build
```

開發服務預設 `http://localhost:5173`，`/api` 代理至 `http://localhost:8084`；容器內以 `API_PROXY_TARGET=http://backend:8084` 覆寫。`API_PROXY_TARGET` 僅由 Vite 開發服務讀取，瀏覽器仍呼叫同源相對路徑。工程狀態請求逾時為 8 秒，認證與業務請求為 10 秒；離開畫面時中止讀取請求。

精確 Node/npm 版本與依賴見 `package.json`、`package-lock.json`。`/auth/me` 決定登入畫面；已登入 API 遇 401 清除本人及帳管畫面，返回登入。首次登入必須先改密；改密成功撤銷登入狀態並要求重登。

API 位於 `/api/v1/auth/{csrf,login,logout,me,password}` 與 `/api/v1/upms/{roles,accounts}`；帳號編輯使用 `/api/v1/upms/accounts/{id}`。寫入一律帶同源憑證與記憶體中的 `X-CSRF-TOKEN`。登入、登出及改密後重新取得 token；403 更新 token 並顯示錯誤，不自動重送寫入。讀寫逾時為 10 秒，錯誤不顯示原始網路例外。密碼僅留在表單狀態，提交後清空，不寫入 storage、URL 或日誌。

角色固定為 ADMIN、DISPATCHER、DRIVER，一帳號一角色。ADMIN 可建立與編輯帳號，版本衝突提示重新載入。帳號復原僅顯示受控離線流程指引；不存在公開註冊、匿名重設、動態權限或物流業務功能。前端選單僅提供操作導覽，授權由後端強制執行。DEV/CI 的 Secure Session 端到端驗證須使用根目錄文件指定的 HTTPS gateway。

ADMIN 從「系統管理」進入字典管理、系統參數與異動紀錄。ADM API 為 `/api/v1/adm/{categories,items,parameters,parameter-definitions,options,change-logs}`，使用相同 Session/CSRF transport。列表採每頁 20 筆；代碼、參數鍵及型別建立後唯讀，無刪除操作。409 顯示明確衝突提示，手動重新載入會清除編輯草稿，以列表的新版本重新編輯；不自動重送。

`ORDER_TYPE` 與其 IMPORT／EXPORT／LOCAL 項目僅供固定枚舉對照。停用分類會顯示歷史項目仍在、不可供新引用的提示，並讀取實際可用選項。其他三個種子分類的正式項目由核准需求建立，不以合成案例作正式種子。

系統參數鍵只能從後端核准定義選擇，不能自由輸入鍵或型別。正常定義為空時顯示「尚無核准參數」，不提供建立表單。隔離測試定義驗證 TEXT／NUMBER／BOOLEAN 全流程；API 的 `valueText` 一律為字串，數字保留十進位精度並依定義驗證範圍，布林值限定 `true`／`false`。異動前後值以 React 文字及 JSON 顯示，不插入 HTML。

「客戶管理」僅向正常登入的 ADMIN／DISPATCHER 顯示；DRIVER 不顯示入口或請求 CRM 資料。CRM API 位於 `/api/v1/crm/customers`、客戶明細下的 `/contacts`／`addresses`，以及 `/api/v1/crm/customer-options`。列表支援搜尋、啟停篩選與每頁 5／20／50 筆；切換條件會返回第一頁。有效客戶預覽只查詢啟用客戶，客戶變更後重新讀取，不建立訂單。

建立客戶只要求代碼與名稱，聯絡人只要求姓名，地址只要求內容。客戶代碼建立後唯讀；聯絡人與地址固定隸屬原客戶，沒有移轉或刪除操作。統一編號可空、可重複，不限定八碼或稅務規則；電話接受國碼、空格與分機等文字，電子郵件只驗證基本格式、不寄信；地址類型是可空的 40 字元自由描述，不是核准枚舉。

CRM 使用既有 CSRF transport。更新帶版本且只送可變欄位；空白選填值轉 `null`。409 保留草稿，手動重新載入後以最新版本重新編輯，系統不自動重送。停用客戶仍可維護歷史、聯絡人與地址，但不供新引用。切換客戶或離開頁面時忽略舊請求回應，不讓資料回填到其他客戶畫面。所有 DEV／CI 案例僅使用合成資料，不儲存 CRM 表單內容到瀏覽器 storage 或日誌。

`public/xk-logo-monogram.svg` 與 `public/favicon.svg` 原樣取自公司 `xk2022/xplore-kaleidoscope` commit `ae9bce5a95d1565d76e76a0414fde7d29a1ed881` 的 `assets/brand/logo/svg/xk-logo-monogram.svg`、`assets/brand/logo/favicon/icon.svg`。字體沿用公司備援順序，不向第三方載入字體。

「訂單工作台」只向 ADMIN／DISPATCHER 顯示。`tomApi.ts` 以原 `OK`／`traceId`／`fieldErrors` 契約整合五個端點，共用 UPMS 的 Session 與 CSRF，不能使用其他模組的 `0000` transport 解析 TOM。列表只傳 page／size／sort／type／customerId；超過 JavaScript safe integer 的頁數與總數以精確十進位字串保留，分頁計算使用 BigInt。

編輯先取得完整訂單，保留所有 nullable、空字串／空物件及穩定 clientRef；重量與材積維持字串，含 offset／微秒時間及原文字不經 trim、NFC 或 Date 往返。保存使用 expectedVersion 與顯式重送 key；不確定結果保留原請求，使用者確認重試時仍傳同 key／body。版本衝突保留本機內容，明確重新載入才捨棄；不自動重送或覆寫。離開未完成編輯會提示，表單與重試資訊不存 browser storage。只讀完整性顯示已保存版本的缺漏，沒有正式受理或派工。

UI 行為與實測：[工作台規格](../docs/07_ui-ux/order-workspace.md)、[M2-05 驗證](../docs/09_testing/m2-05-validation.md)。
