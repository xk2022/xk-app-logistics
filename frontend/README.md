# Frontend

React + TypeScript + Vite 的 M1 殼層，僅呼叫同源 `/api/v1/status`。

```sh
npm ci
npm run dev
npm run lint
npm run typecheck
npm test
npm run build
```

開發服務預設 `http://localhost:5173`，`/api` 代理至 `http://localhost:8084`；容器內以 `API_PROXY_TARGET=http://backend:8084` 覆寫。`API_PROXY_TARGET` 僅由 Vite 開發服務讀取，瀏覽器仍呼叫同源相對路徑。服務請求逾時為 8 秒；離開畫面時中止請求。

精確 Node/npm 版本與依賴見 `package.json`、`package-lock.json`。所有狀態均提供文字回饋與重試操作；目前沒有登入或物流業務功能。

`public/xk-logo-monogram.svg` 與 `public/favicon.svg` 原樣取自公司 `xk2022/xplore-kaleidoscope` commit `ae9bce5a95d1565d76e76a0414fde7d29a1ed881` 的 `assets/brand/logo/svg/xk-logo-monogram.svg`、`assets/brand/logo/favicon/icon.svg`。字體沿用公司備援順序，不向第三方載入字體。
