import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import App from './App'
import type { Account, Role } from './api'
import type { Category, Definition, Item, Parameter, ValueType } from './admApi'

const admin: Account = { id: '00000000-0000-0000-0000-000000000001', username: 'test.admin', displayName: '合成管理員', role: 'ADMIN', enabled: true, mustChangePassword: false, version: 2 }
const category: Category = { id: '10000000-0000-0000-0000-000000000001', code: 'CONTAINER_TYPE', name: '合成貨櫃分類', enabled: true, sortOrder: 0, readOnly: false, version: 2 }
const item: Item = { id: '20000000-0000-0000-0000-000000000001', categoryId: category.id, code: 'SYNTHETIC', label: '合成項目', enabled: true, sortOrder: 0, readOnly: false, version: 0 }
function response(data: unknown, status = 200) {
  return new Response(JSON.stringify({ code: status < 400 ? '0000' : `E0${status}`, message: status === 409 ? '資料版本衝突' : status < 400 ? '成功' : '請確認輸入欄位', data, timestamp: '2026-11-25T12:00:00+08:00' }), { status })
}
function page<T>(content: T[], index = 0, totalPages = content.length ? 1 : 0) {
  return { content, page: index, size: 20, totalElements: content.length, totalPages }
}
function transport(override: (path: string, options: RequestInit) => Response | Promise<Response> | undefined = () => undefined) {
  const fetch = vi.fn(async (path: string, options: RequestInit) => override(path, options) ?? (
    path === '/api/v1/auth/me' ? response(admin)
      : path === '/api/v1/auth/csrf' ? response({ token: 'synthetic-csrf', headerName: 'X-CSRF-TOKEN' })
        : path === '/api/v1/status' ? response({ service: 'xk-logistics', status: 'UP' })
          : path === '/api/v1/adm/parameter-definitions' || path.startsWith('/api/v1/adm/options/') ? response([])
            : path.startsWith('/api/v1/adm/') && options.method === 'GET' ? response(page([])) : response(null, 404)
  ))
  vi.stubGlobal('fetch', fetch)
  return fetch
}
async function openAdm() {
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: '系統管理' }))
  await screen.findByRole('heading', { name: '字典管理' })
}
const fill = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label, { exact: true }), { target: { value } })

