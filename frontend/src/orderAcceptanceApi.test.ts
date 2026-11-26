import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Order } from './tomApi'

const id = 'a0000000-0000-4000-8000-000000000101'
const customerId = '00000000-0000-4000-8000-000000000201'
const itemId = '00000000-0000-4000-8000-000000000301'
const taskId = '00000000-0000-4000-8000-000000000401'
const actorId = '00000000-0000-4000-8000-000000000501'
const key = 'synthetic-accept-1'
const traceId = 'synthetic-acceptance-trace'
function accepted(override: Partial<Order> = {}): Order {
  return { id, orderNo: 'TOM-SYNTHETIC-ACCEPT', type: 'LOCAL', customerId, customerName: '合成歷史客戶', lifecycleStatus: 'OPEN', acceptanceStatus: 'ACCEPTED', version: 1,
    createdAt: '2026-11-25T12:00:00+08:00', updatedAt: '2026-11-26T12:00:00+08:00', createdBy: actorId, updatedBy: actorId,
    customerReference: '  e\u0301\u0000  ', remark: '\r\n\u0000', shipping: { shippingCompany: null, vesselVoyage: null, pol: null, pod: null, eta: null, etd: null, blNo: null },
    items: [{ id: itemId, clientRef: 'synthetic-item', sequence: 1, itemKind: 'CARGO', containerType: null, containerNo: null, packageQty: 1, grossWeight: '9007199254740993.123456789012345', cbm: '0.000', lastPickupDate: '1500-02-28', deliveryCutoffAt: '1500-03-01t00:00:00.123456+23:59' }],
    tasks: [{ id: taskId, taskNo: 'TOM-T-SYNTHETIC', orderId: id, itemId, clientRef: 'synthetic-task', itemRef: 'synthetic-item', taskKind: null, pickupName: '合成起點', pickupAddress: '合成地址\r\n一', deliveryName: '合成終點', deliveryAddress: '合成地址二', siteContact: { name: null, phone: null }, plannedStartAt: null, plannedEndAt: null, predecessorRefs: [], predecessorTaskIds: [], requiredTask: true }], ...override }
}
function envelope(data: unknown = accepted()) { return { code: 'OK', message: '成功', data, traceId } }
function success(data: unknown = accepted(), replayed?: string, status = 200, extraHeaders: Record<string, string> = {}) {
  return new Response(JSON.stringify(envelope(data)), { status, headers: { ...(replayed === undefined ? {} : { 'Idempotency-Replayed': replayed }), ...extraHeaders } })
}
function failure(status: number, code: string, data: unknown = null, fieldErrors: unknown[] = []) { return new Response(JSON.stringify({ code, message: '請確認合成受理資料', data, traceId, fieldErrors }), { status }) }
function csrf(token = 'synthetic-csrf') { return new Response(JSON.stringify({ code: '0000', message: '成功', data: { token, headerName: 'X-CSRF-TOKEN' }, timestamp: '2026-11-26T12:00:00+08:00' })) }
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

