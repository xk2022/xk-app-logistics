import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import type { Account, Role } from './api'
import { refreshCsrf } from './api'
import App from './App'

const lifecycle = vi.hoisted(() => ({ instances: 0, current: 0, expire: null as (() => void) | null }))
vi.mock('./DriverWorkspace', async () => {
  const { useState } = await import('react')
  return { default: function MockDriverWorkspace({ onExpired, onDirtyChange, onBusyChange, logoutPending }: { onExpired: () => void; onDirtyChange?: (dirty: boolean) => void; onBusyChange?: (busy: boolean) => void; logoutPending?: boolean }) {
    const [instance] = useState(() => ++lifecycle.instances); const [raw, setRaw] = useState(''); const [unknown, setUnknown] = useState(false)
    lifecycle.current = instance; lifecycle.expire = onExpired
    if (logoutPending) return <section role="status">正在安全登出…</section>
    return <section><h1>本人執行導覽測試</h1><p>合成本人保護路線與聯絡資訊</p><label>本機原始補件說明<textarea value={raw} onChange={(event) => { setRaw(event.target.value); onDirtyChange?.(true) }} /></label>{unknown && <p>原次結果仍未知</p>}<button onClick={() => { setUnknown(true); onDirtyChange?.(true) }}>模擬本人原次未知</button><button onClick={() => { onDirtyChange?.(true); onBusyChange?.(true) }}>模擬本人原次傳送中</button><button onClick={() => { onBusyChange?.(false); onDirtyChange?.(false); setUnknown(false) }}>模擬本人原次收據成功</button><button onClick={onExpired}>模擬本人真授權失效</button></section>
  } }
})
vi.mock('./DriverSelf', () => ({ default: () => <h1>本人主檔導航測試</h1> }))

const account: Account = { id: '00000000-0000-4000-8000-000000000001', username: 'synthetic.driver.navigation', displayName: '合成原司機', role: 'DRIVER', enabled: true, mustChangePassword: false, version: 0 }
const nextAccount = { ...account, id: '00000000-0000-4000-8000-000000000002', username: 'synthetic.next.driver', displayName: '合成新司機' }
function envelope(data: unknown, status = 200) { return new Response(JSON.stringify({ code: status < 400 ? '0000' : 'E0503', message: '成功', data, timestamp: '2026-11-26T12:00:00+08:00' }), { status }) }
function deferred<T>() { let resolve!: (value: T) => void; let reject!: (reason: unknown) => void; const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail }); return { promise, resolve, reject } }
function transport(role: Role = 'DRIVER', forced = false, logoutResponse?: () => Promise<Response>, csrfResponse?: () => Promise<Response>, meResponse?: () => Promise<Response>) {
  const fetch = vi.fn(async (path: string) => path === '/api/v1/auth/me' ? meResponse ? meResponse() : envelope({ ...account, role, mustChangePassword: forced })
    : path === '/api/v1/auth/csrf' ? csrfResponse ? csrfResponse() : envelope({ token: 'synthetic-driver-navigation-csrf', headerName: 'X-CSRF-TOKEN' })
      : path === '/api/v1/auth/login' ? envelope(nextAccount)
        : path === '/api/v1/auth/logout' ? logoutResponse ? logoutResponse() : envelope(null, 503) : envelope(null, 404))
  vi.stubGlobal('fetch', fetch); return fetch
}
async function openDriver() { render(<App />); fireEvent.click(await screen.findByRole('button', { name: '本人運輸任務' })); await screen.findByRole('heading', { name: '本人執行導覽測試' }) }
async function loginNext() {
  await screen.findByRole('heading', { name: '登入工作空間' }); fireEvent.change(screen.getByLabelText('帳號'), { target: { value: nextAccount.username } }); fireEvent.change(screen.getByLabelText('密碼'), { target: { value: 'synthetic navigation login value' } }); fireEvent.click(screen.getByRole('button', { name: '登入' })); await screen.findByText(/合成新司機，歡迎回來/)
}
beforeEach(() => { lifecycle.instances = 0; lifecycle.current = 0; lifecycle.expire = null })

it('mounts the DRIVER workspace only after explicit navigation and preserves the own-master route', async () => {
  const fetch = transport(); await refreshCsrf(); render(<App />); await screen.findByRole('button', { name: '本人運輸任務' })
  expect(lifecycle.instances).toBe(0); expect(fetch.mock.calls.some(([path]) => path.startsWith('/api/v1/tms/'))).toBe(false)
  fireEvent.click(screen.getByRole('button', { name: '查看本人任務' })); expect(await screen.findByRole('heading', { name: '本人執行導覽測試' })).toBeVisible()
  for (const name of ['訂單工作台', '調度工作台', '確認與結案', '異常處理', '客戶管理', '車輛管理', '司機管理']) expect(screen.queryByRole('button', { name })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '本人司機資料' })); expect(await screen.findByRole('heading', { name: '本人主檔導航測試' })).toBeVisible(); expect(screen.queryByText('合成本人保護路線與聯絡資訊')).not.toBeInTheDocument()
})

