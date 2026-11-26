import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Assignment, DispatchEvent, MyTask, ResourceCandidate, TaskSource, TaskSummary } from './tmsApi'

const uuid = (value: number) => `00000000-0000-4000-8000-${String(value).padStart(12, '0')}`
const taskId = uuid(1), orderId = uuid(2), assignmentId = uuid(3), nextId = uuid(4), driverId = uuid(5), mainId = uuid(6), trailerId = uuid(7), actorId = uuid(8)
const time = '2026-11-26T12:00:00.123456789+08:00', endTime = '2026-11-26T12:01:00.123456789+08:00'
const key = 'synthetic-command-1'
const source: TaskSource = { taskId, taskNo: 'SYNTHETIC-TASK', orderId, orderNo: 'SYNTHETIC-ORDER', orderType: 'LOCAL', orderVersion: 3,
  item: { itemKind: 'CARGO', containerType: '', containerNo: ' e\u0301\u0000 ', packageQty: 1, grossWeight: '9007199254740993.123456789012345', cbm: '0.000' },
  taskKind: '', pickupName: ' 合成起點\u0000 ', pickupAddress: '合成\r\n地址', deliveryName: '合成終點', deliveryAddress: '', siteContact: { name: null, phone: null }, plannedStartAt: '1500-03-01t00:00:00.123456+23:59', plannedEndAt: null, requiredTask: null }
function assigned(options: { id?: string; generation?: number; previous?: string; reason?: string; driverId?: string; mainId?: string; trailerId?: string } = {}): Assignment {
  const id = options.id ?? assignmentId, generation = options.generation ?? 1, driver = options.driverId ?? driverId, main = options.mainId ?? mainId
  const previous = options.previous ?? null, reason = options.reason ?? null
  return { id, taskId, orderId, status: 'ASSIGNED', version: 0, assignedGeneration: generation, endedGeneration: null,
    driver: { id: driver, driverCode: 'SYNTHETIC DRIVER', name: '合成司機', version: 2 }, mainVehicle: { id: main, plateNo: 'SYNTHETIC MAIN', vehicleType: uuid(9), vehicleTypeCode: 'SYNTHETIC_TYPE', vehicleTypeLabel: '合成車種', version: 2 },
    trailer: options.trailerId ? { id: options.trailerId, plateNo: 'SYNTHETIC TRAILER', vehicleType: uuid(9), vehicleTypeCode: 'SYNTHETIC_TYPE', vehicleTypeLabel: '合成拖板', version: 1 } : null,
    source: structuredClone(source), policyMode: 'SYNTHETIC_TEST_ONLY', assignedBy: actorId, assignedAt: time, assignmentReason: reason, endedBy: null, endedAt: null, endReason: null, previousAssignmentId: previous,
    reservations: [{ id: uuid(10), kind: 'DRIVER', position: 'DRIVER', resourceId: driver, acquiredAt: time, releasedAt: null, releaseReason: null }, { id: uuid(11), kind: 'VEHICLE', position: 'MAIN', resourceId: main, acquiredAt: time, releasedAt: null, releaseReason: null }, ...(options.trailerId ? [{ id: uuid(12), kind: 'VEHICLE' as const, position: 'TRAILER' as const, resourceId: options.trailerId, acquiredAt: time, releasedAt: null, releaseReason: null }] : [])],
    events: [{ id: uuid(13), action: previous ? 'REASSIGNED' : 'ASSIGNED', assignmentId: id, previousAssignmentId: previous, fromGeneration: generation - 1, toGeneration: generation, actorId, occurredAt: time, reason }] }
}
function ended(original: Assignment, status: 'REVOKED' | 'SUPERSEDED', generation: number, reason = '合成撤改原因', next = nextId): Assignment {
  const event: DispatchEvent = { id: uuid(14), action: status === 'REVOKED' ? 'REVOKED' : 'REASSIGNED', assignmentId: status === 'REVOKED' ? original.id : next, previousAssignmentId: status === 'REVOKED' ? null : original.id, fromGeneration: generation - 1, toGeneration: generation, actorId, occurredAt: endTime, reason }
  return { ...original, status, version: 1, endedGeneration: generation, endedBy: actorId, endedAt: endTime, endReason: reason, reservations: original.reservations.map((r) => ({ ...r, releasedAt: endTime, releaseReason: reason })), events: [...original.events, event] }
}
function summary(generation = 0, currentAssignmentId: string | null = null): TaskSummary { return { taskId, taskNo: source.taskNo, orderId, orderNo: source.orderNo, orderType: source.orderType, orderVersion: source.orderVersion, generation, taskKind: source.taskKind, pickupName: source.pickupName, deliveryName: source.deliveryName, prerequisiteReady: true, currentAssignmentId, policyMode: 'UNCONFIRMED', blockingReasons: ['POLICY_NOT_CONFIRMED', ...(currentAssignmentId ? ['ALREADY_ASSIGNED' as const] : [])] } }
function myTask(): MyTask { return { assignmentId, taskId, taskNo: source.taskNo, item: source.item, taskKind: source.taskKind, pickupName: source.pickupName, pickupAddress: source.pickupAddress, deliveryName: source.deliveryName, deliveryAddress: source.deliveryAddress, siteContact: source.siteContact, plannedStartAt: source.plannedStartAt, plannedEndAt: source.plannedEndAt, assignedAt: time, policyMode: 'SYNTHETIC_TEST_ONLY' } }
function resource(override: Partial<ResourceCandidate> = {}): ResourceCandidate { return { id: driverId, kind: 'DRIVER', position: 'DRIVER', code: 'SYNTHETIC DRIVER', label: '合成司機', version: 2, administrativelyAvailable: true, occupied: false, occupation: null, policyMode: 'UNCONFIRMED', policyApproved: false, blockingReasons: ['POLICY_NOT_CONFIRMED'], ...override } }
const page = (content: unknown[], index = 0, size = 20, totalElements = content.length) => ({ content, page: index, size, totalElements, totalPages: Math.ceil(totalElements / size) })
const result = (assignment = assigned(), generation = assignment.assignedGeneration) => ({ taskId, generation, assignment })
function ok(data: unknown, replayed?: string, status = 200) { return new Response(JSON.stringify({ code: '0000', message: '成功', data, timestamp: time }), { status, headers: replayed === undefined ? {} : { 'Idempotency-Replayed': replayed } }) }
function error(status: number, code: string, data: unknown = null) { return new Response(JSON.stringify({ code, message: '合成操作失敗', data, timestamp: time }), { status }) }
function csrf(token = 'synthetic-csrf') { return ok({ token, headerName: 'X-CSRF-TOKEN' }) }
function queued(...responses: (Response | Error)[]) {
  const fetch = vi.fn<typeof globalThis.fetch>()
  for (const response of responses) { if (response instanceof Error) fetch.mockRejectedValueOnce(response); else fetch.mockResolvedValueOnce(response) }
  vi.stubGlobal('fetch', fetch); return fetch
}
const assignBody = () => ({ expectedGeneration: 0, driverId, mainVehicleId: mainId, trailerId: null })
beforeEach(() => { vi.resetModules() })
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

