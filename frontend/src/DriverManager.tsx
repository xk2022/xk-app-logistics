import { useCallback, useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from './api'
import type { Account } from './api'
import { ErrorNotice } from './AuthForms'
import { Pager } from './AdmShared'
import { useAdmPage } from './useAdmPage'
import { getDriver, getDriverLicenseTypes, getDrivers } from './driverApi'
import type { Driver, DriverLicenseType, DriverQuery } from './driverApi'
import { dutyLabels, employmentLabels, licenseDateHint, stripDriverText } from './driverModel'
import DriverForm from './DriverForm'
import DriverOptions from './DriverOptions'
import './driver.css'

export interface DriverManagerProps { account: Account; onExpired: () => void; onDirtyChange?: (dirty: boolean) => void; onBusyChange?: (busy: boolean) => void }
type View = { kind: 'list' } | { kind: 'create' } | { kind: 'detail'; id: string }
function DriverList({ query, page, revision, onPage, onOpen, onType, onExpired }: { query: DriverQuery; page: number; revision: number; onPage: (page: number) => void; onOpen: (id: string) => void; onType: (type: DriverLicenseType) => void; onExpired: () => void }) {
  const load = useCallback((index: number, signal?: AbortSignal) => getDrivers(index, query, signal), [query])
  const { loading, data, error } = useAdmPage(load, page, revision, onExpired)
  return <section className="card driver-list-card" aria-labelledby="driver-list-heading"><div className="driver-list-heading"><h2 id="driver-list-heading">司機列表</h2>{data && <p role="status">共 {data.totalElements} 筆司機</p>}</div><ErrorNotice error={error} />{loading ? <p role="status">正在載入司機列表…</p> : error ? <p className="driver-muted">列表尚未載入，請重新載入司機列表。</p> : <>{data?.content.length === 0 ? <p className="empty-state">尚無符合條件的司機。可調整查詢，或新增未綁定帳號的司機主檔。</p> : <div className="table-scroll"><table className="driver-table"><caption className="sr-only">公司司機列表</caption><thead><tr><th scope="col">姓名／編號</th><th scope="col">聯絡方式</th><th scope="col">在職／出勤</th><th scope="col">駕照類別／期限</th><th scope="col">登入帳號</th><th scope="col">操作</th></tr></thead><tbody>{data?.content.map((driver) => { const hint = licenseDateHint(driver.licenseDueDate); return <tr key={driver.id}><th scope="row"><span>{driver.name}</span><small>{driver.driverCode}</small></th><td>{driver.phone ?? '電話未提供'}<small>{driver.email ?? '電子郵件未提供'}</small></td><td><span className={`driver-status driver-status-${driver.employmentStatus}`}>{employmentLabels[driver.employmentStatus]}</span><small>{dutyLabels[driver.dutyStatus]}</small></td><td>{driver.licenseTypeLabel ?? '未提供'}{driver.licenseType && !driver.licenseTypeEnabled && <small>駕照類別已封存</small>}{driver.licenseDueDate && <small><time dateTime={driver.licenseDueDate}>{driver.licenseDueDate}</time></small>}{hint && <small className="driver-date-note">{hint}</small>}</td><td>{driver.accountId ? <>{driver.accountDisplayName}<small>{driver.accountUsername}</small>{!driver.accountBindingValid && <small>原綁定目前無法讀取本人資料</small>}</> : '未綁定'}</td><td><div className="driver-actions"><button className="text-button" aria-label={`檢視司機 ${driver.driverCode}`} onClick={() => onOpen(driver.id)}>明細／編輯</button>{driver.licenseType && <button className="text-button" aria-label={`篩選駕照類別 ${driver.licenseTypeLabel}`} onClick={() => onType({ id: driver.licenseType!, code: driver.licenseTypeCode!, label: driver.licenseTypeLabel! })}>只看此類別</button>}</div></td></tr> })}</tbody></table></div>}{data && <Pager label="司機列表分頁" {...data} disabled={false} onPage={onPage} />}</>}<p className="driver-muted">列表不含證照參考說明。期限僅作記錄提示，不判定資格或阻擋行政選項。</p></section>
}
function DriverWorkspace({ account, onExpired, onDirtyChange, onBusyChange }: DriverManagerProps) {
  const [view, setView] = useState<View>({ kind: 'list' })
  const [q, setQ] = useState('')
  const [employment, setEmployment] = useState<NonNullable<DriverQuery['employmentStatus']>>('all')
  const [duty, setDuty] = useState<NonNullable<DriverQuery['dutyStatus']>>('all')
  const [type, setType] = useState('')
  const [historicalType, setHistoricalType] = useState<DriverLicenseType | null>(null)
  const [size, setSize] = useState(20)
  const [query, setQuery] = useState<DriverQuery>({ q: '', size: 20 })
  const [page, setPage] = useState(0)
  const [revision, setRevision] = useState(0)
  const [typesRevision, setTypesRevision] = useState(0)
  const [typesState, setTypesState] = useState<{ revision: number; data?: DriverLicenseType[]; error?: unknown }>()
  const [detailRevision, setDetailRevision] = useState(0)
  const [detail, setDetail] = useState<{ key: string; driver?: Driver; error?: unknown }>()
  const [optionsOpen, setOptionsOpen] = useState(false)
  const [notice, setNotice] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [leaving, setLeaving] = useState(false)
  const [busy, setBusy] = useState(false)
  const dirty = useRef(false)
  const busyCallback = useRef(onBusyChange)
  const id = view.kind === 'detail' ? view.id : null
  const detailKey = `${id}/${detailRevision}`
  useEffect(() => { busyCallback.current = onBusyChange }, [onBusyChange])
  useEffect(() => () => busyCallback.current?.(false), [])
  useEffect(() => {
    const controller = new AbortController(); let active = true
    void getDriverLicenseTypes(controller.signal).then((data) => { if (active) setTypesState({ revision: typesRevision, data }) }).catch((failure: unknown) => { if (active) { setTypesState({ revision: typesRevision, error: failure }); if (failure instanceof ApiError && failure.status === 401) onExpired() } })
    return () => { active = false; controller.abort() }
  }, [typesRevision, onExpired])
  useEffect(() => {
    if (!id) return
    const controller = new AbortController(); let active = true
    void getDriver(id, controller.signal).then((driver) => { if (active) setDetail({ key: detailKey, driver }) }).catch((failure: unknown) => { if (active) { setDetail({ key: detailKey, error: failure }); if (failure instanceof ApiError && failure.status === 401) onExpired() } })
    return () => { active = false; controller.abort() }
  }, [id, detailKey, onExpired])
  const updateDirty = useCallback((value: boolean) => { dirty.current = value; if (!value) setLeaving(false); onDirtyChange?.(value) }, [onDirtyChange])
  const updateBusy = useCallback((value: boolean) => { setBusy(value); busyCallback.current?.(value) }, [])
  const toList = () => { if (busy) return; updateDirty(false); setLeaving(false); setView({ kind: 'list' }); setRevision((value) => value + 1) }
  const requestList = () => { if (busy) return; if (dirty.current) setLeaving(true); else toList() }
  const search = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); const normalized = stripDriverText(q)
    if ([...normalized].length > 100) { setError(new ApiError(400, '司機搜尋最多 100 字元。')); return }
    setQuery({ q: normalized, size, ...(employment !== 'all' ? { employmentStatus: employment } : {}), ...(duty !== 'all' ? { dutyStatus: duty } : {}), ...(type ? { licenseType: type } : {}) }); setPage(0); setRevision((value) => value + 1); setError(null)
  }
  const currentTypes = typesState?.revision === typesRevision
  const types = currentTypes ? typesState.data ?? [] : []
  const typesError = currentTypes ? typesState.error : undefined
  const currentDetail = detail?.key === detailKey ? detail : undefined
  const formProps = { role: account.role, types, typesLoading: !currentTypes, typesError, onReloadTypes: () => setTypesRevision((value) => value + 1), onExpired, onCancel: requestList, onDirtyChange: updateDirty, onBusyChange: updateBusy, onSaved: (driver: Driver) => setNotice(`已確認司機 ${driver.driverCode}，版本 ${driver.version}。`) }
  if (view.kind !== 'list') return <section className="driver-workspace"><div className="section-title"><div><p className="eyebrow">FLEET MANAGEMENT / DRIVER</p><h1>司機管理</h1></div></div>{leaving && <section className="driver-confirm" role="alert" aria-label="確認離開司機"><h2>離開前請確認</h2><p>尚有未保存或未確認結果的司機內容。離開後，本機內容會清除。</p><div className="driver-actions"><button className="secondary-button" disabled={busy} onClick={() => { if (!busy) setLeaving(false) }}>繼續編輯司機</button><button className="secondary-button" disabled={busy} onClick={toList}>捨棄本機司機內容並返回列表</button></div></section>}{view.kind === 'create' ? <DriverForm key="new-driver" {...formProps} /> : !currentDetail ? <section className="card"><p role="status">正在載入司機明細…</p><button className="secondary-button" onClick={requestList}>返回司機列表</button></section> : currentDetail.error ? <section className="card"><ErrorNotice error={currentDetail.error} /><div className="driver-actions"><button className="secondary-button" onClick={() => setDetailRevision((value) => value + 1)}>重新讀取司機明細</button><button className="secondary-button" onClick={requestList}>返回司機列表</button></div></section> : <DriverForm key={view.id} driver={currentDetail.driver} {...formProps} />}</section>
  return <section className="driver-workspace"><div className="section-title driver-heading"><div><p className="eyebrow">FLEET MANAGEMENT / DRIVER</p><h1>司機管理</h1><p className="page-description">維護本公司司機主檔、行政狀態與選擇性登入關聯。</p></div><button className="primary-button" onClick={() => { setView({ kind: 'create' }); setNotice('') }}>新增司機</button></div><aside className="driver-scope"><strong>在職、出勤、帳號與派工各自分開</strong><p>可管理未綁定帳號的司機。行政可用不代表可派工或法規資格；本頁不建立使用中、任務占用或帳號生命週期變更。</p></aside>{notice && <p className="success-notice" role="status">{notice}</p>}<ErrorNotice error={error} />
    <section className="card driver-filter" aria-labelledby="driver-query-heading"><div className="driver-filter-heading"><h2 id="driver-query-heading">查詢司機</h2><button className="text-button" onClick={() => { setRevision((value) => value + 1); setTypesRevision((value) => value + 1) }}>重新載入司機列表</button></div><form className="driver-query" aria-label="司機查詢" onSubmit={search}><div><label htmlFor="driver-filter-q">司機搜尋</label><input id="driver-filter-q" autoComplete="off" value={q} onChange={(event) => setQ(event.target.value)} placeholder="字面搜尋編號、姓名、電話或電子郵件" /></div><div><label htmlFor="driver-filter-employment">在職狀態篩選</label><select id="driver-filter-employment" value={employment} onChange={(event) => setEmployment(event.target.value as typeof employment)}><option value="all">全部在職狀態</option>{Object.entries(employmentLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></div><div><label htmlFor="driver-filter-duty">出勤狀態篩選</label><select id="driver-filter-duty" value={duty} onChange={(event) => setDuty(event.target.value as typeof duty)}><option value="all">全部出勤狀態</option>{Object.entries(dutyLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></div><div><label htmlFor="driver-filter-license">駕照類別篩選</label><select id="driver-filter-license" value={type} onChange={(event) => { setType(event.target.value); const selected = types.find((item) => item.id === event.target.value); if (selected) setHistoricalType(selected) }}><option value="">全部駕照類別</option>{historicalType && !types.some((item) => item.id === historicalType.id) && <option value={historicalType.id}>{historicalType.label}（歷史類別）</option>}{types.map((item) => <option value={item.id} key={item.id}>{item.label} · {item.code}</option>)}</select></div><div><label htmlFor="driver-filter-size">每頁筆數</label><select id="driver-filter-size" value={size} onChange={(event) => setSize(Number(event.target.value))}><option value="5">5</option><option value="20">20</option><option value="50">50</option></select></div><button className="primary-button">搜尋司機</button></form><ErrorNotice error={typesError} />{!currentTypes ? <p className="driver-muted" role="status">正在載入駕照類別篩選…</p> : !typesError && types.length === 0 && <p className="driver-muted">尚無核准駕照類別；不會因此阻擋建立未提供類別的主檔。{account.role === 'ADMIN' ? '可至系統管理維護經核准分類。' : '如需分類請聯絡管理員。'}</p>}</section>
    <DriverList query={query} page={page} revision={revision} onPage={setPage} onExpired={onExpired} onOpen={(driverId) => { setView({ kind: 'detail', id: driverId }); setDetailRevision((value) => value + 1); setNotice('') }} onType={(selected) => { setType(selected.id); setHistoricalType(selected); setQuery((current) => ({ ...current, licenseType: selected.id })); setPage(0) }} /><div className="driver-options-toggle"><button className="text-button" aria-expanded={optionsOpen} onClick={() => setOptionsOpen((value) => !value)}>{optionsOpen ? '收合行政可用司機選項' : '查看行政可用司機選項'}</button></div>{optionsOpen && <DriverOptions revision={revision} onExpired={onExpired} />}</section>
}
export default function DriverManager(props: DriverManagerProps) {
  if (props.account.role === 'DRIVER' || props.account.mustChangePassword) return <section className="card"><h1>無法使用司機管理</h1><p>請依目前角色及登入狀態使用已授權功能。</p></section>
  return <DriverWorkspace {...props} />
}
