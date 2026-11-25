import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const id = '00000000-0000-4000-8000-000000000101'
const typeId = '00000000-0000-4000-8000-000000000201'
const actorId = '00000000-0000-4000-8000-000000000301'
const option = { id, plateNo: 'SYNTHETIC AB-01', vehicleType: typeId, vehicleTypeCode: 'SYNTHETIC_TRUCK', vehicleTypeLabel: '合成車種', vehicleTypeEnabled: true, capacityTon: '9007199254740993.123456789012345', version: 1 }
const vehicle = { ...option, registrationNo: null, registrationDate: '1500-02-28', inspectionDueDate: '0001-01-01', administrativeStatus: 'AVAILABLE', createdAt: '2026-11-25T00:00:00Z', createdBy: actorId, updatedAt: '2026-11-25T08:00:00.123456+08:00', updatedBy: actorId }
const paged = (content: unknown[], page = 0, size = 20, totalElements = content.length) => ({ content, page, size, totalElements, totalPages: Math.ceil(totalElements / size) })
function response(data: unknown, status = 200) {
  return new Response(JSON.stringify({ code: status < 400 ? '0000' : `E0${status}`, message: '合成測試回應', data, timestamp: '2026-11-25T12:00:00+08:00' }), { status })
}
function csrf(token = 'synthetic-csrf') { return response({ token, headerName: 'X-CSRF-TOKEN' }) }
function queued(...responses: (Response | Error)[]) {
  const fetch = vi.fn()
  for (const item of responses) {
    if (item instanceof Error) fetch.mockRejectedValueOnce(item)
    else fetch.mockResolvedValueOnce(item)
  }
  vi.stubGlobal('fetch', fetch)
  return fetch
}
beforeEach(() => { vi.resetModules() })
afterEach(() => { vi.unstubAllGlobals() })

