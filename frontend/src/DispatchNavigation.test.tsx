import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import App from './App'
import type { Account, Role } from './api'

vi.mock('./DispatchManager', () => ({ default: ({ onDirtyChange, onBusyChange, onExpired }: { onDirtyChange?: (dirty: boolean) => void; onBusyChange?: (busy: boolean) => void; onExpired: () => void }) => <section><h1>派工導覽測試</h1><label>導覽合成派工內容<input onChange={() => onDirtyChange?.(true)} /></label><button onClick={() => onDirtyChange?.(true)}>模擬派工結果未知</button><button onClick={() => { onDirtyChange?.(true); onBusyChange?.(true) }}>模擬同次派工傳送中</button><button onClick={() => { onBusyChange?.(false); onDirtyChange?.(false) }}>模擬原次派工確認成功</button><button onClick={onExpired}>模擬調度授權失效</button></section> }))
vi.mock('./MyTasks', () => ({ default: ({ onExpired }: { onExpired: () => void }) => <section><h1>本人任務唯讀測試</h1><p>合成敏感路線與聯絡資訊</p><button onClick={onExpired}>模擬本人任務授權失效</button></section> }))
vi.mock('./DriverSelf', () => ({ default: () => <h1>原本人司機資料測試</h1> }))

const account: Account = { id: '00000000-0000-4000-8000-000000000001', username: 'synthetic.dispatch.ui', displayName: '合成調度使用者', role: 'ADMIN', enabled: true, mustChangePassword: false, version: 0 }
function envelope(data: unknown, status = 200) {
  return new Response(JSON.stringify({ code: status < 400 ? '0000' : 'E0503', message: '成功', data, timestamp: '2026-11-26T12:00:00+08:00' }), { status })
}
function transport(role: Role = 'ADMIN', forced = false, logoutResponse?: () => Promise<Response>) {
  const fetch = vi.fn(async (path: string) => path === '/api/v1/auth/me' ? envelope({ ...account, role, mustChangePassword: forced })
    : path === '/api/v1/auth/csrf' ? envelope({ token: 'synthetic-dispatch-csrf', headerName: 'X-CSRF-TOKEN' })
      : path === '/api/v1/auth/logout' ? logoutResponse ? logoutResponse() : envelope(null, 503)
        : path.startsWith('/api/v1/crm/customers?') ? envelope({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }) : envelope(null, 404))
  vi.stubGlobal('fetch', fetch)
  return fetch
}
async function openDispatch() {
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: '調度工作台' }))
  await screen.findByRole('heading', { name: '派工導覽測試' })
}

it.each(['ADMIN', 'DISPATCHER'] as Role[])('lets %s enter dispatch without exposing the driver-only task route', async (role) => {
  transport(role)
  render(<App />)
  expect(await screen.findByRole('button', { name: '調度工作台' })).toBeVisible()
  expect(screen.queryByRole('button', { name: '本人運輸任務' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '開始調度' }))
  expect(await screen.findByRole('heading', { name: '派工導覽測試' })).toBeVisible()
  expect(screen.getByRole('button', { name: '調度工作台' })).toHaveAttribute('aria-current', 'page')
})

it('keeps DRIVER on the separate own-task page and preserves the existing own-master-data navigation', async () => {
  const fetch = transport('DRIVER')
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: '查看本人任務' }))
  expect(await screen.findByRole('heading', { name: '本人任務唯讀測試' })).toBeVisible()
  for (const name of ['調度工作台', '開始調度', '訂單工作台', '車輛管理', '司機管理', '客戶管理', '帳號管理']) expect(screen.queryByRole('button', { name })).not.toBeInTheDocument()
  expect(fetch.mock.calls.some(([path]) => /^\/api\/v1\/(tms|orders|fms)\//.test(path))).toBe(false)
  fireEvent.click(screen.getByRole('button', { name: '本人司機資料' }))
  expect(await screen.findByRole('heading', { name: '原本人司機資料測試' })).toBeVisible()
  expect(screen.queryByText('合成敏感路線與聯絡資訊')).not.toBeInTheDocument()
})

it.each(['ADMIN', 'DISPATCHER', 'DRIVER'] as Role[])('keeps %s behind the first-password barrier', async (role) => {
  const fetch = transport(role, true)
  render(<App />)
  await screen.findByRole('heading', { name: '首次登入，請先修改密碼' })
  for (const name of ['調度工作台', '開始調度', '本人運輸任務', '查看本人任務']) expect(screen.queryByRole('button', { name })).not.toBeInTheDocument()
  expect(fetch.mock.calls.some(([path]) => path.startsWith('/api/v1/tms/'))).toBe(false)
})

