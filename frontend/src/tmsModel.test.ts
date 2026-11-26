import { describe, expect, it } from 'vitest'
import { ApiError } from './api'
import { TmsApiError } from './tmsApi'
import type { Assignment, DispatchDetail, ResourceCandidate, TaskSummary } from './tmsApi'
import { candidateSelectable, commandResources, immutableSnapshot, makeDispatchCommand, normalizeTmsText, safeExitRequired, settlesPriorDispatch, sourceAllowsNewDispatch } from './tmsModel'

const id = (number: number) => `00000000-0000-4000-8000-${String(number).padStart(12, '0')}`
const taskId = id(1), orderId = id(2), assignmentId = id(3), driverId = id(4), mainId = id(5), trailerId = id(6), actorId = id(7)
const time = '2026-11-26T12:00:00+08:00'
function assignment(): Assignment {
  return { id: assignmentId, taskId, orderId, status: 'ASSIGNED', version: 0, assignedGeneration: 7, endedGeneration: null,
    driver: { id: driverId, driverCode: 'SYNTHETIC DRIVER', name: '合成司機', version: 3 }, mainVehicle: { id: mainId, plateNo: 'SYNTHETIC MAIN', vehicleType: id(8), vehicleTypeCode: 'SYNTHETIC_MAIN', vehicleTypeLabel: '合成主車', version: 2 }, trailer: { id: trailerId, plateNo: 'SYNTHETIC TRAILER', vehicleType: id(9), vehicleTypeCode: 'SYNTHETIC_TRAILER', vehicleTypeLabel: '合成拖板', version: 2 },
    source: { taskId, taskNo: 'SYNTHETIC-TASK', orderId, orderNo: 'SYNTHETIC-ORDER', orderType: 'LOCAL', orderVersion: 2, item: { itemKind: 'CARGO', containerType: null, containerNo: null, packageQty: 1, grossWeight: null, cbm: null }, taskKind: null, pickupName: '合成起點', pickupAddress: '合成起點地址', deliveryName: '合成終點', deliveryAddress: '合成終點地址', siteContact: null, plannedStartAt: null, plannedEndAt: null, requiredTask: true }, policyMode: 'SYNTHETIC_TEST_ONLY', assignedBy: actorId, assignedAt: time, assignmentReason: null, endedBy: null, endedAt: null, endReason: null, previousAssignmentId: null,
    reservations: [{ id: id(10), kind: 'DRIVER', position: 'DRIVER', resourceId: driverId, acquiredAt: time, releasedAt: null, releaseReason: null }, { id: id(11), kind: 'VEHICLE', position: 'MAIN', resourceId: mainId, acquiredAt: time, releasedAt: null, releaseReason: null }, { id: id(12), kind: 'VEHICLE', position: 'TRAILER', resourceId: trailerId, acquiredAt: time, releasedAt: null, releaseReason: null }], events: [{ id: id(13), action: 'ASSIGNED', assignmentId, previousAssignmentId: null, fromGeneration: 6, toGeneration: 7, actorId, occurredAt: time, reason: null }] }
}
function detail(current: Assignment | null = null, task: Partial<TaskSummary> = {}): DispatchDetail {
  return { task: { taskId, taskNo: 'SYNTHETIC-TASK', orderId, orderNo: 'SYNTHETIC-ORDER', orderType: 'LOCAL', orderVersion: 2, generation: current?.assignedGeneration ?? 0, taskKind: null, pickupName: '合成起點', deliveryName: '合成終點', prerequisiteReady: true, currentAssignmentId: current?.id ?? null, policyMode: 'SYNTHETIC_TEST_ONLY', blockingReasons: current ? ['ALREADY_ASSIGNED'] : [], ...task }, current, history: { content: current ? [current] : [], page: 0, size: 20, totalElements: current ? 1 : 0, totalPages: current ? 1 : 0 } }
}
function selection() { return { driverId, mainVehicleId: mainId, trailerId: '', reason: '合成操作原因' } }
function candidate(override: Partial<ResourceCandidate> = {}): ResourceCandidate { return { id: driverId, kind: 'DRIVER', position: 'DRIVER', code: 'SYNTHETIC DRIVER', label: '合成司機', version: 3, administrativelyAvailable: true, occupied: false, occupation: null, policyMode: 'SYNTHETIC_TEST_ONLY', policyApproved: true, blockingReasons: [], ...override } }
function failure(action: () => unknown): TmsApiError { let caught: unknown; try { action() } catch (error) { caught = error } expect(caught).toBeInstanceOf(TmsApiError); return caught as TmsApiError }