describe('independent TOM acceptance operation', () => {
  it('accepts HTTP200 without Location, uses the shared CSRF cache and preserves the complete historical snapshot', async () => {
    const fetch = queued(csrf(), success(accepted(), 'false'))
    const { acceptOrder } = await import('./orderAcceptanceApi')
    const result = await acceptOrder(id.toUpperCase(), { expectedVersion: 0 }, key)
    expect(result).toEqual({ ...envelope(), replayed: false, location: null })
    expect(fetch.mock.calls[1][0]).toBe(`/api/v1/orders/${id}/accept`)
    expect(fetch.mock.calls[1][1]).toMatchObject({ method: 'POST', credentials: 'same-origin', headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'X-CSRF-TOKEN': 'synthetic-csrf', 'Idempotency-Key': key }, body: '{"expectedVersion":0}' })
    expect(result.data.customerName).toBe('合成歷史客戶')
    expect(result.data.items[0].grossWeight).toBe('9007199254740993.123456789012345')
    expect(result.data.items[0].deliveryCutoffAt).toBe('1500-03-01t00:00:00.123456+23:59')
    expect(result.data.remark).toBe('\r\n\u0000')
  })

  it('captures the expected version before awaiting CSRF and never serializes later caller changes', async () => {
    let release!: (response: Response) => void
    const fetch = vi.fn<typeof globalThis.fetch>().mockImplementationOnce(() => new Promise((resolve) => { release = resolve })).mockResolvedValueOnce(success(accepted(), 'false'))
    vi.stubGlobal('fetch', fetch)
    const { acceptOrder } = await import('./orderAcceptanceApi')
    const body = { expectedVersion: 0 }
    const pending = acceptOrder(id, body, key)
    body.expectedVersion = 50
    release(csrf())
    expect((await pending).data.version).toBe(1)
    expect(fetch.mock.calls[1][1]?.body).toBe('{"expectedVersion":0}')
  })

  it('requires caller-directed same-body/key replay after a lost response and returns the original accepted snapshot', async () => {
    const fetch = queued(csrf(), new TypeError('Synthetic lost response'), success(accepted(), 'true'))
    const { acceptOrder } = await import('./orderAcceptanceApi')
    const body = Object.freeze({ expectedVersion: 0 })
    await expect(acceptOrder(id, body, key)).rejects.toMatchObject({ code: 'NETWORK_ERROR', uncertain: true })
    expect(fetch).toHaveBeenCalledTimes(2)
    expect((await acceptOrder(id, body, key)).replayed).toBe(true)
    expect(fetch.mock.calls[2][1]?.body).toBe(fetch.mock.calls[1][1]?.body)
    expect(fetch.mock.calls[2][1]).toMatchObject({ headers: { 'Idempotency-Key': key } })
  })

  it('leaves original create POST strict201/Location rules unchanged', async () => {
    const draftOrder = accepted({ acceptanceStatus: 'DRAFT', version: 0 })
    queued(csrf(), success(draftOrder, 'false', 200), success(draftOrder, 'false', 201), success(accepted(), 'false'))
    const old = await import('./tomApi')
    const { acceptOrder } = await import('./orderAcceptanceApi')
    await expect(old.createOrder({ type: 'LOCAL', customerId }, 'synthetic-create-1')).rejects.toMatchObject({ code: 'INVALID_RESPONSE', uncertain: true })
    await expect(old.createOrder({ type: 'LOCAL', customerId }, 'synthetic-create-2')).rejects.toMatchObject({ code: 'INVALID_RESPONSE', uncertain: true })
    expect((await acceptOrder(id, { expectedVersion: 0 }, key)).location).toBeNull()
  })

  it('refreshes CSRF after rejection but never silently resends acceptance', async () => {
    const fetch = queued(csrf('before'), failure(403, 'CSRF_INVALID'), csrf('after'), success(accepted(), 'true'))
    const { acceptOrder } = await import('./orderAcceptanceApi')
    await expect(acceptOrder(id, { expectedVersion: 0 }, key)).rejects.toMatchObject({ status: 403, code: 'CSRF_INVALID', uncertain: false, traceId })
    expect(fetch).toHaveBeenCalledTimes(3)
    expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
    await acceptOrder(id, { expectedVersion: 0 }, key)
    expect(fetch.mock.calls[3][1]).toMatchObject({ headers: { 'X-CSRF-TOKEN': 'after', 'Idempotency-Key': key } })
  })

  it('preserves completeness field pointers separately from protocol failures and returns the original conflict version', async () => {
    const stored = [{ path: '/items/0/containerType', fieldId: 'IF04', ruleId: 'ORD-P02', message: '請補合成櫃型' }]
    const protocol = [{ path: '/expectedVersion', fieldId: 'request', ruleId: 'FORMAT', message: '請確認版本格式' }]
    queued(csrf(), failure(400, 'VALIDATION_ERROR', null, stored), failure(400, 'VALIDATION_ERROR', null, protocol), failure(409, 'VERSION_CONFLICT', { actualVersion: 8 }, [{ path: '/expectedVersion', fieldId: 'AF01', ruleId: 'VERSION', message: '版本已更新' }]))
    const { acceptOrder } = await import('./orderAcceptanceApi')
    await expect(acceptOrder(id, { expectedVersion: 0 }, key)).rejects.toMatchObject({ code: 'VALIDATION_ERROR', fieldErrors: stored, fields: { '/items/0/containerType': '請補合成櫃型' }, uncertain: false })
    await expect(acceptOrder(id, { expectedVersion: 0 }, key)).rejects.toMatchObject({ code: 'VALIDATION_ERROR', fieldErrors: protocol, uncertain: false })
    await expect(acceptOrder(id, { expectedVersion: 0 }, key)).rejects.toMatchObject({ code: 'VERSION_CONFLICT', actualVersion: 8, uncertain: false, traceId })
  })

  it.each([[400, 'INVALID_CUSTOMER'], [401, 'UNAUTHENTICATED'], [403, 'FORBIDDEN'], [404, 'ORDER_NOT_FOUND'], [409, 'ORDER_NOT_EDITABLE'], [409, 'PROTECTED_RELATION'], [409, 'IDEMPOTENCY_KEY_REUSED'], [409, 'IDEMPOTENCY_IN_PROGRESS']] as const)('keeps %s/%s as a known error without interpreting it as a fresh success', async (status, code) => {
    const fetch = queued(csrf(), failure(status, code), ...(status === 403 ? [csrf()] : []))
    const { acceptOrder } = await import('./orderAcceptanceApi')
    await expect(acceptOrder(id, { expectedVersion: 0 }, key)).rejects.toMatchObject({ status, code, uncertain: false, traceId })
    expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
  })

  it('keeps valid 500 responses uncertain and hides server details', async () => {
    const fetch = queued(csrf(), failure(500, 'INTERNAL_ERROR'))
    const { acceptOrder } = await import('./orderAcceptanceApi')
    await expect(acceptOrder(id, { expectedVersion: 0 }, key)).rejects.toMatchObject({ status: 500, code: 'INTERNAL_ERROR', message: '服務暫時無法使用，請稍後重試。', uncertain: true })
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('allows the maximum request version for the authoritative ORDER_NOT_EDITABLE check', async () => {
    const fetch = queued(csrf(), failure(409, 'ORDER_NOT_EDITABLE'))
    const { acceptOrder } = await import('./orderAcceptanceApi')
    await expect(acceptOrder(id, { expectedVersion: Number.MAX_SAFE_INTEGER }, key)).rejects.toMatchObject({ code: 'ORDER_NOT_EDITABLE', uncertain: false })
    expect(fetch.mock.calls[1][1]?.body).toBe('{"expectedVersion":9007199254740991}')
  })
})

describe('untrusted acceptance acknowledgements', () => {
  it.each([
    ['wrong HTTP201', () => success(accepted(), 'false', 201, { Location: `/api/v1/orders/${id}` })],
    ['missing replay header', () => success()], ['invalid replay header', () => success(accepted(), 'TRUE')],
    ['wrong order ID', () => success(accepted({ id: customerId, tasks: [] }), 'false')], ['draft instead of accepted', () => success(accepted({ acceptanceStatus: 'DRAFT' }), 'false')],
    ['cancelled order', () => success(accepted({ lifecycleStatus: 'CANCELLED' }), 'false')], ['closed order', () => success(accepted({ lifecycleStatus: 'CLOSED' }), 'false')],
    ['unchanged version', () => success(accepted({ version: 0 }), 'false')], ['skipped version', () => success(accepted({ version: 2 }), 'false')],
    ['unsafe version', () => success(accepted({ version: Number.MAX_SAFE_INTEGER + 1 }), 'false')],
    ['invented event metadata', () => success({ ...accepted(), acceptedAt: '2026-11-26T12:00:00+08:00' }, 'false')],
    ['wrong UPMS envelope', () => csrf()], ['malformed JSON', () => new Response('{', { headers: { 'Idempotency-Replayed': 'false' } })],
    ['duplicate JSON properties', () => new Response(JSON.stringify(envelope()).replace('"version":1', '"version":1,"version":1'), { headers: { 'Idempotency-Replayed': 'false' } })],
    ['trailing JSON data', () => new Response(`${JSON.stringify(envelope())}{}`, { headers: { 'Idempotency-Replayed': 'false' } })],
    ['fraction rounded by native JSON.parse', () => new Response(JSON.stringify(envelope()).replace('"version":1', '"version":1.0000000000000000001'), { headers: { 'Idempotency-Replayed': 'false' } })],
    ['invalid UTF8', () => new Response(new Uint8Array([0xff, 0x7b, 0x7d]), { headers: { 'Idempotency-Replayed': 'false' } })],
    ['unpaired surrogate', () => success(accepted({ customerReference: '\uD800' }), 'false')],
    ['new unapproved error code', () => failure(409, 'ORDER_INCOMPLETE')], ['mismatched error HTTP', () => failure(400, 'VERSION_CONFLICT')],
    ['invalid error data', () => failure(409, 'VERSION_CONFLICT', { actualVersion: 1, eventId: id })],
  ])('retains uncertainty for %s', async (_name, ack) => {
    const fetch = queued(csrf(), ack())
    const { acceptOrder } = await import('./orderAcceptanceApi')
    await expect(acceptOrder(id, { expectedVersion: 0 }, key)).rejects.toMatchObject({ code: 'INVALID_RESPONSE', uncertain: true })
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('accepts mathematically integral JSON exponent responses without rounding fractions', async () => {
    queued(csrf(), new Response(JSON.stringify(envelope()).replace('"version":1', '"version":1e0'), { headers: { 'Idempotency-Replayed': 'true' } }))
    const { acceptOrder } = await import('./orderAcceptanceApi')
    expect((await acceptOrder(id, { expectedVersion: 0 }, key)).data.version).toBe(1)
  })
})

describe('acceptance input and cancellation', () => {
  it.each([null, {}, { expectedVersion: null }, { expectedVersion: '0' }, { expectedVersion: -1 }, { expectedVersion: 0.5 }, { expectedVersion: NaN }, { expectedVersion: Infinity }, { expectedVersion: Number.MAX_SAFE_INTEGER + 1 }, { expectedVersion: 0, actorId }, { expectedVersion: 0, draft: {} }, [0]])('rejects invalid request %j before any HTTP', async (body) => {
    const fetch = queued()
    const { acceptOrder } = await import('./orderAcceptanceApi')
    await expect(acceptOrder(id, body as never, key)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_ERROR', uncertain: false })
    expect(fetch).not.toHaveBeenCalled()
  })

  it('rejects malformed paths and keys without generating a replacement identity', async () => {
    const fetch = queued()
    const { acceptOrder } = await import('./orderAcceptanceApi')
    for (const badId of [`${id}\n`, `${id}?force=true`, 'me']) await expect(acceptOrder(badId, { expectedVersion: 0 }, key)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_ERROR', uncertain: false })
    for (const badKey of ['', 'a'.repeat(129), 'synthetic\n', '合成識別']) await expect(acceptOrder(id, { expectedVersion: 0 }, badKey)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_ERROR', uncertain: false })
    expect(fetch).not.toHaveBeenCalled()
  })

  it('does not dispatch a business request when security-token acquisition fails', async () => {
    const fetch = queued(new TypeError('Synthetic CSRF failure'))
    const { acceptOrder } = await import('./orderAcceptanceApi')
    await expect(acceptOrder(id, { expectedVersion: 0 }, key)).rejects.toMatchObject({ code: 'NETWORK_ERROR', uncertain: false })
    expect(fetch).toHaveBeenCalledOnce()
    expect(fetch.mock.calls[0][0]).toBe('/api/v1/auth/csrf')
  })

  it('does not dispatch an already-aborted request', async () => {
    const fetch = queued(), controller = new AbortController()
    controller.abort()
    const { acceptOrder } = await import('./orderAcceptanceApi')
    await expect(acceptOrder(id, { expectedVersion: 0 }, key, controller.signal)).rejects.toMatchObject({ code: 'REQUEST_ABORTED', uncertain: false })
    expect(fetch).not.toHaveBeenCalled()
  })

  it('marks an interrupted dispatched acceptance uncertain without retrying', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(csrf()).mockImplementationOnce((_url, options) => new Promise((_resolve, reject) => {
      options?.signal?.addEventListener('abort', () => reject(new DOMException('Synthetic abort', 'AbortError')), { once: true })
    }))
    vi.stubGlobal('fetch', fetch)
    await (await import('./api')).getCsrfHeaders()
    const { acceptOrder } = await import('./orderAcceptanceApi')
    const controller = new AbortController(), pending = acceptOrder(id, { expectedVersion: 0 }, key, controller.signal)
    const assertion = expect(pending).rejects.toMatchObject({ code: 'REQUEST_ABORTED', uncertain: true })
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(2))
    controller.abort()
    await assertion
  })

  it('times out a dispatched acceptance without automatically repeating it', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(csrf()).mockImplementationOnce((_url, options) => new Promise((_resolve, reject) => {
      options?.signal?.addEventListener('abort', () => reject(new DOMException('Synthetic timeout', 'AbortError')), { once: true })
    }))
    vi.stubGlobal('fetch', fetch)
    await (await import('./api')).getCsrfHeaders()
    const { acceptOrder } = await import('./orderAcceptanceApi')
    vi.useFakeTimers()
    const pending = acceptOrder(id, { expectedVersion: 0 }, key)
    const assertion = expect(pending).rejects.toMatchObject({ code: 'REQUEST_ABORTED', uncertain: true })
    await vi.advanceTimersByTimeAsync(10000)
    await assertion
    expect(fetch).toHaveBeenCalledTimes(2)
  })
})
