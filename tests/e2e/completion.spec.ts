import { createHash, randomUUID } from 'node:crypto'
import { readFileSync, writeFileSync } from 'node:fs'
import { request as httpsRequest } from 'node:https'
import { crc32, deflateSync } from 'node:zlib'
import { isDeepStrictEqual } from 'node:util'
import { expect, test, type Browser, type BrowserContext, type Page, type Request, type Response, type Route, type TestInfo } from '@playwright/test'
import { completionFixtureFor, signIn, type Credential } from './fixtures'

// M404 adds API capability and truthful old record wording, not the M406 UI.
// Private PNGs stay in memory; explicit screenshots contain only legacy records.
test.use({ screenshot: 'off' })
test.describe.configure({ retries: 0 })
type Body = Record<string, unknown>
interface Reply<T> { status: number; code: string; data: T; payload: Body; replayed: string | null; cache: string | null; completionRoute: boolean; noLocation: boolean }
interface PageData<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }
interface Execution { phase: string; version: number; departedAt: string | null }
interface Witness { id: string; completedAt: string; releasedAt: string; policyMode: string; safeReleaseConfirmed: boolean }
interface CompleteResult { assignmentId: string; taskId: string; execution: Execution; completion: Witness }
interface CompleteDetail extends Body { assignmentId: string; taskId: string; taskNo: string; execution: Execution; completion: Witness | null }
interface Requirements { evidenceBasis: string; minimumCount: number; allowedMediaTypes: string[]; maxBytes: number; maxPixels: number; requiresSafeReleaseConfirmation: boolean }
interface Policy { assignmentId: string; taskId: string; execution: Execution; mode: string; canComplete: boolean; blocker: string | null; requirements: Requirements | null; requiredEvidenceIds: string[] | null }
interface Driver { id: string; driverCode: string; version: number }
interface Vehicle { id: string; plateNo: string; version: number }
interface Account { id: string; role: string; displayName: string; enabled: boolean; version: number }
interface Order extends Body { id: string; version: number; tasks: { id: string; clientRef: string }[] }
interface Assignment extends Body { id: string; taskId: string; status: string; version: number; endedAt: string | null; reservations: { resourceId: string; releasedAt: string | null; releaseReason: string | null }[] }
interface DispatchCommand { assignment: Assignment; generation: number; taskId: string }
interface Evidence { id: string; assignmentId: string; taskId: string; phase: string; executionVersion: number; mediaType: string; byteSize: number; sha256: string; uploadedAt: string; status: string }
interface RequestFact { id: string; evidenceIds: string[]; uploadedCount: number }
interface ExceptionCase { id: string; version: number; status: string; description: string; activeRequest: RequestFact | null }
interface CompleteBody { expectedVersion: number; evidenceIds: string[]; confirmSafeRelease: true }
interface Scenario { order: Order; driver: Driver; main: Vehicle; trailer: Vehicle | null; assignment: Assignment; taskId: string; dependentId: string; execution: Execution; saved: Body; dispatchBody: Body; dispatchKey: string; dispatchResult: DispatchCommand; departKey: string; readyIds: string[]; readyBody: Body; readyKey: string; photoId: string; photoKey: string }
interface Captured { schema: string; value: unknown }
type CompletionSchema = 'CompletionDetailEnvelope' | 'CompletionPolicyEnvelope' | 'CompleteMyTaskEnvelope' | 'ErrorEnvelope'
const hash = (bytes: Buffer) => createHash('sha256').update(bytes).digest('hex')
const safeFields = ['assignmentId', 'taskId', 'taskNo', 'item', 'taskKind', 'pickupName', 'pickupAddress', 'deliveryName', 'deliveryAddress', 'siteContact', 'plannedStartAt', 'plannedEndAt', 'assignedAt', 'policyMode']

