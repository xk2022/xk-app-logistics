import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import App from './App'
import type { Account, Role } from './api'

const synthetic: Account = { id: '00000000-0000-0000-0000-000000000001', username: 'test.admin', displayName: '合成管理員', role: 'ADMIN', enabled: true, mustChangePassword: false, version: 2 }
const statusData = { service: 'xk-logistics', status: 'UP' }
function response(data: unknown, status = 200) {
  return new Response(JSON.stringify({ code: status < 400 ? '0000' : `E0${status}`, message: status === 409 ? '資料版本衝突' : status < 400 ? '成功' : '請確認輸入欄位', data, timestamp: '2026-11-25T12:00:00+08:00' }), { status })
}
function transport(overrides: (path: string, options: RequestInit) => Response | Promise<Response> | undefined) {
  const fetch = vi.fn(async (path: string, options: RequestInit) => overrides(path, options)
    ?? (path === '/api/v1/status' ? response(statusData) : path === '/api/v1/auth/csrf' ? response({ token: 'synthetic-csrf', headerName: 'X-CSRF-TOKEN' }) : response(null, 401)))
  vi.stubGlobal('fetch', fetch)
  return fetch
}
const fill = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label, { exact: true }), { target: { value } })

describe('UPMS interface', () => {
  it('shows login for an initial 401 and keeps credentials out of storage', async () => {
    const fetch = transport((path) => path === '/api/v1/auth/login' ? response(synthetic) : undefined)
    const storage = vi.spyOn(Storage.prototype, 'setItem')
    render(<App />)
    expect(await screen.findByRole('heading', { name: '登入工作空間' })).toBeVisible()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    fill('帳號', 'test.admin'); fill('密碼', 'synthetic login value')
    fireEvent.click(screen.getByRole('button', { name: '登入' }))
    expect(await screen.findByRole('heading', { name: '管理員工作台' })).toBeVisible()
    expect(storage).not.toHaveBeenCalled()
    expect(fetch.mock.calls.filter(([path]) => path === '/api/v1/auth/csrf')).toHaveLength(2)
  })

  it.each([['DISPATCHER', '調度員'], ['DRIVER', '司機']] as [Role, string][])('shows only the %s workspace', async (role, label) => {
    const fetch = transport((path) => path === '/api/v1/auth/me' ? response({ ...synthetic, role }) : undefined)
    render(<App />)
    expect(await screen.findByRole('heading', { name: `${label}工作台` })).toBeVisible()
    expect(screen.queryByRole('button', { name: '帳號管理' })).not.toBeInTheDocument()
    expect(fetch.mock.calls.some(([path]) => path.includes('/upms/'))).toBe(false)
  })

  it('forces a password change before showing any workspace and requires re-login', async () => {
    const fetch = transport((path) => path === '/api/v1/auth/me' ? response({ ...synthetic, mustChangePassword: true }) : path === '/api/v1/auth/password' ? response(null) : undefined)
    render(<App />)
    expect(await screen.findByRole('heading', { name: '首次登入，請先修改密碼' })).toBeVisible()
    expect(screen.queryByRole('button', { name: '帳號管理' })).not.toBeInTheDocument()
    fill('目前密碼', 'synthetic initial value'); fill('新密碼', 'synthetic changed value'); fill('確認新密碼', 'synthetic changed value')
    fireEvent.click(screen.getByRole('button', { name: '確認修改密碼' }))
    expect(await screen.findByRole('heading', { name: '登入工作空間' })).toBeVisible()
    expect(screen.getByText(/所有登入狀態已撤銷/)).toBeVisible()
    expect(fetch.mock.calls.some(([path, options]) => path === '/api/v1/auth/password' && options.method === 'POST')).toBe(true)
  })

  it('validates password length and confirmation without sending a mutation', async () => {
    const fetch = transport((path) => path === '/api/v1/auth/me' ? response({ ...synthetic, mustChangePassword: true }) : undefined)
    render(<App />)
    await screen.findByRole('heading', { name: '首次登入，請先修改密碼' })
    fill('目前密碼', 'synthetic initial value'); fill('新密碼', 'short'); fill('確認新密碼', 'short')
    fireEvent.click(screen.getByRole('button', { name: '確認修改密碼' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('新密碼須為 15–128 字元')
    expect(screen.getByLabelText('目前密碼')).toHaveValue('')
    expect(fetch.mock.calls.some(([path]) => path === '/api/v1/auth/password')).toBe(false)
  })

  it('clears account information after an authenticated request expires', async () => {
    transport((path) => path === '/api/v1/auth/me' ? response(synthetic) : path === '/api/v1/upms/roles' ? response(['ADMIN', 'DISPATCHER', 'DRIVER']) : undefined)
    render(<App />)
    await screen.findByRole('heading', { name: '管理員工作台' })
    fireEvent.click(screen.getByRole('button', { name: '帳號管理' }))
    expect(await screen.findByRole('heading', { name: '登入工作空間' })).toBeVisible()
    expect(screen.queryByText('合成管理員')).not.toBeInTheDocument()
    expect(screen.getByText('登入狀態已失效，請重新登入。')).toBeVisible()
  })

  it('removes a revoked dispatcher session when本人狀態 is refreshed', async () => {
    let revoked = false
    transport((path) => path === '/api/v1/auth/me' ? revoked ? response(null, 401) : response({ ...synthetic, role: 'DISPATCHER' }) : undefined)
    render(<App />)
    await screen.findByRole('heading', { name: '調度員工作台' })
    revoked = true
    fireEvent.click(screen.getByRole('button', { name: '重新確認本人狀態' }))
    expect(await screen.findByRole('heading', { name: '登入工作空間' })).toBeVisible()
    expect(screen.queryByText('合成管理員')).not.toBeInTheDocument()
  })

  it('keeps the username and clears a rejected login password after a CSRF failure', async () => {
    const fetch = transport((path) => path === '/api/v1/auth/login' ? response(null, 403) : undefined)
    render(<App />)
    await screen.findByRole('heading', { name: '登入工作空間' })
    fill('帳號', 'test.admin'); fill('密碼', 'synthetic login value')
    fireEvent.click(screen.getByRole('button', { name: '登入' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('權限不足或安全驗證已失效')
    expect(screen.getByLabelText('帳號')).toHaveValue('test.admin')
    expect(screen.getByLabelText('密碼')).toHaveValue('')
    expect(fetch.mock.calls.filter(([path]) => path === '/api/v1/auth/login')).toHaveLength(1)
    expect(fetch.mock.calls.filter(([path]) => path === '/api/v1/auth/csrf')).toHaveLength(2)
  })

  it('updates an account with its version and handles a conflict without clearing non-secret input', async () => {
    const fetch = transport((path, options) => path === '/api/v1/auth/me' ? response(synthetic)
      : path === '/api/v1/upms/roles' ? response(['ADMIN', 'DISPATCHER', 'DRIVER'])
      : path === '/api/v1/upms/accounts' ? response([synthetic])
      : options.method === 'PATCH' ? response(null, 409) : undefined)
    render(<App />)
    await screen.findByRole('heading', { name: '管理員工作台' })
    fireEvent.click(screen.getByRole('button', { name: '帳號管理' }))
    fireEvent.click(await screen.findByRole('button', { name: '編輯 test.admin' }))
    fill('顯示名稱', '更新合成名稱')
    fireEvent.click(screen.getByRole('button', { name: '儲存變更' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('資料版本衝突')
    expect(screen.getByLabelText('顯示名稱')).toHaveValue('更新合成名稱')
    const patch = fetch.mock.calls.find(([, options]) => options.method === 'PATCH')
    expect(JSON.parse(patch![1].body as string)).toMatchObject({ version: 2, displayName: '更新合成名稱' })
    fireEvent.click(screen.getByRole('button', { name: '重新載入帳號' }))
    await screen.findByRole('button', { name: '編輯 test.admin' })
    expect(screen.getByRole('heading', { name: '建立帳號' })).toBeVisible()
    expect(screen.getByLabelText('顯示名稱')).toHaveValue('')
  })

  it('updates its own display name without pretending that the session was revoked', async () => {
    transport((path, options) => path === '/api/v1/auth/me' ? response(synthetic)
      : path === '/api/v1/upms/roles' ? response(['ADMIN', 'DISPATCHER', 'DRIVER'])
      : path === '/api/v1/upms/accounts' ? response([synthetic])
      : options.method === 'PATCH' ? response({ ...synthetic, displayName: '已更新合成名稱', version: 3 }) : undefined)
    render(<App />)
    await screen.findByRole('heading', { name: '管理員工作台' })
    fireEvent.click(screen.getByRole('button', { name: '帳號管理' }))
    fireEvent.click(await screen.findByRole('button', { name: '編輯 test.admin' }))
    fill('顯示名稱', '已更新合成名稱')
    fireEvent.click(screen.getByRole('button', { name: '儲存變更' }))
    expect(await screen.findByText('已更新合成名稱 · 管理員')).toBeVisible()
    expect(screen.queryByRole('heading', { name: '登入工作空間' })).not.toBeInTheDocument()
  })

  it('creates an account, refreshes the list, and clears the initial password', async () => {
    let created = false
    const fetch = transport((path, options) => path === '/api/v1/auth/me' ? response(synthetic)
      : path === '/api/v1/upms/roles' ? response(['ADMIN', 'DISPATCHER', 'DRIVER'])
      : path === '/api/v1/upms/accounts' && options.method === 'POST' ? (created = true, response({ ...synthetic, id: 'new-id', username: 'test.driver', role: 'DRIVER', mustChangePassword: true }, 201))
      : path === '/api/v1/upms/accounts' ? response(created ? [synthetic, { ...synthetic, id: 'new-id', username: 'test.driver' }] : [synthetic]) : undefined)
    render(<App />)
    await screen.findByRole('heading', { name: '管理員工作台' })
    fireEvent.click(screen.getByRole('button', { name: '帳號管理' }))
    await screen.findByRole('button', { name: '編輯 test.admin' })
    fill('帳號', 'test.driver'); fill('顯示名稱', '合成司機'); fill('初始密碼', 'synthetic initial value')
    fireEvent.change(screen.getByLabelText('角色'), { target: { value: 'DRIVER' } })
    fireEvent.click(screen.getByRole('button', { name: '建立帳號' }))
    expect(await screen.findByRole('button', { name: '編輯 test.driver' })).toBeVisible()
    expect(screen.getByLabelText('初始密碼')).toHaveValue('')
    expect(fetch.mock.calls.filter(([path, options]) => path === '/api/v1/upms/accounts' && options.method === 'POST')).toHaveLength(1)
  })

  it('safely logs out and removes account data', async () => {
    transport((path) => path === '/api/v1/auth/me' ? response(synthetic) : path === '/api/v1/auth/logout' ? response(null) : undefined)
    render(<App />)
    await screen.findByRole('heading', { name: '管理員工作台' })
    fireEvent.click(screen.getByRole('button', { name: '登出' }))
    expect(await screen.findByRole('heading', { name: '登入工作空間' })).toBeVisible()
    await waitFor(() => expect(screen.queryByText('合成管理員')).not.toBeInTheDocument())
    expect(screen.getByText('已安全登出。')).toBeVisible()
  })

  it('ignores a stale本人狀態 response that arrives after logout', async () => {
    let reads = 0
    let finish!: (value: Response) => void
    transport((path) => path === '/api/v1/auth/me' ? ++reads === 1 ? response(synthetic) : new Promise<Response>((resolve) => { finish = resolve })
      : path === '/api/v1/auth/logout' ? response(null) : undefined)
    render(<App />)
    await screen.findByRole('heading', { name: '管理員工作台' })
    fireEvent.click(screen.getByRole('button', { name: '重新確認本人狀態' }))
    await waitFor(() => expect(reads).toBe(2))
    fireEvent.click(screen.getByRole('button', { name: '登出' }))
    await screen.findByRole('heading', { name: '登入工作空間' })
    await act(async () => { finish(response(synthetic)) })
    expect(screen.getByRole('heading', { name: '登入工作空間' })).toBeVisible()
    expect(screen.queryByText('合成管理員')).not.toBeInTheDocument()
  })
})
