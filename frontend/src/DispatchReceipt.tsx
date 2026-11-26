import type { TmsWriteResult } from './tmsApi'
import { AssignmentView } from './DispatchShared'

export default function DispatchReceipt({ result }: { result: TmsWriteResult }) {
  return <section className="card dispatch-receipt" aria-labelledby="dispatch-receipt-heading"><p className="eyebrow">COMMAND RECEIPT</p><h2 id="dispatch-receipt-heading">本次操作收據</h2><p role="status">{result.replayed ? '已取回原次歷史成功結果；這不是目前派工狀態。' : '本次操作已成功確認；以下是操作完成當時的快照。'}</p><p>本次結果任務版本：<strong>{result.data.generation}</strong>。{result.replayed && '重播不會重新占用資源或新增事件。'}下一次操作前，請明確重新讀取目前派工，不以此收據取代當前狀態。</p><AssignmentView assignment={result.data.assignment} snapshot /></section>
}
