import { useLayoutEffect, useRef, useState } from 'react'
import { OrderField, OrderTimeField } from './OrderField'
import type { EditorItem, EditorTask } from './orderEditorModel'

export interface OrderItemsEditorProps { items: EditorItem[]; tasks: EditorTask[]; disabled: boolean; onChange: (items: EditorItem[]) => void; onAdd: () => void; onRemove: (ref: string) => void }
export default function OrderItemsEditor({ items, tasks, disabled, onChange, onAdd, onRemove }: OrderItemsEditorProps) {
  const [removing, setRemoving] = useState<string | null>(null)
  const moved = useRef<string | null>(null)
  useLayoutEffect(() => { if (moved.current) { document.getElementById(`order-item-${moved.current}-group`)?.focus(); moved.current = null } }, [items])
  const update = (index: number, patch: Partial<EditorItem>) => { if (!disabled) onChange(items.map((item, at) => at === index ? { ...item, ...patch } : item)) }
  const move = (index: number, direction: number) => {
    if (disabled || index + direction < 0 || index + direction >= items.length) return
    const result = [...items]; [result[index], result[index + direction]] = [result[index + direction], result[index]]; moved.current = items[index].clientRef; onChange(result)
  }
  return <section className="order-items-section" aria-labelledby="order-items-title" data-order-path="/items" tabIndex={-1}><div className="order-section-heading"><div><h2 id="order-items-title">貨櫃與貨物項目</h2><p>草稿可不含項目。移動畫面順序不會改變項目參照或自動重編序號。</p></div><button type="button" className="secondary-button" disabled={disabled} onClick={onAdd}>新增項目</button></div>
    {items.length === 0 && <p className="order-empty">尚無項目；可先保存最小草稿，再逐步補齊。</p>}
    {items.map((item, index) => {
      const prefix = `order-item-${item.clientRef}`
      const path = `/items/${index}`
      const linked = tasks.filter((task) => task.itemRef === item.clientRef)
      return <fieldset id={`order-item-${item.clientRef}-group`} data-order-path={path} tabIndex={-1} className="order-item-card" disabled={disabled} key={item.clientRef}><legend>項目 {index + 1}</legend><div className="order-row-actions"><button type="button" className="text-button" disabled={disabled || index === 0} aria-label={`上移項目 ${index + 1}`} onClick={() => move(index, -1)}>上移</button><button type="button" className="text-button" disabled={disabled || index === items.length - 1} aria-label={`下移項目 ${index + 1}`} onClick={() => move(index, 1)}>下移</button><button type="button" className="text-button order-remove" disabled={disabled} aria-label={`移除項目 ${index + 1}`} onClick={() => setRemoving(item.clientRef)}>移除</button></div>
        <details className="order-saved-identity"><summary>項目識別</summary><OrderField id={`${prefix}-ref`} label="項目參照" value={item.clientRef} onChange={() => {}} readOnly path={`${path}/clientRef`} hint="用於連結任務；新增後保持不變。" />{item.serverId && <p>已保存識別：<code>{item.serverId}</code></p>}</details>
        <div className="order-field-grid"><OrderField id={`${prefix}-sequence`} label="項目序號" path={`${path}/sequence`} value={item.sequence} onChange={(value) => update(index, { sequence: value })} inputMode="numeric" hint="選填；同單不重複的正整數，不等於畫面位置。" />
          <div className="order-field"><label htmlFor={`${prefix}-kind`}>標的類型</label><select id={`${prefix}-kind`} data-order-path={`${path}/itemKind`} value={item.itemKind} onChange={(event) => update(index, { itemKind: event.target.value as EditorItem['itemKind'] })}><option value="">未知</option><option value="CONTAINER">貨櫃</option><option value="CARGO">一般貨物</option></select><small>變更類型不會清除其他欄位。</small></div>
          <OrderField id={`${prefix}-container-type`} label="櫃型" path={`${path}/containerType`} value={item.containerType} onChange={(value) => update(index, { containerType: value })} maxLength={200} hint="選填自由文字，不限定候選櫃型。" />
          <OrderField id={`${prefix}-container-no`} label="櫃號" path={`${path}/containerNo`} value={item.containerNo} onChange={(value) => update(index, { containerNo: value })} maxLength={200} hint="選填；草稿可以未知。" />
          <OrderField id={`${prefix}-quantity`} label="件數" path={`${path}/packageQty`} value={item.packageQty} onChange={(value) => update(index, { packageQty: value })} inputMode="numeric" hint="1–2147483647；留空表示未知，不以 0 代替。" />
          <OrderField id={`${prefix}-weight`} label="毛重 kg" path={`${path}/grossWeight`} value={item.grossWeight} onChange={(value) => update(index, { grossWeight: value })} inputMode="decimal" maxLength={32} hint="非負十進位字串；0 為已知零，留空是未知。" />
          <OrderField id={`${prefix}-cbm`} label="體積 m³" path={`${path}/cbm`} value={item.cbm} onChange={(value) => update(index, { cbm: value })} inputMode="decimal" maxLength={32} hint="保留十進位精度，不使用指數。" />
          <OrderField id={`${prefix}-last-pickup`} label="最後提櫃日" path={`${path}/lastPickupDate`} value={item.lastPickupDate} onChange={(value) => update(index, { lastPickupDate: value })} hint="選填 YYYY-MM-DD，例如 2026-12-04。" />
          <OrderTimeField id={`${prefix}-cutoff`} label="交櫃截止時間" path={`${path}/deliveryCutoffAt`} value={item.deliveryCutoffAt} onChange={(value) => update(index, { deliveryCutoffAt: value })} />
        </div>
        {removing === item.clientRef && <div className="order-confirm" role="group" aria-label={`確認移除項目 ${index + 1}`}>
          {linked.length ? <><p>此項目仍有 {linked.length} 個任務。請先變更任務所屬項目或明確移除任務；不會自動刪除其他資料。</p><button type="button" className="secondary-button" onClick={() => setRemoving(null)}>保留項目</button></> : <><p>確認移除此項目及其本機內容？保存後才會變更伺服器草稿。</p><button type="button" className="secondary-button" onClick={() => { onRemove(item.clientRef); setRemoving(null) }}>確認移除項目</button><button type="button" className="secondary-button" onClick={() => setRemoving(null)}>保留項目</button></>}
        </div>}
      </fieldset>
    })}
  </section>
}
