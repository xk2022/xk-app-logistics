import { useCallback, useState } from 'react'
import type { FormEvent } from 'react'
import { getCustomerOptions, validateText } from './crmApi'
import { AdmError, Pager } from './AdmShared'
import { useAdmPage } from './useAdmPage'

export default function CustomerOptions({ revision, onExpired }: { revision: number; onExpired: () => void }) {
  const [q, setQ] = useState('')
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const [attempt, setAttempt] = useState(0)
  const [error, setError] = useState<unknown>(null)
  const load = useCallback((page: number, signal?: AbortSignal) => getCustomerOptions(page, search, signal), [search])
  const result = useAdmPage(load, page, revision + attempt, onExpired)
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    try { validateText(q, '選項搜尋', 100); setSearch(q.trim()); setPage(0); setAttempt((value) => value + 1); setError(null) } catch (failure) { setError(failure) }
  }
  return <section className="card crm-options" aria-labelledby="customer-options-heading"><h2 id="customer-options-heading">有效客戶選項</h2><p className="page-description">僅預覽可供未來新引用的啟用客戶；此處不建立訂單或物流任務。</p><form className="crm-search-form" onSubmit={submit}><div><label htmlFor="customer-options-search">選項搜尋</label><input id="customer-options-search" value={q} maxLength={200} onChange={(event) => setQ(event.target.value)} /></div><button className="secondary-button" disabled={result.loading}>查詢有效客戶</button></form><AdmError error={error ?? result.error} />{result.loading ? <p role="status">正在載入有效客戶…</p> : result.error ? <p>無法載入有效客戶，請重新查詢。</p> : <>{result.data?.content.length ? <ul className="customer-option-list">{result.data.content.map((option) => <li key={option.id}><strong>{option.name}</strong><code>{option.customerCode}</code></li>)}</ul> : <p className="empty-state">目前沒有符合條件的有效客戶。</p>}{result.data && <Pager label="有效客戶分頁" {...result.data} disabled={false} onPage={setPage} />}</>}</section>
}
