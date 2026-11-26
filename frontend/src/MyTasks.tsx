import { useEffect, useRef, useState } from 'react'
import { ApiError } from './api'
import { getMyTmsTasks, TmsApiError } from './tmsApi'
import type { MyTask, TmsPage } from './tmsApi'
import { taipeiPreview } from './orderEditorModel'
import './my-tasks.css'

interface ReadRequest { epoch: number; page: number; size: number; visible: boolean }
type ReadState = { epoch: number; kind: 'ready'; data: TmsPage<MyTask> } | { epoch: number; kind: 'error'; error: unknown }

function RawValue({ value }: { value: string | null }) {
  if (value === null) return <span className="my-tasks-unprovided">未提供</span>
  if (value === '') return <span className="my-tasks-unprovided">空字串</span>
  return <span className="my-tasks-raw">{value}{value.trim() === '' && <small>空白文字（保留原值）</small>}</span>
}
function TimeValue({ value, assigned = false }: { value: string | null; assigned?: boolean }) {
  if (value === null || value === '') return <RawValue value={value} />
  let preview: string | null = null
  try { preview = taipeiPreview(value) } catch { /* The original value stays visible if a device cannot format it. */ }
  return <div className="my-tasks-time"><time dateTime={value}>{assigned && preview ? preview : value}</time>{preview && <small>{assigned ? '台北時間' : `台北時間：${preview}`}</small>}</div>
}
function TaskCard({ task }: { task: MyTask }) {
  return <article className="card my-task-card" aria-label={`本人任務 ${task.taskNo}`}>
    <header className="my-task-heading"><div><p className="eyebrow">ASSIGNED TASK</p><h2>{task.taskNo}</h2></div><span className="my-task-readonly">指派記錄 · 唯讀</span></header>
    <dl className="my-task-overview"><div><dt>作業類型</dt><dd><RawValue value={task.taskKind} /></dd></div><div><dt>指派時間</dt><dd><TimeValue value={task.assignedAt} assigned /></dd></div></dl>
    <section className="my-task-route" aria-label="本段運送路線"><div className="my-task-stop"><span className="my-task-stop-marker" aria-hidden="true">起</span><div><h3>起點</h3><p className="my-task-place"><RawValue value={task.pickupName} /></p><p><RawValue value={task.pickupAddress} /></p></div></div><div className="my-task-stop"><span className="my-task-stop-marker" aria-hidden="true">迄</span><div><h3>終點</h3><p className="my-task-place"><RawValue value={task.deliveryName} /></p><p><RawValue value={task.deliveryAddress} /></p></div></div></section>
    <section className="my-task-section" aria-label="本段時間"><h3>預定時間</h3><dl className="my-task-data"><div><dt>預定開始</dt><dd><TimeValue value={task.plannedStartAt} /></dd></div><div><dt>預定結束</dt><dd><TimeValue value={task.plannedEndAt} /></dd></div></dl></section>
    <section className="my-task-section" aria-label="本段標的"><h3>貨櫃與貨物</h3><dl className="my-task-data"><div><dt>標的類型</dt><dd>{task.item.itemKind === 'CONTAINER' ? '貨櫃' : task.item.itemKind === 'CARGO' ? '貨物' : '未提供'}</dd></div><div><dt>櫃型</dt><dd><RawValue value={task.item.containerType} /></dd></div><div><dt>櫃號</dt><dd><RawValue value={task.item.containerNo} /></dd></div><div><dt>件數</dt><dd><RawValue value={task.item.packageQty === null ? null : String(task.item.packageQty)} /></dd></div><div><dt>毛重 kg</dt><dd><RawValue value={task.item.grossWeight} /></dd></div><div><dt>體積 m³</dt><dd><RawValue value={task.item.cbm} /></dd></div></dl></section>
    <section className="my-task-section" aria-label="本段現場聯絡"><h3>現場聯絡</h3>{task.siteContact === null ? <p className="my-tasks-help">未提供整組現場聯絡資訊。</p> : <><p className="my-tasks-help">已提供現場聯絡欄位。</p><dl className="my-task-data"><div><dt>現場聯絡姓名</dt><dd><RawValue value={task.siteContact.name} /></dd></div><div><dt>現場聯絡電話</dt><dd><RawValue value={task.siteContact.phone} /></dd></div></dl></>}</section>
    {task.policyMode === 'UNCONFIRMED' && <p className="my-tasks-policy-note">資格政策尚待確認，請向調度員核對。</p>}
  </article>
}

