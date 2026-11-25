import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from './api'
import { createCustomer, nullable, updateCustomer, validateEmail, validateText } from './crmApi'
import type { Customer } from './crmApi'
import { AdmError } from './AdmShared'

export default function CustomerForm({ customer, onSaved, onExpired, onCancel }: { customer?: Customer; onSaved: (customer: Customer) => void; onExpired: () => void; onCancel?: () => void }) {
  const [code, setCode] = useState(customer?.customerCode ?? '')
  const [name, setName] = useState(customer?.name ?? '')
  const [taxId, setTaxId] = useState(customer?.taxId ?? '')
  const [phone, setPhone] = useState(customer?.phone ?? '')
  const [email, setEmail] = useState(customer?.email ?? '')
  const [enabled, setEnabled] = useState(customer?.enabled ?? true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const active = useRef(true)
  useEffect(() => { active.current = true; return () => { active.current = false } }, [])
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setBusy(true); setError(null)
    try {
      if (!customer && !/^[A-Z0-9][A-Z0-9_-]{0,63}$/.test(code)) throw new ApiError(400, '客戶代碼須為 1–64 個大寫英數字，可包含 _ -，並以英數字開頭。')
      validateText(name, '客戶名稱', 200, true); validateText(taxId, '統一編號', 50); validateText(phone, '客戶電話', 50); validateEmail(email)
      const data = { name, taxId: nullable(taxId), phone: nullable(phone), email: nullable(email), enabled }
      const saved = customer ? await updateCustomer(customer.id, { ...data, version: customer.version }) : await createCustomer({ ...data, customerCode: code })
      if (active.current) onSaved(saved)
    } catch (failure) { if (active.current) { setError(failure); if (failure instanceof ApiError && failure.status === 401) onExpired() } }
    finally { if (active.current) setBusy(false) }
  }
  return <section className="card" aria-labelledby="customer-form-heading"><h2 id="customer-form-heading">{customer ? '編輯客戶資料' : '建立客戶'}</h2><AdmError error={error} /><form className="stack-form" aria-label={customer ? '編輯客戶表單' : '建立客戶表單'} onSubmit={(event) => { void submit(event) }}>
    <label htmlFor="customer-code">客戶代碼</label><input id="customer-code" required disabled={busy} readOnly={Boolean(customer)} maxLength={64} value={code} onChange={(event) => setCode(event.target.value)} aria-describedby="customer-code-help" /><small id="customer-code-help">1–64 個大寫英數字及 _ -，建立後不可修改。</small>
    <label htmlFor="customer-name">客戶名稱</label><input id="customer-name" required disabled={busy} maxLength={400} value={name} onChange={(event) => setName(event.target.value)} aria-describedby="customer-name-help" /><small id="customer-name-help">最多 200 字元，名稱可重複。</small>
    <label htmlFor="customer-tax-id">統一編號</label><input id="customer-tax-id" disabled={busy} maxLength={100} value={taxId} onChange={(event) => setTaxId(event.target.value)} aria-describedby="customer-tax-help" /><small id="customer-tax-help">選填，最多 50 字元；不限定格式或唯一性。</small>
    <label htmlFor="customer-phone">客戶電話</label><input id="customer-phone" type="tel" disabled={busy} maxLength={100} value={phone} onChange={(event) => setPhone(event.target.value)} aria-describedby="customer-phone-help" /><small id="customer-phone-help">選填，最多 50 字元，可填國碼、空格與分機。</small>
    <label htmlFor="customer-email">客戶電子郵件</label><input id="customer-email" inputMode="email" autoComplete="off" disabled={busy} maxLength={508} value={email} onChange={(event) => setEmail(event.target.value)} />
    <label className="checkbox-label"><input type="checkbox" disabled={busy} checked={enabled} onChange={(event) => setEnabled(event.target.checked)} />啟用客戶</label>
    <div className="form-actions"><button className="primary-button" disabled={busy}>{busy ? '儲存中…' : customer ? '儲存客戶' : '建立客戶'}</button>{onCancel && <button className="secondary-button" type="button" disabled={busy} onClick={onCancel}>取消建立</button>}</div>
  </form></section>
}
