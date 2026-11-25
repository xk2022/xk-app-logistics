import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import App from './App'
import type { Account, Role } from './api'
import type { Contact, Customer, CustomerAddress } from './crmApi'

const account: Account = { id: '00000000-0000-0000-0000-000000000001', username: 'test.admin', displayName: '合成管理員', role: 'ADMIN', enabled: true, mustChangePassword: false, version: 1 }
const customer: Customer = { id: '10000000-0000-0000-0000-000000000001', customerCode: 'SYNTHETIC_A', name: '合成客戶甲', taxId: null, phone: null, email: null, enabled: true, version: 2 }
const customerPath = `/api/v1/crm/customers/${customer.id}`
function response(data: unknown, status = 200) {
  return new Response(JSON.stringify({ code: status < 400 ? '0000' : `E0${status}`, message: status === 409 ? '資料版本衝突' : status < 400 ? '成功' : '請確認輸入欄位', data, timestamp: '2026-11-25T12:00:00+08:00' }), { status })
}
function page<T>(content: T[], index = 0, size = 20, totalElements = content.length) {
  return { content, page: index, size, totalElements, totalPages: Math.ceil(totalElements / size) }
}
function transport(override: (path: string, options: RequestInit) => Response | Promise<Response> | undefined = () => undefined, role: Role = 'ADMIN') {
  const fetch = vi.fn(async (path: string, options: RequestInit) => override(path, options) ?? (
    path === '/api/v1/auth/me' ? response({ ...account, role })
      : path === '/api/v1/auth/csrf' ? response({ token: 'synthetic-crm-csrf', headerName: 'X-CSRF-TOKEN' })
        : path === '/api/v1/status' ? response({ service: 'xk-logistics', status: 'UP' })
          : path.startsWith('/api/v1/crm/customers?') ? response(page([customer]))
            : path === customerPath ? response(customer)
              : path.startsWith('/api/v1/crm/') && options.method === 'GET' ? response(page([])) : response(null, 404)
  ))
  vi.stubGlobal('fetch', fetch)
  return fetch
}
async function openCrm() {
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: '客戶管理' }))
  await screen.findByRole('heading', { name: '客戶管理' })
}
async function openDetail() {
  fireEvent.click(await screen.findByRole('button', { name: `檢視客戶 ${customer.customerCode}` }))
  await screen.findByRole('heading', { name: `客戶明細：${customer.name}` })
}
const fill = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label, { exact: true }), { target: { value } })

