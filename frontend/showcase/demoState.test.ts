import { describe, expect, it, vi } from 'vitest'
import { allTasks, DemoError, executeDemo, taskOccupied } from './demoState'
import type { DemoCommand, DemoRole, DemoState } from './demoState'
import { createDemoState, exampleDraft } from './demoFixtures'

const localId = 'order-local'
const raw = ' 合成 e\u0301\0原文\r\n第二行 '
const local = (state: DemoState) => state.orders.find(order => order.id === localId)!
const task = (state: DemoState, index = 0) => local(state).tasks[index]
function run(state: DemoState, role: DemoRole, command: DemoCommand) { return executeDemo(state, role, command) }
function accepted() { return run(createDemoState(), 'ADMIN', { kind: 'accept', orderId: localId }) }
function assigned() {
  const state = accepted()
  return run(state, 'DISPATCHER', { kind: 'assign', taskId: task(state).id, driverId: 'driver-1', mainVehicleId: 'main-1', trailerId: 'trailer-1' })
}
function departed() {
  const state = assigned()
  return run(state, 'DRIVER', { kind: 'depart', taskId: task(state).id })
}
function progress(state: DemoState, index = 0) {
  const taskId = task(state, index).id
  const withPhoto = run(state, 'DRIVER', { kind: 'photo', taskId })
  const evidence = task(withPhoto, index).photos.at(-1)!.id
  return run(withPhoto, 'DRIVER', { kind: 'progress', taskId, evidenceIds: [evidence] })
}
function awaiting() { return progress(progress(departed())) }
function completed() {
  const state = awaiting(), row = task(state)
  return run(state, 'DRIVER', { kind: 'complete', taskId: row.id, evidenceIds: row.completionEvidenceIds, safeRelease: true })
}
function needsSupplement() {
  let state = awaiting()
  state = run(state, 'DRIVER', { kind: 'report', taskId: task(state).id, text: raw })
  return run(state, 'ADMIN', { kind: 'request', taskId: task(state).id, exceptionId: task(state).exceptions[0].id, text: raw })
}
function rejectUnchanged(state: DemoState, role: DemoRole, command: DemoCommand, message?: RegExp) {
  const before = structuredClone(state)
  expect(() => run(state, role, command)).toThrow(message ?? DemoError)
  expect(state).toEqual(before)
}

function finish(state: DemoState, index: number) {
  const taskId = task(state, index).id
  state = run(state, 'DISPATCHER', { kind: 'assign', taskId, driverId: 'driver-1', mainVehicleId: 'main-1', trailerId: null })
  state = run(state, 'DRIVER', { kind: 'depart', taskId })
  state = progress(progress(state, index), index)
  return run(state, 'DRIVER', { kind: 'complete', taskId, evidenceIds: task(state, index).completionEvidenceIds, safeRelease: true })
}

