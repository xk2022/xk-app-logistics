import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import App from './App'
import type { Account, Role } from './api'

vi.mock('./ClosureWorkspace', () => ({ default: ({ onDirtyChange, onBusyChange, onExpired }: { onDirtyChange?: (dirty: boolean) => void; onBusyChange?: (busy: boolean) => void; onExpired: () => void }) => <section><h1>確認與結案導覽測試</h1><p>合成保護確認與結案原始內容</p><label>導覽合成處置說明<input onChange={() => onDirtyChange?.(true)} /></label><button onClick={() => onDirtyChange?.(true)}>模擬確認與結案結果未知</button><button onClick={() => { onDirtyChange?.(true); onBusyChange?.(true) }}>模擬同次確認與結案傳送中</button><button onClick={() => { onBusyChange?.(false); onDirtyChange?.(false) }}>模擬原次確認與結案確認成功</button><button onClick={onExpired}>模擬確認與結案授權失效</button></section> }))

const account: Account = { id: '00000000-0000-4000-8000-000000000001', username: 'synthetic.closure.navigation', displayName: '合成確認與結案管理使用者', role: 'ADMIN', enabled: true, mustChangePassword: false, version: 0 }
function envelope(data: unknown, status = 200) { return new Response(JSON.stringify({ code: status < 400 ? '0000' : 'E0503', message: '成功', data, timestamp: '2026-11-26T12:00:00+08:00' }), { status }) }
function transport(role: Role = 'ADMIN', forced = false) {
  const fetch = vi.fn(async (path: string) => path === '/api/v1/auth/me' ? envelope({ ...account, role, mustChangePassword: forced })
    : path === '/api/v1/auth/csrf' ? envelope({ token: 'synthetic-closure-csrf', headerName: 'X-CSRF-TOKEN' })
      : path === '/api/v1/auth/logout' ? envelope(null, 503)
        : path.startsWith('/api/v1/crm/customers?') ? envelope({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }) : envelope(null, 404))
  vi.stubGlobal('fetch', fetch); return fetch
}
async function openClosure() {
  render(<App />); fireEvent.click(await screen.findByRole('button', { name: '確認與結案' }))
  await screen.findByRole('heading', { name: '確認與結案導覽測試' })
}

it.each(['ADMIN', 'DISPATCHER'] as Role[])('lets normal %s enter closure management from its workspace', async (role) => {
  const fetch = transport(role); render(<App />)
  expect(await screen.findByRole('button', { name: '確認與結案' })).toBeVisible()
  expect(fetch.mock.calls.some(([path]) => path.startsWith('/api/v1/tms/completions'))).toBe(false)
  fireEvent.click(screen.getByRole('button', { name: '開啟確認與結案' }))
  expect(await screen.findByRole('heading', { name: '確認與結案導覽測試' })).toBeVisible()
  expect(screen.getByRole('button', { name: '確認與結案' })).toHaveAttribute('aria-current', 'page')
  expect(screen.queryByRole('button', { name: '本人運輸任務' })).not.toBeInTheDocument()
})

it('does not offer or preload manager closure data for DRIVER', async () => {
  const fetch = transport('DRIVER'); render(<App />)
  await screen.findByRole('button', { name: '本人運輸任務' })
  for (const name of ['確認與結案', '開啟確認與結案']) expect(screen.queryByRole('button', { name })).not.toBeInTheDocument()
  expect(fetch.mock.calls.some(([path]) => path.startsWith('/api/v1/tms/completions'))).toBe(false)
})

it.each(['ADMIN', 'DISPATCHER', 'DRIVER'] as Role[])('keeps %s behind its first-password barrier without closure data', async (role) => {
  const fetch = transport(role, true); render(<App />)
  await screen.findByRole('heading', { name: '首次登入，請先修改密碼' })
  for (const name of ['確認與結案', '開啟確認與結案']) expect(screen.queryByRole('button', { name })).not.toBeInTheDocument()
  expect(fetch.mock.calls.some(([path]) => path.startsWith('/api/v1/tms/'))).toBe(false)
})

