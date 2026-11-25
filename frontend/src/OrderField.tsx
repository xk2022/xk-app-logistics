import { useContext, useId } from 'react'
import { OrderFieldIssues } from './orderFieldContext'
import { taipeiPreview } from './orderEditorModel'

export interface OrderFieldProps { id: string; label: string; value: string; onChange: (value: string) => void; disabled?: boolean; maxLength?: number; multiline?: boolean; hint?: string; readOnly?: boolean; path?: string; inputMode?: 'text' | 'numeric' | 'decimal' }
export function OrderField({ id, label, value, onChange, disabled, maxLength, multiline, hint, readOnly, path, inputMode }: OrderFieldProps) {
  const issues = useContext(OrderFieldIssues).filter((issue) => issue.path === path)
  const hintId = useId()
  const unusual = value.includes('\u0000') || value.includes('\r') || !multiline && value.includes('\n')
  const attributes = { id, disabled, readOnly, value, 'data-order-path': path, 'aria-invalid': issues.length > 0 ? true as const : undefined, 'aria-describedby': hint || unusual || issues.length ? hintId : undefined, maxLength: maxLength === undefined ? undefined : maxLength * 2 }
  return <div className="order-field"><label htmlFor={id}>{label}</label>{multiline ? <textarea {...attributes} rows={3} onChange={(event) => onChange(event.target.value)} /> : <input {...attributes} type="text" inputMode={inputMode} onChange={(event) => onChange(event.target.value)} />}<div id={hintId}>{hint && <small>{hint}</small>}{unusual && <div className="order-raw-note"><small>原值含特殊字元或換行；未修改時原樣保留。修改此欄位將以畫面輸入取代原值。</small><details><summary>查看原值字元表示</summary><code>{JSON.stringify(value)}</code></details></div>}{issues.map((issue, index) => <p className="order-field-error" key={index}>{issue.message}</p>)}</div></div>
}
export function OrderTimeField(props: OrderFieldProps) {
  const preview = taipeiPreview(props.value)
  return <div><OrderField {...props} hint={props.hint ?? '保留時區及最多六位小數秒，例如 2026-12-01T09:00:00+08:00；留空表示未知。'} />{preview && <p className="order-time-preview">台北時間：{preview}（輸入原值不轉換）</p>}</div>
}
