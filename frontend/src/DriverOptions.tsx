import { useCallback, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from './api'
import { ErrorNotice } from './AuthForms'
import { Pager } from './AdmShared'
import { useAdmPage } from './useAdmPage'
import { getDriverOptions } from './driverApi'
import { stripDriverText } from './driverModel'

export default function DriverOptions({ revision, onExpired }: { revision: number; onExpired: () => void }) {
  const [text, setText] = useState('')
  const [q, setQ] = useState('')
  const [page, setPage] = useState(0)
  const [attempt, setAttempt] = useState(0)
  const [error, setError] = useState<unknown>(null)
  const load = useCallback((index: number, signal?: AbortSignal) => getDriverOptions(index, { q, size: 5 }, signal), [q])
  const { loading, data, error: loadError } = useAdmPage(load, page, revision + attempt, onExpired)
  const search = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); const normalized = stripDriverText(text)
    if ([...normalized].length > 100) { setError(new ApiError(400, '行政可用司機搜尋最多 100 字元。')); return }
    setQ(normalized); setPage(0); setAttempt((value) => value + 1); setError(null)
  }
  return <section className="card driver-options" aria-label="行政可用司機選項"><h2>行政可用司機選項</h2><p className="driver-muted">只依「在職且出勤」列出。未綁定帳號、證照日期或封存類別不額外排除；這不是實際可派工、資源占用或法規資格判定。</p><form className="driver-options-query" onSubmit={search}><div><label htmlFor="driver-option-search">行政可用司機搜尋</label><input id="driver-option-search" autoComplete="off" value={text} onChange={(event) => setText(event.target.value)} placeholder="字面搜尋編號、姓名或聯絡文字" /></div><button className="secondary-button" disabled={loading}>搜尋行政可用司機</button><button type="button" className="text-button" disabled={loading} onClick={() => setAttempt((value) => value + 1)}>重新載入行政選項</button></form><ErrorNotice error={error ?? loadError} />{loading ? <p role="status">正在載入行政可用選項…</p> : loadError ? <p className="driver-muted">選項尚未載入，請重新載入。</p> : <>{data?.content.length === 0 ? <p className="empty-state">目前沒有符合條件的行政可用司機。</p> : <ul className="driver-options-list">{data?.content.map((driver) => <li key={driver.id}><strong>{driver.name}</strong><span>{driver.driverCode} · {driver.licenseTypeLabel ?? '駕照類別未提供'}</span>{driver.licenseType && !driver.licenseTypeEnabled && <small className="driver-muted">駕照類別已封存；仍保留行政可用選項。</small>}{driver.licenseDueDate && <small className="driver-muted">記錄期限 <time dateTime={driver.licenseDueDate}>{driver.licenseDueDate}</time></small>}</li>)}</ul>}{data && <Pager label="行政可用司機選項分頁" {...data} disabled={false} onPage={setPage} />}</>}</section>
}
