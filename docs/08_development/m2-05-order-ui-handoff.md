# LG-M2-05｜訂單工作台 UI 工程交接

狀態：Draft（執行規格，非測試結果）。日期：2026-11-25。使用者已交辦本工作包；main 基準 `0766f703848719064c3e1a4488ec18d0a970b7ff`，公司規範 `5be640ab11811b24706101ea13db73185e9ce4d8`。

先讀 README、AGENTS、[文件索引](../README.md)、[工作台規格](../07_ui-ux/order-workspace.md)、[UI 工程決策](../98_decisions/2026-11-25-tom-ui-engineering.md)、[TOM API](../06_api/tom.md)及四份固定來源：[OpenAPI](../06_api/openapi.yaml)、[欄位字典](../05_database/first-slice-fields.csv)、[角色矩陣](../06_api/order-draft-access-policy.json)、[合成案例](../06_api/order-draft-contract-examples.json)。原四檔與 V1–V5 不因 UI 工作包改寫。

## 交付範圍

1. 沿既有 React／TypeScript／Vite，為正常 ADMIN／DISPATCHER 加入「訂單工作台」的列表、新增、完整明細與草稿編輯；DRIVER、匿名及強制改密不開放入口，伺服器守衛不變。完整明細 GET 失敗顯示錯誤／重試／返回，不以 summary 或空表單授予編輯。
2. 串原五項 API 與獨立 TOM envelope，列表僅支援契約既有 filters／sorts／page。新增最小 type＋有效客戶可存，零項目／零任務不因完整性缺口被擋。
3. 完整表單涵蓋訂單、船務、多項目、多段任務、同單跨項目前置及 requiredTask 的 null／true，穩定保存 clientRef，移除前處理關聯影響。
4. 從完整 GET 建立 PUT 白名單模型，保留全部可編輯欄位、原文字、null／空物件、日期／offset／微秒、十進位字串及同客戶歷史名稱；不能拿 summary 或畫面局部值覆蓋聚合。
5. 保存使用不可變請求快照及 Idempotency-Key；網路／回應不確定及寫入 500 保留原 key/body，完整區分五種 409。未決後遇 CSRF／前置／VALIDATION_ERROR／ORDER_NOT_FOUND 不解除原操作，僅依工程決策的 post-replay domain 白名單處理；後端順序或 key 保存政策變更須同步覆核。busy／pending／reading 期間不得以 remote comparison 清除未決快照。建立成功維持同 Editor 採用內部 baseline，下一次保存發 PUT，不重掛載清除成功提示。禁止自動覆寫版本、換 key 重送或假成功。
6. 顯示已保存版本的只讀完整性、缺漏定位、pending 及 NOT_GRANTED；dirty／版本不一致時停用舊索引跳轉。可用欄位定位失配時回退父 JSON Pointer，先展開 details，不能修改 draft。COMPLETE 不新增受理、派工或資源占用動作。
7. 桌機與手機可用，具載入／空白／失敗／衝突回饋、標籤、鍵盤焦點及未保存離開提示。只用隔離合成資料；不將草稿、token 或憑證放入 URL、持久瀏覽器儲存或報告。

## 必要驗收

| 層級 | 本包必驗情境；實際執行後另記證據 |
| --- | --- |
| 前端 lint／typecheck／test／build | TOM parser 與既有 envelope 分離；完整欄位往返、null／omit／{}、原 Unicode／NUL、精確 decimal、日期 offset／微秒、stable refs、圖、列表白名單、非同步舊回應與重複提交 |
| 元件與狀態 | 三類草稿、完整 GET 才能編輯與角色／載入錯誤隔離、建立成功不重掛載、保存中與結果不確定／500、同 key/body 明確重試及未決後 CSRF／前置失敗仍保留身份、解除白名單、remote comparison 守衛、各種 409 與二次確認版本處理、fieldErrors 父區塊回退／details 展開且不改 draft、空列表／讀取失敗、已停用歷史客戶、只讀完整性版本及 dirty／舊版不可跳錯索引、NOT_GRANTED |
| 真實 HTTPS E2E | ADMIN／DISPATCHER 合法流程、DRIVER／強制改密隔離；桌面及手機建立／重讀／編輯、至少一次多項目多段與前置、版本衝突與失敗回饋、歷史客戶快照、表單原值不損失 |
| 後端與契約回歸 | Maven verify 保留既有測試；原四來源 hash／schema／圖／15 角色綁定／40 欄及實際 HTTP schema；不為 UI 放寬契約或移除守衛 |
| 交付 | 受測 SHA 與最終 PR/head、四項必要 CI、桌機手機證據及剩餘風險；未跑／失敗分開記錄 |

測試須能識別真正重播未加版、未建第二張單，不能只斷言按鈕文字。保留 UI 修改前的原後端驗收及既有 UPMS／ADM／CRM 流程；失敗的必要檢查不可跳過。測試實際數量、命令、報告與 CI 結果由本包驗證文件記錄，本交接不提前給予通過結論。

本包不改原 API／角色／已採決議，不新增正式資料、已核准字典枚舉、派工、照片、正式受理、財務或部署。D04／D05／D10 的未決政策維持未決。若畫面便利設計會改變欄位語意、資料保存或權限，須先回到已採契約處理，不能以 UI 預設擴權。
