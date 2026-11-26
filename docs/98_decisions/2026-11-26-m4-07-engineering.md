# LG-M4-07 工程驗收採用

使用者已授權M4全包及Q41–Q47；本文件固定工程測試方式，不核准正式派工資格、照片保存、現場例外或責任政策。基線與順序門檻見[工作包](../08_development/m4-07-integration-handoff.md)。

- 僅新增一個直接V9→V14升級案例，原15個Platform案例與helpers完整保留。原欄位保留、最新full-row冪等與真服務重啟分別證明；legacy CLOSED不補造M4 audit。
- test-only Python stdlib工具整合真HTTPS cohort、47表與owner-neutral結構比對、PG17私有dump／不同DB還原、正常production JAR拒絕及真正Console PTY。無新套件、API、migration或policy。
- 三個global普通fixture帳號與舊130分離；所有業務資料由真HTTPS API建立。來源正向僅既有actual-DB-gated合成工廠，正常不同DB不能載入；不放寬scope或授權。
- 全47比對含完整reservation marker、內部raw JSON／bytea及Flyway；不jsonb解碼NUL、不改原文字元或照片。跨DB結構只比較邏輯定義／enabled／deferrability等，不比較catalog OID與系統生成RI trigger名稱。
- CLI採named one-shot `docker run -it`與stdlib pty／select，真Console guard保留；web JVM持續運行以驗persisted authVersion。自動化不是真人責任驗證。每一密碼先入私有inventory，從不放argv／log／artifact。
- normal auth保持account＋lock每列不變，audit僅明列append；recover／本人改密只允victim的password_hash、must_change_password、temporary_password_expires_at、auth_version、version、updated_at及明列事件。無last_login欄位，不新增；其餘帳號、原audit及全部業務資料維持。
- 舊瀏覽器動態密碼同樣先登錄private inventory才使用，兩worker採有界獨占鎖及0600原子替換；fixture identities、實際DB帳號列數與secret值數分開實測，不混稱133。
- CI必跑drill，diagnostics在最終隱私前，scanner增加artifacts範圍並保全部舊guard；外部owned-state與cleanup-only處理失敗／取消。只移除精確owned資源，不prune、不動普通DEV資料。

[驗證](../09_testing/m4-07-validation.md)只記實跑結果；正式DR、分散式Session、正式provider、保存政策與部署另待核准，未因本工程驗收自動通過。


## 驗收實證後的窄修正

- 還原後29個enum-like CHECK僅SQL反解形式不同。只canonical直接 `= ANY` 的兩種已觀測literal-only unbounded varchar→text ARRAY，保所有值／順序／欄位／operator／其餘SQL及constraint type＋5flags；bounded／未知／表達式／quoted假ARRAY不變。12個stdlib反向單元列必要CI，未排除CHECK、修改SQL或repair。
- legacy task current／history基線在全部cohort完成、H結案及A資源重用後捕捉。正常mode僅依真service優先序變UN並插入單一POLICY_NOT_CONFIRMED；immutablecurrent／history與其餘task欄位及原因完整精確比對。
- 手機實圖顯示操作鈕過窄，依已採用單欄要求，僅≤680px且driver-execution-workspace操作鈕全寬／至少44px。舊FMS共用樣式、桌面、其他143frontend輸入不變；加五鈕實際bbox驗證，剝除新增helper＋call可還原原spec，全部40舊流程保留。
- 最終150secret patterns為133fixture＋12browser＋4Console／改密＋1DB，與source135帳號列及另一init DB單帳號分開。每值先登錄、原始報告guard全過才匯出；不以redaction代安全門檻。

以上是測試比對、既有手機規格及隱私覆蓋修正，不核准新業務例外。最終新空庫完整重驗結果見本包驗證。
