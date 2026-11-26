import { useEffect, useState } from 'react'
import { TmsApiError, getTaskDispatch } from './tmsApi'
import type { Assignment, TmsPage } from './tmsApi'
import { assignmentLabels, safeExitRequired } from './tmsModel'
import { AssignmentView } from './DispatchShared'

export default function DispatchHistory({ taskId, generation, initial, disabled, onExpired, onStale }: { taskId: string; generation: number; initial: TmsPage<Assignment>; disabled: boolean; onExpired: () => void; onStale: () => void }) {
  const [page, setPage] = useState(initial.page)
  const [attempt, setAttempt] = useState(0)
  const [state, setState] = useState<{ key: string; data?: TmsPage<Assignment>; error?: unknown }>()
  const key = `${taskId}/${page}/${attempt}`
  useEffect(() => {
    if (disabled || page === initial.page && attempt === 0) return
    const controller = new AbortController(); let active = true
    void getTaskDispatch(taskId, { historyPage: page, historySize: initial.size }, controller.signal).then((detail) => { if (active) { setState({ key, data: detail.history }); if (detail.task.generation !== generation) onStale() } }).catch((error: unknown) => { if (active) { setState({ key, error }); if (safeExitRequired(error)) onExpired() } })
    return () => { active = false; controller.abort() }
  }, [disabled, taskId, page, attempt, key, generation, initial.page, initial.size, onExpired, onStale])
  const current: { data?: TmsPage<Assignment>; error?: unknown } | undefined = page === initial.page && attempt === 0 ? { data: initial } : state?.key === key ? state : undefined
  return <section className="dispatch-history" aria-labelledby="dispatch-history-heading"><div className="dispatch-section-heading"><h2 id="dispatch-history-heading">派工歷史</h2><button type="button" className="text-button" disabled={disabled || !current} onClick={() => setAttempt((value) => value + 1)}>重新讀取派工歷史</button></div><p className="dispatch-muted">依指派時任務版本排序，包含當前派工；版本可能因撤派而跳號。歷史不代表目前仍占用；完成不會改寫原派工與撤改記錄。</p>{!current ? <p role="status">正在讀取派工歷史…</p> : current.error ? <p className="error-notice" role="alert">{current.error instanceof TmsApiError ? current.error.message : '歷史讀取失敗，請重新讀取。'}</p> : <>{current.data?.content.length === 0 ? <p className="empty-state">尚無派工歷史。</p> : <ul className="dispatch-history-list">{current.data?.content.map((assignment) => <li key={assignment.id}><details><summary>指派版本 {assignment.assignedGeneration} · {assignmentLabels[assignment.status]} · {assignment.driver.name} · {assignment.mainVehicle.plateNo}</summary><AssignmentView assignment={assignment} snapshot /></details></li>)}</ul>}{current.data && <nav className="dispatch-pager" aria-label="派工歷史分頁"><span>共 {current.data.totalElements} 筆 · {current.data.totalPages ? `第 ${current.data.page + 1} / ${current.data.totalPages} 頁` : '目前無資料'}</span><div><button type="button" className="secondary-button compact" disabled={disabled || page === 0} onClick={() => setPage(page - 1)}>上一頁歷史</button><button type="button" className="secondary-button compact" disabled={disabled || page >= 100000 || page + 1 >= current.data.totalPages} onClick={() => setPage(page + 1)}>下一頁歷史</button></div></nav>}</>}</section>
}