describe('dispatch authorization and uncertainty boundaries', () => {
  it.each([null, 'FORBIDDEN', 'UNKNOWN_AUTH_STATE'])('requires a safe exit for403reason %s instead of guessing a CSRF failure', (reason) => {
    expect(safeExitRequired(new TmsApiError(403, 'E0403', '相同的合成說明', { reason }))).toBe(true)
  })
  it('keeps a genuine CSRF failure pending while treating401 as authentication loss', () => {
    expect(safeExitRequired(new TmsApiError(403, 'E0403', '權限不足或安全驗證失效', { reason: 'CSRF_INVALID' }))).toBe(false)
    expect(safeExitRequired(new TmsApiError(401, 'E0401', '登入失效'))).toBe(true)
    expect(safeExitRequired(new TmsApiError(500, 'E0500', 'FORBIDDEN'))).toBe(false)
    expect(safeExitRequired(new Error('401 FORBIDDEN CSRF_INVALID'))).toBe(false)
  })

  it.each([
    ['E1601', 'VERSION_CONFLICT'], ['E1602', 'RESOURCE_OCCUPIED'], ['E1603', 'INVALID_STATE'], ['E1604', 'PREREQUISITE_NOT_READY'], ['E1605', 'POLICY_NOT_CONFIRMED'], ['E1606', 'MASTER_UNAVAILABLE'], ['E1609', 'PROTECTED_RELATION'],
  ] as const)('settles prior uncertainty only for the known post-replay409pair %s/%s', (code, reason) => {
    expect(settlesPriorDispatch(new TmsApiError(409, code, '合成拒絕', { reason }))).toBe(true)
    expect(settlesPriorDispatch(new TmsApiError(409, code, '合成拒絕', { reason, uncertain: true }))).toBe(false)
    expect(settlesPriorDispatch(new TmsApiError(400, code, '合成拒絕', { reason }))).toBe(false)
    expect(settlesPriorDispatch(new TmsApiError(409, code, '合成拒絕', { reason: 'UNRECOGNIZED_REASON' }))).toBe(false)
  })

  it('does not settle protocol/auth/shape/no-op/key/in-progress errors or infer settlement from messages', () => {
    const errors = [new TmsApiError(409, 'E1607', '重送識別已被使用', { reason: 'IDEMPOTENCY_KEY_REUSED' }), new TmsApiError(409, 'E1608', '仍在處理', { reason: 'IDEMPOTENCY_IN_PROGRESS' }),
      new TmsApiError(400, 'E0400', 'no-op', { reason: 'VALIDATION_ERROR', fields: { resources: '三項資源不可相同' } }), new TmsApiError(400, 'E0400', '格式錯誤', { reason: 'VALIDATION_ERROR', fields: { expectedGeneration: '版本格式錯誤' } }),
      new TmsApiError(403, 'E0403', 'CSRF錯誤', { reason: 'CSRF_INVALID' }), new TmsApiError(401, 'E0401', '登入失效'), new TmsApiError(404, 'E0404', '找不到'), new TmsApiError(405, 'E0405', '方法錯誤'), new TmsApiError(415, 'E0415', '媒體錯誤'),
      new TmsApiError(500, 'E0500', 'VERSION_CONFLICT', { reason: 'VERSION_CONFLICT' }), new TmsApiError(409, 'INVALID_RESPONSE', 'VERSION_CONFLICT', { reason: 'VERSION_CONFLICT' }), new ApiError(409, '版本衝突'), new Error('資料已更新')]
    for (const error of errors) expect(settlesPriorDispatch(error)).toBe(false)
  })
})

