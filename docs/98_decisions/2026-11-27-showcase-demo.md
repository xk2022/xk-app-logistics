# Showcase Demo 先行｜採用與範圍

2026-11-27，使用者於核准 Q48–Q54／M5 七包後，補充「先不建立 Render 資源」及「暫時只完成showcase demo部署」。本次交付因此限定既有 XK Showcase Hub 的物流合成互動展示；完整 LG-M5-01～07 暫緩，不以此展示冒稱七包、正式部署或 MVP v1.0.0 已完成。

前置規格 PR #31 source `c03e8cc404cb6ae0aeff26359596bdf126164761`／merge `14697495e9bded2661d6457dc845cb3cff56d68d`；PR CI45918607908 attempt1、精確 main CI45919744208 attempt1 四項成功後開始。公司最新工程／部署／測試／維運規範核對 `f1be01c0c5d8bc5b1238f250838e336bef47b149`。

- 使用既有 `xk2022/xk-website-showcase`／GitHub Pages `/logistics/`，不建立 Vercel、Render、Neon 資源，不購買方案或改費用設定。GitHub Pages 允許個人／組織專案展示；本次不提供商業 SaaS、付款或真實資料處理。平台額度與既有帳單仍由帳戶管理者負責，未宣稱總帳單為零。
- 展示源碼留於物流 Repo，採獨立 `frontend/showcase/` 入口、`build:showcase` 與 `dist-showcase/`。正常產品入口、API client、後端、Migration、正式拒絕策略與舊回歸保留。重用純視覺元件與樣式，不掛載有正式 auth／API effects 的 App，也不攔截正式 API 偽造成功。
- 三個角色是公開合成 persona 預覽，不是真實登入或權限隔離服務。訂單、派工、進度、內建合成照片、補件、完成、確認及結案均為單分頁記憶體模擬；無憑證、API、檔案上傳、付款、瀏覽器持久化或 service worker。刷新／明確重置恢復種子，重置不得清除 Hub 其他展示的儲存資料。
- CSP 使用 `connect-src 'none'`，資源限本展示；禁止表單傳送與物件。固定 noindex／nofollow 與 canonical。這些是展示隔離，不核准正式人車資格、照片提供者／保存、現場例外或安全及管理責任政策。
- 以實際 Rollup module provenance、正常產品建置 byte 比對、模型／元件測試及桌面／手機真瀏覽器驗證隔離與閉環。不用 HTTP 回應替身把展示當真後端；原真後端 E2E 另外保留。
- 來源與 Hub 各自 PR：來源完整品質檢查／合併／精確 main 四過後，Hub 才登錄不可變 source SHA。Hub 保留整個最新 push workflow 的成功門檻、無憑證建置、全站回歸、發布 freshness guard 及公開 manifest 比對。

操作說明見[展示手冊](../07_ui-ux/showcase-demo.md)，實際結果與交付收據見[驗收紀錄](../09_testing/showcase-demo-validation.md)。GitHub Pages 可能保留安全訪問紀錄；不以「展示不保存業務資料」宣稱平台完全不記錄資訊。