export default function MyTasks({ onExpired }: { onExpired: () => void }) {
  const [request, setRequest] = useState<ReadRequest>(() => ({ epoch: 0, page: 0, size: 20, visible: document.visibilityState !== 'hidden' }))
  const [state, setState] = useState<ReadState>()
  const epoch = useRef(0)
  const inflight = useRef<AbortController | null>(null)
  const expired = useRef(onExpired)
  useEffect(() => { expired.current = onExpired }, [onExpired])
  useEffect(() => {
    if (!request.visible) return
    const controller = new AbortController(); let active = true
    inflight.current = controller
    const stillCurrent = () => active && !controller.signal.aborted && epoch.current === request.epoch
    void getMyTmsTasks(request.page, { size: request.size }, controller.signal).then((data) => {
      if (stillCurrent()) setState({ epoch: request.epoch, kind: 'ready', data })
    }).catch((error: unknown) => {
      if (!stillCurrent()) return
      setState({ epoch: request.epoch, kind: 'error', error })
      if (error instanceof ApiError && (error.status === 401 || error.status === 403 && !(error instanceof TmsApiError && error.reason === 'CSRF_INVALID'))) expired.current()
    })
    return () => { active = false; controller.abort(); if (inflight.current === controller) inflight.current = null }
  }, [request])
  useEffect(() => {
    const visibility = () => {
      const visible = document.visibilityState !== 'hidden'
      epoch.current += 1; const nextEpoch = epoch.current; inflight.current?.abort(); setState(undefined)
      setRequest((current) => ({ ...current, epoch: nextEpoch, page: 0, visible }))
    }
    document.addEventListener('visibilitychange', visibility)
    return () => document.removeEventListener('visibilitychange', visibility)
  }, [])
  const read = (page: number, size = request.size) => {
    epoch.current += 1; inflight.current?.abort(); setState(undefined)
    setRequest({ epoch: epoch.current, page, size, visible: document.visibilityState !== 'hidden' })
  }
  const current = request.visible && state?.epoch === request.epoch ? state : undefined
  const data = current?.kind === 'ready' ? current.data : undefined
  const error = current?.kind === 'error' ? current.error : undefined
  const missing = error instanceof ApiError && error.status === 404
  const denied = error instanceof ApiError && (error.status === 401 || error.status === 403 && !(error instanceof TmsApiError && error.reason === 'CSRF_INVALID'))
  const csrf = error instanceof TmsApiError && error.status === 403 && error.reason === 'CSRF_INVALID'
  return <section className="my-tasks-workspace" aria-busy={!current}>
    <div className="section-title my-tasks-heading"><div><p className="eyebrow">MY ASSIGNED TASKS</p><h1>本人指派任務</h1><p className="page-description">依目前帳號綁定列出指派時資料，可能包含已完成任務；本清單不表示仍執行或資源仍占用。</p></div><button type="button" className="secondary-button" disabled={!current || !request.visible} onClick={() => read(0)}>重新讀取本人任務</button></div>
    <div className="my-tasks-controls"><div><label htmlFor="my-tasks-size">每頁本人任務數</label><select id="my-tasks-size" value={request.size} disabled={!current || !request.visible} onChange={(event) => { const size = Number(event.target.value); if ([5, 20, 50, 100].includes(size)) read(0, size) }}><option value="5">5</option><option value="20">20</option><option value="50">50</option><option value="100">100</option></select></div>{data && <p role="status">共 {data.totalElements} 段本人指派任務</p>}</div>
    {!request.visible ? <section className="card my-tasks-empty" role="status">頁面回到前景後，將重新讀取本人任務。</section> : !current ? <section className="card my-tasks-empty" role="status">正在重新確認本人指派任務…</section> : current.kind === 'error' ? <section className="card my-tasks-empty" role="alert"><h2>{missing ? '目前沒有可讀取的本人任務' : denied ? '本人任務存取已失效' : '無法讀取本人任務'}</h2><p>{missing ? '請聯絡管理員確認目前帳號綁定；此頁不提供其他司機查詢。' : denied ? '請重新登入後再確認本人任務。' : csrf ? '安全驗證暫時無法完成，請重新讀取。' : '請確認連線後重新讀取；舊任務與聯絡資訊已隱去。'}</p></section> : data && <>
      {data.content.length === 0 ? <section className="card my-tasks-empty"><h2>目前沒有可讀取的本人指派任務</h2><p>調度員指派後可重新讀取；撤派或改派的舊任務不會保留在此頁。此清單不提供完成狀態。</p></section> : <>
        {data.content.some((task) => task.policyMode === 'SYNTHETIC_TEST_ONLY') && <aside className="my-tasks-synthetic" aria-label="合成工程驗證"><strong>合成工程驗證</strong><p>目前資料屬合成測試情境，不代表正式營運或法規適格核准。</p></aside>}
        <div className="my-tasks-list">{data.content.map((task) => <TaskCard key={task.assignmentId} task={task} />)}</div>
      </>}
      <nav className="my-tasks-pager" aria-label="本人任務分頁"><p>{data.totalPages ? `第 ${data.page + 1} / ${data.totalPages} 頁` : '目前無資料'}</p><div><button type="button" className="secondary-button" disabled={data.page === 0} onClick={() => read(data.page - 1)}>上一頁本人任務</button><button type="button" className="secondary-button" disabled={data.page + 1 >= data.totalPages || data.page >= 100000} onClick={() => read(data.page + 1)}>下一頁本人任務</button></div></nav>
      <p className="my-tasks-help">路線或聯絡資訊需要調整時，請聯絡調度員。</p>
    </>}
  </section>
}
