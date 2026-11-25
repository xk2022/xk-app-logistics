# 環境策略｜公司 APP 規範 v1.1 對齊（尚未部署）

2026-11-25 核對公司最新 commit `5be640ab11811b24706101ea13db73185e9ce4d8` 的 [APP 部署規範](https://github.com/xk2022/xplore-kaleidoscope/blob/5be640ab11811b24706101ea13db73185e9ce4d8/doc/04_engineering/app-deployment-standard.md)。此更新僅同步已核准的公司平台方向，不建立任何外部資源，也不是專案部署驗收。

| 環境 | 前端 | 後端／DB |
| --- | --- | --- |
| DEV | Vite、本機同源 TLS gateway | Spring Boot、本機 PostgreSQL 容器 |
| DEMO（個人非商業技術驗證） | Vercel Hobby | Render Free Web Service／Neon Free |
| STG（客戶驗收） | 符合商業用途條款的平台或獨立容器 | 合規付費或獨立測試容器／獨立 DB |
| PRD | VPS Docker | VPS Docker／獨立資料卷或受管 DB |

Railway 不再是公司 DEMO 預設方案。免費 DEMO 以 NT$0/月為目標，僅使用合成資料及免費額度，不自動升級或建立付費資源；Vercel Hobby 不用於商業招攬、客戶驗收或正式營運。Render 可能休眠且本機檔案不持久，Neon 有運算／儲存額度；部署前另查官方最新條款，不能以定時流量規避休眠。

各環境資料庫及 Secrets 分離。登入產品必須另驗同源 Session／CSRF、HTTPS、Cookie、可信代理與持久化；不能以跨站 Cookie 或免費服務可用性作假設。正式環境還需最小權限、備份還原、人工責任及核准。實際供應商帳號、費用、照片儲存、網域、部署與維運均未在 CRM 工作包執行，沒有已開通網址或採購承諾。

本輪仍僅驗 DEV／隔離 CI；`compose.prod.yaml` 保持禁止啟動的佔位設定。