describe('production policy and administrative source separation', () => {
  it('never permits a new production assignment even when a task has no listed blockers', () => {
    const unconfirmed = detail(null, { policyMode: 'UNCONFIRMED', blockingReasons: [] })
    expect(sourceAllowsNewDispatch(unconfirmed.task)).toBe(false)
    expect(failure(() => makeDispatchCommand('assign', unconfirmed, selection())).fields).toHaveProperty('policy')
  })

  it('permits synthetic reassign only when its sole blocker is the existing assignment', () => {
    const task = detail(assignment()).task
    expect(sourceAllowsNewDispatch(task)).toBe(false)
    expect(sourceAllowsNewDispatch(task, true)).toBe(true)
    expect(sourceAllowsNewDispatch({ ...task, prerequisiteReady: false, blockingReasons: [] }, true)).toBe(false)
    expect(sourceAllowsNewDispatch({ ...task, blockingReasons: ['ALREADY_ASSIGNED', 'SOURCE_NOT_ACCEPTED'] }, true)).toBe(false)
    expect(sourceAllowsNewDispatch({ ...task, policyMode: 'UNCONFIRMED' }, true)).toBe(false)
  })

  it('allows release of an assigned target despite production policy, blocked source or prerequisite', () => {
    const blocked = detail(assignment(), { policyMode: 'UNCONFIRMED', prerequisiteReady: false, blockingReasons: ['POLICY_NOT_CONFIRMED', 'PREREQUISITE_NOT_READY', 'SOURCE_NOT_ACCEPTED', 'ALREADY_ASSIGNED'] })
    expect(makeDispatchCommand('revoke', blocked, selection())).toEqual({ kind: 'revoke', taskId, assignmentId, body: { expectedGeneration: 7, reason: '合成操作原因' } })
    expect(failure(() => makeDispatchCommand('reassign', blocked, { ...selection(), driverId: id(90) })).fields).toHaveProperty('policy')
  })

  it('requires the appropriate current assignment state before creating a command', () => {
    expect(failure(() => makeDispatchCommand('assign', detail(assignment()), selection())).fields).toHaveProperty('assignmentId')
    expect(failure(() => makeDispatchCommand('revoke', detail(), selection())).fields).toHaveProperty('assignmentId')
    const terminal = { ...assignment(), status: 'REVOKED' as const, version: 1 as const, endedGeneration: 8, endedBy: actorId, endedAt: time, endReason: '合成撤派' }
    expect(failure(() => makeDispatchCommand('reassign', detail(terminal), selection())).fields).toHaveProperty('assignmentId')
  })
})

describe('resource combination and generation', () => {
  it('uses task generation instead of assignment version0/1 and explicitly clears an optional trailer', () => {
    const current = assignment(), resources = commandResources(current)
    expect(resources).toEqual({ driverId, mainVehicleId: mainId, trailerId })
    const command = makeDispatchCommand('reassign', detail(current), { ...resources, trailerId: '', reason: '明確移除合成拖板' })
    expect(command).toEqual({ kind: 'reassign', taskId, assignmentId, body: { expectedGeneration: 7, driverId, mainVehicleId: mainId, trailerId: null, reason: '明確移除合成拖板' } })
    expect(current.version).toBe(0)
    expect(current.trailer?.id).toBe(trailerId)
  })

  it('rejects same-triple reassign and main/trailer reuse without changing the selected resources', () => {
    const current = assignment(), unchanged = { ...commandResources(current), reason: '合成改派' }
    expect(failure(() => makeDispatchCommand('reassign', detail(current), unchanged)).fields).toHaveProperty('resources')
    expect(failure(() => makeDispatchCommand('assign', detail(), { ...selection(), trailerId: mainId })).fields).toHaveProperty('trailerId')
    expect(makeDispatchCommand('reassign', detail(current), { ...unchanged, driverId: id(91) }).body).toMatchObject({ driverId: id(91), mainVehicleId: mainId, trailerId })
    expect(unchanged).toEqual({ driverId, mainVehicleId: mainId, trailerId, reason: '合成改派' })
  })

  it.each([-1, 0.5, Number.MAX_SAFE_INTEGER, Number.MAX_SAFE_INTEGER + 1, NaN, Infinity])('rejects a generation that cannot safely advance: %s', (generation) => {
    expect(failure(() => makeDispatchCommand('assign', detail(null, { generation }), selection())).fields).toHaveProperty('expectedGeneration')
  })

  it('allows exact zero and the last generation that can safely advance', () => {
    expect(makeDispatchCommand('assign', detail(), selection()).body.expectedGeneration).toBe(0)
    expect(makeDispatchCommand('assign', detail(null, { generation: Number.MAX_SAFE_INTEGER - 1 }), selection()).body.expectedGeneration).toBe(Number.MAX_SAFE_INTEGER - 1)
  })

  it('requires current-assignment and task context to reuse occupied resources', () => {
    const own = candidate({ occupied: true, occupation: { assignmentId, taskId } })
    expect(candidateSelectable(own, taskId, assignmentId)).toBe(true)
    expect(candidateSelectable(own, taskId)).toBe(false)
    expect(candidateSelectable(own, taskId, id(92))).toBe(false)
    expect(candidateSelectable(own, id(93), assignmentId)).toBe(false)
    expect(candidateSelectable({ ...own, blockingReasons: ['RESOURCE_OCCUPIED'] }, taskId, assignmentId)).toBe(false)
  })

  it('does not infer approval from synthetic-looking labels, administrative availability or missing blocker text', () => {
    expect(candidateSelectable(candidate(), taskId)).toBe(true)
    expect(candidateSelectable(candidate({ policyMode: 'UNCONFIRMED', policyApproved: true }), taskId)).toBe(false)
    expect(candidateSelectable(candidate({ policyApproved: false }), taskId)).toBe(false)
    expect(candidateSelectable(candidate({ administrativelyAvailable: false }), taskId)).toBe(false)
    expect(candidateSelectable(candidate({ blockingReasons: ['POLICY_NOT_CONFIRMED'] }), taskId)).toBe(false)
  })
})

