# M4-03 異常與補件｜工程採用

狀態：Approved（工程選擇），日期：2026-11-26。依[Q41–Q47](2026-11-26-m4-execution.md)及完整M4授權。D04／D05現場例外、D06照片規則、D10保存／清理、正式provider與安全釋放／結案責任仍未核准；本包沒有新的正式業務豁免。

## 前置與契約

[M4-02 PR #24](https://github.com/xk2022/xk-app-logistics/pull/24) source `b9647dfe4430e8d088362331c92d31de405c44a5`／merge `4681f13b4dd1dc324632848e7b119147e0e43068`，PR CI45775507970／main CI45776966300 attempt1均四項成功後，從最新main建立 `feat/lg-m4-03-exceptions`。公司最新核對 `9213a3d8dacb8774d87c26cb736c4a4315779e95`，重讀AGENTS、README、docs索引及APP工程／API／測試／安全規範。

新增[獨立十三項API](../06_api/tms-exceptions.md)／[OpenAPI](../06_api/tms-exceptions-openapi.yaml)及E19；保留所有舊API、回應形狀、原工具與V1–V11 bytes。原M4-02進度不暗增異常錯誤或暫停規則；未解異常由本包窄查詢供後續完成／結案重新檢核。完整DRIVER操作UI留M4-06，本包交付ADMIN／DISPATCHER管理UI。

## 異常獨立版本與原始事實

只有出發後DEPARTED／IN_PROGRESS／AWAITING_COMPLETION可新建回報；reportedPhase enum精確三值，current execution仍沿原五值。回報建立OPEN/version0，補件要求 OPEN→NEEDS_SUPPLEMENT、每個新上傳 NEEDS→NEEDS、明確提交 NEEDS→OPEN、管理處置 OPEN→RESOLVED，各恰加異常版本1。上傳不等於提交，已處置不代表已安全釋放或結案；不改execution／assignment／generation／TOM／FMS／occupations。

blocking由status!=RESOLVED導出，不接受caller waiver、force、blocking=false、釋放或跳關旗標。原description／phase／executionVersion／server time／reporter/source不改，處置reason另存resolutionNote。所有原始有效Unicode scalar文字（NUL／NFD／首尾空白／換行）保存，僅驗nonblank（Java whitespace／SpaceChar／FEFF）及description1..2000、reason／note1..1000 code points；不trim／NFC。UTF8 bytea與json snapshots保原值，React以純文字轉義呈現。

ActiveRequest精確五欄{id,reason,requestedAt,uploadedCount,evidenceIds}，canonical唯一排序IDs最多32，count等於陣列長度；提供重載後選取本request照片的有界介面。32是工程上限而非D06張數核准。每個新request須新上傳並明確選照提交，不把舊照片重標成新補件；舊照片、要求及原收據皆不可覆寫。合法同階段補件若同時符合原M4-02規則，不另擅訂purpose排除規則。

## 策略、角色與歷史

ExceptionPolicy普通啟動UNCONFIRMED，沒有ENV／ADM／HTTP／profile核准開關或分類／理由豁免。正向test-only factory各自實查current_database=xk_logistics_e2e，並驗同scope合成driver／task，production JAR無此替身。合成閉環要求至少一次明確提交且有1張新驗證PNG才可處置；65536bytes／4096pixels等只是合成工程值，非正式D04／D05／D06政策。provider仍獨立檢核，未知requirements不是空清單豁免。

DRIVER所有讀寫／重播先重驗正常帳號、原HttpSession、目前綁定、同次有效指派、受理來源版本及完整occupations。管理歷史／detail／metadata則先驗正常ADMIN／DISPATCHER、原Session及servercompany事實，不依賴driver綁定、occupation或未結案來源；完成／釋放後仍須可讀管理歷史。管理privatebytes另驗目前ExceptionPolicy.managerCanRead及provider，只讀明確關聯該異常的補件，不代理DRIVER端點或公開檔案。

管理新命令在receipt MISS後才重驗current source／occupation；管理成功重播在fresh role／Session／company fact後先回原收據，不因今日status／phase／policy或歷史來源失效隱藏舊成功。DRIVER重播仍須current scope；request只需先驗屬同exception／assignment，不能在ledger前要求它仍active。manager reason清楚標為DRIVER可見；manager history僅多captured actor{role,displayName}（既有UPMS1..80），不暴露account UUID，driver history不含此identity。

## 有界傳輸、交易與競爭

新JSON命令實際InputStream讀16384+1驗超量，新nested raw upload實讀1MiB+1；沒有先無界@RequestBody再檢長度。所有本包新POST只從header取CSRF，避免unsupported form先解析；原M4-02及舊路徑行為保留。DEV nginx只有原照片與新補件兩個精確UUID raw路徑2m，其餘16k。Raw雙header X-Exception-Version／X-Execution-Version，各單一ASCII整數，先驗異常token再執行token；內容PNG／CRC／結構／pixel先於有限解碼，bytes不轉碼、不存公開路徑。

一個upload同caller PG transaction提交blob＋原Evidence10 metadata＋request link＋異常版本／event＋ledger；任一步或deferredFK失敗整筆回滾。submit只驗其request／exception／assignment／driver的VERIFIED新證據及獨立supplement policy，不偷偷加「必等今日execution phase/version」限制。原M4-02 POST仍只兩階段；V12僅增量將既有metadata phase CHECK允AWAITING，舊資料及V11文件不改。

UPMS canonical／fresh Session→排序FMS→TOM→TMS assignment root／完整占用→execution→exception→request／evidence→event／ledger。合法雙向競爭是OPEN且已有fulfilled的REQUEST vs RESOLVE，以及NEEDS且已有有效照片的UPLOAD vs SUBMIT；RESOLVE與晚upload／submit僅驗版本／前態拒絕，不能放寬resolve到NEEDS來製造雙合法競爭。

ExceptionQueries.lockBlockingForAssignment在MANDATORY writable caller transaction內自行鎖同assignment root FOR UPDATE，零case也鎖；report共享該root，防止count0 phantom。返回窄{assignmentId,unresolvedCount}，不暴露repository或未來完成權限，未知／腐敗狀態不能回0。呼叫者維持canonical／source／root鎖順序並持有至commit。

## 原次重試與UI

所有POST200＋Idempotency-Replayed，無Location；JSON數學整數等價、UUID集合lowercase排序且語意重複拒絕；raw文字不正規化。hash不含今日phase／policy／server time／generatedID。fresh authorized scope後先原收據，miss才版本／狀態／策略；雙單位E1901只回actualVersion（異常）或actualExecutionVersion（執行）其一，manager命令只接受異常unit。

同一unknown POST的原body／path／key只有E1901／02／03／07／08明確post-ledger MISS拒絕可結清；E1904／05／06、common4xx／5xx、transport／parse／ack witness失敗均保不確定。GET／照片錯誤不能結清另一pending POST；401／FORBIDDEN清除protected data但不宣稱rollback。CSRF只明確刷新，不背景重送命令。成功收據與current GET分開，下一新操作先讀最新。

管理UI含角色導覽、有界列表／明細／歷史／關聯照片、原文與driver-visible處置原因、確認、dirty／busy／unknown／409與登入失效保護；前端禁用不替代server。照片只在記憶體驗bytes／SHA／decode，objectURL關閉／失效即回收，報告不存原照片／headers／cookie／CSRF／key。桌面／手機截圖於preview關閉後保存，測試仍直接驗private bytes。

V12新增tms_exception、tms_exception_event、tms_exception_command、tms_exception_supplement_request、tms_exception_supplement_evidence、tms_exception_event_evidence六表，最新40；無回填／清理／M4-04實作／M5／正式部署。[驗證紀錄](../09_testing/m4-03-validation.md)另記實際結果，不以本工程採用代替測試。