describe('isolated synthetic state and accepted source snapshots', () => {
  it('creates independent IMPORT, EXPORT and LOCAL drafts without inventing accepted facts', () => {
    const a = createDemoState(), b = createDemoState()
    expect(a.orders.map(order => order.draft.type)).toEqual(['LOCAL', 'IMPORT', 'EXPORT'])
    expect(a.orders.every(order => !order.accepted && !order.closed && order.tasks.length === 0)).toBe(true)
    a.orders[0].draft.items[0].grossWeight = '42.0000'
    expect(b.orders[0].draft.items[0].grossWeight).toBe('1250.50')
  })
  it.each(['IMPORT', 'EXPORT', 'LOCAL'] as const)('edits and accepts a %s draft while preserving entered source text and decimal strings', type => {
    const initial = createDemoState(), id = `order-${type.toLowerCase()}`, draft = exampleDraft(type)
    draft.tasks[0].pickupAddress = raw
    draft.items[0].grossWeight = '0.0000000000000000001'
    const saved = run(initial, 'DISPATCHER', { kind: 'save', orderId: id, draft })
    expect(initial.orders.find(order => order.id === id)!.draft.tasks[0].pickupAddress).not.toBe(raw)
    const accepted = run(saved, 'ADMIN', { kind: 'accept', orderId: id }), order = accepted.orders.find(order => order.id === id)!
    expect(order.tasks[0].source.pickupAddress).toBe(raw)
    expect(order.tasks[0].item.grossWeight).toBe(draft.items[0].grossWeight)
    expect(order.tasks[0].source.plannedStartAt).toBe(draft.tasks[0].plannedStartAt)
    expect(order.tasks[0].phase).toBe('UNASSIGNED')
    expect(order.accepted).toBe(true)
    draft.tasks[0].pickupAddress = 'later caller mutation'
    expect(order.tasks[0].source.pickupAddress).toBe(raw)
    expect(order.draft.tasks[0].pickupAddress).toBe(raw)
    rejectUnchanged(accepted, 'ADMIN', { kind: 'save', orderId: id, draft: exampleDraft(type) }, /只有未受理/)
  })
  const missingAcceptance: { type: 'LOCAL' | 'IMPORT' | 'EXPORT'; field: string; mutate: (draft: ReturnType<typeof exampleDraft>) => void }[] = [
    { type: 'LOCAL', field: 'item sequence', mutate: draft => { draft.items[0].sequence = '' } },
    { type: 'LOCAL', field: 'item kind', mutate: draft => { draft.items[0].itemKind = '' } },
    { type: 'IMPORT', field: 'container type', mutate: draft => { draft.items[0].containerType = '' } },
    { type: 'IMPORT', field: 'last pickup date', mutate: draft => { draft.items[0].lastPickupDate = '' } },
    { type: 'EXPORT', field: 'delivery cutoff', mutate: draft => { draft.items[0].deliveryCutoffAt = '' } },
    { type: 'LOCAL', field: 'cargo package quantity', mutate: draft => { draft.items[0].packageQty = '' } },
    { type: 'IMPORT', field: 'shipping group', mutate: draft => { draft.shipping = null } },
    { type: 'IMPORT', field: 'arrival time', mutate: draft => { draft.shipping!.eta = '' } },
    { type: 'IMPORT', field: 'bill of lading', mutate: draft => { draft.shipping!.blNo = '' } },
    { type: 'EXPORT', field: 'departure time', mutate: draft => { draft.shipping!.etd = '' } },
    ...(['LOCAL', 'IMPORT', 'EXPORT'] as const).map(type => ({ type, field: 'explicit necessary task', mutate: (draft: ReturnType<typeof exampleDraft>) => { draft.tasks[0].requiredTask = '' as const } })),
    ...(['IMPORT', 'EXPORT'] as const).flatMap(type => (['shippingCompany', 'vesselVoyage', 'pol', 'pod'] as const).map(field => ({ type, field, mutate: (draft: ReturnType<typeof exampleDraft>) => { draft.shipping![field] = '' } }))),
  ]
  it.each(missingAcceptance)('does not accept $type with missing $field', ({ type, mutate }) => {
    const state = createDemoState(), orderId = `order-${type.toLowerCase()}`
    mutate(state.orders.find(order => order.id === orderId)!.draft)
    rejectUnchanged(state, 'ADMIN', { kind: 'accept', orderId })
    expect(state.orders.find(order => order.id === orderId)!.tasks).toEqual([])
  })
  it.each(['IMPORT', 'EXPORT'] as const)('allows %s general cargo without inventing a domestic package-quantity requirement', type => {
    const state = createDemoState(), orderId = `order-${type.toLowerCase()}`, order = state.orders.find(value => value.id === orderId)!
    order.draft.items[0].itemKind = 'CARGO'
    order.draft.items[0].containerType = ''
    order.draft.items[0].packageQty = ''
    const accepted = run(state, 'DISPATCHER', { kind: 'accept', orderId }).orders.find(value => value.id === orderId)!
    expect(accepted.accepted).toBe(true)
    expect(accepted.tasks[0].item.packageQty).toBe('')
    expect(accepted.tasks[0].source.requiredTask).toBe('true')
  })
  it('creates a new draft independently without reusing a seed order identifier', () => {
    const initial = createDemoState(), created = run(initial, 'ADMIN', { kind: 'save', orderId: null, draft: exampleDraft() })
    expect(created.orders).toHaveLength(initial.orders.length + 1)
    expect(initial.orders).toHaveLength(3)
    expect(created.orders[0].id).not.toBe(localId)
    expect(created.orders[0].tasks).toEqual([])
  })
  it('rejects incomplete acceptance and keeps the original draft intact', () => {
    const state = createDemoState()
    local(state).draft.tasks[0].deliveryAddress = ''
    rejectUnchanged(state, 'ADMIN', { kind: 'accept', orderId: localId }, /補齊/)
    local(state).draft.tasks = []
    rejectUnchanged(state, 'ADMIN', { kind: 'accept', orderId: localId }, /至少/)
  })
  it.each(['self', 'missing', 'cycle'] as const)('rejects %s predecessor graphs without creating task snapshots', kind => {
    const state = createDemoState(), draft = local(state).draft
    draft.tasks.push({ ...structuredClone(draft.tasks[0]), clientRef: 'second-task', predecessorRefs: [] })
    if (kind === 'self') draft.tasks[0].predecessorRefs = [draft.tasks[0].clientRef]
    else if (kind === 'missing') draft.tasks[0].predecessorRefs = ['not-in-this-order']
    else { draft.tasks[0].predecessorRefs = ['second-task']; draft.tasks[1].predecessorRefs = [draft.tasks[0].clientRef] }
    const before = structuredClone(state)
    expect(() => run(state, 'ADMIN', { kind: 'accept', orderId: localId })).toThrow()
    expect(state).toEqual(before)
    expect(local(state).tasks).toEqual([])
  })
  it('rejects double acceptance rather than recreating task identities', () => {
    const state = accepted()
    rejectUnchanged(state, 'DISPATCHER', { kind: 'accept', orderId: localId }, /已受理/)
  })
})

