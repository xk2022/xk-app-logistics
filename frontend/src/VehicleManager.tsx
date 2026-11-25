import { useCallback, useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from './api'
import type { Account } from './api'
import { ErrorNotice } from './AuthForms'
import { Pager } from './AdmShared'
import { useAdmPage } from './useAdmPage'
import { getVehicle, getVehicles, getVehicleTypes } from './vehicleApi'
import type { Vehicle, VehicleQuery, VehicleTypeOption } from './vehicleApi'
import { inspectionHint, stripVehicleText, vehicleStatusLabels } from './vehicleModel'
import VehicleForm from './VehicleForm'
import VehicleOptions from './VehicleOptions'
import './vehicle.css'

export interface VehicleManagerProps { account: Account; onExpired: () => void; onDirtyChange?: (dirty: boolean) => void; onBusyChange?: (busy: boolean) => void }
type View = { kind: 'list' } | { kind: 'create' } | { kind: 'detail'; id: string }

function VehicleList({ query, page, revision, onPage, onOpen, onType, onExpired }: { query: VehicleQuery; page: number; revision: number; onPage: (page: number) => void; onOpen: (id: string) => void; onType: (type: VehicleTypeOption) => void; onExpired: () => void }) {
  const load = useCallback((index: number, signal?: AbortSignal) => getVehicles(index, query, signal), [query])
  const { loading, data, error } = useAdmPage(load, page, revision, onExpired)
  return <section className="card vehicle-list-card" aria-labelledby="vehicle-list-heading"><div className="vehicle-list-heading"><h2 id="vehicle-list-heading">車輛列表</h2>{data && <p role="status">共 {data.totalElements} 筆車輛</p>}</div><ErrorNotice error={error} />
    {loading ? <p role="status">正在載入車輛列表…</p> : error ? <p className="vehicle-muted">列表尚未載入，請重新載入車輛列表。</p> : <>{data?.content.length === 0 ? <p className="empty-state">尚無符合條件的車輛。可調整查詢，或新增車輛；沒有預設車籍資料。</p> : <div className="table-scroll"><table className="vehicle-table"><caption className="sr-only">公司車輛列表</caption><thead><tr><th scope="col">車牌／車籍</th><th scope="col">車種／載重</th><th scope="col">管理狀態</th><th scope="col">檢驗期限</th><th scope="col">操作</th></tr></thead><tbody>{data?.content.map((vehicle) => { const hint = inspectionHint(vehicle.inspectionDueDate); return <tr key={vehicle.id}><th scope="row"><span className="vehicle-plate">{vehicle.plateNo}</span><small>{vehicle.registrationNo ?? '車籍編號未提供'}</small></th><td><span>{vehicle.vehicleTypeLabel}</span>{!vehicle.vehicleTypeEnabled && <small>車種已封存</small>}<small>{vehicle.capacityTon === null ? '載重未知' : `${vehicle.capacityTon} 噸`}</small></td><td><span className={`vehicle-status vehicle-status-${vehicle.administrativeStatus}`}>{vehicleStatusLabels[vehicle.administrativeStatus]}</span></td><td>{vehicle.inspectionDueDate ? <time className="vehicle-date" dateTime={vehicle.inspectionDueDate}>{vehicle.inspectionDueDate}</time> : '未提供'}{hint && <span className="vehicle-inspection-note">{hint}</span>}</td><td><div className="vehicle-actions"><button className="text-button" aria-label={`檢視車輛 ${vehicle.plateNo}`} onClick={() => onOpen(vehicle.id)}>明細／編輯</button><button className="text-button" aria-label={`篩選車種 ${vehicle.vehicleTypeLabel}`} onClick={() => onType({ id: vehicle.vehicleType, code: vehicle.vehicleTypeCode, label: vehicle.vehicleTypeLabel })}>只看此車種</button></div></td></tr> })}</tbody></table></div>}{data && <Pager label="車輛列表分頁" {...data} disabled={false} onPage={onPage} />}</>}
    <p className="vehicle-muted">檢驗期限僅作自然日記錄及提示，不判定法規合規或限制派工。</p>
  </section>
}

function VehicleWorkspace({ account, onExpired, onDirtyChange, onBusyChange }: VehicleManagerProps) {
  const [view, setView] = useState<View>({ kind: 'list' })
  const [q, setQ] = useState('')
  const [type, setType] = useState('')
  const [historicalType, setHistoricalType] = useState<VehicleTypeOption | null>(null)
  const [status, setStatus] = useState<NonNullable<VehicleQuery['administrativeStatus']>>('all')
  const [size, setSize] = useState(20)
  const [query, setQuery] = useState<VehicleQuery>({ q: '', size: 20 })
  const [page, setPage] = useState(0)
  const [revision, setRevision] = useState(0)
  const [typesRevision, setTypesRevision] = useState(0)
  const [typesState, setTypesState] = useState<{ revision: number; data?: VehicleTypeOption[]; error?: unknown }>()
  const [detailRevision, setDetailRevision] = useState(0)
  const [detail, setDetail] = useState<{ key: string; vehicle?: Vehicle; error?: unknown }>()
  const [optionsOpen, setOptionsOpen] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState('')
  const [leaving, setLeaving] = useState(false)
  const [writing, setWriting] = useState(false)
  const dirty = useRef(false)
  const busyCallback = useRef(onBusyChange)
  const id = view.kind === 'detail' ? view.id : null
  const detailKey = `${id}/${detailRevision}`
  useEffect(() => { busyCallback.current = onBusyChange }, [onBusyChange])
  useEffect(() => () => busyCallback.current?.(false), [])
  useEffect(() => {
    const controller = new AbortController(); let active = true
    void getVehicleTypes(controller.signal).then((data) => { if (active) setTypesState({ revision: typesRevision, data }) })
      .catch((failure: unknown) => { if (active) { setTypesState({ revision: typesRevision, error: failure }); if (failure instanceof ApiError && failure.status === 401) onExpired() } })
    return () => { active = false; controller.abort() }
  }, [typesRevision, onExpired])
  useEffect(() => {
    if (!id) return
    const controller = new AbortController(); let active = true
    void getVehicle(id, controller.signal).then((vehicle) => { if (active) setDetail({ key: detailKey, vehicle }) })
      .catch((failure: unknown) => { if (active) { setDetail({ key: detailKey, error: failure }); if (failure instanceof ApiError && failure.status === 401) onExpired() } })
    return () => { active = false; controller.abort() }
  }, [id, detailKey, onExpired])
  const updateDirty = useCallback((value: boolean) => { dirty.current = value; if (!value) setLeaving(false); onDirtyChange?.(value) }, [onDirtyChange])
  const updateBusy = useCallback((value: boolean) => { setWriting(value); busyCallback.current?.(value) }, [])
  const toList = () => { if (writing) return; updateDirty(false); setLeaving(false); setView({ kind: 'list' }); setRevision((value) => value + 1) }
  const requestList = () => { if (writing) return; if (dirty.current) setLeaving(true); else toList() }
  const search = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const normalized = stripVehicleText(q)
    if ([...normalized].length > 100) { setError(new ApiError(400, '車牌搜尋最多 100 字元。')); return }
    setQuery({ q: normalized, size, ...(type ? { vehicleType: type } : {}), ...(status !== 'all' ? { administrativeStatus: status } : {}) }); setPage(0); setRevision((value) => value + 1); setError(null)
  }
  const selectType = (selected: VehicleTypeOption) => { setHistoricalType(selected); setType(selected.id); setQuery((current) => ({ ...current, vehicleType: selected.id })); setPage(0) }
  const typesCurrent = typesState?.revision === typesRevision
  const types = typesCurrent ? typesState.data ?? [] : []
  const typesError = typesCurrent ? typesState.error : undefined
  const currentDetail = detail?.key === detailKey ? detail : undefined
  if (view.kind !== 'list') return <section className="vehicle-workspace"><div className="section-title"><div><p className="eyebrow">FLEET MANAGEMENT / VEHICLE</p><h1>車輛管理</h1></div></div>{leaving && <section className="vehicle-confirm" role="alert" aria-label="確認離開車輛"><h2>離開前請確認</h2><p>尚有未保存或未確認結果的車輛內容。離開後，本機內容會清除。</p><div className="vehicle-actions"><button className="secondary-button" disabled={writing} onClick={() => { if (!writing) setLeaving(false) }}>繼續編輯車輛</button><button className="secondary-button" disabled={writing} onClick={toList}>捨棄本機車輛內容並返回列表</button></div></section>}
    {view.kind === 'create' ? <VehicleForm key="new-vehicle" role={account.role} types={types} typesLoading={!typesCurrent} typesError={typesError} onReloadTypes={() => setTypesRevision((value) => value + 1)} onExpired={onExpired} onCancel={requestList} onDirtyChange={updateDirty} onBusyChange={updateBusy} onSaved={(vehicle) => setNotice(`已確認車輛 ${vehicle.plateNo}，版本 ${vehicle.version}。`)} /> : !currentDetail ? <section className="card"><p role="status">正在載入車輛明細…</p><button className="secondary-button" onClick={requestList}>返回車輛列表</button></section> : currentDetail.error ? <section className="card"><ErrorNotice error={currentDetail.error} /><div className="vehicle-actions"><button className="secondary-button" onClick={() => setDetailRevision((value) => value + 1)}>重新讀取車輛明細</button><button className="secondary-button" onClick={requestList}>返回車輛列表</button></div></section> : <VehicleForm key={view.id} vehicle={currentDetail.vehicle} role={account.role} types={types} typesLoading={!typesCurrent} typesError={typesError} onReloadTypes={() => setTypesRevision((value) => value + 1)} onExpired={onExpired} onCancel={requestList} onDirtyChange={updateDirty} onBusyChange={updateBusy} onSaved={(vehicle) => setNotice(`已確認車輛 ${vehicle.plateNo}，版本 ${vehicle.version}。`)} />}
  </section>
  return <section className="vehicle-workspace"><div className="section-title vehicle-heading"><div><p className="eyebrow">FLEET MANAGEMENT / VEHICLE</p><h1>車輛管理</h1><p className="page-description">維護本公司車輛、車籍及管理狀態，保留穩定識別與歷史資料。</p></div><div className="vehicle-heading-actions"><button className="primary-button" onClick={() => { setView({ kind: 'create' }); setNotice('') }}>新增車輛</button></div></div>
    <aside className="vehicle-scope"><svg width="25" height="25" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true"><path d="M3 7h11v10H3zM14 10h4l3 3v4h-7M6 7V4h5v3" strokeLinejoin="round" /><circle cx="7" cy="18" r="2" /><circle cx="18" cy="18" r="2" /></svg><div><strong>主檔狀態與實際派工分開</strong><p>本頁的可用、維修、停用為人工管理狀態。使用中須由後續派工占用推導；目前沒有派工、預約或司機配對。</p></div></aside>
    {notice && <p className="success-notice" role="status">{notice}</p>}<ErrorNotice error={error} />
    <section className="card vehicle-filter" aria-labelledby="vehicle-query-heading"><div className="vehicle-filter-heading"><h2 id="vehicle-query-heading">查詢車輛</h2><button className="text-button" onClick={() => { setRevision((value) => value + 1); setTypesRevision((value) => value + 1) }}>重新載入車輛列表</button></div><form className="vehicle-query" aria-label="車輛查詢" onSubmit={search}><div><label htmlFor="vehicle-filter-q">車牌搜尋</label><input id="vehicle-filter-q" value={q} autoComplete="off" placeholder="字面搜尋車牌或車籍編號" onChange={(event) => setQ(event.target.value)} /></div><div><label htmlFor="vehicle-filter-type">車種篩選</label><select id="vehicle-filter-type" value={type} onChange={(event) => { const selected = types.find((item) => item.id === event.target.value); setType(event.target.value); if (selected) setHistoricalType(selected) }}><option value="">全部車種</option>{historicalType && !types.some((item) => item.id === historicalType.id) && <option value={historicalType.id}>{historicalType.label}（歷史車種）</option>}{types.map((item) => <option value={item.id} key={item.id}>{item.label} · {item.code}</option>)}</select></div><div><label htmlFor="vehicle-filter-status">管理狀態篩選</label><select id="vehicle-filter-status" value={status} onChange={(event) => setStatus(event.target.value as typeof status)}><option value="all">全部管理狀態</option>{Object.entries(vehicleStatusLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></div><div><label htmlFor="vehicle-filter-size">每頁筆數</label><select id="vehicle-filter-size" value={size} onChange={(event) => setSize(Number(event.target.value))}><option value="5">5</option><option value="20">20</option><option value="50">50</option></select></div><button className="primary-button">搜尋車輛</button></form><ErrorNotice error={typesError} />{!typesCurrent ? <p role="status" className="vehicle-muted">正在載入車種篩選…</p> : !typesError && types.length === 0 && <p className="vehicle-muted">尚無可供新引用的核准車種。{account.role === 'ADMIN' ? '請至系統管理建立經核准的 VEHICLE_TYPE 字典項目。' : '請聯絡管理員設定經核准車種。'}歷史車輛仍可查詢及維護。</p>}</section>
    <VehicleList query={query} page={page} revision={revision} onPage={setPage} onExpired={onExpired} onType={selectType} onOpen={(vehicleId) => { setView({ kind: 'detail', id: vehicleId }); setDetailRevision((value) => value + 1); setNotice('') }} />
    <div className="vehicle-options-toggle"><button className="text-button" aria-expanded={optionsOpen} onClick={() => setOptionsOpen((value) => !value)}>{optionsOpen ? '收合主檔可用選項' : '查看主檔可用選項'}</button></div>{optionsOpen && <VehicleOptions revision={revision} onExpired={onExpired} />}
  </section>
}
export default function VehicleManager(props: VehicleManagerProps) {
  if (props.account.role === 'DRIVER' || props.account.mustChangePassword) return <section className="card"><h1>無法使用車輛管理</h1><p>請依目前角色及登入狀態使用已授權功能。</p></section>
  return <VehicleWorkspace {...props} />
}
