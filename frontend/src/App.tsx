import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, getMe, logout, roleLabels } from './api'
import type { Account } from './api'
import { ErrorNotice, LoginForm, PasswordForm } from './AuthForms'
import AccountManager from './AccountManager'
import SystemOverview, { ServiceHealth } from './SystemOverview'

type Session = { kind: 'loading' } | { kind: 'anonymous' } | { kind: 'authenticated'; account: Account } | { kind: 'error'; error: unknown }
type Page = 'workspace' | 'accounts' | 'password'

export default function App() {
  const [session, setSession] = useState<Session>({ kind: 'loading' })
  const [revision, setRevision] = useState(0)
  const [notice, setNotice] = useState('')
  const [page, setPage] = useState<Page>('workspace')
  const [engineering, setEngineering] = useState(false)
  const [logoutBusy, setLogoutBusy] = useState(false)
  const [logoutError, setLogoutError] = useState<unknown>(null)
  const [checkingMe, setCheckingMe] = useState(false)
  const sessionEpoch = useRef(0)
  useEffect(() => {
    const controller = new AbortController()
    let active = true
    void getMe(controller.signal).then((account) => { if (active) setSession({ kind: 'authenticated', account }) })
      .catch((error: unknown) => { if (active) setSession(error instanceof ApiError && error.status === 401 ? { kind: 'anonymous' } : { kind: 'error', error }) })
    return () => { active = false; controller.abort() }
  }, [revision])
  const expired = useCallback(() => { sessionEpoch.current += 1; setSession({ kind: 'anonymous' }); setPage('workspace'); setNotice('登入狀態已失效，請重新登入。'); setLogoutError(null) }, [])
  const loginSuccess = (account: Account) => { sessionEpoch.current += 1; setSession({ kind: 'authenticated', account }); setPage('workspace'); setNotice('') }
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
    try { await logout(); setSession({ kind: 'anonymous' }); setNotice('已安全登出。'); setPage('workspace') }
    catch (error) { if (error instanceof ApiError && error.status === 401) expired(); else setLogoutError(error) }
    finally { setLogoutBusy(false) }
  }
  if (engineering && session.kind !== 'authenticated') return <><button className="return-login secondary-button" onClick={() => setEngineering(false)}>返回登入</button><SystemOverview /></>
  const account = session.kind === 'authenticated' ? session.account : null
  const currentPage = account?.mustChangePassword ? 'password' : page
  const title = !account ? '帳號登入' : currentPage === 'accounts' ? '帳號管理' : currentPage === 'password' ? '修改密碼' : `${roleLabels[account.role]}工作台`

  return <div className="app-shell upms-shell"><a className="skip-link" href="#main-content">跳至主要內容</a><aside className="sidebar"><a className="brand" href="#main-content" aria-label="XK Logistics 首頁"><span className="brand-mark" aria-hidden="true"><img src="/xk-logo-monogram.svg" alt="" width="34" height="34" /></span><span>Logistics<small>貨運物流管理平台</small></span></a><p className="workspace-label">工作空間</p><nav aria-label="主要導覽">{account ? <>
    {!account.mustChangePassword && <button className={`nav-link ${currentPage === 'workspace' ? 'active' : ''}`} aria-current={currentPage === 'workspace' ? 'page' : undefined} onClick={() => setPage('workspace')}>我的工作台</button>}
    {account.role === 'ADMIN' && !account.mustChangePassword && <button className={`nav-link ${currentPage === 'accounts' ? 'active' : ''}`} aria-current={currentPage === 'accounts' ? 'page' : undefined} onClick={() => setPage('accounts')}>帳號管理</button>}
    <button className={`nav-link ${currentPage === 'password' ? 'active' : ''}`} aria-current={currentPage === 'password' ? 'page' : undefined} onClick={() => setPage('password')}>修改密碼</button>
  </> : <button className="nav-link active">帳號登入</button>}</nav><div className="sidebar-foot"><span className="environment-dot" aria-hidden="true" /> 開發環境<small>XK SYSTEM · v2.0</small></div></aside>
    <div className="main-area"><header className="topbar"><span>XK Logistics <span className="divider">/</span>{title}</span>{account ? <div className="user-tools"><span>{account.displayName} · {roleLabels[account.role]}</span><button className="secondary-button compact" disabled={logoutBusy} onClick={() => { void doLogout() }}>{logoutBusy ? '登出中…' : '登出'}</button></div> : <button className="text-button" onClick={() => setEngineering(true)}>系統狀態</button>}</header><main id="main-content" tabIndex={-1}>
      <ErrorNotice error={logoutError} />
      {session.kind === 'loading' ? <section className="card" role="status">正在確認登入狀態…</section> : session.kind === 'error' ? <section className="card"><h1>無法確認登入狀態</h1><ErrorNotice error={session.error} /><button className="primary-button" onClick={() => { setSession({ kind: 'loading' }); setRevision((value) => value + 1) }}>重新確認登入</button></section> : !account ? <div className="login-layout"><LoginForm onLogin={loginSuccess} notice={notice} /><section className="login-intro"><p className="eyebrow">XK LOGISTICS / UPMS</p><h2>讓協作從安全登入開始。</h2><p>依您的角色，進入專屬工作空間。帳號由管理員受控建立，沒有預設帳號與密碼。</p><details><summary>檢查系統服務狀態</summary><ServiceHealth /></details></section></div> : currentPage === 'password' ? <PasswordForm forced={account.mustChangePassword} onExpired={expired} onChanged={() => { sessionEpoch.current += 1; setSession({ kind: 'anonymous' }); setPage('workspace'); setNotice('密碼已更新，所有登入狀態已撤銷。請使用新密碼重新登入。') }} /> : currentPage === 'accounts' && account.role === 'ADMIN' ? <AccountManager onExpired={expired} onSelfUpdated={(updated) => { if (updated.id === account.id) { sessionEpoch.current += 1; if (updated.role !== account.role || updated.enabled !== account.enabled) { setSession({ kind: 'anonymous' }); setPage('workspace'); setNotice('您的角色或啟用狀態已更新，請重新登入。') } else setSession({ kind: 'authenticated', account: updated }) } }} /> : <>
        <div className="page-heading"><div><p className="eyebrow">YOUR WORKSPACE</p><h1>{roleLabels[account.role]}工作台</h1><p className="page-description">{account.displayName}，歡迎回來。</p></div><span className="stage-tag">M2-01 帳號與權限</span></div>
        <div className="overview-grid"><section className="card"><p className="eyebrow">MY ACCOUNT</p><h2>本人帳號</h2><dl className="account-details"><div><dt>帳號</dt><dd>{account.username}</dd></div><div><dt>顯示名稱</dt><dd>{account.displayName}</dd></div><div><dt>角色</dt><dd>{roleLabels[account.role]}</dd></div><div><dt>狀態</dt><dd>啟用</dd></div></dl><div className="form-actions"><button className="secondary-button" onClick={() => setPage('password')}>前往修改密碼</button><button className="secondary-button" disabled={checkingMe || logoutBusy} onClick={() => { void checkMe() }}>{checkingMe ? '確認中…' : '重新確認本人狀態'}</button></div></section><section className="card"><p className="eyebrow">ACCESS SCOPE</p><h2>{account.role === 'ADMIN' ? '管理帳號與固定角色' : account.role === 'DISPATCHER' ? '調度工作空間已就緒' : '司機工作空間已就緒'}</h2><p className="workspace-copy">{account.role === 'ADMIN' ? '可建立帳號、維護顯示名稱及角色、啟用或停用帳號。' : '目前提供本人帳號及密碼維護。物流業務功能將於後續工作包開放。'}</p>{account.role === 'ADMIN' && <button className="primary-button" onClick={() => setPage('accounts')}>管理帳號</button>}<p className="scope-copy">一帳號一角色；操作權限與資料範圍由伺服器驗證。</p></section></div>
        <section className="scope-strip"><p>請勿共用帳號。閒置 30 分鐘或登入滿 8 小時後須重新登入；每個帳號最多 3 個並存登入。</p></section>
      </>}
    </main><footer>XK SYSTEM <span>探索萬象科技</span><span className="footer-right">XK Logistics v2.0</span></footer></div></div>
}
