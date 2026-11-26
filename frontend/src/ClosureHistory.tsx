import { useEffect, useState } from 'react'
import { getCompletionConfirmationHistory, getOrderClosureHistory } from './closureApi'
import type { ManagementConfirmation, ManagementPage, OrderClosureWitness } from './closureApi'
import { managementSafeExit } from './closureModel'
import { ClosureFact, ConfirmationFact, ManagementError, ManagementPager } from './ClosureShared'
export default function ClosureHistory({ kind, id, disabled, onExpired }: { kind: 'confirm' | 'close'; id: string; disabled: boolean; onExpired: () => void }) {
  const [page, setPage] = useState(0), [revision, setRevision] = useState(0), [state, setState] = useState<{key:string;data?:ManagementPage<ManagementConfirmation | OrderClosureWitness>;error?:unknown}>()
  const key = `${kind}/${id}/${page}/${revision}`, title = kind === 'confirm' ? '管理確認歷程' : '訂單結案歷程'
  useEffect(() => { if (disabled) return; const controller = new AbortController(); let active = true; const request = kind === 'confirm' ? getCompletionConfirmationHistory(id, { page, size: 5 }, controller.signal) : getOrderClosureHistory(id, { page, size: 5 }, controller.signal); void request.then((data) => { if (active) setState({ key, data }) }).catch((error: unknown) => { if (active) { setState({ key, error }); if (managementSafeExit(error)) onExpired() } }); return () => { active = false; controller.abort() } }, [kind, id, page, key, disabled, onExpired])
  const current = state?.key === key ? state : undefined
  return <section className="card" aria-label={title}><div className="closure-section-heading"><h2>{title}</h2><button type="button" className="text-button" disabled={disabled} onClick={() => setRevision((v) => v + 1)}>重新讀取{title}</button></div>{!current ? <p role="status">正在讀取{title}…</p> : current.error ? <ManagementError error={current.error} /> : <>{current.data?.content.length === 0 ? <p>尚無本流程的{title}紀錄。</p> : <ul className="closure-history-list">{current.data?.content.map((row) => <li key={row.id}>{kind === 'confirm' ? <ConfirmationFact fact={row as ManagementConfirmation} /> : <ClosureFact fact={row as OrderClosureWitness} />}</li>)}</ul>}{current.data && <ManagementPager page={page} totalPages={current.data.totalPages} totalElements={current.data.totalElements} disabled={disabled} label={title} onPage={setPage} />}</>}</section>
}
