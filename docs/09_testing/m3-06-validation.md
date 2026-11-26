# LG-M3-06 M3 整合驗證

狀態：工程整合與本機全量回歸完成；最終 source／PR／merge／四項 PR 及 main CI 以[本包唯一 head 交付收據](https://github.com/xk2022/xk-app-logistics/pulls?q=is%3Apr+head%3Atest%2Flg-m3-06-integration+base%3Amain)為準，未以歷史通過替代本包執行。

## 開工與範圍

前置 [PR #20](https://github.com/xk2022/xk-app-logistics/pull/20) 最終 source `997950eb1ee80982bc455e29e8a8d7ecb52cdcbd`；[PR CI 45751323496](https://github.com/xk2022/xk-app-logistics/actions/runs/45751323496) attempt 2 四項成功後，合併至 `7acbd43f5e098a877af6a1458e5b32d81e390f41`。[main CI 45752348554](https://github.com/xk2022/xk-app-logistics/actions/runs/45752348554) attempt 1 四項成功、head 與 merge 一致，才由此建立本包分支。

開工核對公司最新 `xk2022/xplore-kaleidoscope@e0229b97acb2bf6c2d83f8155665f68b11c27d71`；相較 M3-05 的治理／檢索工具增量未修改 APP 開發、測試、API、部署、安全或維運核心規範。已讀當前 README、AGENTS、文件索引與本包 handoff。Q01–Q40、舊 API、V1–V9 與依賴均保留。

本包補驗 V8 舊 ACCEPTED 任務經 V9 回填後，實際 HTTPS 查詢、指派與原 key 重播，並於最終版本重跑原 M2／M3 全量回歸。既有交易、併發、回滾、角色隔離與真 UI 案例直接引用並重跑，不寫同義鏡像測試。詳見 [M3 最終報告](m3-integration-acceptance.md)。

## 實際執行

| 項目 | 結果 |
| --- | --- |
| 升級後真 HTTP／測試政策隔離 | 專項 7 unit＋11 integration 全過，0 failure／error／skip；Platform 10（含升級後 HTTPS 指派／重播）、正常政策 DefaultPolicy 1、合成政策單元 7，54.342 秒 |
| Frontend lint／typecheck／test／build | 本包重跑全過；26 files／590 tests，73 modules；202 產品／建置輸入保持凍結位元組 |
| 全量 Maven verify／PostgreSQL | 本包 124 unit＋245 integration＝369 全過，0 failure／error／skip，5 分 41 秒；162 後端來源 hash 前後一致 |
| 空庫／重啟／正常 JAR | 新 JAR 九份 migration bytes 完全相同，含 PropertiesLauncher、沒有測試政策／launcher／fixture／support classes；PG 17.12 隔離 V1–V9／27 表空庫與重啟一致；Flyway 計九份成功具版本 migration，排除 SCHEMA marker |
| 真 HTTPS 桌面／手機 E2E | 28／28、11 files、2 workers、0 retries、2.2 分鐘；10 張原始 PNG，檢視桌面目前派工及手機本人任務 |
| 五份實際 HTTP 契約／報告隱私 | TOM 424／12 files、Acceptance 32／4、Vehicle 174／2、Driver 282／2、TMS 332／4，合計 1,244 筆／24 files；70 憑證 fixture 清除前報告隱私通過，無 trace |
| 依賴與秘密掃描／工具／文件 | root／frontend npm audit 0；CycloneDX 1.6／144 components；OSV all-vulns 409 套件無已知弱點；五契約靜態檢查、actionlint、E2E strict TypeScript、diff 通過；80 份 Markdown／454 個相對檔案連結通過；gitleaks 實際結果見交付收據 |
| 本包 PR／merge／main CI | 依[唯一 head 交付 PR 收據](https://github.com/xk2022/xk-app-logistics/pulls?q=is%3Apr+head%3Atest%2Flg-m3-06-integration+base%3Amain)記錄完整 SHA／run／attempt／四項結果；四項 PR 成功後才合併，再核對 main head 與四項成功 |

## 執行與隔離證據

Frontend 依序執行 lint、typecheck、Vitest 全量與 build；backend 以 Java 21／Maven 3.9.16 `verify --strict-checksums` 執行，所有單元／整合測試均無略過。E2E 指向新的獨立 TLS／PostgreSQL，明確 `--workers=2 --retries=0`；之後五個 validator 逐一驗證本次 `test-results/**/*-contract-responses.json`，隱私檢查於刪除 fixture 前完成。重現命令見 [E2E README](../../tests/e2e/README.md)及[契約 README](../../tests/contracts/README.md)。

202 個產品／建置輸入及 162 個後端來源檔的凍結 hash 在驗收後相同；本包未更改 production 原始碼、依賴、V1–V9 或契約。隔離 frontend 映像內 71 個來源／建置檔（產品 68 個）逐檔相同，正常 DEV 仍以原 JAR 入口、沒有合成政策掛載，原業務表零資料保留。隔離容器、資料卷及三個私密 fixture／env／overlay 在證據保存與隱私通過後清除。

## 邊界

正向派工僅使用實查 DB 名稱為 `xk_logistics_e2e` 的 test-only 合成政策；普通服務維持 UNCONFIRMED 拒絕新派工／改派。沒有新增核准旗標、正式資料、M4 或部署。完整 M3 工程驗收不代表正式營運／法規適格或運輸完成。
