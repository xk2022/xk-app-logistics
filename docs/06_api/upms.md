# LG-M2-01 UPMS API 契約 v1

日期：2026-11-25。新模組契約；[既有 OpenAPI 0.2.0](openapi.yaml)的訂單草稿操作、欄位及授權維持原樣。產品依據為 [Q06–Q10](../98_decisions/2026-11-25-m2-access.md)，安全參數與限制見 [UPMS 安全工程 ADR](../98_decisions/2026-11-25-upms-engineering.md)。本文件不是 API 已通過驗收的聲明。

## 共通規則

- Base path：`/api/v1`。使用同源 HTTPS Session Cookie，無 Bearer token。所有 POST／PATCH 必須附有效 CSRF header，包括登入與登出。
- 請求及回應為 JSON；成功回應為 `code: "0000"`、`message: "成功"`、`data` 及 ISO 8601 `timestamp`（`+08:00`）。失敗也使用相同 envelope，配合 HTTP 狀態判斷。
- 回應不含密碼、密碼 hash、Session ID、復原秘密或內部例外；CSRF token 只由專用端點提供，不寫入日誌或持久瀏覽器儲存。
- 固定單角色 `ADMIN`、`DISPATCHER`、`DRIVER`；不接受多角色聯集。角色限制由後端執行，不依賴前端隱藏選單。
- UUID 以字串表示；`version` 是帳號樂觀鎖版本，不是認證版本。`authVersion` 不對外提供。

成功範例（合成資料）：

```json
{
  "code": "0000",
  "message": "成功",
  "data": {
    "id": "64a14562-4ada-4fa0-96c1-82d179ef303e",
    "username": "demo.dispatcher",
    "displayName": "合成調度員",
    "role": "DISPATCHER",
    "enabled": true,
    "mustChangePassword": false,
    "version": 0
  },
  "timestamp": "2026-11-25T14:00:00+08:00"
}
```

## 端點與權限

| 方法 | 路徑 | 成功 HTTP／data | 可用身分 |
| --- | --- | --- | --- |
| GET | `/auth/csrf` | 200／`Csrf` | 未登入或已登入；包含強制改密狀態 |
| POST | `/auth/login` | 200／`Account` | 具有效憑證且未受強制改密守衛限制；須先取得 CSRF，既有 Session 會被替換 |
| POST | `/auth/logout` | 200／null | 目前 Session，包含強制改密狀態 |
| GET | `/auth/me` | 200／`Account` | 已登入，包含強制改密狀態 |
| POST | `/auth/password` | 200／null | 已登入，包含強制改密狀態 |
| GET | `/upms/roles` | 200／角色字串陣列 | 正常登入 ADMIN |
| GET | `/upms/accounts` | 200／`Account[]` | 正常登入 ADMIN |
| POST | `/upms/accounts` | 201／`Account` | 正常登入 ADMIN |
| PATCH | `/upms/accounts/{uuid}` | 200／`Account` | 正常登入 ADMIN |

這裡的 `Account` 僅為帳號及角色資料，不包含客戶、司機主檔、車輛、訂單或派工資料。未提供公開註冊、公開初始化、匿名復原、帳號刪除、任意權限編輯或角色組合 API。首次管理員與例外帳號復原使用受控離線流程。

## DTO

`Account`：

| 欄位 | 型別 | 意義 |
| --- | --- | --- |
| id | UUID 字串 | 帳號識別 |
| username | 字串 | 登入名稱 |
| displayName | 字串 | 顯示名稱 |
| role | enum 字串 | ADMIN／DISPATCHER／DRIVER 其中一個 |
| enabled | boolean | 是否啟用 |
| mustChangePassword | boolean | 是否限制為強制改密流程 |
| version | 整數 | 更新時須回送的樂觀鎖版本 |

`Csrf`：`{ "token": "<本次由伺服器產生的token>", "headerName": "X-CSRF-TOKEN" }`。使用實際回傳的 headerName 與 token；載入時、登入成功及登出成功後重取。token 會隨 Session／認證生命週期改變，不能硬編碼或沿用舊值。

請求 DTO：

| 端點 | 必要欄位 |
| --- | --- |
| POST `/auth/login` | `username: string`、`password: string` |
| POST `/auth/logout` | 無欄位；仍須有效 CSRF |
| POST `/auth/password` | `currentPassword: string`、`newPassword: string` |
| POST `/upms/accounts` | `username: string`、`displayName: string`、`role: enum`、`password: string` |
| PATCH `/upms/accounts/{uuid}` | `displayName: string`、`role: enum`、`enabled: boolean`、`version: integer` |

帳號建立後初始密碼為限時臨時憑證、`mustChangePassword=true`；建立 API 不回傳該密碼。PATCH 不更名 username、不接受密碼或 authVersion、不重設認證期限；改密與受控復原走各自流程。

