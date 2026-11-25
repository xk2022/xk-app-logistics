import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import App from './App'
import type { Account, Role } from './api'

vi.mock('./DriverManager', () => ({ default: ({ onDirtyChange, onExpired, onBusyChange }: { onDirtyChange?: (dirty: boolean) => void; onExpired: () => void; onBusyChange?: (busy: boolean) => void }) => <section><h1>司機管理</h1><label>導航測試編號<input onChange={() => onDirtyChange?.(true)} /></label><button onClick={onExpired}>模擬司機操作登入失效</button><button onClick={() => onBusyChange?.(true)}>模擬司機保存中</button><button onClick={() => { onBusyChange?.(false); onDirtyChange?.(false) }}>模擬司機保存完成</button></section> }))
vi.mock('./DriverSelf', () => ({ default: ({ onExpired }: { onExpired: () => void }) => <section><h1>本人司機唯讀測試</h1><button onClick={onExpired}>模擬本人資料登入失效</button></section> }))
const account: Account = { id: '00000000-0000-4000-8000-000000000001', username: 'synthetic.driver-nav', displayName: '合成司機使用者', role: 'ADMIN', enabled: true, mustChangePassword: false, version: 0 }
function envelope(data: unknown, status = 200) {
  return new Response(JSON.stringify({ code: status < 400 ? '0000' : 'E0503', message: '成功', data, timestamp: '2026-11-25T12:00:00+08:00' }), { status })
}
function transport(role: Role = 'ADMIN', forced = false) {
  const fetch = vi.fn(async (path: string) => path === '/api/v1/auth/me' ? envelope({ ...account, role, mustChangePassword: forced })
    : path === '/api/v1/auth/csrf' ? envelope({ token: 'synthetic-driver-csrf', headerName: 'X-CSRF-TOKEN' })
      : path === '/api/v1/auth/logout' ? envelope(null, 503)
        : path.startsWith('/api/v1/crm/customers?') ? envelope({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }) : envelope(null, 404))
  vi.stubGlobal('fetch', fetch)
  return fetch
}
async function openDrivers() {
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: '司機管理' }))
  await screen.findByRole('heading', { name: '司機管理' })
}

