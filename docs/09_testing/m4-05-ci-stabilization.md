# LG-M4-05｜CI 穩定化補正

主工作包 [PR #27](https://github.com/xk2022/xk-app-logistics/pull/27)，source `7625d2817db6bbff036f7982c36a34705ca4cb00`／merge `28b26cd4068cfda642cb6ae4cf565884891491c0`，PR CI45816721104 attempt1四項全過。精確merge的main CI45818321672 attempt1完成失敗：前端882過／1失敗（共883），Compose在E2E之前因Maven Central429失敗；Backend與安全檢查成功。原main失敗保留，不標為成功。

舊Vehicle.test新建分支在重複車牌錯誤後立即斷言focus，未等VehicleForm的passive useEffect。findByText只等待錯誤文字，不能保證focus effect已完成。只把原toHaveFocus包入await waitFor，仍驗證實際焦點。原30案例、兩分支、enabled／aria-invalid／accessible description／saveEnabled／無版本衝突／明確改牌斷言全部保留。沒有改產品、API、timeout、sleep、人工focus或案例重試。

Compose的dependency前綴解析遇HTTP429；fixture與瀏覽器未開始，不記為38個瀏覽器案例失敗。CI及E2E啟動文件改用完整座標`org.apache.maven.plugins:maven-dependency-plugin:3.10.0:build-classpath`，3.10.0即先前成功步驟實際解析版本。固定座標避免重新掃各plugin group動態metadata，沒有改pom或產品相依。相同Java21／Maven3.9.16本機指令成功，生成classpath與原版本byte-identical；下載錯誤仍會正常使工作失敗，沒有略過檢查。

補正分支`fix/lg-m4-05-ci-stabilization`，source／PR／merge及精確main四項CI由[補正交付收據](https://github.com/xk2022/xk-app-logistics/pulls?q=is%3Apr+head%3Afix%2Flg-m4-05-ci-stabilization+base%3Amain)核對。本機Vehicle30／ESLint與完整lint／typecheck／883 tests（34files）／build全過，產出仍為BPgE5AH9.js／615.40kB。125其他前端輸入、263 backend／203 main、38 E2E及Nginx均不變；HTTP-E2E輸入只改README，系統輸入只改CI插件指令。原612 Maven與38真TLS本機功能驗收對應相同產品bytes，補正PR及main仍完整執行全部四CI。

完整座標驗證、actionlint、相對文件連結及staged／commit-history機密掃描為補正門檻；本機記錄位於`/workspace/artifacts/lg-m4-05-evidence/ci-stabilization/`。最終main四項成功前不進M4-06。既有[M4-05完整驗證](m4-05-validation.md)及隔離合成策略、正常未核准政策拒絕保持，不做M5或正式部署。

## 最終遠端收據

補正 [PR #28](https://github.com/xk2022/xk-app-logistics/pull/28) source `7324252264c2de39f1facc61fa77fd3a1e95d496`，merge `a879f93a7bf6838dc46d2fb8d5fde554f7bd5ae7`；PR CI45821675514及精確main CI45823239922均attempt1四項成功，source／merge tree相同。兩者883前端／612後端（161unit＋451IT）／38E2E全過，HTTP PR2,686／main2,687，各96檔，報告隱私通過。main後端07:19，0failure／error／skip。原main45818321672失敗保留，最終門檻由補正main完成，隨後才從a879f93開始M4-06。完整literal收據與原失敗保留於PR #27／#28及本機delivery.json，不將首次merge冒充main全過。
