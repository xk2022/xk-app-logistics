# LG-M4-03 異常與補件驗證

狀態：交付完成，PR #25及main四項CI均已通過。前包[PR #24](https://github.com/xk2022/xk-app-logistics/pull/24)／merge `4681f13b4dd1dc324632848e7b119147e0e43068` 的PR及main CI均attempt1四過後才開本包。公司核對 `9213a3d8dacb8774d87c26cb736c4a4315779e95`；[工程採用](../98_decisions/2026-11-26-m4-03-engineering.md)先凍結新契約。沒有把source review或靜態discovery當runtime通過。

| 驗收 | 實際狀態 |
| --- | --- |
| Frontend lint／typecheck／test／build | lint／typecheck／718 tests（30 files）／build 83 modules全過；JS560.65kB／gzip157.17kB有bundle advisory |
| Backend clean verify／PostgreSQL | 完整 clean verify 146 unit＋384 integration＝530，全過0 failure／error／skip，11:41；213backend／169main輸入保持凍結 |
| 八份API契約 | 八份靜態及1,949筆實際HTTP／58檔全過；新13ops／46schemas、65正／498反／8引用防護／6來源hash及17CLI拒絕通過 |
| V11→V12／40表空庫／重啟／映像同源 | Platform13案例全過；真實PG17.12空庫40表／12版本／零業務資料，重啟一致；268正式class集合與bytes、82前端輸入、12migration bytes比對相同；JAR無test support／fixture |
| 真TLS desktop／Pixel7 E2E | 34／14files全過，2 workers／0 retries，3.0m；包含新增桌面與手機異常管理實際操作 |
| npm／SBOM／OSV／gitleaks／隱私／工具 | root／frontend npm各0；SBOM1.6／144元件；OSV409套件無issue；100憑證報告隱私全過；staged gitleaks 0 findings／無排除；commit history於交付PR及CI另核對 |
| source／PR／merge／PR及main四項CI | PR #25 CI45787301633／main CI45788390226 attempt1均四項成功；不以前包或本機結果替代 |

## 邊界

D04／D05、D06／D10、正式provider／派工資格／安全釋放及結案責任仍未核准；普通新策略UNCONFIRMED，正向只明確隔離合成。處置不代表安全釋放；未做M4-04～06、M5或正式部署。完整司機操作UI留M4-06，本包只管理UI與driver API驗收。

## 首輪重點驗證與修正

首輪2分43秒，15 unit全過；108 integration中57過、51個ExceptionIT因同一ApplicationContext啟動問題報error、0 assertion failure／skip。原因為新測試@Bean exceptionPolicy與普通預設bean同名，Spring正確拒絕覆寫。僅將該測試factory命名改為唯一名稱，保留Primary與全部51案例／斷言；不啟用全域bean override，不修改普通政策。原Evidence42、Platform13及新的普通DefaultPolicy2均已過。修正後必須重跑重點及全量，不能把首輪當成功。


第二輪重點驗證15 unit＋108 integration全過，0 failure／error／skip，4分18秒；確認 Bean 命名修正未變更169份正式主程式輸入。

前端首輪 lint／typecheck通過，718案例中717過、1個比較流程測試失敗，未繼續build。原因為衝突提示先於上層pending guard解除，測試過早點到disabled的重新讀取按鈕，未發出fresh GET。僅調整測試等待按鈕可操作並確認第二次GET，保留原文、明確採用及不自動重送全部斷言，未加timeout或修改產品程式；修正後118個新增測試、typecheck及ESLint通過，完整四項重新執行均通過，718 tests／30 files，0失敗。


完整 Maven 驗證已通過。測試 fork 在全部案例結束、System.exit(0) 後遇30秒退出等待並由 Surefire 結束；Maven 最終 BUILD SUCCESS／exit0，30個JUnit suite合計530案例全過、無skip。這不是正式環境部署或營運核准，隔離服務重啟／健康仍另外實測。


## 真實 HTTP 與操作證據

