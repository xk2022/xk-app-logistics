# LG-M4-06｜司機執行工作台

基礎為 M4-05 最終補正 merge `a879f93a7bf6838dc46d2fb8d5fde554f7bd5ae7`。主 PR #27／補正 PR #28，補正 PR CI45821675514及精確 main CI45823239922 attempt1四項成功後開始本包；首輪 main45818321672 的焦點時序與 pre-E2E Maven429失敗保留。[前包補正](../09_testing/m4-05-ci-stabilization.md)。公司規範核對 `f1be01c0c5d8bc5b1238f250838e336bef47b149`，新品牌母稿決議明示現產品暫留既有資產，工程規範不變。

依 [M4 交接](m4-execution-handoff.md)、[既有 API](../06_api/tms-execution.md)、[進度照片](../06_api/tms-progress-evidence.md)、[異常補件](../06_api/tms-exceptions.md)、[完成](../06_api/tms-completion.md)及[UI](../07_ui-ux/driver-execution-workspace.md)實作。只消費既有 API，不新增端點、schema、migration、正式政策或儲存服務；V14／47表與全部舊契約維持。

DRIVER從「本人運輸任務」進入原14欄指派記錄，再明確開啟本人16欄窄詳情；提供出發、普通PNG上傳、進度、異常、補件PNG上傳、明確補件提交及完成七種POST。EXEC與EXCEPTION版本各自呈現。補件上傳不等於提交，提交不等於異常解除，完成不等於整單結案。完成後只讀既有完成窄資料，停止不再授權的照片／進度／異常查詢；不可藉管理API或整單查詢補資料。

命令先檢視確認；凍結原次body／bytes／path／key／版本，只允許明確原次重試。成功收據與目前GET分開，GET不能替未知POST定案。來源經逐操作稽核的同一POST、最小錯誤形狀才可證明原次未提交；CSRF、GET、另一POST、key重用／in-progress／protected relation、網路與格式錯誤都不能。保留raw NUL／NFD／空白／換行、decimal字串、原時間offset與微秒、null差異，不自動trim或改token。

照片保留原始PNG bytes，只在記憶體驗證並預覽。嚴格核HTTP200、media／size／hash／private no-store及安全headers，不提供公開或下載網址，不轉檔、不移除metadata、不造上傳百分比。關閉、背景、換任務、登出開始或失去授權立即撤URL／舊讀取；晚回bytes不可掛回。hidden或普通登出失敗保留必要草稿／Blob／原次意圖，但移除保護DOM，恢復後須fresh observation與明確重試，不背景送POST。

actor/context scope與可暫停送出gate分開；每次hash／CSRF await後、fetch前明查gate及abort。StrictMode每個effect setup建立新active scope，退休的sender不能復活。真unmount／換actor／authloss／明確捨棄清除capability；404清原文與bytes後僅保留不可重試的未知結果標記，不推斷完成／回滾。App保留同一工作區於logoutPending，防舊logout在CSRF後跨登入送出及晚回覆蓋新Session。

全部前端品質、完整Maven、PostgreSQL／真TLS、桌面及手機E2E、十份HTTP契約與隱私／機密檢查實跑後才記成功。隔離fixture為130帳號、正向只由actual-DB-gated test policy；正式策略仍拒絕。本包獨立PR／main四過後才進M4-07，詳[實測紀錄](../09_testing/m4-06-validation.md)。
