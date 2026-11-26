import { randomUUID } from 'node:crypto'
import { readFileSync, writeFileSync } from 'node:fs'
import { request as httpsRequest } from 'node:https'
import { isDeepStrictEqual } from 'node:util'
import { expect, test, type Browser, type BrowserContext, type Page, type Request, type Response, type Route, type TestInfo } from '@playwright/test'
import { executionFixtureFor, signIn, type Credential } from './fixtures'

// M4-01 is API acceptance, not the M4-06 execution UI. This group owns its
// actors/resources; runtime startup and account initialization stay external.
test.describe.configure({ retries: 0 })
type Body = Record<string, unknown>
interface Reply<T> { status: number; code: string; data: T; payload: Body; replayed: string | null }
interface PageData<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }
interface Execution { phase: string; version: number; departedAt: string | null }
interface Departure { assignmentId: string; taskId: string; execution: Execution }
interface OwnDetail extends Body { assignmentId: string; taskId: string; taskNo: string; execution: Execution }
interface Driver { id: string; driverCode: string; accountId: string | null; version: number }
interface Vehicle { id: string; plateNo: string; version: number }
interface Account { id: string; role: string; displayName: string; enabled: boolean; version: number }
interface Order extends Body { id: string; version: number; acceptanceStatus: string; tasks: { id: string; clientRef: string }[] }
interface Assignment extends Body { id: string; taskId: string; status: string; version: number; assignedGeneration: number; driver: { id: string }; mainVehicle: { id: string }; reservations: { releasedAt: string | null }[]; events: unknown[] }
interface Command { taskId: string; generation: number; assignment: Assignment }
interface DispatchDetail { task: { generation: number; currentAssignmentId: string | null }; current: Assignment | null; history: PageData<Assignment> }
interface Resources { driver: Driver; main: Vehicle }
interface Scenario { order: Order; taskId: string; dependentId?: string; assignment: Assignment; assignBody: Body; assignKey: string; resources: Resources }
interface Captured { schema: string; value: unknown }
type ExecutionSchema = 'MyTaskExecutionEnvelope' | 'DepartMyTaskEnvelope' | 'ErrorEnvelope'
const privateNote = '整單私有合成備註不得出現在出發本人資料'
const myTaskFields = ['assignmentId', 'taskId', 'taskNo', 'item', 'taskKind', 'pickupName', 'pickupAddress', 'deliveryName', 'deliveryAddress', 'siteContact', 'plannedStartAt', 'plannedEndAt', 'assignedAt', 'policyMode']

