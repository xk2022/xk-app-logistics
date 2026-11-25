import { useCallback, useState } from 'react'
import type { FormEvent } from 'react'
import { getCustomers, validateText } from './crmApi'
import type { CustomerQuery } from './crmApi'
import { AdmError, Pager } from './AdmShared'
import { useAdmPage } from './useAdmPage'
import CustomerForm from './CustomerForm'
import CustomerDetail from './CustomerDetail'
import CustomerOptions from './CustomerOptions'

export default function CustomerManager({ onExpired }: { onExpired: () => void }) {
  const [q, setQ] = useState('')
  const [enabled, setEnabled] = useState<CustomerQuery['enabled']>('all')
  const [size, setSize] = useState<5 | 20 | 50>(20)
  const [query, setQuery] = useState<CustomerQuery>({ q: '', enabled: 'all', size: 20 })
  const [page, setPage] = useState(0)
  const [revision, setRevision] = useState(0)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [optionsOpen, setOptionsOpen] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [notice, setNotice] = useState('')
  const load = useCallback((page: number, signal?: AbortSignal) => getCustomers(page, query, signal), [query])
  const { loading, data, error: loadError } = useAdmPage(load, page, revision, onExpired)
  const reload = () => { setError(null); setSelectedId(null); setCreating(false); setRevision((value) => value + 1) }
  const search = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    try { validateText(q, '客戶搜尋', 100); setQuery({ q: q.trim(), enabled, size }); setPage(0); setSelectedId(null); setCreating(false); setError(null); setNotice('') } catch (failure) { setError(failure) }
  }
  return <section className="crm-manager"><div className="section-title"><div><p className="eyebrow">CUSTOMER RELATIONSHIP MANAGEMENT</p><h1>客戶管理</h1><p className="page-description">管理本公司客戶、聯絡人與常用地址。停用保留歷史，不提供刪除。</p></div><div className="crm-heading-actions"><button className="primary-button" onClick={() => { setSelectedId(null); setCreating(true); setNotice(''); setError(null) }}>新增客戶</button><button className="secondary-button" disabled={loading} onClick={reload}>重新載入列表</button></div></div>
    {notice && <p className="success-notice" role="status">{notice}</p>}<AdmError error={error ?? loadError} />
    <section className="card" aria-labelledby="customer-list-heading"><h2 id="customer-list-heading">客戶列表</h2><form className="crm-search-form" aria-label="客戶查詢" onSubmit={search}><div className="crm-search-main"><label htmlFor="customer-search">客戶搜尋</label><input id="customer-search" maxLength={200} placeholder="輸入客戶名稱或代碼" value={q} onChange={(event) => setQ(event.target.value)} /></div><div><label htmlFor="customer-enabled">客戶狀態</label><select id="customer-enabled" value={enabled} onChange={(event) => setEnabled(event.target.value as CustomerQuery['enabled'])}><option value="all">全部</option><option value="true">啟用</option><option value="false">停用</option></select></div><div><label htmlFor="customer-size">每頁筆數</label><select id="customer-size" value={size} onChange={(event) => setSize(Number(event.target.value) as 5 | 20 | 50)}><option value="5">5</option><option value="20">20</option><option value="50">50</option></select></div><button className="secondary-button" disabled={loading}>搜尋客戶</button></form>
      {loading ? <p role="status">正在載入客戶列表…</p> : loadError ? <p>無法載入客戶列表，請重新載入。</p> : <><div className="table-scroll"><table><caption className="sr-only">公司客戶列表</caption><thead><tr><th scope="col">名稱／代碼</th><th scope="col">電話／電子郵件</th><th scope="col">狀態</th><th scope="col">操作</th></tr></thead><tbody>{data?.content.map((customer) => <tr key={customer.id}><th scope="row">{customer.name}<small>{customer.customerCode}</small></th><td className="crm-detail-cell">{customer.phone ?? '未填電話'}<small>{customer.email}</small></td><td>{customer.enabled ? '啟用' : '停用'}</td><td><button className="text-button" aria-label={`檢視客戶 ${customer.customerCode}`} onClick={() => { setSelectedId(customer.id); setCreating(false); setNotice('') }}>明細／編輯</button></td></tr>)}</tbody></table></div>{data?.content.length === 0 && <p className="empty-state">目前沒有符合條件的客戶。可調整查詢，或新增客戶。</p>}{data && <Pager label="客戶列表分頁" {...data} disabled={false} onPage={(value) => { setSelectedId(null); setCreating(false); setPage(value) }} />}</>}
    </section>
    <div className="crm-options-toggle"><button className="text-button" aria-expanded={optionsOpen} onClick={() => setOptionsOpen((value) => !value)}>{optionsOpen ? '收合有效客戶選項' : '查看有效客戶選項'}</button></div>{optionsOpen && <CustomerOptions revision={revision} onExpired={onExpired} />}
    {creating && <div className="crm-create-section"><CustomerForm onExpired={onExpired} onCancel={() => setCreating(false)} onSaved={(customer) => { setNotice('客戶已建立。'); setCreating(false); setSelectedId(customer.id); setRevision((value) => value + 1) }} /></div>}
    {selectedId && <CustomerDetail key={selectedId} id={selectedId} onExpired={onExpired} onChanged={() => setRevision((value) => value + 1)} />}
  </section>
}
