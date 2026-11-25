import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Order, OrderDraft, OrderItem, TransportTask } from './tomApi'

const orderId = '00000000-0000-0000-0000-000000000001'
const customerId = '00000000-0000-0000-0000-000000000002'
const itemId = '00000000-0000-0000-0000-000000000003'
const taskId = '00000000-0000-0000-0000-000000000004'
const actorId = '00000000-0000-0000-0000-000000000005'
const traceId = 'synthetic-trace'
const key = 'synthetic-save-1'
const draft: OrderDraft = { type: 'IMPORT', customerId }
const fullItem: OrderItem = { id: itemId, clientRef: 'item-1', sequence: null, itemKind: null, containerType: null,
  containerNo: null, packageQty: null, grossWeight: null, cbm: null, lastPickupDate: null, deliveryCutoffAt: null }
const fullTask: TransportTask = { id: taskId, taskNo: 'TOM-T-SYNTHETIC', orderId, itemId, clientRef: 'task-1', itemRef: 'item-1',
  taskKind: null, pickupName: null, pickupAddress: null, deliveryName: null, deliveryAddress: null, siteContact: null,
  plannedStartAt: null, plannedEndAt: null, predecessorRefs: [], requiredTask: null, predecessorTaskIds: [] }
function order(): Order {
  return { id: orderId, orderNo: 'TOM-SYNTHETIC', type: 'IMPORT', customerId, customerName: '合成客戶', lifecycleStatus: 'OPEN',
    acceptanceStatus: 'DRAFT', version: 0, createdAt: '2026-11-25T12:00:00+08:00', updatedAt: '2026-11-25T12:00:00+08:00',
    customerReference: null, remark: null, shipping: null, items: [], tasks: [], createdBy: actorId, updatedBy: actorId }
}
function summary() {
  const { id, orderNo, type, customerId, customerName, lifecycleStatus, acceptanceStatus, version, createdAt, updatedAt } = order()
  return { id, orderNo, type, customerId, customerName, lifecycleStatus, acceptanceStatus, version, createdAt, updatedAt }
}
function success(data: unknown, status = 200, replayed?: boolean): Response {
  return new Response(JSON.stringify({ code: 'OK', message: '成功', data, traceId }), { status,
    headers: replayed === undefined ? {} : { 'Idempotency-Replayed': String(replayed), Location: `/api/v1/orders/${orderId}` } })
}
function error(status: number, code: string, data: unknown = null, fieldErrors: unknown[] = []): Response {
  return new Response(JSON.stringify({ code, message: '請確認合成測試資料', data, traceId, fieldErrors }), { status })
}
function csrf(token = 'synthetic-csrf'): Response {
  return new Response(JSON.stringify({ code: '0000', message: '成功', timestamp: '2026-11-25T12:00:00+08:00',
    data: { token, headerName: 'X-CSRF-TOKEN' } }))
}
function queued(...responses: (Response | Error)[]) {
  const fetch = vi.fn<typeof globalThis.fetch>()
  for (const response of responses) {
    if (response instanceof Error) fetch.mockRejectedValueOnce(response)
    else fetch.mockResolvedValueOnce(response)
  }
  vi.stubGlobal('fetch', fetch)
  return fetch
}

beforeEach(() => { vi.resetModules() })
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