function png(width = 2, height = 2, color = 97, padding = 0): Buffer {
  const chunk = (name: string, data: Buffer) => { const type = Buffer.from(name), length = Buffer.alloc(4), checksum = Buffer.alloc(4); length.writeUInt32BE(data.length); checksum.writeUInt32BE(crc32(Buffer.concat([type, data]))); return Buffer.concat([length, type, data, checksum]) }
  const header = Buffer.alloc(13); header.writeUInt32BE(width, 0); header.writeUInt32BE(height, 4); header[8] = 8; header[9] = 6
  const scanlines = Buffer.alloc((width * 4 + 1) * height)
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) { const offset = y * (width * 4 + 1) + 1 + x * 4; scanlines[offset] = color; scanlines[offset + 1] = 137; scanlines[offset + 2] = 211; scanlines[offset + 3] = 255 }
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header), ...(padding ? [chunk('tEXt', Buffer.concat([Buffer.from('synthetic\0'), Buffer.alloc(padding, 65)]))] : []), chunk('IDAT', deflateSync(scanlines)), chunk('IEND', Buffer.alloc(0))])
}
class ResponseCollector {
  readonly completion: Captured[] = []; readonly exceptions: Captured[] = []; readonly progress: Captured[] = []; readonly execution: Captured[] = []; readonly tms: Captured[] = []; readonly orders: Captured[] = []; readonly acceptances: Captured[] = []; readonly vehicles: Captured[] = []; readonly drivers: Captured[] = []
  private readonly pages = new Set<Page>(); private readonly pending = new Set<Promise<void>>()
  private readonly capture = (response: Response) => {
    const path = new URL(response.url()).pathname, method = response.request().method()
    let schema: string | undefined, target: Captured[] | undefined
    const manager = /^\/api\/v1\/tms\/exceptions(?:\/|$)/.test(path)
    const self = /^\/api\/v1\/tms\/my-tasks\/[0-9a-f-]{36}\/exceptions(?:\/|$)/i.test(path)
    if (/^\/api\/v1\/tms\/my-tasks\/[0-9a-f-]{36}\/(completion|completion-policy|complete)$/i.test(path)) { target = this.completion; schema = path.endsWith('/completion-policy') ? 'CompletionPolicyEnvelope' : path.endsWith('/complete') ? 'CompleteMyTaskEnvelope' : 'CompletionDetailEnvelope' }
    else if (manager || self) {
      if (path.endsWith('/content') && response.status() < 400) return
      target = this.exceptions
      if (path.endsWith('/history')) schema = manager ? 'ManagerExceptionHistoryPageEnvelope' : 'DriverExceptionHistoryPageEnvelope'
      else if (path.endsWith('/content')) schema = 'ErrorEnvelope'
      else if (path.endsWith('/evidence')) schema = method === 'POST' ? 'SupplementUploadEnvelope' : 'SupplementEvidencePageEnvelope'
      else if (method === 'POST') schema = 'ExceptionRecordEnvelope'
      else if (path === '/api/v1/tms/exceptions' || /\/my-tasks\/[0-9a-f-]{36}\/exceptions$/i.test(path)) schema = 'ExceptionSummaryPageEnvelope'
      else schema = 'ExceptionDetailEnvelope'
    } else if (/^\/api\/v1\/tms\/my-tasks\/[0-9a-f-]{36}\/(progress-policy|progress|evidence(?:\/[0-9a-f-]{36}\/content)?)$/i.test(path)) {
      if (path.endsWith('/content') && response.status() < 400) return
      target = this.progress; schema = path.endsWith('/progress-policy') ? 'ProgressPolicyEnvelope' : path.endsWith('/progress') ? 'ProgressEnvelope' : method === 'POST' ? 'EvidenceEnvelope' : 'EvidencePageEnvelope'
    } else if (/^\/api\/v1\/tms\/my-tasks\/[0-9a-f-]{36}(?:\/depart)?$/i.test(path)) { target = this.execution; schema = path.endsWith('/depart') ? 'DepartMyTaskEnvelope' : 'MyTaskExecutionEnvelope' }
    else if (/^\/api\/v1\/tms\/tasks\/[0-9a-f-]{36}\/dispatch$/i.test(path)) { target = this.tms; schema = method === 'GET' ? 'DispatchDetailEnvelope' : 'CommandResultEnvelope' }
    else if (/^\/api\/v1\/tms\/tasks$/.test(path)) { target = this.tms; schema = 'TaskPageEnvelope' }
    else if (path === '/api/v1/tms/my-tasks' && method === 'GET') { target = this.tms; schema = 'MyTaskPageEnvelope' }
    else if (path === '/api/v1/tms/resources' && method === 'GET') { target = this.tms; schema = 'ResourcePageEnvelope' }
    else if (/^\/api\/v1\/tms\/dispatches\/[0-9a-f-]{36}\/(revoke|reassign)$/i.test(path)) { target = this.tms; schema = 'CommandResultEnvelope' }
    else if (/^\/api\/v1\/orders\/[0-9a-f-]{36}\/accept$/i.test(path)) { target = this.acceptances; schema = 'AcceptedOrderEnvelope' }
    else if (path === '/api/v1/orders' || /^\/api\/v1\/orders\/[0-9a-f-]{36}$/i.test(path)) { target = this.orders; schema = 'OrderEnvelope' }
    else if (path === '/api/v1/fms/vehicles' || /^\/api\/v1\/fms\/vehicles\/[0-9a-f-]{36}$/i.test(path)) { target = this.vehicles; schema = 'VehicleEnvelope' }
    else if (path === '/api/v1/fms/drivers' || /^\/api\/v1\/fms\/drivers\/[0-9a-f-]{36}$/i.test(path)) { target = this.drivers; schema = 'DriverEnvelope' }
    if (!schema || !target) return
    const name = response.status() >= 400 ? 'ErrorEnvelope' : schema, destination = target
    const pending = (async () => { try { destination.push({ schema: name, value: await response.json() }) } catch { /* Bodyless/unreadable responses are not claimed JSON evidence. */ } })()
    this.pending.add(pending); void pending.then(() => this.pending.delete(pending), () => this.pending.delete(pending))
  }
  watch(page: Page) { if (!this.pages.has(page)) { this.pages.add(page); page.on('response', this.capture) } }
  record(schema: CompletionSchema, value: Body) { this.completion.push({ schema, value }) }
  async finish() { for (const page of this.pages) page.off('response', this.capture); await Promise.allSettled([...this.pending]) }
}
const collectors = new Map<string, ResponseCollector>()
test.beforeEach(async ({ page }, info) => { const collector = new ResponseCollector(); collector.watch(page); collectors.set(info.testId, collector) })
test.afterEach(async ({}, info) => {
  const collector = collectors.get(info.testId); if (!collector) return; await collector.finish()
  // Original public synthetic JSON only. No private PNG, request body, raw
  // headers, keys, cookies, credentials or trace archives enter saved evidence.
  for (const [name, values] of [['completion', collector.completion], ['exception', collector.exceptions], ['progress-evidence', collector.progress], ['execution', collector.execution], ['tms', collector.tms], ['order', collector.orders], ['acceptance', collector.acceptances], ['vehicle', collector.vehicles], ['driver', collector.drivers]] as const) {
    if (values.length) writeFileSync(info.outputPath(`${name}-contract-responses.json`), JSON.stringify(values))
  }
  collectors.delete(info.testId)
})
interface Options { key?: string; raw?: string | number[]; executionVersion?: number; exceptionVersion?: number; media?: string; csrf?: 'invalid' | 'omit'; headers?: Record<string, string> }
async function api<T = Body>(page: Page, path: string, method = 'GET', body?: unknown, options: Options = {}): Promise<Reply<T>> {
  return page.evaluate(async ({ path, method, body, options }) => {
    const headers: Record<string, string> = { Accept: path.split('?')[0].endsWith('/content') ? 'image/png' : 'application/json', ...options.headers }
    if (method !== 'GET') {
      if (options.csrf !== 'omit') { const csrf = (await (await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' })).json()).data; if (csrf?.token) headers[csrf.headerName] = options.csrf === 'invalid' ? 'synthetic-invalid-csrf' : csrf.token }
      headers['Content-Type'] = options.media ?? 'application/json'; headers['Idempotency-Key'] = options.key ?? crypto.randomUUID()
      if (options.executionVersion !== undefined) headers['X-Execution-Version'] = String(options.executionVersion)
      if (options.exceptionVersion !== undefined) headers['X-Exception-Version'] = String(options.exceptionVersion)
    }
    const wire = Array.isArray(options.raw) ? new Uint8Array(options.raw) : options.raw ?? (body === undefined ? undefined : JSON.stringify(body))
    const response = await fetch(`/api/v1${path}`, { method, credentials: 'same-origin', headers, ...(wire === undefined ? {} : { body: wire }) })
    const payload = await response.json()
    return { status: response.status, code: payload.code, data: payload.data, payload, replayed: response.headers.get('Idempotency-Replayed'), cache: response.headers.get('Cache-Control'), completionRoute: /\/(completion|completion-policy|complete)$/.test(path.split('?')[0]), noLocation: !response.headers.has('Location') }
  }, { path, method, body, options })
}
function exact(value: object, keys: readonly string[]) { expect(Object.keys(value).sort()).toEqual([...keys].sort()) }
function ok<T>(reply: Reply<T>, status = 200, code = '0000'): T { if (reply.completionRoute) { expect(reply.cache).toBe('no-store, private'); expect(reply.noLocation).toBe(true) }; expect(reply.status).toBe(status); expect(reply.code).toBe(code); exact(reply.payload, ['code', 'message', 'data', 'timestamp']); expect(typeof reply.payload.timestamp === 'string' && reply.payload.timestamp.endsWith('+08:00')).toBe(true); return reply.data }
function rejected(reply: Reply<unknown>, status: number, code: string, reason?: string) { const data = ok(reply, status, code); if (reason) expect((data as Body)?.reason).toBe(reason); if ([401, 404, 405, 413, 415, 500].includes(status)) expect(data).toBeNull(); expect(reply.replayed).toBeNull() }
async function contextFor(browser: Browser, info: TestInfo): Promise<BrowserContext> { const use = info.project.use; const context = await browser.newContext({ baseURL: use.baseURL, ignoreHTTPSErrors: use.ignoreHTTPSErrors, viewport: use.viewport, screen: use.screen, deviceScaleFactor: use.deviceScaleFactor, isMobile: use.isMobile, hasTouch: use.hasTouch, userAgent: use.userAgent }); context.on('page', (page) => collectors.get(info.testId)?.watch(page)); return context }
async function enter(page: Page, credential: Credential, temporary = false) { await page.goto('/'); expect(new URL(page.url()).protocol).toBe('https:'); await signIn(page, credential); await expect(page.getByRole(temporary ? 'heading' : 'button', { name: temporary ? '首次登入，請先修改密碼' : '登出', exact: true })).toBeVisible() }
async function privatePng(page: Page, path: string, expected: Buffer, extra: Record<string, string> = {}) {
  const result = await page.evaluate(async ({ path, expected, extra }) => {
    const response = await fetch(`/api/v1${path}`, { credentials: 'same-origin', headers: { Accept: 'image/png', ...extra } })
    if (!response.ok) return { status: response.status, code: (await response.json()).code }
    const bytes = new Uint8Array(await response.arrayBuffer()), digest = [...new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))].map((byte) => byte.toString(16).padStart(2, '0')).join('')
    const bitmap = await createImageBitmap(new Blob([bytes], { type: 'image/png' })); const size = [bitmap.width, bitmap.height]; bitmap.close()
    return { status: response.status, equal: bytes.length === expected.length && bytes.every((byte, index) => byte === expected[index]), digest, size, length: response.headers.get('Content-Length'), media: response.headers.get('Content-Type'), cache: response.headers.get('Cache-Control'), nosniff: response.headers.get('X-Content-Type-Options'), disposition: response.headers.get('Content-Disposition'), forbidden: ['ETag', 'Last-Modified', 'Accept-Ranges', 'Location', 'Content-Location'].some((name) => response.headers.has(name)) }
  }, { path, expected: [...expected], extra })
  expect(result.status).toBe(200); expect(result.equal).toBe(true); expect(result.digest).toBe(hash(expected)); expect(result.size).toEqual([2, 2]); expect(result.length).toBe(String(expected.length)); expect(result.media).toBe('image/png'); expect(result.cache).toBe('no-store, private'); expect(result.nosniff).toBe('nosniff'); expect(result.disposition).toBe('inline; filename="evidence.png"'); expect(result.forbidden).toBe(false)
}

interface Forwarded { status: number; payload: Body; replayed?: string; cache?: string }
const safeForwardFailure = 'The controlled synthetic completion request could not be completed safely.'
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
          try { const value: unknown = JSON.parse(Buffer.concat(chunks).toString('utf8')); if (value === null || typeof value !== 'object' || Array.isArray(value)) { fail(); return }; finish(() => resolve({ status: response.statusCode!, payload: value as Body, replayed: response.headers['idempotency-replayed'] as string | undefined, cache: response.headers['cache-control'] })) }
          catch { fail() }
        })
      })
      upstream.on('error', fail); deadline = setTimeout(cancel, 15_000); signal.addEventListener('abort', cancel, { once: true }); if (signal.aborted) { cancel(); return }; upstream.end(body)
    })
  } catch { throw new Error(safeForwardFailure) }
}
const completionPath = (scenario: Scenario, suffix: 'completion' | 'completion-policy' | 'complete') => `/tms/my-tasks/${scenario.assignment.id}/${suffix}`
const copySafe = (value: Body) => Object.fromEntries(safeFields.map((field) => [field, value[field]]))
async function detail(page: Page, scenario: Scenario, phase: string, version: number): Promise<CompleteDetail> {
  const reply = await api<CompleteDetail>(page, completionPath(scenario, 'completion')), value = ok(reply)
  exact(value, [...safeFields, 'execution', 'completion']); exact(value.execution, ['phase', 'version', 'departedAt'])
  expect(reply.cache).toBe('no-store, private'); expect(value.assignmentId).toBe(scenario.assignment.id); expect(value.taskId).toBe(scenario.taskId)
  expect(value.execution.phase).toBe(phase); expect(value.execution.version).toBe(version)
  expect(isDeepStrictEqual(copySafe(value), scenario.saved)).toBe(true)
  if (phase === 'COMPLETED') { expect(value.completion).not.toBeNull(); witness(value.completion!) }
  else expect(value.completion).toBeNull()
  return value
}
function witness(value: Witness) { exact(value, ['id', 'completedAt', 'releasedAt', 'policyMode', 'safeReleaseConfirmed']); expect(value.policyMode).toBe('SYNTHETIC_TEST_ONLY'); expect(value.safeReleaseConfirmed).toBe(true); expect(value.completedAt).toBe(value.releasedAt); expect(Number.isFinite(Date.parse(value.completedAt))).toBe(true) }
function complete(reply: Reply<CompleteResult>, scenario: Scenario, replayed = false): CompleteResult {
  const value = ok(reply); exact(value, ['assignmentId', 'taskId', 'execution', 'completion']); exact(value.execution, ['phase', 'version', 'departedAt']); witness(value.completion)
  expect(value.assignmentId).toBe(scenario.assignment.id); expect(value.taskId).toBe(scenario.taskId)
  expect(value.execution).toEqual({ phase: 'COMPLETED', version: 4, departedAt: scenario.execution.departedAt }); expect(reply.replayed).toBe(String(replayed)); expect(reply.cache).toBe('no-store, private')
  return value
}
async function policy(page: Page, scenario: Scenario, canComplete: boolean, blocker: string | null): Promise<Policy> {
  const reply = await api<Policy>(page, completionPath(scenario, 'completion-policy')), value = ok(reply)
  exact(value, ['assignmentId', 'taskId', 'execution', 'mode', 'canComplete', 'blocker', 'requirements', 'requiredEvidenceIds']); expect(reply.cache).toBe('no-store, private')
  expect(value.assignmentId).toBe(scenario.assignment.id); expect(value.taskId).toBe(scenario.taskId); expect(value.mode).toBe('SYNTHETIC_TEST_ONLY'); expect(value.canComplete).toBe(canComplete); expect(value.blocker).toBe(blocker)
  expect(value.execution).toEqual(blocker === 'ALREADY_COMPLETED' ? { ...scenario.execution, phase: 'COMPLETED', version: 4 } : scenario.execution)
  if (canComplete) {
    expect(value.execution).toEqual(scenario.execution)
    expect(value.requirements).toEqual({ evidenceBasis: 'LATEST_AWAITING_TRANSITION', minimumCount: 1, allowedMediaTypes: ['image/png'], maxBytes: 65536, maxPixels: 4096, requiresSafeReleaseConfirmation: true })
    expect(value.requiredEvidenceIds).toEqual([...scenario.readyIds].sort())
  } else { expect(value.requirements).toBeNull(); expect(value.requiredEvidenceIds).toBeNull() }
  return value
}
async function prepare(manager: Page, driverPage: Page, accountId: string, fixture: ReturnType<typeof completionFixtureFor>, sequence: number, withTrailer: boolean, red: Buffer, blue: Buffer): Promise<Scenario> {
  const scope = fixture.scope
  const driver = ok(await api<Driver>(manager, '/fms/drivers', 'POST', { driverCode: `SYNTHETIC_TMS_${scope}_D${sequence}`, name: `合成完成司機 ${scope} ${sequence}`, accountId, employmentStatus: 'ACTIVE', dutyStatus: 'ON_DUTY' }), 201)
  const main = ok(await api<Vehicle>(manager, '/fms/vehicles', 'POST', { plateNo: `CMP-${scope[0]}-${randomUUID().slice(0, 10)}`, vehicleType: fixture.vehicleTypes.main.id, capacityTon: '1.00', administrativeStatus: 'AVAILABLE' }), 201)
  const trailer = withTrailer ? ok(await api<Vehicle>(manager, '/fms/vehicles', 'POST', { plateNo: `CMP-${scope[0]}-T-${randomUUID().slice(0, 10)}`, vehicleType: fixture.vehicleTypes.trailer.id, administrativeStatus: 'AVAILABLE' }), 201) : null
  const customer = ok(await api<{ id: string }>(manager, '/crm/customers', 'POST', { customerCode: `COMPLETE_${scope}_${randomUUID().slice(0, 8).toUpperCase()}`, name: `合成完成客戶 ${sequence}` }), 201)
  const draft = { type: 'LOCAL', customerId: customer.id, remark: '整單私有內容不進本人完成摘要', items: [{ clientRef: 'completion-item', sequence: 1, itemKind: 'CARGO', packageQty: 7, grossWeight: '0.0000000000000000001', cbm: '0' }], tasks: Array.from({ length: 2 }, (_, index) => ({ clientRef: `completion-task-${index}`, itemRef: 'completion-item', taskKind: `SYNTHETIC_TMS_${scope}_COMPLETION_${sequence}_${index}`, pickupName: ` 合成 e\u0301 起點 ${index} `, pickupAddress: ` 合成\u0000地址\r\n${index}樓 `, deliveryName: `合成終點 ${index}`, deliveryAddress: `合成終點地址 ${index}`, siteContact: { name: ' 合成 e\u0301 聯絡 ', phone: null }, plannedStartAt: '1500-01-02T08:00:00.123456+23:59', plannedEndAt: '1500-01-02T10:00:00.123456+23:59', requiredTask: true, predecessorRefs: index ? ['completion-task-0'] : [] })) }
  const created = await api<Order>(manager, '/orders', 'POST', draft); expect(created.status).toBe(201); expect(created.code).toBe('OK')
  const accepted = await api<Order>(manager, `/orders/${created.data.id}/accept`, 'POST', { expectedVersion: created.data.version }); expect(accepted.status).toBe(200); expect(accepted.code).toBe('OK')
  const taskId = accepted.data.tasks.find((task) => task.clientRef === 'completion-task-0')!.id, dependentId = accepted.data.tasks.find((task) => task.clientRef === 'completion-task-1')!.id
  const dispatchBody = { expectedGeneration: 0, driverId: driver.id, mainVehicleId: main.id, trailerId: trailer?.id ?? null }, dispatchKey = randomUUID()
  const dispatchResult = ok(await api<DispatchCommand>(manager, `/tms/tasks/${taskId}/dispatch`, 'POST', dispatchBody, { key: dispatchKey }))
  const waitingReply = await api<CompleteDetail>(driverPage, `/tms/my-tasks/${dispatchResult.assignment.id}/completion`), waiting = ok(waitingReply)
  exact(waiting, [...safeFields, 'execution', 'completion']); expect(waiting.execution).toEqual({ phase: 'WAITING', version: 0, departedAt: null }); expect(waiting.completion).toBeNull(); expect(waitingReply.cache).toBe('no-store, private')
  const scenario: Scenario = { order: accepted.data, driver, main, trailer, assignment: dispatchResult.assignment, taskId, dependentId, execution: waiting.execution, saved: copySafe(waiting), dispatchBody, dispatchKey, dispatchResult, departKey: randomUUID(), readyIds: [], readyBody: {}, readyKey: randomUUID(), photoId: '', photoKey: randomUUID() }
  const base = `/tms/my-tasks/${scenario.assignment.id}`
  scenario.execution = ok(await api<{ execution: Execution }>(driverPage, `${base}/depart`, 'POST', { expectedVersion: 0 }, { key: scenario.departKey })).execution
  await detail(driverPage, scenario, 'DEPARTED', 1); await policy(driverPage, scenario, false, 'UNSUPPORTED_PHASE')
  const departedPhoto = ok(await api<Evidence>(driverPage, `${base}/evidence`, 'POST', undefined, { raw: [...red], media: 'image/png', executionVersion: 1 }))
  expect(departedPhoto.sha256).toBe(hash(red))
  scenario.execution = ok(await api<{ execution: Execution }>(driverPage, `${base}/progress`, 'POST', { expectedVersion: 1, targetPhase: 'IN_PROGRESS', evidenceIds: [departedPhoto.id] })).execution
  const first = ok(await api<Evidence>(driverPage, `${base}/evidence`, 'POST', undefined, { raw: [...blue], media: 'image/png', executionVersion: 2 },))
  const last = ok(await api<Evidence>(driverPage, `${base}/evidence`, 'POST', undefined, { raw: [...red], media: 'image/png', executionVersion: 2, key: scenario.photoKey }))
  expect(first.sha256).toBe(hash(blue)); expect(last.sha256).toBe(hash(red)); scenario.photoId = last.id
  scenario.readyIds = [first.id, last.id].sort()
  scenario.readyBody = { expectedVersion: 2, targetPhase: 'AWAITING_COMPLETION', evidenceIds: [last.id.toUpperCase(), first.id] }
  scenario.execution = ok(await api<{ execution: Execution }>(driverPage, `${base}/progress`, 'POST', scenario.readyBody, { key: scenario.readyKey })).execution
  expect(scenario.execution.phase).toBe('AWAITING_COMPLETION'); expect(scenario.execution.version).toBe(3)
  await detail(driverPage, scenario, 'AWAITING_COMPLETION', 3); await policy(driverPage, scenario, true, null)
  return scenario
}
async function resolveException(manager: Page, driverPage: Page, scenario: Scenario, original: ExceptionCase, bytes: Buffer): Promise<{ record: ExceptionCase; photo: Evidence; report: ExceptionCase }> {
  const base = `/tms/my-tasks/${scenario.assignment.id}/exceptions/${original.id}`
  const asked = ok(await api<ExceptionCase>(manager, `/tms/exceptions/${original.id}/supplement-requests`, 'POST', { expectedVersion: original.version, reason: ' 合成完成前補件 e\u0301 \u0000 原文 ' }))
  const photoReply = ok(await api<{ version: number; evidence: Evidence }>(driverPage, `${base}/supplement-requests/${asked.activeRequest!.id}/evidence`, 'POST', undefined, { raw: [...bytes], media: 'image/png', exceptionVersion: asked.version, executionVersion: 3 }))
  expect(photoReply.evidence.phase).toBe('AWAITING_COMPLETION'); expect(photoReply.evidence.sha256).toBe(hash(bytes))
  const submitted = ok(await api<ExceptionCase>(driverPage, `${base}/supplement-requests/${asked.activeRequest!.id}/submit`, 'POST', { expectedVersion: photoReply.version, note: ' 合成完成前明確補件 e\u0301 \u0000 ', evidenceIds: [photoReply.evidence.id] }))
  const resolved = ok(await api<ExceptionCase>(manager, `/tms/exceptions/${original.id}/resolve`, 'POST', { expectedVersion: submitted.version, reason: ' 合成異常已另行處置，不代表安全釋放 ' }))
  expect(resolved.status).toBe('RESOLVED'); expect(resolved.version).toBe(4)
  return { record: resolved, photo: photoReply.evidence, report: original }
}
async function available(manager: Page, scenario: Scenario, occupied: boolean, assignmentId?: string) {
  for (const [position, resource] of [['DRIVER', { id: scenario.driver.id, code: scenario.driver.driverCode }], ['MAIN', { id: scenario.main.id, code: scenario.main.plateNo }], ...(scenario.trailer ? [['TRAILER', { id: scenario.trailer.id, code: scenario.trailer.plateNo }]] : [])] as [string, { id: string; code: string }][]) {
    const page = ok(await api<PageData<{ id: string; occupied: boolean; occupation: { assignmentId: string } | null }>>(manager, `/tms/resources?taskId=${scenario.dependentId}&position=${position}&q=${encodeURIComponent(resource.code)}`))
    const value = page.content.find((candidate) => candidate.id === resource.id)!; expect(value).toBeDefined(); expect(value.occupied).toBe(occupied)
    if (assignmentId) expect(value.occupation!.assignmentId).toBe(assignmentId); else if (!occupied) expect(value.occupation).toBeNull()
  }
}
async function legacyRecord(page: Page, scenario: Scenario, info: TestInfo, name: string) {
  await page.bringToFront()
  await page.getByRole('navigation', { name: '主要導覽', exact: true }).getByRole('button', { name: '本人運輸任務', exact: true }).click()
  await expect(page.getByRole('heading', { name: '本人指派任務', exact: true })).toBeVisible()
  const card = page.getByRole('article', { name: `本人任務 ${scenario.saved.taskNo}`, exact: true })
  await expect(card).toBeVisible(); await expect(card).toContainText('指派記錄 · 唯讀')
  await expect(page.getByText(/本清單不表示仍執行或資源仍占用/)).toBeVisible()
  await expect(page.getByRole('button', { name: /確認完成|完成任務|安全釋放/ })).toHaveCount(0)
  await expect(page.locator('img[src^="blob:"]')).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.evaluate(() => { if (document.activeElement instanceof HTMLElement) document.activeElement.blur(); scrollTo({ top: 0, left: 0, behavior: 'instant' }) })
  await page.screenshot({ path: info.outputPath(name), fullPage: true })
}
async function loseCompletion(page: Page, scenario: Scenario, body: CompleteBody, collector: ResponseCollector) {
  const origin = new URL(page.url()).origin, path = `/api/v1${completionPath(scenario, 'complete')}`, cancellation = new AbortController(), active = new Set<Promise<void>>()
  let attempts = 0, matching = false, key: string | undefined, original: Buffer | null = null
  let resolve!: (value: CompleteResult) => void, reject!: (error: Error) => void
  const committed = new Promise<CompleteResult>((yes, no) => { resolve = yes; reject = no }); void committed.catch(() => undefined)
  const deadline = setTimeout(() => { cancellation.abort(); reject(new Error(safeForwardFailure)) }, 20_000)
  const matches = (url: URL) => url.origin === origin && url.pathname === path
  const handle = async (route: Route) => {
    try {
      const request = route.request(); if (request.method() !== 'POST') { await route.continue(); return }
      attempts++; const incomingKey = (await request.allHeaders())['idempotency-key'], incoming = request.postDataBuffer()
      if (attempts === 1) {
        key = incomingKey; original = incoming
        if (!incoming || !key || !isDeepStrictEqual(JSON.parse(incoming.toString('utf8')), body)) throw new Error(safeForwardFailure)
        const response = await forward(request, origin, path, cancellation.signal), value = response.payload.data as CompleteResult
        if (response.status !== 200 || response.payload.code !== '0000' || response.cache !== 'no-store, private' || response.replayed !== 'false' || value.assignmentId !== scenario.assignment.id || value.taskId !== scenario.taskId || value.execution.phase !== 'COMPLETED' || value.execution.version !== 4 || value.execution.departedAt !== scenario.execution.departedAt || !value.completion.safeReleaseConfirmed) throw new Error(safeForwardFailure)
        collector.record('CompleteMyTaskEnvelope', response.payload)
        await route.abort('failed'); clearTimeout(deadline); resolve(value)
      } else {
        const same = Boolean(key && key === incomingKey && original && incoming && original.equals(incoming)); matching = attempts === 2 ? same : matching && same
        if (!same) throw new Error(safeForwardFailure)
        if (attempts === 2) {
          const response = await forward(request, origin, path, cancellation.signal, true)
          if (response.status !== 403 || response.payload.code !== 'E0403' || response.cache !== 'no-store, private' || (response.payload.data as Body)?.reason !== 'CSRF_INVALID') throw new Error(safeForwardFailure)
          await route.fulfill({ status: response.status, headers: { 'Content-Type': 'application/json', 'Cache-Control': response.cache }, body: JSON.stringify(response.payload) })
        } else await route.continue()
      }
    } catch { clearTimeout(deadline); reject(new Error(safeForwardFailure)); try { await route.abort('failed') } catch { /* Closing context. */ } }
  }
  const handler = (route: Route) => { const work = handle(route); active.add(work); void work.then(() => active.delete(work), () => active.delete(work)); return work }
  await page.route(matches, handler)
  return { committed, attempts: () => attempts, matching: () => matching, dispose: async () => { clearTimeout(deadline); cancellation.abort(); reject(new Error(safeForwardFailure)); await Promise.allSettled([...active]); await page.unroute(matches, handler) } }
}