describe('ADM interface', () => {
  it('keeps ORDER_TYPE and its fixed items read-only even if a flag is unexpectedly false', async () => {
    const order = { ...category, code: 'ORDER_TYPE', name: '訂單類型', readOnly: false }
    const fetch = transport((path) => path.startsWith('/api/v1/adm/categories?') ? response(page([order]))
      : path.includes(`/categories/${order.id}/items?`) ? response(page([{ ...item, code: 'IMPORT', label: '進口', readOnly: false }])) : undefined)
    await openAdm()
    expect(screen.queryByRole('button', { name: '編輯分類 ORDER_TYPE' })).not.toBeInTheDocument()
    fireEvent.click(await screen.findByRole('button', { name: '管理項目 ORDER_TYPE' }))
    expect(await screen.findByText('IMPORT', { selector: 'small' })).toBeVisible()
    expect(screen.getByText(/僅供唯讀對照/)).toBeVisible()
    expect(screen.queryByRole('button', { name: '編輯項目 IMPORT' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '建立項目' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('項目代碼')).not.toBeInTheDocument()
    expect(fetch.mock.calls.some(([, options]) => options.method !== 'GET')).toBe(false)
  })

  it('shows an empty approved catalog without offering arbitrary parameter keys', async () => {
    transport()
    await openAdm()
    fireEvent.click(screen.getByRole('button', { name: '系統參數' }))
    expect(await screen.findByRole('heading', { name: '尚無核准參數' })).toBeVisible()
    expect(screen.queryByLabelText('核准參數鍵')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '建立參數' })).not.toBeInTheDocument()
    expect(screen.getByText('尚無系統參數資料。')).toBeVisible()
    expect(screen.getByRole('button', { name: '下一頁' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '上一頁' })).toBeDisabled()
  })

  it.each([
    ['TEXT', '合成文字值'], ['NUMBER', '9007199254740993.01'], ['BOOLEAN', 'false'],
  ] as [ValueType, string][])('creates an approved %s fixture using an exact string value', async (valueType, valueText) => {
    const definition: Definition = { key: `TEST_${valueType}`, name: '合成核准參數', valueType, description: '僅用於隔離測試', minLength: valueType === 'TEXT' ? 1 : null, maxLength: valueType === 'TEXT' ? 80 : null, allowedValues: [], minValue: valueType === 'NUMBER' ? '-9999999999999999.99' : null, maxValue: valueType === 'NUMBER' ? '9999999999999999.99' : null, decimalScale: valueType === 'NUMBER' ? 2 : null }
    let created: Parameter | undefined
    const fetch = transport((path, options) => path === '/api/v1/adm/parameter-definitions' ? response([definition])
      : path === '/api/v1/adm/parameters' && options.method === 'POST' ? (created = { id: '30000000-0000-0000-0000-000000000001', ...JSON.parse(options.body as string), version: 0 }, response(created, 201))
        : path.startsWith('/api/v1/adm/parameters?') ? response(page(created ? [created] : [])) : undefined)
    await openAdm()
    fireEvent.click(screen.getByRole('button', { name: '系統參數' }))
    fireEvent.change(await screen.findByLabelText('核准參數鍵'), { target: { value: definition.key } })
    if (valueType !== 'BOOLEAN') fill('參數值', valueText)
    fireEvent.click(screen.getByRole('button', { name: '建立參數' }))
    expect(await screen.findByText('系統參數已建立。')).toBeVisible()
    const write = fetch.mock.calls.find(([path, options]) => path === '/api/v1/adm/parameters' && options.method === 'POST')
    expect(JSON.parse(write![1].body as string)).toMatchObject({ key: definition.key, valueType, valueText })
    expect(typeof JSON.parse(write![1].body as string).valueText).toBe('string')
    expect(write![1]).toMatchObject({ credentials: 'same-origin', headers: { 'X-CSRF-TOKEN': 'synthetic-csrf' } })
    expect(await screen.findByRole('button', { name: `編輯參數 ${definition.key}` })).toBeVisible()
  })

  it('allows an explicitly approved empty text enum value', async () => {
    const definition: Definition = { key: 'TEST_EMPTY_TEXT', name: '合成空字串參數', valueType: 'TEXT', description: '隔離測試允許空字串', minLength: null, maxLength: null, allowedValues: ['', 'APPROVED'], minValue: null, maxValue: null, decimalScale: null }
    const fetch = transport((path, options) => path === '/api/v1/adm/parameter-definitions' ? response([definition])
      : path === '/api/v1/adm/parameters' && options.method === 'POST' ? response({ id: '30000000-0000-0000-0000-000000000002', ...JSON.parse(options.body as string), version: 0 }, 201) : undefined)
    await openAdm()
    fireEvent.click(screen.getByRole('button', { name: '系統參數' }))
    fireEvent.change(await screen.findByLabelText('核准參數鍵'), { target: { value: definition.key } })
    const value = screen.getByLabelText('參數值')
    expect(screen.getByRole('option', { name: '（空字串）' })).toHaveValue('')
    expect(value).toBeValid()
    fireEvent.click(screen.getByRole('button', { name: '建立參數' }))
    expect(await screen.findByText('系統參數已建立。')).toBeVisible()
    const write = fetch.mock.calls.find(([path, options]) => path === '/api/v1/adm/parameters' && options.method === 'POST')
    expect(JSON.parse(write![1].body as string)).toMatchObject({ key: definition.key, valueType: 'TEXT', valueText: '' })
  })

  it('preserves conflicted input, then reloads the current version before resubmission', async () => {
    let reads = 0
    let patches = 0
    const fetch = transport((path, options) => path.startsWith('/api/v1/adm/categories?') ? response(page([{ ...category, version: ++reads === 1 ? 2 : 3 }]))
      : path === `/api/v1/adm/categories/${category.id}` && options.method === 'PATCH' ? (++patches === 1 ? response(null, 409) : response({ ...category, name: '確認最新名稱', version: 4 })) : undefined)
    await openAdm()
    fireEvent.click(await screen.findByRole('button', { name: `編輯分類 ${category.code}` }))
    expect(screen.getByLabelText('分類代碼')).toHaveAttribute('readonly')
    fill('分類名稱', '保留待確認名稱')
    fireEvent.click(screen.getByRole('button', { name: '儲存分類' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('資料版本衝突')
    expect(screen.getByLabelText('分類名稱')).toHaveValue('保留待確認名稱')
    fireEvent.click(screen.getByRole('button', { name: '重新載入分類' }))
    expect(screen.getByLabelText('分類名稱')).toHaveValue('')
    fireEvent.click(await screen.findByRole('button', { name: `編輯分類 ${category.code}` }))
    fill('分類名稱', '確認最新名稱')
    fireEvent.click(screen.getByRole('button', { name: '儲存分類' }))
    expect(await screen.findByText('字典分類已更新。')).toBeVisible()
    const bodies = fetch.mock.calls.filter(([, options]) => options.method === 'PATCH').map(([, options]) => JSON.parse(options.body as string))
    expect(bodies.map((body) => body.version)).toEqual([2, 3])
    expect(bodies[1]).not.toHaveProperty('code')
  })

  it('clears the ADM view when a request returns 401', async () => {
    transport((path) => path.startsWith('/api/v1/adm/categories?') ? response(null, 401) : undefined)
    await openAdm()
    expect(await screen.findByRole('heading', { name: '登入工作空間' })).toBeVisible()
    expect(screen.queryByLabelText('分類代碼')).not.toBeInTheDocument()
    expect(screen.queryByText('合成管理員')).not.toBeInTheDocument()
  })

  it('retains a rejected edit and never automatically replays a forbidden write', async () => {
    const fetch = transport((path, options) => path.startsWith('/api/v1/adm/categories?') ? response(page([category]))
      : options.method === 'PATCH' ? response(null, 403) : undefined)
    await openAdm()
    fireEvent.click(await screen.findByRole('button', { name: `編輯分類 ${category.code}` }))
    fill('分類名稱', '保留名稱')
    fireEvent.click(screen.getByRole('button', { name: '儲存分類' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('權限不足或安全驗證已失效')
    expect(screen.getByLabelText('分類名稱')).toHaveValue('保留名稱')
    expect(fetch.mock.calls.filter(([, options]) => options.method === 'PATCH')).toHaveLength(1)
    expect(fetch.mock.calls.some(([path]) => path === '/api/v1/auth/csrf')).toBe(true)
  })

  it('renders change-log values as inert text and requests the next bounded page', async () => {
    const markup = '<img src=x onerror="alert(1)"><script>unsafe()</script>'
    const fetch = transport((path) => path.startsWith('/api/v1/adm/change-logs?') ? response(page([{ id: 'log-1', targetType: 'CATEGORY', targetId: category.id, action: 'UPDATE', actorId: admin.id, actorUsername: admin.username, occurredAt: '2026-11-25T12:00:00+08:00', before: null, after: { name: markup } }], path.includes('page=1') ? 1 : 0, 2)) : undefined)
    await openAdm()
    fireEvent.click(screen.getByRole('button', { name: '異動紀錄' }))
    fireEvent.click(await screen.findByText('檢視前後值'))
    expect(screen.getByText((value, node) => node?.tagName === 'PRE' && value.includes('<script>unsafe()</script>'))).toBeVisible()
    expect(document.querySelector('pre img, pre script')).toBeNull()
    expect(screen.queryByRole('button', { name: /儲存|刪除/ })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '下一頁' }))
    await waitFor(() => expect(fetch.mock.calls.some(([path]) => path === '/api/v1/adm/change-logs?page=1&size=20')).toBe(true))
  })

  it.each(['DISPATCHER', 'DRIVER'] as Role[])('does not offer ADM or request its data for %s', async (role) => {
    const fetch = transport((path) => path === '/api/v1/auth/me' ? response({ ...admin, role }) : undefined)
    render(<App />)
    await screen.findByRole('heading', { name: role === 'DRIVER' ? '司機工作台' : '調度員工作台' })
    expect(screen.queryByRole('button', { name: '系統管理' })).not.toBeInTheDocument()
    expect(fetch.mock.calls.some(([path]) => path.startsWith('/api/v1/adm/'))).toBe(false)
  })

  it('ignores a slow previous category response when the selected category changes', async () => {
    const second = { ...category, id: 'category-2', code: 'TASK_KIND', name: '合成任務分類' }
    let resolveFirst!: (value: Response) => void
    transport((path) => path.startsWith('/api/v1/adm/categories?') ? response(page([category, second]))
      : path.includes(`/categories/${category.id}/items?`) ? new Promise<Response>((resolve) => { resolveFirst = resolve })
        : path.includes(`/categories/${second.id}/items?`) ? response(page([{ ...item, id: 'item-2', categoryId: second.id, label: '目前分類項目' }])) : undefined)
    await openAdm()
    fireEvent.click(await screen.findByRole('button', { name: `管理項目 ${category.code}` }))
    fireEvent.click(screen.getByRole('button', { name: `管理項目 ${second.code}` }))
    expect(await screen.findByText('目前分類項目', { selector: 'th' })).toBeVisible()
    await act(async () => { resolveFirst(response(page([{ ...item, label: '舊分類慢回應' }]))) })
    expect(screen.queryByText('舊分類慢回應')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '字典項目：合成任務分類' })).toBeVisible()
  })
})
