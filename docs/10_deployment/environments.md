# 環境策略｜Draft

按[公司 APP 部署規範](https://github.com/xk2022/xplore-kaleidoscope/blob/main/doc/04_engineering/app-deployment-standard.md)：

| 環境 | 前端 | 後端／DB |
| --- | --- | --- |
| DEV | Vite | Spring Boot、本機 PostgreSQL 容器 |
| DEMO | Vercel | Railway／獨立 PostgreSQL |
| STG | Vercel 或獨立測試容器 | Railway 或測試容器／獨立 DB |
| PRD | VPS Docker | VPS Docker／獨立資料卷或受管 DB |

同源 Session+CSRF 需驗證前後端分平台路由，不能以跨站 Cookie 假定可用。展示僅合成資料、環境 Secrets 分離；PRD 需 HTTPS、備份還原、人工核准。未確認供應商帳號、照片儲存、成本、網域與部署，沒有已開通網址或採購承諾。