describe('TMS independent transport and routes', () => {
  it('uses all seven routes, exact query types and shared CSRF without general FMS lookups', async () => {
    const current = assigned(), replacement = assigned({ id: nextId, generation: 2, previous: assignmentId, reason: '合成改派', driverId: uuid(15) })
    const fetch = queued(ok(page([summary()])), ok({ task: summary(), current: null, history: page([]) }), ok(page([resource()])), ok(page([myTask()])), csrf(), ok(result(current), 'false'), ok(result(replacement), 'false'), ok(result(ended(replacement, 'REVOKED', 3, '合成撤派'), 3), 'false'))
    const api = await import('./tmsApi')
    await api.getTmsTasks(1, { size: 5, state: 'ALL', orderId, q: ' %_ + & ' })
    await api.getTaskDispatch(taskId, { historyPage: 0, historySize: 5 })
    await api.getTmsResources({ taskId, position: 'DRIVER', assignmentId, page: 0, size: 5, q: '合成' })
    await api.getMyTmsTasks(0, { size: 5 })
    expect((await api.assignTask(taskId, assignBody(), key)).data.assignment.id).toBe(assignmentId)
    await api.reassignDispatch(assignmentId, { expectedGeneration: 1, driverId: uuid(15), mainVehicleId: mainId, trailerId: null, reason: '合成改派' }, 'synthetic-reassign', { taskId })
    await api.revokeDispatch(nextId, { expectedGeneration: 2, reason: '合成撤派' }, 'synthetic-revoke', { taskId })
    const paths = fetch.mock.calls.map(([url]) => new URL(String(url), 'https://localhost').pathname)
    expect(paths).toEqual(['/api/v1/tms/tasks', `/api/v1/tms/tasks/${taskId}/dispatch`, '/api/v1/tms/resources', '/api/v1/tms/my-tasks', '/api/v1/auth/csrf', `/api/v1/tms/tasks/${taskId}/dispatch`, `/api/v1/tms/dispatches/${assignmentId}/reassign`, `/api/v1/tms/dispatches/${nextId}/revoke`])
    expect(Object.fromEntries(new URL(String(fetch.mock.calls[0][0]), 'https://localhost').searchParams)).toEqual({ page: '1', size: '5', q: ' %_ + & ', state: 'ALL', orderId })
    expect(fetch.mock.calls[5][1]).toMatchObject({ method: 'POST', credentials: 'same-origin', headers: { 'X-CSRF-TOKEN': 'synthetic-csrf', 'Idempotency-Key': key }, body: JSON.stringify(assignBody()) })
  })

  it('preserves historical snapshots, raw TOM text and SUPERSEDED events targeting the new assignment', async () => {
    const first = ended(assigned(), 'SUPERSEDED', 2), current = assigned({ id: nextId, generation: 2, previous: assignmentId, reason: '合成撤改原因' })
    queued(ok({ task: summary(2, nextId), current, history: page([current, first]) }))
    const { getTaskDispatch } = await import('./tmsApi')
    const detail = await getTaskDispatch(taskId)
    expect(detail.history.content[1].events[1]).toMatchObject({ assignmentId: nextId, previousAssignmentId: assignmentId })
    expect(detail.current?.source.item.grossWeight).toBe('9007199254740993.123456789012345')
    expect(detail.current?.source.item.containerNo).toBe(' e\u0301\u0000 ')
    expect(detail.current?.source.siteContact).toEqual({ name: null, phone: null })
    expect(detail.current?.source.plannedStartAt).toBe('1500-03-01t00:00:00.123456+23:59')
  })

  it('keeps blocked and reusable occupied candidates without post-filtering the server page', async () => {
    const own = resource({ occupied: true, occupation: { assignmentId, taskId }, policyMode: 'SYNTHETIC_TEST_ONLY', policyApproved: true, blockingReasons: [] })
    const unavailable = resource({ id: uuid(16), administrativelyAvailable: false, blockingReasons: ['ADMIN_UNAVAILABLE', 'POLICY_NOT_CONFIRMED'] })
    queued(ok(page([own, unavailable], 0, 5, 6)))
    const { getTmsResources } = await import('./tmsApi')
    expect(await getTmsResources({ taskId, position: 'DRIVER', assignmentId, size: 5 })).toMatchObject({ content: [own, unavailable], totalElements: 6, totalPages: 2 })
  })

  it('captures the original reason/body before awaiting CSRF and checks its normalized acknowledgement', async () => {
    let release!: (response: Response) => void
    const rawReason = '\u001c\uFEFF  e\u0301\n\t合成\r\n原因  ', canonical = 'é\n\t合成\r\n原因'
    const fetch = vi.fn<typeof globalThis.fetch>().mockImplementationOnce(() => new Promise((resolve) => { release = resolve })).mockResolvedValueOnce(ok(result(ended(assigned(), 'REVOKED', 2, canonical), 2), 'false'))
    vi.stubGlobal('fetch', fetch)
    const { revokeDispatch } = await import('./tmsApi')
    const body = { expectedGeneration: 1, reason: rawReason }, context = { taskId }
    const pending = revokeDispatch(assignmentId, body, key, context)
    body.reason = 'changed'; body.expectedGeneration = 88; context.taskId = uuid(99)
    release(csrf())
    expect((await pending).data.generation).toBe(2)
    expect(fetch.mock.calls[1][1]?.body).toBe(JSON.stringify({ expectedGeneration: 1, reason: rawReason }))
  })

  it('replays an old ASSIGNED result after later revoke without inventing current occupancy', async () => {
    const original = assigned(), revoked = ended(original, 'REVOKED', 2)
    const fetch = queued(csrf(), ok(result(original), 'false'), ok(result(revoked, 2), 'false'), ok(result(original), 'true'))
    const api = await import('./tmsApi')
    await api.assignTask(taskId, assignBody(), key)
    await api.revokeDispatch(assignmentId, { expectedGeneration: 1, reason: '合成撤改原因' }, 'synthetic-revoke', { taskId })
    const replay = await api.assignTask(taskId, assignBody(), key)
    expect(replay.replayed).toBe(true)
    expect(replay.data.assignment.status).toBe('ASSIGNED')
    expect(fetch.mock.calls[3][1]?.body).toBe(fetch.mock.calls[1][1]?.body)
  })

  it('preserves E16 business reason, nested fields and actualGeneration separately', async () => {
    queued(csrf(), error(409, 'E1601', { reason: 'VERSION_CONFLICT', actualGeneration: 7, fields: { expectedGeneration: '版本已更新' } }))
    const { assignTask } = await import('./tmsApi'), { ApiError } = await import('./api')
    const caught = await assignTask(taskId, assignBody(), key).catch((failure: unknown) => failure)
    expect(caught).toBeInstanceOf(ApiError)
    expect(caught).toMatchObject({ status: 409, code: 'E1601', reason: 'VERSION_CONFLICT', actualGeneration: 7, fields: { expectedGeneration: '版本已更新' }, uncertain: false })
  })

  it('refreshes only CSRF_INVALID and never implicitly repeats a command', async () => {
    const fetch = queued(csrf('before'), error(403, 'E0403', { reason: 'CSRF_INVALID' }), csrf('fresh'), ok(result(), 'true'))
    const { assignTask } = await import('./tmsApi')
    await expect(assignTask(taskId, assignBody(), key)).rejects.toMatchObject({ status: 403, reason: 'CSRF_INVALID', uncertain: false })
    expect(fetch).toHaveBeenCalledTimes(3)
    await assignTask(taskId, assignBody(), key)
    expect(fetch.mock.calls[3][1]).toMatchObject({ headers: { 'X-CSRF-TOKEN': 'fresh', 'Idempotency-Key': key } })
    expect(fetch.mock.calls[3][1]?.body).toBe(fetch.mock.calls[1][1]?.body)
  })

  it.each([401, 403])('exposes authorization loss from CSRF refresh HTTP%s instead of hiding it', async (status) => {
    const fetch = queued(csrf(), error(403, 'E0403', { reason: 'CSRF_INVALID' }), error(status, status === 401 ? 'E0401' : 'E0403'))
    const { assignTask } = await import('./tmsApi')
    await expect(assignTask(taskId, assignBody(), key)).rejects.toMatchObject({ status, code: status === 401 ? 'E0401' : 'E0403', reason: status === 403 ? 'FORBIDDEN' : null, uncertain: true })
    expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
  })

  it('preserves the original CSRF result after a refresh network failure', async () => {
    const fetch = queued(csrf(), error(403, 'E0403', { reason: 'CSRF_INVALID' }), new TypeError('Synthetic refresh loss'))
    const { assignTask } = await import('./tmsApi')
    await expect(assignTask(taskId, assignBody(), key)).rejects.toMatchObject({ code: 'E0403', reason: 'CSRF_INVALID' })
    expect(fetch).toHaveBeenCalledTimes(3)
  })

  it.each([null, { reason: 'FORBIDDEN' }, { reason: 'UNKNOWN_AUTH_STATE' }])('preserves non-CSRF403 data %j for safe UI exit without a token refresh', async (data) => {
    const fetch = queued(error(403, 'E0403', data))
    const { getMyTmsTasks } = await import('./tmsApi')
    await expect(getMyTmsTasks(0)).rejects.toMatchObject({ status: 403, reason: data?.reason ?? null })
    expect(fetch).toHaveBeenCalledOnce()
  })

  it('marks dispatched network/500 failures uncertain while leaving token acquisition failures certain', async () => {
    let fetch = queued(new TypeError('Synthetic preflight loss'))
    let api = await import('./tmsApi')
    await expect(api.assignTask(taskId, assignBody(), key)).rejects.toMatchObject({ code: 'NETWORK_ERROR', uncertain: false })
    expect(fetch).toHaveBeenCalledOnce()
    vi.resetModules()
    fetch = queued(csrf(), new TypeError('Synthetic lost command'), error(500, 'E0500'))
    api = await import('./tmsApi')
    await expect(api.assignTask(taskId, assignBody(), key)).rejects.toMatchObject({ code: 'NETWORK_ERROR', uncertain: true })
    await expect(api.assignTask(taskId, assignBody(), key)).rejects.toMatchObject({ code: 'E0500', uncertain: true, message: '服務暫時無法使用，請稍後重試。' })
    expect(fetch).toHaveBeenCalledTimes(3)
  })
})