describe('vehicle API requests', () => {
  it('encodes literal searches, vehicle type, administrative status and bounded pagination without inventing sorting', async () => {
    const fetch = queued(response(paged([vehicle], 1, 5, 6)))
    const { getVehicles } = await import('./vehicleApi')
    const result = await getVehicles(1, { q: ' 合成 & + _ % ', vehicleType: typeId, administrativeStatus: 'MAINTENANCE', size: 5 })
    const url = new URL(fetch.mock.calls[0][0], 'https://localhost')
    expect(url.pathname).toBe('/api/v1/fms/vehicles')
    expect(Object.fromEntries(url.searchParams)).toEqual({ page: '1', size: '5', q: ' 合成 & + _ % ', vehicleType: typeId, administrativeStatus: 'MAINTENANCE' })
    expect(fetch.mock.calls[0][1]).toMatchObject({ method: 'GET', credentials: 'same-origin' })
    expect(result).toMatchObject({ page: 1, size: 5, totalElements: 6, totalPages: 2 })
    expect(result.content[0].capacityTon).toBe(option.capacityTon)
  })

  it('uses all six routes and the shared CSRF cache for minimum create and truly partial nullable clearing', async () => {
    const fetch = queued(response(paged([vehicle])), response(vehicle), csrf(), response(vehicle, 201),
      response({ ...vehicle, capacityTon: null, registrationNo: null, inspectionDueDate: null, version: 2 }), response(paged([option])), response([{ id: typeId, code: 'SYNTHETIC_TRUCK', label: '合成車種' }]))
    const api = await import('./vehicleApi')
    await api.getVehicles(0)
    expect((await api.getVehicle(id)).registrationDate).toBe('1500-02-28')
    await api.createVehicle({ plateNo: ' synthetic ab-01 ', vehicleType: typeId })
    await api.updateVehicle(id, { capacityTon: null, registrationNo: null, inspectionDueDate: null, version: 1 })
    await api.getVehicleOptions(0)
    expect(await api.getVehicleTypes()).toEqual([{ id: typeId, code: 'SYNTHETIC_TRUCK', label: '合成車種' }])
    expect(fetch.mock.calls.map((call) => call[0])).toEqual(['/api/v1/fms/vehicles?page=0&size=20', `/api/v1/fms/vehicles/${id}`, '/api/v1/auth/csrf', '/api/v1/fms/vehicles', `/api/v1/fms/vehicles/${id}`, '/api/v1/fms/vehicle-options?page=0&size=20', '/api/v1/fms/vehicle-types'])
    expect(JSON.parse(fetch.mock.calls[3][1].body)).toEqual({ plateNo: ' synthetic ab-01 ', vehicleType: typeId })
    expect(JSON.parse(fetch.mock.calls[4][1].body)).toEqual({ capacityTon: null, registrationNo: null, inspectionDueDate: null, version: 1 })
    expect(fetch.mock.calls[3][1]).toMatchObject({ method: 'POST', credentials: 'same-origin', headers: { 'X-CSRF-TOKEN': 'synthetic-csrf' } })
    expect(fetch.mock.calls[4][1]).toMatchObject({ method: 'PATCH', headers: { 'X-CSRF-TOKEN': 'synthetic-csrf' } })
    expect(fetch.mock.calls[3][1].headers).not.toHaveProperty('Idempotency-Key')
  })

  it('keeps AVAILABLE master-data options with archived types, zero or unknown capacity and no date/occupancy restriction', async () => {
    const fetch = queued(response(paged([{ ...option, vehicleTypeEnabled: false, capacityTon: '0' }, { ...option, capacityTon: null }])))
    const { getVehicleOptions } = await import('./vehicleApi')
    const result = await getVehicleOptions(0, { q: '合成', vehicleType: typeId, size: 50 })
    const url = new URL(fetch.mock.calls[0][0], 'https://localhost')
    expect(Object.fromEntries(url.searchParams)).toEqual({ page: '0', size: '50', q: '合成', vehicleType: typeId })
    expect(result.content.map((item) => [item.capacityTon, item.vehicleTypeEnabled])).toEqual([['0', false], [null, true]])
  })

  it('omits all-state filtering and accepts the controlled empty type catalog', async () => {
    const fetch = queued(response(paged([])), response([]))
    const api = await import('./vehicleApi')
    expect((await api.getVehicles(0, { administrativeStatus: 'all' })).content).toEqual([])
    expect(await api.getVehicleTypes()).toEqual([])
    expect(new URL(fetch.mock.calls[0][0], 'https://localhost').searchParams.has('administrativeStatus')).toBe(false)
  })

  it('preserves every decimal digit and date-only boundary when sending a complete write', async () => {
    const fetch = queued(csrf(), response(vehicle, 201))
    const { createVehicle } = await import('./vehicleApi')
    const data = { plateNo: 'SYNTHETIC AB-01', vehicleType: typeId, capacityTon: option.capacityTon, registrationNo: '合成註冊識別', registrationDate: '1500-02-28', inspectionDueDate: '9999-12-31', administrativeStatus: 'INACTIVE' as const }
    await createVehicle(data)
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual(data)
  })

  it('refreshes a rejected CSRF token without replaying or creating a second write', async () => {
    const fetch = queued(csrf('before'), response(null, 403), csrf('after'), response({ ...vehicle, version: 2 }))
    const { updateVehicle } = await import('./vehicleApi')
    const data = { administrativeStatus: 'MAINTENANCE' as const, version: 1 }
    await expect(updateVehicle(id, data)).rejects.toMatchObject({ status: 403 })
    expect(fetch).toHaveBeenCalledTimes(3)
    expect(fetch.mock.calls.filter((call) => call[1].method === 'PATCH')).toHaveLength(1)
    await updateVehicle(id, data)
    expect(fetch.mock.calls[3][1]).toMatchObject({ method: 'PATCH', headers: { 'X-CSRF-TOKEN': 'after' } })
  })

  it('retains field errors and optimistic conflicts for the UI without automatic reload or retry', async () => {
    const fetch = queued(csrf(), response({ capacityTon: '請使用十進位文字' }, 400), response(null, 409))
    const { updateVehicle } = await import('./vehicleApi')
    await expect(updateVehicle(id, { capacityTon: '1e3', version: 1 })).rejects.toMatchObject({ status: 400, fields: { capacityTon: '請使用十進位文字' } })
    await expect(updateVehicle(id, { plateNo: 'SYNTHETIC AB-02', version: 1 })).rejects.toMatchObject({ status: 409 })
    expect(fetch).toHaveBeenCalledTimes(3)
    expect(fetch.mock.calls.filter((call) => call[1].method === 'GET' && call[0] !== '/api/v1/auth/csrf')).toHaveLength(0)
  })

  it('does not automatically resend a vehicle create after a lost response', async () => {
    const fetch = queued(csrf(), new TypeError('Synthetic connection loss'))
    const { createVehicle } = await import('./vehicleApi')
    await expect(createVehicle({ plateNo: 'SYNTHETIC AB-01', vehicleType: typeId })).rejects.toMatchObject({ status: 0 })
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(fetch.mock.calls.filter((call) => call[1].method === 'POST')).toHaveLength(1)
  })

  it('forwards cancellation to a GET request and presents the shared connection error', async () => {
    const controller = new AbortController()
    const fetch = vi.fn().mockImplementation((_url, options: RequestInit) => new Promise((_resolve, reject) => {
      options.signal?.addEventListener('abort', () => reject(new DOMException('Synthetic abort', 'AbortError')), { once: true })
    }))
    vi.stubGlobal('fetch', fetch)
    const { getVehicleTypes } = await import('./vehicleApi')
    const pending = getVehicleTypes(controller.signal)
    controller.abort()
    await expect(pending).rejects.toMatchObject({ status: 0, message: '連線逾時或中斷，請稍後重試。' })
    expect(fetch.mock.calls[0][1].signal.aborted).toBe(true)
  })

  it('preserves authentication failure status and safely rejects a malformed envelope', async () => {
    queued(response(null, 401), new Response(JSON.stringify({ code: 'OK', message: '不適用的訂單格式', data: vehicle, traceId: 'synthetic' })))
    const { getVehicle } = await import('./vehicleApi')
    await expect(getVehicle(id)).rejects.toMatchObject({ status: 401 })
    await expect(getVehicle(id)).rejects.toThrow('服務回應格式不正確')
  })
})