describe('canonical reason validation and original-request snapshot', () => {
  it('validates after Java-compatible Unicode boundary stripping/NFC but preserves the raw reason in the body', () => {
    const raw = '\u001c\uFEFF  e\u0301\n\t合成\r\n原因  \u001f'
    expect(normalizeTmsText(raw)).toBe('é\n\t合成\r\n原因')
    const command = makeDispatchCommand('revoke', detail(assignment()), { ...selection(), reason: raw })
    expect(command.body).toEqual({ expectedGeneration: 7, reason: raw })
    expect(normalizeTmsText('\u0085')).toBe('\u0085')
  })

  it('counts canonical Unicode codepoints rather than raw padding, NFD or UTF16 units', () => {
    const raw = `\uFEFF ${'e\u0301'.repeat(500)} \u001f`
    expect(makeDispatchCommand('revoke', detail(assignment()), { ...selection(), reason: raw }).body).toMatchObject({ reason: raw })
    expect(makeDispatchCommand('revoke', detail(assignment()), { ...selection(), reason: '🚛'.repeat(500) }).body).toMatchObject({ reason: '🚛'.repeat(500) })
    expect(failure(() => makeDispatchCommand('revoke', detail(assignment()), { ...selection(), reason: '🚛'.repeat(501) })).fields).toHaveProperty('reason')
  })

  it.each(['  \uFEFF\u001c  ', '合成\u0000原因', '\u0085合成', '合成\u007f原因', '\uD800', '\uDC00'])('rejects empty/control/unpaired reason %j', (reason) => {
    expect(failure(() => makeDispatchCommand('revoke', detail(assignment()), { ...selection(), reason })).fields).toHaveProperty('reason')
  })

  it('deep clones/freezes the operation, key, generation and raw reason for exact retries', () => {
    const raw = '  e\u0301\r\n合成待確認原因  ', command = makeDispatchCommand('revoke', detail(assignment()), { ...selection(), reason: raw })
    const mutable = { ...command, key: 'synthetic-original-key' }, snapshot = immutableSnapshot(mutable), firstWire = JSON.stringify(snapshot.body)
    mutable.key = 'synthetic-new-key'; mutable.body.expectedGeneration = 88
    if ('reason' in mutable.body) mutable.body.reason = 'new reason'
    expect(snapshot).toMatchObject({ kind: 'revoke', taskId, assignmentId, key: 'synthetic-original-key', body: { expectedGeneration: 7, reason: raw } })
    expect(JSON.stringify(snapshot.body)).toBe(firstWire)
    expect(Object.isFrozen(snapshot)).toBe(true)
    expect(Object.isFrozen(snapshot.body)).toBe(true)
    expect(() => { snapshot.body.expectedGeneration = 9 }).toThrow(TypeError)
  })
})
