import { useCallback, useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { TmsApiError, getTaskDispatch, getTmsTasks } from './tmsApi'
import type { DispatchDetail, TaskQuery, TaskSummary, TmsPage, TmsWriteResult } from './tmsApi'
import { normalizeTmsText, safeExitRequired } from './tmsModel'
import DispatchCommandForm from './DispatchCommandForm'
import DispatchHistory from './DispatchHistory'
import DispatchReceipt from './DispatchReceipt'
import { AssignmentView, Blockers, PolicyNotice, RawText } from './DispatchShared'
import './dispatch.css'

export interface DispatchManagerProps { onExpired: () => void; onDirtyChange?: (dirty: boolean) => void; onBusyChange?: (busy: boolean) => void }
function TmsError({ error }: { error: unknown }) { return error ? <div className="error-notice" role="alert"><p>{error instanceof Error ? error.message : '讀取失敗，請重新確認。'}</p>{error instanceof TmsApiError && <small>錯誤代碼：{error.code}</small>}</div> : null }
function TaskList({ query, page, revision, onPage, onOpen, onOrder, onExpired }: { query: TaskQuery; page: number; revision: number; onPage: (page: number) => void; onOpen: (task: TaskSummary) => void; onOrder: (id: string, label: string) => void; onExpired: () => void }) {
  const [state, setState] = useState<{ key: string; query: TaskQuery; data?: TmsPage<TaskSummary>; error?: unknown }>()
  const key = `${page}/${revision}`
  useEffect(() => {
    const controller = new AbortController(); let active = true
    void getTmsTasks(page, query, controller.signal).then((data) => { if (active) setState({ key, query, data }) }).catch((error: unknown) => { if (active) { setState({ key, query, error }); if (safeExitRequired(error)) onExpired() } })
    return () => { active = false; controller.abort() }
  }, [query, key, page, onExpired])
  const current = state?.key === key && state.query === query ? state : undefined
  return <section className="dispatch-task-results" aria-labelledby="dispatch-list-heading"><div className="dispatch-section-heading"><h2 id="dispatch-list-heading">調度任務</h2>{current?.data && <p role="status">共 {current.data.totalElements} 筆任務</p>}</div>{!current ? <section className="card" role="status">正在載入調度任務…</section> : current.error ? <section className="card"><TmsError error={current.error} /><p className="dispatch-muted">任務列表未載入，請重新載入任務列表。</p></section> : <>{current.data?.content.length === 0 ? <section className="card empty-state"><h3>尚無符合條件的調度任務</h3><p>任務須先隨訂單正式受理；可調整查詢條件。被政策阻擋的任務仍會列出，不會被畫面隱藏。</p></section> : <ul className="dispatch-task-list">{current.data?.content.map((task) => <li key={task.taskId}><article className="card dispatch-task-card" aria-label={`調度任務 ${task.taskNo}`}><div className="dispatch-card-top"><span className="dispatch-tag">{task.currentAssignmentId ? '已有有效派工' : '待派任務'}</span><span className="dispatch-policy-tag">{task.policyMode === 'UNCONFIRMED' ? '政策未核准' : '僅供合成測試'}</span></div><h3>{task.taskNo}</h3><p className="dispatch-muted">訂單 {task.orderNo} · {task.orderType} · 來源訂單版本 {task.orderVersion}</p><div className="dispatch-route-preview"><span><RawText value={task.pickupName} /></span><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true"><path d="M5 12h14m-5-5 5 5-5 5" strokeLinecap="round" strokeLinejoin="round" /></svg><span><RawText value={task.deliveryName} /></span></div><p className="dispatch-muted">作業：<RawText value={task.taskKind} /> · 任務派工版本 {task.generation}</p><Blockers reasons={task.blockingReasons} /><div className="dispatch-actions"><button type="button" className="primary-button" aria-label={`開啟派工 ${task.taskNo}`} onClick={() => onOpen(task)}>開啟派工</button><button type="button" className="text-button" aria-label={`只看訂單 ${task.orderNo}`} onClick={() => onOrder(task.orderId, task.orderNo)}>只看此訂單</button></div></article></li>)}</ul>}{current.data && <nav className="dispatch-pager" aria-label="調度任務分頁"><span>{current.data.totalPages ? `第 ${page + 1} / ${current.data.totalPages} 頁` : '目前無資料'}</span><div><button type="button" className="secondary-button" disabled={page === 0} onClick={() => onPage(page - 1)}>上一頁任務</button><button type="button" className="secondary-button" disabled={page >= 100000 || page + 1 >= current.data.totalPages} onClick={() => onPage(page + 1)}>下一頁任務</button></div></nav>}</>}</section>
}
export default function DispatchManager({ onExpired, onDirtyChange, onBusyChange }: DispatchManagerProps) {
  const [q, setQ] = useState('')
  const [filter, setFilter] = useState<NonNullable<TaskQuery['state']>>('UNASSIGNED')
  const [size, setSize] = useState(20)
  const [orderFilter, setOrderFilter] = useState<{ id: string; label: string } | null>(null)
  const [query, setQuery] = useState<TaskQuery>({ q: '', state: 'UNASSIGNED', size: 20 })
  const [page, setPage] = useState(0)
  const [revision, setRevision] = useState(0)
  const [selected, setSelected] = useState<TaskSummary | null>(null)
  const [detail, setDetail] = useState<DispatchDetail | null>(null)
  const [loadError, setLoadError] = useState<unknown>(null)
  const [error, setError] = useState<unknown>(null)
  const [loading, setLoading] = useState(false)
  const [reading, setReading] = useState(false)
  const [remote, setRemote] = useState<DispatchDetail | null>(null)
  const [epoch, setEpoch] = useState(0)
  const [receipt, setReceipt] = useState<TmsWriteResult | null>(null)
  const [needsRefresh, setNeedsRefresh] = useState(false)
  const [knownStale, setKnownStale] = useState(false)
  const [dirty, setDirty] = useState(false)
  const [formBusy, setFormBusy] = useState(false)
  const [pending, setPending] = useState(false)
  const [leaving, setLeaving] = useState(false)
  const callbacks = useRef({ onDirtyChange, onBusyChange })
  const active = useRef(true)
  const readController = useRef<AbortController | null>(null)
  const readingRef = useRef(false)
  const formBusyRef = useRef(false)
  useEffect(() => { callbacks.current = { onDirtyChange, onBusyChange } }, [onDirtyChange, onBusyChange])
  useEffect(() => { active.current = true; return () => { active.current = false; readController.current?.abort(); callbacks.current.onDirtyChange?.(false); callbacks.current.onBusyChange?.(false) } }, [])
  const reportDirty = useCallback((value: boolean) => { setDirty(value); if (!value) setLeaving(false); else setReceipt(null); callbacks.current.onDirtyChange?.(value) }, [])
  const reportBusy = useCallback((value: boolean) => { formBusyRef.current = value; setFormBusy(value); callbacks.current.onBusyChange?.(value || readingRef.current) }, [])
  const reportPending = useCallback((value: boolean) => setPending(value), [])
  const adopt = (fresh: DispatchDetail) => { setDetail(fresh); setSelected(fresh.task); setRemote(null); setNeedsRefresh(false); setKnownStale(false); setEpoch((value) => value + 1); reportDirty(false); reportPending(false) }
  const open = (task: TaskSummary) => { setSelected(task); setDetail(null); setLoadError(null); setReceipt(null); setRemote(null); setNeedsRefresh(false); setKnownStale(false); setLoading(true) }
  const selectedId = selected?.taskId
  useEffect(() => {
    if (!selectedId) return
    const controller = new AbortController(); let current = true
    void getTaskDispatch(selectedId, { historyPage: 0, historySize: 5 }, controller.signal).then((fresh) => { if (current) { setDetail(fresh); setSelected(fresh.task); setLoading(false); setEpoch((value) => value + 1) } }).catch((failure: unknown) => { if (current) { setLoadError(failure); setLoading(false); if (safeExitRequired(failure)) onExpired() } })
    return () => { current = false; controller.abort() }
  }, [selectedId, onExpired])
  const toList = () => { if (formBusy || reading) return; reportDirty(false); setPending(false); setLeaving(false); setSelected(null); setDetail(null); setReceipt(null); setRemote(null); setRevision((value) => value + 1) }
  const requestList = () => { if (formBusy || reading) return; if (dirty || pending) setLeaving(true); else toList() }
  const readFresh = async () => {
    if (!selectedId || formBusy || readingRef.current || pending) return
    readController.current?.abort(); const controller = new AbortController(); readController.current = controller
    readingRef.current = true; setReading(true); callbacks.current.onBusyChange?.(true); setError(null); setRemote(null)
    try { const fresh = await getTaskDispatch(selectedId, { historyPage: 0, historySize: 5 }, controller.signal); if (!active.current || controller.signal.aborted) return; if (dirty) setRemote(fresh); else adopt(fresh) }
    catch (failure) { if (active.current && !controller.signal.aborted) { setError(failure); if (safeExitRequired(failure)) onExpired() } }
    finally { if (active.current && readController.current === controller) { readingRef.current = false; setReading(false); callbacks.current.onBusyChange?.(formBusyRef.current) } }
  }
  const stale = useCallback(() => setKnownStale(true), [])
  const search = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); const text = normalizeTmsText(q)
    if ([...text].length > 100) { setError(new TmsApiError(400, 'E0400', '任務搜尋最多 100 字元。')); return }
    setError(null); setQuery({ q: text, state: filter, size, ...(orderFilter ? { orderId: orderFilter.id } : {}) }); setPage(0); setRevision((value) => value + 1)
  }
  const completed = (result: TmsWriteResult) => { setReceipt(result); setNeedsRefresh(true); setRemote(null); reportDirty(false); reportPending(false) }
  if (selected) return <section className="dispatch-workspace"><div className="section-title dispatch-heading"><div><p className="eyebrow">TRANSPORT MANAGEMENT / DISPATCH</p><h1>調度工作台</h1><p className="page-description">任務 {selected.taskNo} · 訂單 {selected.orderNo}</p></div><div className="dispatch-actions"><button type="button" className="secondary-button" disabled={formBusy || reading} onClick={requestList}>返回調度列表</button><button type="button" className="primary-button" disabled={formBusy || reading || pending || loading} onClick={() => { void readFresh() }}>{reading ? '正在讀取目前派工…' : '重新讀取目前派工'}</button></div></div>
    {leaving && <section className="dispatch-warning" role="alert" aria-label="確認離開調度"><h2>離開前請確認</h2><p>尚有本機操作或未知結果；原請求可能已成功。離開會清除選擇、原因與原次重試資訊，並不取消伺服器操作。</p><div className="dispatch-actions"><button type="button" className="secondary-button" disabled={formBusy || reading} onClick={() => setLeaving(false)}>繼續處理派工</button><button type="button" className="secondary-button" disabled={formBusy || reading} onClick={toList}>捨棄本機操作並返回列表</button></div></section>}
    <TmsError error={error} />{remote && <section className="dispatch-warning" role="group" aria-label="採用最新派工確認"><h2>最新派工已讀取，尚未覆蓋本機內容</h2><p>伺服器任務版本 {remote.task.generation}，{remote.current ? '有有效派工' : '目前無有效派工'}。採用會捨棄本機未保存選擇及原因；不會自動送出下一操作。</p><div className="dispatch-actions"><button type="button" className="secondary-button" disabled={formBusy || reading || pending} onClick={() => { if (!formBusy && !reading && !pending) adopt(remote) }}>採用最新派工資料</button><button type="button" className="text-button" disabled={formBusy || reading || pending} onClick={() => setRemote(null)}>保留本機操作內容</button></div></section>}
    {loading ? <section className="card" role="status">正在載入派工明細…</section> : loadError && !detail ? <section className="card"><TmsError error={loadError} /><p>請返回列表後重新開啟，或重新讀取目前派工。</p></section> : detail && <><PolicyNotice mode={detail.task.policyMode} />{receipt && <DispatchReceipt result={receipt} />}{needsRefresh ? <section className="card dispatch-refresh-required" role="status"><h2>目前派工尚未重新讀取</h2><p>收據是原次操作快照，不能當作目前占用或下一次版本。請使用「重新讀取目前派工」確認最新資料。</p></section> : <><section className="card dispatch-detail-summary" aria-labelledby="dispatch-detail-heading"><h2 id="dispatch-detail-heading">任務與當前派工</h2><dl className="dispatch-data-grid"><div><dt>任務單號</dt><dd>{detail.task.taskNo}</dd></div><div><dt>來源訂單</dt><dd>{detail.task.orderNo}</dd></div><div><dt>任務派工版本</dt><dd>{detail.task.generation}</dd></div><div><dt>作業類型</dt><dd><RawText value={detail.task.taskKind} /></dd></div><div><dt>起點名稱</dt><dd><RawText value={detail.task.pickupName} /></dd></div><div><dt>終點名稱</dt><dd><RawText value={detail.task.deliveryName} /></dd></div></dl><Blockers reasons={detail.task.blockingReasons} />{detail.current ? <section aria-label="目前有效派工"><h2>目前有效派工</h2><AssignmentView assignment={detail.current} /></section> : <p className="dispatch-empty">目前沒有有效派工。</p>}</section>{knownStale && <p className="dispatch-warning" role="alert">歷史讀取發現任務版本已變更。請明確重新讀取目前派工，再操作。</p>}<section className="card"><DispatchCommandForm key={`form/${detail.task.taskId}/${epoch}`} detail={detail} disabled={reading} stale={knownStale} onExpired={onExpired} onCompleted={completed} onDirtyChange={reportDirty} onBusyChange={reportBusy} onPendingChange={reportPending} /></section><section className="card"><DispatchHistory key={`history/${detail.task.taskId}/${epoch}`} taskId={detail.task.taskId} generation={detail.task.generation} initial={detail.history} disabled={formBusy || reading || dirty || pending} onExpired={onExpired} onStale={stale} /></section></>}</>}
  </section>
  return <section className="dispatch-workspace"><div className="section-title dispatch-heading"><div><p className="eyebrow">TRANSPORT MANAGEMENT / DISPATCH</p><h1>調度工作台</h1><p className="page-description">逐段維護指派、撤派及改派；保留來源快照、歷史與排他占用。</p></div><button type="button" className="secondary-button" onClick={() => setRevision((value) => value + 1)}>重新載入任務列表</button></div><aside className="dispatch-scope"><strong>候選提示不等於正式適格</strong><p>正常服務的派工政策尚未核准，禁止新指派／改派。正向工程驗收僅使用明示合成測試政策，不提供出發、回報、照片、預約或自動釋放。</p></aside><TmsError error={error} /><section className="card dispatch-query-card"><h2>查詢調度任務</h2><form className="dispatch-query" aria-label="調度任務查詢" onSubmit={search}><div><label htmlFor="dispatch-search">任務搜尋</label><input id="dispatch-search" autoComplete="off" value={q} onChange={(event) => setQ(event.target.value)} placeholder="字面搜尋任務或訂單單號、起終點" /></div><div><label htmlFor="dispatch-filter-state">任務狀態篩選</label><select id="dispatch-filter-state" value={filter} onChange={(event) => setFilter(event.target.value as typeof filter)}><option value="UNASSIGNED">待派任務</option><option value="ASSIGNED">已有有效派工</option><option value="ALL">全部任務</option></select></div><div><label htmlFor="dispatch-page-size">每頁任務筆數</label><select id="dispatch-page-size" value={size} onChange={(event) => setSize(Number(event.target.value))}><option value="5">5</option><option value="20">20</option><option value="50">50</option></select></div><button className="primary-button">查詢任務</button></form><div className="dispatch-order-filter"><span>訂單條件：{orderFilter?.label ?? '全部訂單'}</span>{orderFilter && <button type="button" className="text-button" onClick={() => { setOrderFilter(null); setQuery((previous) => { const { orderId, ...rest } = previous; void orderId; return rest }); setPage(0) }}>清除訂單條件</button>}</div></section><TaskList query={query} page={page} revision={revision} onPage={setPage} onOpen={open} onExpired={onExpired} onOrder={(id, label) => { setOrderFilter({ id, label }); setQuery((previous) => ({ ...previous, orderId: id })); setPage(0) }} /></section>
}
