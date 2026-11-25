# FMS 車輛模型｜已採用基準

| 欄位 | 意義 |
| --- | --- |
| id | UUID，伺服器產生 |
| plateNo | 唯一車牌，正規化與長度依工程契約確認 |
| vehicleType | 車種識別；車頭／拖板／貨車等分類可表達 |
| capacityTon | 載重，十進位噸數；未知可空，不以 0 代替未知 |
| registrationNo / registrationDate | 車籍資訊，適用必填及格式待確認 |
| inspectionDueDate | 檢驗期限，自然日，可空 |
| administrativeStatus | AVAILABLE／MAINTENANCE／INACTIVE |
| version / audit | 樂觀鎖版本、建立／修改時間及操作者 |

建議表 fms_vehicle；主檔 UUID 穩定，停用保留資料，不提供硬刪除。車牌更正的可操作條件須在實作契約記錄，不覆寫未來派工歷史快照。使用中為後續 TMS 派工占用推導值，非人工主檔欄位。本包不建占用、司機或配對表。使用新 Flyway V6，保留 V1–V5。

ADM 若尚無正式車種選項，使用明確受控的分類框架與空狀態；測試合成分類只在隔離環境，不開放未核准的廣泛 ADM 讀取權限。詳細型別、唯一索引及欄位校驗於實作前定稿。