it('retains the original uncertain dispatch state until the user explicitly leaves', async () => {
  const fetch = transport()
  await openDispatch()
  const input = screen.getByLabelText('導覽合成派工內容')
  fireEvent.change(input, { target: { value: '合成未確認選擇' } })
  fireEvent.click(screen.getByRole('button', { name: '客戶管理' }))
  expect(screen.getByRole('alert', { name: '確認切換工作頁面' })).toHaveTextContent('派工內容尚未完成確認')
  expect(fetch.mock.calls.some(([path]) => path.startsWith('/api/v1/crm/'))).toBe(false)
  fireEvent.click(screen.getByRole('button', { name: '留在派工頁面' }))
  expect(input).toHaveValue('合成未確認選擇')
  fireEvent.click(screen.getByRole('button', { name: '客戶管理' }))
  fireEvent.click(screen.getByRole('button', { name: '捨棄派工內容並離開' }))
  expect(await screen.findByRole('heading', { name: '客戶管理' })).toBeVisible()
  expect(screen.queryByLabelText('導覽合成派工內容')).not.toBeInTheDocument()
})

it('disables an existing leave prompt while the same dispatch retry is in flight', async () => {
  const fetch = transport()
  await openDispatch()
  fireEvent.click(screen.getByRole('button', { name: '模擬派工結果未知' }))
  fireEvent.click(screen.getByRole('button', { name: '客戶管理' }))
  const prompt = screen.getByRole('alert', { name: '確認切換工作頁面' })
  fireEvent.click(screen.getByRole('button', { name: '模擬同次派工傳送中' }))
  const discard = screen.getByRole('button', { name: '捨棄派工內容並離開' })
  expect(discard).toBeDisabled()
  fireEvent.click(discard)
  expect(fetch.mock.calls.some(([path]) => path.startsWith('/api/v1/crm/'))).toBe(false)
  fireEvent.click(screen.getByRole('button', { name: '模擬原次派工確認成功' }))
  await waitFor(() => expect(prompt).not.toBeInTheDocument())
  fireEvent.click(screen.getByRole('button', { name: '客戶管理' }))
  expect(await screen.findByRole('heading', { name: '客戶管理' })).toBeVisible()
})

it('retains dispatch content and its navigation guard when an explicitly confirmed logout fails', async () => {
  transport()
  await openDispatch()
  fireEvent.change(screen.getByLabelText('導覽合成派工內容'), { target: { value: '合成原次內容' } })
  fireEvent.click(screen.getByRole('button', { name: '登出' }))
  fireEvent.click(screen.getByRole('button', { name: '捨棄派工內容並登出' }))
  await waitFor(() => expect(screen.getAllByRole('alert').some((node) => node.textContent?.includes('服務暫時無法使用'))).toBe(true))
  expect(screen.getByLabelText('導覽合成派工內容')).toHaveValue('合成原次內容')
  fireEvent.click(screen.getByRole('button', { name: '客戶管理' }))
  expect(screen.getByRole('button', { name: '捨棄派工內容並離開' })).toBeVisible()
})

it('clears dispatch state and an outstanding logout prompt on authorization expiry', async () => {
  transport('DISPATCHER')
  await openDispatch()
  fireEvent.click(screen.getByRole('button', { name: '模擬同次派工傳送中' }))
  fireEvent.click(screen.getByRole('button', { name: '登出' }))
  expect(screen.getByRole('button', { name: '捨棄派工內容並登出' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: '模擬調度授權失效' }))
  expect(await screen.findByRole('heading', { name: '登入工作空間' })).toBeVisible()
  expect(screen.queryByRole('heading', { name: '派工導覽測試' })).not.toBeInTheDocument()
  expect(screen.queryByRole('alert', { name: '確認切換工作頁面' })).not.toBeInTheDocument()
})

it('removes private own-task content when authorization expires', async () => {
  transport('DRIVER')
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: '本人運輸任務' }))
  fireEvent.click(screen.getByRole('button', { name: '模擬本人任務授權失效' }))
  expect(await screen.findByRole('heading', { name: '登入工作空間' })).toBeVisible()
  expect(screen.queryByText('合成敏感路線與聯絡資訊')).not.toBeInTheDocument()
})

it('unmounts private own tasks immediately during logout and shows the login page on success', async () => {
  let release: ((response: Response) => void) | undefined
  const response = new Promise<Response>((resolve) => { release = resolve })
  const fetch = transport('DRIVER', false, () => response)
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: '本人運輸任務' }))
  expect(screen.getByText('合成敏感路線與聯絡資訊')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '登出' }))
  await waitFor(() => expect(fetch.mock.calls.some(([path]) => path === '/api/v1/auth/logout')).toBe(true))
  expect(screen.queryByText('合成敏感路線與聯絡資訊')).not.toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveTextContent('正在安全登出')
  release?.(envelope(null))
  expect(await screen.findByRole('heading', { name: '登入工作空間' })).toBeVisible()
})