describe('driver role navigation and editing protection', () => {
  it.each(['ADMIN', 'DISPATCHER'] as Role[])('lets %s open the driver workspace', async (role) => {
    transport(role)
    render(<App />)
    expect(await screen.findByRole('button', { name: '司機管理' })).toBeVisible()
    expect(screen.queryByRole('button', { name: '本人司機資料' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '管理司機' }))
    expect(await screen.findByRole('heading', { name: '司機管理' })).toBeVisible()
  })

  it('mounts only the own-driver page for DRIVER without management access', async () => {
    const fetch = transport('DRIVER')
    render(<App />)
    await screen.findByRole('heading', { name: '司機工作台' })
    for (const name of ['司機管理', '管理司機', '車輛管理', '訂單工作台', '帳號管理']) expect(screen.queryByRole('button', { name })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('導航測試編號')).not.toBeInTheDocument()
    expect(fetch.mock.calls.some(([path]) => path.startsWith('/api/v1/fms/'))).toBe(false)
    fireEvent.click(screen.getAllByRole('button', { name: '本人司機資料' })[0])
    expect(await screen.findByRole('heading', { name: '本人司機唯讀測試' })).toBeVisible()
    expect(screen.queryByLabelText('導航測試編號')).not.toBeInTheDocument()
  })

  it('removes the own-driver page when authentication expires', async () => {
    transport('DRIVER')
    render(<App />)
    fireEvent.click((await screen.findAllByRole('button', { name: '本人司機資料' }))[0])
    fireEvent.click(screen.getByRole('button', { name: '模擬本人資料登入失效' }))
    expect(await screen.findByRole('heading', { name: '登入工作空間' })).toBeVisible()
    expect(screen.queryByRole('heading', { name: '本人司機唯讀測試' })).not.toBeInTheDocument()
  })

  it.each(['ADMIN', 'DRIVER'] as Role[])('requires first-password change before exposing %s driver actions', async (role) => {
    const fetch = transport(role, true)
    render(<App />)
    await screen.findByRole('heading', { name: '首次登入，請先修改密碼' })
    expect(screen.queryByRole('button', { name: '司機管理' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '本人司機資料' })).not.toBeInTheDocument()
    expect(fetch.mock.calls.some(([path]) => path.startsWith('/api/v1/fms/'))).toBe(false)
  })

  it('keeps unsaved driver contents until the user explicitly leaves the module', async () => {
    const fetch = transport()
    await openDrivers()
    const input = screen.getByLabelText('導航測試編號')
    fireEvent.change(input, { target: { value: 'SYNTHETIC-DIRTY' } })
    fireEvent.click(screen.getByRole('button', { name: '客戶管理' }))
    expect(screen.getByRole('alert', { name: '確認切換工作頁面' })).toHaveTextContent('司機內容尚未完成確認')
    expect(fetch.mock.calls.some(([path]) => path.startsWith('/api/v1/crm/customers?'))).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: '留在司機頁面' }))
    expect(input).toHaveValue('SYNTHETIC-DIRTY')
    fireEvent.click(screen.getByRole('button', { name: '客戶管理' }))
    fireEvent.click(screen.getByRole('button', { name: '捨棄司機內容並離開' }))
    expect(await screen.findByRole('heading', { name: '客戶管理' })).toBeVisible()
    expect(screen.queryByLabelText('導航測試編號')).not.toBeInTheDocument()
  })

  it('preserves the edit guard if a confirmed logout fails', async () => {
    transport()
    await openDrivers()
    fireEvent.change(screen.getByLabelText('導航測試編號'), { target: { value: 'SYNTHETIC-KEEP' } })
    fireEvent.click(screen.getByRole('button', { name: '登出' }))
    fireEvent.click(screen.getByRole('button', { name: '捨棄司機內容並登出' }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('服務暫時無法使用'))
    expect(screen.getByLabelText('導航測試編號')).toHaveValue('SYNTHETIC-KEEP')
    fireEvent.click(screen.getByRole('button', { name: '訂單工作台' }))
    expect(screen.getByRole('alert', { name: '確認切換工作頁面' })).toHaveTextContent('司機內容尚未完成確認')
  })

  it('clears driver contents when authentication expires', async () => {
    transport()
    await openDrivers()
    fireEvent.change(screen.getByLabelText('導航測試編號'), { target: { value: 'SYNTHETIC-EXPIRED' } })
    fireEvent.click(screen.getByRole('button', { name: '模擬司機操作登入失效' }))
    expect(await screen.findByRole('heading', { name: '登入工作空間' })).toBeVisible()
    expect(screen.queryByLabelText('導航測試編號')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '司機管理' })).not.toBeInTheDocument()
  })

  it('cannot confirm navigation during a save and removes an obsolete dirty prompt after success', async () => {
    const fetch = transport()
    await openDrivers()
    fireEvent.change(screen.getByLabelText('導航測試編號'), { target: { value: 'SYNTHETIC-SAVING' } })
    fireEvent.click(screen.getByRole('button', { name: '客戶管理' }))
    fireEvent.click(screen.getByRole('button', { name: '模擬司機保存中' }))
    const leave = screen.getByRole('button', { name: '捨棄司機內容並離開' })
    expect(leave).toBeDisabled()
    fireEvent.click(leave)
    expect(fetch.mock.calls.some(([path]) => path.startsWith('/api/v1/crm/customers?'))).toBe(false)
    expect(screen.getByLabelText('導航測試編號')).toHaveValue('SYNTHETIC-SAVING')
    fireEvent.click(screen.getByRole('button', { name: '模擬司機保存完成' }))
    expect(screen.queryByRole('alert', { name: '確認切換工作頁面' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '客戶管理' }))
    expect(await screen.findByRole('heading', { name: '客戶管理' })).toBeVisible()
  })
})
