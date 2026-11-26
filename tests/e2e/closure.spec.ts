import { createHash, randomUUID } from 'node:crypto'
import { readFileSync, writeFileSync } from 'node:fs'
import { request as httpsRequest } from 'node:https'
import { crc32, deflateSync } from 'node:zlib'
import { isDeepStrictEqual } from 'node:util'
import { expect, test, type Browser, type BrowserContext, type Locator, type Page, type Request, type Response, type Route, type TestInfo } from '@playwright/test'
import { closureFixtureFor, signIn, type Credential } from './fixtures'

// M405 management UI over real backend state. Private PNGs exist only in memory.
test.use({ screenshot: 'off' })
test.describe.configure({ retries: 0 })
type Body = Record<string, unknown>
interface Reply<T> { status: number; code: string; data: T; payload: Body; replayed: string | null; cache: string | null; completionRoute: boolean; noLocation: boolean }
interface PageData<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }
interface Execution { phase: string; version: number; departedAt: string | null }
interface Witness { id: string; completedAt: string; releasedAt: string; policyMode: string; safeReleaseConfirmed: boolean }
interface CompleteResult { assignmentId: string; taskId: string; execution: Execution; completion: Witness }
interface CompleteDetail extends Body { assignmentId: string; taskId: string; taskNo: string; execution: Execution; completion: Witness | null }
interface Driver { id: string; driverCode: string; version: number }
interface Vehicle { id: string; plateNo: string; version: number }
interface Account { id: string; role: string; displayName: string; enabled: boolean; version: number }
interface Order extends Body { id: string; version: number; tasks: { id: string; clientRef: string; taskNo: string; requiredTask: true | null }[] }
interface Assignment extends Body { id: string; taskId: string; status: string; version: number; endedAt: string | null; reservations: { resourceId: string; releasedAt: string | null; releaseReason: string | null }[] }
interface DispatchCommand { assignment: Assignment; generation: number; taskId: string }
interface Evidence { id: string; assignmentId: string; taskId: string; phase: string; executionVersion: number; mediaType: string; byteSize: number; sha256: string; uploadedAt: string; status: string }
interface Captured { schema: string; value: unknown }
type ClosureSchema = 'ConfirmCompletionEnvelope' | 'CloseOrderEnvelope' | 'ErrorEnvelope'
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
  readonly closure: Captured[] = []; readonly completion: Captured[] = []; readonly exceptions: Captured[] = []; readonly progress: Captured[] = []; readonly execution: Captured[] = []; readonly tms: Captured[] = []; readonly orders: Captured[] = []; readonly acceptances: Captured[] = []; readonly vehicles: Captured[] = []; readonly drivers: Captured[] = []
  private readonly pages = new Set<Page>(); private readonly pending = new Set<Promise<void>>()
  private readonly capture = (response: Response) => {
    const path = new URL(response.url()).pathname, method = response.request().method()
    let schema: string | undefined, target: Captured[] | undefined
    const manager = /^\/api\/v1\/tms\/exceptions(?:\/|$)/.test(path)
    const self = /^\/api\/v1\/tms\/my-tasks\/[0-9a-f-]{36}\/exceptions(?:\/|$)/i.test(path)
    if (/^\/api\/v1\/tms\/completions(?:\/|$)/i.test(path)) {
      target = this.closure; schema = path.endsWith('/history') ? 'ManagementConfirmationHistoryPageEnvelope' : path.endsWith('/confirm') ? 'ConfirmCompletionEnvelope' : path.endsWith('/confirmation') ? 'CompletionConfirmationDetailEnvelope' : 'CompletionConfirmationSummaryPageEnvelope'
    } else if (path === '/api/v1/orders/closure-worklist' || /^\/api\/v1\/orders\/[0-9a-f-]{36}\/(close|closure(?:\/(tasks|history))?)$/i.test(path)) {
      target = this.closure; schema = path.endsWith('/closure-worklist') ? 'ClosureSummaryPageEnvelope' : path.endsWith('/tasks') ? 'ClosureTaskReviewPageEnvelope' : path.endsWith('/history') ? 'OrderClosureHistoryPageEnvelope' : path.endsWith('/close') ? 'CloseOrderEnvelope' : 'OrderClosureEnvelope'
    } else if (/^\/api\/v1\/tms\/my-tasks\/[0-9a-f-]{36}\/(completion|completion-policy|complete)$/i.test(path)) { target = this.completion; schema = path.endsWith('/completion-policy') ? 'CompletionPolicyEnvelope' : path.endsWith('/complete') ? 'CompleteMyTaskEnvelope' : 'CompletionDetailEnvelope' }
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
    else if (path === '/api/v1/orders' || /^\/api\/v1\/orders\/[0-9a-f-]{36}$/i.test(path)) { target = this.orders; schema = path === '/api/v1/orders' && method === 'GET' ? 'OrderPageEnvelope' : 'OrderEnvelope' }
    else if (path === '/api/v1/fms/vehicles' || /^\/api\/v1\/fms\/vehicles\/[0-9a-f-]{36}$/i.test(path)) { target = this.vehicles; schema = 'VehicleEnvelope' }
    else if (path === '/api/v1/fms/drivers' || /^\/api\/v1\/fms\/drivers\/[0-9a-f-]{36}$/i.test(path)) { target = this.drivers; schema = 'DriverEnvelope' }
    if (!schema || !target) return
    const name = response.status() >= 400 ? 'ErrorEnvelope' : schema, destination = target
    const pending = (async () => { try { destination.push({ schema: name, value: await response.json() }) } catch { /* Bodyless/unreadable responses are not claimed JSON evidence. */ } })()
    this.pending.add(pending); void pending.then(() => this.pending.delete(pending), () => this.pending.delete(pending))
  }
  watch(page: Page) { if (!this.pages.has(page)) { this.pages.add(page); page.on('response', this.capture) } }
  record(schema: ClosureSchema, value: Body) { this.closure.push({ schema, value }) }
  async finish() { for (const page of this.pages) page.off('response', this.capture); await Promise.allSettled([...this.pending]) }
}
const collectors = new Map<string, ResponseCollector>()
test.beforeEach(async ({ page }, info) => { const collector = new ResponseCollector(); collector.watch(page); collectors.set(info.testId, collector) })
test.afterEach(async ({}, info) => {
  const collector = collectors.get(info.testId); if (!collector) return; await collector.finish()
  // Original public synthetic JSON only. No private PNG, request body, raw
  // headers, keys, cookies, credentials or trace archives enter saved evidence.
  for (const [name, values] of [['closure', collector.closure], ['completion', collector.completion], ['exception', collector.exceptions], ['progress-evidence', collector.progress], ['execution', collector.execution], ['tms', collector.tms], ['order', collector.orders], ['acceptance', collector.acceptances], ['vehicle', collector.vehicles], ['driver', collector.drivers]] as const) {
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
    return { status: response.status, code: payload.code, data: payload.data, payload, replayed: response.headers.get('Idempotency-Replayed'), cache: response.headers.get('Cache-Control'), completionRoute: /^\/(tms\/completions(?:\/|$)|orders\/(?:closure-worklist|[0-9a-f-]{36}\/(close|closure)(?:\/|$)))/i.test(path.split('?')[0]), noLocation: !response.headers.has('Location') }
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
const safeForwardFailure = 'The controlled synthetic management request could not be completed safely.'
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

interface Actor { role: 'ADMIN' | 'DISPATCHER'; displayName: string }
interface Confirmation { id: string; version: number; confirmedAt: string; policyMode: string; reason: string; actor: Actor }
interface ConfirmationSummary { completionId: string; assignmentId: string; taskId: string; taskNo: string; orderId: string; orderNo: string; sourceOrderVersion: number; completedAt: string; version: number; state: string; confirmedAt: string | null }
interface ConfirmationDetail { record: ConfirmationSummary; execution: Execution; completion: Witness; confirmation: Confirmation | null; policy: { mode: string; canConfirm: boolean; blocker: string | null } }
interface ConfirmResult { completionId: string; assignmentId: string; taskId: string; sourceOrderVersion: number; version: number; confirmation: Confirmation }
interface ClosureWitness { id: string; sourceVersion: number; version: number; closedAt: string; policyMode: string; managerConfirmed: boolean; reason: string; taskCount: number; actor: Actor }
interface Counts { taskCount: number; completedTaskCount: number; confirmedTaskCount: number; blockingExceptionCount: number; effectiveOccupationCount: number; sourceOrderVersion: number }
interface ClosureView { orderId: string; orderNo: string; lifecycleStatus: string; acceptanceStatus: string; version: number; auditState: string; closure: ClosureWitness | null; policy: { mode: string; canClose: boolean; blocker: string | null; requirements: Body | null }; counts: Counts | null }
interface CloseResult { orderId: string; orderNo: string; lifecycleStatus: string; acceptanceStatus: string; version: number; closure: ClosureWitness }
interface TaskReview { orderId: string; sourceOrderVersion: number; taskId: string; taskNo: string; requiredTask: true | null; assignmentId: string | null; completionId: string | null; executionPhase: string; executionVersion: number; completionVerified: boolean; confirmationVersion: number; confirmedAt: string | null; blockingExceptionCount: number; effectiveOccupationCount: number }
interface Cohort { order: Order; created: Order; edited: Order; draft: Body; putBody: Body; createKey: string; putKey: string; acceptKey: string; driver: Driver; main: Vehicle; trailer: Vehicle; customer: { id: string; version: number; name: string } }
interface Completed { assignment: Assignment; taskId: string; taskNo: string; source: Body; result: CompleteResult; body: Body; key: string; legacy: Body }
const confirmationSummaryKeys = ['completionId', 'assignmentId', 'taskId', 'taskNo', 'orderId', 'orderNo', 'sourceOrderVersion', 'completedAt', 'version', 'state', 'confirmedAt']
const taskReviewKeys = ['orderId', 'sourceOrderVersion', 'taskId', 'taskNo', 'requiredTask', 'assignmentId', 'completionId', 'executionPhase', 'executionVersion', 'completionVerified', 'confirmationVersion', 'confirmedAt', 'blockingExceptionCount', 'effectiveOccupationCount']
const confirmPath = (task: Completed) => `/tms/completions/${task.result.completion.id}`
const closePath = (cohort: Cohort) => `/orders/${cohort.order.id}`
const copySafe = (value: Body) => Object.fromEntries(safeFields.map((field) => [field, value[field]]))
function original<T>(reply: Reply<T>, status = 200): T { expect(reply.status).toBe(status); expect(reply.code).toBe('OK'); exact(reply.payload, ['code', 'message', 'data', 'traceId']); return reply.data }
function actor(value: Actor) { exact(value, ['role', 'displayName']); expect(['ADMIN', 'DISPATCHER']).toContain(value.role); expect([...value.displayName].length).toBeGreaterThan(0); expect([...value.displayName].length).toBeLessThanOrEqual(80) }
function confirmation(value: Confirmation, reason: string) { exact(value, ['id', 'version', 'confirmedAt', 'policyMode', 'reason', 'actor']); expect(value.version).toBe(1); expect(value.policyMode).toBe('SYNTHETIC_TEST_ONLY'); expect(value.reason).toBe(reason); actor(value.actor); expect(Number.isFinite(Date.parse(value.confirmedAt))).toBe(true) }
function confirmed(value: ConfirmResult, cohort: Cohort, task: Completed, reason: string) { exact(value, ['completionId', 'assignmentId', 'taskId', 'sourceOrderVersion', 'version', 'confirmation']); expect(value.completionId).toBe(task.result.completion.id); expect(value.assignmentId).toBe(task.assignment.id); expect(value.taskId).toBe(task.taskId); expect(value.sourceOrderVersion).toBe(cohort.order.version); expect(value.version).toBe(1); confirmation(value.confirmation, reason) }
function closed(value: CloseResult, cohort: Cohort, reason: string) { exact(value, ['orderId', 'orderNo', 'lifecycleStatus', 'acceptanceStatus', 'version', 'closure']); expect(value.orderId).toBe(cohort.order.id); expect(value.orderNo).toBe(cohort.order.orderNo); expect(value.lifecycleStatus).toBe('CLOSED'); expect(value.acceptanceStatus).toBe('ACCEPTED'); expect(value.version).toBe(cohort.order.version + 1); exact(value.closure, ['id', 'sourceVersion', 'version', 'closedAt', 'policyMode', 'managerConfirmed', 'reason', 'taskCount', 'actor']); expect(value.closure.sourceVersion).toBe(cohort.order.version); expect(value.closure.version).toBe(value.version); expect(value.closure.policyMode).toBe('SYNTHETIC_TEST_ONLY'); expect(value.closure.managerConfirmed).toBe(true); expect(value.closure.reason).toBe(reason); expect(value.closure.taskCount).toBe(cohort.order.tasks.length); actor(value.closure.actor); expect(Number.isFinite(Date.parse(value.closure.closedAt))).toBe(true) }
async function confirmationDetail(page: Page, cohort: Cohort, task: Completed, version: 0 | 1): Promise<ConfirmationDetail> {
  const value = ok(await api<ConfirmationDetail>(page, `${confirmPath(task)}/confirmation`)); exact(value, ['record', 'execution', 'completion', 'confirmation', 'policy']); exact(value.record, confirmationSummaryKeys); exact(value.policy, ['mode', 'canConfirm', 'blocker'])
  expect(value.record).toMatchObject({ completionId: task.result.completion.id, assignmentId: task.assignment.id, taskId: task.taskId, orderId: cohort.order.id, sourceOrderVersion: cohort.order.version, version, state: version ? 'CONFIRMED' : 'PENDING' }); expect(value.execution).toEqual(task.result.execution); expect(value.completion).toEqual(task.result.completion)
  expect(value.policy.mode).toBe('SYNTHETIC_TEST_ONLY'); expect(value.policy.canConfirm).toBe(version === 0); expect(value.policy.blocker).toBe(version ? 'ALREADY_CONFIRMED' : null)
  if (version) { expect(value.confirmation).not.toBeNull(); expect(value.record.confirmedAt).toBe(value.confirmation!.confirmedAt) } else { expect(value.confirmation).toBeNull(); expect(value.record.confirmedAt).toBeNull() }
  return value
}
async function closureView(page: Page, cohort: Cohort, blocker: string | null, expectedCounts?: Partial<Counts>): Promise<ClosureView> {
  const value = ok(await api<ClosureView>(page, `${closePath(cohort)}/closure`)); exact(value, ['orderId', 'orderNo', 'lifecycleStatus', 'acceptanceStatus', 'version', 'auditState', 'closure', 'policy', 'counts']); exact(value.policy, ['mode', 'canClose', 'blocker', 'requirements']); expect(value.orderId).toBe(cohort.order.id); expect(value.policy.mode).toBe('SYNTHETIC_TEST_ONLY'); expect(value.policy.blocker).toBe(blocker); expect(value.policy.canClose).toBe(blocker === null)
  if (expectedCounts) { expect(value.lifecycleStatus).toBe('OPEN'); expect(value.acceptanceStatus).toBe('ACCEPTED'); expect(value.version).toBe(cohort.order.version); expect(value.auditState).toBe('NOT_CLOSED'); expect(value.closure).toBeNull(); expect(value.counts).toMatchObject({ taskCount: 2, sourceOrderVersion: cohort.order.version, ...expectedCounts }); exact(value.counts!, ['taskCount', 'completedTaskCount', 'confirmedTaskCount', 'blockingExceptionCount', 'effectiveOccupationCount', 'sourceOrderVersion']) }
  if (blocker) expect(value.policy.requirements).toBeNull(); else expect(value.policy.requirements).toEqual({ taskBasis: 'ALL_PERSISTED_TASKS', requiresManagerConfirmation: true, requiresNoBlockingExceptions: true, requiresNoEffectiveOccupations: true })
  return value
}
async function reviewTasks(page: Page, cohort: Cohort): Promise<TaskReview[]> {
  const first = ok(await api<PageData<TaskReview>>(page, `${closePath(cohort)}/closure/tasks?page=0&size=1`)), second = ok(await api<PageData<TaskReview>>(page, `${closePath(cohort)}/closure/tasks?page=1&size=1`))
  for (const value of [first, second]) { exact(value, ['content', 'page', 'size', 'totalElements', 'totalPages']); expect(value.size).toBe(1); expect(value.totalElements).toBe(2); expect(value.totalPages).toBe(2); expect(value.content).toHaveLength(1) }
  const all = [...first.content, ...second.content]; expect(new Set(all.map((task) => task.taskId)).size).toBe(2); expect(all.map((task) => task.taskId).sort()).toEqual(cohort.order.tasks.map((task) => task.id).sort())
  for (const task of all) { const original = cohort.order.tasks.find((source) => source.id === task.taskId)!; expect(task.requiredTask).toBe(original.requiredTask); expect(task.taskNo).toBe(original.taskNo); exact(task, taskReviewKeys); expect(task.orderId).toBe(cohort.order.id); expect(task.sourceOrderVersion).toBe(cohort.order.version); expect([true, null]).toContain(task.requiredTask) }
  expect(all.map((task) => task.requiredTask)).toEqual(expect.arrayContaining([true, null])); return all
}
async function createCohort(manager: Page, fixture: ReturnType<typeof closureFixtureFor>): Promise<Cohort> {
  const scope = fixture.scope, driver = ok(await api<Driver>(manager, '/fms/drivers', 'POST', { driverCode: `SYNTHETIC_TMS_${scope}_D6001`, name: `合成結案司機 ${scope}`, accountId: fixture.driverA.id, employmentStatus: 'ACTIVE', dutyStatus: 'ON_DUTY' }), 201)
  const main = ok(await api<Vehicle>(manager, '/fms/vehicles', 'POST', { plateNo: `CLS-${scope[0]}-${randomUUID().slice(0, 10)}`, vehicleType: fixture.vehicleTypes.main.id, capacityTon: '1.00', administrativeStatus: 'AVAILABLE' }), 201), trailer = ok(await api<Vehicle>(manager, '/fms/vehicles', 'POST', { plateNo: `CLS-${scope[0]}-T-${randomUUID().slice(0, 10)}`, vehicleType: fixture.vehicleTypes.trailer.id, administrativeStatus: 'AVAILABLE' }), 201)
  const customer = ok(await api<Cohort['customer']>(manager, '/crm/customers', 'POST', { customerCode: `CLOSURE_${scope}_${randomUUID().slice(0, 8).toUpperCase()}`, name: ' 合成歷史客戶 e\u0301 ' }), 201)
  const draft = { type: 'LOCAL', customerId: customer.id, customerReference: ' 合成\u0000外部參照 e\u0301 ', remark: ' 合成結案原文\r\n e\u0301 \u0000 ', items: [{ clientRef: 'closure-item', sequence: 1, itemKind: 'CARGO', packageQty: 7, grossWeight: '0.0000000000000000001', cbm: '0' }], tasks: Array.from({ length: 2 }, (_, index) => ({ clientRef: `closure-task-${index}`, itemRef: 'closure-item', taskKind: `SYNTHETIC_TMS_${scope}_CLOSURE_6001_${index}`, pickupName: ` 合成 e\u0301 起點 ${index} `, pickupAddress: ` 合成\u0000地址\r\n${index}樓 `, deliveryName: `合成終點 ${index}`, deliveryAddress: `合成終點地址 ${index}`, siteContact: { name: ' 合成 e\u0301 聯絡 ', phone: null }, plannedStartAt: '1500-01-02T08:00:00.123456+23:59', plannedEndAt: '1500-01-02T10:00:00.123456+23:59', requiredTask: index ? null : true, predecessorRefs: index ? ['closure-task-0'] : [] })) }
  const createKey = randomUUID(), created = original(await api<Order>(manager, '/orders', 'POST', draft, { key: createKey }), 201)
  const draftView = ok(await api<ClosureView>(manager, `/orders/${created.id}/closure`)); expect(draftView.counts).toBeNull(); expect(draftView.auditState).toBe('NOT_CLOSED'); expect(draftView.policy.blocker).toBe('ORDER_NOT_ACCEPTED')
  rejected(await api(manager, `/orders/${created.id}/closure/tasks`), 409, 'E2102', 'INVALID_STATE')
  const putBody = { expectedVersion: created.version, draft: { ...draft, remark: `${draft.remark} 修訂 ` } }, putKey = randomUUID(), edited = original(await api<Order>(manager, `/orders/${created.id}`, 'PUT', putBody, { key: putKey }))
  const acceptKey = randomUUID(), order = original(await api<Order>(manager, `/orders/${created.id}/accept`, 'POST', { expectedVersion: edited.version }, { key: acceptKey }))
  expect([created.version, edited.version, order.version]).toEqual([0, 1, 2]); expect(order.tasks.map((task) => task.id)).toEqual(edited.tasks.map((task) => task.id))
  return { order, created, edited, draft, putBody, createKey, putKey, acceptKey, driver, main, trailer, customer }
}
async function completeTask(manager: Page, driver: Page, cohort: Cohort, index: number, bytes: Buffer, beforeComplete?: (value: { assignment: Assignment; taskId: string; execution: Execution }) => Promise<void>): Promise<Completed> {
  const taskId = cohort.order.tasks.find((task) => task.clientRef === `closure-task-${index}`)!.id
  const assigned = ok(await api<DispatchCommand>(manager, `/tms/tasks/${taskId}/dispatch`, 'POST', { expectedGeneration: 0, driverId: cohort.driver.id, mainVehicleId: cohort.main.id, trailerId: index ? null : cohort.trailer.id })), assignment = assigned.assignment
  expect(assignment.reservations).toHaveLength(index ? 2 : 3)
  const base = `/tms/my-tasks/${assignment.id}`, waiting = ok(await api<CompleteDetail>(driver, `${base}/completion`)); exact(waiting, [...safeFields, 'execution', 'completion']); expect(waiting.execution.phase).toBe('WAITING'); expect(waiting.completion).toBeNull()
  const departed = ok(await api<{ execution: Execution }>(driver, `${base}/depart`, 'POST', { expectedVersion: 0 })).execution
  const first = ok(await api<Evidence>(driver, `${base}/evidence`, 'POST', undefined, { raw: [...bytes], media: 'image/png', executionVersion: 1 })); expect(first.sha256).toBe(hash(bytes)); await privatePng(driver, `${base}/evidence/${first.id}/content`, bytes)
  const progress = ok(await api<{ execution: Execution }>(driver, `${base}/progress`, 'POST', { expectedVersion: 1, targetPhase: 'IN_PROGRESS', evidenceIds: [first.id] })).execution; expect(progress.version).toBe(2)
  const final = ok(await api<Evidence>(driver, `${base}/evidence`, 'POST', undefined, { raw: [...bytes], media: 'image/png', executionVersion: 2 })); expect(final.sha256).toBe(hash(bytes))
  const ready = ok(await api<{ execution: Execution }>(driver, `${base}/progress`, 'POST', { expectedVersion: 2, targetPhase: 'AWAITING_COMPLETION', evidenceIds: [final.id] })).execution; expect(ready).toEqual({ phase: 'AWAITING_COMPLETION', version: 3, departedAt: departed.departedAt })
  if (beforeComplete) await beforeComplete({ assignment, taskId, execution: ready })
  const policy = ok(await api<Body>(driver, `${base}/completion-policy`)); expect(policy.canComplete).toBe(true); expect(policy.requiredEvidenceIds).toEqual([final.id])
  const body = { expectedVersion: 3, evidenceIds: [final.id], confirmSafeRelease: true }, key = randomUUID(), reply = await api<CompleteResult>(driver, `${base}/complete`, 'POST', body, { key }), result = ok(reply); expect(reply.replayed).toBe('false'); expect(result.execution).toEqual({ phase: 'COMPLETED', version: 4, departedAt: departed.departedAt }); expect(result.completion.safeReleaseConfirmed).toBe(true); expect(result.completion.policyMode).toBe('SYNTHETIC_TEST_ONLY')
  const legacy = ok(await api<Body>(manager, `/tms/tasks/${taskId}/dispatch`)); const historical = legacy.current as Assignment
  expect(historical.id).toBe(assignment.id); expect(historical.status).toBe('ASSIGNED'); expect(historical.version).toBe(0); expect(historical.reservations.every((row) => row.releasedAt === null && row.releaseReason === null)).toBe(true)
  return { assignment, taskId, taskNo: waiting.taskNo, source: copySafe(waiting), result, body, key, legacy }
}

type ManagementKind = 'confirm' | 'close'
const ui = {
  confirm: { current: '目前行政確認資料', back: '返回確認清單', fresh: '重新讀取行政確認', start: '記錄管理確認', label: '管理確認原因', review: '檢視管理確認', group: '管理確認二次確認', final: '確認記錄', raw: '管理確認原因原文' },
  close: { current: '目前訂單結案資料', back: '返回結案清單', fresh: '重新讀取訂單結案', start: '結案訂單', label: '訂單結案原因', review: '檢視結案確認', group: '訂單結案二次確認', final: '確認結案', raw: '訂單結案原因原文' },
} as const
async function raw(locator: Locator, expected: string) { await expect(locator).toBeVisible(); expect(await locator.textContent()).toBe(expected) }
async function workspace(page: Page) { await page.bringToFront(); await page.getByRole('navigation', { name: '主要導覽', exact: true }).getByRole('button', { name: '確認與結案', exact: true }).click(); await expect(page.getByRole('heading', { name: '確認與結案', exact: true })).toBeVisible() }
async function openConfirmation(page: Page, cohort: Cohort, task: Completed) {
  await page.bringToFront(); const back = page.getByRole('button', { name: ui.confirm.back, exact: true }); if (await back.count()) await back.click()
  await workspace(page); await page.getByRole('tab', { name: '完成管理確認', exact: true }).click()
  await page.getByLabel('行政確認狀態', { exact: true }).selectOption('ALL'); await page.getByLabel('確認訂單識別', { exact: true }).fill(cohort.order.id); await page.getByLabel('每頁確認筆數', { exact: true }).selectOption('5')
  const waiting = page.waitForResponse((response) => { const url = new URL(response.url()); return response.request().method() === 'GET' && url.pathname === '/api/v1/tms/completions' && url.searchParams.get('orderId') === cohort.order.id && url.searchParams.get('size') === '5' })
  await page.getByRole('button', { name: '查詢完成確認', exact: true }).click(); expect((await waiting).status()).toBe(200)
  await page.getByRole('button', { name: `開啟完成確認 ${task.taskNo}`, exact: true }).click(); await expect(page.getByRole('region', { name: ui.confirm.current, exact: true })).toBeVisible()
}
async function openClosure(page: Page, cohort: Cohort, state = 'OPEN') {
  await page.bringToFront(); const back = page.getByRole('button', { name: ui.close.back, exact: true }); if (await back.count()) await back.click()
  await workspace(page); await page.getByRole('tab', { name: '訂單結案', exact: true }).click(); await page.getByLabel('結案清單範圍', { exact: true }).selectOption(state); await page.getByLabel('每頁結案筆數', { exact: true }).selectOption('5')
  const waiting = page.waitForResponse((response) => { const url = new URL(response.url()); return response.request().method() === 'GET' && url.pathname === '/api/v1/orders/closure-worklist' && url.searchParams.get('state') === state && url.searchParams.get('size') === '5' })
  await page.getByRole('button', { name: '查詢結案訂單', exact: true }).click(); expect((await waiting).status()).toBe(200)
  // Each browser case creates its own newest order. No unapproved free-text
  // or order-id filter is added to the fixed closure-worklist contract.
  const button = page.getByRole('button', { name: `開啟訂單結案 ${cohort.order.orderNo}`, exact: true })
  const list = page.getByRole('region', { name: '結案清單', exact: true })
  await expect(list.getByRole('status')).toHaveCount(0)
  let pageNumber = 0
  while (!(await button.count())) {
    const next = page.getByRole('button', { name: '下一頁結案清單', exact: true }); await expect(next).toBeEnabled(); pageNumber++
    const paged = page.waitForResponse((response) => { const url = new URL(response.url()); return response.request().method() === 'GET' && url.pathname === '/api/v1/orders/closure-worklist' && url.searchParams.get('page') === String(pageNumber) })
    await next.click(); expect((await paged).status()).toBe(200); await expect(list.getByRole('status')).toHaveCount(0)
  }
  await button.click(); await expect(page.getByRole('region', { name: ui.close.current, exact: true })).toBeVisible()
}
async function review(page: Page, kind: ManagementKind, reason: string) {
  await page.bringToFront(); await page.getByRole('button', { name: ui[kind].start, exact: true }).click(); const field = page.getByLabel(ui[kind].label, { exact: true }); await field.fill(reason); await expect(field).toHaveValue(reason)
  await page.getByRole('button', { name: ui[kind].review, exact: true }).click(); const group = page.getByRole('group', { name: ui[kind].group, exact: true }); await raw(group.getByLabel('本次管理原因原文', { exact: true }), reason); await expect(group.getByRole('button', { name: ui[kind].final, exact: true })).toBeFocused()
}
async function submit(page: Page, kind: ManagementKind, path: string, status = 200): Promise<Reply<Body>> {
  await page.bringToFront(); const waiting = page.waitForResponse((response) => response.request().method() === 'POST' && new URL(response.url()).pathname === `/api/v1${path}`)
  await page.getByRole('button', { name: ui[kind].final, exact: true }).click(); const response = await waiting, payload = await response.json(); expect(response.status()).toBe(status)
  return { status: response.status(), code: payload.code, data: payload.data, payload, replayed: await response.headerValue('Idempotency-Replayed'), cache: await response.headerValue('Cache-Control'), completionRoute: true, noLocation: (await response.headerValue('Location')) === null }
}
async function fresh(page: Page, kind: ManagementKind, path: string, adopting = false) {
  await page.bringToFront(); const waiting = page.waitForResponse((response) => response.request().method() === 'GET' && new URL(response.url()).pathname === `/api/v1${path}`)
  await page.getByRole('button', { name: ui[kind].fresh, exact: true }).click(); expect((await waiting).status()).toBe(200)
  const adopt = page.getByRole('button', { name: '採用最新管理資料', exact: true }); if (adopting) { await expect(adopt).toBeVisible(); await adopt.click() }
}
async function loseManagement(page: Page, path: string, body: Body, schema: ClosureSchema, collector: ResponseCollector, validate: (value: Body) => boolean) {
  const origin = new URL(page.url()).origin, fullPath = `/api/v1${path}`, cancellation = new AbortController(), active = new Set<Promise<void>>()
  let attempts = 0, matching = false, key: string | undefined, original: Buffer | null = null
  let resolve!: (value: Body) => void, reject!: (error: Error) => void
  const committed = new Promise<Body>((yes, no) => { resolve = yes; reject = no }); void committed.catch(() => undefined)
  const deadline = setTimeout(() => { cancellation.abort(); reject(new Error(safeForwardFailure)) }, 20_000)
  const matches = (url: URL) => url.origin === origin && url.pathname === fullPath
  const handle = async (route: Route) => {
    try {
      const request = route.request(); if (request.method() !== 'POST') { await route.continue(); return }
      attempts++; const incomingKey = (await request.allHeaders())['idempotency-key'], incoming = request.postDataBuffer()
      if (attempts === 1) {
        key = incomingKey; original = incoming
        if (!incoming || !key || !isDeepStrictEqual(JSON.parse(incoming.toString('utf8')), body)) throw new Error(safeForwardFailure)
        const response = await forward(request, origin, fullPath, cancellation.signal)
        if (response.status !== 200 || response.payload.code !== '0000' || response.cache !== 'no-store, private' || response.replayed !== 'false' || !validate(response.payload.data as Body)) throw new Error(safeForwardFailure)
        collector.record(schema, response.payload); await route.abort('failed'); clearTimeout(deadline); resolve(response.payload.data as Body)
      } else {
        const same = Boolean(key && key === incomingKey && original && incoming && original.equals(incoming)); matching = attempts === 2 ? same : matching && same
        if (!same) throw new Error(safeForwardFailure)
        if (attempts === 2) {
          const response = await forward(request, origin, fullPath, cancellation.signal, true)
          if (response.status !== 403 || response.payload.code !== 'E0403' || response.cache !== 'no-store, private' || (response.payload.data as Body)?.reason !== 'CSRF_INVALID') throw new Error(safeForwardFailure)
          await route.fulfill({ status: response.status, headers: { 'Content-Type': 'application/json', 'Cache-Control': response.cache }, body: JSON.stringify(response.payload) })
        } else await route.continue()
      }
    } catch { clearTimeout(deadline); reject(new Error(safeForwardFailure)); try { await route.abort('failed') } catch { /* Context may be closing. */ } }
  }
  const handler = (route: Route) => { const work = handle(route); active.add(work); void work.then(() => active.delete(work), () => active.delete(work)); return work }
  await page.route(matches, handler)
  return { committed, path: fullPath, attempts: () => attempts, matching: () => matching,
    replay: async (actor: Page, raw?: string, changed?: Body) => { if (!key || !original) throw new Error(safeForwardFailure); return api<Body>(actor, path.replace(/[0-9a-f]{8}-[0-9a-f-]{27}/gi, (id) => id.toUpperCase()), 'POST', changed, { key, raw: raw ?? (changed ? undefined : original.toString('utf8')) }) },
    dispose: async () => { clearTimeout(deadline); cancellation.abort(); reject(new Error(safeForwardFailure)); await Promise.allSettled([...active]); await page.unroute(matches, handler) } }
}
async function retryUnknown(page: Page, loss: Awaited<ReturnType<typeof loseManagement>>, kind: ManagementKind, reason: string): Promise<Body> {
  await page.bringToFront(); await expect(page.getByRole('heading', { name: '操作結果尚未確認', exact: true })).toBeVisible(); const field = page.getByLabel(ui[kind].label, { exact: true }); await expect(field).toHaveValue(reason); await expect(field).toBeDisabled()
  const forbidden = page.waitForResponse((response) => new URL(response.url()).pathname === loss.path && response.status() === 403)
  await page.getByRole('button', { name: '以原請求重試確認或結案', exact: true }).click(); await forbidden; await expect(page.getByRole('heading', { name: '操作結果尚未確認', exact: true })).toBeVisible(); await expect(field).toHaveValue(reason)
  const replayed = page.waitForResponse((response) => new URL(response.url()).pathname === loss.path && response.status() === 200)
  await page.getByRole('button', { name: '以原請求重試確認或結案', exact: true }).click(); const response = await replayed; expect(await response.headerValue('Idempotency-Replayed')).toBe('true'); expect(loss.attempts()).toBe(3); expect(loss.matching()).toBe(true)
  await expect(page.getByRole('region', { name: '本次管理操作收據', exact: true })).toBeVisible(); return (await response.json()).data as Body
}
async function screenshot(page: Page, info: TestInfo, name: string) {
  await page.bringToFront(); await expect(page.locator('img[src^="blob:"]')).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
  expect(await page.locator('input:not([type="hidden"]),textarea,select').evaluateAll((nodes) => nodes.filter((node) => node.getClientRects().length).every((node) => Boolean((node as HTMLInputElement).labels?.length || node.getAttribute('aria-label') || node.getAttribute('aria-labelledby'))))).toBe(true)
  await page.evaluate(() => { if (document.activeElement instanceof HTMLElement) document.activeElement.blur(); scrollTo({ top: 0, left: 0, behavior: 'instant' }) }); await page.screenshot({ path: info.outputPath(name), fullPage: true })
}

test('management confirms every true or unspecified task and closes the real TOM order with immutable receipts over TLS', async ({ page, browser }, info) => {
  test.setTimeout(600_000)
  const fixture = closureFixtureFor(info.project.name), collector = collectors.get(info.testId)!, contexts = await Promise.all(Array.from({ length: 5 }, () => contextFor(browser, info)))
  try {
    const [dispatcher, driverA, driverB, temporary, anonymous] = await Promise.all(contexts.map((context) => context.newPage()))
    await enter(page, fixture.admin); await enter(dispatcher, fixture.dispatcher); await enter(driverA, fixture.driverA); await enter(driverB, fixture.driverB); await enter(temporary, fixture.temporary, true); await anonymous.goto('/')
    const bytes = png(), cohort = await createCohort(page, fixture), confirmReason = ' 合成行政確認 e\u0301\n\u0000 <b>保留原文</b> ', secondReason = ' 合成未指定任務確認 e\u0301 \u0000 ', closeReason = ' 合成訂單結案 e\u0301\n\u0000 <script>純文字</script> ', localConfirm = ' 合成本機行政確認不可丟棄 e\u0301 \u0000 ', localClose = ' 合成本機結案不可丟棄 e\u0301 \u0000 '
    expect(await driverA.evaluate(async (raw) => { const bitmap = await createImageBitmap(new Blob([new Uint8Array(raw)], { type: 'image/png' })); const dimensions = [bitmap.width, bitmap.height]; bitmap.close(); return dimensions }, [...bytes])).toEqual([2, 2])
    const a = await completeTask(page, driverA, cohort, 0, bytes, async ({ assignment, execution }) => {
      await closureView(page, cohort, 'TASKS_INCOMPLETE', { completedTaskCount: 0, confirmedTaskCount: 0, blockingExceptionCount: 0, effectiveOccupationCount: 3 })
      const rows = await reviewTasks(page, cohort); expect(rows.filter((row) => row.executionPhase === 'AWAITING_COMPLETION')).toHaveLength(1); expect(rows.find((row) => row.requiredTask === null)!.completionVerified).toBe(false)
      rejected(await api(dispatcher, `${closePath(cohort)}/close`, 'POST', { expectedVersion: cohort.order.version, confirmClosure: true, reason: closeReason }), 409, 'E2107', 'TASKS_INCOMPLETE')
      const report = ok(await api<{ id: string; version: number; description: string }>(driverA, `/tms/my-tasks/${assignment.id}/exceptions`, 'POST', { expectedExecutionVersion: execution.version, description: ' 原始合成結案前異常 e\u0301\r\n\u0000 ' }))
      const blocked = ok(await api<Body>(driverA, `/tms/my-tasks/${assignment.id}/completion-policy`)); expect(blocked.blocker).toBe('UNRESOLVED_EXCEPTIONS')
      const requested = ok(await api<{ version: number; activeRequest: { id: string } }>(dispatcher, `/tms/exceptions/${report.id}/supplement-requests`, 'POST', { expectedVersion: report.version, reason: ' 合成結案前要求補件 e\u0301 \u0000 ' }))
      const supplementPath = `/tms/my-tasks/${assignment.id}/exceptions/${report.id}/supplement-requests/${requested.activeRequest.id}`
      const uploaded = ok(await api<{ version: number; evidence: Evidence }>(driverA, `${supplementPath}/evidence`, 'POST', undefined, { raw: [...bytes], media: 'image/png', executionVersion: execution.version, exceptionVersion: requested.version })); expect(uploaded.evidence.sha256).toBe(hash(bytes))
      const submitted = ok(await api<{ version: number }>(driverA, `${supplementPath}/submit`, 'POST', { expectedVersion: uploaded.version, note: ' 合成結案前明確補件 e\u0301 \u0000 ', evidenceIds: [uploaded.evidence.id] }))
      const resolved = ok(await api<{ status: string; version: number; description: string }>(dispatcher, `/tms/exceptions/${report.id}/resolve`, 'POST', { expectedVersion: submitted.version, reason: ' 合成異常另行處置，不等同安全確認 e\u0301 \u0000 ' })); expect(resolved.status).toBe('RESOLVED'); expect(resolved.description).toBe(report.description); expect(resolved.version).toBe(4)
    })
    const pending = await confirmationDetail(page, cohort, a, 0), emptyHistory = ok(await api<PageData<Confirmation>>(page, `${confirmPath(a)}/confirmation/history?page=0&size=1`)); expect(emptyHistory.content).toEqual([]); expect(emptyHistory.totalElements).toBe(0)
    await closureView(page, cohort, 'TASKS_INCOMPLETE', { completedTaskCount: 1, confirmedTaskCount: 0, blockingExceptionCount: 0, effectiveOccupationCount: 0 })
    const endpoints: [string, string, Body | undefined][] = [
      ['/tms/completions?state=ALL', 'GET', undefined], [`${confirmPath(a)}/confirmation`, 'GET', undefined], [`${confirmPath(a)}/confirm`, 'POST', { expectedVersion: 0, confirmCompletion: true, reason: confirmReason }], [`${confirmPath(a)}/confirmation/history`, 'GET', undefined],
      ['/orders/closure-worklist?state=ALL', 'GET', undefined], [`${closePath(cohort)}/closure`, 'GET', undefined], [`${closePath(cohort)}/closure/tasks`, 'GET', undefined], [`${closePath(cohort)}/closure/history`, 'GET', undefined], [`${closePath(cohort)}/close`, 'POST', { expectedVersion: cohort.order.version, confirmClosure: true, reason: closeReason }],
    ]
    for (const [actorPage, status, code] of [[driverA, 403, 'E0403'], [driverB, 403, 'E0403'], [temporary, 403, 'E0403'], [anonymous, 401, 'E0401']] as const) {
      for (const [path, method, body] of endpoints) rejected(await api(actorPage, path, method, body, { csrf: status === 401 ? 'omit' : undefined }), status, code)
      await expect(actorPage.getByRole('navigation', { name: '主要導覽', exact: true }).getByRole('button', { name: '確認與結案', exact: true })).toHaveCount(0)
    }
    // Version-conflict tokens have two distinct units, never execution4 or a
    // generic actualVersion. Failed MISS requests do not record confirmations.
    const wrongCounter = await api(page, `${confirmPath(a)}/confirm`, 'POST', { expectedVersion: 4, confirmCompletion: true, reason: confirmReason }); rejected(wrongCounter, 409, 'E2101', 'VERSION_CONFLICT'); exact(wrongCounter.data, ['reason', 'actualConfirmationVersion']); expect(wrongCounter.data.actualConfirmationVersion).toBe(0)
    const wrongOrder = await api(page, `${closePath(cohort)}/close`, 'POST', { expectedVersion: 0, confirmClosure: true, reason: closeReason }); rejected(wrongOrder, 409, 'E2101', 'VERSION_CONFLICT'); exact(wrongOrder.data, ['reason', 'actualOrderVersion']); expect(wrongOrder.data.actualOrderVersion).toBe(2)
    for (const [path, body] of [[`${confirmPath(a)}/confirm`, { expectedVersion: 0, confirmCompletion: true, reason: confirmReason }], [`${closePath(cohort)}/close`, { expectedVersion: 2, confirmClosure: true, reason: closeReason }]] as [string, Body][]) {
      for (const invalid of [{ ...body, expectedVersion: '0' }, { ...body, reason: ' \ufeff\u2003\t\n' }, { ...body, actor: fixture.admin.id }, { ...body, reason: 'x'.repeat(1001) }, { ...body, [path.endsWith('/confirm') ? 'confirmCompletion' : 'confirmClosure']: false }]) rejected(await api(page, path, 'POST', invalid), 400, 'E0400')
      rejected(await api(page, path, 'POST', body, { csrf: 'invalid' }), 403, 'E0403', 'CSRF_INVALID'); rejected(await api(page, `${path}?forceRelease=true`, 'POST', body), 400, 'E0400')
      rejected(await api(page, path, 'POST', body, { media: 'text/plain' }), 415, 'E0415'); rejected(await api(page, path, 'POST', undefined, { raw: '{"expectedVersion":0,"expectedVersion":0}', media: 'application/json' }), 400, 'E0400')
      rejected(await api(page, path, 'POST', undefined, { raw: ' '.repeat(16385) }), 413, 'E0413')
    }
    for (const path of [`${confirmPath(a)}/confirmation?policyMode=SYNTHETIC_TEST_ONLY`, '/tms/completions?state=ALL&state=PENDING', `${confirmPath(a)}/confirmation/history?size=101`, '/orders/closure-worklist?sort=asc', `${closePath(cohort)}/closure?forceRelease=true`, `${closePath(cohort)}/closure/tasks?page=-1`, `${closePath(cohort)}/closure/history?size=0`]) rejected(await api(page, path), 400, 'E0400')
    for (const path of [`${confirmPath(a)}/confirmation`, `${closePath(cohort)}/closure`, `${closePath(cohort)}/closure/tasks`]) {
      const response = await page.evaluate(async (path) => { const head = await fetch(`/api/v1${path}`, { method: 'HEAD', credentials: 'same-origin' }); return { status: head.status, cache: head.headers.get('Cache-Control'), empty: (await head.text()) === '' } }, path)
      expect(response).toEqual({ status: 405, cache: 'no-store, private', empty: true })
    }
    rejected(await api(page, `${confirmPath(a)}/confirm`), 405, 'E0405'); rejected(await api(page, `${closePath(cohort)}/close`), 405, 'E0405')
    await confirmationDetail(page, cohort, a, 0)
    const summaries = ok(await api<PageData<ConfirmationSummary>>(dispatcher, `/tms/completions?orderId=${cohort.order.id}&state=PENDING&page=0&size=1`)); expect(summaries.content).toEqual([pending.record]); expect(summaries.totalElements).toBe(1)
    await openConfirmation(page, cohort, a); await openConfirmation(dispatcher, cohort, a); await review(dispatcher, 'confirm', localConfirm); await review(page, 'confirm', confirmReason)
    const confirmBody = { expectedVersion: 0, confirmCompletion: true, reason: confirmReason }, confirmationLoss = await loseManagement(page, `${confirmPath(a)}/confirm`, confirmBody, 'ConfirmCompletionEnvelope', collector, (value) => value.completionId === a.result.completion.id && value.version === 1 && (value.confirmation as Body)?.reason === confirmReason)
    let firstReceipt!: ConfirmResult
    try {
      await page.getByRole('button', { name: ui.confirm.final, exact: true }).click(); firstReceipt = await confirmationLoss.committed as unknown as ConfirmResult; confirmed(firstReceipt, cohort, a, confirmReason)
      const committedFact = await confirmationDetail(dispatcher, cohort, a, 1); expect(committedFact.confirmation).toEqual(firstReceipt.confirmation)
      const received = await retryUnknown(page, confirmationLoss, 'confirm', confirmReason); expect(isDeepStrictEqual(received, firstReceipt)).toBe(true)
      // A receipt is historical: current facts are hidden until an explicit
      // GET, rather than replacing them with the successful receipt.
      await expect(page.getByRole('region', { name: ui.confirm.current, exact: true })).toHaveCount(0)
      await expect(page.getByRole('heading', { name: '目前管理資料尚未重新讀取', exact: true })).toBeVisible()
      await raw(page.getByRole('region', { name: '本次管理操作收據', exact: true }).getByLabel(ui.confirm.raw, { exact: true }), confirmReason)
      const stale = await submit(dispatcher, 'confirm', `${confirmPath(a)}/confirm`, 409); rejected(stale, 409, 'E2101', 'VERSION_CONFLICT'); exact(stale.data, ['reason', 'actualConfirmationVersion']); expect(stale.data.actualConfirmationVersion).toBe(1); await expect(dispatcher.getByLabel(ui.confirm.label, { exact: true })).toHaveValue(localConfirm)
      await fresh(dispatcher, 'confirm', `${confirmPath(a)}/confirmation`, true); await raw(dispatcher.getByRole('region', { name: ui.confirm.current, exact: true }).getByLabel(ui.confirm.raw, { exact: true }), confirmReason)
      await fresh(page, 'confirm', `${confirmPath(a)}/confirmation`); await raw(page.getByRole('region', { name: ui.confirm.current, exact: true }).getByLabel(ui.confirm.raw, { exact: true }), confirmReason)
      const originalExecution = ok(await api<CompleteDetail>(driverA, `/tms/my-tasks/${a.assignment.id}/completion`)); expect(originalExecution.execution).toEqual(a.result.execution); expect(originalExecution.completion).toEqual(a.result.completion)
      const originalOrder = original(await api<Order>(page, `/orders/${cohort.order.id}`)); expect(isDeepStrictEqual(originalOrder, cohort.order)).toBe(true); expect(isDeepStrictEqual(ok(await api(page, `/tms/tasks/${a.taskId}/dispatch`)), a.legacy)).toBe(true)
      await raw(page.getByRole('region', { name: '管理確認歷程', exact: true }).getByLabel(ui.confirm.raw, { exact: true }), confirmReason)
      await screenshot(page, info, 'management-completion-confirmed.png')
    } finally { await confirmationLoss.dispose() }
    const afterFirst = await reviewTasks(page, cohort); expect(afterFirst.find((task) => task.taskId === a.taskId)!.confirmationVersion).toBe(1); expect(afterFirst.find((task) => task.requiredTask === null)!.completionVerified).toBe(false)
    rejected(await api(dispatcher, `${closePath(cohort)}/close`, 'POST', { expectedVersion: 2, confirmClosure: true, reason: closeReason }), 409, 'E2107', 'TASKS_INCOMPLETE')
    const b = await completeTask(dispatcher, driverA, cohort, 1, png(2, 2, 203))
    const waitingForAdmin = await closureView(page, cohort, 'CONFIRMATION_REQUIRED', { completedTaskCount: 2, confirmedTaskCount: 1, blockingExceptionCount: 0, effectiveOccupationCount: 0 }); expect(waitingForAdmin.policy.requirements).toBeNull()
    rejected(await api(page, `${closePath(cohort)}/close`, 'POST', { expectedVersion: 2, confirmClosure: true, reason: closeReason }), 409, 'E2108', 'CONFIRMATION_REQUIRED')
    await openConfirmation(dispatcher, cohort, b); await review(dispatcher, 'confirm', secondReason); const secondReply = await submit(dispatcher, 'confirm', `${confirmPath(b)}/confirm`), secondReceipt = ok(secondReply) as unknown as ConfirmResult; expect(secondReply.replayed).toBe('false'); confirmed(secondReceipt, cohort, b, secondReason); expect(secondReceipt.confirmation.actor.role).toBe('DISPATCHER'); await fresh(dispatcher, 'confirm', `${confirmPath(b)}/confirmation`)
    for (const [task, receipt] of [[a, firstReceipt], [b, secondReceipt]] as const) { const history = ok(await api<PageData<Confirmation>>(page, `${confirmPath(task)}/confirmation/history?page=0&size=1`)); expect(history.content).toEqual([receipt.confirmation]); expect(history.totalElements).toBe(1); expect(ok(await api<PageData<Confirmation>>(page, `${confirmPath(task)}/confirmation/history?page=1&size=1`)).content).toEqual([]) }
    const ready = await closureView(page, cohort, null, { completedTaskCount: 2, confirmedTaskCount: 2, blockingExceptionCount: 0, effectiveOccupationCount: 0 }), manifest = await reviewTasks(page, cohort)
    expect(manifest.every((task) => task.completionVerified && task.executionPhase === 'COMPLETED' && task.executionVersion === 4 && task.confirmationVersion === 1 && task.confirmedAt !== null && task.blockingExceptionCount === 0 && task.effectiveOccupationCount === 0)).toBe(true)
    // This is an accepted historical reference, not a new CRM selection. Rename
    // and disabling may not rewrite snapshots or invent a closure eligibility gate.
    ok(await api(page, `/crm/customers/${cohort.customer.id}`, 'PATCH', { version: cohort.customer.version, name: '合成目前已改名且停用', enabled: false }))
    await closureView(page, cohort, null, { completedTaskCount: 2, confirmedTaskCount: 2, effectiveOccupationCount: 0 })
    await openClosure(page, cohort); await openClosure(dispatcher, cohort)
    const taskRegion = dispatcher.getByRole('region', { name: '結案任務檢核', exact: true }); await expect(taskRegion).toContainText('必要性未指定的任務仍納入全部任務檢核')
    await expect(taskRegion.getByRole('article', { name: `結案任務 ${a.taskNo}`, exact: true })).toContainText('必要任務'); await expect(taskRegion.getByRole('article', { name: `結案任務 ${b.taskNo}`, exact: true })).toContainText('必要性未指定')
    for (const task of [a, b]) await expect(taskRegion.getByRole('article', { name: `結案任務 ${task.taskNo}`, exact: true })).toContainText('已核實完成紀錄')
    await expect(dispatcher.getByRole('region', { name: '目前結案檢核摘要', exact: true })).toContainText('全部任務')
    await review(page, 'close', localClose); await review(dispatcher, 'close', closeReason)
    const closeBody = { expectedVersion: ready.version, confirmClosure: true, reason: closeReason }, closureLoss = await loseManagement(dispatcher, `${closePath(cohort)}/close`, closeBody, 'CloseOrderEnvelope', collector, (value) => value.orderId === cohort.order.id && value.lifecycleStatus === 'CLOSED' && value.version === 3 && (value.closure as Body)?.reason === closeReason)
    let closedReceipt!: CloseResult
    try {
      await dispatcher.getByRole('button', { name: ui.close.final, exact: true }).click(); closedReceipt = await closureLoss.committed as unknown as CloseResult; closed(closedReceipt, cohort, closeReason)
      const current = await closureView(page, cohort, 'ALREADY_CLOSED'); expect(current.auditState).toBe('RECORDED'); expect(current.counts).toBeNull(); expect(current.closure).toEqual(closedReceipt.closure); expect(current.version).toBe(3)
      const historicalManifest = await reviewTasks(page, cohort); expect(historicalManifest).toEqual(manifest); expect(historicalManifest.every((task) => task.sourceOrderVersion === 2)).toBe(true)
      const closedOrder = original(await api<Order>(page, `/orders/${cohort.order.id}`)); expect(closedOrder.lifecycleStatus).toBe('CLOSED'); expect(closedOrder.acceptanceStatus).toBe('ACCEPTED'); expect(closedOrder.version).toBe(3)
      for (const field of ['id', 'orderNo', 'type', 'customerId', 'customerName', 'customerReference', 'remark', 'shipping', 'items', 'tasks', 'createdAt', 'createdBy']) expect(isDeepStrictEqual(closedOrder[field], cohort.order[field])).toBe(true)
      const ordinaryList = original(await api<PageData<Body>>(page, `/orders?customerId=${cohort.customer.id}&page=0&size=100`)); expect(ordinaryList.content.find((order) => order.id === cohort.order.id)).toMatchObject({ lifecycleStatus: 'CLOSED', acceptanceStatus: 'ACCEPTED', version: 3 })
      const closeReplay = await retryUnknown(dispatcher, closureLoss, 'close', closeReason); expect(isDeepStrictEqual(closeReplay, closedReceipt)).toBe(true)
      await expect(dispatcher.getByRole('region', { name: ui.close.current, exact: true })).toHaveCount(0); await expect(dispatcher.getByRole('heading', { name: '目前管理資料尚未重新讀取', exact: true })).toBeVisible(); await raw(dispatcher.getByRole('region', { name: '本次管理操作收據', exact: true }).getByLabel(ui.close.raw, { exact: true }), closeReason)
      const staleClose = await submit(page, 'close', `${closePath(cohort)}/close`, 409); rejected(staleClose, 409, 'E2101', 'VERSION_CONFLICT'); exact(staleClose.data, ['reason', 'actualOrderVersion']); expect(staleClose.data.actualOrderVersion).toBe(3); await expect(page.getByLabel(ui.close.label, { exact: true })).toHaveValue(localClose)
      await fresh(page, 'close', `${closePath(cohort)}/closure`, true); await fresh(dispatcher, 'close', `${closePath(cohort)}/closure`)
      await raw(page.getByRole('region', { name: ui.close.current, exact: true }).getByLabel(ui.close.raw, { exact: true }), closeReason)
      await raw(dispatcher.getByRole('region', { name: ui.close.current, exact: true }).getByLabel(ui.close.raw, { exact: true }), closeReason)
      await raw(dispatcher.getByRole('region', { name: '訂單結案歷程', exact: true }).getByLabel(ui.close.raw, { exact: true }), closeReason)
      const originalRead = dispatcher.waitForResponse((response) => response.request().method() === 'GET' && new URL(response.url()).pathname === `/api/v1/orders/${cohort.order.id}`)
      await dispatcher.getByRole('button', { name: '查看訂單', exact: true }).click(); expect((await originalRead).status()).toBe(200)
      const originalRegion = dispatcher.getByRole('region', { name: '訂單原始資料', exact: true }); await expect(originalRegion).toContainText('已結案'); await expect(originalRegion).toContainText('已正式受理'); await raw(originalRegion.getByLabel('訂單原始備註', { exact: true }), cohort.order.remark as string)
      await expect(originalRegion.locator('dl > div').filter({ has: dispatcher.getByText('客戶', { exact: true }) }).locator('dd')).toHaveText(cohort.order.customerName as string)
      await screenshot(dispatcher, info, 'management-recorded-order-closure.png')
      const history = ok(await api<PageData<ClosureWitness>>(page, `${closePath(cohort)}/closure/history?page=0&size=1`)); expect(history.content).toEqual([closedReceipt.closure]); expect(history.totalElements).toBe(1); expect(ok(await api<PageData<ClosureWitness>>(page, `${closePath(cohort)}/closure/history?page=1&size=1`)).content).toEqual([])
      const recordedList = ok(await api<PageData<Body>>(page, '/orders/closure-worklist?state=RECORDED&page=0&size=100')); const record = recordedList.content.find((order) => order.orderId === cohort.order.id)!; exact(record, ['orderId', 'orderNo', 'type', 'lifecycleStatus', 'acceptanceStatus', 'version', 'auditState', 'updatedAt', 'closedAt']); expect(record).toMatchObject({ lifecycleStatus: 'CLOSED', acceptanceStatus: 'ACCEPTED', version: 3, auditState: 'RECORDED', closedAt: closedReceipt.closure.closedAt })
      expect(ok(await api<PageData<Body>>(page, '/orders/closure-worklist?state=OPEN&page=0&size=100')).content.some((order) => order.orderId === cohort.order.id)).toBe(false)
      // Receipt remains original despite canonical JSON spelling, current CLOSED
      // version3 and CRM disabling. Changing raw reason is a different command.
      const canonical = JSON.stringify({ reason: closeReason, confirmClosure: true }).replace(/}$/, ',"expectedVersion":2e0}')
      const canonicalReplay = await closureLoss.replay(dispatcher, canonical); expect(canonicalReplay.replayed).toBe('true'); expect(isDeepStrictEqual(ok(canonicalReplay), closedReceipt)).toBe(true)
      rejected(await closureLoss.replay(dispatcher, undefined, { ...closeBody, reason: `${closeReason} changed` }), 409, 'E2104', 'IDEMPOTENCY_KEY_REUSED')
      rejected(await api(page, `${closePath(cohort)}/close`, 'POST', { ...closeBody, expectedVersion: 3 }), 409, 'E2102', 'INVALID_STATE')
      // Old codecs keep their original OPEN snapshots and child identities.
      expect(isDeepStrictEqual(original(await api<Order>(page, '/orders', 'POST', cohort.draft, { key: cohort.createKey }), 201), cohort.created)).toBe(true)
      expect(isDeepStrictEqual(original(await api<Order>(page, `/orders/${cohort.order.id}`, 'PUT', cohort.putBody, { key: cohort.putKey })), cohort.edited)).toBe(true)
      expect(isDeepStrictEqual(original(await api<Order>(page, `/orders/${cohort.order.id}/accept`, 'POST', { expectedVersion: 1 }, { key: cohort.acceptKey })), cohort.order)).toBe(true)
      // New-reference creation uses an active new customer, while physical
      // resources can be reused by another order without affecting old proof.
      const otherCustomer = ok(await api<{ id: string }>(page, '/crm/customers', 'POST', { customerCode: `CLS_REUSE_${fixture.scope}_${randomUUID().slice(0, 8).toUpperCase()}`, name: '合成結案後重用客戶' }), 201)
      const reuseDraft = { ...(cohort.draft as Body), customerId: otherCustomer.id, tasks: [(cohort.draft.tasks as Body[])[0]] }, reuseCreated = original(await api<Order>(page, '/orders', 'POST', reuseDraft), 201), reuseOrder = original(await api<Order>(page, `/orders/${reuseCreated.id}/accept`, 'POST', { expectedVersion: 0 }))
      const reused = ok(await api<DispatchCommand>(page, `/tms/tasks/${reuseOrder.tasks[0].id}/dispatch`, 'POST', { expectedGeneration: 0, driverId: cohort.driver.id, mainVehicleId: cohort.main.id, trailerId: cohort.trailer.id })); expect(reused.assignment.reservations).toHaveLength(3)
      for (const task of [a, b]) {
        const fact = ok(await api<CompleteDetail>(driverA, `/tms/my-tasks/${task.assignment.id}/completion`)); exact(fact, [...safeFields, 'execution', 'completion']); expect(fact.execution).toEqual(task.result.execution); expect(fact.completion).toEqual(task.result.completion); expect(isDeepStrictEqual(copySafe(fact), task.source)).toBe(true)
        const replay = await api<CompleteResult>(driverA, `/tms/my-tasks/${task.assignment.id}/complete`, 'POST', task.body, { key: task.key }); expect(replay.replayed).toBe('true'); expect(isDeepStrictEqual(ok(replay), task.result)).toBe(true)
        for (const suffix of ['', '/progress-policy', '/evidence', '/exceptions']) rejected(await api(driverA, `/tms/my-tasks/${task.assignment.id}${suffix}`), 404, 'E0404')
        const confirmedRead = await confirmationDetail(page, cohort, task, 1); expect(confirmedRead.record.sourceOrderVersion).toBe(2)
      }
      expect(await reviewTasks(page, cohort)).toEqual(manifest); await closureView(page, cohort, 'ALREADY_CLOSED')
      const originalConfirmationReplay = await confirmationLoss.replay(page, JSON.stringify({ reason: confirmReason, confirmCompletion: true }).replace(/}$/, ',\"expectedVersion\":0e0}')); expect(originalConfirmationReplay.replayed).toBe('true'); expect(isDeepStrictEqual(ok(originalConfirmationReplay), firstReceipt)).toBe(true)
      const reuseResources = ok(await api<PageData<{ id: string; occupied: boolean; occupation: { assignmentId: string } | null }>>(page, `/tms/resources?taskId=${reuseOrder.tasks[0].id}&position=DRIVER&q=${encodeURIComponent(cohort.driver.driverCode)}`)); expect(reuseResources.content.find((resource) => resource.id === cohort.driver.id)).toMatchObject({ occupied: true, occupation: { assignmentId: reused.assignment.id } })
      const afterReuseReceipt = await closureLoss.replay(dispatcher); expect(afterReuseReceipt.replayed).toBe('true'); expect(isDeepStrictEqual(ok(afterReuseReceipt), closedReceipt)).toBe(true)
      const oldList = ok(await api<PageData<Body>>(driverA, '/tms/my-tasks?size=100')); expect(oldList.content.map((task) => task.assignmentId)).toEqual(expect.arrayContaining([a.assignment.id, b.assignment.id, reused.assignment.id])); for (const task of oldList.content) exact(task, safeFields)
      // Binding changes affect only the narrow DRIVER capability, never the
      // company manager's historical facts. Command ledgers remain actor-scoped.
      const unbound = ok(await api<Driver>(page, `/fms/drivers/${cohort.driver.id}`, 'PATCH', { version: cohort.driver.version, accountId: null })); rejected(await api(driverA, `/tms/my-tasks/${a.assignment.id}/completion`), 404, 'E0404')
      ok(await api(page, `/fms/drivers/${cohort.driver.id}`, 'PATCH', { version: unbound.version, accountId: fixture.driverB.id })); expect(ok(await api<CompleteDetail>(driverB, `/tms/my-tasks/${a.assignment.id}/completion`)).completion).toEqual(a.result.completion)
      const actorMiss = await api(driverB, `/tms/my-tasks/${a.assignment.id}/complete`, 'POST', a.body, { key: a.key }); rejected(actorMiss, 409, 'E2001', 'VERSION_CONFLICT'); expect(actorMiss.data.actualVersion).toBe(4)
      await confirmationDetail(page, cohort, a, 1); await closureView(page, cohort, 'ALREADY_CLOSED')
      // Fresh role revocation precedes even an original successful receipt.
      const account = ok(await api<Account[]>(page, '/upms/accounts')).find((entry) => entry.id === fixture.dispatcher.id)!
      ok(await api(page, `/upms/accounts/${account.id}`, 'PATCH', { version: account.version, displayName: account.displayName, role: 'DRIVER', enabled: true }))
      rejected(await closureLoss.replay(dispatcher), 401, 'E0401'); rejected(await api(dispatcher, `${confirmPath(a)}/confirmation`), 401, 'E0401')
      await dispatcher.bringToFront(); await dispatcher.getByRole('button', { name: ui.close.fresh, exact: true }).click(); await expect(dispatcher.getByRole('heading', { name: '登入工作空間', exact: true })).toBeVisible(); await expect(dispatcher.getByRole('region', { name: ui.close.current, exact: true })).toHaveCount(0); await expect(dispatcher.getByLabel(ui.close.raw, { exact: true })).toHaveCount(0)
      await enter(dispatcher, fixture.dispatcher); await expect(dispatcher.getByRole('navigation', { name: '主要導覽', exact: true }).getByRole('button', { name: '確認與結案', exact: true })).toHaveCount(0)
      rejected(await closureLoss.replay(dispatcher), 403, 'E0403'); rejected(await api(dispatcher, `${confirmPath(a)}/confirmation`), 403, 'E0403')
    } finally { await closureLoss.dispose() }
  } finally { await Promise.all(contexts.map((context) => context.close())) }
})