## 欄位驗證

- `username`：3–64 個 ASCII 字元，轉為小寫後格式須為 `[a-z0-9][a-z0-9._-]{2,63}`；儲存、登入、查重與限流使用相同小寫識別，不區分 ASCII 大小寫，不接受首尾空白。
- `displayName`：NFC 後 trim，1–80 個 Unicode code points，拒絕 ISO control 字元；只供顯示，不作額外角色或業務資料來源。
- 一般及臨時密碼：NFC 後 15–128 個 Unicode code points；不 trim、不截斷，允許空白與 Unicode，拒絕不成對的 UTF-16 surrogate。不要求大小寫／數字／符號組合。初始化、建立、復原及改密皆檢查有限的本機常見密碼清單，範圍及來源見 ADR。
- `role` 必須是固定三角色其中一個；不存在多角色 DTO。`version` 必須與目前帳號版本一致。
- 不接受停權帳號登入；不允許停用或降級最後一個啟用中的 ADMIN。重複 username、版本衝突及最後 ADMIN 守衛回 HTTP 409。

## 認證流程

1. 前端初載先 GET `/auth/csrf`，再用同源 Cookie 與取得的 CSRF header POST `/auth/login`。
2. 登入成功後 Session ID 輪換，前端重新 GET `/auth/csrf`，使用 Account 決定進入強制改密或該角色基礎工作台。
3. 臨時密碼最長 15 分鐘有效；強制改密 Session 在本模組只允許 csrf、me、password、logout。其他 UPMS 端點回 403；既有公開 health/status 工程端點不因此改為業務 API。
4. 改密必須驗證 currentPassword；成功後撤銷既有認證，要求重新登入。前端清除本人狀態並重取 CSRF，不把舊 Account 當作仍已登入。
5. 登出使用 POST 並驗 CSRF；成功後清除登入畫面狀態並重取 CSRF。

Session 閒置 30 分鐘或登入起 8 小時到期。每帳號最多 3 個並存，第 4 個登入撤銷最早登入的 Session。停權、角色異動、復原、改密會使舊認證版本失效；過期或撤銷後的受保護請求回 401。單 instance 重啟會使所有 Session 登出。

登入失敗限流使用 15 分鐘內 10 次失敗／暫停 15 分鐘的工程預設。不存在帳號、停權、密碼錯誤、臨時憑證到期及受限帳號不以不同外部錯誤揭露存在狀態；不因限流改回可區分的帳號提示。

限流計數綁定伺服器讀取的目前 DB 認證版本；成功登入清除該版本計數，受控復原透過遞增認證版本使舊鎖不再適用，即使 CLI 與 Web 位於不同程序。舊計數待 TTL 過期，不要求跨程序清除記憶體。普通登入、顯示名稱編輯、重取 CSRF 不更改認證版本，呼叫端也不能指定它；因此不能用一般請求切換計數空間繞過限制。若全域有界計數容量已滿，新空間仍採拒絕登入的保護策略。

## 錯誤契約

| HTTP | code | 情境 | data |
| --- | --- | --- | --- |
| 400 | E0400 | 欄位／JSON／UUID 等請求格式不合法 | 欄位明細物件，或 null |
| 401 | E0401 | 未登入、憑證無效、到期或撤銷 | null |
| 403 | E0403 | 角色不足、強制改密守衛、CSRF 缺少或錯誤 | null |
| 404 | E0404 | 已授權管理操作找不到目標帳號 | null |
| 409 | E0409 | 重複 username、樂觀鎖衝突、最後 ADMIN 守衛 | null |
| 500 | E0500 | 未預期的服務錯誤；不揭露內部例外 | null |
| 503 | E0503 | 資料庫等必要服務暫時不可用 | null |

欄位驗證範例：

```json
{
  "code": "E0400",
  "message": "請確認輸入欄位",
  "data": { "displayName": "顯示名稱須為 1–80 字元" },
  "timestamp": "2026-11-25T14:00:00+08:00"
}
```

錯誤訊息為可採取下一步的繁中說明，不回顯使用者密碼、內部帳號查詢結果、例外或堆疊。前端按 HTTP/code 處理載入、失敗、重試及重新登入，不將失敗顯示為成功；未認得的錯誤使用一般失敗提示。

## 驗收與範圍

以真實 PostgreSQL／HTTP 測正常與錯誤情境、後端角色拒絕、樂觀鎖、最後 ADMIN、初始化競爭、Session／CSRF／撤銷及改密；桌面與手機以同源 TLS E2E 驗 Cookie、登入、登出、強制改密及工作台。尚未執行的案例不得標為通過。

此契約不加入 CRM／ADM／TOM 欄位、不更動原訂單五項 API、不授權物流業務或正式部署；正式帳號復原責任、分散式限流、憑證及維運仍需後續驗證。
