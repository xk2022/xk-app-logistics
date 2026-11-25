import { useEffect, useState } from 'react'
import { fetchStatus } from './status'
import type { ServiceStatus } from './status'

type StatusState =
  | { kind: 'loading' }
  | { kind: 'ready'; service: ServiceStatus }
  | { kind: 'error'; message: string }

export function ServiceHealth() {
  const [attempt, setAttempt] = useState(0)
  const [state, setState] = useState<StatusState>({ kind: 'loading' })

  useEffect(() => {
    const controller = new AbortController()
    let active = true
    const timeout = window.setTimeout(() => controller.abort(), 8000)
    void fetchStatus(controller.signal)
      .then((service) => { if (active) setState({ kind: 'ready', service }) })
      .catch((error: unknown) => {
        if (active) setState({ kind: 'error', message: error instanceof Error && error.name !== 'AbortError'
          ? error.message : '連線逾時或中斷，請確認服務已啟動後重試。' })
      })
      .finally(() => window.clearTimeout(timeout))
    return () => { active = false; window.clearTimeout(timeout); controller.abort() }
  }, [attempt])

  const refresh = () => {
    setState({ kind: 'loading' })
    setAttempt((value) => value + 1)
  }

  return (
    <section className="card health-card" aria-labelledby="health-title">
      <div className="card-heading">
        <div><p className="eyebrow">SERVICE HEALTH</p><h2 id="health-title">服務狀態</h2></div>
        <span className={`status-badge ${state.kind}`}>
          <span className="status-dot" aria-hidden="true" />
          {state.kind === 'loading' ? '檢查中' : state.kind === 'ready' ? '正常運作' : '無法連線'}
        </span>
      </div>
      <div className="health-content" role="status" aria-live="polite" aria-atomic="true">
        <h3>{state.kind === 'loading' ? '正在檢查服務連線…' : state.kind === 'ready' ? '系統服務已就緒' : '暫時無法取得服務狀態'}</h3>
        <p>{state.kind === 'loading' ? '請稍候，正在確認後端與資料庫連線。' : state.kind === 'ready'
          ? '後端與資料庫連線正常，可以繼續開發環境驗證。' : state.message}</p>
        {state.kind === 'ready' && <p className="checked-at">最後確認：<time dateTime={state.service.timestamp}>{new Intl.DateTimeFormat('zh-TW', {
          timeZone: 'Asia/Taipei', dateStyle: 'medium', timeStyle: 'medium',
        }).format(new Date(state.service.timestamp))}（台北時間）</time></p>}
      </div>
      <button className="primary-button" onClick={refresh} disabled={state.kind === 'loading'}>
        {state.kind === 'loading' ? '檢查中…' : state.kind === 'error' ? '重新連線' : '重新檢查'}
        <svg aria-hidden="true" width="17" height="17" viewBox="0 0 24 24" fill="none"><path d="M6 18 18 6M6 6h12v12" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" /></svg>
      </button>
    </section>
  )
}

export default function SystemOverview() {
  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content">跳至主要內容</a>
      <aside className="sidebar">
        <a className="brand" href="#main-content" aria-label="XK Logistics 首頁"><span className="brand-mark" aria-hidden="true"><img src="/xk-logo-monogram.svg" alt="" width="34" height="34" /></span><span>Logistics<small>貨運物流管理平台</small></span></a>
        <p className="workspace-label">工作空間</p>
        <nav aria-label="主要導覽"><a className="nav-link active" href="#main-content" aria-current="page"><svg aria-hidden="true" width="19" height="19" viewBox="0 0 24 24" fill="none"><path d="m12 3 9 9-9 9-9-9 9-9Z" stroke="currentColor" strokeWidth="1.6" /><path d="m12 8 4 4-4 4-4-4 4-4Z" fill="currentColor" /></svg> 系統總覽</a></nav>
        <div className="sidebar-foot"><span className="environment-dot" aria-hidden="true" /> 開發環境<small>XK SYSTEM · v2.0</small></div>
      </aside>
      <div className="main-area">
        <header className="topbar"><span>XK Logistics <span className="divider">/</span> 系統總覽</span><span className="environment-tag">DEVELOPMENT</span></header>
        <main id="main-content" tabIndex={-1}>
          <div className="page-heading"><div><p className="eyebrow">WORKSPACE OVERVIEW</p><h1>系統總覽</h1><p className="page-description">歡迎使用 XK Logistics，從這裡確認開發環境的服務狀態。</p></div><span className="stage-tag">M1 工程初始化</span></div>
          <div className="overview-grid">
            <ServiceHealth />
            <section className="card introduction" aria-labelledby="workspace-title"><p className="eyebrow">XK LOGISTICS</p><h2 id="workspace-title">讓每一段運送，清楚有序。</h2><p>貨運物流管理平台的工程基礎已建立。這個工作空間將逐步串起訂單、任務與運送協作。</p><div className="scope-note"><svg aria-hidden="true" width="20" height="20" viewBox="0 0 24 24" fill="none"><circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="1.6" /><circle cx="12" cy="12" r="4" stroke="currentColor" strokeWidth="1.6" /></svg><div><strong>目前階段</strong><p>提供系統殼層與服務狀態檢查；業務功能將於後續階段開放。</p></div></div></section>
          </div>
          <section className="scope-strip" aria-label="環境說明"><span className="info-symbol" aria-hidden="true">i</span><p>此為開發環境，僅供工程驗證，請使用合成測試資料。</p></section>
        </main>
        <footer>XK SYSTEM <span>探索萬象科技</span><span className="footer-right">XK Logistics v2.0</span></footer>
      </div>
    </div>
  )
}
