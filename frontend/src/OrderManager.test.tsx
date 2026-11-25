import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import App from './App'
import type { Account, Role } from './api'
import type { Order } from './tomApi'

// Isolate workspace navigation from the editor's separately tested form state machine.
vi.mock('./OrderEditor', () => ({ default: ({ order, onDirtyChange, onCancel, onSaved }: { order?: Order; onDirtyChange?: (dirty: boolean) => void; onCancel?: () => void; onSaved: (order: Order) => void }) => <section><h1>{order ? `編輯訂單 ${order.orderNo}` : '新增訂單'}</h1><label>測試編輯內容<input onChange={() => onDirtyChange?.(true)} /></label><button onClick={onCancel}>返回訂單列表</button><button onClick={() => onSaved(fullOrder as Order)}>模擬已確認保存</button>{order && <p>{order.remark}</p>}</section> }))

const account: Account = { id: '00000000-0000-4000-8000-000000000001', username: 'synthetic.admin', displayName: '合成工作台使用者', role: 'ADMIN', enabled: true, mustChangePassword: false, version: 0 }
const customerId = '00000000-0000-4000-8000-000000000101'
const summary = { id: '00000000-0000-4000-8000-000000000201', orderNo: 'SYNTHETIC-ORDER-001', type: 'IMPORT', customerId, customerName: '合成歷史客戶', lifecycleStatus: 'OPEN', acceptanceStatus: 'DRAFT', version: 2, createdAt: '2026-11-25T10:00:00+08:00', updatedAt: '2026-11-25T10:00:00.123456+08:00' }
const fullOrder = { ...summary, customerReference: null, remark: '完整明細才有的備註', shipping: null, items: [], tasks: [], createdBy: account.id, updatedBy: account.id }
const tom = (data: unknown, status = 200, code = 'OK') => new Response(JSON.stringify({ code, message: status < 400 ? '成功' : '登入狀態已失效，請重新登入。', data, traceId: 'synthetic-workspace-trace', ...(status >= 400 ? { fieldErrors: [] } : {}) }), { status })
const shared = (data: unknown, status = 200) => new Response(JSON.stringify({ code: status < 400 ? '0000' : 'E0401', message: '成功', data, timestamp: '2026-11-25T10:00:00+08:00' }), { status })
const page = (content: unknown[] = [summary], index = 0, totalElements = content.length) => ({ content, page: index, size: 20, totalElements, totalPages: Math.ceil(totalElements / 20) })
function transport(override: (path: string, options: RequestInit) => Response | Promise<Response> | undefined = () => undefined, role: Role = 'ADMIN') {
  const fetch = vi.fn(async (path: string, options: RequestInit) => override(path, options) ?? (path === '/api/v1/auth/me' ? shared({ ...account, role }) : path.startsWith('/api/v1/orders?') ? tom(page()) : path === `/api/v1/orders/${summary.id}` ? tom(fullOrder) : path.startsWith('/api/v1/crm/customer-options?') ? shared(page([])) : shared(null, 404)))
  vi.stubGlobal('fetch', fetch)
  return fetch
}
async function openOrders() {
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: '訂單工作台' }))
  await screen.findByRole('heading', { name: '訂單工作台' })
}