describe('vehicle response validation', () => {
  it.each([
    ['decimal number', { capacityTon: 1.2 }], ['exponent', { capacityTon: '1e3' }], ['negative capacity', { capacityTon: '-1' }],
    ['leading zero', { capacityTon: '01' }], ['decimal trailing newline', { capacityTon: '1.5\n' }], ['too many decimal characters', { capacityTon: '1'.repeat(33) }],
    ['impossible date', { inspectionDueDate: '1500-02-29' }], ['year zero', { registrationDate: '0000-01-01' }], ['date trailing newline', { registrationDate: '2026-12-01\n' }],
    ['manual occupancy', { administrativeStatus: 'IN_USE' }], ['unsafe version', { version: Number.MAX_SAFE_INTEGER + 1 }], ['negative version', { version: -1 }],
    ['wrong vehicle UUID', { id: 'vehicle-id' }], ['wrong type UUID', { vehicleType: 'type-id' }], ['missing type enabled metadata', { vehicleTypeEnabled: undefined }],
    ['blank label', { vehicleTypeLabel: ' ' }], ['NUL in plate', { plateNo: 'AB\u0000CD' }], ['control in registration', { registrationNo: 'REG\n01' }],
    ['unpaired surrogate', { plateNo: '\uD800' }], ['too many plate codepoints', { plateNo: '🚚'.repeat(41) }],
    ['missing audit', { createdBy: undefined }], ['wrong audit UUID', { updatedBy: 'actor' }], ['invalid audit timestamp', { createdAt: '2026-02-30T00:00:00Z' }],
  ])('rejects %s before exposing a vehicle to the UI', async (_name, override) => {
    queued(response({ ...vehicle, ...override }))
    const { getVehicle } = await import('./vehicleApi')
    await expect(getVehicle(id)).rejects.toThrow('車輛資料回應格式不正確')
  })

  it('counts codepoints, retaining supplementary characters and all valid nullable date boundaries', async () => {
    queued(response({ ...vehicle, plateNo: '🚚'.repeat(40), registrationNo: '🚚'.repeat(100), vehicleTypeLabel: '🚚'.repeat(80), registrationDate: '0001-01-01', inspectionDueDate: '9999-12-31', capacityTon: null, version: Number.MAX_SAFE_INTEGER }))
    const { getVehicle } = await import('./vehicleApi')
    const result = await getVehicle(id)
    expect([...result.plateNo]).toHaveLength(40)
    expect(result).toMatchObject({ registrationDate: '0001-01-01', inspectionDueDate: '9999-12-31', capacityTon: null, version: Number.MAX_SAFE_INTEGER })
  })

  it.each([
    ['negative page', { page: -1 }], ['out of bound page', { page: 100001 }], ['empty size', { size: 0 }], ['oversized page', { size: 101 }],
    ['unsafe total', { totalElements: Number.MAX_SAFE_INTEGER + 1 }], ['fractional total pages', { totalPages: 1.5 }], ['missing content', { content: null }],
  ])('rejects %s metadata', async (_name, override) => {
    queued(response({ ...paged([]), ...override }))
    const { getVehicles } = await import('./vehicleApi')
    await expect(getVehicles(0)).rejects.toThrow('車輛資料回應格式不正確')
  })

  it('rejects a malformed option or type instead of mislabelling an archived classification', async () => {
    queued(response(paged([{ ...option, vehicleTypeEnabled: null }])), response([{ id: typeId, code: 'SYNTHETIC_TRUCK\n', label: '合成車種' }]))
    const api = await import('./vehicleApi')
    await expect(api.getVehicleOptions(0)).rejects.toThrow('車輛資料回應格式不正確')
    await expect(api.getVehicleTypes()).rejects.toThrow('車輛資料回應格式不正確')
  })
})