test('completion adopts exact readiness proof, releases effective resources once and preserves narrow completed records over TLS', async ({ page, browser }, info) => {
  test.setTimeout(480_000)
  const fixture = completionFixtureFor(info.project.name), collector = collectors.get(info.testId)!
  const contexts = await Promise.all(Array.from({ length: 5 }, () => contextFor(browser, info)))
  try {
    const [dispatcher, driverA, driverB, temporary, anonymous] = await Promise.all(contexts.map((context) => context.newPage()))
    await enter(page, fixture.admin); await enter(dispatcher, fixture.dispatcher); await enter(driverA, fixture.driverA); await enter(driverB, fixture.driverB); await enter(temporary, fixture.temporary, true); await anonymous.goto('/')
    const red = png(), blue = png(2, 2, 203)
    expect(await driverA.evaluate(async (bytes) => { const image = await createImageBitmap(new Blob([new Uint8Array(bytes)], { type: 'image/png' })); const dimensions = [image.width, image.height]; image.close(); return dimensions }, [...red])).toEqual([2, 2])
    const a = await prepare(page, driverA, fixture.driverA.id, fixture, 5001, true, red, blue)
    const b = await prepare(page, driverB, fixture.driverB.id, fixture, 5002, false, red, blue)
    const unknown = randomUUID()
    for (const [actor, status, code] of [[page, 403, 'E0403'], [dispatcher, 403, 'E0403'], [temporary, 403, 'E0403'], [anonymous, 401, 'E0401']] as const) {
      for (const suffix of ['completion', 'completion-policy']) rejected(await api(actor, `/tms/my-tasks/${unknown}/${suffix}`), status, code)
      rejected(await api(actor, `/tms/my-tasks/${unknown}/complete`, 'POST', { expectedVersion: 3, evidenceIds: a.readyIds, confirmSafeRelease: true }, { csrf: status === 401 ? 'omit' : undefined }), status, code)
    }
    for (const suffix of ['completion', 'completion-policy']) rejected(await api(driverB, completionPath(a, suffix as 'completion' | 'completion-policy')), 404, 'E0404')
    rejected(await api(driverB, completionPath(a, 'complete'), 'POST', { expectedVersion: 3, evidenceIds: a.readyIds, confirmSafeRelease: true }), 404, 'E0404')
    rejected(await api(driverA, `${completionPath(a, 'completion')}?driverId=${fixture.driverA.id}`), 400, 'E0400')
    rejected(await api(driverA, `${completionPath(a, 'completion-policy')}?history=1`), 400, 'E0400')
    const completeA: CompleteBody = { expectedVersion: 3, evidenceIds: [...a.readyIds].reverse().map((id) => id.toUpperCase()), confirmSafeRelease: true }, completeKey = randomUUID()
    for (const invalid of [{ ...completeA, confirmSafeRelease: false }, { expectedVersion: 3, evidenceIds: a.readyIds }, { ...completeA, expectedVersion: '3' }, { ...completeA, evidenceIds: [a.readyIds[0], a.readyIds[0].toUpperCase()] }, { ...completeA, policyMode: 'SYNTHETIC_TEST_ONLY' }]) rejected(await api(driverA, completionPath(a, 'complete'), 'POST', invalid), 400, 'E0400')
    rejected(await api(driverA, completionPath(a, 'complete'), 'POST', { ...completeA, evidenceIds: [] }), 409, 'E2007', 'EVIDENCE_REQUIRED')
    rejected(await api(driverA, completionPath(a, 'complete'), 'POST', { ...completeA, evidenceIds: [a.readyIds[0]] }), 409, 'E2008', 'EVIDENCE_INVALID')
    rejected(await api(driverA, completionPath(a, 'complete'), 'POST', { ...completeA, evidenceIds: b.readyIds }), 409, 'E2008', 'EVIDENCE_INVALID')
    rejected(await api(driverA, completionPath(a, 'complete'), 'POST', completeA, { csrf: 'invalid' }), 403, 'E0403', 'CSRF_INVALID')

    const reportBody = { expectedExecutionVersion: 3, description: ' 合成完成前異常 e\u0301 \u0000 原文 ' }, reportKey = randomUUID()
    const originalException = ok(await api<ExceptionCase>(driverA, `/tms/my-tasks/${a.assignment.id}/exceptions`, 'POST', reportBody, { key: reportKey }))
    await policy(driverA, a, false, 'UNRESOLVED_EXCEPTIONS')
    rejected(await api(driverA, completionPath(a, 'complete'), 'POST', completeA, { key: completeKey }), 409, 'E2009', 'UNRESOLVED_EXCEPTIONS')
    const resolved = await resolveException(page, driverA, a, originalException, red)
    await policy(driverA, a, true, null)
    // Exception review version4 is not execution version3: error units stay execution-only.
    const wrongVersion = await api(driverA, completionPath(a, 'complete'), 'POST', { ...completeA, expectedVersion: 2 })
    rejected(wrongVersion, 409, 'E2001', 'VERSION_CONFLICT'); exact(wrongVersion.data, ['reason', 'actualVersion']); expect(wrongVersion.data.actualVersion).toBe(3)
    rejected(await api(driverA, completionPath(a, 'complete'), 'POST', { ...completeA, evidenceIds: [resolved.photo.id] }), 409, 'E2008', 'EVIDENCE_INVALID')
    const pending = ok(await api<PageData<{ taskId: string; prerequisiteReady: boolean }>>(page, `/tms/tasks?orderId=${a.order.id}&state=ALL`))
    expect(pending.content.find((task) => task.taskId === a.dependentId)!.prerequisiteReady).toBe(false)
    await available(page, a, true)
    const legacyBefore = ok(await api<Body>(page, `/tms/tasks/${a.taskId}/dispatch`))
    const loss = await loseCompletion(driverA, a, completeA, collector)
    let first: CompleteResult, maintained: Vehicle
    try {
      expect(await api<CompleteResult>(driverA, completionPath(a, 'complete'), 'POST', completeA, { key: completeKey }).then(() => false, () => true)).toBe(true)
      first = await loss.committed; witness(first.completion)
      const completedRead = await detail(driverA, a, 'COMPLETED', 4); expect(completedRead.completion).toEqual(first.completion)
      await policy(driverA, a, false, 'ALREADY_COMPLETED'); await available(page, a, false)
      // The effective release also updates FMS occupied-source guards. Current
      // availability must not become a new gate on the original completed receipt.
      maintained = ok(await api<Vehicle>(page, `/fms/vehicles/${a.main.id}`, 'PATCH', { version: a.main.version, administrativeStatus: 'MAINTENANCE' }))
      await detail(driverA, a, 'COMPLETED', 4); await policy(driverA, a, false, 'ALREADY_COMPLETED')
      rejected(await api(driverA, completionPath(a, 'complete'), 'POST', completeA, { key: completeKey }), 403, 'E0403', 'CSRF_INVALID')
      const replay = complete(await api<CompleteResult>(driverA, completionPath(a, 'complete'), 'POST', completeA, { key: completeKey }), a, true)
      expect(isDeepStrictEqual(replay, first)).toBe(true); expect(loss.attempts()).toBe(3); expect(loss.matching()).toBe(true)
    } finally { await loss.dispose() }
    const restored = ok(await api<Vehicle>(page, `/fms/vehicles/${a.main.id}`, 'PATCH', { version: maintained!.version, administrativeStatus: 'AVAILABLE' }))
    expect(restored.version).toBe(a.main.version + 2)
    const ready = ok(await api<PageData<{ taskId: string; prerequisiteReady: boolean }>>(page, `/tms/tasks?orderId=${a.order.id}&state=ALL`))
    expect(ready.content.find((task) => task.taskId === a.dependentId)!.prerequisiteReady).toBe(true)
    const reuseA = ok(await api<DispatchCommand>(dispatcher, `/tms/tasks/${a.dependentId}/dispatch`, 'POST', { expectedGeneration: 0, driverId: a.driver.id, mainVehicleId: a.main.id, trailerId: a.trailer!.id }))
    expect(reuseA.assignment.reservations).toHaveLength(3)
    await available(page, a, true, reuseA.assignment.id)
    const canonical = '{"expectedVersion":3.0,"evidenceIds":' + JSON.stringify([...a.readyIds].reverse()) + ',"confirmSafeRelease":true}'
    const afterReuse = complete(await api<CompleteResult>(driverA, `/tms/my-tasks/${a.assignment.id.toUpperCase()}/complete`, 'POST', undefined, { raw: canonical, key: completeKey }), a, true)
    expect(isDeepStrictEqual(afterReuse, first!)).toBe(true)
    await available(page, a, true, reuseA.assignment.id)
    const completedA = await detail(driverA, a, 'COMPLETED', 4); expect(completedA.completion).toEqual(first!.completion)
    expect(completedA.taskId).toBe(a.taskId); expect(completedA.taskId === reuseA.taskId).toBe(false)
    const oldDispatch = ok(await api<Body>(page, `/tms/tasks/${a.taskId}/dispatch`)); expect(isDeepStrictEqual(oldDispatch, legacyBefore)).toBe(true)
    const oldAssignReplay = await api<DispatchCommand>(page, `/tms/tasks/${a.taskId}/dispatch`, 'POST', a.dispatchBody, { key: a.dispatchKey })
    expect(oldAssignReplay.replayed).toBe('true'); expect(isDeepStrictEqual(ok(oldAssignReplay), a.dispatchResult)).toBe(true)
    const originalAssignment = (oldDispatch.current as Assignment)
    expect(originalAssignment.status).toBe('ASSIGNED'); expect(originalAssignment.version).toBe(0); expect(originalAssignment.endedAt).toBeNull(); expect(originalAssignment.reservations.every((row) => row.releasedAt === null && row.releaseReason === null)).toBe(true)
    const stillRecorded = ok(await api<PageData<Body>>(driverA, '/tms/my-tasks?size=100'))
    for (const row of stillRecorded.content) exact(row, safeFields)
    expect(stillRecorded.content.map((row) => row.assignmentId)).toContain(a.assignment.id); expect(stillRecorded.content.map((row) => row.assignmentId)).toContain(reuseA.assignment.id)
    expect(isDeepStrictEqual(copySafe(stillRecorded.content.find((row) => row.assignmentId === a.assignment.id)!), a.saved)).toBe(true)
    await legacyRecord(driverA, a, info, 'completion-original-assignment-record.png')
    rejected(await api(driverA, completionPath(a, 'complete'), 'POST', { ...completeA, evidenceIds: [] }, { key: completeKey }), 409, 'E2004', 'IDEMPOTENCY_KEY_REUSED')
    const stale = await api(driverA, completionPath(a, 'complete'), 'POST', completeA); rejected(stale, 409, 'E2001', 'VERSION_CONFLICT'); expect(stale.data.actualVersion).toBe(4)
    rejected(await api(driverA, completionPath(a, 'complete'), 'POST', { ...completeA, expectedVersion: 4 }), 409, 'E2002', 'INVALID_STATE')

    // The completed read is a separate capability: no older current-only
    // detail, progress, photo, exception or receipt helper is widened by release.
    const baseA = `/tms/my-tasks/${a.assignment.id}`
    for (const path of [baseA, `${baseA}/progress-policy`, `${baseA}/evidence`, `${baseA}/evidence/${a.photoId}/content`, `${baseA}/exceptions`, `${baseA}/exceptions/${originalException.id}`, `${baseA}/exceptions/${originalException.id}/history`]) rejected(await api(driverA, path), 404, 'E0404')
    rejected(await api(driverA, `${baseA}/depart`, 'POST', { expectedVersion: 0 }, { key: a.departKey }), 404, 'E0404')
    rejected(await api(driverA, `${baseA}/progress`, 'POST', a.readyBody, { key: a.readyKey }), 404, 'E0404')
    rejected(await api(driverA, `${baseA}/evidence`, 'POST', undefined, { raw: [...red], media: 'image/png', executionVersion: 2, key: a.photoKey }), 404, 'E0404')
    rejected(await api(driverA, `${baseA}/exceptions`, 'POST', reportBody, { key: reportKey }), 404, 'E0404')
    rejected(await api(dispatcher, `/tms/dispatches/${a.assignment.id}/revoke`, 'POST', { expectedGeneration: 1, reason: '合成完成不能偽造撤派' }), 409, 'E1603', 'INVALID_STATE')
    const historicalException = ok(await api<{ exception: ExceptionCase; execution: Execution }>(page, `/tms/exceptions/${originalException.id}`))
    expect(historicalException.exception).toEqual(resolved.record); expect(historicalException.execution.phase).toBe('COMPLETED')
    await privatePng(page, `/tms/exceptions/${originalException.id}/evidence/${resolved.photo.id}/content`, red)
    const sameOrder = await api<Order>(page, `/orders/${a.order.id}`); expect(sameOrder.code).toBe('OK'); expect(isDeepStrictEqual(sameOrder.data, a.order)).toBe(true)

    // Both commands are legal with no blockers at AWAITING. Shared root locking
    // permits either a report OR completion, never contradictory successful facts.
    const completeB: CompleteBody = { expectedVersion: 3, evidenceIds: b.readyIds, confirmSafeRelease: true }, keyB = randomUUID()
    const [reported, completed] = await Promise.all([
      api<ExceptionCase>(driverB, `/tms/my-tasks/${b.assignment.id}/exceptions`, 'POST', { expectedExecutionVersion: 3, description: ' 合成零筆阻擋競爭 e\u0301 \u0000 ' }),
      api<CompleteResult>(driverB, completionPath(b, 'complete'), 'POST', completeB, { key: keyB }),
    ])
    let resultB: CompleteResult
    if (completed.status === 200) { resultB = complete(completed, b); rejected(reported, 404, 'E0404') }
    else {
      const blocked = ok(reported); expect(blocked.status).toBe('OPEN'); rejected(completed, 409, 'E2009', 'UNRESOLVED_EXCEPTIONS')
      await resolveException(dispatcher, driverB, b, blocked, blue)
      resultB = complete(await api<CompleteResult>(driverB, completionPath(b, 'complete'), 'POST', completeB, { key: keyB }), b)
    }
    const detailB = await detail(driverB, b, 'COMPLETED', 4); expect(detailB.completion).toEqual(resultB.completion)
    await policy(driverB, b, false, 'ALREADY_COMPLETED'); await available(page, b, false)
    const reuseB = ok(await api<DispatchCommand>(page, `/tms/tasks/${b.dependentId}/dispatch`, 'POST', { expectedGeneration: 0, driverId: b.driver.id, mainVehicleId: b.main.id, trailerId: null }))
    expect(reuseB.assignment.reservations).toHaveLength(2); await available(page, b, true, reuseB.assignment.id)
    expect(isDeepStrictEqual(complete(await api<CompleteResult>(driverB, completionPath(b, 'complete'), 'POST', completeB, { key: keyB }), b, true), resultB)).toBe(true)
    await legacyRecord(driverB, b, info, 'completion-two-resource-record.png')
    const listB = ok(await api<PageData<Body>>(driverB, '/tms/my-tasks?size=100')); expect(listB.content.map((row) => row.assignmentId)).toContain(b.assignment.id)

    // Same physical driver rebound to another normal account may read the
    // narrow saved fact, but that account never acquires someone else's receipt.
    const unboundA = ok(await api<Driver>(page, `/fms/drivers/${a.driver.id}`, 'PATCH', { version: a.driver.version, accountId: null }))
    rejected(await api(driverA, completionPath(a, 'completion')), 404, 'E0404')
    rejected(await api(driverA, completionPath(a, 'complete'), 'POST', completeA, { key: completeKey }), 404, 'E0404')
    ok(await api(page, `/fms/drivers/${b.driver.id}`, 'PATCH', { version: b.driver.version, accountId: null }))
    ok(await api(page, `/fms/drivers/${a.driver.id}`, 'PATCH', { version: unboundA.version, accountId: fixture.driverB.id }))
    const rebound = await detail(driverB, a, 'COMPLETED', 4); expect(rebound.completion).toEqual(first!.completion)
    const actorScoped = await api(driverB, completionPath(a, 'complete'), 'POST', completeA, { key: completeKey }); rejected(actorScoped, 409, 'E2001', 'VERSION_CONFLICT'); expect(actorScoped.data.actualVersion).toBe(4)
    rejected(await api(driverB, completionPath(b, 'completion')), 404, 'E0404')
    rejected(await api(driverB, completionPath(b, 'complete'), 'POST', completeB, { key: keyB }), 404, 'E0404')
    const account = ok(await api<Account[]>(page, '/upms/accounts')).find((actor) => actor.id === fixture.driverB.id)!
    ok(await api(page, `/upms/accounts/${account.id}`, 'PATCH', { version: account.version, displayName: account.displayName, role: account.role, enabled: false }))
    for (const suffix of ['completion', 'completion-policy']) rejected(await api(driverB, completionPath(a, suffix as 'completion' | 'completion-policy')), 401, 'E0401')
    rejected(await api(driverB, completionPath(a, 'complete'), 'POST', completeA, { key: completeKey }), 401, 'E0401')
    await driverB.getByRole('button', { name: '重新讀取本人任務', exact: true }).click()
    await expect(driverB.getByRole('heading', { name: '登入工作空間', exact: true })).toBeVisible()
    await expect(driverB.getByRole('article', { name: `本人任務 ${b.saved.taskNo}`, exact: true })).toHaveCount(0)
  } finally { await Promise.all(contexts.map((context) => context.close())) }
})
