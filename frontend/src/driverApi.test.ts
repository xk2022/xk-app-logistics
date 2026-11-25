import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const id = '00000000-0000-4000-8000-000000000101'
const licenseId = '00000000-0000-4000-8000-000000000201'
const accountId = '00000000-0000-4000-8000-000000000301'
const self = { id, driverCode: 'SYNTHETIC DRIVER-01', name: '合成司機', phone: null, email: null, employmentStatus: 'ACTIVE', dutyStatus: 'OFF_DUTY', licenseType: null, licenseTypeCode: null, licenseTypeLabel: null, licenseTypeEnabled: false, licenseDueDate: null }
const summary = { ...self, accountId: null, accountUsername: null, accountDisplayName: null, accountBindingValid: false, version: 1 }
const driver = { ...summary, licenseReference: null, createdAt: '2026-11-25T00:00:00Z', createdBy: accountId, updatedAt: '2026-11-25T08:00:00.123456789+08:00', updatedBy: accountId }
const option = { id, driverCode: self.driverCode, name: self.name, licenseType: null, licenseTypeCode: null, licenseTypeLabel: null, licenseTypeEnabled: false, licenseDueDate: null, version: 1 }
const type = { id: licenseId, code: 'SYNTHETIC_LICENSE', label: '合成駕照類別' }
const account = { id: accountId, username: 'synthetic.driver', displayName: '合成司機帳號', bound: false }
const paged = (content: unknown[], page = 0, size = 20, totalElements = content.length) => ({ content, page, size, totalElements, totalPages: Math.ceil(totalElements / size) })
function response(data: unknown, status = 200) { return new Response(JSON.stringify({ code: status < 400 ? '0000' : `E0${status}`, message: '合成測試回應', data, timestamp: '2026-11-25T12:00:00+08:00' }), { status }) }
function csrf(token = 'synthetic-csrf') { return response({ token, headerName: 'X-CSRF-TOKEN' }) }
function queued(...values: (Response | Error)[]) {
  const fetch = vi.fn()
  for (const value of values) {
    if (value instanceof Error) fetch.mockRejectedValueOnce(value)
    else fetch.mockResolvedValueOnce(value)
  }
  vi.stubGlobal('fetch', fetch)
  return fetch
}
beforeEach(() => { vi.resetModules() })
afterEach(() => { vi.unstubAllGlobals() })