describe('demonstration role boundaries remain in the model', () => {
  const managerCommands: DemoCommand[] = [
    { kind: 'save', orderId: null, draft: exampleDraft() }, { kind: 'accept', orderId: localId },
    { kind: 'assign', taskId: 'task-10', driverId: 'driver-1', mainVehicleId: 'main-1', trailerId: null },
    { kind: 'request', taskId: 'task-10', exceptionId: 'example', text: raw },
    { kind: 'resolve', taskId: 'task-10', exceptionId: 'example', text: raw },
    { kind: 'confirm', taskId: 'task-10', text: raw }, { kind: 'close', orderId: localId, text: raw },
  ]
  it.each(managerCommands)('DRIVER cannot execute $kind by calling the model directly', command => {
    rejectUnchanged(createDemoState(), 'DRIVER', command, /展示角色/)
  })
  it.each(['ADMIN', 'DISPATCHER'] as const)('%s cannot impersonate a driver operation', role => {
    const state = needsSupplement(), row = task(state), exception = row.exceptions[0]
    const commands: DemoCommand[] = [
      { kind: 'depart', taskId: row.id }, { kind: 'photo', taskId: row.id }, { kind: 'progress', taskId: row.id, evidenceIds: [] },
      { kind: 'report', taskId: row.id, text: raw }, { kind: 'supplementPhoto', taskId: row.id, exceptionId: exception.id },
      { kind: 'submit', taskId: row.id, exceptionId: exception.id, text: raw, evidenceIds: [] },
      { kind: 'complete', taskId: row.id, evidenceIds: row.completionEvidenceIds, safeRelease: true },
    ]
    for (const command of commands) rejectUnchanged(state, role, command, /展示角色/)
  })
  it('does not expose a support driver task to the synthetic current driver', () => {
    const state = accepted(), assignedToOther = run(state, 'ADMIN', { kind: 'assign', taskId: task(state).id, driverId: 'driver-2', mainVehicleId: 'main-1', trailerId: null })
    rejectUnchanged(assignedToOther, 'DRIVER', { kind: 'depart', taskId: task(assignedToOther).id }, /本人運輸任務/)
  })
})

