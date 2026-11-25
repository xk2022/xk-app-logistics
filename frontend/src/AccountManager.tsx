import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError, createAccount, listAccounts, listRoles, roleLabels, updateAccount } from './api'
import type { Account, Role } from './api'
import { ErrorNotice } from './AuthForms'

export default function AccountManager({ onExpired, onSelfUpdated }: { onExpired: () => void; onSelfUpdated: (account: Account) => void }) {
  const [accounts, setAccounts] = useState<Account[]>([])
  const [availableRoles, setAvailableRoles] = useState<Role[]>([])
  const [revision, setRevision] = useState(0)
  const [loading, setLoading] = useState(true)
  const [loadFailed, setLoadFailed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState('')
  const [editing, setEditing] = useState<Account | null>(null)
  const [username, setUsername] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [role, setRole] = useState<Role>('DISPATCHER')
  const [password, setPassword] = useState('')
  const [enabled, setEnabled] = useState(true)

  useEffect(() => {
    const controller = new AbortController()
    let active = true
    void Promise.all([listAccounts(controller.signal), listRoles(controller.signal)])
      .then(([data, roleData]) => { if (active) { setAccounts(data); setAvailableRoles(roleData); setLoadFailed(false) } })
      .catch((failure: unknown) => { if (active) { setError(failure); setLoadFailed(true); if (failure instanceof ApiError && failure.status === 401) onExpired() } })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false; controller.abort() }
  }, [revision, onExpired])

  const reload = () => { clearForm(); setLoading(true); setError(null); setRevision((value) => value + 1) }
  const clearForm = () => { setEditing(null); setUsername(''); setDisplayName(''); setRole('DISPATCHER'); setPassword(''); setEnabled(true) }
  const edit = (account: Account) => {
    setEditing(account); setUsername(account.username); setDisplayName(account.displayName); setRole(account.role); setEnabled(account.enabled)
    setPassword(''); setError(null); setNotice('')
    window.setTimeout(() => document.getElementById('account-display-name')?.focus(), 0)
  }
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setBusy(true); setError(null); setNotice('')
    try {
      if (!displayName.trim() || [...displayName.trim()].length > 80) throw new ApiError(400, '顯示名稱須為 1–80 字元。')
      if (!editing && !/^[a-z0-9][a-z0-9._-]{2,63}$/.test(username)) throw new ApiError(400, '帳號須為 3–64 個小寫英數字，可包含 . _ -，且以英數字開頭。')
      if (!editing && ([...password.normalize('NFC')].length < 15 || [...password.normalize('NFC')].length > 128)) throw new ApiError(400, '初始密碼須為 15–128 字元。')
      const updated = editing ? await updateAccount(editing.id, { displayName, role, enabled, version: editing.version })
        : await createAccount({ username, displayName, role, password })
      onSelfUpdated(updated)
      setNotice(editing ? '帳號已更新。變更會由伺服器立即套用。' : '帳號已建立。請透過受控管道交付初始密碼，並於 15 分鐘內登入改密。')
      clearForm(); reload()
    } catch (failure) {
      setError(failure)
      if (failure instanceof ApiError && failure.status === 401) onExpired()
    } finally { setBusy(false); setPassword('') }
  }

  return (
    <section aria-labelledby="accounts-heading" className="accounts-section">
      <div className="section-title"><div><p className="eyebrow">USER MANAGEMENT</p><h1 id="accounts-heading">帳號管理</h1><p className="page-description">維護帳號與固定角色。所有變更均由伺服器驗證與稽核。</p></div><button className="secondary-button" disabled={loading || busy} onClick={reload}>重新載入帳號</button></div>
      {notice && <p className="success-notice" role="status">{notice}</p>}
      <ErrorNotice error={error} />
      {error instanceof ApiError && error.status === 409 && <p className="conflict-help">資料可能已更新，或此變更不符合帳號保護規則。請重新載入帳號後確認再操作。</p>}
      <div className="account-layout">
        <section className="card account-list" aria-labelledby="account-list-heading">
          <h2 id="account-list-heading">帳號列表</h2>
          {loading ? <p role="status">正在載入帳號…</p> : loadFailed ? <p>無法載入帳號，請重新載入。</p> : <div className="table-scroll"><table><caption className="sr-only">公司帳號及角色</caption><thead><tr><th scope="col">帳號／名稱</th><th scope="col">角色</th><th scope="col">狀態</th><th scope="col">操作</th></tr></thead><tbody>{accounts.map((account) => <tr key={account.id}><th scope="row"><span>{account.displayName}</span><small>{account.username}</small></th><td>{roleLabels[account.role]}</td><td><span className={`account-state ${account.enabled ? '' : 'disabled'}`}>{account.enabled ? '啟用' : '停用'}</span>{account.mustChangePassword && <small>待改密</small>}</td><td><button className="text-button" disabled={busy} aria-label={`編輯 ${account.username}`} onClick={() => edit(account)}>編輯</button></td></tr>)}</tbody></table>{accounts.length === 0 && <p>尚無可顯示帳號。</p>}</div>}
        </section>
        <section className="card" aria-labelledby="account-form-heading"><h2 id="account-form-heading">{editing ? `編輯帳號：${editing.username}` : '建立帳號'}</h2>
          <form onSubmit={(event) => { void submit(event) }} className="stack-form">
            <label htmlFor="account-username">帳號</label><input id="account-username" autoComplete="off" required disabled={busy || Boolean(editing)} value={username} maxLength={64} onChange={(event) => setUsername(event.target.value)} aria-describedby="username-help" />
            <small id="username-help">3–64 個小寫英數字，可包含 . _ -</small>
            <label htmlFor="account-display-name">顯示名稱</label><input id="account-display-name" required disabled={busy} value={displayName} maxLength={160} onChange={(event) => setDisplayName(event.target.value)} />
            <label htmlFor="account-role">角色</label><select id="account-role" value={role} disabled={busy || availableRoles.length === 0} onChange={(event) => setRole(event.target.value as Role)}>{availableRoles.map((item) => <option value={item} key={item}>{roleLabels[item]}</option>)}</select>
            {editing ? <label className="checkbox-label"><input type="checkbox" checked={enabled} disabled={busy} onChange={(event) => setEnabled(event.target.checked)} />啟用帳號</label> : <><label htmlFor="initial-password">初始密碼</label><input id="initial-password" type="password" autoComplete="new-password" required disabled={busy} value={password} onChange={(event) => setPassword(event.target.value)} aria-describedby="initial-password-help" /><small id="initial-password-help">15–128 字元；新帳號須於首次登入時改密。</small></>}
            <div className="form-actions"><button className="primary-button" disabled={busy || loading || loadFailed || availableRoles.length === 0}>{busy ? '儲存中…' : editing ? '儲存變更' : '建立帳號'}</button>{editing && <button className="secondary-button" type="button" disabled={busy} onClick={clearForm}>取消編輯</button>}</div>
          </form>
        </section>
      </div>
      <aside className="scope-strip"><p>帳號復原須由具主機權限的負責人執行受控離線命令；此頁不提供匿名重設密碼。角色固定為管理員、調度員與司機，一帳號一角色。</p></aside>
    </section>
  )
}
