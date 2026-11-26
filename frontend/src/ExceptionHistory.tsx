import { useEffect, useState } from 'react'
import { getExceptionHistory } from './exceptionApi'
import type { ExceptionPage, ManagerExceptionEvent } from './exceptionApi'
import { roleLabels } from './api'
import { exceptionEventLabels, exceptionStatusLabels, safeExceptionExit } from './exceptionModel'
import { ExceptionErrorNotice, ExceptionRawText } from './ExceptionShared'
export default function ExceptionHistory({ id, version, disabled, onExpired, onStale }: { id: string; version: number; disabled: boolean; onExpired: () => void; onStale: () => void }) {
  const [page, setPage] = useState(0), [revision, setRevision] = useState(0), [state, setState] = useState<{ key: string; data?: ExceptionPage<ManagerExceptionEvent>; error?: unknown }>()
  const key = `${id}/${page}/${revision}/${version}`
  useEffect(() => {
    if (disabled) return
    const controller = new AbortController(); let active = true
    void getExceptionHistory(id, { page, size: 5 }, controller.signal).then((data) => { if (active) { setState({ key, data }); if (data.content.some((row) => row.toVersion > version)) onStale() } }).catch((error: unknown) => { if (active) { setState({ key, error }); if (safeExceptionExit(error)) onExpired() } })
    return () => { active = false; controller.abort() }
  }, [id, page, key, version, disabled, onExpired, onStale])
  const current = state?.key === key ? state : undefined
  return <section className="card exception-history" aria-labelledby="exception-history-heading"><div className="exception-section-heading"><h2 id="exception-history-heading">異常歷程</h2><button type="button" className="text-button" disabled={disabled} onClick={() => setRevision((value) => value + 1)}>重新讀取異常歷程</button></div><p className="exception-muted">保留原始回報、要求、上傳、明確提交與處置；上傳不會自動完成補件。此頁不會自動改寫目前異常或本機操作。</p>{!current ? <p role="status">正在讀取異常歷程…</p> : current.error ? <ExceptionErrorNotice error={current.error} /> : <>{current.data?.content.length === 0 ? <p className="empty-state">尚無異常歷程。</p> : <ol className="exception-history-list">{current.data?.content.map((event) => <li key={event.id}><article aria-label={`${exceptionEventLabels[event.type]} · 異常版本 ${event.toVersion}`}><h3>{exceptionEventLabels[event.type]} · 異常版本 {event.toVersion}</h3><p className="exception-muted">{roleLabels[event.actor.role]} · {event.actor.displayName} · <time dateTime={event.occurredAt}>{event.occurredAt}</time></p><p>{event.fromStatus ? `${exceptionStatusLabels[event.fromStatus]} → ` : ''}{exceptionStatusLabels[event.toStatus]}</p>{event.note !== null && <ExceptionRawText value={event.note} label="異常事件原文" />}{event.evidenceIds.length > 0 && <p>本次關聯照片：{event.evidenceIds.length} 張</p>}<details className="exception-identity"><summary>事件與關聯識別</summary><p>事件：{event.id}</p>{event.requestId && <p>補件要求：{event.requestId}</p>}{event.evidenceIds.map((photo) => <p key={photo}>照片：{photo}</p>)}</details></article></li>)}</ol>}{current.data && <nav className="exception-pager" aria-label="異常歷程分頁"><span>共 {current.data.totalElements} 筆 · {current.data.totalPages ? `第 ${current.data.page + 1} / ${current.data.totalPages} 頁` : '目前無資料'}</span><div><button type="button" className="secondary-button" disabled={disabled || page === 0} onClick={() => setPage(page - 1)}>上一頁異常歷程</button><button type="button" className="secondary-button" disabled={disabled || page >= 100000 || page + 1 >= current.data.totalPages} onClick={() => setPage(page + 1)}>下一頁異常歷程</button></div></nav>}</>}</section>
}
