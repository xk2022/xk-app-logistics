import { useCallback, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from './api'
import { ErrorNotice } from './AuthForms'
import { Pager } from './AdmShared'
import { useAdmPage } from './useAdmPage'
import { getVehicleOptions } from './vehicleApi'
import { stripVehicleText } from './vehicleModel'

export default function VehicleOptions({ revision, onExpired }: { revision: number; onExpired: () => void }) {
  const [text, setText] = useState('')
  const [q, setQ] = useState('')
  const [page, setPage] = useState(0)
  const [attempt, setAttempt] = useState(0)
  const [error, setError] = useState<unknown>(null)
  const load = useCallback((index: number, signal?: AbortSignal) => getVehicleOptions(index, { q, size: 5 }, signal), [q])
  const { loading, data, error: loadError } = useAdmPage(load, page, revision + attempt, onExpired)
  const search = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const normalized = stripVehicleText(text)
    if ([...normalized].length > 100) { setError(new ApiError(400, '可用車輛搜尋最多 100 字元。')); return }
    setQ(normalized); setPage(0); setAttempt((value) => value + 1); setError(null)
  }
  return <section className="card vehicle-options" aria-label="主檔可用車輛選項"><h2>主檔可用車輛選項</h2><p className="vehicle-muted">只依管理狀態「可用」列出。車種停用或檢驗日期不會額外排除；這不代表實際可派工，也不檢查或保留占用。</p><form className="vehicle-options-query" onSubmit={search}><div><label htmlFor="vehicle-option-search">可用車輛搜尋</label><input id="vehicle-option-search" autoComplete="off" value={text} onChange={(event) => setText(event.target.value)} placeholder="字面搜尋車牌或車籍編號" /></div><button className="secondary-button" disabled={loading}>搜尋可用車輛</button><button type="button" className="text-button" disabled={loading} onClick={() => setAttempt((value) => value + 1)}>重新載入可用選項</button></form><ErrorNotice error={error ?? loadError} />
    {loading ? <p role="status">正在載入主檔可用選項…</p> : loadError ? <p className="vehicle-muted">選項尚未載入，請重新載入。</p> : <>{data?.content.length === 0 ? <p className="empty-state">目前沒有符合條件的可用車輛。</p> : <ul className="vehicle-options-list">{data?.content.map((vehicle) => <li key={vehicle.id}><strong>{vehicle.plateNo}</strong><span>{vehicle.vehicleTypeLabel} · {vehicle.capacityTon === null ? '載重未知' : `${vehicle.capacityTon} 噸`}</span>{!vehicle.vehicleTypeEnabled && <small className="vehicle-muted">車種已封存；仍保留主檔可用選項。</small>}</li>)}</ul>}{data && <Pager label="可用車輛選項分頁" {...data} disabled={loading} onPage={setPage} />}</>}
  </section>
}