| 契約 | 實際回應 | 檔數 |
| --- | ---: | ---: |
| 原訂單 | 458 | 18 |
| 正式受理 | 52 | 10 |
| 車輛 | 178 | 4 |
| 司機 | 292 | 4 |
| 派工 | 409 | 10 |
| 本人詳情／出發 | 178 | 6 |
| 進度／照片 | 216 | 4 |
| 異常／補件 | 166 | 2 |
| 合計 | 1,949 | 58 |

34案例涵蓋原32個M1～M4-02回歸及桌面／Pixel7管理異常流程：保留原文與動作當時名稱、明確要求補件／上傳／提交／處置、實際私有PNG bytes／digest／解碼／cache、合法競爭、dirty導覽、真版本衝突比較／採用、成功但遺失回應後實際TLS重送、第一次CSRF拒絕與同key再試、角色／綁定／本人隔離／授權失效清除。原五項訂單API與所有舊schema未放寬。

100個私有fixture憑證在真TLS／映像驗證後才產生，報告隱私檢查全過後保存58份原始body-only JSON及64張screenshots，再清除本包容器／資料卷／env／overlay／fixture；一般DEV保留。新照片僅在記憶體驗bytes，不保存原始PNG／headers／Cookie／CSRF／key或trace。新8張管理截圖皆先關閉私有預覽，檢查無blob圖片、無水平溢出與輸入標籤；root已檢視實際desktop及mobile畫面。

本機證據位於工作區 `/workspace/artifacts/lg-m4-03-evidence/`，含JUnit30 suites、backend／frontend／E2E／HTTP／隱私／初始化與runtime摘要、source hash、SBOM及掃描；交付圖為`exception-desktop.png`及`exception-mobile.png`。CI上傳相同來源的Playwright／HTTP與Maven artifacts，不把本機截圖當遠端CI結果。

## 交付門檻與剩餘風險

受測分支`feat/lg-m4-03-exceptions`，source／PR／merge與四項PR及main CI由[唯一head收據](https://github.com/xk2022/xk-app-logistics/pulls?q=is%3Apr+head%3Afeat%2Flg-m4-03-exceptions+base%3Amain)追蹤；不以前包或本機結果替代遠端CI結果。main四項成功才開始M4-04。

正式D04／D05、D06／D10、provider、派工資格、安全解除與結案責任均未核准，預設拒絕保持；test-only正向不是營運核准。沿用UPMS單instance限制，尚無正式儲存／保存清理。前端bundle560.65kB／gzip157.17kB超過500kB advisory，build成功。無M4-04～06、M5或正式部署。


最終staged秘密掃描首輪4筆generic-api-key提示，逐一證明皆為新契約／validator內指向M401及M402公開OpenAPI檔的SHA-256 integrity pins，並非憑證。僅把新YAML的兩個值明確標str、Python的兩個完整literal加括號；root獨立比較整棵safe_load tree及整份Python AST完全相等、6個digest仍對應實際檔案，沒有切割雜湊、忽略／排除／suppression或放寬檢查。新validator65正／498反／8ref／6hash／17CLI與166筆實際異常回應重驗通過；正式產品來源／全部舊契約與migration未變，因此不無故重跑backend／frontend。

公開pin等價修正後，完整staged diff秘密掃描已0 findings，沒有排除；八份實際HTTP共1,949／58檔重新驗證通過。最終提交前再核對文件、凍結來源及staged掃描。


最終交付：[PR #25](https://github.com/xk2022/xk-app-logistics/pull/25)，source`508e1e41174623e39c25352d1ae7d77df3692c99`／merge`e55ee4ecdf98d6f314b60e45c96b0b18d9e5489e`；[PR CI45787301633](https://github.com/xk2022/xk-app-logistics/actions/runs/45787301633)及[main CI45788390226](https://github.com/xk2022/xk-app-logistics/actions/runs/45788390226) attempt1各四項成功，source／merge樹相同。遠端各718／530／34全過；八HTTP PR1,949（異常166）、main1,947（異常164），各58檔且schema／隱私通過。受控遺失回應與競爭使收集數可不同，逐輪保留實際數。確認main後才開M4-04。最終本機staged及36commits全歷史gitleaks均0 findings／無排除。
