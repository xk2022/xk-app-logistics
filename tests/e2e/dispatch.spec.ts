import { randomUUID } from 'node:crypto'
import { writeFileSync } from 'node:fs'
import { isDeepStrictEqual } from 'node:util'
import { expect, test, type Browser, type BrowserContext, type Page, type Response, type TestInfo } from '@playwright/test'
import { dispatchFixtureFor, signIn, type Credential } from './fixtures'

// API acceptance only. The complete dispatch workbench is a later work package.
// Every project owns its actors, types, and synthetic records. Preparation and
// runtime startup are explicit external operations, never hidden in this suite.
test.describe.configure({ retries: 0 })
type Body = Record<string, unknown>
interface PageData<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }
interface Reply<T> { status: number; code: string; data: T; payload: Body; replayed: string | null }
interface Customer { id: string }
interface Driver { id: string; driverCode: string; name: string; accountId: string | null; version: number }
interface Vehicle { id: string; plateNo: string; version: number }
interface Order { id: string; version: number; acceptanceStatus: string; tasks: { id: string; clientRef: string }[] }
interface TaskSummary { taskId: string; generation: number; currentAssignmentId: string | null; blockingReasons: string[]; policyMode: string }
interface Reservation { resourceId: string; position: string; releasedAt: string | null }
interface Assignment extends Body {
  id: string; taskId: string; status: string; version: number; assignedGeneration: number; endedGeneration: number | null
  driver: { id: string; name: string }; mainVehicle: { id: string }; trailer: { id: string } | null
  source: Body; policyMode: string; endReason: string | null; reservations: Reservation[]
}
interface Command { taskId: string; generation: number; assignment: Assignment }
interface Detail { task: TaskSummary; current: Assignment | null; history: PageData<Assignment> }
interface Candidate { id: string; policyApproved: boolean; occupied: boolean; administrativelyAvailable: boolean; blockingReasons: string[] }
interface MyTask extends Body { assignmentId: string; taskId: string; taskNo: string; policyMode: string }
interface Account { id: string; role: string; displayName: string; enabled: boolean; version: number }
type Schema = 'TaskPageEnvelope' | 'DispatchDetailEnvelope' | 'CommandResultEnvelope' | 'ResourcePageEnvelope' | 'MyTaskPageEnvelope' | 'ErrorEnvelope'
interface Captured { schema: Schema; value: unknown }

class ResponseCollector {
  readonly values: Captured[] = []
  private readonly pages = new Set<Page>()
  private readonly pending = new Set<Promise<void>>()
  private readonly capture = (response: Response) => {
    const path = new URL(response.url()).pathname
    const method = response.request().method()
    let schema: Schema | undefined
    if (path === '/api/v1/tms/tasks' && method === 'GET') schema = 'TaskPageEnvelope'
    else if (/^\/api\/v1\/tms\/tasks\/[0-9a-f-]{36}\/dispatch$/i.test(path)) schema = method === 'GET' ? 'DispatchDetailEnvelope' : method === 'POST' ? 'CommandResultEnvelope' : undefined
    else if (/^\/api\/v1\/tms\/dispatches\/[0-9a-f-]{36}\/(revoke|reassign)$/i.test(path) && method === 'POST') schema = 'CommandResultEnvelope'
    else if (path === '/api/v1/tms/resources' && method === 'GET') schema = 'ResourcePageEnvelope'
    else if (path === '/api/v1/tms/my-tasks' && method === 'GET') schema = 'MyTaskPageEnvelope'
    if (!schema) return
    const responseSchema = response.status() >= 400 ? 'ErrorEnvelope' : schema
    const pending = (async () => {
      try { this.values.push({ schema: responseSchema, value: await response.json() }) }
      catch { /* Unreadable responses do not count as captured HTTP evidence. */ }
    })()
    this.pending.add(pending)
    void pending.then(() => this.pending.delete(pending), () => this.pending.delete(pending))
  }
  watch(page: Page): void { if (!this.pages.has(page)) { this.pages.add(page); page.on('response', this.capture) } }
  async finish(): Promise<void> {
    for (const page of this.pages) page.off('response', this.capture)
    await Promise.allSettled([...this.pending])
  }
}
const collectors = new Map<string, ResponseCollector>()
test.beforeEach(async ({ page }, info) => { const collector = new ResponseCollector(); collectors.set(info.testId, collector); collector.watch(page) })
test.afterEach(async ({}, info) => {
  const collector = collectors.get(info.testId)
  if (!collector) return
  await collector.finish()
  // Only public synthetic response bodies. No request data, keys, passwords,
  // authentication headers, cookies, or CSRF values are written to reports.
  writeFileSync(info.outputPath('tms-contract-responses.json'), JSON.stringify(collector.values))
  collectors.delete(info.testId)
})