describe('TMS privacy and schema validation', () => {
  it.each(['orderId', 'customerName', 'driverId', 'accountId', 'mainVehicle', 'plateNo', 'licenseReference', 'generation', 'history', 'reservations', 'traceId'])('rejects forbidden MyTask field %s', async (field) => {
    queued(ok(page([{ ...myTask(), [field]: 'synthetic-private' }])))
    const { getMyTmsTasks } = await import('./tmsApi')
    await expect(getMyTmsTasks(0)).rejects.toMatchObject({ code: 'INVALID_RESPONSE', uncertain: false })
  })

  it('preserves nullable-vs-empty/raw source values and rejects nested sensitive extras', async () => {
    queued(ok(page([{ ...myTask(), pickupName: '', siteContact: null }])), ok(page([{ ...myTask(), item: { ...source.item, id: uuid(80) } }])), ok(page([{ ...myTask(), siteContact: { name: null, phone: null, accountId: uuid(80) } }])))
    const { getMyTmsTasks } = await import('./tmsApi')
    expect((await getMyTmsTasks(0)).content[0]).toMatchObject({ pickupName: '', siteContact: null, item: source.item })
    await expect(getMyTmsTasks(0)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    await expect(getMyTmsTasks(0)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it.each([
    ['unsafe generation', { generation: Number.MAX_SAFE_INTEGER + 1 }], ['unknown blocker', { blockingReasons: ['LEGAL_APPROVED'] }], ['duplicate blocker', { blockingReasons: ['POLICY_NOT_CONFIRMED', 'POLICY_NOT_CONFIRMED'] }], ['missing nullable field', { pickupName: undefined }],
  ])('rejects task %s', async (_name, override) => {
    queued(ok(page([{ ...summary(), ...override }])))
    const { getTmsTasks } = await import('./tmsApi')
    await expect(getTmsTasks(0)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it.each([
    ['policy invention', { policyApproved: true }], ['occupation mismatch', { occupied: true }], ['role mismatch', { kind: 'VEHICLE' }], ['unknown position', { position: 'PAIR' }],
  ])('rejects resource %s', async (_name, override) => {
    queued(ok(page([{ ...resource(), ...override }])))
    const { getTmsResources } = await import('./tmsApi')
    await expect(getTmsResources({ taskId, position: 'DRIVER' })).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it.each([
    ['source precision', { plannedStartAt: '2026-11-26T10:00:00.1234567+08:00' }], ['invalid calendar', { plannedStartAt: '1500-02-29T10:00:00+08:00' }], ['unsafe integer', { item: { ...source.item, packageQty: 2147483648 } }], ['numeric decimal', { item: { ...source.item, grossWeight: 1.5 } }],
  ])('rejects MyTask %s without normalizing it', async (_name, override) => {
    queued(ok(page([{ ...myTask(), ...override }])))
    const { getMyTmsTasks } = await import('./tmsApi')
    await expect(getMyTmsTasks(0)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it.each([
    ['active terminal fields', (a: Assignment) => ({ ...a, endedBy: actorId })],
    ['wrong source task', (a: Assignment) => ({ ...a, source: { ...a.source, taskId: uuid(90) } })],
    ['wrong reservation resource', (a: Assignment) => ({ ...a, reservations: a.reservations.map((r, i) => i ? { ...r, resourceId: uuid(90) } : r) })],
    ['duplicate position', (a: Assignment) => ({ ...a, reservations: [a.reservations[0], { ...a.reservations[0], id: uuid(90) }] })],
    ['wrong reservation kind', (a: Assignment) => ({ ...a, reservations: a.reservations.map((r, i) => i ? { ...r, kind: 'DRIVER' } : r) })],
    ['unrelated event', (a: Assignment) => ({ ...a, events: [{ ...a.events[0], assignmentId: uuid(90) }] })],
    ['event generation gap', (a: Assignment) => ({ ...a, events: [{ ...a.events[0], toGeneration: 2 }] })],
    ['missing trailer reservation', (a: Assignment) => ({ ...a, trailer: { ...a.mainVehicle, id: trailerId } })],
  ])('rejects assignment %s', async (_name, mutate) => {
    queued(ok({ task: summary(1, assignmentId), current: mutate(assigned()), history: page([]) }))
    const { getTaskDispatch } = await import('./tmsApi')
    await expect(getTaskDispatch(taskId)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('rejects a terminal assignment with active reservations and keeps history totals intact', async () => {
    const terminal = ended(assigned(), 'REVOKED', 2)
    queued(ok({ task: summary(2), current: null, history: page([{ ...terminal, reservations: assigned().reservations }]) }), ok({ task: summary(2), current: null, history: page([terminal], 1, 5, 6) }))
    const { getTaskDispatch } = await import('./tmsApi')
    await expect(getTaskDispatch(taskId)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    expect((await getTaskDispatch(taskId, { historyPage: 1, historySize: 5 })).history).toMatchObject({ page: 1, totalElements: 6, totalPages: 2 })
  })

  it.each([
    ['wrong reason/code pair', () => error(409, 'E1605', { reason: 'VERSION_CONFLICT' })], ['missing conflict generation', () => error(409, 'E1601', { reason: 'VERSION_CONFLICT' })], ['empty fields', () => error(400, 'E0400', { reason: 'VALIDATION_ERROR', fields: {} })], ['extra data', () => error(409, 'E1602', { reason: 'RESOURCE_OCCUPIED', driverId })], ['wrong TOM envelope', () => new Response(JSON.stringify({ code: 'OK', message: '成功', data: page([]), traceId: 'synthetic' }))],
  ])('rejects %s', async (_name, ack) => {
    queued(ack())
    const { getTmsTasks } = await import('./tmsApi')
    await expect(getTmsTasks(0)).rejects.toMatchObject({ code: 'INVALID_RESPONSE', uncertain: false })
  })

  it('rejects exact fractional/duplicate/malformedUTF8 values without native JSON rounding', async () => {
    const body = JSON.stringify({ code: '0000', message: '成功', data: page([summary()]), timestamp: time })
    queued(new Response(body.replace('"generation":0', '"generation":0.00000000000000000001')), new Response(body.replace('"generation":0', '"generation":0,"generation":0')), new Response(new Uint8Array([0xff])))
    const { getTmsTasks } = await import('./tmsApi')
    for (let i = 0; i < 3; i++) await expect(getTmsTasks(0)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })
})

describe('TMS command acknowledgement witnesses', () => {
  it.each([
    ['HTTP201', () => ok(result(), 'false', 201)], ['missing replay header', () => ok(result())], ['wrong replay header', () => ok(result(), 'TRUE')], ['generation unchanged', () => ok(result(assigned(), 0), 'false')], ['wrong task', () => ok({ ...result(), taskId: uuid(90) }, 'false')], ['wrong selected driver', () => ok(result(assigned({ driverId: uuid(90) })), 'false')], ['invented trailer', () => ok(result(assigned({ trailerId })), 'false')], ['wrong assignment reason', () => ok(result({ ...assigned(), assignmentReason: 'unexpected' }), 'false')], ['wrong event reason', () => ok(result({ ...assigned(), events: [{ ...assigned().events[0], reason: 'unexpected' }] }), 'false')],
  ])('keeps %s uncertain', async (_name, ack) => {
    queued(csrf(), ack())
    const { assignTask } = await import('./tmsApi')
    await expect(assignTask(taskId, assignBody(), key)).rejects.toMatchObject({ code: 'INVALID_RESPONSE', uncertain: true })
  })

  it('checks both revoke endReason and its current event, not a past assignmentReason', async () => {
    const original = assigned({ id: nextId, generation: 2, previous: assignmentId, reason: '原改派原因' })
    const terminal = ended(original, 'REVOKED', 3, '新撤派原因')
    queued(csrf(), ok(result(terminal, 3), 'false'), ok(result({ ...terminal, endReason: '另一原因' }, 3), 'false'), ok(result({ ...terminal, events: terminal.events.map((e, i) => i ? { ...e, reason: '另一原因' } : e) }, 3), 'false'))
    const { revokeDispatch } = await import('./tmsApi'), body = { expectedGeneration: 2, reason: ' 新撤派原因 ' }
    expect((await revokeDispatch(nextId, body, key, { taskId })).data.assignment.assignmentReason).toBe('原改派原因')
    await expect(revokeDispatch(nextId, body, key, { taskId })).rejects.toMatchObject({ uncertain: true })
    await expect(revokeDispatch(nextId, body, key, { taskId })).rejects.toMatchObject({ uncertain: true })
  })

  it('requires a new reassigned identity and a matching canonical reason', async () => {
    const replacement = assigned({ id: nextId, generation: 2, previous: assignmentId, reason: 'é 合成改派', driverId: uuid(15), trailerId })
    queued(csrf(), ok(result(replacement), 'false'), ok(result({ ...replacement, assignmentReason: '錯誤原因' }), 'false'))
    const { reassignDispatch } = await import('./tmsApi'), body = { expectedGeneration: 1, driverId: uuid(15), mainVehicleId: mainId, trailerId, reason: ' e\u0301 合成改派 ' }
    expect((await reassignDispatch(assignmentId, body, key, { taskId })).data.assignment.id).toBe(nextId)
    await expect(reassignDispatch(assignmentId, body, key, { taskId })).rejects.toMatchObject({ uncertain: true })
  })
})

describe('TMS query/input boundaries and cancellation', () => {
  it('rejects unrelated queries, unsafe bodies, missing nullable fields and client policy invention before HTTP', async () => {
    const fetch = queued(), api = await import('./tmsApi')
    await expect(api.getTmsTasks(100001)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_ERROR' })
    await expect(api.getTmsTasks(0, { sort: 'taskNo,asc' } as never)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_ERROR' })
    await expect(api.getTaskDispatch(taskId, { page: 0 } as never)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_ERROR' })
    await expect(api.getMyTmsTasks(0, { driverId } as never)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_ERROR' })
    await expect(api.getTmsResources({ taskId, position: 'MAIN', orderId } as never)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_ERROR' })
    await expect(api.assignTask(taskId, { ...assignBody(), expectedGeneration: 0.1 }, key)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_ERROR' })
    await expect(api.assignTask(taskId, { expectedGeneration: 0, driverId, mainVehicleId: mainId } as never, key)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_ERROR' })
    await expect(api.assignTask(taskId, { ...assignBody(), policyMode: 'SYNTHETIC_TEST_ONLY' } as never, key)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_ERROR' })
    await expect(api.assignTask(taskId, { ...assignBody(), trailerId: mainId }, key)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_ERROR' })
    await expect(api.revokeDispatch(assignmentId, { expectedGeneration: 1, reason: ' \u0000 ' }, key, { taskId })).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_ERROR' })
    await expect(api.revokeDispatch(assignmentId, { expectedGeneration: 1, reason: '合成' }, key, undefined as never)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_ERROR' })
    await expect(api.assignTask(`${taskId}\n`, assignBody(), key)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_ERROR' })
    await expect(api.assignTask(taskId, assignBody(), 'invalid\n')).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_ERROR' })
    expect(fetch).not.toHaveBeenCalled()
  })

  it('supports exact bounded page/search and safe generations without expanding source policy', async () => {
    queued(ok(page([], 100000, 100)), csrf(), error(409, 'E1603', { reason: 'INVALID_STATE' }))
    const api = await import('./tmsApi')
    await api.getTmsTasks(100000, { size: 100, q: `\u001c\uFEFF ${'e\u0301'.repeat(100)} \u001f` })
    await expect(api.assignTask(taskId, { ...assignBody(), expectedGeneration: Number.MAX_SAFE_INTEGER }, key)).rejects.toMatchObject({ code: 'E1603', reason: 'INVALID_STATE', uncertain: false })
  })

  it('does not dispatch a pre-aborted command and marks a dispatched timeout uncertain', async () => {
    let fetch = queued(), api = await import('./tmsApi'), controller = new AbortController()
    controller.abort()
    await expect(api.assignTask(taskId, assignBody(), key, controller.signal)).rejects.toMatchObject({ code: 'REQUEST_ABORTED', uncertain: false })
    expect(fetch).not.toHaveBeenCalled()
    vi.resetModules()
    fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(csrf()).mockImplementationOnce((_url, options) => new Promise((_resolve, reject) => { options?.signal?.addEventListener('abort', () => reject(new DOMException('Synthetic timeout', 'AbortError')), { once: true }) }))
    vi.stubGlobal('fetch', fetch); await (await import('./api')).getCsrfHeaders(); api = await import('./tmsApi'); controller = new AbortController()
    vi.useFakeTimers()
    const pending = api.assignTask(taskId, assignBody(), key, controller.signal), assertion = expect(pending).rejects.toMatchObject({ code: 'REQUEST_ABORTED', uncertain: true })
    await vi.advanceTimersByTimeAsync(10000); await assertion
    expect(fetch).toHaveBeenCalledTimes(2)
  })
})