describe('vehicle query boundaries', () => {
  it('allows exact page/size limits and measures search after NFC normalization', async () => {
    const fetch = queued(response(paged([], 100000, 100)))
    const { getVehicles } = await import('./vehicleApi')
    await getVehicles(100000, { q: `\u001c\uFEFF ${'e\u0301'.repeat(100)} \uFEFF\u001f`, size: 100 })
    expect(fetch).toHaveBeenCalledOnce()
  })

  it('rejects invalid and unknown list/options filters before sending HTTP', async () => {
    const fetch = queued()
    const api = await import('./vehicleApi')
    await expect(api.getVehicles(-1)).rejects.toMatchObject({ status: 400 })
    await expect(api.getVehicles(100001)).rejects.toMatchObject({ status: 400 })
    await expect(api.getVehicles(0, { size: 0 })).rejects.toMatchObject({ status: 400 })
    await expect(api.getVehicles(0, { q: '🚚'.repeat(101) })).rejects.toMatchObject({ status: 400 })
    await expect(api.getVehicles(0, { q: 'AB\u0000CD' })).rejects.toMatchObject({ status: 400 })
    await expect(api.getVehicles(0, { q: '\u0085AB\u0085' })).rejects.toMatchObject({ status: 400 })
    await expect(api.getVehicles(0, { vehicleType: 'not-a-uuid' })).rejects.toMatchObject({ status: 400 })
    await expect(api.getVehicles(0, { sort: 'plateNo,asc' } as never)).rejects.toMatchObject({ status: 400 })
    await expect(api.getVehicleOptions(0, { administrativeStatus: 'INACTIVE' } as never)).rejects.toMatchObject({ status: 400 })
    await expect(api.getVehicle('invalid')).rejects.toMatchObject({ status: 400 })
    expect(fetch).not.toHaveBeenCalled()
  })

  it('validates Gregorian leap years without browser timezone conversion', async () => {
    const { validVehicleDate } = await import('./vehicleApi')
    expect(validVehicleDate('2000-02-29')).toBe(true)
    expect(validVehicleDate('1500-02-29')).toBe(false)
    expect(validVehicleDate('1900-02-29')).toBe(false)
    expect(validVehicleDate('2028-02-29')).toBe(true)
    expect(validVehicleDate('2026-04-31')).toBe(false)
    expect(validVehicleDate('10000-01-01')).toBe(false)
  })
})
