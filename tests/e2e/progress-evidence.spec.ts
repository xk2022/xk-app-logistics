import { createHash, randomUUID } from 'node:crypto'
import { readFileSync, writeFileSync } from 'node:fs'
import { request as httpsRequest } from 'node:https'
import { crc32, deflateSync } from 'node:zlib'
import { isDeepStrictEqual } from 'node:util'
import { expect, test, type Browser, type BrowserContext, type Page, type Request, type Response, type Route, type TestInfo } from '@playwright/test'
import { progressEvidenceFixtureFor, signIn, type Credential } from './fixtures'

// M4-02 adds only API acceptance; the product execution UI is M4-06.
// Every actor/resource is independent of all previous 30 scenarios.
test.describe.configure({ retries: 0 })
type Body = Record<string, unknown>
interface Reply<T> { status: number; code: string; data: T; payload: Body; replayed: string | null; privateRoute: boolean; cache: string | null }
interface PageData<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }
interface Execution { phase: string; version: number; departedAt: string | null }
interface ProgressResult { assignmentId: string; taskId: string; execution: Execution }
interface OwnDetail extends Body { assignmentId: string; taskId: string; execution: Execution }
interface Evidence { id: string; assignmentId: string; taskId: string; phase: string; executionVersion: number; mediaType: string; byteSize: number; sha256: string; uploadedAt: string; status: string }
interface Policy { assignmentId: string; taskId: string; execution: Execution; mode: string; nextPhase: string | null; requirements: { minimumCount: number; allowedMediaTypes: string[]; maxBytes: number; maxPixels: number } | null; blocker: string | null }
interface Driver { id: string; driverCode: string; accountId: string | null; version: number }
interface Vehicle { id: string; plateNo: string; version: number }
interface Account { id: string; displayName: string; role: string; enabled: boolean; version: number }
interface Order extends Body { id: string; version: number; tasks: { id: string; clientRef: string }[] }
interface Assignment extends Body { id: string; taskId: string; version: number; status: string; reservations: { releasedAt: string | null }[] }
interface Command { taskId: string; generation: number; assignment: Assignment }
interface DispatchDetail { task: { generation: number }; current: Assignment | null; history: PageData<Assignment> }
interface Scenario { order: Order; taskId: string; dependentId: string; assignment: Assignment; driver: Driver; main: Vehicle }
interface Captured { schema: string; value: unknown }
type NewSchema = 'ProgressPolicyEnvelope' | 'ProgressEnvelope' | 'EvidenceEnvelope' | 'EvidencePageEnvelope' | 'ErrorEnvelope'
const photoFields = ['id', 'assignmentId', 'taskId', 'phase', 'executionVersion', 'mediaType', 'byteSize', 'sha256', 'uploadedAt', 'status']
const originalFields = ['assignmentId', 'taskId', 'taskNo', 'item', 'taskKind', 'pickupName', 'pickupAddress', 'deliveryName', 'deliveryAddress', 'siteContact', 'plannedStartAt', 'plannedEndAt', 'assignedAt', 'policyMode']
const hash = (bytes: Buffer) => createHash('sha256').update(bytes).digest('hex')
function png(width = 2, height = 2, color = 97, padding = 0): Buffer {
  const chunk = (name: string, data: Buffer) => { const type = Buffer.from(name), length = Buffer.alloc(4), checksum = Buffer.alloc(4); length.writeUInt32BE(data.length); checksum.writeUInt32BE(crc32(Buffer.concat([type, data]))); return Buffer.concat([length, type, data, checksum]) }
  const header = Buffer.alloc(13); header.writeUInt32BE(width, 0); header.writeUInt32BE(height, 4); header[8] = 8; header[9] = 6
  const scanlines = Buffer.alloc((width * 4 + 1) * height)
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) { const offset = y * (width * 4 + 1) + 1 + x * 4; scanlines[offset] = color; scanlines[offset + 1] = 137; scanlines[offset + 2] = 211; scanlines[offset + 3] = 255 }
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header), ...(padding ? [chunk('tEXt', Buffer.concat([Buffer.from('synthetic\0'), Buffer.alloc(padding, 65)]))] : []), chunk('IDAT', deflateSync(scanlines)), chunk('IEND', Buffer.alloc(0))])
}
class ResponseCollector {
  readonly photos: Captured[] = []; readonly execution: Captured[] = []; readonly tms: Captured[] = []; readonly orders: Captured[] = []; readonly acceptances: Captured[] = []
  private readonly pages = new Set<Page>(); private readonly pending = new Set<Promise<void>>()
  private readonly capture = (response: Response) => {
    const path = new URL(response.url()).pathname, method = response.request().method()
    let schema: string | undefined, target: Captured[] | undefined
    if (/^\/api\/v1\/tms\/my-tasks\/[0-9a-f-]{36}\/(progress-policy|progress|evidence(?:\/[0-9a-f-]{36}\/content)?)$/i.test(path)) {
      if (path.endsWith('/content') && response.status() < 400) return // Binary is checked in memory, never written to artifacts.
      target = this.photos; schema = path.endsWith('/progress-policy') ? 'ProgressPolicyEnvelope' : path.endsWith('/progress') ? 'ProgressEnvelope' : method === 'POST' ? 'EvidenceEnvelope' : 'EvidencePageEnvelope'
    } else if (/^\/api\/v1\/tms\/my-tasks\/[0-9a-f-]{36}(?:\/depart)?$/i.test(path)) { target = this.execution; schema = path.endsWith('/depart') ? 'DepartMyTaskEnvelope' : 'MyTaskExecutionEnvelope' }
    else if (path === '/api/v1/tms/my-tasks' && method === 'GET') { target = this.tms; schema = 'MyTaskPageEnvelope' }
    else if (/^\/api\/v1\/tms\/tasks\/[0-9a-f-]{36}\/dispatch$/i.test(path)) { target = this.tms; schema = method === 'GET' ? 'DispatchDetailEnvelope' : 'CommandResultEnvelope' }
    else if (/^\/api\/v1\/tms\/dispatches\/[0-9a-f-]{36}\/(revoke|reassign)$/i.test(path)) { target = this.tms; schema = 'CommandResultEnvelope' }
    else if (/^\/api\/v1\/orders\/[0-9a-f-]{36}\/accept$/i.test(path)) { target = this.acceptances; schema = 'AcceptedOrderEnvelope' }
    else if (path === '/api/v1/orders' || /^\/api\/v1\/orders\/[0-9a-f-]{36}$/i.test(path)) { target = this.orders; schema = 'OrderEnvelope' }
    if (!target || !schema) return
    const destination = target, name = response.status() >= 400 ? 'ErrorEnvelope' : schema
    const pending = (async () => { try { destination.push({ schema: name, value: await response.json() }) } catch { /* Bodyless/aborted responses are not JSON evidence. */ } })()
    this.pending.add(pending); void pending.then(() => this.pending.delete(pending), () => this.pending.delete(pending))
  }
  watch(page: Page): void { if (!this.pages.has(page)) { this.pages.add(page); page.on('response', this.capture) } }
  record(schema: NewSchema, value: Body): void { this.photos.push({ schema, value }) }
  async finish(): Promise<void> { for (const page of this.pages) page.off('response', this.capture); await Promise.allSettled([...this.pending]) }
}
const collectors = new Map<string, ResponseCollector>()
test.beforeEach(async ({ page }, info) => { const collector = new ResponseCollector(); collectors.set(info.testId, collector); collector.watch(page) })
test.afterEach(async ({}, info) => {
  const collector = collectors.get(info.testId); if (!collector) return; await collector.finish()
  // Only original public synthetic JSON bodies. PNG/headers/filename/request
  // context/credentials/CSRF/cookies/keys never enter any saved artifact.
  for (const [name, values] of [['progress-evidence', collector.photos], ['execution', collector.execution], ['tms', collector.tms], ['order', collector.orders], ['acceptance', collector.acceptances]] as const) writeFileSync(info.outputPath(`${name}-contract-responses.json`), JSON.stringify(values))
  collectors.delete(info.testId)
})
interface Options { key?: string; raw?: string | number[]; version?: string | null; media?: string; csrf?: 'invalid' | 'omit'; length?: number; encoding?: string; headers?: Record<string, string> }
async function api<T = Body>(page: Page, path: string, method = 'GET', body?: unknown, options: Options = {}): Promise<Reply<T>> {
  return page.evaluate(async ({ path, method, body, options }) => {
    const headers: Record<string, string> = { Accept: path.split('?')[0].endsWith('/content') ? 'image/png' : 'application/json', ...options.headers }
    if (method !== 'GET') {
      if (options.csrf !== 'omit') { const csrf = (await (await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' })).json()).data; if (csrf && typeof csrf.token === 'string') headers[csrf.headerName] = options.csrf === 'invalid' ? 'synthetic-invalid-csrf' : csrf.token }
      headers['Content-Type'] = options.media ?? 'application/json'; headers['Idempotency-Key'] = options.key ?? crypto.randomUUID()
      if (options.version !== undefined && options.version !== null) headers['X-Execution-Version'] = options.version
      if (options.encoding) headers['Content-Encoding'] = options.encoding
    }
    const wire = options.length !== undefined ? new Uint8Array(options.length) : Array.isArray(options.raw) ? new Uint8Array(options.raw) : options.raw ?? (body === undefined ? undefined : JSON.stringify(body))
    const response = await fetch(`/api/v1${path}`, { method, credentials: 'same-origin', headers, ...(wire === undefined ? {} : { body: wire }) })
    const payload = await response.json()
    return { status: response.status, code: payload.code, data: payload.data, payload, replayed: response.headers.get('Idempotency-Replayed'), privateRoute: /\/(progress-policy|progress|evidence)(?:\/|$)/.test(path.split('?')[0]), cache: response.headers.get('Cache-Control') }
  }, { path, method, body, options })
}
const upload = (page: Page, path: string, bytes: Buffer, version: number | string, key = randomUUID()) => api<Evidence>(page, `${path}/evidence`, 'POST', undefined, { raw: [...bytes], version: String(version), media: 'image/png', key })
async function contextFor(browser: Browser, info: TestInfo): Promise<BrowserContext> { const use = info.project.use; const context = await browser.newContext({ baseURL: use.baseURL, ignoreHTTPSErrors: use.ignoreHTTPSErrors, viewport: use.viewport, screen: use.screen, deviceScaleFactor: use.deviceScaleFactor, isMobile: use.isMobile, hasTouch: use.hasTouch, userAgent: use.userAgent }); context.on('page', (page) => collectors.get(info.testId)?.watch(page)); return context }
async function enter(page: Page, credential: Credential, temporary = false) { await page.goto('/'); expect(new URL(page.url()).protocol).toBe('https:'); await signIn(page, credential); await expect(page.getByRole(temporary ? 'heading' : 'button', { name: temporary ? '首次登入，請先修改密碼' : '登出', exact: true })).toBeVisible() }
function exact(value: object, keys: readonly string[]) { expect(Object.keys(value).sort()).toEqual([...keys].sort()) }
function ok<T>(reply: Reply<T>, status = 200, code = '0000'): T { if (reply.privateRoute) expect(reply.cache).toBe('no-store, private'); expect(reply.status).toBe(status); expect(reply.code).toBe(code); exact(reply.payload, ['code', 'message', 'data', 'timestamp']); expect(typeof reply.payload.timestamp === 'string' && reply.payload.timestamp.endsWith('+08:00')).toBe(true); return reply.data }
function rejected(reply: Reply<unknown>, status: number, code: string, reason?: string) { const data = ok(reply, status, code); if (reason) expect((data as Body)?.reason).toBe(reason); if ([401, 404, 405, 413, 415, 500].includes(status)) expect(data).toBeNull(); expect(reply.replayed).toBeNull() }
function photo(reply: Reply<Evidence>, scenario: Scenario, bytes: Buffer, phase: string, version: number, replayed = false): Evidence { const value = ok(reply); exact(value, photoFields); expect(value.assignmentId).toBe(scenario.assignment.id); expect(value.taskId).toBe(scenario.taskId); expect(value.phase).toBe(phase); expect(value.executionVersion).toBe(version); expect(value.mediaType).toBe('image/png'); expect(value.byteSize).toBe(bytes.byteLength); expect(value.sha256).toBe(hash(bytes)); expect(value.status).toBe('VERIFIED'); expect(reply.replayed).toBe(String(replayed)); return value }
function progress(reply: Reply<ProgressResult>, scenario: Scenario, target: string, version: number, departedAt: string | null, replayed = false): ProgressResult { const value = ok(reply); exact(value, ['assignmentId', 'taskId', 'execution']); exact(value.execution, ['phase', 'version', 'departedAt']); expect(value.assignmentId).toBe(scenario.assignment.id); expect(value.taskId).toBe(scenario.taskId); expect(value.execution).toEqual({ phase: target, version, departedAt }); expect(reply.replayed).toBe(String(replayed)); return value }
async function current(page: Page, path: string, phase: string, version: number): Promise<OwnDetail> { const value = ok(await api<OwnDetail>(page, path)); exact(value, [...originalFields, 'execution']); exact(value.execution, ['phase', 'version', 'departedAt']); expect(value.execution.phase).toBe(phase); expect(value.execution.version).toBe(version); return value }
async function binary(page: Page, path: string, expected: Buffer, extra: Record<string, string> = {}) {
  return page.evaluate(async ({ path, expected, extra }) => {
    const response = await fetch(`/api/v1${path}`, { credentials: 'same-origin', headers: { Accept: 'image/png', ...extra } })
    if (!response.ok) { const payload = await response.json(); return { status: response.status, json: payload, code: payload.code, data: payload.data, replayed: response.headers.get('Idempotency-Replayed') } }
    const bytes = new Uint8Array(await response.arrayBuffer()), digest = [...new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))].map((byte) => byte.toString(16).padStart(2, '0')).join('')
    const bitmap = await createImageBitmap(new Blob([bytes], { type: 'image/png' })); const dimensions = [bitmap.width, bitmap.height]; bitmap.close()
    return { status: response.status, equal: bytes.length === expected.length && bytes.every((byte, index) => byte === expected[index]), digest, dimensions, size: bytes.length, media: response.headers.get('Content-Type'), length: response.headers.get('Content-Length'), cache: response.headers.get('Cache-Control'), nosniff: response.headers.get('X-Content-Type-Options'), disposition: response.headers.get('Content-Disposition'), forbiddenHeaders: ['ETag', 'Last-Modified', 'Accept-Ranges', 'Location', 'Content-Location'].some((header) => response.headers.has(header)) }
  }, { path, expected: [...expected], extra })
}
async function privatePng(page: Page, path: string, bytes: Buffer, extra: Record<string, string> = {}) { const value = await binary(page, path, bytes, extra); expect(value.status).toBe(200); expect(value.equal).toBe(true); expect(value.digest).toBe(hash(bytes)); expect(value.dimensions).toEqual([2, 2]); expect(value.size).toBe(bytes.length); expect(value.length).toBe(String(bytes.length)); expect(value.media).toBe('image/png'); expect(value.cache).toBe('no-store, private'); expect(value.nosniff).toBe('nosniff'); expect(value.disposition).toBe('inline; filename="evidence.png"'); expect(value.forbiddenHeaders).toBe(false) }
interface Forwarded { status: number; payload: Body; replayed?: string; cache?: string }
const safeForwardFailure = 'The controlled synthetic progress/evidence request could not be completed safely.'
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
async function loseCommit(page: Page, path: string, kind: 'upload' | 'progress', scenario: Scenario, expectedPhase: string, expectedVersion: number, collector: ResponseCollector) {
  const origin = new URL(page.url()).origin, fullPath = `/api/v1${path}`
  let attempts = 0, matching = false; let firstKey: string | undefined, firstVersion: string | undefined; let firstBody: Buffer | null = null
  let resolve!: (body: Body) => void, reject!: (failure: Error) => void
  const committed = new Promise<Body>((yes, no) => { resolve = yes; reject = no }); void committed.catch(() => undefined)
  const cancellation = new AbortController(), active = new Set<Promise<void>>()
  const matches = (url: URL) => url.origin === origin && url.pathname === fullPath
  const deadline = setTimeout(() => { cancellation.abort(); reject(new Error(safeForwardFailure)) }, 20_000)
  const handle = async (route: Route) => {
    try {
      const request = route.request(); if (request.method() !== 'POST') { await route.continue(); return }
      attempts++; const headers = await request.allHeaders(), key = headers['idempotency-key'], version = headers['x-execution-version'], body = request.postDataBuffer()
      if (attempts === 1) {
        firstKey = key; firstVersion = version; firstBody = body
        const reply = await forward(request, origin, fullPath, cancellation.signal), result = reply.payload.data as Body | undefined
        if (reply.status !== 200 || reply.cache !== 'no-store, private' || reply.payload.code !== '0000' || reply.replayed !== 'false' || !result || result.assignmentId !== scenario.assignment.id || result.taskId !== scenario.taskId) throw new Error(safeForwardFailure)
        if (kind === 'upload' ? result.phase !== expectedPhase || result.executionVersion !== expectedVersion || result.status !== 'VERIFIED' : (result.execution as Execution | undefined)?.phase !== expectedPhase || (result.execution as Execution).version !== expectedVersion) throw new Error(safeForwardFailure)
        collector.record(kind === 'upload' ? 'EvidenceEnvelope' : 'ProgressEnvelope', reply.payload); await route.abort('failed'); clearTimeout(deadline); resolve(result)
      } else {
        const same = Boolean(firstKey && key === firstKey && version === firstVersion && firstBody && body && firstBody.equals(body)); matching = attempts === 2 ? same : matching && same
        if (attempts === 2) {
          const reply = await forward(request, origin, fullPath, cancellation.signal, true)
          if (reply.status !== 403 || reply.cache !== 'no-store, private' || reply.payload.code !== 'E0403' || (reply.payload.data as Body | null)?.reason !== 'CSRF_INVALID') throw new Error(safeForwardFailure)
          await route.fulfill({ status: 403, contentType: 'application/json', headers: { 'Cache-Control': reply.cache! }, body: JSON.stringify(reply.payload) })
        } else await route.continue()
      }
    } catch { clearTimeout(deadline); reject(new Error(safeForwardFailure)); try { await route.abort('failed') } catch { /* Closing page. */ } }
  }
  const handler = (route: Route) => { const pending = handle(route); active.add(pending); void pending.then(() => active.delete(pending), () => active.delete(pending)); return pending }
  await page.route(matches, handler)
  return { committed, matching: () => matching, attempts: () => attempts, dispose: async () => { clearTimeout(deadline); cancellation.abort(); reject(new Error(safeForwardFailure)); await Promise.allSettled([...active]); await page.unroute(matches, handler) } }
}

test('controlled progress and private immutable PNG evidence preserve current authority, receipts and occupancy over TLS', async ({ page, browser }, info) => {
  test.setTimeout(480_000)
  const fixture = progressEvidenceFixtureFor(info.project.name), scope = fixture.scope
  const contexts = await Promise.all(Array.from({ length: 5 }, () => contextFor(browser, info)))
  try {
    const [dispatcher, driverA, driverB, temporary, anonymous] = await Promise.all(contexts.map((context) => context.newPage()))
    await enter(page, fixture.admin); await enter(dispatcher, fixture.dispatcher); await enter(driverA, fixture.driverA); await enter(driverB, fixture.driverB); await enter(temporary, fixture.temporary, true); await anonymous.goto('/')
    const red = png(), blue = png(2, 2, 203)
    // Independently decode the generated test image before using it as a valid fixture.
    const decoded = await driverA.evaluate(async (bytes) => { const bitmap = await createImageBitmap(new Blob([new Uint8Array(bytes)], { type: 'image/png' })); const size = [bitmap.width, bitmap.height]; bitmap.close(); return size }, [...red])
    expect(decoded).toEqual([2, 2])
    const unknown = `/tms/my-tasks/${randomUUID()}`, missingContent = `${unknown}/evidence/${randomUUID()}/content`
    for (const [actor, status, code] of [[anonymous, 401, 'E0401'], [page, 403, 'E0403'], [dispatcher, 403, 'E0403'], [temporary, 403, 'E0403']] as const) {
      for (const path of [`${unknown}/progress-policy`, `${unknown}/evidence`, missingContent]) rejected(await api(actor, path), status, code)
      rejected(await api(actor, `${unknown}/progress`, 'POST', { expectedVersion: 1, targetPhase: 'IN_PROGRESS', evidenceIds: [] }, { csrf: status === 401 ? 'omit' : undefined }), status, code)
      rejected(await api(actor, `${unknown}/evidence`, 'POST', undefined, { raw: [...red], media: 'image/png', version: '1', csrf: status === 401 ? 'omit' : undefined }), status, code)
    }
    const customer = ok(await api<{ id: string }>(page, '/crm/customers', 'POST', { customerCode: `PROGRESS_${scope}_${randomUUID().slice(0, 8).toUpperCase()}`, name: `合成進度客戶 ${scope}` }), 201)
    let sequence = 3000
    const setup = async (accountId: string): Promise<Scenario> => {
      const driver = ok(await api<Driver>(page, '/fms/drivers', 'POST', { driverCode: `SYNTHETIC_TMS_${scope}_D${++sequence}`, name: `合成證據司機 ${scope} ${sequence}`, accountId, employmentStatus: 'ACTIVE', dutyStatus: 'ON_DUTY' }), 201)
      const main = ok(await api<Vehicle>(page, '/fms/vehicles', 'POST', { plateNo: `PNG-${scope[0]}-${randomUUID().slice(0, 10)}`, vehicleType: fixture.vehicleTypes.main.id, capacityTon: '1.00', administrativeStatus: 'AVAILABLE' }), 201)
      // Canonical decimal output and valid nonblank contact follow the unchanged TOM contract.
      const draft = { type: 'LOCAL', customerId: customer.id, remark: '整單私有合成備註不能出現在照片或本人資料', items: [{ clientRef: 'photo-item', sequence: 1, itemKind: 'CARGO', packageQty: 7, grossWeight: '0.0000000000000000001', cbm: '0' }], tasks: Array.from({ length: 2 }, (_, index) => ({ clientRef: `photo-task-${index}`, itemRef: 'photo-item', taskKind: `SYNTHETIC_TMS_${scope}_PROGRESS_${sequence}_${index}`, pickupName: ' 合成 e\u0301 起點 ', pickupAddress: ' 合成\u0000地址\r\n二樓 ', deliveryName: '合成終點', deliveryAddress: '合成終點地址（非真實地點）', siteContact: { name: ' 合成 e\u0301 聯絡 ', phone: null }, plannedStartAt: '1500-01-02T08:00:00.123456+23:59', plannedEndAt: '1500-01-02T10:00:00.123456+23:59', requiredTask: true, predecessorRefs: index ? ['photo-task-0'] : [] })) }
      const created = await api<Order>(page, '/orders', 'POST', draft); expect(created.status).toBe(201); expect(created.code).toBe('OK')
      const accepted = await api<Order>(page, `/orders/${created.data.id}/accept`, 'POST', { expectedVersion: created.data.version }); expect(accepted.status).toBe(200); expect(accepted.code).toBe('OK')
      const taskId = accepted.data.tasks.find((task) => task.clientRef === 'photo-task-0')!.id, dependentId = accepted.data.tasks.find((task) => task.clientRef === 'photo-task-1')!.id
      const assigned = ok(await api<Command>(page, `/tms/tasks/${taskId}/dispatch`, 'POST', { expectedGeneration: 0, driverId: driver.id, mainVehicleId: main.id, trailerId: null }))
      return { order: accepted.data, taskId, dependentId, assignment: assigned.assignment, driver, main }
    }
    const a = await setup(fixture.driverA.id), b = await setup(fixture.driverB.id), pathA = `/tms/my-tasks/${a.assignment.id}`, pathB = `/tms/my-tasks/${b.assignment.id}`
    const before = ok(await api<DispatchDetail>(page, `/tms/tasks/${a.taskId}/dispatch`))
    const policy = async (phase: string, version: number, blocker: string | null, nextPhase: string | null): Promise<Policy> => {
      const value = ok(await api<Policy>(driverA, `${pathA}/progress-policy`)); exact(value, ['assignmentId', 'taskId', 'execution', 'mode', 'nextPhase', 'requirements', 'blocker']); expect(value.assignmentId).toBe(a.assignment.id); expect(value.taskId).toBe(a.taskId); expect(value.execution.phase).toBe(phase); expect(value.execution.version).toBe(version); expect(value.mode).toBe('SYNTHETIC_TEST_ONLY'); expect(value.blocker).toBe(blocker); expect(value.nextPhase).toBe(nextPhase)
      if (blocker) expect(value.requirements).toBeNull(); else expect(value.requirements).toEqual({ minimumCount: 1, allowedMediaTypes: ['image/png'], maxBytes: 65536, maxPixels: 4096 })
      return value
    }
    await policy('WAITING', 0, 'UNSUPPORTED_PHASE', null)
    expect(ok(await api<PageData<Evidence>>(driverA, `${pathA}/evidence`)).totalElements).toBe(0)
    rejected(await upload(driverA, pathA, red, 0), 409, 'E1802', 'INVALID_STATE')
    rejected(await api(driverA, `${pathA}/progress`, 'POST', { expectedVersion: 0, targetPhase: 'IN_PROGRESS', evidenceIds: [] }), 409, 'E1802', 'INVALID_STATE')
    const departKey = randomUUID(), departed = ok(await api<ProgressResult>(driverA, `${pathA}/depart`, 'POST', { expectedVersion: 0 }, { key: departKey }))
    exact(departed, ['assignmentId', 'taskId', 'execution']); exact(departed.execution, ['phase', 'version', 'departedAt']); expect(departed.assignmentId).toBe(a.assignment.id); expect(departed.taskId).toBe(a.taskId); expect(departed.execution.phase).toBe('DEPARTED'); expect(typeof departed.execution.departedAt).toBe('string'); const departedAt = departed.execution.departedAt; expect(departed.execution.version).toBe(1)
    ok(await api(driverB, `${pathB}/depart`, 'POST', { expectedVersion: 0 }))
    await policy('DEPARTED', 1, null, 'IN_PROGRESS')
    for (const suffix of ['/progress-policy?driverId=forbidden', '/progress?phase=x', '/evidence?x=1', '/evidence?page=0&page=1', '/evidence?size=101']) rejected(await api(driverA, `${pathA}${suffix}`, suffix.startsWith('/progress?') ? 'POST' : 'GET', suffix.startsWith('/progress?') ? { expectedVersion: 1, targetPhase: 'IN_PROGRESS', evidenceIds: [] } : undefined), 400, 'E0400')
    for (const command of [{ expectedVersion: 1, targetPhase: 'COMPLETED', evidenceIds: [] }, { expectedVersion: 1, targetPhase: 'IN_PROGRESS', evidenceIds: [], actorId: fixture.driverA.id }, { expectedVersion: 1.2, targetPhase: 'IN_PROGRESS', evidenceIds: [] }, { expectedVersion: 1, targetPhase: 'IN_PROGRESS', evidenceIds: Array.from({ length: 33 }, () => randomUUID()) }]) rejected(await api(driverA, `${pathA}/progress`, 'POST', command), 400, 'E0400')
    rejected(await api(driverA, `${pathA}/progress`, 'POST', undefined, { raw: '{"expectedVersion":1,"expectedVersion":1,"targetPhase":"IN_PROGRESS","evidenceIds":[]}' }), 400, 'E0400')
    rejected(await api(driverA, `${pathA}/progress`, 'POST', { expectedVersion: 1, targetPhase: 'IN_PROGRESS', evidenceIds: [] }), 409, 'E1807', 'EVIDENCE_REQUIRED')
    rejected(await api(driverA, `${pathA}/progress`, 'POST', { expectedVersion: 1, targetPhase: 'AWAITING_COMPLETION', evidenceIds: [] }), 409, 'E1802', 'INVALID_STATE')
    rejected(await api(driverA, `${pathA}/progress`, 'POST', { expectedVersion: 1, targetPhase: 'IN_PROGRESS', evidenceIds: [randomUUID()] }), 409, 'E1808', 'EVIDENCE_INVALID')
    for (const version of [null, '01', '1.0', '-1', '1,1', '9007199254740992']) rejected(await api(driverA, `${pathA}/evidence`, 'POST', undefined, { raw: [...red], media: 'image/png', version }), 400, 'E0400')
    for (const media of ['application/json', 'image/jpeg', 'image/png; filename=synthetic.png']) rejected(await api(driverA, `${pathA}/evidence`, 'POST', undefined, { raw: [...red], media, version: '1' }), 415, 'E0415')
    rejected(await api(driverA, `${pathA}/evidence`, 'POST', undefined, { raw: [...red], media: 'image/png', version: '1', encoding: 'gzip' }), 415, 'E0415')
    const corrupt = Buffer.from(red); corrupt[29] ^= 1
    for (const bytes of [Buffer.alloc(0), red.subarray(0, 8), red.subarray(0, red.length - 4), corrupt, Buffer.from('not a PNG'), png(65, 65), png(2, 2, 97, 65536)]) rejected(await upload(driverA, pathA, bytes, 1), 400, 'E0400')
    rejected(await api(driverA, `${pathA}/evidence`, 'POST', undefined, { length: 1048577, media: 'image/png', version: '1' }), 413, 'E0413')
    await current(driverA, pathA, 'DEPARTED', 1); expect(ok(await api<PageData<Evidence>>(driverA, `${pathA}/evidence`)).totalElements).toBe(0)

    const uploadKey = randomUUID(), uploadLoss = await loseCommit(driverA, `${pathA}/evidence`, 'upload', a, 'DEPARTED', 1, collectors.get(info.testId)!)
    let first: Evidence
    try {
      expect(await upload(driverA, pathA, red, 1, uploadKey).then(() => false, () => true)).toBe(true)
      const committed = await uploadLoss.committed
      rejected(await api(driverA, `${pathA}/evidence`, 'POST', undefined, { raw: [...red], version: '1', media: 'image/png', key: uploadKey, csrf: 'invalid' }), 403, 'E0403', 'CSRF_INVALID')
      // The real invalid-CSRF relay is followed only by this explicit original retry.
      first = photo(await upload(driverA, pathA, red, 1, uploadKey), a, red, 'DEPARTED', 1, true)
      expect(isDeepStrictEqual(first, committed)).toBe(true); expect(uploadLoss.matching()).toBe(true); expect(uploadLoss.attempts()).toBe(3)
    } finally { await uploadLoss.dispose() }
    const second = photo(await upload(driverA, pathA, blue, 1), a, blue, 'DEPARTED', 1), third = photo(await upload(driverA, pathA, red, 1), a, red, 'DEPARTED', 1)
    expect(first!.id === third.id).toBe(false); await current(driverA, pathA, 'DEPARTED', 1)
    const listed = ok(await api<PageData<Evidence>>(driverA, `${pathA}/evidence?size=2`)), next = ok(await api<PageData<Evidence>>(driverA, `${pathA}/evidence?page=1&size=2`))
    exact(listed, ['content', 'page', 'size', 'totalElements', 'totalPages']); expect(listed.totalElements).toBe(3); expect(listed.totalPages).toBe(2); expect(listed.content).toHaveLength(2); expect(next.content).toHaveLength(1)
    expect([...listed.content, ...next.content].map((value) => value.id).sort()).toEqual([first!.id, second.id, third.id].sort())
    const content = `${pathA}/evidence/${first!.id}/content`; await privatePng(driverA, content, red); await privatePng(driverA, `${pathA}/evidence/${second.id}/content`, blue)
    await privatePng(driverA, content, red, { 'If-None-Match': 'synthetic-conditional', 'If-Modified-Since': 'Wed, 01 Jan 2048 00:00:00 GMT' })
    rejected(await api(driverA, content, 'GET', undefined, { headers: { Range: 'bytes=0-0' } }), 400, 'E0400')
    const head = await driverA.evaluate(async (path) => { const response = await fetch(`/api/v1${path}`, { method: 'HEAD', credentials: 'same-origin' }); return { status: response.status, replayed: response.headers.get('Idempotency-Replayed') } }, content)
    expect(head.status).toBe(405); expect(head.replayed).toBeNull()
    rejected(await api(driverA, `${content}?download=1`), 400, 'E0400')
    rejected(await api(driverB, content), 404, 'E0404')
    const foreign = photo(await upload(driverB, pathB, blue, 1), b, blue, 'DEPARTED', 1)
    rejected(await api(driverA, `${pathA}/evidence/${foreign.id}/content`), 404, 'E0404')
    rejected(await api(driverA, `${pathA}/progress`, 'POST', { expectedVersion: 1, targetPhase: 'IN_PROGRESS', evidenceIds: [foreign.id] }), 409, 'E1808', 'EVIDENCE_INVALID')
    rejected(await api(driverA, `${pathA}/progress`, 'POST', { expectedVersion: 1, targetPhase: 'IN_PROGRESS', evidenceIds: [first!.id, first!.id.toUpperCase()] }), 400, 'E0400')
    rejected(await upload(driverA, pathA, blue, 1, uploadKey), 409, 'E1804', 'IDEMPOTENCY_KEY_REUSED')

    const progressKey = randomUUID(), command = { expectedVersion: 1, targetPhase: 'IN_PROGRESS', evidenceIds: [second.id.toUpperCase(), first!.id] }, progressLoss = await loseCommit(driverA, `${pathA}/progress`, 'progress', a, 'IN_PROGRESS', 2, collectors.get(info.testId)!)
    let started: ProgressResult
    try {
      expect(await api(driverA, `${pathA}/progress`, 'POST', command, { key: progressKey }).then(() => false, () => true)).toBe(true)
      const committed = await progressLoss.committed
      rejected(await api(driverA, `${pathA}/progress`, 'POST', command, { key: progressKey }), 403, 'E0403', 'CSRF_INVALID')
      started = progress(await api<ProgressResult>(driverA, `${pathA}/progress`, 'POST', command, { key: progressKey }), a, 'IN_PROGRESS', 2, departedAt, true)
      expect(isDeepStrictEqual(started, committed)).toBe(true); expect(progressLoss.matching()).toBe(true); expect(progressLoss.attempts()).toBe(3)
    } finally { await progressLoss.dispose() }
    await current(driverA, pathA, 'IN_PROGRESS', 2); await policy('IN_PROGRESS', 2, null, 'AWAITING_COMPLETION')
    progress(await api<ProgressResult>(driverA, `/tms/my-tasks/${a.assignment.id.toUpperCase()}/progress`, 'POST', undefined, { key: progressKey, raw: '{"expectedVersion":1.0,"targetPhase":"IN_PROGRESS","evidenceIds":' + JSON.stringify([first!.id.toUpperCase(), second.id]) + '}' }), a, 'IN_PROGRESS', 2, departedAt, true)
    photo(await upload(driverA, pathA, red, 1, uploadKey), a, red, 'DEPARTED', 1, true)
    rejected(await api(driverA, `${pathA}/progress`, 'POST', { ...command, evidenceIds: [first!.id] }, { key: progressKey }), 409, 'E1804', 'IDEMPOTENCY_KEY_REUSED')
    rejected(await api(driverA, `${pathA}/progress`, 'POST', command), 409, 'E1801', 'VERSION_CONFLICT')
    rejected(await api(driverA, `${pathA}/progress`, 'POST', { expectedVersion: 2, targetPhase: 'IN_PROGRESS', evidenceIds: [first!.id] }), 409, 'E1802', 'INVALID_STATE')
    rejected(await api(driverA, `${pathA}/progress`, 'POST', { expectedVersion: 2, targetPhase: 'AWAITING_COMPLETION', evidenceIds: [third.id] }), 409, 'E1808', 'EVIDENCE_INVALID')
    const fourth = photo(await upload(driverA, pathA, red, 2), a, red, 'IN_PROGRESS', 2)
    const finish = { expectedVersion: 2, targetPhase: 'AWAITING_COMPLETION', evidenceIds: [fourth.id] }, finishKeys = [randomUUID(), randomUUID()]
    const [advanceA, advanceB, racingUpload] = await Promise.all([api<ProgressResult>(driverA, `${pathA}/progress`, 'POST', finish, { key: finishKeys[0] }), api<ProgressResult>(driverA, `${pathA}/progress`, 'POST', finish, { key: finishKeys[1] }), upload(driverA, pathA, blue, 2)])
    expect([advanceA, advanceB].filter((reply) => reply.status === 200)).toHaveLength(1)
    const winner = advanceA.status === 200 ? 0 : 1, winnerReply = winner ? advanceB : advanceA, loserReply = winner ? advanceA : advanceB
    progress(winnerReply, a, 'AWAITING_COMPLETION', 3, departedAt); rejected(loserReply, 409, 'E1801', 'VERSION_CONFLICT')
    if (racingUpload.status === 200) photo(racingUpload, a, blue, 'IN_PROGRESS', 2); else rejected(racingUpload, 409, 'E1801', 'VERSION_CONFLICT')
    progress(await api<ProgressResult>(driverA, `${pathA}/progress`, 'POST', finish, { key: finishKeys[winner] }), a, 'AWAITING_COMPLETION', 3, departedAt, true)
    await current(driverA, pathA, 'AWAITING_COMPLETION', 3); await policy('AWAITING_COMPLETION', 3, 'UNSUPPORTED_PHASE', null)
    // Unsupported next transition does not revoke authorized private photo reads.
    await privatePng(driverA, content, red); photo(await api<Evidence>(driverA, `/tms/my-tasks/${a.assignment.id.toUpperCase()}/evidence`, 'POST', undefined, { raw: [...red], version: '1', media: 'IMAGE/PNG', key: uploadKey }), a, red, 'DEPARTED', 1, true)
    progress(await api<ProgressResult>(driverA, `${pathA}/progress`, 'POST', command, { key: progressKey }), a, 'IN_PROGRESS', 2, departedAt, true)
    const oldDeparture = await api<ProgressResult>(driverA, `${pathA}/depart`, 'POST', { expectedVersion: 0 }, { key: departKey }); ok(oldDeparture); expect(oldDeparture.replayed).toBe('true'); expect(oldDeparture.data).toEqual(departed)
    rejected(await upload(driverA, pathA, red, 3), 409, 'E1802', 'INVALID_STATE')
    const dispatchAfter = ok(await api<DispatchDetail>(page, `/tms/tasks/${a.taskId}/dispatch`)); expect(isDeepStrictEqual(dispatchAfter, before)).toBe(true); expect(dispatchAfter.current!.reservations.every((value) => value.releasedAt === null)).toBe(true)
    const orderAfter = await api<Order>(page, `/orders/${a.order.id}`); expect(orderAfter.code).toBe('OK'); expect(isDeepStrictEqual(orderAfter.data, a.order)).toBe(true)
    expect(ok(await api<Driver>(page, `/fms/drivers/${a.driver.id}`)).version).toBe(a.driver.version); expect(ok(await api<Vehicle>(page, `/fms/vehicles/${a.main.id}`)).version).toBe(a.main.version)
    rejected(await api(page, `/tms/tasks/${a.dependentId}/dispatch`, 'POST', { expectedGeneration: 0, driverId: b.driver.id, mainVehicleId: b.main.id, trailerId: null }), 409, 'E1604', 'PREREQUISITE_NOT_READY')
    rejected(await api(dispatcher, `/tms/dispatches/${a.assignment.id}/revoke`, 'POST', { expectedGeneration: 1, reason: '合成進度不可釋放資源' }), 409, 'E1603', 'INVALID_STATE')
    const originalList = ok(await api<PageData<Body>>(driverA, '/tms/my-tasks')); expect(originalList.content.map((item) => item.assignmentId)).toContain(a.assignment.id); for (const item of originalList.content) exact(item, originalFields)

    const unboundA = ok(await api<Driver>(page, `/fms/drivers/${a.driver.id}`, 'PATCH', { version: a.driver.version, accountId: null }))
    for (const route of [`${pathA}/progress-policy`, `${pathA}/evidence`, content]) rejected(await api(driverA, route), 404, 'E0404')
    rejected(await upload(driverA, pathA, red, 1, uploadKey), 404, 'E0404'); rejected(await api(driverA, `${pathA}/progress`, 'POST', command, { key: progressKey }), 404, 'E0404')
    ok(await api(page, `/fms/drivers/${b.driver.id}`, 'PATCH', { version: b.driver.version, accountId: null }))
    ok(await api(page, `/fms/drivers/${a.driver.id}`, 'PATCH', { version: unboundA.version, accountId: fixture.driverB.id }))
    // Uploader actor is immutable audit, not an extra current-reader identity rule.
    const rebound = ok(await api<PageData<Evidence>>(driverB, `${pathA}/evidence`)); expect(rebound.content.some((item) => item.id === first!.id)).toBe(true)
    await privatePng(driverB, content, red)
    rejected(await upload(driverB, pathA, red, 1, uploadKey), 409, 'E1801', 'VERSION_CONFLICT')
    const actors = ok(await api<Account[]>(page, '/upms/accounts')), actorB = actors.find((value) => value.id === fixture.driverB.id)!
    ok(await api(page, `/upms/accounts/${actorB.id}`, 'PATCH', { version: actorB.version, displayName: actorB.displayName, role: actorB.role, enabled: false }))
    rejected(await api(driverB, content), 401, 'E0401'); rejected(await api(driverB, `${pathA}/evidence`), 401, 'E0401')
    rejected(await api(driverB, `${pathA}/progress`, 'POST', finish, { key: finishKeys[winner] }), 401, 'E0401')
    ok(await api(driverA, '/auth/logout', 'POST'))
    rejected(await api(driverA, content), 401, 'E0401')
  } finally { await Promise.all(contexts.map((context) => context.close())) }
})
