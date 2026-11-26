import { useCallback, useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from './api'
import { ErrorNotice } from './AuthForms'
import { getCustomerOptions, validateText } from './crmApi'
import type { CustomerOption } from './crmApi'
import { getOrder, getOrders } from './tomApi'
import type { Order, OrderListQuery, OrderPage } from './tomApi'
import OrderEditor from './OrderEditor'
import { isEditableOrder, isWaitingForDispatch } from './orderAcceptanceModel'
import './order-workspace.css'

const typeLabels = { IMPORT: '進口', EXPORT: '出口', LOCAL: '國內運輸' } as const
type CustomerFilter = { id: string; name: string }
type View = { kind: 'list' } | { kind: 'create' } | { kind: 'edit'; id: string }
type ListQuery = Omit<OrderListQuery, 'page'>
const listTimestamp = new Intl.DateTimeFormat('zh-TW', { timeZone: 'Asia/Taipei', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
function updatedAtLabel(value: string): string {
  const instant = Date.parse(value)
  return Number.isFinite(instant) ? listTimestamp.format(instant) : value
}

function CustomerFilterPicker({ onSelect, onExpired }: { onSelect: (customer: CustomerFilter) => void; onExpired: () => void }) {
  const [text, setText] = useState('')
  const [search, setSearch] = useState({ q: '', page: 0, revision: 0 })
  const [inputError, setInputError] = useState<unknown>(null)
  const [result, setResult] = useState<{ search: typeof search; content?: CustomerOption[]; totalPages?: number; error?: unknown }>()
  useEffect(() => {
    const controller = new AbortController()
    let active = true
    void getCustomerOptions(search.page, search.q, controller.signal)
      .then((data) => { if (active) setResult({ search, content: data.content, totalPages: data.totalPages }) })
      .catch((error: unknown) => { if (active) { setResult({ search, error }); if (error instanceof ApiError && error.status === 401) onExpired() } })
    return () => { active = false; controller.abort() }
  }, [search, onExpired])
  const current = result?.search === search ? result : undefined
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    try { validateText(text, '客戶搜尋', 100); setSearch({ q: text.trim(), page: 0, revision: search.revision + 1 }); setInputError(null) }
    catch (error) { setInputError(error) }
  }
  return <section className="order-filter-picker" aria-label="選擇篩選客戶"><form className="order-picker-search" onSubmit={submit}><div><label htmlFor="order-filter-customer-search">篩選客戶搜尋</label><input id="order-filter-customer-search" value={text} onChange={(event) => setText(event.target.value)} placeholder="輸入名稱或客戶代碼" /></div><button className="secondary-button" disabled={!current}>查詢篩選客戶</button></form><ErrorNotice error={inputError ?? current?.error} />
    {!current ? <p role="status">正在載入篩選客戶…</p> : current.error ? <button className="secondary-button" onClick={() => setSearch({ ...search, revision: search.revision + 1 })}>重新載入篩選客戶</button> : <><ul className="order-customer-choices">{current.content?.map((customer) => <li key={customer.id}><button className="secondary-button" aria-label={`篩選客戶：${customer.name}（${customer.customerCode}）`} onClick={() => onSelect(customer)}><span>{customer.name}<small>{customer.customerCode}</small></span><span aria-hidden="true">選擇</span></button></li>)}</ul>{current.content?.length === 0 && <p className="empty-state">沒有符合條件的有效客戶。</p>}<nav className="order-picker-pages" aria-label="篩選客戶分頁"><button className="secondary-button compact" disabled={search.page === 0} onClick={() => setSearch({ ...search, page: search.page - 1 })}>上一頁客戶</button><span>第 {search.page + 1} 頁</span><button className="secondary-button compact" disabled={search.page + 1 >= (current.totalPages ?? 0)} onClick={() => setSearch({ ...search, page: search.page + 1 })}>下一頁客戶</button></nav></>}
    <p className="order-muted">選項顯示有效客戶；歷史訂單也可從列表中的客戶名稱篩選。</p>
  </section>
}

function OrderList({ query, page, revision, onPage, onOpen, onCustomer, onExpired }: { query: ListQuery; page: string; revision: number; onPage: (page: string) => void; onOpen: (id: string) => void; onCustomer: (customer: CustomerFilter) => void; onExpired: () => void }) {
  const [result, setResult] = useState<{ query: ListQuery; page: string; revision: number; data?: OrderPage; error?: unknown }>()
  useEffect(() => {
    const controller = new AbortController()
    let active = true
    void getOrders({ ...query, page }, controller.signal)
      .then(({ data }) => { if (active) setResult({ query, page, revision, data }) })
      .catch((error: unknown) => { if (active) { setResult({ query, page, revision, error }); if (error instanceof ApiError && error.status === 401) onExpired() } })
    return () => { active = false; controller.abort() }
  }, [query, page, revision, onExpired])
  const current = result?.query === query && result.page === page && result.revision === revision ? result : undefined
  if (!current) return <section className="card order-loading" role="status"><span className="order-loading-dot" aria-hidden="true" />正在載入訂單列表…</section>
  if (current.error) return <section className="card"><ErrorNotice error={current.error} /><p className="order-muted">訂單列表尚未載入，請使用「重新載入訂單列表」再次查詢。</p></section>
  const data = current.data!
  const index = BigInt(data.page)
  return <section aria-labelledby="order-results-heading"><div className="order-results-heading"><h2 id="order-results-heading">訂單列表</h2><p role="status">共 {String(data.totalElements)} 筆訂單</p></div>
    {data.content.length === 0 ? <div className="card order-list-empty"><span className="order-list-empty-icon" aria-hidden="true">＋</span><h3>尚無符合條件的訂單</h3><p>調整類型或客戶條件，或從「新增訂單」開始建立草稿。</p></div> : <ul className="order-records">{data.content.map((order) => <li key={order.id}><article className="card order-record" aria-label={`訂單 ${order.orderNo}`}><div className="order-record-top"><span className={`order-type order-type-${order.type.toLowerCase()}`}>{typeLabels[order.type]}</span><span className="order-draft-tag">{isWaitingForDispatch(order) ? '已受理・待派車' : `${order.acceptanceStatus === 'DRAFT' ? '草稿' : '已受理'} · ${order.lifecycleStatus === 'OPEN' ? '進行中' : order.lifecycleStatus === 'CANCELLED' ? '已取消' : '已結案'}`}</span></div><h3>{order.orderNo}</h3><p className="order-customer-name">{order.customerName}</p><dl className="order-record-meta"><div><dt>{order.acceptanceStatus === 'ACCEPTED' ? '訂單版本' : '草稿版本'}</dt><dd>{order.version}</dd></div><div><dt>更新（台北）</dt><dd><time dateTime={order.updatedAt}>{updatedAtLabel(order.updatedAt)}</time></dd></div></dl><div className="order-record-actions"><button className="primary-button" aria-label={`${isEditableOrder(order) ? '編輯' : '檢視'}訂單 ${order.orderNo}`} onClick={() => onOpen(order.id)}>{order.acceptanceStatus === 'ACCEPTED' ? '檢視已受理訂單' : '開啟訂單'} <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="M5 12h14m-5-5 5 5-5 5" strokeLinecap="round" strokeLinejoin="round" /></svg></button><button className="text-button" aria-label={`只看客戶 ${order.customerName}`} onClick={() => onCustomer({ id: order.customerId, name: order.customerName })}>只看此客戶</button></div></article></li>)}</ul>}
    <nav className="order-pagination" aria-label="訂單列表分頁"><span>{BigInt(data.totalPages) > 0n ? `第 ${index + 1n} / ${data.totalPages} 頁` : '目前無資料'}</span><div><button className="secondary-button" disabled={index === 0n} onClick={() => onPage(String(index - 1n))}>上一頁訂單</button><button className="secondary-button" disabled={index + 1n >= BigInt(data.totalPages)} onClick={() => onPage(String(index + 1n))}>下一頁訂單</button></div></nav>
  </section>
}

export default function OrderManager({ onExpired, onDirtyChange, onBusyChange }: { onExpired: () => void; onDirtyChange?: (dirty: boolean) => void; onBusyChange?: (busy: boolean) => void }) {
  const [view, setView] = useState<View>({ kind: 'list' })
  const [type, setType] = useState('')
  const [customer, setCustomer] = useState<CustomerFilter | null>(null)
  const [pickerOpen, setPickerOpen] = useState(false)
  const [size, setSize] = useState(20)
  const [sort, setSort] = useState<NonNullable<OrderListQuery['sort']>>('updatedAt,desc')
  const [query, setQuery] = useState<ListQuery>({ size: 20, sort: 'updatedAt,desc' })
  const [page, setPage] = useState('0')
  const [revision, setRevision] = useState(0)
  const [orderRevision, setOrderRevision] = useState(0)
  const [loaded, setLoaded] = useState<{ key: string; order?: Order; error?: unknown }>()
  const [leaveConfirm, setLeaveConfirm] = useState(false)
  const [savedNotice, setSavedNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const dirty = useRef(false)
  const busyCallback = useRef(onBusyChange)
  const id = view.kind === 'edit' ? view.id : null
  const loadKey = `${id}/${orderRevision}`
  useEffect(() => { busyCallback.current = onBusyChange }, [onBusyChange])
  useEffect(() => () => busyCallback.current?.(false), [])
  useEffect(() => {
    if (!id) return
    const controller = new AbortController()
    let active = true
    void getOrder(id, controller.signal).then(({ data }) => { if (active) setLoaded({ key: loadKey, order: data }) })
      .catch((error: unknown) => { if (active) { setLoaded({ key: loadKey, error }); if (error instanceof ApiError && error.status === 401) onExpired() } })
    return () => { active = false; controller.abort() }
  }, [id, loadKey, onExpired])
  const updateDirty = useCallback((value: boolean) => { dirty.current = value; if (!value) setLeaveConfirm(false); onDirtyChange?.(value) }, [onDirtyChange])
  const updateBusy = useCallback((value: boolean) => { setBusy(value); busyCallback.current?.(value) }, [])
  const toList = () => { if (busy) return; updateDirty(false); setLeaveConfirm(false); setView({ kind: 'list' }); setRevision((value) => value + 1) }
  const requestList = () => { if (busy) return; if (dirty.current) setLeaveConfirm(true); else toList() }
  const onSaved = (order: Order) => {
    // Keep the mounted editor and its own confirmed baseline; never replace it with a list summary.
    setSavedNotice(`已確認訂單 ${order.orderNo}，版本 ${order.version}。`)
  }
  const search = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setQuery({ size, sort, ...(type ? { type: type as NonNullable<OrderListQuery['type']> } : {}), ...(customer ? { customerId: customer.id } : {}) })
    setPage('0'); setRevision((value) => value + 1)
  }
  const selectCustomer = (value: CustomerFilter) => { setCustomer(value); setPickerOpen(false) }
  const currentOrder = loaded?.key === loadKey ? loaded : undefined
  if (view.kind !== 'list') return <section className="order-workspace">
    {leaveConfirm && <section className="warning-notice order-leave-confirm" role="alert" aria-label="確認離開訂單"><h2>離開前請確認</h2><p>本機有尚未儲存或尚未確認結果的訂單內容。離開後，這個頁面的內容與重試資訊會清除。</p><div className="form-actions"><button className="secondary-button" disabled={busy} onClick={() => { if (!busy) setLeaveConfirm(false) }}>繼續編輯訂單</button><button className="secondary-button" disabled={busy} onClick={toList}>捨棄本機內容並返回列表</button></div></section>}
    {view.kind === 'create' ? <OrderEditor key="new-order" onExpired={onExpired} onCancel={requestList} onSaved={onSaved} onDirtyChange={updateDirty} onBusyChange={updateBusy} /> : !currentOrder ? <section className="card"><h1>載入訂單</h1><p role="status">正在載入完整訂單…</p><button className="secondary-button" onClick={requestList}>返回訂單列表</button></section> : currentOrder.error ? <section className="card"><h1>無法開啟訂單</h1><ErrorNotice error={currentOrder.error} /><div className="form-actions"><button className="primary-button" onClick={() => setOrderRevision((value) => value + 1)}>重新載入訂單</button><button className="secondary-button" onClick={requestList}>返回訂單列表</button></div></section> : <OrderEditor key={view.id} order={currentOrder.order} onExpired={onExpired} onCancel={requestList} onSaved={onSaved} onDirtyChange={updateDirty} onBusyChange={updateBusy} />}
  </section>
  return <section className="order-workspace"><div className="section-title order-workspace-heading"><div><p className="eyebrow">TRANSPORT ORDER MANAGEMENT</p><h1>訂單工作台</h1><p className="page-description">從客戶需求開始，整理貨櫃、貨物與每一段運輸。</p></div><button className="primary-button" onClick={() => { setView({ kind: 'create' }); setSavedNotice('') }}><span aria-hidden="true">＋</span> 新增訂單</button></div>
    <section className="order-workspace-note"><div><span className="order-note-marker" aria-hidden="true">01</span><div><strong>先存草稿，再確認完整性</strong><p>可分次補齊資料。完整性檢核提供缺漏提示；正式受理須另行明確確認，受理後待派車，目前不提供派工。</p></div></div><span className="order-scope-tag">進口 · 出口 · 國內運輸</span></section>
    {savedNotice && <p className="success-notice" role="status">{savedNotice}</p>}
    <section className="card order-filter-card" aria-labelledby="order-query-heading"><div className="order-filter-title"><h2 id="order-query-heading">查詢訂單</h2><button className="text-button" onClick={() => setRevision((value) => value + 1)}>重新載入訂單列表</button></div><form className="order-query" aria-label="訂單查詢" onSubmit={search}><div><label htmlFor="order-filter-type">訂單類型篩選</label><select id="order-filter-type" value={type} onChange={(event) => setType(event.target.value)}><option value="">全部類型</option><option value="IMPORT">進口 IMPORT</option><option value="EXPORT">出口 EXPORT</option><option value="LOCAL">國內運輸 LOCAL</option></select></div><div><label htmlFor="order-sort">訂單排序</label><select id="order-sort" value={sort} onChange={(event) => setSort(event.target.value as typeof sort)}><option value="updatedAt,desc">最近更新優先</option><option value="updatedAt,asc">較早更新優先</option><option value="orderNo,asc">單號由小至大</option><option value="orderNo,desc">單號由大至小</option></select></div><div><label htmlFor="order-page-size">每頁訂單筆數</label><select id="order-page-size" value={size} onChange={(event) => setSize(Number(event.target.value))}><option value="5">5</option><option value="20">20</option><option value="50">50</option><option value="100">100</option></select></div><button className="primary-button">查詢訂單</button></form><div className="order-customer-filter"><span>客戶條件：<strong>{customer?.name ?? '全部客戶'}</strong></span><button className="text-button" aria-expanded={pickerOpen} onClick={() => setPickerOpen((value) => !value)}>{pickerOpen ? '收合篩選客戶' : '選擇篩選客戶'}</button>{customer && <button className="text-button" onClick={() => { setCustomer(null); setQuery((value) => { const { customerId: removed, ...rest } = value; void removed; return rest }); setPage('0') }}>清除客戶條件</button>}</div>{pickerOpen && <CustomerFilterPicker onSelect={selectCustomer} onExpired={onExpired} />}</section>
    <OrderList query={query} page={page} revision={revision} onPage={setPage} onOpen={(orderId) => { setOrderRevision((value) => value + 1); setView({ kind: 'edit', id: orderId }); setSavedNotice('') }} onCustomer={(value) => { selectCustomer(value); setQuery((previous) => ({ ...previous, customerId: value.id })); setPage('0') }} onExpired={onExpired} />
  </section>
}