it.each(['ADMIN', 'DISPATCHER'] as Role[])('does not preload or offer the DRIVER workspace for %s', async (role) => {
  const fetch = transport(role); await refreshCsrf(); render(<App />); await screen.findByRole('button', { name: '調度工作台' }); expect(screen.queryByRole('button', { name: '本人運輸任務' })).not.toBeInTheDocument(); expect(lifecycle.instances).toBe(0); expect(fetch.mock.calls.some(([path]) => path.startsWith('/api/v1/tms/'))).toBe(false)
})
it.each(['ADMIN', 'DISPATCHER', 'DRIVER'] as Role[])('does not mount protected execution before %s changes its temporary password', async (role) => {
  transport(role, true); await refreshCsrf(); render(<App />); await screen.findByRole('heading', { name: '首次登入，請先修改密碼' }); expect(lifecycle.instances).toBe(0); expect(screen.queryByRole('button', { name: '本人運輸任務' })).not.toBeInTheDocument()
})
it('retains exact raw draft when navigation is cancelled and discards only on explicit departure', async () => {
  transport(); await refreshCsrf(); await openDriver(); const raw = ' 合成 e\u0301\u0000 說明\n尾 '; fireEvent.change(screen.getByLabelText('本機原始補件說明'), { target: { value: raw } }); fireEvent.click(screen.getByRole('button', { name: '本人司機資料' })); expect(screen.getByRole('alert', { name: '確認切換工作頁面' })).toHaveTextContent('本人任務內容尚未完成確認'); fireEvent.click(screen.getByRole('button', { name: '留在本人任務頁面' })); expect(screen.getByLabelText('本機原始補件說明')).toHaveValue(raw); fireEvent.click(screen.getByRole('button', { name: '本人司機資料' })); fireEvent.click(screen.getByRole('button', { name: '捨棄本人任務內容並離開' })); expect(await screen.findByRole('heading', { name: '本人主檔導航測試' })).toBeVisible(); expect(screen.queryByLabelText('本機原始補件說明')).not.toBeInTheDocument()
})
it('does not send logout or leave through an already-open guard while an original command is busy', async () => {
  const fetch = transport(); await refreshCsrf(); await openDriver(); fireEvent.click(screen.getByRole('button', { name: '模擬本人原次未知' })); fireEvent.click(screen.getByRole('button', { name: '登出' })); fireEvent.click(screen.getByRole('button', { name: '模擬本人原次傳送中' })); const discard = screen.getByRole('button', { name: '捨棄本人任務內容並登出' }); expect(discard).toBeDisabled(); fireEvent.click(discard); expect(fetch.mock.calls.some(([path]) => path === '/api/v1/auth/logout')).toBe(false); fireEvent.click(screen.getByRole('button', { name: '模擬本人原次收據成功' })); await waitFor(() => expect(screen.queryByRole('alert', { name: '確認切換工作頁面' })).not.toBeInTheDocument())
})
it('hides private DOM during failed logout while retaining the same instance, raw draft, unknown and guard', async () => {
  const pending = deferred<Response>(); const fetch = transport('DRIVER', false, () => pending.promise); await refreshCsrf(); await openDriver(); const raw = ' 合成 e\u0301\u0000 補件\n尾 '; fireEvent.change(screen.getByLabelText('本機原始補件說明'), { target: { value: raw } }); fireEvent.click(screen.getByRole('button', { name: '模擬本人原次未知' })); const instance = lifecycle.current; fireEvent.click(screen.getByRole('button', { name: '登出' })); fireEvent.click(screen.getByRole('button', { name: '留在本人任務頁面' })); expect(screen.getByLabelText('本機原始補件說明')).toHaveValue(raw); fireEvent.click(screen.getByRole('button', { name: '登出' })); fireEvent.click(screen.getByRole('button', { name: '捨棄本人任務內容並登出' })); await waitFor(() => expect(fetch.mock.calls.some(([path]) => path === '/api/v1/auth/logout')).toBe(true)); expect(screen.queryByText('合成本人保護路線與聯絡資訊')).not.toBeInTheDocument(); expect(screen.queryByLabelText('本機原始補件說明')).not.toBeInTheDocument(); expect(screen.getByRole('status')).toHaveTextContent('正在安全登出'); expect(lifecycle.current).toBe(instance)
  await act(async () => { pending.resolve(envelope(null, 503)) }); expect(await screen.findByLabelText('本機原始補件說明')).toHaveValue(raw); expect(lifecycle.current).toBe(instance); expect(lifecycle.instances).toBe(1); expect(screen.getByText('原次結果仍未知')).toBeVisible(); expect(fetch.mock.calls.filter(([path]) => path === '/api/v1/auth/logout')).toHaveLength(1); fireEvent.click(screen.getByRole('button', { name: '本人司機資料' })); expect(screen.getByRole('button', { name: '捨棄本人任務內容並離開' })).toBeEnabled()
})
it('clears private execution and guards on successful logout', async () => {
  transport('DRIVER', false, async () => envelope(null)); await refreshCsrf(); await openDriver(); fireEvent.click(screen.getByRole('button', { name: '模擬本人原次未知' })); fireEvent.click(screen.getByRole('button', { name: '登出' })); fireEvent.click(screen.getByRole('button', { name: '捨棄本人任務內容並登出' })); await screen.findByRole('heading', { name: '登入工作空間' }); expect(screen.queryByText('原次結果仍未知')).not.toBeInTheDocument(); expect(screen.queryByRole('alert', { name: '確認切換工作頁面' })).not.toBeInTheDocument()
})
it.each(['success', 'failure'])('ignores an old dispatched logout %s after actual new login', async (outcome) => {
  const pending = deferred<Response>(); const fetch = transport('DRIVER', false, () => pending.promise); await refreshCsrf(); await openDriver(); fireEvent.click(screen.getByRole('button', { name: '登出' })); await waitFor(() => expect(fetch.mock.calls.some(([path]) => path === '/api/v1/auth/logout')).toBe(true)); await act(async () => { lifecycle.expire?.() }); await loginNext(); await act(async () => { if (outcome === 'success') pending.resolve(envelope(null)); else pending.reject(new TypeError('synthetic transport failure')) }); expect(screen.getByText(/合成新司機，歡迎回來/)).toBeVisible(); expect(screen.queryByRole('heading', { name: '登入工作空間' })).not.toBeInTheDocument(); expect(screen.getByRole('button', { name: '登出' })).toBeEnabled(); expect(screen.queryByText(/服務暫時無法使用/)).not.toBeInTheDocument()
})
it('cancels an old logout before POST when its CSRF resolves only after expiry and a new login', async () => {
  const old = deferred<Response>(); let csrfReads = 0; const fetch = transport('DRIVER', false, undefined, async () => { csrfReads += 1; return csrfReads === 1 ? envelope(null, 503) : csrfReads === 2 ? old.promise : envelope({ token: 'fresh-new-session', headerName: 'X-CSRF-TOKEN' }) }); await expect(refreshCsrf()).rejects.toMatchObject({ status: 503 }); await openDriver(); fireEvent.click(screen.getByRole('button', { name: '登出' })); await waitFor(() => expect(csrfReads).toBe(2)); await act(async () => { lifecycle.expire?.() }); await loginNext(); await act(async () => { old.resolve(envelope({ token: 'retired-old-session', headerName: 'X-CSRF-TOKEN' })) }); expect(fetch.mock.calls.filter(([path]) => path === '/api/v1/auth/logout')).toHaveLength(0); expect(screen.getByText(/合成新司機，歡迎回來/)).toBeVisible(); expect(screen.getByRole('button', { name: '登出' })).toBeEnabled()
})

