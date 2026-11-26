import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, getMe, logout, roleLabels } from './api'
import type { Account } from './api'
import { ErrorNotice, LoginForm, PasswordForm } from './AuthForms'
import AccountManager from './AccountManager'
import AdmManager from './AdmManager'
import CustomerManager from './CustomerManager'
import OrderManager from './OrderManager'
import VehicleManager from './VehicleManager'
import DriverManager from './DriverManager'
import DriverSelf from './DriverSelf'
import DispatchManager from './DispatchManager'
import ExceptionManager from './ExceptionManager'
import ClosureWorkspace from './ClosureWorkspace'
import MyTasks from './MyTasks'
import SystemOverview, { ServiceHealth } from './SystemOverview'

type Session = { kind: 'loading' } | { kind: 'anonymous' } | { kind: 'authenticated'; account: Account } | { kind: 'error'; error: unknown }
type Page = 'workspace' | 'accounts' | 'password' | 'adm' | 'crm' | 'tom' | 'fms' | 'drivers' | 'driver-self' | 'tms' | 'exceptions' | 'closure' | 'my-tasks'

export default function App() {
  const [session, setSession] = useState<Session>({ kind: 'loading' })
  const [revision, setRevision] = useState(0)
  const [notice, setNotice] = useState('')
  const [page, setPage] = useState<Page>('workspace')
  const [editingDirty, setEditingDirty] = useState(false)
  const [editingBusy, setEditingBusy] = useState(false)
  const [pendingNavigation, setPendingNavigation] = useState<Page | 'logout' | null>(null)
  const [engineering, setEngineering] = useState(false)
  const [logoutBusy, setLogoutBusy] = useState(false)
  const [logoutError, setLogoutError] = useState<unknown>(null)
  const [checkingMe, setCheckingMe] = useState(false)
  const sessionEpoch = useRef(0)
  const reportDirty = useCallback((dirty: boolean) => { setEditingDirty(dirty); if (!dirty) setPendingNavigation(null) }, [])
  useEffect(() => {
    const controller = new AbortController()
    let active = true
    void getMe(controller.signal).then((account) => { if (active) setSession({ kind: 'authenticated', account }) })
      .catch((error: unknown) => { if (active) setSession(error instanceof ApiError && error.status === 401 ? { kind: 'anonymous' } : { kind: 'error', error }) })
    return () => { active = false; controller.abort() }
  }, [revision])
  const expired = useCallback(() => { sessionEpoch.current += 1; setSession({ kind: 'anonymous' }); setPage('workspace'); setNotice('登入狀態已失效，請重新登入。'); setLogoutError(null); setEditingDirty(false); setEditingBusy(false); setPendingNavigation(null) }, [])
  const loginSuccess = (account: Account) => { sessionEpoch.current += 1; setSession({ kind: 'authenticated', account }); setPage('workspace'); setNotice(''); setEditingDirty(false); setEditingBusy(false); setPendingNavigation(null) }
  const checkMe = async () => {
    if (logoutBusy) return
    const epoch = sessionEpoch.current
    setCheckingMe(true); setLogoutError(null)
    try { const updated = await getMe(); if (epoch === sessionEpoch.current) setSession({ kind: 'authenticated', account: updated }) }
    catch (error) { if (epoch === sessionEpoch.current) { if (error instanceof ApiError && error.status === 401) expired(); else setLogoutError(error) } }
    finally { setCheckingMe(false) }
  }
  const doLogout = async () => {
    sessionEpoch.current += 1
    setLogoutBusy(true); setLogoutError(null)
    try { await logout(); setSession({ kind: 'anonymous' }); setNotice('已安全登出。'); setPage('workspace'); setEditingDirty(false); setEditingBusy(false); setPendingNavigation(null) }
    catch (error) { if (error instanceof ApiError && error.status === 401) expired(); else setLogoutError(error) }
    finally { setLogoutBusy(false) }
  }
  const navigate = (target: Page | 'logout') => {
    if (target === page) return
    if ((page === 'tom' || page === 'fms' || page === 'drivers' || page === 'tms' || page === 'exceptions' || page === 'closure') && (editingDirty || editingBusy)) { setPendingNavigation(target); return }
    setPendingNavigation(null)
    if (target === 'logout') void doLogout(); else { setEditingBusy(false); setPage(target) }
  }
  const confirmNavigation = () => {
    if (editingBusy) return
    const target = pendingNavigation
    setPendingNavigation(null)
    if (target === 'logout') void doLogout()
    else if (target) { setEditingDirty(false); setPage(target) }
  }
  if (engineering && session.kind !== 'authenticated') return <><button className="return-login secondary-button" onClick={() => setEngineering(false)}>返回登入</button><SystemOverview /></>
  const account = session.kind === 'authenticated' ? session.account : null
  const currentPage = account?.mustChangePassword ? 'password' : page
  const editingLabel = page === 'closure' ? '確認與結案' : page === 'exceptions' ? '異常' : page === 'tms' ? '派工' : page === 'drivers' ? '司機' : page === 'fms' ? '車輛' : '訂單'
  const title = !account ? '帳號登入' : currentPage === 'accounts' ? '帳號管理' : currentPage === 'adm' ? '系統管理' : currentPage === 'crm' ? '客戶管理' : currentPage === 'tom' ? '訂單工作台' : currentPage === 'fms' ? '車輛管理' : currentPage === 'drivers' ? '司機管理' : currentPage === 'tms' ? '調度工作台' : currentPage === 'closure' ? '確認與結案' : currentPage === 'exceptions' ? '異常處理' : currentPage === 'my-tasks' ? '本人運輸任務' : currentPage === 'driver-self' ? '本人司機資料' : currentPage === 'password' ? '修改密碼' : `${roleLabels[account.role]}工作台`

  return <div className="app-shell upms-shell"><a className="skip-link" href="#main-content">跳至主要內容</a><aside className="sidebar"><a className="brand" href="#main-content" aria-label="XK Logistics 首頁"><span className="brand-mark" aria-hidden="true"><img src="/xk-logo-monogram.svg" alt="" width="34" height="34" /></span><span>Logistics<small>貨運物流管理平台</small></span></a><p className="workspace-label">工作空間</p><nav aria-label="主要導覽">{account ? <>
    {!account.mustChangePassword && <button className={`nav-link ${currentPage === 'workspace' ? 'active' : ''}`} aria-current={currentPage === 'workspace' ? 'page' : undefined} onClick={() => navigate('workspace')}>我的工作台</button>}
    {account.role === 'ADMIN' && !account.mustChangePassword && <button className={`nav-link ${currentPage === 'accounts' ? 'active' : ''}`} aria-current={currentPage === 'accounts' ? 'page' : undefined} onClick={() => navigate('accounts')}>帳號管理</button>}
    {account.role === 'ADMIN' && !account.mustChangePassword && <button className={`nav-link ${currentPage === 'adm' ? 'active' : ''}`} aria-current={currentPage === 'adm' ? 'page' : undefined} onClick={() => navigate('adm')}>系統管理</button>}
    {account.role !== 'DRIVER' && !account.mustChangePassword && <button className={`nav-link ${currentPage === 'crm' ? 'active' : ''}`} aria-current={currentPage === 'crm' ? 'page' : undefined} onClick={() => navigate('crm')}>客戶管理</button>}
    {account.role !== 'DRIVER' && !account.mustChangePassword && <button className={`nav-link ${currentPage === 'tom' ? 'active' : ''}`} aria-current={currentPage === 'tom' ? 'page' : undefined} onClick={() => navigate('tom')}>訂單工作台</button>}
    {account.role !== 'DRIVER' && !account.mustChangePassword && <button className={`nav-link ${currentPage === 'fms' ? 'active' : ''}`} aria-current={currentPage === 'fms' ? 'page' : undefined} onClick={() => navigate('fms')}>車輛管理</button>}
    {account.role !== 'DRIVER' && !account.mustChangePassword && <button className={`nav-link ${currentPage === 'drivers' ? 'active' : ''}`} aria-current={currentPage === 'drivers' ? 'page' : undefined} onClick={() => navigate('drivers')}>司機管理</button>}
    {account.role !== 'DRIVER' && !account.mustChangePassword && <button className={`nav-link ${currentPage === 'tms' ? 'active' : ''}`} aria-current={currentPage === 'tms' ? 'page' : undefined} onClick={() => navigate('tms')}>調度工作台</button>}
    {account.role !== 'DRIVER' && !account.mustChangePassword && <button className={`nav-link ${currentPage === 'exceptions' ? 'active' : ''}`} aria-current={currentPage === 'exceptions' ? 'page' : undefined} onClick={() => navigate('exceptions')}>異常處理</button>}
    {account.role !== 'DRIVER' && !account.mustChangePassword && <button className={`nav-link ${currentPage === 'closure' ? 'active' : ''}`} aria-current={currentPage === 'closure' ? 'page' : undefined} onClick={() => navigate('closure')}>確認與結案</button>}
    {account.role === 'DRIVER' && !account.mustChangePassword && <button className={`nav-link ${currentPage === 'my-tasks' ? 'active' : ''}`} aria-current={currentPage === 'my-tasks' ? 'page' : undefined} onClick={() => navigate('my-tasks')}>本人運輸任務</button>}
    {account.role === 'DRIVER' && !account.mustChangePassword && <button className={`nav-link ${currentPage === 'driver-self' ? 'active' : ''}`} aria-current={currentPage === 'driver-self' ? 'page' : undefined} onClick={() => navigate('driver-self')}>本人司機資料</button>}
    <button className={`nav-link ${currentPage === 'password' ? 'active' : ''}`} aria-current={currentPage === 'password' ? 'page' : undefined} onClick={() => navigate('password')}>修改密碼</button>
  </> : <button className="nav-link active">帳號登入</button>}</nav><div className="sidebar-foot"><span className="environment-dot" aria-hidden="true" /> 開發環境<small>XK SYSTEM · v2.0</small></div></aside>
    <div className="main-area"><header className="topbar"><span>XK Logistics <span className="divider">/</span>{title}</span>{account ? <div className="user-tools"><span>{account.displayName} · {roleLabels[account.role]}</span><button className="secondary-button compact" disabled={logoutBusy} onClick={() => navigate('logout')}>{logoutBusy ? '登出中…' : '登出'}</button></div> : <button className="text-button" onClick={() => setEngineering(true)}>系統狀態</button>}</header><main id="main-content" tabIndex={-1}>
      <ErrorNotice error={logoutError} />
      {account && pendingNavigation && <section className="warning-notice order-leave-confirm" role="alert" aria-label="確認切換工作頁面"><h2>{editingLabel}內容尚未完成確認</h2><p>目前有尚未儲存或尚未確認結果的{editingLabel}內容。離開後，本機內容與重試資訊會清除。</p><div className="form-actions"><button className="secondary-button" onClick={() => setPendingNavigation(null)}>留在{editingLabel}頁面</button><button className="secondary-button" disabled={logoutBusy || editingBusy} onClick={confirmNavigation}>{`捨棄${editingLabel}內容並${pendingNavigation === 'logout' ? '登出' : '離開'}`}</button></div></section>}
      {session.kind === 'loading' ? <section className="card" role="status">正在確認登入狀態…</section> : session.kind === 'error' ? <section className="card"><h1>無法確認登入狀態</h1><ErrorNotice error={session.error} /><button className="primary-button" onClick={() => { setSession({ kind: 'loading' }); setRevision((value) => value + 1) }}>重新確認登入</button></section> : !account ? <div className="login-layout"><LoginForm onLogin={loginSuccess} notice={notice} /><section className="login-intro"><p className="eyebrow">XK LOGISTICS / UPMS</p><h2>讓協作從安全登入開始。</h2><p>依您的角色，進入專屬工作空間。帳號由管理員受控建立，沒有預設帳號與密碼。</p><details><summary>檢查系統服務狀態</summary><ServiceHealth /></details></section></div> : currentPage === 'password' ? <PasswordForm forced={account.mustChangePassword} onExpired={expired} onChanged={() => { sessionEpoch.current += 1; setSession({ kind: 'anonymous' }); setPage('workspace'); setNotice('密碼已更新，所有登入狀態已撤銷。請使用新密碼重新登入。') }} /> : currentPage === 'accounts' && account.role === 'ADMIN' ? <AccountManager onExpired={expired} onSelfUpdated={(updated) => { if (updated.id === account.id) { sessionEpoch.current += 1; if (updated.role !== account.role || updated.enabled !== account.enabled) { setSession({ kind: 'anonymous' }); setPage('workspace'); setNotice('您的角色或啟用狀態已更新，請重新登入。') } else setSession({ kind: 'authenticated', account: updated }) } }} /> : currentPage === 'adm' && account.role === 'ADMIN' ? <AdmManager onExpired={expired} /> : currentPage === 'crm' && account.role !== 'DRIVER' ? <CustomerManager onExpired={expired} /> : currentPage === 'tom' && account.role !== 'DRIVER' ? <OrderManager onExpired={expired} onDirtyChange={reportDirty} onBusyChange={setEditingBusy} /> : currentPage === 'drivers' && account.role !== 'DRIVER' ? <DriverManager account={account} onExpired={expired} onDirtyChange={reportDirty} onBusyChange={setEditingBusy} /> : currentPage === 'tms' && account.role !== 'DRIVER' ? <DispatchManager key={account.id} onExpired={expired} onDirtyChange={reportDirty} onBusyChange={setEditingBusy} /> : currentPage === 'exceptions' && account.role !== 'DRIVER' ? <ExceptionManager key={account.id} onExpired={expired} onDirtyChange={reportDirty} onBusyChange={setEditingBusy} /> : currentPage === 'closure' && account.role !== 'DRIVER' ? <ClosureWorkspace key={account.id} onExpired={expired} onDirtyChange={reportDirty} onBusyChange={setEditingBusy} /> : currentPage === 'my-tasks' && account.role === 'DRIVER' ? logoutBusy ? <section className="card" role="status">正在安全登出…</section> : <MyTasks key={account.id} onExpired={expired} /> : currentPage === 'driver-self' && account.role === 'DRIVER' ? <DriverSelf onExpired={expired} /> : currentPage === 'fms' && account.role !== 'DRIVER' ? <VehicleManager account={account} onExpired={expired} onDirtyChange={reportDirty} onBusyChange={setEditingBusy} /> : <>
        <div className="page-heading"><div><p className="eyebrow">YOUR WORKSPACE</p><h1>{roleLabels[account.role]}工作台</h1><p className="page-description">{account.displayName}，歡迎回來。</p></div><span className="stage-tag">運輸執行工作台</span></div>
        <div className="overview-grid"><section className="card"><p className="eyebrow">MY ACCOUNT</p><h2>本人帳號</h2><dl className="account-details"><div><dt>帳號</dt><dd>{account.username}</dd></div><div><dt>顯示名稱</dt><dd>{account.displayName}</dd></div><div><dt>角色</dt><dd>{roleLabels[account.role]}</dd></div><div><dt>狀態</dt><dd>啟用</dd></div></dl><div className="form-actions"><button className="secondary-button" onClick={() => navigate('password')}>前往修改密碼</button><button className="secondary-button" disabled={checkingMe || logoutBusy} onClick={() => { void checkMe() }}>{checkingMe ? '確認中…' : '重新確認本人狀態'}</button></div></section><section className="card"><p className="eyebrow">ACCESS SCOPE</p><h2>{account.role === 'ADMIN' ? '管理帳號與固定角色' : account.role === 'DISPATCHER' ? '調度工作空間已就緒' : '司機工作空間已就緒'}</h2><p className="workspace-copy">{account.role === 'ADMIN' ? '可管理帳號與系統資料，維護本公司客戶、車輛與司機主檔、訂單草稿、正式受理及出發前派工，查看運輸異常與補件歷程、管理完成確認及訂單結案；操作依伺服器目前策略驗證。' : account.role === 'DISPATCHER' ? '可維護本公司客戶、車輛與司機主檔及進口、出口、國內運輸訂單，編排項目、任務與前置依賴，正式受理完整訂單，並管理出發前派工及查看運輸異常與補件歷程、管理完成確認及訂單結案；操作依伺服器目前策略驗證。' : '可查看目前帳號綁定的本人司機資料及本人指派任務，並維護本人帳號與密碼。'}</p>{account.role === 'ADMIN' && <button className="primary-button" onClick={() => navigate('accounts')}>管理帳號</button>}{account.role !== 'DRIVER' && <button className="primary-button workspace-order-button" onClick={() => navigate('tom')}>管理訂單</button>}{account.role !== 'DRIVER' && <button className="secondary-button workspace-crm-button" onClick={() => navigate('crm')}>管理客戶</button>}{account.role !== 'DRIVER' && <button className="secondary-button workspace-crm-button" onClick={() => navigate('fms')}>管理車輛</button>}{account.role !== 'DRIVER' && <button className="secondary-button workspace-crm-button" onClick={() => navigate('drivers')}>管理司機</button>}{account.role !== 'DRIVER' && <button className="primary-button workspace-order-button" onClick={() => navigate('tms')}>開始調度</button>}{account.role !== 'DRIVER' && <button className="secondary-button workspace-crm-button" onClick={() => navigate('exceptions')}>處理異常</button>}{account.role !== 'DRIVER' && <button className="secondary-button workspace-crm-button" onClick={() => navigate('closure')}>開啟確認與結案</button>}{account.role === 'DRIVER' && <button className="primary-button workspace-order-button" onClick={() => navigate('my-tasks')}>查看本人任務</button>}{account.role === 'DRIVER' && <button className="secondary-button workspace-crm-button" onClick={() => navigate('driver-self')}>本人司機資料</button>}<p className="scope-copy">一帳號一角色；操作權限與資料範圍由伺服器驗證。</p></section></div>
        <section className="scope-strip"><p>請勿共用帳號。閒置 30 分鐘或登入滿 8 小時後須重新登入；每個帳號最多 3 個並存登入。</p></section>
      </>}
    </main><footer>XK SYSTEM <span>探索萬象科技</span><span className="footer-right">XK Logistics v2.0</span></footer></div></div>
}
