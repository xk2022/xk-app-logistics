import { useContext, useLayoutEffect, useRef, useState } from 'react'
import type { EditorItem, EditorTask } from './orderEditorModel'
import { OrderField, OrderTimeField } from './OrderField'
import { OrderFieldIssues } from './orderFieldContext'

export interface OrderTasksEditorProps {
  tasks: EditorTask[]
  items: EditorItem[]
  disabled: boolean
  onChange: (tasks: EditorTask[]) => void
  onAdd: () => void
  onRemove: (ref: string) => void
}

export default function OrderTasksEditor({ tasks, items, disabled, onChange, onAdd, onRemove }: OrderTasksEditorProps) {
  const [contactRemoval, setContactRemoval] = useState<string | null>(null)
  const issues = useContext(OrderFieldIssues)
  const focusAfterMove = useRef<string | null>(null)
  useLayoutEffect(() => {
    const ref = focusAfterMove.current
    if (ref) {
      document.getElementById(`order-task-${ref}-group`)?.focus()
      focusAfterMove.current = null
    }
  }, [tasks])
  const update = (ref: string, changes: Partial<EditorTask>) => {
    if (!disabled) onChange(tasks.map((task) => task.clientRef === ref ? { ...task, ...changes } : task))
  }
  const move = (index: number, direction: -1 | 1) => {
    const next = index + direction
    if (disabled || next < 0 || next >= tasks.length) return
    const reordered = [...tasks]
    ;[reordered[index], reordered[next]] = [reordered[next], reordered[index]]
    focusAfterMove.current = tasks[index].clientRef
    onChange(reordered)
  }
  const removeContact = (task: EditorTask) => {
    if (disabled) return
    if (task.siteContact && (task.siteContact.name !== '' || task.siteContact.phone !== '')) setContactRemoval(task.clientRef)
    else { update(task.clientRef, { siteContact: null }); setContactRemoval(null) }
  }
  const changePredecessor = (task: EditorTask, ref: string, checked: boolean) => {
    update(task.clientRef, { predecessorRefs: checked
      ? task.predecessorRefs.includes(ref) ? [...task.predecessorRefs] : [...task.predecessorRefs, ref]
      : task.predecessorRefs.filter((value) => value !== ref) })
  }

  return <section className="order-tasks-editor" aria-labelledby="order-tasks-heading" data-order-path="/tasks" tabIndex={-1}>
    <div className="order-section-heading"><div><h2 id="order-tasks-heading">運送任務</h2><p>每段任務連到一個項目；排列位置不代表執行先後。</p></div><button className="secondary-button" type="button" disabled={disabled} onClick={onAdd}>新增任務</button></div>
    {tasks.length === 0 && <p className="order-empty">尚無任務。草稿可先保存，完整路線可稍後補齊。</p>}
    {tasks.map((task, index) => {
      const prefix = `order-task-${task.clientRef}`
      const path = `/tasks/${index}`
      const otherTasks = tasks.filter((other) => other.clientRef !== task.clientRef)
      const invalidPredecessors = task.predecessorRefs.filter((ref) => !otherTasks.some((other) => other.clientRef === ref))
      const missingItem = task.itemRef !== '' && !items.some((item) => item.clientRef === task.itemRef)
      const itemIssues = issues.filter((issue) => issue.path === `${path}/itemRef`)
      const requiredIssues = issues.filter((issue) => issue.path === `${path}/requiredTask`)
      const dependencyIssues = issues.filter((issue) => issue.path === `${path}/predecessorRefs` || issue.path.startsWith(`${path}/predecessorRefs/`))
      return <fieldset key={task.clientRef} id={`${prefix}-group`} className="order-task-card" disabled={disabled} data-order-path={path} tabIndex={-1}>
        <legend>任務 {index + 1}</legend>
        {task.taskNo && <span className="order-task-number">任務單號 {task.taskNo}</span>}
        <div className="order-row-actions">
          <button type="button" className="text-button" disabled={disabled || index === 0} aria-label={`上移任務 ${index + 1}`} onClick={() => move(index, -1)}>上移</button>
          <button type="button" className="text-button" disabled={disabled || index === tasks.length - 1} aria-label={`下移任務 ${index + 1}`} onClick={() => move(index, 1)}>下移</button>
          <button type="button" className="text-button order-remove" disabled={disabled} aria-label={`移除任務 ${index + 1}`} onClick={() => onRemove(task.clientRef)}>移除任務</button>
        </div>
        <div className="order-field-grid">
          <div className="order-field"><label htmlFor={`${prefix}-item`}>所屬項目</label><select id={`${prefix}-item`} data-order-path={`${path}/itemRef`} value={task.itemRef} disabled={disabled} onChange={(event) => update(task.clientRef, { itemRef: event.target.value })} aria-invalid={itemIssues.length > 0 || missingItem ? true : undefined} aria-describedby={`${prefix}-item-help${itemIssues.length ? ` ${prefix}-item-errors` : ''}`}>
            <option value="">請選擇本單項目</option>
            {missingItem && <option value={task.itemRef}>失聯項目：{task.itemRef}</option>}
            {items.map((item, itemIndex) => <option key={item.clientRef} value={item.clientRef}>項目 {itemIndex + 1}{item.sequence ? `（序號 ${item.sequence}）` : ''}{item.containerNo ? ` · ${item.containerNo}` : ''}</option>)}
          </select><small id={`${prefix}-item-help`}>{items.length === 0 ? '請先新增項目，再指定任務的所屬項目。' : '只可連到本次草稿中的項目；不會因更換所屬項目而清除其他欄位。'}</small>{missingItem && <p className="order-inline-warning">所屬項目不存在，請明確改選後再保存。</p>}{itemIssues.length > 0 && <div id={`${prefix}-item-errors`}>{itemIssues.map((issue, issueIndex) => <p className="order-field-error" key={issueIndex}>{issue.message}</p>)}</div>}</div>
          <OrderField id={`${prefix}-kind`} path={`${path}/taskKind`} label="作業類型" value={task.taskKind} disabled={disabled} onChange={(value) => update(task.clientRef, { taskKind: value })} maxLength={200} hint="可留空；不限定候選作業名稱。" />
          <div className="order-field"><label htmlFor={`${prefix}-required`}>必要任務標記</label><select id={`${prefix}-required`} data-order-path={`${path}/requiredTask`} value={task.requiredTask} disabled={disabled} onChange={(event) => update(task.clientRef, { requiredTask: event.target.value as '' | 'true' })} aria-invalid={requiredIssues.length > 0 ? true : undefined} aria-describedby={`${prefix}-required-help${requiredIssues.length ? ` ${prefix}-required-errors` : ''}`}><option value="">未知</option><option value="true">明確必要</option></select><small id={`${prefix}-required-help`}>不提供任意免除必要義務的操作。</small>{requiredIssues.length > 0 && <div id={`${prefix}-required-errors`}>{requiredIssues.map((issue, issueIndex) => <p className="order-field-error" key={issueIndex}>{issue.message}</p>)}</div>}</div>
          <OrderField id={`${prefix}-pickup-name`} path={`${path}/pickupName`} label="起點名稱" value={task.pickupName} disabled={disabled} onChange={(value) => update(task.clientRef, { pickupName: value })} maxLength={200} />
          <OrderField id={`${prefix}-pickup-address`} path={`${path}/pickupAddress`} label="起點地址" value={task.pickupAddress} disabled={disabled} onChange={(value) => update(task.clientRef, { pickupAddress: value })} maxLength={1000} multiline />
          <OrderField id={`${prefix}-delivery-name`} path={`${path}/deliveryName`} label="終點名稱" value={task.deliveryName} disabled={disabled} onChange={(value) => update(task.clientRef, { deliveryName: value })} maxLength={200} />
          <OrderField id={`${prefix}-delivery-address`} path={`${path}/deliveryAddress`} label="終點地址" value={task.deliveryAddress} disabled={disabled} onChange={(value) => update(task.clientRef, { deliveryAddress: value })} maxLength={1000} multiline />
          <OrderTimeField id={`${prefix}-start`} path={`${path}/plannedStartAt`} label="預定開始" value={task.plannedStartAt} disabled={disabled} onChange={(value) => update(task.clientRef, { plannedStartAt: value })} />
          <OrderTimeField id={`${prefix}-end`} path={`${path}/plannedEndAt`} label="預定結束" value={task.plannedEndAt} disabled={disabled} onChange={(value) => update(task.clientRef, { plannedEndAt: value })} />
        </div>
        <fieldset className="order-contact-fields" disabled={disabled} data-order-path={`${path}/siteContact`} tabIndex={-1}>
          <legend>現場聯絡方式</legend>
          {task.siteContact === null ? <><p>尚未提供現場聯絡方式，可依需要補充。</p><button className="secondary-button" type="button" disabled={disabled} onClick={() => { setContactRemoval(null); update(task.clientRef, { siteContact: { name: '', phone: '' } }) }}>新增現場聯絡資料</button></> : <>
            <div className="order-field-grid"><OrderField id={`${prefix}-contact-name`} path={`${path}/siteContact/name`} label="現場聯絡人姓名" value={task.siteContact.name} disabled={disabled} maxLength={200} onChange={(value) => update(task.clientRef, { siteContact: { ...task.siteContact!, name: value } })} /><OrderField id={`${prefix}-contact-phone`} path={`${path}/siteContact/phone`} label="現場聯絡電話" value={task.siteContact.phone} disabled={disabled} maxLength={50} onChange={(value) => update(task.clientRef, { siteContact: { ...task.siteContact!, phone: value } })} /></div>
            <details className="order-field-note"><summary>進階說明</summary><p>即使姓名與電話尚未填寫，也會保留這組已提供的聯絡資料；若整組不需要，請使用移除操作。</p></details>
            {contactRemoval === task.clientRef ? <div className="order-confirm" role="alertdialog" aria-labelledby={`${prefix}-contact-remove-title`} aria-describedby={`${prefix}-contact-remove-help`}><h3 id={`${prefix}-contact-remove-title`}>移除這段的現場聯絡資料？</h3><p id={`${prefix}-contact-remove-help`}>姓名與電話會從這份草稿移除。保存後才會更新訂單。</p><div className="order-row-actions"><button className="secondary-button" type="button" disabled={disabled} onClick={() => { update(task.clientRef, { siteContact: null }); setContactRemoval(null) }}>確認移除聯絡資料</button><button className="text-button" type="button" disabled={disabled} onClick={() => setContactRemoval(null)}>保留聯絡資料</button></div></div> : <button className="text-button order-remove" type="button" disabled={disabled} onClick={() => removeContact(task)}>移除現場聯絡資料</button>}
          </>}
        </fieldset>
        <fieldset className="order-dependencies" disabled={disabled} data-order-path={`${path}/predecessorRefs`} tabIndex={-1} aria-invalid={dependencyIssues.length > 0 ? true : undefined} aria-describedby={dependencyIssues.length ? `${prefix}-dependency-errors` : undefined}>
          <legend>前置任務</legend><p>可連到本單不同項目的任務；不可指向自己、失聯任務或形成循環。</p>
          {otherTasks.length === 0 && invalidPredecessors.length === 0 && <p className="order-field-note">尚無其他任務可設定為前置。</p>}
          {otherTasks.map((other) => {
            const otherIndex = tasks.findIndex((candidate) => candidate.clientRef === other.clientRef)
            return <label className="order-dependency-option" key={other.clientRef}><input type="checkbox" data-order-path={task.predecessorRefs.includes(other.clientRef) ? `${path}/predecessorRefs/${task.predecessorRefs.indexOf(other.clientRef)}` : undefined} disabled={disabled} checked={task.predecessorRefs.includes(other.clientRef)} onChange={(event) => changePredecessor(task, other.clientRef, event.target.checked)} /><span>任務 {otherIndex + 1}{other.taskKind ? ` · ${other.taskKind}` : other.taskNo ? ` · ${other.taskNo}` : ''}</span></label>
          })}
          {invalidPredecessors.map((ref) => <label className="order-dependency-option order-inline-warning" key={ref}><input type="checkbox" data-order-path={`${path}/predecessorRefs/${task.predecessorRefs.indexOf(ref)}`} checked disabled={disabled} onChange={(event) => changePredecessor(task, ref, event.target.checked)} /><span>{ref === task.clientRef ? '無效：指向自己' : '失聯前置任務'}<small>請明確取消這筆關聯後再保存。</small></span></label>)}
          {dependencyIssues.length > 0 && <div id={`${prefix}-dependency-errors`}>{dependencyIssues.map((issue, issueIndex) => <p className="order-field-error" key={issueIndex}>{issue.message}</p>)}</div>}
        </fieldset>
        <details className="order-saved-identity"><summary>任務識別</summary><OrderField id={`${prefix}-ref`} path={`${path}/clientRef`} label="任務參照" value={task.clientRef} readOnly disabled={disabled} onChange={() => {}} hint="供前置關聯使用；移動位置不會變更。" />{task.serverId && <dl><div><dt>任務 UUID</dt><dd>{task.serverId}</dd></div></dl>}</details>
      </fieldset>
    })}
  </section>
}
