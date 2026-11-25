# 舊系統盤點與接續｜Draft

| 來源 | 查核及策略 |
| --- | --- |
| [xk-truck-platform](https://github.com/xk2022/xk-truck-platform/tree/7b115c1f20bf723b3931f0547a34032e52649931) | Java 21／Boot 3.5.7／MySQL 8；README 稱 UPMS、ADM、FMS、OrderCore、TOM 基礎存在。派工及訂單仍進行中。保留業務語意，重新實作；不以 README 當測試證據。 |
| [codex-xk-logistic](https://github.com/xk2022/codex-xk-logistic/blob/7092510c7e63a3f5781e1f0a24787a32e3248ac4/docs/README.md) | 2026-11-17 規格、8 份 ADR、首片 OpenAPI、欄位與角色契約；產品尚未實作。優先承接已採用規格，未決事項保留。 |
| [xk-corp-truck-transport](https://github.com/xk2022/xk-corp-truck-transport) | 企業形象網站，獨立維護、不合併進 APP。 |
| [xk-showcase-taiyang-logistics](https://github.com/xk2022/xk-showcase-taiyang-logistics) | 展示案例，獨立保留。 |
| [xk-lp-longtong-logistics](https://github.com/xk2022/xk-lp-longtong-logistics) | 一頁式網站，獨立保留。 |

舊平台原碼靜態核對（2026-11-17 歷史證據）：[45 個來源檔及報告](https://github.com/xk2022/codex-xk-logistic/blob/7092510c7e63a3f5781e1f0a24787a32e3248ac4/docs/SOURCE_CODE_AUDIT_20261117.md)，[功能對照](https://github.com/xk2022/codex-xk-logistic/blob/7092510c7e63a3f5781e1f0a24787a32e3248ac4/docs/TRUCK_PLATFORM_TO_LOGISTICS_MATRIX.md)。本次僅讀取文件、未重跑源碼或驗證本機／DB。
  
既有決策 M01：[ADR-0007](https://github.com/xk2022/codex-xk-logistic/blob/7092510c7e63a3f5781e1f0a24787a32e3248ac4/docs/adr/ADR-0007.md)。不移轉所詢問舊客戶、訂單及照片；不刪除舊資料、不停用舊庫。其他導入需另議。新庫後續作正式產品，舊庫保留作歷史來源，不複製商業模板或實際客戶資料。
