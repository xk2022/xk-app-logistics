import { useEffect, useRef } from 'react'
import type { Order } from './tomApi'
import { isEditableOrder, isWaitingForDispatch } from './orderAcceptanceModel'
import './order-acceptance.css'

export interface OrderAcceptancePanelProps { order?: Order; dirty: boolean; disabled: boolean; confirming: boolean; onOpen: () => void; onCancel: () => void; onConfirm: () => void }
export default function OrderAcceptancePanel({ order, dirty, disabled, confirming, onOpen, onCancel, onConfirm }: OrderAcceptancePanelProps) {
  const confirm = useRef<HTMLButtonElement>(null)
  const opener = useRef<HTMLButtonElement>(null)
  useEffect(() => { if (confirming) confirm.current?.focus() }, [confirming])
  const canStart = Boolean(order && isEditableOrder(order) && !dirty && !disabled)
  return <section className={`order-formal-acceptance ${order?.acceptanceStatus === 'ACCEPTED' ? 'order-formal-accepted' : ''}`} aria-labelledby="order-formal-acceptance-heading">
    <div className="order-formal-heading"><div><p className="eyebrow">ORDER ACCEPTANCE</p><h2 id="order-formal-acceptance-heading">{order && isWaitingForDispatch(order) ? '已受理・待派車' : order?.acceptanceStatus === 'ACCEPTED' ? '訂單已受理' : '正式受理'}</h2><p>{order?.acceptanceStatus === 'ACCEPTED' ? '以已保存訂單狀態為準。受理內容僅供檢視，目前沒有撤回、派工或資源占用操作。' : '完整性檢核提供缺漏提示；正式受理必須由您明確確認，並由伺服器重新驗證目前訂單。'}</p></div><button ref={opener} type="button" className="primary-button" disabled={!canStart} onClick={() => { if (canStart) onOpen() }}>正式受理訂單</button></div>
    {!order ? <p className="order-formal-help">請先建立並保存草稿，再進行正式受理。</p> : order.acceptanceStatus === 'DRAFT' && dirty ? <p className="order-formal-help">請先保存本機修改，或處理尚未確認的結果與版本衝突，再受理已保存版本。</p> : !isEditableOrder(order) && order.acceptanceStatus !== 'ACCEPTED' && <p className="order-formal-help">目前訂單狀態不可正式受理。</p>}
    {confirming && order && <section className="order-formal-confirm" role="group" aria-labelledby="order-formal-confirm-heading"><h3 id="order-formal-confirm-heading">正式受理確認</h3><dl><div><dt>訂單單號</dt><dd>{order.orderNo}</dd></div><div><dt>客戶歷史名稱</dt><dd>{order.customerName}</dd></div><div><dt>已保存版本</dt><dd>{order.version}</dd></div></dl><p>確認後，伺服器將重新驗證目前客戶及已核准完整性規則。成功受理的內容將不可再以草稿方式修改；本階段沒有撤回、派車或資源占用。</p><p>受理不代表法規資格或尚待確認的現場政策已獲核准，不會自動派工。</p><div className="order-row-actions"><button ref={confirm} type="button" className="primary-button" disabled={!canStart} onClick={() => { if (canStart) onConfirm() }}>確認正式受理</button><button type="button" className="secondary-button" disabled={disabled} onClick={() => { if (disabled) return; onCancel(); opener.current?.focus() }}>取消受理確認</button></div></section>}
  </section>
}
