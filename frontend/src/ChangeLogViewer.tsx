import { useState } from 'react'
import { getChangeLogs } from './admApi'
import { AdmError, Pager } from './AdmShared'
import { useAdmPage } from './useAdmPage'

export default function ChangeLogViewer({ onExpired }: { onExpired: () => void }) {
  const [page, setPage] = useState(0)
  const [revision, setRevision] = useState(0)
  const { loading, data, error } = useAdmPage(getChangeLogs, page, revision, onExpired)
  return <section><div className="section-title"><div><h1>異動紀錄</h1><p className="page-description">檢視字典與參數的操作人、時間及前後值。紀錄僅供查閱。</p></div><button className="secondary-button" disabled={loading} onClick={() => setRevision((value) => value + 1)}>重新載入異動紀錄</button></div><AdmError error={error} /><div className="card">{loading ? <p role="status">正在載入異動紀錄…</p> : error ? <p>無法載入異動紀錄，請重新載入。</p> : <><div className="table-scroll"><table><caption className="sr-only">ADM 異動紀錄列表</caption><thead><tr><th scope="col">時間／操作人</th><th scope="col">對象</th><th scope="col">動作</th><th scope="col">前後值</th></tr></thead><tbody>{data?.content.map((log) => <tr key={log.id}><th scope="row"><time dateTime={log.occurredAt}>{new Intl.DateTimeFormat('zh-TW', { timeZone: 'Asia/Taipei', dateStyle: 'short', timeStyle: 'medium' }).format(new Date(log.occurredAt))}</time><small>{log.actorUsername}</small></th><td>{log.targetType}<small className="record-id">{log.targetId}</small></td><td>{log.action}</td><td><details className="change-details"><summary>檢視前後值</summary><h3>變更前</h3><pre>{JSON.stringify(log.before, null, 2)}</pre><h3>變更後</h3><pre>{JSON.stringify(log.after, null, 2)}</pre></details></td></tr>)}</tbody></table></div>{data?.content.length === 0 && <p className="empty-state">尚無異動紀錄。</p>}{data && <Pager label="異動紀錄分頁" {...data} disabled={false} onPage={setPage} />}</>}</div></section>
}