describe('CRM interface', () => {
  it.each(['ADMIN', 'DISPATCHER'] as Role[])('allows %s to enter customer management', async (role) => {
    const fetch = transport(undefined, role)
    await openCrm()
    expect(await screen.findByRole('button', { name: `檢視客戶 ${customer.customerCode}` })).toBeVisible()
    expect(screen.getByRole('button', { name: '新增客戶' })).toBeEnabled()
    expect(fetch.mock.calls.some(([path]) => path.startsWith('/api/v1/crm/customers?'))).toBe(true)
  })

  it('does not expose CRM navigation or data to a driver', async () => {
    const fetch = transport(undefined, 'DRIVER')
    render(<App />)
    await screen.findByRole('heading', { name: '司機工作台' })
    expect(screen.queryByRole('button', { name: '客戶管理' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '管理客戶' })).not.toBeInTheDocument()
    expect(fetch.mock.calls.some(([path]) => path.startsWith('/api/v1/crm/'))).toBe(false)
  })

  it('creates a customer with optional null tax ID and an international phone', async () => {
    let created: Customer | undefined
    const fetch = transport((path, options) => path === '/api/v1/crm/customers' && options.method === 'POST'
      ? (created = { ...customer, ...JSON.parse(options.body as string), version: 0 }, response(created, 201))
      : path === customerPath && created ? response(created) : undefined)
    await openCrm()
    fireEvent.click(screen.getByRole('button', { name: '新增客戶' }))
    const form = screen.getByRole('form', { name: '建立客戶表單' })
    expect(within(form).getByLabelText('客戶代碼')).toBeRequired()
    expect(within(form).getByLabelText('客戶名稱')).toBeRequired()
    expect(within(form).getByLabelText('統一編號')).not.toBeRequired()
    fill('客戶代碼', 'SYNTHETIC_NEW'); fill('客戶名稱', '合成國際客戶'); fill('客戶電話', '+44 20 7946 0958 ext 12')
    fireEvent.click(screen.getByRole('button', { name: '建立客戶' }))
    expect(await screen.findByText('客戶已建立。')).toBeVisible()
    const write = fetch.mock.calls.find(([path, options]) => path === '/api/v1/crm/customers' && options.method === 'POST')
    expect(JSON.parse(write![1].body as string)).toMatchObject({ customerCode: 'SYNTHETIC_NEW', name: '合成國際客戶', taxId: null, phone: '+44 20 7946 0958 ext 12', email: null })
    expect(write![1]).toMatchObject({ credentials: 'same-origin', headers: { 'X-CSRF-TOKEN': expect.any(String) } })
  })

  it('keeps customer code immutable and reloads a new version after conflict', async () => {
    let reads = 0
    let writes = 0
    const fetch = transport((path, options) => path === customerPath && options.method === 'GET' ? response({ ...customer, version: ++reads === 1 ? 2 : 3 })
      : path === customerPath && options.method === 'PATCH' ? (++writes === 1 ? response(null, 409) : response({ ...customer, name: '已確認名稱', version: 4 })) : undefined)
    await openCrm(); await openDetail()
    expect(screen.getByLabelText('客戶代碼')).toHaveAttribute('readonly')
    fill('客戶名稱', '保留衝突輸入')
    fireEvent.click(screen.getByRole('button', { name: '儲存客戶' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('資料版本衝突')
    expect(screen.getByLabelText('客戶名稱')).toHaveValue('保留衝突輸入')
    fireEvent.click(screen.getByRole('button', { name: '重新載入客戶' }))
    await waitFor(() => expect(screen.getByLabelText('客戶名稱')).toHaveValue(customer.name))
    fill('客戶名稱', '已確認名稱')
    fireEvent.click(screen.getByRole('button', { name: '儲存客戶' }))
    expect(await screen.findByText('客戶資料已更新。')).toBeVisible()
    const bodies = fetch.mock.calls.filter(([, options]) => options.method === 'PATCH').map(([, options]) => JSON.parse(options.body as string))
    expect(bodies.map((body) => body.version)).toEqual([2, 3])
    expect(Object.keys(bodies[1]).sort()).toEqual(['email', 'enabled', 'name', 'phone', 'taxId', 'version'])
  })

  it('maintains multiple contacts and addresses of a disabled customer without moving their parent', async () => {
    const contacts: Contact[] = [
      { id: '20000000-0000-0000-0000-000000000001', customerId: customer.id, name: '合成聯絡甲', title: null, phone: null, email: null, enabled: true, version: 1 },
      { id: '20000000-0000-0000-0000-000000000002', customerId: customer.id, name: '合成聯絡乙', title: '窗口', phone: '+81 3 1234 5678', email: null, enabled: true, version: 0 },
    ]
    const addresses: CustomerAddress[] = [
      { id: '30000000-0000-0000-0000-000000000001', customerId: customer.id, label: '合成地址甲', address: '合成地址一號', addressType: null, enabled: true, version: 1 },
      { id: '30000000-0000-0000-0000-000000000002', customerId: customer.id, label: '合成地址乙', address: '合成地址二號', addressType: '自由用途', enabled: true, version: 0 },
    ]
    const fetch = transport((path, options) => path === customerPath && options.method === 'GET' ? response({ ...customer, enabled: false })
      : path.startsWith(`${customerPath}/contacts?`) ? response(page(contacts))
        : path.startsWith(`${customerPath}/addresses?`) ? response(page(addresses))
          : path === `${customerPath}/contacts/${contacts[0].id}` && options.method === 'PATCH' ? response({ ...contacts[0], name: '合成聯絡甲已更新', version: 2 })
            : path === `${customerPath}/addresses` && options.method === 'POST' ? response({ id: '30000000-0000-0000-0000-000000000003', customerId: customer.id, ...JSON.parse(options.body as string), version: 0 }, 201) : undefined)
    await openCrm(); await openDetail()
    expect(screen.getByText(/此客戶已停用/)).toBeVisible()
    expect(await screen.findByRole('button', { name: '編輯聯絡人 合成聯絡甲' })).toBeVisible()
    expect(screen.getByRole('button', { name: '編輯聯絡人 合成聯絡乙' })).toBeVisible()
    expect(await screen.findByRole('button', { name: '編輯地址 合成地址甲' })).toBeVisible()
    expect(screen.getByRole('button', { name: '編輯地址 合成地址乙' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '編輯聯絡人 合成聯絡甲' }))
    fireEvent.change(within(screen.getByRole('form', { name: '聯絡人表單' })).getByLabelText(/^聯絡人姓名/), { target: { value: '合成聯絡甲已更新' } })
    fireEvent.click(screen.getByRole('button', { name: '儲存聯絡人' }))
    expect(await screen.findByText('聯絡人已更新。')).toBeVisible()
    const addressForm = within(screen.getByRole('form', { name: '常用地址表單' }))
    const addressType = addressForm.getByLabelText(/^地址類型說明/)
    expect(addressType.tagName).toBe('INPUT')
    fireEvent.change(addressForm.getByLabelText(/^地址內容/), { target: { value: '合成海外地址三號' } })
    fireEvent.change(addressType, { target: { value: '自由描述的國際轉運地點' } })
    fireEvent.click(screen.getByRole('button', { name: '建立地址' }))
    expect(await screen.findByText('常用地址已建立。')).toBeVisible()
    const patch = fetch.mock.calls.find(([, options]) => options.method === 'PATCH')
    expect(patch![0]).toBe(`${customerPath}/contacts/${contacts[0].id}`)
    expect(JSON.parse(patch![1].body as string)).not.toHaveProperty('customerId')
    expect(JSON.parse(patch![1].body as string)).not.toHaveProperty('id')
    const write = fetch.mock.calls.find(([path, options]) => path === `${customerPath}/addresses` && options.method === 'POST')
    expect(JSON.parse(write![1].body as string)).toMatchObject({ label: null, addressType: '自由描述的國際轉運地點' })
    expect(JSON.parse(write![1].body as string)).not.toHaveProperty('customerId')
    expect(screen.queryByRole('button', { name: /刪除/ })).not.toBeInTheDocument()
  })

  it('returns to login and clears customer details on 401', async () => {
    transport((path) => path === customerPath ? response(null, 401) : undefined)
    await openCrm()
    fireEvent.click(await screen.findByRole('button', { name: `檢視客戶 ${customer.customerCode}` }))
    expect(await screen.findByRole('heading', { name: '登入工作空間' })).toBeVisible()
    expect(screen.queryByText(customer.name)).not.toBeInTheDocument()
    expect(screen.queryByLabelText('客戶代碼')).not.toBeInTheDocument()
  })

  it('preserves input after 403 and never automatically replays a rejected mutation', async () => {
    const fetch = transport((path, options) => path === customerPath && options.method === 'PATCH' ? response(null, 403) : undefined)
    await openCrm(); await openDetail()
    fill('客戶名稱', '拒絕後保留名稱')
    fireEvent.click(screen.getByRole('button', { name: '儲存客戶' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('權限不足或安全驗證已失效')
    expect(screen.getByLabelText('客戶名稱')).toHaveValue('拒絕後保留名稱')
    expect(fetch.mock.calls.filter(([, options]) => options.method === 'PATCH')).toHaveLength(1)
  })

  it('refreshes effective options after disabling a customer while keeping its history available', async () => {
    let current = { ...customer }
    const fetch = transport((path, options) => path.startsWith('/api/v1/crm/customers?') ? response(page([current]))
      : path === customerPath && options.method === 'GET' ? response(current)
        : path === customerPath && options.method === 'PATCH' ? (current = { ...current, ...JSON.parse(options.body as string), version: 3 }, response(current))
          : path.startsWith('/api/v1/crm/customer-options?') ? response(page(current.enabled ? [{ id: current.id, customerCode: current.customerCode, name: current.name, version: current.version }] : [])) : undefined)
    await openCrm()
    fireEvent.click(screen.getByRole('button', { name: '查看有效客戶選項' }))
    const options = within(await screen.findByRole('region', { name: '有效客戶選項' }))
    expect(await options.findByText(customer.name)).toBeVisible()
    await openDetail()
    fireEvent.click(screen.getByLabelText('啟用客戶'))
    fireEvent.click(screen.getByRole('button', { name: '儲存客戶' }))
    expect(await screen.findByText('客戶資料已更新。')).toBeVisible()
    expect(await options.findByText('目前沒有符合條件的有效客戶。')).toBeVisible()
    expect(screen.getByText(/此客戶已停用/)).toBeVisible()
    expect(screen.getByRole('button', { name: `檢視客戶 ${customer.customerCode}` })).toBeVisible()
    expect(fetch.mock.calls.filter(([path]) => path.startsWith('/api/v1/crm/customer-options?'))).toHaveLength(2)
  })

  it('encodes search and false filters, paginates, and keeps enabled off the options request', async () => {
    const fetch = transport((path) => path.startsWith('/api/v1/crm/customers?') ? response(page([customer], Number(new URL(path, 'https://test.local').searchParams.get('page')), 5, 6)) : undefined)
    await openCrm()
    await screen.findByRole('button', { name: `檢視客戶 ${customer.customerCode}` })
    fill('客戶搜尋', '合成 A&B +')
    fireEvent.change(screen.getByLabelText('客戶狀態'), { target: { value: 'false' } })
    fireEvent.change(screen.getByLabelText('每頁筆數'), { target: { value: '5' } })
    fireEvent.click(screen.getByRole('button', { name: '搜尋客戶' }))
    await waitFor(() => expect(fetch.mock.calls.some(([path]) => path === '/api/v1/crm/customers?page=0&size=5&q=%E5%90%88%E6%88%90+A%26B+%2B&enabled=false')).toBe(true))
    const pager = within(await screen.findByRole('navigation', { name: '客戶列表分頁' }))
    await waitFor(() => expect(pager.getByRole('button', { name: '下一頁' })).toBeEnabled())
    fireEvent.click(pager.getByRole('button', { name: '下一頁' }))
    await waitFor(() => expect(fetch.mock.calls.some(([path]) => path.includes('page=1&size=5') && path.includes('enabled=false'))).toBe(true))
    fireEvent.click(screen.getByRole('button', { name: '查看有效客戶選項' }))
    await screen.findByText('目前沒有符合條件的有效客戶。')
    fill('選項搜尋', '客戶 +')
    fireEvent.click(screen.getByRole('button', { name: '查詢有效客戶' }))
    await waitFor(() => expect(fetch.mock.calls.some(([path]) => path.startsWith('/api/v1/crm/customer-options?') && path.includes('q='))).toBe(true))
    for (const [path] of fetch.mock.calls.filter(([path]) => path.startsWith('/api/v1/crm/customer-options?'))) expect(new URL(path, 'https://test.local').searchParams.has('enabled')).toBe(false)
  })

  it('ignores stale details after switching to another customer', async () => {
    const second = { ...customer, id: '10000000-0000-0000-0000-000000000002', customerCode: 'SYNTHETIC_B', name: '合成客戶乙' }
    let resolveFirst!: (response: Response) => void
    transport((path, options) => path.startsWith('/api/v1/crm/customers?') ? response(page([customer, second]))
      : path === customerPath && options.method === 'GET' ? new Promise<Response>((resolve) => { resolveFirst = resolve })
        : path === `/api/v1/crm/customers/${second.id}` ? response(second) : undefined)
    await openCrm()
    fireEvent.click(await screen.findByRole('button', { name: `檢視客戶 ${customer.customerCode}` }))
    fireEvent.click(screen.getByRole('button', { name: `檢視客戶 ${second.customerCode}` }))
    expect(await screen.findByRole('heading', { name: `客戶明細：${second.name}` })).toBeVisible()
    await act(async () => { resolveFirst(response(customer)) })
    expect(screen.getByLabelText('客戶代碼')).toHaveValue(second.customerCode)
    expect(screen.queryByRole('heading', { name: `客戶明細：${customer.name}` })).not.toBeInTheDocument()
  })

  it('keeps the latest selection when a create response finishes after leaving its form', async () => {
    let resolveCreate!: (response: Response) => void
    transport((path, options) => path === '/api/v1/crm/customers' && options.method === 'POST' ? new Promise<Response>((resolve) => { resolveCreate = resolve }) : undefined)
    await openCrm()
    fireEvent.click(screen.getByRole('button', { name: '新增客戶' }))
    fill('客戶代碼', 'SYNTHETIC_NEW'); fill('客戶名稱', '稍後完成的合成客戶')
    fireEvent.click(screen.getByRole('button', { name: '建立客戶' }))
    await waitFor(() => expect(typeof resolveCreate).toBe('function'))
    await openDetail()
    await act(async () => { resolveCreate(response({ ...customer, id: '10000000-0000-0000-0000-000000000003', customerCode: 'SYNTHETIC_NEW', name: '稍後完成的合成客戶', version: 0 }, 201)) })
    expect(screen.getByRole('heading', { name: `客戶明細：${customer.name}` })).toBeVisible()
    expect(screen.getByLabelText('客戶代碼')).toHaveValue(customer.customerCode)
  })
})
