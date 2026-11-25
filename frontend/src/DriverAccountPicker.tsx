import { useEffect, useState } from 'react'
import { ApiError } from './api'
import { ErrorNotice } from './AuthForms'
import { getDriverAccountOptions } from './driverApi'
import type { Driver, DriverAccountOption } from './driverApi'
import type { PageData } from './admApi'
import { stripDriverText } from './driverModel'

export default function DriverAccountPicker({ value, driver, disabled, issue, onChange, onExpired }: { value: string; driver?: Driver; disabled: boolean; issue?: string; onChange: (id: string) => void; onExpired: () => void }) {
  const [text, setText] = useState('')
  const [search, setSearch] = useState({ q: '', page: 0, revision: 0 })
  const [state, setState] = useState<{ search: typeof search; data?: PageData<DriverAccountOption>; error?: unknown }>()
  const [choices, setChoices] = useState<Record<string, DriverAccountOption>>({})
  const [inputError, setInputError] = useState<unknown>(null)
  useEffect(() => {
    const controller = new AbortController(); let active = true
    void getDriverAccountOptions(search.page, { q: search.q, size: 5 }, controller.signal).then((data) => {
      if (active) { setState({ search, data }); setChoices((previous) => ({ ...previous, ...Object.fromEntries(data.content.map((account) => [account.id, account])) })) }
    }).catch((error: unknown) => { if (active) { setState({ search, error }); if (error instanceof ApiError && error.status === 401) onExpired() } })
    return () => { active = false; controller.abort() }
  }, [search, onExpired])
  const current = state?.search === search ? state : undefined
  const chosen = choices[value]
  const original = driver?.accountId === value
  const label = (account: DriverAccountOption) => `${account.displayName} · ${account.username}${account.bound ? account.id === driver?.accountId ? '（原綁定）' : '（已綁定，不可選）' : ''}`
  const searchAccounts = () => {
    const q = stripDriverText(text)
    if ([...q].length > 100) { setInputError(new ApiError(400, '綁定帳號搜尋最多 100 字元。')); return }
    setInputError(null); setSearch({ q, page: 0, revision: search.revision + 1 })
  }
  return <fieldset className="driver-account-picker" disabled={disabled}><legend>登入帳號綁定（選填）</legend><p>司機主檔與登入帳號分開。只可選擇符合條件的司機角色帳號；此處不建立帳號、不修改密碼或角色。</p><div className="driver-account-query"><div><label htmlFor="driver-account-q">綁定帳號搜尋</label><input id="driver-account-q" value={text} autoComplete="off" onChange={(event) => setText(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter') { event.preventDefault(); searchAccounts() } }} /></div><button type="button" className="secondary-button" onClick={searchAccounts}>查詢可綁定帳號</button></div><ErrorNotice error={inputError ?? current?.error} />{!current ? <p role="status">正在讀取可綁定帳號…</p> : current.data?.content.length === 0 && <p className="driver-muted">此頁沒有符合條件的帳號。仍可保留未綁定主檔；需要帳號請聯絡管理員。</p>}
    <label htmlFor="driver-accountId">綁定登入帳號</label><select id="driver-accountId" name="accountId" value={value} onChange={(event) => onChange(event.target.value)} aria-invalid={issue ? true : undefined} aria-describedby={`driver-account-help${issue ? ' driver-account-error' : ''}`}><option value="">未綁定帳號</option>{value && !current?.data?.content.some((account) => account.id === value) && <option value={value} disabled={!original && chosen?.bound}>{original ? `${driver?.accountDisplayName ?? '原帳號'} · ${driver?.accountUsername ?? '既有綁定'}（原綁定）` : chosen ? label(chosen) : '已選帳號'}</option>}{driver?.accountId && driver.accountId !== value && !current?.data?.content.some((account) => account.id === driver.accountId) && <option value={driver.accountId}>{driver.accountDisplayName ?? '原帳號'} · {driver.accountUsername ?? '既有綁定'}（原綁定）</option>}{current?.data?.content.map((account) => <option key={account.id} value={account.id} disabled={account.bound && account.id !== driver?.accountId}>{label(account)}</option>)}</select><small id="driver-account-help">已被綁定的候選不可選，原綁定可保留。有效臨時帳號可綁定，但本人須先完成首次改密才能讀取司機資料。</small>{issue && <p className="driver-field-error" id="driver-account-error">{issue}</p>}{driver?.accountId && !driver.accountBindingValid && <p className="driver-history-note">原綁定帳號目前無法讀取本人資料。可保留關聯並維護主檔，或明確解綁／選擇另一個有效帳號；不會自動停用、改角色或解除關聯。</p>}{driver?.accountId && value !== driver.accountId && <p className="driver-binding-warning">儲存這次{value ? '換綁' : '解綁'}後，原帳號即不能繼續讀取此司機主檔。帳號本身的角色與登入狀態不會由本頁變更。</p>}{!original && chosen?.bound && <p className="driver-field-error">已選帳號目前顯示為已綁定，請查詢並選擇另一個候選，或保留未綁定。</p>}{current?.data && <nav className="adm-pager" aria-label="可綁定帳號分頁"><span>共 {current.data.totalElements} 筆 · {current.data.totalPages ? `第 ${current.data.page + 1} / ${current.data.totalPages} 頁` : '目前無資料'}</span><div><button type="button" className="secondary-button compact" disabled={disabled || current.data.page === 0} onClick={() => setSearch({ ...search, page: search.page - 1 })}>上一頁帳號</button><button type="button" className="secondary-button compact" disabled={disabled || current.data.page + 1 >= current.data.totalPages || current.data.page >= 100000} onClick={() => setSearch({ ...search, page: search.page + 1 })}>下一頁帳號</button></div></nav>}
  </fieldset>
}