class ResponseCollector {
  readonly execution: Captured[] = []
  readonly tms: Captured[] = []
  readonly orders: Captured[] = []
  readonly acceptances: Captured[] = []
  private readonly pages = new Set<Page>()
  private readonly pending = new Set<Promise<void>>()
  private readonly capture = (response: Response) => {
    const path = new URL(response.url()).pathname, method = response.request().method()
    let schema: string | undefined, target: Captured[] | undefined
    // Only the two frozen operation paths are execution evidence, including
    // unsupported methods on those paths. Never collect /auth or request data.
    if (/^\/api\/v1\/tms\/my-tasks\/[0-9a-f-]{36}(?:\/depart)?$/i.test(path)) {
      target = this.execution; schema = path.endsWith('/depart') ? 'DepartMyTaskEnvelope' : 'MyTaskExecutionEnvelope'
    } else if (path === '/api/v1/tms/my-tasks' && method === 'GET') { target = this.tms; schema = 'MyTaskPageEnvelope' }
    else if (/^\/api\/v1\/tms\/tasks\/[0-9a-f-]{36}\/dispatch$/i.test(path)) { target = this.tms; schema = method === 'GET' ? 'DispatchDetailEnvelope' : method === 'POST' ? 'CommandResultEnvelope' : undefined }
    else if (/^\/api\/v1\/tms\/dispatches\/[0-9a-f-]{36}\/(revoke|reassign)$/i.test(path) && method === 'POST') { target = this.tms; schema = 'CommandResultEnvelope' }
    else if (path === '/api/v1/tms/tasks' && method === 'GET') { target = this.tms; schema = 'TaskPageEnvelope' }
    else if (/^\/api\/v1\/orders\/[0-9a-f-]{36}\/accept$/i.test(path) && method === 'POST') { target = this.acceptances; schema = 'AcceptedOrderEnvelope' }
    else if (path === '/api/v1/orders' && method === 'POST') { target = this.orders; schema = 'OrderEnvelope' }
    else if (/^\/api\/v1\/orders\/[0-9a-f-]{36}$/i.test(path) && method === 'GET') { target = this.orders; schema = 'OrderEnvelope' }
    if (!schema || !target) return
    const destination = target, name = response.status() >= 400 ? 'ErrorEnvelope' : schema
    const pending = (async () => { try { destination.push({ schema: name, value: await response.json() }) } catch { /* An unreadable/aborted response is not claimed as evidence. */ } })()
    this.pending.add(pending); void pending.then(() => this.pending.delete(pending), () => this.pending.delete(pending))
  }
  watch(page: Page): void { if (!this.pages.has(page)) { this.pages.add(page); page.on('response', this.capture) } }
  record(schema: ExecutionSchema, value: Body): void { this.execution.push({ schema, value }) }
  async finish(): Promise<void> { for (const page of this.pages) page.off('response', this.capture); await Promise.allSettled([...this.pending]) }
}
const collectors = new Map<string, ResponseCollector>()
test.beforeEach(async ({ page }, info) => { const collector = new ResponseCollector(); collectors.set(info.testId, collector); collector.watch(page) })
test.afterEach(async ({}, info) => {
  const collector = collectors.get(info.testId)
  if (!collector) return
  await collector.finish()
  // Public synthetic response bodies only: no credentials, headers, cookie,
  // CSRF, request context, idempotency key or raw request is serialized.
  for (const [name, values] of [['execution', collector.execution], ['tms', collector.tms], ['order', collector.orders], ['acceptance', collector.acceptances]] as const) {
    writeFileSync(info.outputPath(`${name}-contract-responses.json`), JSON.stringify(values))
  }
  collectors.delete(info.testId)
})
interface RequestOptions { key?: string; csrf?: 'invalid' | 'omit'; raw?: string | number[]; media?: string; omitKey?: boolean }
async function api<T = Body>(page: Page, path: string, method = 'GET', body?: unknown, options: RequestOptions = {}): Promise<Reply<T>> {
  return page.evaluate(async ({ path, method, body, options }) => {
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (method !== 'GET') {
      if (options.csrf !== 'omit') {
        const csrf = (await (await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' })).json()).data
        if (csrf && typeof csrf.headerName === 'string' && typeof csrf.token === 'string') headers[csrf.headerName] = options.csrf === 'invalid' ? 'synthetic-invalid-csrf' : csrf.token
      }
      headers['Content-Type'] = options.media ?? 'application/json'
      if (!options.omitKey && (path.startsWith('/tms/') || path.startsWith('/orders'))) headers['Idempotency-Key'] = options.key ?? crypto.randomUUID()
    }
    const wire = Array.isArray(options.raw) ? new Uint8Array(options.raw) : options.raw ?? (body === undefined ? undefined : JSON.stringify(body))
    const response = await fetch(`/api/v1${path}`, { method, credentials: 'same-origin', headers, ...(wire === undefined ? {} : { body: wire }) })
    const payload = await response.json()
    return { status: response.status, code: payload.code, data: payload.data, payload, replayed: response.headers.get('Idempotency-Replayed') }
  }, { path, method, body, options })
}
async function newContext(browser: Browser, info: TestInfo): Promise<BrowserContext> {
  const use = info.project.use
  const context = await browser.newContext({ baseURL: use.baseURL, ignoreHTTPSErrors: use.ignoreHTTPSErrors, viewport: use.viewport, screen: use.screen, deviceScaleFactor: use.deviceScaleFactor, isMobile: use.isMobile, hasTouch: use.hasTouch, userAgent: use.userAgent })
  context.on('page', (page) => collectors.get(info.testId)?.watch(page))
  return context
}
async function enter(page: Page, credential: Credential, temporary = false): Promise<void> {
  await page.goto('/'); expect(new URL(page.url()).protocol).toBe('https:'); await signIn(page, credential)
  if (temporary) await expect(page.getByRole('heading', { name: '首次登入，請先修改密碼' })).toBeVisible()
  else await expect(page.getByRole('button', { name: '登出', exact: true })).toBeVisible()
}
function exact(value: object, keys: readonly string[]): void { expect(Object.keys(value).sort()).toEqual([...keys].sort()) }
function envelope<T>(reply: Reply<T>, status = 200, code = '0000'): T {
  expect(reply.status).toBe(status); expect(reply.code).toBe(code)
  exact(reply.payload, ['code', 'message', 'data', 'timestamp'])
  expect(typeof reply.payload.message).toBe('string')
  expect(typeof reply.payload.timestamp === 'string' && reply.payload.timestamp.endsWith('+08:00')).toBe(true)
  return reply.data
}
function rejected(reply: Reply<unknown>, status: number, code: string, reason?: string): void {
  const data = envelope(reply, status, code)
  if (reason) expect((data as Body)?.reason).toBe(reason)
  if (status === 401 || status === 404) expect(data).toBeNull()
  expect(reply.replayed).toBeNull()
}
function departure(reply: Reply<Departure>, scenario: Scenario, replayed: boolean): Departure {
  const result = envelope(reply)
  exact(result, ['assignmentId', 'taskId', 'execution']); exact(result.execution, ['phase', 'version', 'departedAt'])
  expect(result.assignmentId).toBe(scenario.assignment.id); expect(result.taskId).toBe(scenario.taskId)
  expect(result.execution.phase).toBe('DEPARTED'); expect(result.execution.version).toBe(1)
  expect(typeof result.execution.departedAt === 'string' && !Number.isNaN(Date.parse(result.execution.departedAt))).toBe(true)
  expect(reply.replayed).toBe(String(replayed))
  return result
}
async function ownDetail(page: Page, scenario: Scenario, phase: 'WAITING' | 'DEPARTED', contact?: Body | null): Promise<OwnDetail> {
  const result = envelope(await api<OwnDetail>(page, `/tms/my-tasks/${scenario.assignment.id}`))
  exact(result, [...myTaskFields, 'execution']); exact(result.execution, ['phase', 'version', 'departedAt'])
  expect(result.assignmentId).toBe(scenario.assignment.id); expect(result.taskId).toBe(scenario.taskId)
  expect(result.execution.phase).toBe(phase); expect(result.execution.version).toBe(phase === 'WAITING' ? 0 : 1)
  if (phase === 'WAITING') expect(result.execution.departedAt).toBeNull()
  else expect(typeof result.execution.departedAt).toBe('string')
  expect(JSON.stringify(result).includes(privateNote)).toBe(false)
  if (contact !== undefined) expect(result.siteContact).toEqual(contact)
  return result
}
async function unchangedDispatch(admin: Page, scenario: Scenario, before: DispatchDetail): Promise<void> {
  const fresh = envelope(await api<DispatchDetail>(admin, `/tms/tasks/${scenario.taskId}/dispatch`))
  expect(isDeepStrictEqual(fresh, before)).toBe(true)
  expect(fresh.current!.status).toBe('ASSIGNED'); expect(fresh.current!.version).toBe(0)
  expect(fresh.current!.reservations.every((entry) => entry.releasedAt === null)).toBe(true)
  const source = await api<Order>(admin, `/orders/${scenario.order.id}`)
  expect(source.status).toBe(200); expect(source.code).toBe('OK'); expect(isDeepStrictEqual(source.data, scenario.order)).toBe(true)
}

interface Forwarded { status: number; payload: Body; replayed?: string }
const safeForwardFailure = 'The controlled synthetic departure request could not be completed safely.'
// APIRequestContext is deliberately avoided: failed request logs can contain
// sensitive headers. Only the actual public response body leaves this helper.
async function forward(request: Request, origin: string, path: string, signal: AbortSignal, invalidCsrf = false): Promise<Forwarded> {
  try {
    const url = new URL(request.url())
    if (url.origin !== origin || url.protocol !== 'https:' || url.pathname !== path || request.method() !== 'POST') throw new Error(safeForwardFailure)
    const headers = { ...await request.allHeaders() }
    const connectionTokens = (headers.connection ?? '').split(',').map((value) => value.trim().toLowerCase())
    for (const name of [...connectionTokens, 'connection', 'proxy-connection', 'keep-alive', 'transfer-encoding', 'upgrade', 'te', 'trailer', 'proxy-authenticate', 'proxy-authorization']) delete headers[name]
    for (const name of Object.keys(headers)) if (name.startsWith(':')) delete headers[name]
    const body = request.postDataBuffer() ?? Buffer.alloc(0)
    headers['content-length'] = String(body.byteLength); headers['accept-encoding'] = 'identity'
    if (invalidCsrf) headers['x-csrf-token'] = 'synthetic-invalid-csrf'
    const ca = ['localhost', '127.0.0.1'].includes(url.hostname) ? readFileSync('infrastructure/dev-tls/localhost.crt') : undefined
    return await new Promise<Forwarded>((resolve, reject) => {
      let settled = false; let deadline: ReturnType<typeof setTimeout> | undefined; let upstream: ReturnType<typeof httpsRequest> | undefined
      const finish = (complete: () => void) => { if (settled) return; settled = true; if (deadline) clearTimeout(deadline); signal.removeEventListener('abort', cancel); complete() }
      const fail = () => finish(() => reject(new Error(safeForwardFailure)))
      const cancel = () => { upstream?.destroy(); fail() }
      if (signal.aborted) { fail(); return }
      upstream = httpsRequest(url, { method: 'POST', headers, ...(ca ? { ca } : {}), rejectUnauthorized: true, agent: false }, (response) => {
        const chunks: Buffer[] = []; let bytes = 0
        response.on('error', fail); response.on('aborted', fail)
        response.on('data', (chunk: Buffer) => { bytes += chunk.byteLength; if (bytes > 1_048_576) { response.destroy(); fail() } else chunks.push(chunk) })
        response.on('end', () => {
          try { const value: unknown = JSON.parse(Buffer.concat(chunks).toString('utf8')); if (value === null || typeof value !== 'object' || Array.isArray(value)) { fail(); return }; finish(() => resolve({ status: response.statusCode!, payload: value as Body, replayed: response.headers['idempotency-replayed'] as string | undefined })) }
          catch { fail() }
        })
      })
      upstream.on('error', fail); deadline = setTimeout(cancel, 15_000); signal.addEventListener('abort', cancel, { once: true }); if (signal.aborted) { cancel(); return }; upstream.end(body)
    })
  } catch { throw new Error(safeForwardFailure) }
}
async function loseDeparture(page: Page, scenario: Scenario, collector: ResponseCollector) {
  const origin = new URL(page.url()).origin, path = `/api/v1/tms/my-tasks/${scenario.assignment.id}/depart`
  let attempts = 0, matching = false; let firstKey: string | undefined; let firstBody: Buffer | null = null
  let resolve!: (result: Departure) => void, reject!: (error: Error) => void
  const committed = new Promise<Departure>((yes, no) => { resolve = yes; reject = no }); void committed.catch(() => undefined)
  const cancellation = new AbortController(), active = new Set<Promise<void>>()
  const matches = (url: URL) => url.origin === origin && url.pathname === path
  const deadline = setTimeout(() => { cancellation.abort(); reject(new Error(safeForwardFailure)) }, 20_000)
  const handle = async (route: Route) => {
    try {
      const request = route.request()
      if (request.method() !== 'POST') { await route.continue(); return }
      attempts++; const key = (await request.allHeaders())['idempotency-key'], body = request.postDataBuffer()
      if (attempts === 1) {
        firstKey = key; firstBody = body
        const reply = await forward(request, origin, path, cancellation.signal), result = reply.payload.data as Departure | undefined
        if (reply.status !== 200 || reply.payload.code !== '0000' || reply.replayed !== 'false' || !result || result.assignmentId !== scenario.assignment.id || result.taskId !== scenario.taskId || result.execution?.phase !== 'DEPARTED' || result.execution.version !== 1 || typeof result.execution.departedAt !== 'string') throw new Error(safeForwardFailure)
        collector.record('DepartMyTaskEnvelope', reply.payload); await route.abort('failed'); clearTimeout(deadline); resolve(result)
      } else {
        const same = Boolean(firstKey && key === firstKey && firstBody && body && firstBody.equals(body)); matching = attempts === 2 ? same : matching && same
        if (attempts === 2) {
          const reply = await forward(request, origin, path, cancellation.signal, true)
          if (reply.status !== 403 || reply.payload.code !== 'E0403' || (reply.payload.data as Body | null)?.reason !== 'CSRF_INVALID') throw new Error(safeForwardFailure)
          await route.fulfill({ status: 403, contentType: 'application/json', body: JSON.stringify(reply.payload) })
        } else await route.continue()
      }
    } catch { clearTimeout(deadline); reject(new Error(safeForwardFailure)); try { await route.abort('failed') } catch { /* Closing page. */ } }
  }
  const handler = (route: Route) => { const pending = handle(route); active.add(pending); void pending.then(() => active.delete(pending), () => active.delete(pending)); return pending }
  await page.route(matches, handler)
  return { committed, attempts: () => attempts, matching: () => matching, dispose: async () => { clearTimeout(deadline); cancellation.abort(); reject(new Error(safeForwardFailure)); await Promise.allSettled([...active]); await page.unroute(matches, handler) } }
}

test('current DRIVER detail and departure preserve authority, version, replay and dispatch race safety over TLS', async ({ page, browser }, info) => {
  test.setTimeout(420_000)
  const fixture = executionFixtureFor(info.project.name), scope = fixture.scope
  const contexts = await Promise.all(Array.from({ length: 5 }, () => newContext(browser, info)))
  try {
    const [dispatcher, driverA, driverB, temporary, anonymous] = await Promise.all(contexts.map((context) => context.newPage()))
    await enter(page, fixture.admin); await enter(dispatcher, fixture.dispatcher); await enter(driverA, fixture.driverA); await enter(driverB, fixture.driverB); await enter(temporary, fixture.temporary, true); await anonymous.goto('/')
    const unknown = randomUUID(), unknownPath = `/tms/my-tasks/${unknown}`
    rejected(await api(anonymous, unknownPath), 401, 'E0401')
    rejected(await api(anonymous, `${unknownPath}/depart`, 'POST', { expectedVersion: 0 }, { csrf: 'omit' }), 401, 'E0401')
    for (const actor of [page, dispatcher, temporary]) {
      rejected(await api(actor, unknownPath), 403, 'E0403')
      rejected(await api(actor, `${unknownPath}/depart`, 'POST', { expectedVersion: 0 }), 403, 'E0403')
    }
    rejected(await api(driverB, unknownPath), 404, 'E0404')
    rejected(await api(driverB, `${unknownPath}/depart`, 'POST', { expectedVersion: 0 }), 404, 'E0404')
    const customer = envelope(await api<{ id: string }>(page, '/crm/customers', 'POST', { customerCode: `EXEC_${scope}_${randomUUID().slice(0, 8).toUpperCase()}`, name: `合成執行客戶 ${scope}` }), 201)
    let sequence = 2000
    const bound: Partial<Record<'A' | 'B', Driver>> = {}
    const makeResources = async (owner: 'A' | 'B'): Promise<Resources> => {
      if (bound[owner]) { const previous = bound[owner]!; envelope(await api<Driver>(page, `/fms/drivers/${previous.id}`, 'PATCH', { version: previous.version, accountId: null })) }
      const driver = envelope(await api<Driver>(page, '/fms/drivers', 'POST', { driverCode: `SYNTHETIC_TMS_${scope}_D${++sequence}`, name: `合成執行司機 ${scope} ${sequence}`, accountId: owner === 'A' ? fixture.driverA.id : fixture.driverB.id, employmentStatus: 'ACTIVE', dutyStatus: 'ON_DUTY' }), 201)
      bound[owner] = driver
      const main = envelope(await api<Vehicle>(page, '/fms/vehicles', 'POST', { plateNo: `EXEC-${scope[0]}-${randomUUID().slice(0, 10)}`, vehicleType: fixture.vehicleTypes.main.id, administrativeStatus: 'AVAILABLE', capacityTon: '1.00' }), 201)
      return { driver, main }
    }
    const makeScenario = async (owner: 'A' | 'B', dependent = false, contact: Body | null = { name: null, phone: null }): Promise<Scenario> => {
      const resources = await makeResources(owner)
      const draft = { type: 'LOCAL', customerId: customer.id, remark: privateNote, items: [{ clientRef: 'exec-item', sequence: 1, itemKind: 'CARGO', packageQty: 7, grossWeight: '0.0000000000000000001', cbm: '0.00' }], tasks: Array.from({ length: dependent ? 2 : 1 }, (_, index) => ({ clientRef: `exec-task-${index + 1}`, itemRef: 'exec-item', taskKind: `SYNTHETIC_TMS_${scope}_EXECUTION_${sequence}_${index + 1}`, pickupName: ' 合成 e\u0301 起點 ', pickupAddress: ' 合成\u0000原始地址\r\n第二行 ', deliveryName: '合成執行終點', deliveryAddress: '合成終點（非真實地址）', siteContact: contact, plannedStartAt: '1500-01-02T08:00:00.123456+23:59', plannedEndAt: '1500-01-02T10:00:00.123456+23:59', requiredTask: true, predecessorRefs: index ? ['exec-task-1'] : [] })) }
      const created = await api<Order>(page, '/orders', 'POST', draft); expect(created.status).toBe(201); expect(created.code).toBe('OK')
      const accepted = await api<Order>(page, `/orders/${created.data.id}/accept`, 'POST', { expectedVersion: created.data.version }); expect(accepted.status).toBe(200); expect(accepted.code).toBe('OK'); expect(accepted.data.acceptanceStatus).toBe('ACCEPTED')
      const taskId = accepted.data.tasks.find((task) => task.clientRef === 'exec-task-1')!.id
      const assignBody = { expectedGeneration: 0, driverId: resources.driver.id, mainVehicleId: resources.main.id, trailerId: null }, assignKey = randomUUID()
      const command = envelope(await api<Command>(page, `/tms/tasks/${taskId}/dispatch`, 'POST', assignBody, { key: assignKey }))
      expect(command.generation).toBe(1); expect(command.assignment.policyMode).toBe('SYNTHETIC_TEST_ONLY')
      return { order: accepted.data, taskId, dependentId: accepted.data.tasks.find((task) => task.clientRef === 'exec-task-2')?.id, assignment: command.assignment, assignBody, assignKey, resources }
    }

    const first = await makeScenario('A', true), ownPath = `/tms/my-tasks/${first.assignment.id}`, departPath = `${ownPath}/depart`
    const before = envelope(await api<DispatchDetail>(page, `/tms/tasks/${first.taskId}/dispatch`))
    const waiting = await ownDetail(driverA, first, 'WAITING', { name: null, phone: null })
    const repeatRead = await ownDetail(driverA, first, 'WAITING'); expect(isDeepStrictEqual(waiting, repeatRead)).toBe(true)
    expect(waiting.pickupName).toBe(' 合成 e\u0301 起點 '); expect(waiting.pickupAddress).toBe(' 合成\u0000原始地址\r\n第二行 ')
    expect((waiting.item as Body).grossWeight).toBe('0.0000000000000000001'); expect((waiting.item as Body).cbm).toBe('0')
    expect(waiting.item).toEqual((first.assignment.source as Body).item)
    expect(waiting.plannedStartAt).toBe('1500-01-02T08:00:00.123456+23:59')
    const summary = envelope(await api<PageData<Body>>(driverA, '/tms/my-tasks?size=100'))
    expect(summary.content).toHaveLength(1); exact(summary.content[0], myTaskFields)
    const { execution: _execution, ...withoutExecution } = waiting; expect(isDeepStrictEqual(withoutExecution, summary.content[0])).toBe(true)
    rejected(await api(driverB, ownPath), 404, 'E0404'); rejected(await api(driverB, departPath, 'POST', { expectedVersion: 0 }), 404, 'E0404')
    for (const query of ['driverId=forbidden', 'page=0', 'sort=phase', 'expectedVersion=0', 'x=1&x=2']) {
      rejected(await api(driverA, `${ownPath}?${query}`), 400, 'E0400')
      rejected(await api(driverA, `${departPath}?${query}`, 'POST', { expectedVersion: 0 }), 400, 'E0400')
    }
    for (const body of [{}, { expectedVersion: -1 }, { expectedVersion: null }, { expectedVersion: '0' }, { expectedVersion: 0.1 }, { expectedVersion: Number.MAX_SAFE_INTEGER + 1 }, { expectedVersion: 0, driverId: first.resources.driver.id }, { expectedVersion: 0, phase: 'DEPARTED' }]) rejected(await api(driverA, departPath, 'POST', body), 400, 'E0400')
    for (const raw of ['{"expectedVersion":0,"expectedVersion":0}', '{"expectedVersion":0}{}', '{"expectedVersion":0,"actor":"\\ud800"}', '[0]']) rejected(await api(driverA, departPath, 'POST', undefined, { raw }), 400, 'E0400')
    rejected(await api(driverA, departPath, 'POST', undefined, { raw: [123, 34, 120, 34, 58, 34, 195, 40, 34, 125] }), 400, 'E0400')
    rejected(await api(driverA, departPath, 'POST', { expectedVersion: 0 }, { omitKey: true }), 400, 'E0400')
    rejected(await api(driverA, departPath, 'POST', { expectedVersion: 0 }, { key: 'invalid key space' }), 400, 'E0400')
    rejected(await api(driverA, departPath, 'PUT', { expectedVersion: 0 }), 405, 'E0405')
    rejected(await api(driverA, ownPath, 'PUT', { expectedVersion: 0 }), 405, 'E0405')
    // HEAD has no response body. Assert only public HTTP metadata, not a JSON
    // envelope that the transport is required to suppress.
    const head = await driverA.evaluate(async (path) => {
      const response = await fetch(`/api/v1${path}`, { method: 'HEAD', credentials: 'same-origin', headers: { Accept: 'application/json' } })
      return { status: response.status, replayed: response.headers.get('Idempotency-Replayed') }
    }, ownPath)
    expect(head.status).toBe(405); expect(head.replayed).toBeNull()

    rejected(await api(driverA, departPath), 405, 'E0405')
    rejected(await api(driverA, departPath, 'POST', { expectedVersion: 0 }, { media: 'text/plain' }), 415, 'E0415')
    rejected(await api(driverA, departPath, 'POST', { expectedVersion: 0 }, { csrf: 'invalid' }), 403, 'E0403', 'CSRF_INVALID')
    rejected(await api(driverA, departPath, 'POST', { expectedVersion: 0 }, { csrf: 'omit' }), 403, 'E0403', 'CSRF_INVALID')
    const stale = await api(driverA, departPath, 'POST', { expectedVersion: 7 }); rejected(stale, 409, 'E1701', 'VERSION_CONFLICT'); expect(stale.data.actualVersion).toBe(0)
    await ownDetail(driverA, first, 'WAITING'); await unchangedDispatch(page, first, before)

    // The first actual success commits before its browser response is lost.
    // Both subsequent requests retain exact raw body/key across a real CSRF403.
    const departedKey = `execution-${randomUUID()}`, originalBody = { expectedVersion: 0 }, loss = await loseDeparture(driverA, first, collectors.get(info.testId)!)
    let departed: Departure
    try {
      const lost = await api(driverA, departPath, 'POST', originalBody, { key: departedKey }).then(() => false, () => true)
      expect(lost).toBe(true); departed = await loss.committed
      rejected(await api(driverA, departPath, 'POST', originalBody, { key: departedKey }), 403, 'E0403', 'CSRF_INVALID')
      expect(loss.attempts()).toBe(2)
      const replay = departure(await api<Departure>(driverA, departPath, 'POST', originalBody, { key: departedKey }), first, true)
      expect(isDeepStrictEqual(replay, departed)).toBe(true); expect(loss.matching()).toBe(true); expect(loss.attempts()).toBe(3)
    } finally { await loss.dispose() }
    await ownDetail(driverA, first, 'DEPARTED'); await unchangedDispatch(page, first, before)
    // Mathematical integer canonicalization is independent of body spelling.
    const canonicalReplay = departure(await api<Departure>(driverA, departPath, 'POST', undefined, { key: departedKey, raw: '{"expectedVersion":0.0}' }), first, true)
    expect(isDeepStrictEqual(canonicalReplay, departed!)).toBe(true)
    departure(await api<Departure>(driverA, `/tms/my-tasks/${first.assignment.id.toUpperCase()}/depart`, 'POST', originalBody, { key: departedKey }), first, true)
    rejected(await api(driverA, departPath, 'POST', { expectedVersion: 1 }, { key: departedKey }), 409, 'E1704', 'IDEMPOTENCY_KEY_REUSED')
    rejected(await api(driverA, departPath, 'POST', originalBody, { key: departedKey.toUpperCase() }), 409, 'E1701', 'VERSION_CONFLICT')
    const outdated = await api(driverA, departPath, 'POST', { expectedVersion: 0 }); rejected(outdated, 409, 'E1701', 'VERSION_CONFLICT'); expect(outdated.data.actualVersion).toBe(1)
    rejected(await api(driverA, departPath, 'POST', { expectedVersion: 1 }), 409, 'E1702', 'INVALID_STATE')
    const spareB = await makeResources('B')
    rejected(await api(driverB, ownPath), 404, 'E0404'); rejected(await api(driverB, departPath, 'POST', originalBody, { key: departedKey }), 404, 'E0404')
    rejected(await api(page, `/tms/tasks/${first.dependentId}/dispatch`, 'POST', { expectedGeneration: 0, driverId: spareB.driver.id, mainVehicleId: spareB.main.id, trailerId: null }), 409, 'E1604', 'PREREQUISITE_NOT_READY')
    rejected(await api(page, `/tms/dispatches/${first.assignment.id}/revoke`, 'POST', { expectedGeneration: 1, reason: '合成出發後不可撤派' }), 409, 'E1603', 'INVALID_STATE')
    rejected(await api(dispatcher, `/tms/dispatches/${first.assignment.id}/reassign`, 'POST', { expectedGeneration: 1, driverId: spareB.driver.id, mainVehicleId: spareB.main.id, trailerId: null, reason: '合成出發後不可改派' }), 409, 'E1603', 'INVALID_STATE')
    await unchangedDispatch(page, first, before)

    // A single actor changing binding must lose its previous receipt as well as
    // its previous detail, even though the actor/path/key ledger still exists.
    const revokedFirst = await makeScenario('A', false, null)
    rejected(await api(driverA, ownPath), 404, 'E0404'); rejected(await api(driverA, departPath, 'POST', originalBody, { key: departedKey }), 404, 'E0404')
    await ownDetail(driverA, revokedFirst, 'WAITING', null)
    envelope(await api(page, `/tms/dispatches/${revokedFirst.assignment.id}/revoke`, 'POST', { expectedGeneration: 1, reason: '合成先撤派再出發' }))
    rejected(await api(driverA, `/tms/my-tasks/${revokedFirst.assignment.id}`), 404, 'E0404')
    rejected(await api(driverA, `/tms/my-tasks/${revokedFirst.assignment.id}/depart`, 'POST', { expectedVersion: 0 }), 404, 'E0404')

    const reassignedFirst = await makeScenario('A', false, { name: ' 合成 e\u0301 聯絡 ', phone: null }), nextB = await makeResources('B')
    await ownDetail(driverA, reassignedFirst, 'WAITING', { name: ' 合成 e\u0301 聯絡 ', phone: null })
    const changeBody = { expectedGeneration: 1, driverId: nextB.driver.id, mainVehicleId: nextB.main.id, trailerId: null, reason: '合成先改派再出發' }, changeKey = randomUUID()
    const changed = envelope(await api<Command>(dispatcher, `/tms/dispatches/${reassignedFirst.assignment.id}/reassign`, 'POST', changeBody, { key: changeKey }))
    rejected(await api(driverA, `/tms/my-tasks/${reassignedFirst.assignment.id}`), 404, 'E0404')
    rejected(await api(driverA, `/tms/my-tasks/${reassignedFirst.assignment.id}/depart`, 'POST', { expectedVersion: 0 }), 404, 'E0404')
    const forB: Scenario = { ...reassignedFirst, assignment: changed.assignment, resources: nextB }
    await ownDetail(driverB, forB, 'WAITING', { name: ' 合成 e\u0301 聯絡 ', phone: null })
    const changedBefore = envelope(await api<DispatchDetail>(page, `/tms/tasks/${forB.taskId}/dispatch`))
    departure(await api<Departure>(driverB, `/tms/my-tasks/${forB.assignment.id}/depart`, 'POST', { expectedVersion: 0 }), forB, false)
    const historical = await api<Command>(dispatcher, `/tms/dispatches/${reassignedFirst.assignment.id}/reassign`, 'POST', changeBody, { key: changeKey })
    envelope(historical); expect(historical.replayed).toBe('true'); expect(isDeepStrictEqual(historical.data, changed)).toBe(true)
    const historicalAssign = await api<Command>(page, `/tms/tasks/${forB.taskId}/dispatch`, 'POST', reassignedFirst.assignBody, { key: reassignedFirst.assignKey })
    envelope(historicalAssign); expect(historicalAssign.replayed).toBe('true'); expect(historicalAssign.data.assignment.id).toBe(reassignedFirst.assignment.id)
    await unchangedDispatch(page, forB, changedBefore)

    // Real simultaneous requests accept either serialized winner, never both.
    // Deterministic lock/failure injection belongs to backend integration tests.
    const revokeRace = await makeScenario('A')
    const [racedDeparture, racedRevoke] = await Promise.all([
      api<Departure>(driverA, `/tms/my-tasks/${revokeRace.assignment.id}/depart`, 'POST', { expectedVersion: 0 }),
      api<Command>(page, `/tms/dispatches/${revokeRace.assignment.id}/revoke`, 'POST', { expectedGeneration: 1, reason: '合成撤派與出發競爭' }),
    ])
    expect([racedDeparture.status, racedRevoke.status].filter((status) => status === 200)).toHaveLength(1)
    const revokeFinal = envelope(await api<DispatchDetail>(page, `/tms/tasks/${revokeRace.taskId}/dispatch`))
    if (racedDeparture.status === 200) {
      departure(racedDeparture, revokeRace, false); rejected(racedRevoke, 409, 'E1603', 'INVALID_STATE')
      expect(revokeFinal.task.generation).toBe(1); expect(revokeFinal.current!.reservations.every((entry) => entry.releasedAt === null)).toBe(true)
      await ownDetail(driverA, revokeRace, 'DEPARTED')
    } else {
      rejected(racedDeparture, 404, 'E0404'); envelope(racedRevoke); expect(revokeFinal.task.generation).toBe(2); expect(revokeFinal.current).toBeNull()
      expect(revokeFinal.history.content[0].reservations.every((entry) => entry.releasedAt !== null)).toBe(true)
    }

    const reassignRace = await makeScenario('A'), raceB = await makeResources('B')
    const [racedDepart2, racedChange] = await Promise.all([
      api<Departure>(driverA, `/tms/my-tasks/${reassignRace.assignment.id}/depart`, 'POST', { expectedVersion: 0 }),
      api<Command>(dispatcher, `/tms/dispatches/${reassignRace.assignment.id}/reassign`, 'POST', { expectedGeneration: 1, driverId: raceB.driver.id, mainVehicleId: raceB.main.id, trailerId: null, reason: '合成改派與出發競爭' }),
    ])
    expect([racedDepart2.status, racedChange.status].filter((status) => status === 200)).toHaveLength(1)
    const changeFinal = envelope(await api<DispatchDetail>(page, `/tms/tasks/${reassignRace.taskId}/dispatch`))
    if (racedDepart2.status === 200) {
      departure(racedDepart2, reassignRace, false); rejected(racedChange, 409, 'E1603', 'INVALID_STATE')
      expect(changeFinal.task.generation).toBe(1); expect(changeFinal.current!.id).toBe(reassignRace.assignment.id)
      expect(changeFinal.current!.reservations.every((entry) => entry.releasedAt === null)).toBe(true)
    } else {
      rejected(racedDepart2, 404, 'E0404'); const moved = envelope(racedChange)
      expect(changeFinal.task.generation).toBe(2); expect(changeFinal.current!.id).toBe(moved.assignment.id)
      expect(changeFinal.history.content.find((entry) => entry.id === reassignRace.assignment.id)!.reservations.every((entry) => entry.releasedAt !== null)).toBe(true)
      await ownDetail(driverB, { ...reassignRace, assignment: moved.assignment, resources: raceB }, 'WAITING')
    }

    // A reused key is a new command on another canonical assignment path.
    const doubled = await makeScenario('A'), doubleKey = departedKey, doublePath = `/tms/my-tasks/${doubled.assignment.id}/depart`
    const doubleBefore = envelope(await api<DispatchDetail>(page, `/tms/tasks/${doubled.taskId}/dispatch`))
    const responses = await Promise.all([api<Departure>(driverA, doublePath, 'POST', { expectedVersion: 0 }, { key: doubleKey }), api<Departure>(driverA, doublePath, 'POST', { expectedVersion: 0 }, { key: doubleKey })])
    expect(responses.filter((reply) => reply.status === 200 && reply.replayed === 'false')).toHaveLength(1)
    let initial: Departure | undefined
    for (const reply of responses) {
      if (reply.status === 200) { const value = departure(reply, doubled, reply.replayed === 'true'); if (initial) expect(isDeepStrictEqual(initial, value)).toBe(true); else initial = value }
      else rejected(reply, 409, 'E1705', 'IDEMPOTENCY_IN_PROGRESS')
    }
    const doubleReplay = departure(await api<Departure>(driverA, doublePath, 'POST', { expectedVersion: 0 }, { key: doubleKey }), doubled, true)
    expect(isDeepStrictEqual(doubleReplay, initial)).toBe(true); await ownDetail(driverA, doubled, 'DEPARTED'); await unchangedDispatch(page, doubled, doubleBefore)
    // New execution does not widen the original fourteen-field list.
    const finalList = envelope(await api<PageData<Body>>(driverA, '/tms/my-tasks?size=100'))
    for (const item of finalList.content) exact(item, myTaskFields)
    const actors = envelope(await api<Account[]>(page, '/upms/accounts')), actor = actors.find((value) => value.id === fixture.driverA.id)!
    envelope(await api(page, `/upms/accounts/${actor.id}`, 'PATCH', { version: actor.version, displayName: actor.displayName, role: actor.role, enabled: false }))
    rejected(await api(driverA, `/tms/my-tasks/${doubled.assignment.id}`), 401, 'E0401')
    rejected(await api(driverA, doublePath, 'POST', { expectedVersion: 0 }, { key: doubleKey }), 401, 'E0401')
    rejected(await api(driverB, `/tms/my-tasks/${doubled.assignment.id}`), 404, 'E0404')
  } finally { await Promise.all(contexts.map((context) => context.close())) }
})
