import { useEffect, useRef, useState } from 'react'
import { ApiError } from './api'
import { getCustomerOptions } from './crmApi'
import type { CustomerOption } from './crmApi'
import type { PageData } from './admApi'
import { TomApiError, checkOrder, createOrder, getOrder, newIdempotencyKey, replaceOrder } from './tomApi'
import type { AcceptanceCheck, Order, OrderDraft, TomWriteResult } from './tomApi'
import { OrderField, OrderTimeField } from './OrderField'
import OrderItemsEditor from './OrderItemsEditor'
import OrderTasksEditor from './OrderTasksEditor'
import { OrderFieldIssues } from './orderFieldContext'
import { EditorValidationError, editorFromOrder, emptyShipping, newItem, newTask, toOrderDraft } from './orderEditorModel'
import type { EditorDraft, EditorIssue, EditorShipping } from './orderEditorModel'
import './order-editor.css'

export interface OrderEditorProps { order?: Order; onSaved: (order: Order) => void; onExpired: () => void; onCancel?: () => void; onDirtyChange?: (dirty: boolean) => void }
interface PendingWrite { id?: string; version?: number; draft: OrderDraft; key: string; unresolved?: true }
interface Checked { data: AcceptanceCheck; traceId: string }
function frozen<T>(value: T): T { if (value && typeof value === 'object') { Object.values(value).forEach(frozen); Object.freeze(value) } return value }
function errorMessage(error: unknown): string { return error instanceof Error ? error.message : '操作未完成，請稍後再試。' }
// These TomService failures occur only after the original-key replay lookup found no record.
// Shape validation, security filters and replay's own order lookup do not prove that.
function settlesPriorWrite(error: unknown): boolean {
  return error instanceof TomApiError && (error.status === 400 && error.code === 'INVALID_CUSTOMER' || error.status === 409 && ['VERSION_CONFLICT', 'ORDER_NOT_EDITABLE', 'PROTECTED_RELATION'].includes(error.code))
}