it('preserves the raw local closure note until an explicit navigation discard', async () => {
  const fetch = transport(); await openClosure()
  const input = screen.getByLabelText('導覽合成處置說明'); fireEvent.change(input, { target: { value: ' 合成 e\u0301 原始說明 ' } })
  fireEvent.click(screen.getByRole('button', { name: '客戶管理' }))
  expect(screen.getByRole('alert', { name: '確認切換工作頁面' })).toHaveTextContent('確認與結案內容尚未完成確認')
  expect(fetch.mock.calls.some(([path]) => path.startsWith('/api/v1/crm/'))).toBe(false)
  fireEvent.click(screen.getByRole('button', { name: '留在確認與結案頁面' })); expect(input).toHaveValue(' 合成 e\u0301 原始說明 ')
  fireEvent.click(screen.getByRole('button', { name: '客戶管理' })); fireEvent.click(screen.getByRole('button', { name: '捨棄確認與結案內容並離開' }))
  expect(await screen.findByRole('heading', { name: '客戶管理' })).toBeVisible()
  expect(screen.queryByText('合成保護確認與結案原始內容')).not.toBeInTheDocument()
})

it('blocks an existing discard prompt while the same closure retry is in flight', async () => {
  const fetch = transport(); await openClosure()
  fireEvent.click(screen.getByRole('button', { name: '模擬確認與結案結果未知' })); fireEvent.click(screen.getByRole('button', { name: '客戶管理' }))
  const prompt = screen.getByRole('alert', { name: '確認切換工作頁面' })
  fireEvent.click(screen.getByRole('button', { name: '模擬同次確認與結案傳送中' })); const discard = screen.getByRole('button', { name: '捨棄確認與結案內容並離開' })
  expect(discard).toBeDisabled(); fireEvent.click(discard)
  expect(fetch.mock.calls.some(([path]) => path.startsWith('/api/v1/crm/'))).toBe(false)
  fireEvent.click(screen.getByRole('button', { name: '模擬原次確認與結案確認成功' })); await waitFor(() => expect(prompt).not.toBeInTheDocument())
  fireEvent.click(screen.getByRole('button', { name: '客戶管理' })); expect(await screen.findByRole('heading', { name: '客戶管理' })).toBeVisible()
})

it('retains closure draft and guard when a confirmed logout fails', async () => {
  transport(); await openClosure()
  fireEvent.change(screen.getByLabelText('導覽合成處置說明'), { target: { value: '合成待確認原次說明' } })
  fireEvent.click(screen.getByRole('button', { name: '登出' })); fireEvent.click(screen.getByRole('button', { name: '捨棄確認與結案內容並登出' }))
  await waitFor(() => expect(screen.getAllByRole('alert').some((node) => node.textContent?.includes('服務暫時無法使用'))).toBe(true))
  expect(screen.getByLabelText('導覽合成處置說明')).toHaveValue('合成待確認原次說明')
  fireEvent.click(screen.getByRole('button', { name: '客戶管理' })); expect(screen.getByRole('button', { name: '捨棄確認與結案內容並離開' })).toBeVisible()
})

it('clears closure content, busy state and pending logout on authorization loss', async () => {
  transport('DISPATCHER'); await openClosure()
  fireEvent.click(screen.getByRole('button', { name: '模擬同次確認與結案傳送中' })); fireEvent.click(screen.getByRole('button', { name: '登出' }))
  expect(screen.getByRole('button', { name: '捨棄確認與結案內容並登出' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: '模擬確認與結案授權失效' }))
  expect(await screen.findByRole('heading', { name: '登入工作空間' })).toBeVisible()
  expect(screen.queryByText('合成保護確認與結案原始內容')).not.toBeInTheDocument()
  expect(screen.queryByRole('alert', { name: '確認切換工作頁面' })).not.toBeInTheDocument()
})
