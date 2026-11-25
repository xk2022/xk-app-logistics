# 功能目錄｜Draft

| 模組 | 功能 | 優先 |
| --- | --- | --- |
| UPMS | 帳號、角色、授權 | P0 |
| ADM | 字典、必要參數 | P0 |
| CRM | 客戶及必要主檔 | P0 |
| TOM | 草稿、受理、項目及任務依賴、整單生命週期 | P0 |
| TMS | 分段調度、派工撤改、異常與占用 | P0 |
| FMS | 車輛、司機及資源狀態 | P0 |
| TRACK | 手機回報、照片、進度與行政確認 | P0 |
| FIN / MNT / BI | 計費請款、維保、營運報表 | P1 建議 |
| PORTAL / AI | 客戶入口、智慧輔助 | P2 候選 |

模組代碼及邊界為新庫提案；舊 FMS 內含 Dispatch，拆至 TMS 前需確認交易所有權；OrderCore 高階狀態可作 TOM 內領域模型，非必須獨立服務。詳細差異：[既有對照矩陣](https://github.com/xk2022/codex-xk-logistic/blob/7092510c7e63a3f5781e1f0a24787a32e3248ac4/docs/TRUCK_PLATFORM_TO_LOGISTICS_MATRIX.md)。
