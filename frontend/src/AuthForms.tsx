import { useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError, changePassword, login } from './api'
import type { Account } from './api'

export function ErrorNotice({ error }: { error: unknown }) {
  if (!error) return null
  return <div className="error-notice" role="alert"><p>{error instanceof ApiError ? error.message : '操作未完成，請稍後重試。'}</p>{error instanceof ApiError && Object.keys(error.fields).length > 0 && <ul>{Object.entries(error.fields).map(([field, message]) => <li key={field}>{message}</li>)}</ul>}</div>
}

export function LoginForm({ onLogin, notice }: { onLogin: (account: Account) => void; notice: string }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setBusy(true); setError(null)
    try { onLogin(await login(username, password)) } catch (failure) { setError(failure) }
    finally { setBusy(false); setPassword('') }
  }
  return <section className="card login-card" aria-labelledby="login-heading"><p className="eyebrow">WELCOME BACK</p><h1 id="login-heading">登入工作空間</h1><p className="page-description">使用受控建立的帳號，安全進入您的工作台。</p>{notice && <p className="success-notice" role="status">{notice}</p>}<ErrorNotice error={error} /><form onSubmit={(event) => { void submit(event) }} className="stack-form"><label htmlFor="login-username">帳號</label><input id="login-username" autoComplete="username" required value={username} disabled={busy} onChange={(event) => setUsername(event.target.value)} /><label htmlFor="login-password">密碼</label><input id="login-password" type="password" autoComplete="current-password" required value={password} disabled={busy} onChange={(event) => setPassword(event.target.value)} /><button className="primary-button" disabled={busy}>{busy ? '登入中…' : '登入'}</button></form><div className="recovery-note"><strong>需要帳號或忘記密碼？</strong><p>請聯絡負責人，透過受控流程建立或復原帳號。此平台不提供公開註冊或匿名密碼重設。</p></div></section>
}

export function PasswordForm({ forced, onChanged, onExpired }: { forced: boolean; onChanged: () => void; onExpired: () => void }) {
  const [current, setCurrent] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setBusy(true); setError(null)
    try {
      const count = [...password.normalize('NFC')].length
      if (count < 15 || count > 128) throw new ApiError(400, '新密碼須為 15–128 字元。')
      if (password !== confirm) throw new ApiError(400, '兩次輸入的新密碼不一致。')
      await changePassword(current, password); onChanged()
    } catch (failure) { setError(failure); if (failure instanceof ApiError && failure.status === 401) onExpired() }
    finally { setBusy(false); setCurrent(''); setPassword(''); setConfirm('') }
  }
  return <section className="card password-card" aria-labelledby="password-heading"><p className="eyebrow">ACCOUNT SECURITY</p><h1 id="password-heading">{forced ? '首次登入，請先修改密碼' : '修改密碼'}</h1><p className="page-description">{forced ? '完成改密後，才能使用工作台。' : '更新密碼可保護您的帳號。'} 改密成功會撤銷所有登入狀態，請重新登入。</p><ErrorNotice error={error} /><form className="stack-form" onSubmit={(event) => { void submit(event) }}><label htmlFor="current-password">目前密碼</label><input id="current-password" required disabled={busy} type="password" autoComplete="current-password" value={current} onChange={(event) => setCurrent(event.target.value)} /><label htmlFor="new-password">新密碼</label><input id="new-password" required disabled={busy} type="password" autoComplete="new-password" value={password} onChange={(event) => setPassword(event.target.value)} aria-describedby="password-help" /><small id="password-help">15–128 字元，支援空格及 Unicode 字元。</small><label htmlFor="confirm-password">確認新密碼</label><input id="confirm-password" required disabled={busy} type="password" autoComplete="new-password" value={confirm} onChange={(event) => setConfirm(event.target.value)} /><button className="primary-button" disabled={busy}>{busy ? '修改中…' : '確認修改密碼'}</button></form></section>
}