describe('dispatch prerequisites and effective resource occupations', () => {
  it('keeps a successor unassigned until its actual predecessor completes, then permits released resources', () => {
    const initial = createDemoState(), draft = local(initial).draft
    draft.tasks.push({ ...structuredClone(draft.tasks[0]), clientRef: 'successor', predecessorRefs: [draft.tasks[0].clientRef], requiredTask: '' })
    let state = run(initial, 'ADMIN', { kind: 'accept', orderId: localId })
    const successorId = task(state, 1).id
    rejectUnchanged(state, 'DISPATCHER', { kind: 'assign', taskId: successorId, driverId: 'driver-1', mainVehicleId: 'main-1', trailerId: null }, /前置任務尚未完成/)
    state = finish(state, 0)
    expect(taskOccupied(task(state))).toBe(false)
    state = run(state, 'DISPATCHER', { kind: 'assign', taskId: successorId, driverId: 'driver-1', mainVehicleId: 'main-1', trailerId: null })
    expect(task(state, 1).phase).toBe('WAITING')
    expect(taskOccupied(task(state, 1))).toBe(true)
  })
  it('does not silently replace an existing assignment', () => {
    const state = assigned()
    rejectUnchanged(state, 'ADMIN', { kind: 'assign', taskId: task(state).id, driverId: 'driver-2', mainVehicleId: 'main-2', trailerId: null }, /不能重複指派/)
  })
  it.each(['driver', 'main', 'trailer'] as const)('blocks a cross-order occupied %s even when the other selected resources are free', resource => {
    let state = assigned()
    state = run(state, 'ADMIN', { kind: 'accept', orderId: 'order-import' })
    const other = state.orders.find(order => order.id === 'order-import')!.tasks[0]
    rejectUnchanged(state, 'DISPATCHER', { kind: 'assign', taskId: other.id, driverId: resource === 'driver' ? 'driver-1' : 'driver-2', mainVehicleId: resource === 'main' ? 'main-1' : 'main-2', trailerId: resource === 'trailer' ? 'trailer-1' : null }, /有效占用/)
    expect(allTasks(state).filter(taskOccupied)).toHaveLength(1)
  })
  it.each([
    { driverId: 'unknown', mainVehicleId: 'main-1', trailerId: null },
    { driverId: 'driver-1', mainVehicleId: 'trailer-1', trailerId: null },
    { driverId: 'driver-1', mainVehicleId: 'main-1', trailerId: 'main-2' },
  ])('rejects a non-candidate or wrong resource position $mainVehicleId/$trailerId', choice => {
    const state = accepted()
    rejectUnchanged(state, 'ADMIN', { kind: 'assign', taskId: task(state).id, ...choice })
  })
})

