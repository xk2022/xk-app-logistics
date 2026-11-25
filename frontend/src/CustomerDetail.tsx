import { useEffect, useState } from 'react'
import { ApiError } from './api'
import { getCustomer } from './crmApi'
import type { Customer } from './crmApi'
import CustomerForm from './CustomerForm'
import CustomerChildren from './CustomerChildren'
import { AdmError } from './AdmShared'

export default function CustomerDetail({ id, onChanged, onExpired }: { id: string; onChanged: () => void; onExpired: () => void }) {
  const [revision, setRevision] = useState(0)
  const key = `${id}/${revision}`
  const [state, setState] = useState<{ key: string; customer?: Customer; error?: unknown }>()
  const [notice, setNotice] = useState('')
  useEffect(() => {
    let active = true
    const controller = new AbortController()
    void getCustomer(id, controller.signal).then((customer) => { if (active) setState({ key, customer }) })
      .catch((error: unknown) => { if (active) { setState({ key, error }); if (error instanceof ApiError && error.status === 401) onExpired() } })
    return () => { active = false; controller.abort() }
  }, [id, key, onExpired])
  const loading = state?.key !== key
  const customer = loading ? undefined : state?.customer
  return <section className="crm-customer-detail" aria-labelledby="customer-detail-heading"><div className="section-title"><div><p className="eyebrow">CUSTOMER DETAILS</p><h2 id="customer-detail-heading">客戶明細{customer ? `：${customer.name}` : ''}</h2></div><button className="secondary-button" disabled={loading} onClick={() => { setNotice(''); setRevision((value) => value + 1) }}>重新載入客戶</button></div>{notice && <p className="success-notice" role="status">{notice}</p>}{loading ? <p role="status">正在載入客戶明細…</p> : state?.error ? <><AdmError error={state.error} /><p>無法載入客戶明細，請重新載入或重新選擇客戶。</p></> : customer && <>
    {!customer.enabled && <p className="warning-notice">此客戶已停用，不可供新引用。歷史資料、聯絡人與常用地址仍可維護。</p>}
    <CustomerForm key={`${customer.id}/${customer.version}/${revision}`} customer={customer} onExpired={onExpired} onSaved={(updated) => { setState({ key, customer: updated }); setNotice('客戶資料已更新。'); onChanged() }} />
    <CustomerChildren key={`${customer.id}/contacts`} customer={customer} kind="contacts" onExpired={onExpired} />
    <CustomerChildren key={`${customer.id}/addresses`} customer={customer} kind="addresses" onExpired={onExpired} />
  </>}</section>
}