describe('original TOM API contract', () => {
  it('uses all five original routes, legacy envelopes, explicit idempotency and replay metadata', async () => {
    const check = { orderId, version: 0, checkedRules: ['ORD-P01', 'ORD-P02', 'ORD-P03'], confirmedRulesOutcome: 'COMPLETE',
      missingFields: [], pendingPolicyIds: ['Q06'], operationalAcceptance: 'NOT_GRANTED' }
    const fetch = queued(success({ content: [summary()], page: 0, size: 20, totalElements: 1, totalPages: 1 }), success(order()),
      success(check), csrf(), success(order(), 201, false), success({ ...order(), version: 1 }, 200, true))
    const api = await import('./tomApi')
    expect((await api.getOrders({ type: 'IMPORT', customerId, sort: 'orderNo,asc' })).data.content).toEqual([summary()])
    expect(await api.getOrder(orderId)).toEqual({ code: 'OK', message: '成功', data: order(), traceId })
    expect((await api.checkOrder(orderId)).data).toEqual(check)
    expect(await api.createOrder(draft, key)).toMatchObject({ code: 'OK', traceId, replayed: false, location: `/api/v1/orders/${orderId}` })
    expect(await api.replaceOrder(orderId, { expectedVersion: 0, draft }, key)).toMatchObject({ replayed: true, data: { version: 1 } })
    expect(fetch.mock.calls.map(([url]) => url)).toEqual([
      `/api/v1/orders?page=0&size=20&sort=orderNo%2Casc&type=IMPORT&customerId=${customerId}`,
      `/api/v1/orders/${orderId}`, `/api/v1/orders/${orderId}/acceptance-check`, '/api/v1/auth/csrf',
      '/api/v1/orders', `/api/v1/orders/${orderId}`,
    ])
    expect(fetch.mock.calls[4][1]).toMatchObject({ method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': 'synthetic-csrf', 'Idempotency-Key': key }, body: JSON.stringify(draft) })
    expect(fetch.mock.calls[5][1]).toMatchObject({ method: 'PUT', body: JSON.stringify({ expectedVersion: 0, draft }) })
  })

  it('shares the existing session CSRF cache and refreshes 403 without replaying a write', async () => {
    const fetch = queued(csrf('before'), error(403, 'CSRF_INVALID'), csrf('fresh'), success(order(), 201, true))
    const session = await import('./api')
    await session.getCsrfHeaders()
    const api = await import('./tomApi')
    await expect(api.createOrder(draft, key)).rejects.toMatchObject({ status: 403, code: 'CSRF_INVALID', uncertain: false, traceId })
    expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
    expect(fetch).toHaveBeenCalledTimes(3)
    await api.createOrder(draft, key)
    expect(fetch.mock.calls[1][1]).toMatchObject({ headers: { 'X-CSRF-TOKEN': 'before', 'Idempotency-Key': key } })
    expect(fetch.mock.calls[3][1]).toMatchObject({ headers: { 'X-CSRF-TOKEN': 'fresh', 'Idempotency-Key': key }, body: JSON.stringify(draft) })
  })

  it('freezes the serialized body before awaiting CSRF and preserves raw text, null, omission and array order', async () => {
    let release!: (response: Response) => void
    const fetch = vi.fn<typeof globalThis.fetch>().mockImplementationOnce(() => new Promise((resolve) => { release = resolve }))
      .mockResolvedValueOnce(success(order(), 201, false))
    vi.stubGlobal('fetch', fetch)
    const api = await import('./tomApi')
    const raw: OrderDraft = { type: 'LOCAL', customerId, customerReference: null, remark: ' e\u0301\u0000 ', shipping: {},
      items: [{ clientRef: 'second', grossWeight: '0.000', lastPickupDate: '1500-01-02' }, { clientRef: 'first', containerNo: ' raw ' }],
      tasks: [{ clientRef: 'second', itemRef: 'second', siteContact: {}, plannedStartAt: '1500-01-02t00:00:00.123456+23:59' }] }
    const original = JSON.stringify(raw)
    const pending = api.createOrder(raw, key)
    raw.remark = 'changed locally'; raw.items!.reverse()
    release(csrf())
    await pending
    expect(fetch.mock.calls[1][1]?.body).toBe(original)
    expect(JSON.parse(fetch.mock.calls[1][1]?.body as string)).not.toHaveProperty('omitted')
  })

  it('returns field pointers, trace and actualVersion from a known conflict without leaking generic error text', async () => {
    const fields = [{ path: '/draft/items/0/packageQty', fieldId: 'packageQty', ruleId: 'positive', message: '須為正整數' }]
    queued(csrf(), error(409, 'VERSION_CONFLICT', { actualVersion: 8 }, fields), error(401, 'UNAUTHENTICATED'), error(500, 'INTERNAL_ERROR'))
    const api = await import('./tomApi')
    await expect(api.replaceOrder(orderId, 0, draft, key)).rejects.toMatchObject({ status: 409, code: 'VERSION_CONFLICT', actualVersion: 8,
      fieldErrors: fields, fields: { '/draft/items/0/packageQty': '須為正整數' }, traceId, uncertain: false })
    await expect(api.getOrder(orderId)).rejects.toMatchObject({ status: 401, code: 'UNAUTHENTICATED', message: '登入狀態已失效，請重新登入。', uncertain: false })
    await expect(api.getOrder(orderId)).rejects.toMatchObject({ status: 500, code: 'INTERNAL_ERROR', message: '服務暫時無法使用，請稍後重試。', uncertain: false })
  })

  it('keeps valid write 500 responses uncertain so callers retain the same key and frozen body', async () => {
    const fetch = queued(csrf(), error(500, 'INTERNAL_ERROR'), success(order(), 201, true))
    const api = await import('./tomApi')
    await expect(api.createOrder(draft, key)).rejects.toMatchObject({ status: 500, code: 'INTERNAL_ERROR', traceId, uncertain: true })
    expect(fetch).toHaveBeenCalledTimes(2)
    expect((await api.createOrder(draft, key)).replayed).toBe(true)
    expect(fetch.mock.calls[1][1]?.body).toBe(fetch.mock.calls[2][1]?.body)
    expect(fetch.mock.calls[2][1]).toMatchObject({ headers: { 'Idempotency-Key': key } })
  })

  it('rejects UPMS envelopes on TOM routes instead of accidentally accepting another contract', async () => {
    queued(csrf())
    const api = await import('./tomApi')
    await expect(api.getOrder(orderId)).rejects.toMatchObject({ code: 'INVALID_RESPONSE', uncertain: false })
  })
})

describe('lossless response decoding', () => {
  it('preserves unbounded page/count integers and sends an arbitrary decimal page without rounding', async () => {
    const huge = '9007199254740993123456789'
    const raw = `{"code":"OK","message":"成功","data":{"content":[],"page":${huge},"size":20,"totalElements":9007199254740993,"totalPages":450359962737050},"traceId":"${traceId}"}`
    const fetch = queued(new Response(raw))
    const api = await import('./tomApi')
    expect((await api.getOrders({ page: huge })).data).toEqual({ content: [], page: huge, size: 20, totalElements: '9007199254740993', totalPages: 450359962737050 })
    expect(fetch.mock.calls[0][0]).toContain(`page=${huge}&`)
  })

  it('accepts mathematical JSON integers but does not round a fractional token to an integer', async () => {
    const raw = JSON.stringify({ code: 'OK', message: '成功', data: order(), traceId })
    queued(new Response(raw.replace('"version":0', '"version":1.0e0')),
      new Response(raw.replace('"version":0', '"version":0.999999999999999999999999')))
    const api = await import('./tomApi')
    expect((await api.getOrder(orderId)).data.version).toBe(1)
    await expect(api.getOrder(orderId)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it.each([
    ['quoted numeric metadata', '{"content":[],"page":"9007199254740993","size":20,"totalElements":0,"totalPages":0}'],
    ['exponent allocation bomb', '{"content":[],"page":1e1000000000000,"size":20,"totalElements":0,"totalPages":0}'],
    ['negative total', '{"content":[],"page":0,"size":20,"totalElements":-1,"totalPages":0}'],
    ['noninteger total', '{"content":[],"page":0,"size":20,"totalElements":1.1,"totalPages":0}'],
  ])('rejects %s', async (_name, data) => {
    queued(new Response(`{"code":"OK","message":"成功","data":${data},"traceId":"${traceId}"}`))
    const api = await import('./tomApi')
    await expect(api.getOrders()).rejects.toMatchObject({ code: 'INVALID_RESPONSE', uncertain: false })
  })

  it('rejects duplicate keys, invalid UTF-8 and unsafe business versions', async () => {
    const raw = JSON.stringify({ code: 'OK', message: '成功', data: order(), traceId })
    queued(new Response(raw.replace('"version":0', '"version":0,"version":1')),
      new Response(new Uint8Array([0x7b, 0xff, 0x7d])), new Response(raw.replace('"version":0', '"version":9007199254740993')))
    const api = await import('./tomApi')
    for (let i = 0; i < 3; i++) await expect(api.getOrder(orderId)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('preserves decimal/date strings, extreme offsets and required nullable read fields', async () => {
    const data = { ...order(), remark: '\u0000e\u0301', items: [{ ...fullItem, grossWeight: '0.0000', lastPickupDate: '1500-01-02',
      deliveryCutoffAt: '1500-01-02t00:00:00.123456+23:59' }], tasks: [{ ...fullTask, siteContact: { name: null, phone: null } }],
      shipping: { shippingCompany: null, vesselVoyage: null, pol: null, pod: null, eta: null, etd: null, blNo: null } }
    queued(success(data))
    const api = await import('./tomApi')
    expect((await api.getOrder(orderId)).data).toEqual(data)
  })

  it('matches predecessor IDs as a set without assuming parallel response array ordering', async () => {
    const secondId = '00000000-0000-0000-0000-000000000006'
    const lastId = '00000000-0000-0000-0000-000000000007'
    const data = { ...order(), items: [fullItem], tasks: [fullTask, { ...fullTask, id: secondId, clientRef: 'task-2' },
      { ...fullTask, id: lastId, clientRef: 'task-3', predecessorRefs: ['task-1', 'task-2'], predecessorTaskIds: [secondId, taskId] }] }
    queued(success(data))
    const api = await import('./tomApi')
    expect((await api.getOrder(orderId)).data.tasks[2].predecessorTaskIds).toEqual([secondId, taskId])
  })

  it.each([
    ['year zero date', { ...order(), items: [{ ...fullItem, lastPickupDate: '0000-01-01' }] }],
    ['year zero datetime', { ...order(), createdAt: '0000-01-01T00:00:00Z' }],
    ['invalid leap day', { ...order(), items: [{ ...fullItem, lastPickupDate: '1500-02-29' }] }],
    ['microsecond overflow', { ...order(), updatedAt: '2026-11-25T00:00:00.1234567Z' }],
    ['extra read field', { ...order(), secret: 'unexpected' }],
    ['incomplete read shipping', { ...order(), shipping: {} }],
    ['noncontract false', { ...order(), items: [fullItem], tasks: [{ ...fullTask, requiredTask: false }] }],
    ['foreign task owner', { ...order(), items: [fullItem], tasks: [{ ...fullTask, orderId: customerId }] }],
    ['incorrect decimal type', { ...order(), items: [{ ...fullItem, grossWeight: 1.2 }] }],
  ])('rejects %s', async (_name, data) => {
    queued(success(data))
    const api = await import('./tomApi')
    await expect(api.getOrder(orderId)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })
})

describe('write outcome and cancellation safety', () => {
  it('does not retry a lost response and allows only a caller-requested same-body/key replay', async () => {
    const fetch = queued(csrf(), new TypeError('synthetic internal hostname'), success(order(), 201, true))
    const api = await import('./tomApi')
    await expect(api.createOrder(draft, key)).rejects.toMatchObject({ code: 'NETWORK_ERROR', uncertain: true, message: '無法確認服務回應，請確認網路後重試。' })
    expect(fetch).toHaveBeenCalledTimes(2)
    expect((await api.createOrder(draft, key)).replayed).toBe(true)
    expect(fetch.mock.calls[1][1]?.body).toBe(fetch.mock.calls[2][1]?.body)
  })

  it.each([
    ['missing replay header', () => success(order(), 201)],
    ['HTML response', () => new Response('<html>unexpected</html>', { status: 201 })],
    ['missing location', () => new Response(JSON.stringify({ code: 'OK', message: '成功', data: order(), traceId }), { status: 201, headers: { 'Idempotency-Replayed': 'false' } })],
  ])('retains uncertainty for an untrustworthy acknowledgement: %s', async (_name, acknowledgement) => {
    const fetch = queued(csrf(), acknowledgement())
    const api = await import('./tomApi')
    await expect(api.createOrder(draft, key)).rejects.toMatchObject({ code: 'INVALID_RESPONSE', uncertain: true })
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('forwards caller cancellation and marks a dispatched write uncertain', async () => {
    const fetch = queued(csrf())
    const session = await import('./api'); await session.getCsrfHeaders()
    let dispatched!: () => void
    const ready = new Promise<void>((resolve) => { dispatched = resolve })
    fetch.mockImplementationOnce((_url, init) => new Promise((_resolve, reject) => {
      init!.signal!.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
      dispatched()
    }))
    const api = await import('./tomApi')
    const controller = new AbortController()
    const pending = api.createOrder(draft, key, controller.signal)
    const assertion = expect(pending).rejects.toMatchObject({ code: 'REQUEST_ABORTED', uncertain: true })
    await ready; controller.abort(); await assertion
    expect(fetch.mock.calls[1][1]?.signal?.aborted).toBe(true)
  })

  it('times out a dispatched write once without changing key or sending another request', async () => {
    const fetch = queued(csrf())
    const session = await import('./api'); await session.getCsrfHeaders()
    const api = await import('./tomApi')
    vi.useFakeTimers()
    fetch.mockImplementationOnce((_url, init) => new Promise((_resolve, reject) => {
      init!.signal!.addEventListener('abort', () => reject(new DOMException('timeout', 'AbortError')))
    }))
    const pending = api.createOrder(draft, key)
    const assertion = expect(pending).rejects.toMatchObject({ code: 'REQUEST_ABORTED', uncertain: true })
    await vi.advanceTimersByTimeAsync(10000); await assertion
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('does not dispatch a pre-aborted write or classify CSRF acquisition failure as an uncertain save', async () => {
    const fetch = queued(new TypeError('CSRF network failure'))
    const api = await import('./tomApi')
    const controller = new AbortController(); controller.abort()
    await expect(api.createOrder(draft, key, controller.signal)).rejects.toMatchObject({ code: 'REQUEST_ABORTED', uncertain: false })
    expect(fetch).not.toHaveBeenCalled()
    await expect(api.createOrder(draft, key)).rejects.toMatchObject({ code: 'NETWORK_ERROR', uncertain: false })
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(fetch.mock.calls[0][0]).toBe('/api/v1/auth/csrf')
  })

  it('cancels CSRF acquisition without dispatching the business write', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockImplementation((_url, init) => new Promise((_resolve, reject) => {
      init!.signal!.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
    }))
    vi.stubGlobal('fetch', fetch)
    const api = await import('./tomApi')
    const controller = new AbortController()
    const pending = api.createOrder(draft, key, controller.signal)
    const assertion = expect(pending).rejects.toMatchObject({ code: 'REQUEST_ABORTED', uncertain: false })
    controller.abort(); await assertion
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(fetch.mock.calls[0][0]).toBe('/api/v1/auth/csrf')
  })

  it('rejects unsafe body numbers, unsupported queries and malformed keys before network activity', async () => {
    const fetch = queued()
    const api = await import('./tomApi')
    await expect(api.createOrder({ ...draft, items: [{ clientRef: 'item-1', packageQty: Number.MAX_SAFE_INTEGER + 1 }] }, key)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_ERROR', uncertain: false })
    await expect(api.createOrder(draft, '')).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_ERROR' })
    await expect(api.getOrder('1-1-1-1-1')).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_ERROR' })
    await expect(api.getOrders({ page: Number.MAX_SAFE_INTEGER + 1 })).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_ERROR' })
    await expect(api.getOrders({ enabled: true } as never)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_ERROR' })
    expect(fetch).not.toHaveBeenCalled()
    expect(api.newIdempotencyKey()).toMatch(/^[0-9a-f-]{36}$/)
  })
})
