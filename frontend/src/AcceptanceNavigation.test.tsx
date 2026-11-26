import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import App from './App'
import type { Account, Role } from './api'

vi.mock('./OrderManager', () => ({ default: ({ onDirtyChange, onBusyChange, onExpired }: { onDirtyChange?: (value: boolean) => void; onBusyChange?: (value: boolean) => void; onExpired: () => void }) => <section><h1>受理導覽測試</h1><button onClick={() => onDirtyChange?.(true)}>模擬受理結果未確認</button><button onClick={() => { onDirtyChange?.(true); onBusyChange?.(true) }}>模擬同次受理傳送中</button><button onClick={() => { onBusyChange?.(false); onDirtyChange?.(false) }}>模擬正式受理成功</button><button onClick={onExpired}>模擬受理登入失效</button></section> }))

const account: Account = { id: '00000000-0000-4000-8000-000000000001', username: 'synthetic.acceptance', displayName: '合成受理使用者', role: 'ADMIN', enabled: true, mustChangePassword: false, version: 0 }
function envelope(data: unknown, status = 200) {
  return new Response(JSON.stringify({ code: status < 400 ? '0000' : 'E0404', message: '成功', data, timestamp: '2026-11-26T08:00:00+08:00' }), { status })
}
function transport(role: Role) {
  const fetch = vi.fn(async (path: string) => path === '/api/v1/auth/me' ? envelope({ ...account, role })
    : path.startsWith('/api/v1/crm/customers?') ? envelope({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }) : envelope(null, 404))
  vi.stubGlobal('fetch', fetch)
  return fetch
}
async function openOrders() {
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: '訂單工作台' }))
  await screen.findByRole('heading', { name: '受理導覽測試' })
}

it('blocks an already-open module leave prompt during acceptance retry and clears it after acknowledgment', async () => {
  const fetch = transport('ADMIN')
  await openOrders()
  fireEvent.click(screen.getByRole('button', { name: '模擬受理結果未確認' }))
  fireEvent.click(screen.getByRole('button', { name: '客戶管理' }))
  const prompt = screen.getByRole('alert', { name: '確認切換工作頁面' })
  expect(prompt).toHaveTextContent('訂單內容尚未完成確認')
  fireEvent.click(screen.getByRole('button', { name: '模擬同次受理傳送中' }))
  const discard = screen.getByRole('button', { name: '捨棄訂單內容並離開' })
  expect(discard).toBeDisabled()
  fireEvent.click(discard)
  expect(fetch.mock.calls.some(([path]) => path.startsWith('/api/v1/crm/'))).toBe(false)
  expect(screen.getByRole('heading', { name: '受理導覽測試' })).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '模擬正式受理成功' }))
  await waitFor(() => expect(prompt).not.toBeInTheDocument())
  fireEvent.click(screen.getByRole('button', { name: '客戶管理' }))
  expect(await screen.findByRole('heading', { name: '客戶管理' })).toBeVisible()
})

it('keeps a dispatcher acceptance mounted during network activity and removes private state on expiry', async () => {
  const fetch = transport('DISPATCHER')
  await openOrders()
  fireEvent.click(screen.getByRole('button', { name: '模擬同次受理傳送中' }))
  fireEvent.click(screen.getByRole('button', { name: '登出' }))
  expect(screen.getByRole('button', { name: '捨棄訂單內容並登出' })).toBeDisabled()
  expect(fetch.mock.calls.some(([path]) => path.endsWith('/logout'))).toBe(false)
  fireEvent.click(screen.getByRole('button', { name: '模擬受理登入失效' }))
  expect(await screen.findByRole('heading', { name: '登入工作空間' })).toBeVisible()
  expect(screen.queryByRole('heading', { name: '受理導覽測試' })).not.toBeInTheDocument()
  expect(screen.queryByRole('alert', { name: '確認切換工作頁面' })).not.toBeInTheDocument()
})
