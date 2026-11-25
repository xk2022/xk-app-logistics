import { beforeEach, describe, expect, it, vi } from 'vitest'

const account = { id: '00000000-0000-0000-0000-000000000001', username: 'test.admin', displayName: '合成管理員', role: 'ADMIN', enabled: true, mustChangePassword: false, version: 2 }
function response(data: unknown, status = 200) {
  return new Response(JSON.stringify({ code: status < 400 ? '0000' : `E0${status}`, message: status < 400 ? '成功' : '請確認輸入欄位', data, timestamp: '2026-11-25T12:00:00+08:00' }), { status })
}
beforeEach(() => { vi.resetModules() })

describe('session API transport', () => {
  it('acquires CSRF before login and rotates it after authentication', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(response({ token: 'before', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(response(account)).mockResolvedValueOnce(response({ token: 'after', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(response(account))
    vi.stubGlobal('fetch', fetch)
    const api = await import('./api')
    expect(await api.login('test.admin', 'synthetic login value')).toEqual(account)
    await api.updateAccount(account.id, { displayName: '合成管理員', role: 'ADMIN', enabled: true, version: 2 })
    expect(fetch.mock.calls[1][0]).toBe('/api/v1/auth/login')
    expect(fetch.mock.calls[1][1]).toMatchObject({ method: 'POST', credentials: 'same-origin', headers: { 'X-CSRF-TOKEN': 'before' } })
    expect(fetch.mock.calls[3][1]).toMatchObject({ method: 'PATCH', headers: { 'X-CSRF-TOKEN': 'after' } })
  })

  it('refreshes a rejected CSRF token without replaying the write', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(response({ token: 'before', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(response(null, 403)).mockResolvedValueOnce(response({ token: 'fresh', headerName: 'X-CSRF-TOKEN' }))
    vi.stubGlobal('fetch', fetch)
    const api = await import('./api')
    await expect(api.createAccount({ username: 'test.driver', displayName: '合成司機', role: 'DRIVER', password: 'synthetic initial value' })).rejects.toMatchObject({ status: 403 })
    expect(fetch).toHaveBeenCalledTimes(3)
    expect(fetch.mock.calls.filter((call) => call[1]?.method === 'POST')).toHaveLength(1)
  })

  it('keeps the authenticated state if token rotation fails and obtains a fresh token before the next write', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(response({ token: 'before', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(response(account)).mockRejectedValueOnce(new TypeError('temporary failure'))
      .mockResolvedValueOnce(response({ token: 'fresh', headerName: 'X-CSRF-TOKEN' })).mockResolvedValueOnce(response(account))
    vi.stubGlobal('fetch', fetch)
    const api = await import('./api')
    expect(await api.login('test.admin', 'synthetic login value')).toEqual(account)
    await api.updateAccount(account.id, { displayName: '合成管理員', role: 'ADMIN', enabled: true, version: 2 })
    expect(fetch.mock.calls[4][1]).toMatchObject({ method: 'PATCH', headers: { 'X-CSRF-TOKEN': 'fresh' } })
  })

  it('treats logout as complete if the post-logout token refresh is unavailable', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(response({ token: 'before', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(response(null)).mockRejectedValueOnce(new TypeError('network internals'))
    vi.stubGlobal('fetch', fetch)
    const api = await import('./api')
    await expect(api.logout()).resolves.toBeUndefined()
  })

  it('returns field errors and rejects malformed/HTML success responses', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(response({ token: 'before', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(response({ displayName: '請輸入名稱' }, 400))
      .mockResolvedValueOnce(new Response('<html>unexpected</html>'))
    vi.stubGlobal('fetch', fetch)
    const api = await import('./api')
    await expect(api.createAccount({ username: 'test.driver', displayName: '', role: 'DRIVER', password: 'synthetic initial value' })).rejects.toMatchObject({ status: 400, fields: { displayName: '請輸入名稱' } })
    await expect(api.getMe()).rejects.toMatchObject({ message: '服務回應格式不正確，請稍後重試。' })
  })

  it('does not expose raw network diagnostics', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('internal host and secret')))
    const api = await import('./api')
    await expect(api.getMe()).rejects.toMatchObject({ message: '無法連線，請確認網路後重試。' })
  })
})