export default function OrderEditor({ order: initialOrder, onSaved, onExpired, onCancel, onDirtyChange }: OrderEditorProps) {
  const [baseline, setBaseline] = useState<Order | undefined>(initialOrder)
  const [draft, setDraft] = useState<EditorDraft>(() => editorFromOrder(initialOrder))
  const [pending, setPending] = useState<PendingWrite | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState('')
  const [conflict, setConflict] = useState(false)
  const [remote, setRemote] = useState<Order | null>(null)
  const [reading, setReading] = useState(false)
  const [readError, setReadError] = useState<unknown>(null)
  const [confirmation, setConfirmation] = useState<'reload' | 'rebase' | 'shipping' | null>(null)
  const [check, setCheck] = useState<Checked | null>(null)
  const [checking, setChecking] = useState(false)
  const [checkError, setCheckError] = useState<unknown>(null)
  const [search, setSearch] = useState('')
  const [query, setQuery] = useState('')
  const [customerPage, setCustomerPage] = useState(0)
  const [customerAttempt, setCustomerAttempt] = useState(0)
  const [customers, setCustomers] = useState<{ key: string; data?: PageData<CustomerOption>; error?: unknown }>()
  const [choice, setChoice] = useState<{ id: string; name: string } | null>(initialOrder ? { id: initialOrder.customerId, name: initialOrder.customerName } : null)
  const active = useRef(true)
  const editorElement = useRef<HTMLElement>(null)
  const dirtyCallback = useRef(onDirtyChange)
  const locked = busy || pending !== null || reading || baseline !== undefined && (baseline.lifecycleStatus !== 'OPEN' || baseline.acceptanceStatus !== 'DRAFT')
  const canProcessVersion = !busy && pending === null && !reading
  const dirty = JSON.stringify(draft) !== JSON.stringify(editorFromOrder(baseline)) || pending !== null || busy || conflict
  const issues: EditorIssue[] = error instanceof EditorValidationError ? error.issues : error instanceof TomApiError ? error.fieldErrors : []
  const customerKey = `${query}/${customerPage}/${customerAttempt}`
  const customerLoading = customers?.key !== customerKey
  const customerData = customerLoading ? undefined : customers?.data
  const customerError = customerLoading ? undefined : customers?.error

  useEffect(() => { active.current = true; return () => { active.current = false; dirtyCallback.current?.(false) } }, [])
  useEffect(() => { dirtyCallback.current = onDirtyChange }, [onDirtyChange])
  useEffect(() => { dirtyCallback.current?.(dirty) }, [dirty])
  useEffect(() => {
    if (!dirty) return
    const handler = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', handler)
    return () => window.removeEventListener('beforeunload', handler)
  }, [dirty])
  useEffect(() => {
    let current = true
    const controller = new AbortController()
    void getCustomerOptions(customerPage, query, controller.signal).then((data) => { if (current) setCustomers({ key: customerKey, data }) })
      .catch((failure: unknown) => { if (current) { setCustomers({ key: customerKey, error: failure }); if (failure instanceof ApiError && failure.status === 401) onExpired() } })
    return () => { current = false; controller.abort() }
  }, [customerPage, customerKey, query, onExpired])

  const change = (next: EditorDraft) => { if (!locked) { setDraft(next); setError(null); setNotice(''); setConfirmation(null) } }
  const adopt = (saved: Order) => {
    setBaseline(saved); setDraft(editorFromOrder(saved)); setChoice({ id: saved.customerId, name: saved.customerName }); setPending(null); setConflict(false); setRemote(null); setConfirmation(null); setCheck(null); setError(null); setCheckError(null)
  }
  const handleAuth = (failure: unknown) => { if (failure instanceof ApiError && (failure.status === 401 || failure instanceof TomApiError && failure.code === 'FORBIDDEN')) onExpired() }
  const send = async (operation: PendingWrite) => {
    setBusy(true); setError(null); setNotice(''); setRemote(null); setConfirmation(null); setPending(operation)
    try {
      const result: TomWriteResult<Order> = operation.id ? await replaceOrder(operation.id, operation.version!, operation.draft, operation.key) : await createOrder(operation.draft, operation.key)
      if (!active.current) return
      adopt(result.data)
      setNotice(result.replayed ? `已取回這次保存的第 ${result.data.version} 版原結果。重播可能是歷史快照；最新資料請明確重新載入確認。` : `草稿已保存，版本 ${result.data.version}。`)
      onSaved(result.data)
    } catch (failure) {
      if (!active.current) return
      setError(failure); handleAuth(failure)
      const uncertain = !(failure instanceof TomApiError) || failure.uncertain || failure.code === 'IDEMPOTENCY_IN_PROGRESS' || failure.code === 'IDEMPOTENCY_KEY_REUSED'
      // A filter/shape rejection of this retry cannot settle the earlier lost response.
      const stillUnresolved = operation.unresolved && !settlesPriorWrite(failure)
      setPending(stillUnresolved || uncertain ? { ...operation, unresolved: true } : null)
      if (!stillUnresolved && failure instanceof TomApiError && failure.code === 'VERSION_CONFLICT') setConflict(true)
    } finally { if (active.current) setBusy(false) }
  }
  const save = () => {
    if (locked || conflict) return
    try { const body = frozen(structuredClone(toOrderDraft(draft))); void send({ id: baseline?.id, version: baseline?.version, draft: body, key: newIdempotencyKey() }) }
    catch (failure) { setError(failure) }
  }
  const readRemote = async () => {
    if (!baseline || busy || pending) return
    setReading(true); setReadError(null)
    try { const result = await getOrder(baseline.id); if (active.current) { setRemote(result.data); setConfirmation(null) } }
    catch (failure) { if (active.current) { setReadError(failure); handleAuth(failure) } }
    finally { if (active.current) setReading(false) }
  }
  const runCheck = async () => {
    if (!baseline || checking || pending || busy) return
    setChecking(true); setCheckError(null)
    try { const result = await checkOrder(baseline.id); if (active.current) setCheck({ data: result.data, traceId: result.traceId }) }
    catch (failure) { if (active.current) { setCheckError(failure); handleAuth(failure) } }
    finally { if (active.current) setChecking(false) }
  }
  const focusPath = (path: string) => {
    const candidates = [...editorElement.current?.querySelectorAll<HTMLElement>('[data-order-path]') ?? []]
    let current = path
    let target: HTMLElement | undefined
    while (true) {
      target = candidates.find((element) => element.dataset.orderPath === current && !element.matches(':disabled'))
      if (target || current === '') break
      current = current.slice(0, Math.max(0, current.lastIndexOf('/')))
    }
    target ??= editorElement.current ?? undefined
    let ancestor = target?.parentElement
    while (ancestor && ancestor !== editorElement.current) { if (ancestor instanceof HTMLDetailsElement) ancestor.open = true; ancestor = ancestor.parentElement }
    target?.focus(); target?.scrollIntoView?.({ block: 'center', behavior: 'smooth' })
  }
  const pendingKeyConflict = error instanceof TomApiError && error.code === 'IDEMPOTENCY_KEY_REUSED'
  const historical = baseline && baseline.customerId === draft.customerId
  const updateShipping = (key: keyof EditorShipping, value: string) => { if (draft.shipping) change({ ...draft, shipping: { ...draft.shipping, [key]: value } }) }

  return <section className="order-editor" ref={editorElement} tabIndex={-1} aria-labelledby="order-editor-heading"><header className="order-editor-heading"><div><p className="eyebrow">ORDER DRAFT</p><h1 id="order-editor-heading">{baseline ? `編輯訂單 ${baseline.orderNo}` : '新增訂單'}</h1><p>草稿只需類型與客戶即可保存。完整性檢查不是正式受理或派工。</p></div>{onCancel && <button type="button" className="secondary-button" disabled={busy} onClick={onCancel}>返回訂單列表</button>}</header>
    {baseline && <div className="order-saved-meta"><span>已保存版本 {baseline.version}</span><span>{baseline.lifecycleStatus}／{baseline.acceptanceStatus}</span><span>客戶歷史名稱：{baseline.customerName}</span></div>}
    {baseline && (baseline.lifecycleStatus !== 'OPEN' || baseline.acceptanceStatus !== 'DRAFT') && <p className="order-warning">此訂單目前僅供檢視，不能編輯或保存草稿。</p>}
    {notice && <p className="order-success" role="status">{notice}</p>}
    {Boolean(error) && <div className="order-error-summary" role="alert"><p>{errorMessage(error)}</p>{error instanceof TomApiError && <p>錯誤代碼：{error.code}{error.traceId ? ` · 追查識別：${error.traceId}` : ''}</p>}{issues.length > 0 && <ul>{issues.map((issue, index) => <li key={index}><button type="button" onClick={() => focusPath(issue.path)}>{issue.message} <small>{issue.path}{issue.fieldId ? ` · ${issue.fieldId}` : ''}</small></button></li>)}</ul>}</div>}
    {pending && !busy && <div className="order-uncertain" role="status"><h2>{pendingKeyConflict ? '停止這次重送，先確認伺服器結果' : '保存結果尚待確認'}</h2><p>目前內容已凍結，不會另建一份或自動換識別重送。若要離開，請先理解先前請求可能已成功；返回列表後確認結果，再決定下一步。</p>{!pendingKeyConflict && <button type="button" className="primary-button" onClick={() => { void send(pending) }}>以原內容重試保存</button>}</div>}
    {conflict && <div className="order-warning"><h2>版本衝突，本機草稿已保留</h2><p>伺服器可能已有第 {error instanceof TomApiError && error.actualVersion !== undefined ? error.actualVersion : '較新'} 版；不會自動覆寫版本或重送。請讀取遠端內容後，再明確選擇處理方式。</p></div>}
    {baseline && <section className="order-reload-panel" aria-label="伺服器版本比較"><button type="button" className="secondary-button" disabled={busy || pending !== null || reading} onClick={() => { void readRemote() }}>{reading ? '讀取中…' : '讀取伺服器版本比較'}</button>{Boolean(readError) && <p className="order-error" role="alert">{errorMessage(readError)}</p>}{remote && <div><p>已讀取伺服器第 {remote.version} 版，客戶：{remote.customerName}，{remote.items.length} 個項目、{remote.tasks.length} 個任務；尚未覆蓋本機內容。</p><div className="order-compare-grid"><details><summary>本機完整草稿內容</summary><pre>{JSON.stringify(draft, null, 2)}</pre></details><details><summary>伺服器完整草稿內容</summary><pre>{JSON.stringify(editorFromOrder(remote), null, 2)}</pre></details></div><div className="order-row-actions"><button type="button" className="secondary-button" disabled={!canProcessVersion} onClick={() => { if (canProcessVersion) setConfirmation('reload') }}>捨棄本機內容，載入伺服器版本</button>{conflict && <button type="button" className="secondary-button" disabled={!canProcessVersion} onClick={() => { if (canProcessVersion) setConfirmation('rebase') }}>保留本機草稿，使用此版本繼續</button>}</div>{(confirmation === 'reload' || confirmation === 'rebase') && <div className="order-confirm"><p>{confirmation === 'reload' ? '確認捨棄本機未保存內容，改用已讀取的伺服器版本？' : '下一次保存將以此伺服器版本為基準，完整取代為本機內容，可能覆寫遠端修改或移除遠端新增項目／任務。請先比較後明確確認。'}</p><button type="button" className="secondary-button" disabled={!canProcessVersion} onClick={() => { if (!canProcessVersion || !remote) return; if (confirmation === 'reload') { adopt(remote); onSaved(remote) } else { setBaseline(remote); setConflict(false); setError(null); setRemote(null); setConfirmation(null); setCheck(null); setNotice('已由您確認保留本機內容，並採用讀取版本作為下次保存基準；尚未送出。') } }}>確認{confirmation === 'reload' ? '載入伺服器版本' : '保留本機內容繼續'}</button><button type="button" className="secondary-button" onClick={() => setConfirmation(null)}>取消版本處理</button></div>}</div>}</section>}
    <OrderFieldIssues.Provider value={issues}><fieldset className="order-main-fields" disabled={locked}><legend>訂單基本資料</legend><div className="order-field-grid"><div className="order-field"><label htmlFor="order-type">訂單類型</label><select id="order-type" data-order-path="/type" value={draft.type} onChange={(event) => change({ ...draft, type: event.target.value as EditorDraft['type'] })}><option value="">請選擇類型</option><option value="IMPORT">進口</option><option value="EXPORT">出口</option><option value="LOCAL">國內</option></select><small>變更類型不會自動清除船務、項目或任務。</small></div><OrderField id="order-reference" label="客戶參考號" path="/customerReference" value={draft.customerReference} onChange={(value) => change({ ...draft, customerReference: value })} maxLength={200} hint="選填，不取代系統單號；留空表示未知。" /></div>
      <fieldset className="order-customer-picker"><legend>客戶</legend><div className="order-customer-search"><label htmlFor="order-customer-search">查詢有效客戶</label><input id="order-customer-search" value={search} onChange={(event) => setSearch(event.target.value)} /><button type="button" className="secondary-button" onClick={() => { if ([...search].length > 100) { setError(new EditorValidationError([{ path: '/customerId', message: '客戶搜尋最多 100 字。' }])); return } setQuery(search.trim()); setCustomerPage(0); setCustomerAttempt((value) => value + 1) }}>查詢客戶</button></div>{customerLoading ? <p role="status">正在讀取有效客戶…</p> : customerError ? <p className="order-error" role="alert">{errorMessage(customerError)}；可重新查詢，同一歷史客戶不會因此被清空。</p> : customerData?.content.length === 0 && <p>此頁沒有符合條件的有效客戶。</p>}
        <label htmlFor="order-customer-id">選擇客戶</label><select id="order-customer-id" data-order-path="/customerId" value={draft.customerId} onChange={(event) => { const id = event.target.value; const selected = customerData?.content.find((item) => item.id === id); setChoice(selected ? { id, name: selected.name } : baseline && id === baseline.customerId ? { id, name: baseline.customerName } : null); change({ ...draft, customerId: id }) }}><option value="">請選擇客戶</option>{choice && !customerData?.content.some((item) => item.id === choice.id) && <option value={choice.id}>已選：{choice.name}{historical ? '（歷史客戶）' : ''}</option>}{baseline && baseline.customerId !== choice?.id && !customerData?.content.some((item) => item.id === baseline.customerId) && <option value={baseline.customerId}>原客戶：{baseline.customerName}（歷史客戶）</option>}{customerData?.content.map((item) => <option value={item.id} key={item.id}>{item.name} · {item.customerCode}</option>)}</select>{historical && <p className="order-history-note">同客戶會保留「{baseline!.customerName}」歷史名稱；即使主檔已改名或停用，仍可維護原草稿。</p>}{customerData && <div className="order-customer-pagination"><span>共 {customerData.totalElements} 個有效客戶</span><button type="button" className="text-button" disabled={customerPage === 0} onClick={() => setCustomerPage((value) => value - 1)}>上一頁客戶</button><button type="button" className="text-button" disabled={customerPage + 1 >= customerData.totalPages} onClick={() => setCustomerPage((value) => value + 1)}>下一頁客戶</button></div>}
      </fieldset>
      <div className="order-field"><label htmlFor="order-remark-mode">備註內容狀態</label><select id="order-remark-mode" value={draft.remark === null ? 'unknown' : 'text'} onChange={(event) => change({ ...draft, remark: event.target.value === 'unknown' ? null : '' })}><option value="unknown">未提供</option><option value="text">填寫備註（可留白）</option></select></div>{draft.remark !== null && <OrderField id="order-remark" label="訂單備註" path="/remark" multiline value={draft.remark} onChange={(value) => change({ ...draft, remark: value })} maxLength={4000} hint="保留空字串、空白與原始文字，不自動修整。" />}
    </fieldset>
    <section className="order-shipping-section" data-order-path="/shipping" tabIndex={-1} aria-labelledby="order-shipping-title"><div className="order-section-heading"><div><h2 id="order-shipping-title">船務資料</h2><p>國內單可不提供；切換類型不會移除已填船務。</p></div>{draft.shipping === null ? <button type="button" className="secondary-button" disabled={locked} onClick={() => change({ ...draft, shipping: emptyShipping() })}>加入船務資料</button> : <button type="button" className="text-button order-remove" disabled={locked} onClick={() => setConfirmation('shipping')}>移除整組船務</button>}</div>{draft.shipping === null ? <p className="order-empty">未提供整組船務。</p> : <fieldset disabled={locked} className="order-shipping-fields"><legend>船務欄位</legend><div className="order-field-grid">{([['shippingCompany', '船公司'], ['vesselVoyage', '船名航次'], ['pol', '裝貨港'], ['pod', '卸貨港'], ['blNo', '提單號']] as const).map(([key, label]) => <OrderField key={key} id={`order-shipping-${key}`} path={`/shipping/${key}`} label={label} value={draft.shipping![key]} onChange={(value) => updateShipping(key, value)} maxLength={200} hint="選填；留空表示此欄未知，船務物件仍保留。" />)}<OrderTimeField id="order-shipping-eta" label="預計到港時間" path="/shipping/eta" value={draft.shipping.eta} onChange={(value) => updateShipping('eta', value)} /><OrderTimeField id="order-shipping-etd" label="預計離港時間" path="/shipping/etd" value={draft.shipping.etd} onChange={(value) => updateShipping('etd', value)} /></div></fieldset>}{confirmation === 'shipping' && <div className="order-confirm"><p>確認清除整組船務及已填內容？此動作只修改本機草稿，保存後才套用。</p><button type="button" className="secondary-button" disabled={locked} onClick={() => { change({ ...draft, shipping: null }); setConfirmation(null) }}>確認移除船務資料</button><button type="button" className="secondary-button" onClick={() => setConfirmation(null)}>保留船務資料</button></div>}</section>
    <OrderItemsEditor items={draft.items} tasks={draft.tasks} disabled={locked} onChange={(items) => change({ ...draft, items })} onAdd={() => change({ ...draft, items: [...draft.items, newItem()] })} onRemove={(ref) => { if (draft.tasks.some((task) => task.itemRef === ref)) { setError(new EditorValidationError([{ path: '/items', message: '項目仍被任務使用，請先調整關聯；不會自動刪除任務。' }])); return } change({ ...draft, items: draft.items.filter((item) => item.clientRef !== ref) }) }} />
    <OrderTasksEditor tasks={draft.tasks} items={draft.items} disabled={locked} onChange={(tasks) => change({ ...draft, tasks })} onAdd={() => change({ ...draft, tasks: [...draft.tasks, newTask()] })} onRemove={(ref) => { if (draft.tasks.some((task) => task.predecessorRefs.includes(ref))) { setError(new EditorValidationError([{ path: '/tasks', message: '此任務仍被其他任務作為前置，請先調整依賴；不會自動清除其他任務關聯。' }])); return } change({ ...draft, tasks: draft.tasks.filter((task) => task.clientRef !== ref) }) }} />
    </OrderFieldIssues.Provider>
    <div className="order-save-bar"><p>{busy ? '正在保存；請等待結果。' : dirty ? '有本機未保存內容或待確認操作。' : baseline ? `目前內容對應已保存版本 ${baseline.version}。` : '尚未建立訂單。'}</p><button type="button" className="primary-button" disabled={locked || conflict} onClick={save}>{busy ? '保存中…' : baseline ? '保存草稿' : '建立草稿'}</button></div>
    <section className="order-acceptance-panel" aria-labelledby="order-acceptance-title"><div className="order-section-heading"><div><h2 id="order-acceptance-title">已保存版本完整性</h2><p>{dirty ? '本機尚有修改；檢查不包含未保存內容。' : '僅檢查已保存的欄位，不授予業務操作權限。'}</p></div><button type="button" className="secondary-button" disabled={!baseline || checking || busy || pending !== null} onClick={() => { void runCheck() }}>{checking ? '檢查中…' : '檢查已保存版本'}</button></div>{!baseline && <p>請先建立草稿，才可檢查已保存版本。</p>}{Boolean(checkError) && <p className="order-error" role="alert">{errorMessage(checkError)}</p>}{check && <div className="order-check-result" role="status"><h3>{check.data.confirmedRulesOutcome === 'COMPLETE' ? '已確認規則欄位齊全' : '仍有待補欄位'} · 受檢版本 {check.data.version}</h3>{baseline?.version !== check.data.version && <p className="order-warning">受檢版本與目前編輯基準不同，請比較伺服器資料。</p>}<p>尚未授予正式受理（{check.data.operationalAcceptance}），沒有派工或資源占用。</p><p>已檢規則：{check.data.checkedRules.join('、')}</p>{(dirty || baseline?.version !== check.data.version) && <p>本機內容已修改或受檢版本不同，請先保存／重新載入再定位欄位。</p>}{check.data.missingFields.length > 0 && <ul>{check.data.missingFields.map((issue, index) => <li key={index}><button type="button" className="text-button" disabled={dirty || baseline?.version !== check.data.version} onClick={() => { if (!dirty && baseline?.version === check.data.version) focusPath(issue.path) }}>{issue.message} <small>{issue.path} · {issue.fieldId} · {issue.ruleId}</small></button></li>)}</ul>}<h4>仍待核准的政策</h4>{check.data.pendingPolicyIds.length ? <ul>{check.data.pendingPolicyIds.map((id) => <li key={id}>{id}</li>)}</ul> : <p>本次沒有列出待決政策；仍不代表正式受理。</p>}<small>追查識別：{check.traceId}</small></div>}</section>
  </section>
}
