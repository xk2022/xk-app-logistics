# LG-M3-02 FMS 司機工程決策

日期：2026-11-25。基準為[PR #13](https://github.com/xk2022/xk-app-logistics/pull/13) 合併 `a1d72a0f99d5762f79bed2f85ccf32c4d6446b65` 與[核准Q26–Q30](2026-11-25-fms-driver.md)，公司最新規範固定 `5be640ab11811b24706101ea13db73185e9ce4d8`。這是工作包內工程細化，不宣稱產品、CI或部署已驗證；實際結果由本包驗證紀錄提供。見[模型](../05_database/fms-driver-model.md)、[API](../06_api/fms-drivers.md)與[獨立OpenAPI](../06_api/fms-drivers-openapi.yaml)。

遵循公司[APP開發](https://github.com/xk2022/xplore-kaleidoscope/blob/5be640ab11811b24706101ea13db73185e9ce4d8/doc/04_engineering/app-development-standard.md)、[API與資料](https://github.com/xk2022/xplore-kaleidoscope/blob/5be640ab11811b24706101ea13db73185e9ce4d8/doc/04_engineering/api-and-data.md)、[流程](https://github.com/xk2022/xplore-kaleidoscope/blob/5be640ab11811b24706101ea13db73185e9ce4d8/doc/04_engineering/development-workflow.md)及[測試](https://github.com/xk2022/xplore-kaleidoscope/blob/5be640ab11811b24706101ea13db73185e9ce4d8/doc/04_engineering/app-testing-standard.md)。保留原四份TOM來源、其他M2／車輛契約、V1–V6與既有依賴。不派工、不造occupancy／配對、不用真實資料、不部署。

## 行政狀態不推導派工或帳號權限

employmentStatus工程enum為ACTIVE／INACTIVE／LEFT，dutyStatus為ON_DUTY／OFF_DUTY，建立預設ACTIVE／OFF_DUTY。兩欄獨立記錄，不拒INACTIVE／LEFT＋ON_DUTY，不自動改duty；但行政可用明確只為ACTIVE&&ON_DUTY。options與可信守衛不得追加帳號、證照期限或類別啟用篩選；未綁帳號、停用類別或過期證照仍可能是行政選項，不代表未占用、可派工或法規適格。

ACTIVE↔INACTIVE、兩者可→LEFT；歷史LEFT可建立或維護，但LEFT不能變回ACTIVE／INACTIVE（409 employmentStatus），因復職流程尚未核准。employment不管理UPMS角色、enabled、密碼、session或綁定；正常綁定DRIVER即使INACTIVE／LEFT仍可看本人，權限由UPMS有效actor決定。不得以離職編輯偷偷引入帳號生命週期自動化。

## 最小DTO與固定分類

八端點限本公司：管理七項正常ADMIN／DISPATCHER，本人一項正常DRIVER。獨立OpenAPI固定22欄明細、17欄列表、12欄本人、9欄options與精確前端解析。licenseReference只在管理明細；本人沒有accountId／帳號摘要、version、audit或auth欄。候選僅id／username／displayName／bound，不洩露綁定對象或通用帳號DTO。不以UI隱藏代替server輸出最小化。

licenseType用nullable ADM item UUID，固定DRIVER_LICENSE_TYPE，V7只建空父分類，不擅自seed正式駕照碼。ADMIN仍用原ADM維護；DISPATCHER只透過FMS窄代理讀固定分類啟用選項，不擴ADM HTTP權限。新建／換非空引用驗category-bound父子啟用，未換的歷史停用UUID可維護，null清除。現況metadata不是歷史派工快照。證照格式、正式分類、保存政策、法規限制、工時與復職仍待後續核准。

## 綁定與本人讀取的序列化

accountId是可空一對一關聯，DB非空UNIQUE＋FK之外，必須以UPMS窄公開介面驗目前DRIVER、enabled及usable。有效暫時密碼／mustChangePassword帳號可綁；過期暫時密碼不可。綁定不略過既有強制改密碼屏障，故該帳號完成改密碼前仍不能讀`/me`。同一已存但後來無效的關聯保留並flag false，其他欄位維護／解綁不自動修帳號或撤銷session。

UPMS擁有account repository與候選資格／計數，先按資格篩選再分頁，固定username ASC／id ASC。FMS只為當頁加bound，不在page後篩掉已綁候選扭曲total；UI已綁項不可新選，原保存綁定除外。最終寫入仍驗唯一性，競爭占用409 accountId，不接受client role或外部driverId來擴查候選。

寫入順序canonical→fresh manager actor→PATCH fresh driver/version→必要的新account source guard→必要的ADM category/item。UPMS lifecycle沿同canonical；guard要求MANDATORY writable caller transaction，fresh lock／refresh排除L1舊來源，持鎖到commit。不能跨模組repository或把FK當授權。

`/me`精確matcher在一般FMS matcher前。由目前SessionPrincipal每次重新驗正常DRIVER／authVersion，再由當前accountId找綁定；不接受id／query，不在session快取driverId。採canonical→fresh actor→fresh locked binding driver，使用可鎖的writable transaction但無業務寫入。與unbind／rebind／角色／disable／recovery共用鎖序，使競爭有明確先後；actor撤銷401、無binding404、manager403。這不是持久化Spring SecurityContext或新身分系統，沿既有UPMS每request DB驗證及session撤銷設計。

## canonical、自然日與版本

driverCode用NFC→Unicode strip（isWhitespace／isSpaceChar／FEFF）→ROOT uppercase→NFC，canonical1–40 code points，全狀態唯一且immutable。保留內部空白／連字號，不設國別pattern。name1–200、phone≤50、email≤254、licenseReference≤100，同樣NFC／strip／controls及surrogate拒絕，optional空白轉null。email是描述性聯絡文字，無嚴格ASCII或投遞聲稱；不要求完整證件識別或圖片。input schema用x-normalized bounds，不錯套raw字串長度。

licenseDueDate可空Gregorian0001–9999，只提示過去日期，不猜7／30天或法規到期阻擋。不轉UTC；新欄位局部JDBC4.2 LocalDate，不動既有全域日期映射，真PG以date::text核對1500／0001避免舊GregorianCalendar對稱錯誤。

POST只必填driverCode／name，PATCH為version＋至少一mutable，省略保留、nullable null清除。每個確認PATCH都加一版，含同值與固定clock；updatedAt=max(now,old+1micro)，version0–9007199254740991，上限409。無冪等key契約，500／網路不確定不得自動重送或換版本；保留本機、GET核對，409人工處理不偷偷覆寫。

列表與options固定driverCode ASC／id ASC，q≤100 canonical code points；code查詢另ROOTuppercase／NFC，其他name／phone／email用DB lower雙側字面LIKE，escape %／_／反斜線。不得把q套code40長度，亦不得搜尋licenseReference／帳號資料。未知／重複query與body、trailingJSON／invalidUTF8均拒絕。API沿0000／message／data／timestamp+08，不加入TOM特例traceId／actualVersion；錯誤data沿null或文字map。

## 可信查詢、遷移與驗收

DriverQueries保留server模組界線，findCurrent是immutable current snapshot，排除account／licenseReference／audit；requireAdministrativelyAvailable由caller先授權及canonical，以可寫MANDATORY交易fresh鎖source至commit，只驗ACTIVE&&ON_DUTY，無reservation。不是HTTP權限替代，也不改VehicleQueries。

V7只新增fms_driver及空分類，21表含Flyway；測試須真PG核對V6→V7原19表資料／結構fingerprint、空庫、code／account唯一競爭、partial/null、LEFT／獨立duty、raw舊日期、no-op版次、cached source、帳號與類別停用競爭、self最小化與兩種鎖序的unbind／rebind／revocation。舊後端、前端、20E2E、四TOM來源與vehicle驗收保持。

Driver獨立schema工具只讀新OpenAPI，八operations／28schemas，以同一hash-pinned test-only套件、in-memory refs與禁止外部取回驗靜態fixtures及非空body-only driver-contract-responses.json。Decimal整數判定不讓safe-bound fractional值因float捨入通過；missing／empty evidence必失敗。schema成功不能替代權限、正規化、DB／鎖序或真正HTTP實測，實際結果由root驗證紀錄與CI提供。
