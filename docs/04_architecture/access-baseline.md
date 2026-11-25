# 角色與認證基準

Q02 已採用方向；既有 ACC-P01/P02 已核准。

| 功能 | ADMIN | DISPATCHER | DRIVER |
| --- | --- | --- | --- |
| 五項草稿 API | 本公司、受守衛限制 | 本公司、受守衛限制 | 拒絕 |
| 本人有效派工摘要 | 後續按責任規格 | 後續按責任規格 | 後續僅本人 |
| 受理、派工、照片與結案 | 待分片授權 | 待分片授權 | 待分片授權 |

同源 Session + CSRF；後端強制授權與資料範圍。採受控首次管理員初始化、人工復原、安全密碼保存及隔離測試帳號方向；不預置正式帳密，不把測試 fixture 暴露在正式環境。Q06–Q10 已採用，精確核准範圍見 [M2 帳號與權限決策](../98_decisions/2026-11-25-m2-access.md)。工程細化提案見[歷史 START-P01～P04](https://github.com/xk2022/codex-xk-logistic/blob/7092510c7e63a3f5781e1f0a24787a32e3248ac4/docs/FIRST_SLICE_STARTUP_PLAN.md)。尚未實作或執行安全驗收；未列明的工程參數及業務例外另行確認。

首片 15 個操作×角色對照見 [JSON](../06_api/order-draft-access-policy.json)，皆 not_run。
