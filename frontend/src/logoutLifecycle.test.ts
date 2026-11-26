import { beforeEach, expect, it, vi } from 'vitest'

function response(data: unknown, status = 200) {
  return new Response(JSON.stringify({ code: status < 400 ? '0000' : 'E0503', message: '成功', data, timestamp: '2026-11-26T12:00:00+08:00' }), { status })
}
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>((done) => { resolve = done }); return { promise, resolve } }
beforeEach(() => { vi.resetModules() })

it('never dispatches a previously aborted logout, even with a transport that ignores abort', async () => {
  const fetch = vi.fn().mockResolvedValue(response(null)); vi.stubGlobal('fetch', fetch)
  const api = await import('./api'); const controller = new AbortController(); controller.abort()
  await expect(api.logout(controller.signal)).rejects.toMatchObject({ status: 0 })
  expect(fetch).not.toHaveBeenCalled()
})

it('cancels a logout waiting for CSRF and does not poison a later session token', async () => {
  const old = deferred<Response>(); let csrfReads = 0
  const fetch = vi.fn(async (path: string) => path === '/api/v1/auth/csrf'
    ? ++csrfReads === 1 ? old.promise : response({ token: 'new-session-token', headerName: 'X-CSRF-TOKEN' }) : response(null))
  vi.stubGlobal('fetch', fetch); const api = await import('./api'); const controller = new AbortController()
  const outcome = api.logout(controller.signal).then(() => null, (error: unknown) => error)
  expect(csrfReads).toBe(1); controller.abort(); await api.refreshCsrf()
  old.resolve(response({ token: 'retired-session-token', headerName: 'X-CSRF-TOKEN' }))
  expect(await outcome).toMatchObject({ status: 0 })
  expect(fetch.mock.calls.filter(([path]) => path === '/api/v1/auth/logout')).toHaveLength(0)
  expect(await api.getCsrfHeaders()).toEqual({ 'X-CSRF-TOKEN': 'new-session-token' })
})

it('does not rotate or clear the new token after a retired dispatched logout responds late', async () => {
  const old = deferred<Response>(); let csrfReads = 0
  const fetch = vi.fn(async (path: string) => path === '/api/v1/auth/csrf'
    ? response({ token: ++csrfReads === 1 ? 'before' : 'current', headerName: 'X-CSRF-TOKEN' }) : old.promise)
  vi.stubGlobal('fetch', fetch); const api = await import('./api'); await api.refreshCsrf()
  const controller = new AbortController(); const outcome = api.logout(controller.signal).then(() => null, (error: unknown) => error)
  expect(fetch.mock.calls.filter(([path]) => path === '/api/v1/auth/logout')).toHaveLength(1)
  controller.abort(); await api.refreshCsrf(); old.resolve(response(null))
  expect(await outcome).toMatchObject({ status: 0 }); expect(csrfReads).toBe(2)
  expect(await api.getCsrfHeaders()).toEqual({ 'X-CSRF-TOKEN': 'current' })
})

it('refuses an aborted write with a cached token without starting any request', async () => {
  const fetch = vi.fn().mockResolvedValue(response({ token: 'before', headerName: 'X-CSRF-TOKEN' })); vi.stubGlobal('fetch', fetch)
  const api = await import('./api'); await api.refreshCsrf(); fetch.mockClear()
  const controller = new AbortController(); controller.abort()
  await expect(api.write('/auth/logout', 'POST', undefined, controller.signal)).rejects.toMatchObject({ status: 0 })
  expect(fetch).not.toHaveBeenCalled()
})
