import { useCallback, useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from './api'
import { createAddress, createContact, getAddresses, getContacts, nullable, updateAddress, updateContact, validateEmail, validateText } from './crmApi'
import type { Contact, Customer, CustomerAddress } from './crmApi'
import { AdmError, Pager } from './AdmShared'
import { useAdmPage } from './useAdmPage'
import type { PageData } from './admApi'

type Child = Contact | CustomerAddress
type ChildKind = 'contacts' | 'addresses'
export default function CustomerChildren({ customer, kind, onExpired }: { customer: Customer; kind: ChildKind; onExpired: () => void }) {
  const isContact = kind === 'contacts'
  const title = isContact ? '聯絡人' : '常用地址'
  const [page, setPage] = useState(0)
  const [revision, setRevision] = useState(0)
  const load = useCallback((page: number, signal?: AbortSignal): Promise<PageData<Child>> => isContact ? getContacts(customer.id, page, signal) : getAddresses(customer.id, page, signal), [customer.id, isContact])
  const { loading, data, error: loadError } = useAdmPage(load, page, revision, onExpired)
  const [editing, setEditing] = useState<Child | null>(null)
  const [values, setValues] = useState<Record<string, string>>({})
  const [enabled, setEnabled] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState('')
  const active = useRef(true)
  useEffect(() => { active.current = true; return () => { active.current = false } }, [])
  const fields = isContact ? [
    { key: 'name', label: '聯絡人姓名', maximum: 200, required: true }, { key: 'title', label: '聯絡人職稱', maximum: 100 },
    { key: 'phone', label: '聯絡人電話', maximum: 50 }, { key: 'email', label: '聯絡人電子郵件', maximum: 254 },
  ] : [
    { key: 'label', label: '地址標籤', maximum: 100 }, { key: 'address', label: '地址內容', maximum: 1000, required: true },
    { key: 'addressType', label: '地址類型說明', maximum: 40 },
  ]
  const clear = () => { setEditing(null); setValues({}); setEnabled(true) }
  const reload = () => { clear(); setError(null); setRevision((value) => value + 1) }
  const edit = (child: Child) => {
    setEditing(child); setEnabled(child.enabled); setError(null); setNotice('')
    setValues(Object.fromEntries(fields.map((field) => [field.key, String((child as unknown as Record<string, unknown>)[field.key] ?? '')])))
  }
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setBusy(true); setError(null); setNotice('')
    try {
      fields.forEach((field) => validateText(values[field.key] ?? '', field.label, field.maximum, field.required))
      if (isContact) {
        validateEmail(values.email ?? '')
        const body = { name: values.name ?? '', title: nullable(values.title ?? ''), phone: nullable(values.phone ?? ''), email: nullable(values.email ?? ''), enabled }
        if (editing) await updateContact(customer.id, editing.id, { ...body, version: editing.version }); else await createContact(customer.id, body)
      } else {
        const body = { label: nullable(values.label ?? ''), address: values.address ?? '', addressType: nullable(values.addressType ?? ''), enabled }
        if (editing) await updateAddress(customer.id, editing.id, { ...body, version: editing.version }); else await createAddress(customer.id, body)
      }
      if (active.current) { setNotice(`${title}已${editing ? '更新' : '建立'}。`); clear(); setRevision((value) => value + 1) }
    } catch (failure) { if (active.current) { setError(failure); if (failure instanceof ApiError && failure.status === 401) onExpired() } }
    finally { if (active.current) setBusy(false) }
  }
  return <section className="crm-child-section" aria-labelledby={`${kind}-heading`}>
    <div className="section-title"><div><h2 id={`${kind}-heading`}>{title}</h2><p className="page-description">固定屬於客戶 {customer.customerCode}，不提供跨客戶移轉或刪除。</p></div><button className="secondary-button" disabled={busy || loading} onClick={reload}>重新載入{title}</button></div>
    {notice && <p className="success-notice" role="status">{notice}</p>}<AdmError error={error ?? loadError} />
    <div className="adm-grid"><section className="card" aria-label={`${title}列表`}><h3>{title}列表</h3>{loading ? <p role="status">正在載入{title}…</p> : loadError ? <p>無法載入{title}，請重新載入。</p> : <><div className="table-scroll"><table><caption className="sr-only">{customer.name}的{title}</caption><thead><tr><th scope="col">{isContact ? '姓名／職稱' : '標籤／類型'}</th><th scope="col">{isContact ? '電話／電子郵件' : '地址'}</th><th scope="col">狀態</th><th scope="col">操作</th></tr></thead><tbody>{data?.content.map((child) => <tr key={child.id}><th scope="row">{'name' in child ? child.name : child.label ?? '未填標籤'}<small>{'name' in child ? child.title : child.addressType}</small></th><td className="crm-detail-cell">{'name' in child ? <>{child.phone ?? '未填電話'}<small>{child.email}</small></> : child.address}</td><td>{child.enabled ? '啟用' : '停用'}</td><td><button className="text-button" disabled={busy} aria-label={`編輯${isContact ? '聯絡人' : '地址'} ${'name' in child ? child.name : child.label ?? child.address}`} onClick={() => edit(child)}>編輯</button></td></tr>)}</tbody></table></div>{data?.content.length === 0 && <p className="empty-state">尚無{title}資料。</p>}{data && <Pager label={`${title}分頁`} {...data} disabled={busy} onPage={(value) => { clear(); setError(null); setPage(value) }} />}</>}</section>
      <section className="card" aria-labelledby={`${kind}-form-heading`}><h3 id={`${kind}-form-heading`}>{editing ? '編輯' : '新增'}{title}</h3><form className="stack-form" aria-label={`${title}表單`} onSubmit={(event) => { void submit(event) }}>{fields.map((field) => <div className="crm-field" key={field.key}><label htmlFor={`${kind}-${field.key}`}>{field.label}{!field.required && <small aria-hidden="true">選填</small>}</label>{field.key === 'address' ? <textarea id={`${kind}-${field.key}`} required={field.required} disabled={busy} maxLength={field.maximum * 2} rows={4} value={values[field.key] ?? ''} onChange={(event) => setValues({ ...values, [field.key]: event.target.value })} /> : <input id={`${kind}-${field.key}`} required={field.required} disabled={busy} maxLength={field.maximum * 2} type={field.key === 'phone' ? 'tel' : 'text'} inputMode={field.key === 'email' ? 'email' : undefined} autoComplete="off" value={values[field.key] ?? ''} onChange={(event) => setValues({ ...values, [field.key]: event.target.value })} />}<small>最多 {field.maximum} 字元。{field.key === 'addressType' && '自由描述，不是固定分類選單。'}</small></div>)}<label className="checkbox-label"><input type="checkbox" disabled={busy} checked={enabled} onChange={(event) => setEnabled(event.target.checked)} />啟用{isContact ? '聯絡人' : '地址'}</label><div className="form-actions"><button className="primary-button" disabled={busy || loading || Boolean(loadError)}>{busy ? '儲存中…' : `${editing ? '儲存' : '建立'}${isContact ? '聯絡人' : '地址'}`}</button>{editing && <button className="secondary-button" type="button" disabled={busy} onClick={clear}>取消{title}編輯</button>}</div></form></section>
    </div>
  </section>
}
