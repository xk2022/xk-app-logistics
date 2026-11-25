# Frontend

React + TypeScript + Vite 的同源工作空間。M1 服務狀態頁保留於登入頁「系統狀態」入口；M2-01 提供 Session 登入、登出、本人狀態、強制改密、三角色工作台及 ADMIN 帳號管理。

```sh
npm ci
npm run dev
npm run lint
npm run typecheck
npm test
npm run build
```

開發服務預設 `http://localhost:5173`，`/api` 代理至 `http://localhost:8084`；容器內以 `API_PROXY_TARGET=http://backend:8084` 覆寫。`API_PROXY_TARGET` 僅由 Vite 開發服務讀取，瀏覽器仍呼叫同源相對路徑。服務請求逾時為 8 秒；離開畫面時中止請求。

精確 Node/npm 版本與依賴見 `package.json`、`package-lock.json`。`/auth/me` 決定登入畫面；已登入 API 遇 401 清除本人及帳管畫面，返回登入。首次登入必須先改密；改密成功撤銷登入狀態並要求重登。

API 位於 `/api/v1/auth/{csrf,login,logout,me,password}` 與 `/api/v1/upms/{roles,accounts}`；帳號編輯使用 `/api/v1/upms/accounts/{id}`。寫入一律帶同源憑證與記憶體中的 `X-CSRF-TOKEN`。登入、登出及改密後重新取得 token；403 更新 token 並顯示錯誤，不自動重送寫入。讀寫逾時為 10 秒，錯誤不顯示原始網路例外。密碼僅留在表單狀態，提交後清空，不寫入 storage、URL 或日誌。

角色固定為 ADMIN、DISPATCHER、DRIVER，一帳號一角色。ADMIN 可建立與編輯帳號，版本衝突提示重新載入。帳號復原僅顯示受控離線流程指引；不存在公開註冊、匿名重設、動態權限或物流業務功能。前端選單僅提供操作導覽，授權由後端強制執行。DEV/CI 的 Secure Session 端到端驗證須使用根目錄文件指定的 HTTPS gateway。

`public/xk-logo-monogram.svg` 與 `public/favicon.svg` 原樣取自公司 `xk2022/xplore-kaleidoscope` commit `ae9bce5a95d1565d76e76a0414fde7d29a1ed881` 的 `assets/brand/logo/svg/xk-logo-monogram.svg`、`assets/brand/logo/favicon/icon.svg`。字體沿用公司備援順序，不向第三方載入字體。