describe('photos, progress and supplement request scope are independent', () => {
  it('adding a built-in photograph does not advance progress, and an old phase photograph cannot advance the next phase', () => {
    let state = departed()
    state = run(state, 'DRIVER', { kind: 'photo', taskId: task(state).id })
    const oldId = task(state).photos[0].id
    expect(task(state).phase).toBe('DEPARTED')
    state = run(state, 'DRIVER', { kind: 'progress', taskId: task(state).id, evidenceIds: [oldId] })
    expect(task(state).phase).toBe('IN_PROGRESS')
    rejectUnchanged(state, 'DRIVER', { kind: 'progress', taskId: task(state).id, evidenceIds: [oldId] }, /本階段/)
  })
  it('requires an explicit unique nonempty photograph selection', () => {
    const state = run(departed(), 'DRIVER', { kind: 'photo', taskId: task(departed()).id }), id = task(state).photos[0].id
    for (const evidenceIds of [[], [id, id], ['foreign-photo']]) rejectUnchanged(state, 'DRIVER', { kind: 'progress', taskId: task(state).id, evidenceIds }, /明確選取/)
  })
  it('does not offer ordinary uploads while awaiting completion, but allows current-request supplements', () => {
    let state = needsSupplement(), row = task(state)
    const exceptionId = row.exceptions[0].id
    rejectUnchanged(state, 'DRIVER', { kind: 'photo', taskId: row.id }, /普通任務照片/)
    state = run(state, 'DRIVER', { kind: 'supplementPhoto', taskId: row.id, exceptionId })
    row = task(state)
    const photo = row.photos.at(-1)!, request = row.exceptions[0].request!
    expect(photo.phase).toBe('AWAITING_COMPLETION')
    expect(photo.requestId).toBe(request.id)
    expect(request.evidenceIds).toEqual([photo.id])
    expect(row.phase).toBe('AWAITING_COMPLETION')
    expect(row.exceptions[0].status).toBe('NEEDS_SUPPLEMENT')
  })
  it('keeps raw report, request, note and resolution text exact while supplement submission remains OPEN', () => {
    let state = needsSupplement(), row = task(state)
    const exceptionId = row.exceptions[0].id
    const originalSet = [...row.completionEvidenceIds]
    state = run(state, 'DRIVER', { kind: 'supplementPhoto', taskId: row.id, exceptionId })
    row = task(state)
    state = run(state, 'DRIVER', { kind: 'submit', taskId: row.id, exceptionId, text: raw, evidenceIds: row.exceptions[0].request!.evidenceIds })
    row = task(state)
    expect(row.exceptions[0].status).toBe('OPEN')
    expect(row.exceptions[0].description).toBe(raw)
    expect(row.exceptions[0].request).toBeNull()
    expect(row.exceptions[0].history).toContain(`要求補件：${raw}`)
    expect(row.exceptions[0].history).toContain(`本人補件提交：${raw}`)
    expect(row.completionEvidenceIds).toEqual(originalSet)
    rejectUnchanged(state, 'DRIVER', { kind: 'complete', taskId: row.id, evidenceIds: originalSet, safeRelease: true }, /未處置異常/)
    state = run(state, 'DISPATCHER', { kind: 'resolve', taskId: row.id, exceptionId, text: raw })
    expect(task(state).exceptions[0].resolution).toBe(raw)
    expect(task(state).phase).toBe('AWAITING_COMPLETION')
  })
  it('cannot resolve a new report before requesting and receiving an explicit supplement submission', () => {
    const original = awaiting()
    const state = run(original, 'DRIVER', { kind: 'report', taskId: task(original).id, text: raw }), row = task(state)
    rejectUnchanged(state, 'ADMIN', { kind: 'resolve', taskId: row.id, exceptionId: row.exceptions[0].id, text: raw }, /補件/)
    expect(row.exceptions[0].status).toBe('OPEN')
  })
  it('cannot resolve uploaded supplements before the driver explicitly submits them', () => {
    const initial = needsSupplement(), taskId = task(initial).id, exceptionId = task(initial).exceptions[0].id
    const state = run(initial, 'DRIVER', { kind: 'supplementPhoto', taskId, exceptionId })
    rejectUnchanged(state, 'DISPATCHER', { kind: 'resolve', taskId, exceptionId, text: raw }, /補件/)
    expect(task(state).exceptions[0].status).toBe('NEEDS_SUPPLEMENT')
    expect(task(state).exceptions[0].submittedEvidenceIds).toEqual([])
  })
  it('cannot submit a past request photograph against a newer supplement request', () => {
    let state = needsSupplement(), row = task(state)
    const exceptionId = row.exceptions[0].id
    state = run(state, 'DRIVER', { kind: 'supplementPhoto', taskId: row.id, exceptionId })
    row = task(state)
    const previousPhoto = row.exceptions[0].request!.evidenceIds[0], previousRequest = row.exceptions[0].request!.id
    state = run(state, 'DRIVER', { kind: 'submit', taskId: row.id, exceptionId, text: raw, evidenceIds: [previousPhoto] })
    state = run(state, 'ADMIN', { kind: 'request', taskId: row.id, exceptionId, text: ' 合成第二次要求 ' })
    expect(task(state).exceptions[0].request!.id).not.toBe(previousRequest)
    rejectUnchanged(state, 'DRIVER', { kind: 'submit', taskId: row.id, exceptionId, text: raw, evidenceIds: [previousPhoto] }, /這一次補件要求/)
  })
  it('rejects a foreign exception identifier without changing the task', () => {
    const state = needsSupplement()
    rejectUnchanged(state, 'DRIVER', { kind: 'supplementPhoto', taskId: task(state).id, exceptionId: 'different-task-exception' }, /這個任務/)
  })
})