it('keeps a clean workspace mounted and blocks all navigation while logout is pending', async () => {
  const pending = deferred<Response>(); const fetch = transport('DRIVER', false, () => pending.promise); await refreshCsrf(); await openDriver(); const instance = lifecycle.current; fireEvent.click(screen.getByRole('button', { name: '登出' })); await waitFor(() => expect(fetch.mock.calls.some(([path]) => path === '/api/v1/auth/logout')).toBe(true)); for (const name of ['本人司機資料', '修改密碼', '我的工作台']) { const button = screen.getByRole('button', { name }); expect(button).toBeDisabled(); fireEvent.click(button) } expect(lifecycle.current).toBe(instance); expect(lifecycle.instances).toBe(1); expect(screen.getByRole('status')).toHaveTextContent('正在安全登出'); expect(screen.queryByRole('heading', { name: '本人主檔導航測試' })).not.toBeInTheDocument(); await act(async () => { pending.resolve(envelope(null, 503)) }); expect(await screen.findByRole('heading', { name: '本人執行導覽測試' })).toBeVisible(); expect(lifecycle.current).toBe(instance); expect(screen.getByRole('button', { name: '本人司機資料' })).toBeEnabled()
})

it('retires a pending me check without leaving the check button stuck after failed logout', async () => {
  const pending = deferred<Response>(); let meReads = 0; transport('DRIVER', false, undefined, undefined, async () => ++meReads === 1 ? envelope(account) : pending.promise); await refreshCsrf(); render(<App />); fireEvent.click(await screen.findByRole('button', { name: '重新確認本人狀態' })); await waitFor(() => expect(meReads).toBe(2)); expect(screen.getByRole('button', { name: '確認中…' })).toBeDisabled(); fireEvent.click(screen.getByRole('button', { name: '登出' })); await screen.findByText('服務暫時無法使用，請稍後重試。'); expect(screen.getByRole('button', { name: '重新確認本人狀態' })).toBeEnabled(); await act(async () => { pending.resolve(envelope({ ...account, displayName: '合成過時帳號狀態' })) }); expect(screen.getByRole('button', { name: '重新確認本人狀態' })).toBeEnabled(); expect(screen.getByText(/合成原司機，歡迎回來/)).toBeVisible(); expect(screen.queryByText(/合成過時帳號狀態/)).not.toBeInTheDocument()
})