async function api<T = Body>(page: Page, path: string, method = 'GET', body?: unknown, options: { key?: string; csrf?: 'invalid' | 'omit' } = {}): Promise<Reply<T>> {
  return page.evaluate(async ({ path, method, body, options }) => {
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (method !== 'GET') {
      if (options.csrf !== 'omit') {
        const csrf = (await (await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' })).json()).data
        headers[csrf.headerName] = options.csrf === 'invalid' ? 'synthetic-invalid-csrf' : csrf.token
      }
      headers['Content-Type'] = 'application/json'
      if (path.startsWith('/tms/') || path.startsWith('/orders')) headers['Idempotency-Key'] = options.key ?? crypto.randomUUID()
    }
    const response = await fetch(`/api/v1${path}`, { method, credentials: 'same-origin', headers,
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
    const payload = await response.json()
    return { status: response.status, code: payload.code, data: payload.data, payload, replayed: response.headers.get('Idempotency-Replayed') }
  }, { path, method, body, options })
}
async function contextFor(browser: Browser, info: TestInfo): Promise<BrowserContext> {
  const use = info.project.use
  const context = await browser.newContext({ baseURL: use.baseURL, ignoreHTTPSErrors: use.ignoreHTTPSErrors, viewport: use.viewport,
    screen: use.screen, deviceScaleFactor: use.deviceScaleFactor, isMobile: use.isMobile, hasTouch: use.hasTouch, userAgent: use.userAgent })
  const collector = collectors.get(info.testId)
  if (collector) context.on('page', (page) => collector.watch(page))
  return context
}
async function enter(page: Page, credential: Credential): Promise<void> {
  await page.goto('/')
  expect(new URL(page.url()).protocol).toBe('https:')
  await signIn(page, credential)
  await expect(page.getByRole('button', { name: '登出', exact: true })).toBeVisible()
}
function success<T>(reply: Reply<T>, status = 200): T {
  expect(reply.status).toBe(status); expect(reply.code).toBe('0000')
  expect(Object.keys(reply.payload).sort()).toEqual(['code', 'data', 'message', 'timestamp'])
  expect(typeof reply.payload.timestamp).toBe('string')
  expect((reply.payload.timestamp as string).endsWith('+08:00')).toBe(true)
  return reply.data
}
function rejected(reply: Reply<unknown>, status: number, code: string): void { expect(reply.status).toBe(status); expect(reply.code).toBe(code) }
function exactKeys(value: object, expected: string[]): void { expect(Object.keys(value).sort()).toEqual([...expected].sort()) }
const myTaskKeys = ['assignmentId', 'taskId', 'taskNo', 'item', 'taskKind', 'pickupName', 'pickupAddress', 'deliveryName', 'deliveryAddress', 'siteContact', 'plannedStartAt', 'plannedEndAt', 'assignedAt', 'policyMode']
async function own(page: Page, expected: string[]): Promise<void> {
  const result = success(await api<PageData<MyTask>>(page, '/tms/my-tasks?size=100'))
  expect(result.totalElements).toBe(expected.length)
  expect(result.content.map((task) => task.assignmentId).sort()).toEqual([...expected].sort())
  for (const task of result.content) {
    exactKeys(task, myTaskKeys)
    expect(task.policyMode).toBe('SYNTHETIC_TEST_ONLY')
    exactKeys(task.item as object, ['itemKind', 'containerType', 'containerNo', 'packageQty', 'grossWeight', 'cbm'])
  }
}
function draft(customerId: string, scope: string, count = 3): Body {
  return { type: 'LOCAL', customerId, remark: '整單私有合成備註不可出現在本人摘要',
    items: [{ clientRef: 'item-a', sequence: 1, itemKind: 'CARGO', packageQty: 7, grossWeight: '0.0000000000000000001', cbm: '0' }],
    tasks: Array.from({ length: count }, (_, index) => ({ clientRef: `task-${index + 1}`, itemRef: 'item-a', taskKind: `SYNTHETIC_TMS_${scope}_TASK_${index + 1}`,
      pickupName: '合成起點', pickupAddress: ' 合成\u0000起點（非真實地址） ', deliveryName: '合成終點', deliveryAddress: '合成終點（非真實地址）',
      requiredTask: true, predecessorRefs: index === 2 ? ['task-1'] : [], siteContact: { name: '合成聯絡人', phone: 'SYNTHETIC-CONTACT' },
      plannedStartAt: '2026-12-01T08:00:00.123456+23:59', plannedEndAt: '2026-12-01T10:00:00.123456+23:59' })) }
}
async function acceptedOrder(page: Page, customerId: string, scope: string, count = 3): Promise<Order> {
  const created = await api<Order>(page, '/orders', 'POST', draft(customerId, scope, count))
  expect(created.status).toBe(201); expect(created.code).toBe('OK')
  const unindexed = success(await api<PageData<TaskSummary>>(page, `/tms/tasks?orderId=${created.data.id}&state=ALL`))
  expect(unindexed.totalElements).toBe(0)
  const result = await api<Order>(page, `/orders/${created.data.id}/accept`, 'POST', { expectedVersion: created.data.version })
  expect(result.status).toBe(200); expect(result.code).toBe('OK'); expect(result.data.acceptanceStatus).toBe('ACCEPTED')
  return result.data
}

test('TMS commands preserve exclusive resources, historical replay, source guards and current-driver isolation over TLS', async ({ page, browser }, info) => {
  test.setTimeout(360_000)
  const fixture = dispatchFixtureFor(info.project.name)
  const contexts = await Promise.all([contextFor(browser, info), contextFor(browser, info), contextFor(browser, info), contextFor(browser, info)])
  try {
    const [dispatcher, driverA, driverB, anonymous] = await Promise.all(contexts.map((context) => context.newPage()))
    await enter(page, fixture.admin); await enter(dispatcher, fixture.dispatcher)
    await enter(driverA, fixture.driverA); await enter(driverB, fixture.driverB)
    await anonymous.goto('/')
    rejected(await api(anonymous, '/tms/tasks'), 401, 'E0401')
    rejected(await api(anonymous, '/tms/my-tasks'), 401, 'E0401')
    rejected(await api(page, '/tms/my-tasks'), 403, 'E0403')
    rejected(await api(dispatcher, '/tms/my-tasks'), 403, 'E0403')

    const scope = fixture.scope
    const customer = success(await api<Customer>(page, '/crm/customers', 'POST', { customerCode: `TMS_${scope}_${randomUUID().slice(0, 8).toUpperCase()}`, name: `合成派工客戶 ${scope}` }), 201)
    const personA = success(await api<Driver>(page, '/fms/drivers', 'POST', { driverCode: `SYNTHETIC_TMS_${scope}_D1`, name: '合成派工司機甲', accountId: fixture.driverA.id, employmentStatus: 'ACTIVE', dutyStatus: 'ON_DUTY' }), 201)
    const personB = success(await api<Driver>(page, '/fms/drivers', 'POST', { driverCode: `SYNTHETIC_TMS_${scope}_D2`, name: '合成派工司機乙', accountId: fixture.driverB.id, employmentStatus: 'ACTIVE', dutyStatus: 'ON_DUTY' }), 201)
    const personC = success(await api<Driver>(page, '/fms/drivers', 'POST', { driverCode: `SYNTHETIC_TMS_${scope}_D3`, name: '合成派工司機丙', employmentStatus: 'ACTIVE', dutyStatus: 'ON_DUTY' }), 201)
    const makeVehicle = async (type: string, suffix: string) => success(await api<Vehicle>(page, '/fms/vehicles', 'POST', { plateNo: `TMS-${scope[0]}-${randomUUID().slice(0, 6)}-${suffix}`, vehicleType: type, administrativeStatus: 'AVAILABLE', capacityTon: '1.00' }), 201)
    const mainA = await makeVehicle(fixture.vehicleTypes.main.id, 'A'), mainB = await makeVehicle(fixture.vehicleTypes.main.id, 'B')
    const trailerA = await makeVehicle(fixture.vehicleTypes.trailer.id, 'A'), trailerB = await makeVehicle(fixture.vehicleTypes.trailer.id, 'B')
    const shared = await makeVehicle(fixture.vehicleTypes.shared.id, 'S')
    await own(driverA, []); await own(driverB, [])

    const order = await acceptedOrder(page, customer.id, scope)
    const taskA = order.tasks.find((task) => task.clientRef === 'task-1')!.id
    const taskB = order.tasks.find((task) => task.clientRef === 'task-2')!.id
    const dependent = order.tasks.find((task) => task.clientRef === 'task-3')!.id
    const all = success(await api<PageData<TaskSummary>>(page, `/tms/tasks?orderId=${order.id}&state=ALL&size=100`))
    expect(all.totalElements).toBe(3); expect(all.content.every((task) => task.generation === 0 && task.currentAssignmentId === null && task.policyMode === 'SYNTHETIC_TEST_ONLY')).toBe(true)
    expect(all.content.find((task) => task.taskId === dependent)!.blockingReasons).toContain('PREREQUISITE_NOT_READY')
    const firstPage = success(await api<PageData<TaskSummary>>(page, `/tms/tasks?orderId=${order.id}&state=ALL&size=1`))
    const secondPage = success(await api<PageData<TaskSummary>>(page, `/tms/tasks?orderId=${order.id}&state=ALL&size=1&page=1`))
    expect(firstPage.content).toHaveLength(1); expect(firstPage.totalElements).toBe(3); expect(firstPage.totalPages).toBe(3)
    expect(firstPage.content[0].taskId === secondPage.content[0].taskId).toBe(false)
    for (const path of ['/tms/tasks?size=101', '/tms/tasks?page=-1', '/tms/tasks?page=0&page=1', '/tms/my-tasks?driverId=forbidden']) rejected(await api(path.includes('my-tasks') ? driverA : page, path), 400, 'E0400')

    const resources = success(await api<PageData<Candidate>>(page, `/tms/resources?taskId=${taskA}&position=DRIVER&q=${personA.driverCode}&size=1`))
    expect(resources.content).toHaveLength(1); expect(resources.content[0].id).toBe(personA.id)
    expect(resources.content[0].policyApproved).toBe(true); expect(resources.content[0].administrativelyAvailable).toBe(true); expect(resources.content[0].occupied).toBe(false)
    const assignBody = { expectedGeneration: 0, driverId: personA.id, mainVehicleId: mainA.id, trailerId: trailerA.id }
    const assignKey = randomUUID()
    const firstReply = await api<Command>(page, `/tms/tasks/${taskA}/dispatch`, 'POST', assignBody, { key: assignKey })
    const first = success(firstReply); expect(firstReply.replayed).toBe('false')
    expect(first.generation).toBe(1); expect(first.assignment.status).toBe('ASSIGNED'); expect(first.assignment.version).toBe(0)
    expect(first.assignment.reservations).toHaveLength(3); expect(first.assignment.reservations.every((entry) => entry.releasedAt === null)).toBe(true)
    expect(first.assignment.source.pickupAddress).toBe(' 合成\u0000起點（非真實地址） ')
    expect((first.assignment.source.item as Body).grossWeight).toBe('0.0000000000000000001')
    expect(first.assignment.source.plannedStartAt).toBe('2026-12-01T08:00:00.123456+23:59')
    await own(driverA, [first.assignment.id]); await own(driverB, [])
    expect(success(await api<PageData<TaskSummary>>(page, `/tms/tasks?orderId=${order.id}&state=ASSIGNED`)).totalElements).toBe(1)
    expect(success(await api<PageData<TaskSummary>>(page, `/tms/tasks?orderId=${order.id}&state=UNASSIGNED`)).totalElements).toBe(2)

    const occupied = success(await api<PageData<Candidate>>(page, `/tms/resources?taskId=${taskB}&position=MAIN&q=${mainA.plateNo}`)).content.find((entry) => entry.id === mainA.id)!
    expect(occupied.occupied).toBe(true); expect(occupied.blockingReasons).toContain('RESOURCE_OCCUPIED')
    const reusable = success(await api<PageData<Candidate>>(page, `/tms/resources?taskId=${taskA}&position=MAIN&assignmentId=${first.assignment.id}&q=${mainA.plateNo}`)).content.find((entry) => entry.id === mainA.id)!
    expect(reusable.occupied).toBe(true); expect(reusable.blockingReasons).not.toContain('RESOURCE_OCCUPIED')
    rejected(await api(dispatcher, `/tms/tasks/${taskB}/dispatch`, 'POST', { expectedGeneration: 0, driverId: personA.id, mainVehicleId: mainB.id, trailerId: null }), 409, 'E1602')
    rejected(await api(page, `/tms/tasks/${dependent}/dispatch`, 'POST', { expectedGeneration: 0, driverId: personB.id, mainVehicleId: mainB.id, trailerId: trailerB.id }), 409, 'E1604')
    rejected(await api(page, `/tms/tasks/${taskA}/dispatch`, 'POST', { ...assignBody, mainVehicleId: mainB.id }, { key: assignKey }), 409, 'E1607')
    const stale = await api(page, `/tms/tasks/${taskA}/dispatch`, 'POST', { expectedGeneration: 0, driverId: personB.id, mainVehicleId: mainB.id, trailerId: null })
    rejected(stale, 409, 'E1601'); expect(stale.data.actualGeneration).toBe(1)
    rejected(await api(page, `/fms/vehicles/${mainA.id}`, 'PATCH', { version: mainA.version, administrativeStatus: 'MAINTENANCE' }), 409, 'E0409')
    rejected(await api(page, `/fms/drivers/${personA.id}`, 'PATCH', { version: personA.version, dutyStatus: 'OFF_DUTY' }), 409, 'E0409')
    const renamed = success(await api<Driver>(page, `/fms/drivers/${personA.id}`, 'PATCH', { version: personA.version, name: '合成派工司機甲更名' }))
    expect(success(await api<Detail>(page, `/tms/tasks/${taskA}/dispatch`)).current!.driver.name).toBe(personA.name)

    for (const [path, method, body] of [
      ['/tms/tasks', 'GET', undefined], [`/tms/tasks/${taskA}/dispatch`, 'GET', undefined], [`/tms/tasks/${taskB}/dispatch`, 'POST', assignBody],
      [`/tms/dispatches/${first.assignment.id}/revoke`, 'POST', { expectedGeneration: 1, reason: '禁止司機撤派' }],
      [`/tms/dispatches/${first.assignment.id}/reassign`, 'POST', { ...assignBody, expectedGeneration: 1, reason: '禁止司機改派' }],
      [`/tms/resources?taskId=${taskA}&position=DRIVER`, 'GET', undefined],
    ] as const) rejected(await api(driverA, path, method, body), 403, 'E0403')

    const reassignBody = { expectedGeneration: 1, driverId: personB.id, mainVehicleId: mainB.id, trailerId: trailerB.id, reason: '  合成 e\u0301 改派  ' }
    const reassignKey = randomUUID()
    const changedReply = await api<Command>(dispatcher, `/tms/dispatches/${first.assignment.id}/reassign`, 'POST', reassignBody, { key: reassignKey })
    const changed = success(changedReply); expect(changedReply.replayed).toBe('false')
    expect(changed.generation).toBe(2); expect(changed.assignment.id === first.assignment.id).toBe(false)
    expect(changed.assignment.driver.id).toBe(personB.id); expect(changed.assignment.mainVehicle.id).toBe(mainB.id)
    await own(driverA, []); await own(driverB, [changed.assignment.id])
    const history = success(await api<Detail>(page, `/tms/tasks/${taskA}/dispatch?historySize=1`))
    expect(history.task.generation).toBe(2); expect(history.history.totalElements).toBe(2); expect(history.history.totalPages).toBe(2)
    const old = success(await api<Detail>(page, `/tms/tasks/${taskA}/dispatch?historyPage=1&historySize=1`)).history.content[0]
    expect(old.id).toBe(first.assignment.id); expect(old.status).toBe('SUPERSEDED'); expect(old.version).toBe(1)
    expect(old.endReason).toBe('合成 é 改派'); expect(old.driver.name).toBe(personA.name)
    expect(old.reservations.every((entry) => entry.releasedAt !== null)).toBe(true)
    const replayAssign = await api<Command>(page, `/tms/tasks/${taskA}/dispatch`, 'POST', assignBody, { key: assignKey })
    success(replayAssign); expect(replayAssign.replayed).toBe('true'); expect(isDeepStrictEqual(replayAssign.data, first)).toBe(true)
    expect(success(await api<Detail>(page, `/tms/tasks/${taskA}/dispatch`)).current!.id).toBe(changed.assignment.id)
    rejected(await api(page, `/tms/dispatches/${changed.assignment.id}/reassign`, 'POST', { ...reassignBody, expectedGeneration: 2 }), 400, 'E0400')

    const revokeBody = { expectedGeneration: 2, reason: '  合成撤派保留歷史  ' }, revokeKey = randomUUID()
    const revokedReply = await api<Command>(page, `/tms/dispatches/${changed.assignment.id}/revoke`, 'POST', revokeBody, { key: revokeKey })
    const revoked = success(revokedReply); expect(revokedReply.replayed).toBe('false'); expect(revoked.generation).toBe(3); expect(revoked.assignment.status).toBe('REVOKED')
    expect(revoked.assignment.reservations.every((entry) => entry.releasedAt !== null)).toBe(true)
    await own(driverB, [])
    const replayRevoke = await api<Command>(page, `/tms/dispatches/${changed.assignment.id}/revoke`, 'POST', revokeBody, { key: revokeKey })
    success(replayRevoke); expect(replayRevoke.replayed).toBe('true'); expect(isDeepStrictEqual(replayRevoke.data, revoked)).toBe(true)
    const replayChange = await api<Command>(dispatcher, `/tms/dispatches/${first.assignment.id}/reassign`, 'POST', reassignBody, { key: reassignKey })
    success(replayChange); expect(replayChange.replayed).toBe('true'); expect(isDeepStrictEqual(replayChange.data, changed)).toBe(true)
    const released = success(await api<Detail>(page, `/tms/tasks/${taskA}/dispatch`))
    expect(released.current).toBeNull(); expect(released.task.generation).toBe(3)
    expect(released.history.content.every((entry) => entry.reservations.every((reservation) => reservation.releasedAt !== null))).toBe(true)

    // A shared synthetic classification deliberately exercises the common
    // vehicle namespace in both positions; it is not a real pairing approval.
    const sharedMain = success(await api<Command>(page, `/tms/tasks/${taskB}/dispatch`, 'POST', { expectedGeneration: 0, driverId: personB.id, mainVehicleId: shared.id, trailerId: null }))
    const otherOrder = await acceptedOrder(page, customer.id, scope, 1), otherTask = otherOrder.tasks[0].id
    rejected(await api(page, `/tms/tasks/${otherTask}/dispatch`, 'POST', { expectedGeneration: 0, driverId: personC.id, mainVehicleId: mainB.id, trailerId: shared.id }), 409, 'E1602')
    success(await api<Command>(page, `/tms/dispatches/${sharedMain.assignment.id}/revoke`, 'POST', { expectedGeneration: 1, reason: '合成跨位置釋放' }))
    const sharedTrailer = success(await api<Command>(page, `/tms/tasks/${otherTask}/dispatch`, 'POST', { expectedGeneration: 0, driverId: personC.id, mainVehicleId: mainB.id, trailerId: shared.id }))
    expect(sharedTrailer.assignment.trailer!.id).toBe(shared.id)
    success(await api<Command>(page, `/tms/dispatches/${sharedTrailer.assignment.id}/revoke`, 'POST', { expectedGeneration: 1, reason: '合成驗收結束釋放' }))
    rejected(await api(page, `/tms/tasks/${dependent}/dispatch`, 'POST', { expectedGeneration: 0, driverId: personB.id, mainVehicleId: mainB.id, trailerId: null }), 409, 'E1604')

    const outside = success(await api<Driver>(page, '/fms/drivers', 'POST', { driverCode: `UNAPPROVED_TMS_${randomUUID().slice(0, 8)}`, name: '合成未核准測試政策司機', employmentStatus: 'ACTIVE', dutyStatus: 'ON_DUTY' }), 201)
    const notApproved = success(await api<PageData<Candidate>>(page, `/tms/resources?taskId=${taskA}&position=DRIVER&q=${outside.driverCode}`)).content.find((entry) => entry.id === outside.id)!
    expect(notApproved.administrativelyAvailable).toBe(true); expect(notApproved.policyApproved).toBe(false)
    expect(notApproved.blockingReasons).toContain('POLICY_NOT_CONFIRMED')
    rejected(await api(page, `/tms/tasks/${taskA}/dispatch`, 'POST', { expectedGeneration: 3, driverId: outside.id, mainVehicleId: mainA.id, trailerId: null }), 409, 'E1605')
    rejected(await api(page, `/tms/tasks/${taskA}/dispatch`, 'POST', { ...assignBody, expectedGeneration: 3 }, { csrf: 'invalid' }), 403, 'E0403')
    expect(success(await api<Detail>(page, `/tms/tasks/${taskA}/dispatch`)).task.generation).toBe(3)

    const rebound = success(await api<Command>(page, `/tms/tasks/${taskA}/dispatch`, 'POST', { ...assignBody, expectedGeneration: 3 }))
    expect(rebound.generation).toBe(4); await own(driverA, [rebound.assignment.id])
    const unboundA = success(await api<Driver>(page, `/fms/drivers/${personA.id}`, 'PATCH', { version: renamed.version, accountId: null }))
    rejected(await api(driverA, '/tms/my-tasks'), 404, 'E0404')
    success(await api<Driver>(page, `/fms/drivers/${personB.id}`, 'PATCH', { version: personB.version, accountId: null }))
    rejected(await api(driverB, '/tms/my-tasks'), 404, 'E0404')
    success(await api<Driver>(page, `/fms/drivers/${personA.id}`, 'PATCH', { version: unboundA.version, accountId: fixture.driverB.id }))
    rejected(await api(driverA, '/tms/my-tasks'), 404, 'E0404'); await own(driverB, [rebound.assignment.id])
    const accounts = success(await api<Account[]>(page, '/upms/accounts'))
    const boundActor = accounts.find((actor) => actor.id === fixture.driverB.id)!
    success(await api(page, `/upms/accounts/${boundActor.id}`, 'PATCH', { version: boundActor.version, displayName: boundActor.displayName, role: boundActor.role, enabled: false }))
    rejected(await api(driverB, '/tms/my-tasks'), 401, 'E0401')
    success(await api<Command>(dispatcher, `/tms/dispatches/${rebound.assignment.id}/revoke`, 'POST', { expectedGeneration: 4, reason: '合成撤權後管理撤派' }))
    const current = success(await api<Detail>(page, `/tms/tasks/${taskA}/dispatch`))
    expect(current.current).toBeNull(); expect(current.task.generation).toBe(5)
    const unchanged = await api<Order>(page, `/orders/${order.id}`)
    expect(unchanged.status).toBe(200); expect(isDeepStrictEqual(unchanged.data, order)).toBe(true)
    const dispatcherAccount = success(await api<Account[]>(page, '/upms/accounts')).find((actor) => actor.id === fixture.dispatcher.id)!
    success(await api(page, `/upms/accounts/${dispatcherAccount.id}`, 'PATCH', { version: dispatcherAccount.version, displayName: dispatcherAccount.displayName, role: dispatcherAccount.role, enabled: false }))
    rejected(await api(dispatcher, `/tms/dispatches/${first.assignment.id}/reassign`, 'POST', reassignBody, { key: reassignKey }), 401, 'E0401')
  } finally { await Promise.all(contexts.map((context) => context.close())) }
})