describe('safe release, independent management confirmation and order closure', () => {
  it('requires the exact latest ordinary evidence set and an explicit release confirmation', () => {
    const state = awaiting(), row = task(state)
    rejectUnchanged(state, 'DRIVER', { kind: 'complete', taskId: row.id, evidenceIds: row.completionEvidenceIds, safeRelease: false }, /明確確認/)
    for (const evidenceIds of [[], [row.photos[0].id], [...row.completionEvidenceIds, ...row.completionEvidenceIds]]) rejectUnchanged(state, 'DRIVER', { kind: 'complete', taskId: row.id, evidenceIds, safeRelease: true }, /完整照片集合/)
  })
  it('releases occupations on task completion, without automatically confirming management or closing the order', () => {
    const state = completed(), row = task(state)
    expect(taskOccupied(row)).toBe(false)
    expect(row.confirmed).toBe(false)
    expect(local(state).closed).toBe(false)
    rejectUnchanged(state, 'ADMIN', { kind: 'close', orderId: localId, text: raw }, /逐一取得管理確認/)
    rejectUnchanged(state, 'DRIVER', { kind: 'complete', taskId: row.id, evidenceIds: row.completionEvidenceIds, safeRelease: true }, /先到待完成確認/)
  })
  it('records management confirmation once, then a separate closure with raw reasons', () => {
    let state = completed()
    const row = task(state)
    state = run(state, 'DISPATCHER', { kind: 'confirm', taskId: row.id, text: raw })
    expect(local(state).closed).toBe(false)
    expect(task(state).history).toContain(`展示管理確認：${raw}`)
    rejectUnchanged(state, 'ADMIN', { kind: 'confirm', taskId: row.id, text: raw }, /尚未管理確認/)
    state = run(state, 'ADMIN', { kind: 'close', orderId: localId, text: raw })
    expect(local(state).closed).toBe(true)
    expect(local(state).history).toContain(`展示結案：${raw}`)
    rejectUnchanged(state, 'ADMIN', { kind: 'close', orderId: localId, text: raw }, /不可結案/)
    rejectUnchanged(state, 'DRIVER', { kind: 'report', taskId: row.id, text: raw }, /目前可操作/)
  })
  it('requires every persisted task including an unknown necessary marker to complete and be confirmed', () => {
    const initial = createDemoState(), draft = local(initial).draft
    draft.tasks.push({ ...structuredClone(draft.tasks[0]), clientRef: 'unknown-necessary', requiredTask: '', predecessorRefs: [] })
    let state = run(initial, 'ADMIN', { kind: 'accept', orderId: localId })
    state = finish(state, 0)
    state = run(state, 'ADMIN', { kind: 'confirm', taskId: task(state).id, text: raw })
    rejectUnchanged(state, 'DISPATCHER', { kind: 'close', orderId: localId, text: raw }, /所有任務/)
    state = finish(state, 1)
    rejectUnchanged(state, 'DISPATCHER', { kind: 'close', orderId: localId, text: raw }, /所有任務/)
    state = run(state, 'DISPATCHER', { kind: 'confirm', taskId: task(state, 1).id, text: raw })
    expect(run(state, 'ADMIN', { kind: 'close', orderId: localId, text: raw }).orders.find(order => order.id === localId)!.closed).toBe(true)
  })
  it('reseeds only memory and leaves unrelated browser storage untouched', () => {
    const localKey = 'demo-model-test-foreign-local', sessionKey = 'demo-model-test-foreign-session'
    localStorage.setItem(localKey, 'foreign-local-value')
    sessionStorage.setItem(sessionKey, 'foreign-session-value')
    const reads = [vi.spyOn(Storage.prototype, 'getItem'), vi.spyOn(Storage.prototype, 'setItem'), vi.spyOn(Storage.prototype, 'removeItem'), vi.spyOn(Storage.prototype, 'clear')]
    try {
      const old = completed(), reset = createDemoState()
      expect(old.orders[0].accepted).toBe(true)
      expect(reset.orders.every(order => !order.accepted && !order.closed && !order.tasks.length)).toBe(true)
      expect(reset).toEqual(createDemoState())
      for (const read of reads) expect(read).not.toHaveBeenCalled()
      for (const read of reads) read.mockRestore()
      expect(localStorage.getItem(localKey)).toBe('foreign-local-value')
      expect(sessionStorage.getItem(sessionKey)).toBe('foreign-session-value')
    } finally {
      for (const read of reads) read.mockRestore()
      localStorage.removeItem(localKey)
      sessionStorage.removeItem(sessionKey)
    }
  })
})