describe('driver API transport', () => {
  it('uses all eight narrow routes with shared HTTPS session/CSRF and no external identity on self', async () => {
    const fetch = queued(response(paged([summary])), response(driver), csrf(), response(driver, 201), response({ ...driver, version: 2 }), response(paged([option])), response([type]), response(paged([account], 0, 5)), response(self))
    const api = await import('./driverApi')
    await api.getDrivers(0)
    await api.getDriver(id)
    await api.createDriver({ driverCode: ' synthetic driver-01 ', name: '合成司機' })
    await api.updateDriver(id, { accountId: null, licenseType: null, licenseReference: null, licenseDueDate: null, phone: null, email: null, version: 1 })
    await api.getDriverOptions(0)
    expect(await api.getDriverLicenseTypes()).toEqual([type])
    expect((await api.getDriverAccountOptions(0, { size: 5 })).content).toEqual([account])
    expect(await api.getDriverMe()).toEqual(self)
    expect(fetch.mock.calls.map((call) => call[0])).toEqual(['/api/v1/fms/drivers?page=0&size=20', `/api/v1/fms/drivers/${id}`, '/api/v1/auth/csrf', '/api/v1/fms/drivers', `/api/v1/fms/drivers/${id}`, '/api/v1/fms/driver-options?page=0&size=20', '/api/v1/fms/driver-license-types', '/api/v1/fms/driver-account-options?page=0&size=5', '/api/v1/fms/drivers/me'])
    expect(JSON.parse(fetch.mock.calls[3][1].body)).toEqual({ driverCode: ' synthetic driver-01 ', name: '合成司機' })
    expect(JSON.parse(fetch.mock.calls[4][1].body)).toEqual({ accountId: null, licenseType: null, licenseReference: null, licenseDueDate: null, phone: null, email: null, version: 1 })
    expect(fetch.mock.calls[3][1]).toMatchObject({ method: 'POST', credentials: 'same-origin', headers: { 'X-CSRF-TOKEN': 'synthetic-csrf' } })
    expect(fetch.mock.calls[4][1]).toMatchObject({ method: 'PATCH', credentials: 'same-origin', headers: { 'X-CSRF-TOKEN': 'synthetic-csrf' } })
    expect(fetch.mock.calls[3][1].headers).not.toHaveProperty('Idempotency-Key')
    expect(fetch.mock.calls[8][1]).toMatchObject({ method: 'GET', credentials: 'same-origin' })
  })

  it('encodes literal list filters without sorting or searching private reference/account fields', async () => {
    const fetch = queued(response(paged([summary], 1, 5, 6)))
    const { getDrivers } = await import('./driverApi')
    const result = await getDrivers(1, { q: ' %_ + & e\u0301 ', licenseType: licenseId, employmentStatus: 'LEFT', dutyStatus: 'ON_DUTY', size: 5 })
    const url = new URL(fetch.mock.calls[0][0], 'https://localhost')
    expect(Object.fromEntries(url.searchParams)).toEqual({ page: '1', size: '5', q: ' %_ + & e\u0301 ', licenseType: licenseId, employmentStatus: 'LEFT', dutyStatus: 'ON_DUTY' })
    expect(result).toMatchObject({ totalElements: 6, totalPages: 2 })
  })

  it('retains bound candidates and their original page totals without exposing the other driver', async () => {
    const fetch = queued(response(paged([{ ...account, bound: true }, { ...account, id, username: 'temporary.driver', bound: false }], 1, 5, 9)))
    const { getDriverAccountOptions } = await import('./driverApi')
    const result = await getDriverAccountOptions(1, { q: ' 合成 %_ ', size: 5 })
    expect(result.content.map((item) => item.bound)).toEqual([true, false])
    expect(result).toMatchObject({ page: 1, totalElements: 9, totalPages: 2 })
    expect(Object.fromEntries(new URL(fetch.mock.calls[0][0], 'https://localhost').searchParams)).toEqual({ page: '1', size: '5', q: ' 合成 %_ ' })
  })

  it('keeps administrative options with no account, archived classification and an old date', async () => {
    const fetch = queued(response(paged([{ ...option, licenseType: licenseId, licenseTypeCode: type.code, licenseTypeLabel: type.label, licenseTypeEnabled: false, licenseDueDate: '1500-02-28' }])))
    const { getDriverOptions } = await import('./driverApi')
    const result = await getDriverOptions(0, { q: '合成', licenseType: licenseId })
    expect(result.content[0]).toMatchObject({ licenseTypeEnabled: false, licenseDueDate: '1500-02-28' })
    expect(new URL(fetch.mock.calls[0][0], 'https://localhost').searchParams.has('employmentStatus')).toBe(false)
    expect(new URL(fetch.mock.calls[0][0], 'https://localhost').searchParams.has('dutyStatus')).toBe(false)
  })

  it('accepts bounded descriptive contacts and does not normalize, impose an email regex, or transform natural dates', async () => {
    const descriptive = { ...driver, phone: '+44 (0)20 ext. 合成', email: '非 ASCII 郵件聯絡說明', licenseDueDate: '1500-03-01' }
    const fetch = queued(csrf(), response(descriptive, 201))
    const { createDriver } = await import('./driverApi')
    const body = { driverCode: '\u001c\uFEFF synthetic driver-01 \u001f', name: '  e\u0301 合成  ', phone: descriptive.phone, email: descriptive.email, licenseDueDate: '1500-03-01' }
    expect((await createDriver(body)).email).toBe(descriptive.email)
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual(body)
  })

  it('refreshes CSRF after rejection but waits for an explicit second write', async () => {
    const fetch = queued(csrf('before'), response(null, 403), csrf('after'), response({ ...driver, version: 2 }))
    const { updateDriver } = await import('./driverApi')
    await expect(updateDriver(id, { dutyStatus: 'ON_DUTY', version: 1 })).rejects.toMatchObject({ status: 403 })
    expect(fetch).toHaveBeenCalledTimes(3)
    expect(fetch.mock.calls.filter((call) => call[1].method === 'PATCH')).toHaveLength(1)
    await updateDriver(id, { dutyStatus: 'ON_DUTY', version: 1 })
    expect(fetch.mock.calls[3][1]).toMatchObject({ method: 'PATCH', headers: { 'X-CSRF-TOKEN': 'after' } })
  })

  it('passes field errors for code, binding, employment transition and version conflicts without an automatic GET or resend', async () => {
    const fetch = queued(csrf(), response({ driverCode: '編號重複' }, 409), response({ accountId: '帳號已綁定' }, 409), response({ employmentStatus: '復職政策尚未核准' }, 409), response({ version: '版本已更新' }, 409), response({ accountId: '帳號目前不可綁定' }, 400))
    const api = await import('./driverApi')
    await expect(api.createDriver({ driverCode: 'SYNTHETIC', name: '合成' })).rejects.toMatchObject({ status: 409, fields: { driverCode: '編號重複' } })
    await expect(api.updateDriver(id, { accountId, version: 1 })).rejects.toMatchObject({ status: 409, fields: { accountId: '帳號已綁定' } })
    await expect(api.updateDriver(id, { employmentStatus: 'ACTIVE', version: 1 })).rejects.toMatchObject({ status: 409, fields: { employmentStatus: '復職政策尚未核准' } })
    await expect(api.updateDriver(id, { name: '合成異動', version: 1 })).rejects.toMatchObject({ status: 409, fields: { version: '版本已更新' } })
    await expect(api.updateDriver(id, { accountId, version: 1 })).rejects.toMatchObject({ status: 400, fields: { accountId: '帳號目前不可綁定' } })
    expect(fetch).toHaveBeenCalledTimes(6)
    expect(fetch.mock.calls.filter((call) => call[1].method === 'GET')).toHaveLength(1)
  })

  it('does not resend a lost create response or add a TOM idempotency identity', async () => {
    const fetch = queued(csrf(), new TypeError('Synthetic lost response'))
    const { createDriver } = await import('./driverApi')
    await expect(createDriver({ driverCode: 'SYNTHETIC', name: '合成' })).rejects.toMatchObject({ status: 0 })
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it.each([
    ['unreadable JSON', () => new Response('{', { status: 201 })],
    ['wrong envelope', () => new Response(JSON.stringify({ code: 'OK', data: driver, traceId: 'synthetic' }), { status: 201 })],
    ['missing driver fields', () => response({ id }, 201)],
  ])('keeps a successful HTTP with %s classified as an unconfirmed write', async (_name, ack) => {
    const fetch = queued(csrf(), ack())
    const { createDriver } = await import('./driverApi')
    await expect(createDriver({ driverCode: 'SYNTHETIC', name: '合成' })).rejects.toMatchObject({ status: 0 })
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('preserves 401, 403 and missing-current-binding 404 for the self-page lifecycle', async () => {
    const fetch = queued(response(null, 404), response(null, 401), response(null, 403))
    const { getDriverMe } = await import('./driverApi')
    await expect(getDriverMe()).rejects.toMatchObject({ status: 404 })
    await expect(getDriverMe()).rejects.toMatchObject({ status: 401 })
    await expect(getDriverMe()).rejects.toMatchObject({ status: 403 })
    expect(fetch.mock.calls.every((call) => call[0] === '/api/v1/fms/drivers/me')).toBe(true)
  })

  it('forwards self-page cancellation to the shared transport', async () => {
    const controller = new AbortController()
    const fetch = vi.fn().mockImplementation((_url, options: RequestInit) => new Promise((_resolve, reject) => {
      options.signal?.addEventListener('abort', () => reject(new DOMException('Synthetic abort', 'AbortError')), { once: true })
    }))
    vi.stubGlobal('fetch', fetch)
    const { getDriverMe } = await import('./driverApi')
    const pending = getDriverMe(controller.signal)
    controller.abort()
    await expect(pending).rejects.toMatchObject({ status: 0 })
    expect(fetch.mock.calls[0][1].signal.aborted).toBe(true)
  })
})

describe('driver data minimization and response validation', () => {
  it.each(['licenseReference', 'accountId', 'accountUsername', 'version', 'createdAt', 'authVersion', 'passwordHash'])('rejects an extra private/management self field %s', async (field) => {
    queued(response({ ...self, [field]: 'synthetic-private' }))
    const { getDriverMe } = await import('./driverApi')
    await expect(getDriverMe()).rejects.toThrow('司機資料回應格式不正確')
  })

  it('rejects full manager data on self and reference/contact/account data on minimized lists', async () => {
    queued(response(driver), response(paged([{ ...summary, licenseReference: 'synthetic-private' }])), response(paged([{ ...option, phone: 'synthetic-private' }])), response(paged([{ ...account, driverId: id }])))
    const api = await import('./driverApi')
    await expect(api.getDriverMe()).rejects.toThrow('司機資料回應格式不正確')
    await expect(api.getDrivers(0)).rejects.toThrow('司機資料回應格式不正確')
    await expect(api.getDriverOptions(0)).rejects.toThrow('司機資料回應格式不正確')
    await expect(api.getDriverAccountOptions(0)).rejects.toThrow('司機資料回應格式不正確')
  })

  it('keeps LEFT plus ON_DUTY as recorded values and permits a retained invalid account binding', async () => {
    const binding = { accountId, accountUsername: account.username, accountDisplayName: account.displayName, accountBindingValid: false }
    queued(response({ ...self, employmentStatus: 'LEFT', dutyStatus: 'ON_DUTY' }), response({ ...driver, ...binding, licenseType: licenseId, licenseTypeCode: type.code, licenseTypeLabel: type.label, licenseTypeEnabled: false, licenseDueDate: '0001-01-01' }))
    const api = await import('./driverApi')
    expect(await api.getDriverMe()).toMatchObject({ employmentStatus: 'LEFT', dutyStatus: 'ON_DUTY' })
    expect(await api.getDriver(id)).toMatchObject({ ...binding, licenseTypeEnabled: false, licenseDueDate: '0001-01-01' })
  })

  it.each([
    ['license UUID absent but code present', { licenseTypeCode: type.code }], ['license UUID absent but enabled', { licenseTypeEnabled: true }],
    ['license UUID present but labels absent', { licenseType: licenseId }], ['account absent but summary present', { accountUsername: account.username }],
    ['account absent but binding marked valid', { accountBindingValid: true }], ['account present but summaries absent', { accountId }],
    ['fractional version', { version: 1.5 }], ['unsafe version', { version: Number.MAX_SAFE_INTEGER + 1 }], ['invalid date', { licenseDueDate: '1500-02-29' }],
    ['date year zero', { licenseDueDate: '0000-01-01' }], ['date with trailing newline', { licenseDueDate: '2026-12-01\n' }], ['unknown employment', { employmentStatus: 'AVAILABLE' }],
    ['unknown duty', { dutyStatus: 'IN_USE' }], ['overlong name', { name: '🚚'.repeat(201) }], ['overlong phone', { phone: '🚚'.repeat(51) }],
    ['overlong email', { email: '🚚'.repeat(255) }], ['overlong license description', { licenseReference: '🚚'.repeat(101) }],
    ['NUL name', { name: '合成\u0000司機' }], ['unpaired surrogate', { name: '\uD800' }], ['invalid actor UUID', { updatedBy: 'actor' }],
    ['invalid audit calendar date', { createdAt: '2026-02-30T00:00:00Z' }], ['missing required nullable field', { email: undefined }],
  ])('rejects %s before a full driver reaches the UI', async (_name, override) => {
    queued(response({ ...driver, ...override }))
    const { getDriver } = await import('./driverApi')
    await expect(getDriver(id)).rejects.toThrow('司機資料回應格式不正確')
  })

  it('accepts the exact codepoint and safe-version boundaries without coercing contact values', async () => {
    queued(response({ ...driver, driverCode: '🚚'.repeat(40), name: '🚚'.repeat(200), phone: '🚚'.repeat(50), email: '🚚'.repeat(254), licenseReference: '🚚'.repeat(100), licenseDueDate: '9999-12-31', version: Number.MAX_SAFE_INTEGER }))
    const { getDriver } = await import('./driverApi')
    expect(await getDriver(id)).toMatchObject({ email: '🚚'.repeat(254), licenseDueDate: '9999-12-31', version: Number.MAX_SAFE_INTEGER })
  })

  it('accepts the normal empty license catalog and rejects extra account authorization metadata', async () => {
    queued(response([]), response(paged([{ ...account, mustChangePassword: true }])), response([{ ...type, code: 'SYNTHETIC\n' }]))
    const api = await import('./driverApi')
    expect(await api.getDriverLicenseTypes()).toEqual([])
    await expect(api.getDriverAccountOptions(0)).rejects.toThrow('司機資料回應格式不正確')
    await expect(api.getDriverLicenseTypes()).rejects.toThrow('司機資料回應格式不正確')
  })

  it.each([{ page: -1 }, { size: 101 }, { totalElements: Number.MAX_SAFE_INTEGER + 1 }, { content: null }, { totalPages: 1.5 }])('rejects invalid page metadata %j', async (override) => {
    queued(response({ ...paged([]), ...override }))
    const { getDrivers } = await import('./driverApi')
    await expect(getDrivers(0)).rejects.toThrow('司機資料回應格式不正確')
  })
})

describe('driver query boundaries', () => {
  it('supports canonical search bounds and omits all-state pseudo filters', async () => {
    const fetch = queued(response(paged([], 100000, 100)))
    const { getDrivers } = await import('./driverApi')
    await getDrivers(100000, { q: `\u001c\uFEFF ${'e\u0301'.repeat(100)} \u001f`, size: 100, employmentStatus: 'all', dutyStatus: 'all' })
    const url = new URL(fetch.mock.calls[0][0], 'https://localhost')
    expect(url.searchParams.has('employmentStatus')).toBe(false)
    expect(url.searchParams.has('dutyStatus')).toBe(false)
  })

  it('rejects out-of-contract parameters before any HTTP request', async () => {
    const fetch = queued()
    const api = await import('./driverApi')
    await expect(api.getDrivers(-1)).rejects.toMatchObject({ status: 400 })
    await expect(api.getDrivers(100001)).rejects.toMatchObject({ status: 400 })
    await expect(api.getDrivers(0, { size: 0 })).rejects.toMatchObject({ status: 400 })
    await expect(api.getDrivers(0, { q: '🚚'.repeat(101) })).rejects.toMatchObject({ status: 400 })
    await expect(api.getDrivers(0, { q: '\u0085合成' })).rejects.toMatchObject({ status: 400 })
    await expect(api.getDrivers(0, { licenseType: `${licenseId}\n` })).rejects.toMatchObject({ status: 400 })
    await expect(api.getDrivers(0, { employmentStatus: 'AVAILABLE' } as never)).rejects.toMatchObject({ status: 400 })
    await expect(api.getDrivers(0, { sort: 'name,asc' } as never)).rejects.toMatchObject({ status: 400 })
    await expect(api.getDriverOptions(0, { employmentStatus: 'INACTIVE' } as never)).rejects.toMatchObject({ status: 400 })
    await expect(api.getDriverOptions(0, { dutyStatus: 'OFF_DUTY' } as never)).rejects.toMatchObject({ status: 400 })
    await expect(api.getDriverAccountOptions(0, { driverId: id } as never)).rejects.toMatchObject({ status: 400 })
    await expect(api.getDriver('me')).rejects.toMatchObject({ status: 400 })
    expect(fetch).not.toHaveBeenCalled()
  })
})