describe('order workspace', () => {
  it.each(['ADMIN', 'DISPATCHER'] as Role[])('gives %s a real order workspace with historical customer labels', async (role) => {
    const fetch = transport(undefined, role)
    await openOrders()
    expect(await screen.findByRole('article', { name: `訂單 ${summary.orderNo}` })).toBeVisible()
    expect(screen.getByRole('button', { name: '新增訂單' })).toBeEnabled()
    expect(screen.getByText(summary.customerName)).toBeVisible()
    expect(screen.getByText('進口', { exact: true })).toBeVisible()
    expect(fetch.mock.calls.some(([path]) => path.startsWith('/api/v1/orders?'))).toBe(true)
    expect(fetch.mock.calls.some(([path]) => path === `/api/v1/orders/${summary.id}`)).toBe(false)
    expect(screen.queryByRole('button', { name: /正式受理|派車|結案/ })).not.toBeInTheDocument()
  })

  it('never loads orders or exposes an order entry for DRIVER', async () => {
    const fetch = transport(undefined, 'DRIVER')
    render(<App />)
    await screen.findByRole('heading', { name: '司機工作台' })
    expect(screen.queryByRole('button', { name: '訂單工作台' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '管理訂單' })).not.toBeInTheDocument()
    expect(fetch.mock.calls.some(([path]) => path.startsWith('/api/v1/orders'))).toBe(false)
  })

  it('applies only contract-supported filters and keeps pagination within the selected query', async () => {
    const fetch = transport((path) => path.startsWith('/api/v1/orders?') ? tom(page([summary], Number(new URLSearchParams(path.split('?')[1]).get('page')), 45)) : undefined)
    await openOrders()
    await screen.findByRole('article')
    fireEvent.click(screen.getByRole('button', { name: '下一頁訂單' }))
    await waitFor(() => expect(fetch.mock.calls.some(([path]) => new URLSearchParams(path.split('?')[1]).get('page') === '1')).toBe(true))
    fireEvent.change(screen.getByLabelText('訂單類型篩選'), { target: { value: 'EXPORT' } })
    fireEvent.change(screen.getByLabelText('訂單排序'), { target: { value: 'orderNo,asc' } })
    fireEvent.change(screen.getByLabelText('每頁訂單筆數'), { target: { value: '5' } })
    fireEvent.submit(screen.getByRole('form', { name: '訂單查詢' }))
    await waitFor(() => expect(fetch.mock.calls.some(([path]) => path.includes('type=EXPORT'))).toBe(true))
    const paths = fetch.mock.calls.map(([path]) => path).filter((path) => path.startsWith('/api/v1/orders?'))
    const params = new URLSearchParams(paths.at(-1)!.split('?')[1])
    expect(Object.fromEntries(params)).toEqual({ page: '0', size: '5', sort: 'orderNo,asc', type: 'EXPORT' })
    expect(paths.every((path) => !/[?&](q|status|owner)=/.test(path))).toBe(true)
  })

  it('uses effective CRM options for query selection and supports historical customers from an order', async () => {
    const option = { id: customerId, customerCode: 'SYNTHETIC_ACTIVE', name: '合成有效客戶', version: 0 }
    const fetch = transport((path) => path.startsWith('/api/v1/crm/customer-options?') ? shared(page([option])) : undefined)
    await openOrders(); await screen.findByRole('article')
    fireEvent.click(screen.getByRole('button', { name: '選擇篩選客戶' }))
    fireEvent.click(await screen.findByRole('button', { name: `篩選客戶：${option.name}（${option.customerCode}）` }))
    fireEvent.submit(screen.getByRole('form', { name: '訂單查詢' }))
    await waitFor(() => expect(fetch.mock.calls.some(([path]) => path.includes(`customerId=${customerId}`))).toBe(true))
    fireEvent.click(await screen.findByRole('button', { name: `只看客戶 ${summary.customerName}` }))
    expect(screen.getByText(summary.customerName, { selector: '.order-customer-filter strong' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '清除客戶條件' }))
    await waitFor(() => expect(fetch.mock.calls.filter(([path]) => path.startsWith('/api/v1/orders?')).at(-1)![0]).not.toContain('customerId='))
  })

  it('shows large contract-valid result counts without rounding', async () => {
    const data = JSON.stringify(page([])).replace('"totalElements":0', '"totalElements":9007199254740993').replace('"totalPages":0', '"totalPages":9007199254740993')
    transport((path) => path.startsWith('/api/v1/orders?') ? new Response(`{"code":"OK","message":"成功","traceId":"synthetic-large","data":${data}}`) : undefined)
    await openOrders()
    expect(await screen.findByText('共 9007199254740993 筆訂單')).toBeVisible()
    expect(screen.getByText('第 1 / 9007199254740993 頁')).toBeVisible()
  })

  it('loads a complete order before opening the editor and confirms leaving local changes', async () => {
    const fetch = transport()
    await openOrders()
    fireEvent.click(await screen.findByRole('button', { name: `編輯訂單 ${summary.orderNo}` }))
    expect(await screen.findByRole('heading', { name: `編輯訂單 ${summary.orderNo}` })).toBeVisible()
    expect(screen.getByText(fullOrder.remark)).toBeVisible()
    expect(fetch.mock.calls.filter(([path]) => path === `/api/v1/orders/${summary.id}`)).toHaveLength(1)
    fireEvent.change(screen.getByLabelText('測試編輯內容'), { target: { value: '保留本機草稿' } })
    fireEvent.click(screen.getByRole('button', { name: '客戶管理' }))
    expect(screen.getByRole('alert', { name: '確認切換工作頁面' })).toBeVisible()
    expect(screen.getByRole('heading', { name: `編輯訂單 ${summary.orderNo}` })).toBeVisible()
    expect(fetch.mock.calls.some(([path]) => path.startsWith('/api/v1/crm/customers?'))).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: '留在訂單頁面' }))
    fireEvent.click(screen.getByRole('button', { name: '返回訂單列表' }))
    expect(screen.getByRole('alert', { name: '確認離開訂單' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '繼續編輯訂單' }))
    expect(screen.getByLabelText('測試編輯內容')).toHaveValue('保留本機草稿')
    fireEvent.click(screen.getByRole('button', { name: '返回訂單列表' }))
    fireEvent.click(screen.getByRole('button', { name: '捨棄本機內容並返回列表' }))
    expect(await screen.findByRole('heading', { name: '訂單工作台' })).toBeVisible()
  })

  it('keeps the same editor after a confirmed create so its baseline and replay notice survive', async () => {
    const fetch = transport()
    await openOrders(); await screen.findByRole('article')
    fireEvent.click(screen.getByRole('button', { name: '新增訂單' }))
    const input = screen.getByLabelText('測試編輯內容')
    fireEvent.change(input, { target: { value: '編輯器內已確認的狀態' } })
    fireEvent.click(screen.getByRole('button', { name: '模擬已確認保存' }))
    expect(screen.getByLabelText('測試編輯內容')).toBe(input)
    expect(input).toHaveValue('編輯器內已確認的狀態')
    expect(fetch.mock.calls.some(([path]) => path === `/api/v1/orders/${summary.id}`)).toBe(false)
  })

  it('confirms logout with local changes and keeps the guard when logout fails', async () => {
    const fetch = transport((path) => path === '/api/v1/auth/csrf' ? shared({ token: 'synthetic-workspace-csrf', headerName: 'X-CSRF-TOKEN' }) : path === '/api/v1/auth/logout' ? shared(null, 503) : undefined)
    await openOrders(); await screen.findByRole('article')
    fireEvent.click(screen.getByRole('button', { name: '新增訂單' }))
    fireEvent.change(screen.getByLabelText('測試編輯內容'), { target: { value: '仍須保留的本機內容' } })
    fireEvent.click(screen.getByRole('button', { name: '登出' }))
    expect(screen.getByRole('alert', { name: '確認切換工作頁面' })).toBeVisible()
    expect(fetch.mock.calls.some(([path]) => path === '/api/v1/auth/logout')).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: '捨棄訂單內容並登出' }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('服務暫時無法使用'))
    expect(screen.getByLabelText('測試編輯內容')).toHaveValue('仍須保留的本機內容')
    fireEvent.click(screen.getByRole('button', { name: '客戶管理' }))
    expect(screen.getByRole('alert', { name: '確認切換工作頁面' })).toBeVisible()
  })

  it('ignores an old list response after a newer filter completes', async () => {
    let finish!: (value: Response) => void
    let reads = 0
    const fetch = transport((path) => path.startsWith('/api/v1/orders?') ? ++reads === 1 ? new Promise<Response>((resolve) => { finish = resolve }) : tom(page([{ ...summary, orderNo: 'SYNTHETIC-NEW-QUERY' }])) : undefined)
    await openOrders()
    expect(screen.getByRole('status')).toHaveTextContent('正在載入訂單列表')
    fireEvent.change(screen.getByLabelText('訂單類型篩選'), { target: { value: 'LOCAL' } })
    fireEvent.submit(screen.getByRole('form', { name: '訂單查詢' }))
    await screen.findByRole('article', { name: '訂單 SYNTHETIC-NEW-QUERY' })
    await act(async () => { finish(tom(page())) })
    expect(screen.queryByRole('article', { name: `訂單 ${summary.orderNo}` })).not.toBeInTheDocument()
    expect(fetch.mock.calls.filter(([path]) => path.startsWith('/api/v1/orders?'))[0][1].signal?.aborted).toBe(true)
  })

  it('shows failures without a stale success and allows an explicit list reload', async () => {
    let reads = 0
    transport((path) => path.startsWith('/api/v1/orders?') ? ++reads === 1 ? tom(null, 500, 'INTERNAL_ERROR') : tom(page([])) : undefined)
    await openOrders()
    expect(await screen.findByRole('alert')).toBeVisible()
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '重新載入訂單列表' }))
    expect(await screen.findByRole('heading', { name: '尚無符合條件的訂單' })).toBeVisible()
  })

  it('clears all order data when authentication expires during detail loading', async () => {
    transport((path) => path === `/api/v1/orders/${summary.id}` ? tom(null, 401, 'UNAUTHENTICATED') : undefined)
    await openOrders()
    fireEvent.click(await screen.findByRole('button', { name: `編輯訂單 ${summary.orderNo}` }))
    expect(await screen.findByRole('heading', { name: '登入工作空間' })).toBeVisible()
    expect(screen.queryByText(summary.customerName)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '訂單工作台' })).not.toBeInTheDocument()
  })

  it('does not turn a 403 detail refusal into an editable empty draft', async () => {
    transport((path) => path === `/api/v1/orders/${summary.id}` ? tom(null, 403, 'FORBIDDEN') : undefined)
    await openOrders()
    fireEvent.click(await screen.findByRole('button', { name: `編輯訂單 ${summary.orderNo}` }))
    const card = await screen.findByRole('heading', { name: '無法開啟訂單' })
    expect(card).toBeVisible()
    expect(screen.queryByLabelText('測試編輯內容')).not.toBeInTheDocument()
    expect(within(card.parentElement!).getByRole('button', { name: '返回訂單列表' })).toBeEnabled()
  })
})
