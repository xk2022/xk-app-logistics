import { createHash, randomUUID } from 'node:crypto'
import { readFileSync, writeFileSync } from 'node:fs'
import { request as httpsRequest } from 'node:https'
import { crc32, deflateSync } from 'node:zlib'
import { isDeepStrictEqual } from 'node:util'
import { expect, test, type Browser, type BrowserContext, type Locator, type Page, type Request, type Response, type Route, type TestInfo } from '@playwright/test'
import { exceptionFixtureFor, signIn, type Credential } from './fixtures'

// Explicit, safe screenshots are taken only after closing private PNG previews.
// Automatic failure screenshots could otherwise persist a protected original.
test.use({ screenshot: 'off' })
test.describe.configure({ retries: 0 })
type Body = Record<string, unknown>
interface Reply<T> { status: number; code: string; data: T; payload: Body; replayed: string | null; cache: string | null }
interface PageData<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }
interface Execution { phase: string; version: number; departedAt: string | null }
interface Driver { id: string; driverCode: string; version: number }
interface Vehicle { id: string; version: number }
interface Account { id: string; role: string; displayName: string; enabled: boolean; version: number }
interface Order extends Body { id: string; version: number; tasks: { id: string; clientRef: string }[] }
interface Assignment extends Body { id: string; taskId: string; reservations: { releasedAt: string | null }[] }
interface Command { assignment: Assignment; generation: number; taskId: string }
interface Scenario { order: Order; driver: Driver; main: Vehicle; assignment: Assignment; taskId: string; dependentId: string; execution: Execution }
interface Evidence { id: string; assignmentId: string; taskId: string; phase: string; executionVersion: number; mediaType: string; byteSize: number; sha256: string; uploadedAt: string; status: string }
interface ActiveRequest { id: string; reason: string; requestedAt: string; uploadedCount: number; evidenceIds: string[] }
interface ExceptionCase extends Body { id: string; assignmentId: string; taskId: string; taskNo: string; reportedPhase: string; reportedExecutionVersion: number; description: string; status: 'OPEN' | 'NEEDS_SUPPLEMENT' | 'RESOLVED'; version: number; blocking: boolean; reportedAt: string; resolvedAt: string | null; resolutionNote: string | null; activeRequest: ActiveRequest | null }
interface ExceptionDetail { exception: ExceptionCase; execution: Execution; actions: { mode: string; requestSupplement: { allowed: boolean; blocker: string | null }; resolve: { allowed: boolean; blocker: string | null }; uploadSupplement: { allowed: boolean; blocker: string | null }; submitSupplement: { allowed: boolean; blocker: string | null }; requirements: unknown } }
interface Uploaded { exceptionId: string; assignmentId: string; taskId: string; version: number; requestId: string; evidence: Evidence }
interface LinkedPhoto { requestId: string; submitted: boolean; evidence: Evidence }
interface Captured { schema: string; value: unknown }
type ExceptionSchema = 'ExceptionSummaryPageEnvelope' | 'ExceptionRecordEnvelope' | 'ExceptionDetailEnvelope' | 'DriverExceptionHistoryPageEnvelope' | 'ManagerExceptionHistoryPageEnvelope' | 'SupplementUploadEnvelope' | 'SupplementEvidencePageEnvelope' | 'ErrorEnvelope'
const hash = (bytes: Buffer) => createHash('sha256').update(bytes).digest('hex')
const recordKeys = ['id', 'assignmentId', 'taskId', 'taskNo', 'reportedPhase', 'reportedExecutionVersion', 'description', 'status', 'version', 'blocking', 'reportedAt', 'resolvedAt', 'resolutionNote', 'activeRequest']

function png(width = 2, height = 2, color = 97, padding = 0): Buffer {
  const chunk = (name: string, data: Buffer) => { const type = Buffer.from(name), length = Buffer.alloc(4), checksum = Buffer.alloc(4); length.writeUInt32BE(data.length); checksum.writeUInt32BE(crc32(Buffer.concat([type, data]))); return Buffer.concat([length, type, data, checksum]) }
  const header = Buffer.alloc(13); header.writeUInt32BE(width, 0); header.writeUInt32BE(height, 4); header[8] = 8; header[9] = 6
  const scanlines = Buffer.alloc((width * 4 + 1) * height)
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) { const offset = y * (width * 4 + 1) + 1 + x * 4; scanlines[offset] = color; scanlines[offset + 1] = 137; scanlines[offset + 2] = 211; scanlines[offset + 3] = 255 }
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header), ...(padding ? [chunk('tEXt', Buffer.concat([Buffer.from('synthetic\0'), Buffer.alloc(padding, 65)]))] : []), chunk('IDAT', deflateSync(scanlines)), chunk('IEND', Buffer.alloc(0))])
}
class ResponseCollector {
  readonly exceptions: Captured[] = []; readonly progress: Captured[] = []; readonly execution: Captured[] = []; readonly tms: Captured[] = []; readonly orders: Captured[] = []; readonly acceptances: Captured[] = []; readonly vehicles: Captured[] = []; readonly drivers: Captured[] = []
  private readonly pages = new Set<Page>(); private readonly pending = new Set<Promise<void>>()
  private readonly capture = (response: Response) => {
    const path = new URL(response.url()).pathname, method = response.request().method()
    let schema: string | undefined, target: Captured[] | undefined
    const manager = /^\/api\/v1\/tms\/exceptions(?:\/|$)/.test(path)
    const self = /^\/api\/v1\/tms\/my-tasks\/[0-9a-f-]{36}\/exceptions(?:\/|$)/i.test(path)
    if (manager || self) {
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
  record(schema: ExceptionSchema, value: Body) { this.exceptions.push({ schema, value }) }
  async finish() { for (const page of this.pages) page.off('response', this.capture); await Promise.allSettled([...this.pending]) }
}
const collectors = new Map<string, ResponseCollector>()
test.beforeEach(async ({ page }, info) => { const collector = new ResponseCollector(); collector.watch(page); collectors.set(info.testId, collector) })
test.afterEach(async ({}, info) => {
  const collector = collectors.get(info.testId); if (!collector) return; await collector.finish()
  // Original public synthetic JSON only. No private PNG, request body, raw
  // headers, keys, cookies, credentials or trace archives enter saved evidence.
  for (const [name, values] of [['exception', collector.exceptions], ['progress-evidence', collector.progress], ['execution', collector.execution], ['tms', collector.tms], ['order', collector.orders], ['acceptance', collector.acceptances], ['vehicle', collector.vehicles], ['driver', collector.drivers]] as const) {
    if (values.length) writeFileSync(info.outputPath(`${name}-contract-responses.json`), JSON.stringify(values))
  }
  collectors.delete(info.testId)
})
interface Options { key?: string; raw?: number[]; executionVersion?: number; exceptionVersion?: number; media?: string; csrf?: 'invalid' | 'omit'; headers?: Record<string, string> }
async function api<T = Body>(page: Page, path: string, method = 'GET', body?: unknown, options: Options = {}): Promise<Reply<T>> {
  return page.evaluate(async ({ path, method, body, options }) => {
    const headers: Record<string, string> = { Accept: path.split('?')[0].endsWith('/content') ? 'image/png' : 'application/json', ...options.headers }
    if (method !== 'GET') {
      if (options.csrf !== 'omit') { const csrf = (await (await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' })).json()).data; if (csrf?.token) headers[csrf.headerName] = options.csrf === 'invalid' ? 'synthetic-invalid-csrf' : csrf.token }
      headers['Content-Type'] = options.media ?? 'application/json'; headers['Idempotency-Key'] = options.key ?? crypto.randomUUID()
      if (options.executionVersion !== undefined) headers['X-Execution-Version'] = String(options.executionVersion)
      if (options.exceptionVersion !== undefined) headers['X-Exception-Version'] = String(options.exceptionVersion)
    }
    const wire = options.raw ? new Uint8Array(options.raw) : body === undefined ? undefined : JSON.stringify(body)
    const response = await fetch(`/api/v1${path}`, { method, credentials: 'same-origin', headers, ...(wire === undefined ? {} : { body: wire }) })
    const payload = await response.json()
    return { status: response.status, code: payload.code, data: payload.data, payload, replayed: response.headers.get('Idempotency-Replayed'), cache: response.headers.get('Cache-Control') }
  }, { path, method, body, options })
}
function exact(value: object, keys: readonly string[]) { expect(Object.keys(value).sort()).toEqual([...keys].sort()) }
function ok<T>(reply: Reply<T>, status = 200, code = '0000'): T { expect(reply.status).toBe(status); expect(reply.code).toBe(code); exact(reply.payload, ['code', 'message', 'data', 'timestamp']); expect(typeof reply.payload.timestamp === 'string' && reply.payload.timestamp.endsWith('+08:00')).toBe(true); return reply.data }
function rejected(reply: Reply<unknown>, status: number, code: string, reason?: string) { const data = ok(reply, status, code); if (reason) expect((data as Body)?.reason).toBe(reason); if ([401, 404, 405, 413, 415, 500].includes(status)) expect(data).toBeNull(); expect(reply.replayed).toBeNull() }
function record(reply: Reply<ExceptionCase>, scenario: Scenario, status: ExceptionCase['status'], version: number, replayed = false): ExceptionCase { const value = ok(reply); exact(value, recordKeys); expect(value.assignmentId).toBe(scenario.assignment.id); expect(value.taskId).toBe(scenario.taskId); expect(value.status).toBe(status); expect(value.version).toBe(version); expect(value.blocking).toBe(status !== 'RESOLVED'); expect(reply.replayed).toBe(String(replayed)); expect(reply.cache).toBe('no-store, private'); return value }
async function contextFor(browser: Browser, info: TestInfo): Promise<BrowserContext> { const use = info.project.use; const context = await browser.newContext({ baseURL: use.baseURL, ignoreHTTPSErrors: use.ignoreHTTPSErrors, viewport: use.viewport, screen: use.screen, deviceScaleFactor: use.deviceScaleFactor, isMobile: use.isMobile, hasTouch: use.hasTouch, userAgent: use.userAgent }); context.on('page', (page) => collectors.get(info.testId)?.watch(page)); return context }
async function enter(page: Page, credential: Credential, temporary = false) { await page.goto('/'); expect(new URL(page.url()).protocol).toBe('https:'); await signIn(page, credential); await expect(page.getByRole(temporary ? 'heading' : 'button', { name: temporary ? '首次登入，請先修改密碼' : '登出', exact: true })).toBeVisible() }
const selfPath = (scenario: Scenario) => `/tms/my-tasks/${scenario.assignment.id}/exceptions`
const managerPath = (value: ExceptionCase) => `/tms/exceptions/${value.id}`
async function current(page: Page, value: ExceptionCase, self?: Scenario): Promise<ExceptionDetail> { const reply = await api<ExceptionDetail>(page, self ? `${selfPath(self)}/${value.id}` : managerPath(value)); const result = ok(reply); exact(result, ['exception', 'execution', 'actions']); exact(result.exception, recordKeys); expect(reply.cache).toBe('no-store, private'); return result }
async function supplement(page: Page, scenario: Scenario, value: ExceptionCase, bytes: Buffer): Promise<Uploaded> {
  const request = value.activeRequest!; expect(request).not.toBeNull()
  const reply = await api<Uploaded>(page, `${selfPath(scenario)}/${value.id}/supplement-requests/${request.id}/evidence`, 'POST', undefined, { raw: [...bytes], media: 'image/png', exceptionVersion: value.version, executionVersion: scenario.execution.version })
  const result = ok(reply); exact(result, ['exceptionId', 'assignmentId', 'taskId', 'version', 'requestId', 'evidence']); expect(result.version).toBe(value.version + 1); expect(result.requestId).toBe(request.id); expect(result.exceptionId).toBe(value.id); expect(result.evidence.sha256).toBe(hash(bytes)); expect(result.evidence.byteSize).toBe(bytes.length); expect(result.evidence.executionVersion).toBe(scenario.execution.version); expect(result.evidence.phase).toBe(scenario.execution.phase); expect(reply.replayed).toBe('false'); return result
}
async function submit(page: Page, scenario: Scenario, value: ExceptionCase, note: string, ids: string[]): Promise<ExceptionCase> { return record(await api<ExceptionCase>(page, `${selfPath(scenario)}/${value.id}/supplement-requests/${value.activeRequest!.id}/submit`, 'POST', { expectedVersion: value.version, note, evidenceIds: ids }), scenario, 'OPEN', value.version + 1) }
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
const safeForwardFailure = 'The controlled synthetic exception request could not be completed safely.'
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
async function loseManagerCommit(page: Page, value: ExceptionCase, kind: 'request' | 'resolve', reason: string, collector: ResponseCollector) {
  const origin = new URL(page.url()).origin, path = `/api/v1${managerPath(value)}/${kind === 'request' ? 'supplement-requests' : 'resolve'}`
  const cancellation = new AbortController(), active = new Set<Promise<void>>()
  let attempts = 0, matching = false, key: string | undefined, original: Buffer | null = null
  let resolve!: (value: ExceptionCase) => void, reject!: (error: Error) => void
  const committed = new Promise<ExceptionCase>((yes, no) => { resolve = yes; reject = no }); void committed.catch(() => undefined)
  const deadline = setTimeout(() => { cancellation.abort(); reject(new Error(safeForwardFailure)) }, 20_000)
  const matches = (url: URL) => url.origin === origin && url.pathname === path
  const handle = async (route: Route) => {
    try {
      const request = route.request(); if (request.method() !== 'POST') { await route.continue(); return }
      attempts++
      const incomingKey = (await request.allHeaders())['idempotency-key'], incoming = request.postDataBuffer()
      if (attempts === 1) {
        key = incomingKey; original = incoming
        if (!incoming || !key) throw new Error(safeForwardFailure)
        const body = JSON.parse(incoming.toString('utf8')) as Body
        if (Object.keys(body).sort().join() !== 'expectedVersion,reason' || body.expectedVersion !== value.version || body.reason !== reason) throw new Error(safeForwardFailure)
        const reply = await forward(request, origin, path, cancellation.signal), result = reply.payload.data as ExceptionCase
        if (reply.status !== 200 || reply.payload.code !== '0000' || reply.replayed !== 'false' || reply.cache !== 'no-store, private' || result.id !== value.id || result.version !== value.version + 1 || result.status !== (kind === 'request' ? 'NEEDS_SUPPLEMENT' : 'RESOLVED') || (kind === 'request' ? result.activeRequest?.reason : result.resolutionNote) !== reason) throw new Error(safeForwardFailure)
        collector.record('ExceptionRecordEnvelope', reply.payload)
        await route.abort('failed'); clearTimeout(deadline); resolve(result)
      } else {
        const same = Boolean(key && key === incomingKey && original && incoming && original.equals(incoming))
        matching = attempts === 2 ? same : matching && same
        if (!same) throw new Error(safeForwardFailure)
        if (attempts === 2) {
          const refusal = await forward(request, origin, path, cancellation.signal, true)
          if (refusal.status !== 403 || refusal.payload.code !== 'E0403' || refusal.cache !== 'no-store, private' || (refusal.payload.data as Body)?.reason !== 'CSRF_INVALID') throw new Error(safeForwardFailure)
          await route.fulfill({ status: refusal.status, headers: { 'Content-Type': 'application/json', 'Cache-Control': refusal.cache }, body: JSON.stringify(refusal.payload) })
        } else await route.continue()
      }
    } catch { clearTimeout(deadline); reject(new Error(safeForwardFailure)); try { await route.abort('failed') } catch { /* Page may already be closing. */ } }
  }
  const handler = (route: Route) => { const work = handle(route); active.add(work); void work.then(() => active.delete(work), () => active.delete(work)); return work }
  await page.route(matches, handler)
  return { committed, matching: () => matching, attempts: () => attempts, path, dispose: async () => { clearTimeout(deadline); cancellation.abort(); reject(new Error(safeForwardFailure)); await Promise.allSettled([...active]); await page.unroute(matches, handler) } }
}
async function raw(locator: Locator, expected: string) { await expect(locator).toBeVisible(); expect(await locator.textContent()).toBe(expected) }
async function navigation(page: Page) { await page.getByRole('navigation', { name: '主要導覽', exact: true }).getByRole('button', { name: '異常處理', exact: true }).click(); await expect(page.getByRole('heading', { name: '異常處理', exact: true })).toBeVisible() }
async function openException(page: Page, scenario: Scenario, value: ExceptionCase) {
  const back = page.getByRole('button', { name: '返回異常列表', exact: true }); if (await back.count()) await back.click()
  await navigation(page)
  await page.getByLabel('指派識別篩選', { exact: true }).fill(scenario.assignment.id)
  await page.getByLabel('異常狀態', { exact: true }).selectOption('')
  const response = page.waitForResponse((reply) => { const url = new URL(reply.url()); return url.pathname === '/api/v1/tms/exceptions' && url.searchParams.get('assignmentId') === scenario.assignment.id })
  await page.getByRole('button', { name: '查詢異常', exact: true }).click(); expect((await response).status()).toBe(200)
  await page.getByRole('button', { name: `開啟異常 ${value.taskNo}`, exact: true }).click()
  await raw(page.getByRole('region', { name: '目前異常資料', exact: true }).getByLabel('原始異常描述', { exact: true }), value.description)
}
async function refresh(page: Page, value: ExceptionCase, adopting = false) {
  const response = page.waitForResponse((reply) => reply.request().method() === 'GET' && new URL(reply.url()).pathname === `/api/v1${managerPath(value)}`)
  await page.getByRole('button', { name: '重新讀取異常', exact: true }).click(); expect((await response).status()).toBe(200)
  const adopt = page.getByRole('button', { name: '採用最新異常資料', exact: true })
  if (adopting) { await expect(adopt).toBeVisible(); await adopt.click() }
  const displayed = page.getByRole('region', { name: '目前異常資料', exact: true })
  await raw(displayed.getByLabel('原始異常描述', { exact: true }), value.description)
  await expect(displayed.locator('dl > div').filter({ has: page.getByText('異常版本', { exact: true }) }).locator('dd')).toHaveText(String(value.version))
}
async function review(page: Page, kind: 'request' | 'resolve', reason: string) {
  await page.getByRole('button', { name: kind === 'request' ? '要求補件' : '處置異常', exact: true }).click()
  const textarea = page.getByLabel(kind === 'request' ? '補件要求（司機可見）' : '處置原因（司機可見）', { exact: true })
  await textarea.fill(reason); await expect(textarea).toHaveValue(reason)
  await page.getByRole('button', { name: kind === 'request' ? '檢視補件要求確認' : '檢視異常處置確認', exact: true }).click()
  const confirmation = page.getByRole('group', { name: kind === 'request' ? '要求補件確認' : '異常處置確認', exact: true })
  await raw(confirmation.getByLabel('本次操作原因原文', { exact: true }), reason)
  await expect(confirmation.getByRole('button', { name: kind === 'request' ? '確認要求補件' : '確認處置異常', exact: true })).toBeFocused()
}
async function confirm(page: Page, value: ExceptionCase, kind: 'request' | 'resolve', status = 200): Promise<Reply<ExceptionCase>> {
  const path = `/api/v1${managerPath(value)}/${kind === 'request' ? 'supplement-requests' : 'resolve'}`
  const waiting = page.waitForResponse((reply) => reply.request().method() === 'POST' && new URL(reply.url()).pathname === path)
  await page.getByRole('button', { name: kind === 'request' ? '確認要求補件' : '確認處置異常', exact: true }).click()
  const response = await waiting; expect(response.status()).toBe(status); const payload = await response.json()
  return { status: response.status(), code: payload.code, data: payload.data, payload, replayed: await response.headerValue('Idempotency-Replayed'), cache: await response.headerValue('Cache-Control') }
}
async function retryUnknown(page: Page, fault: Awaited<ReturnType<typeof loseManagerCommit>>, reason: string, kind: 'request' | 'resolve') {
  await expect(page.getByRole('heading', { name: '異常操作結果尚未確認', exact: true })).toBeVisible()
  const textarea = page.getByLabel(kind === 'request' ? '補件要求（司機可見）' : '處置原因（司機可見）', { exact: true })
  await expect(textarea).toHaveValue(reason); await expect(textarea).toBeDisabled()
  const refusal = page.waitForResponse((reply) => new URL(reply.url()).pathname === fault.path && reply.status() === 403)
  await page.getByRole('button', { name: '以原請求重試異常操作', exact: true }).click(); await refusal
  await expect(page.getByRole('heading', { name: '異常操作結果尚未確認', exact: true })).toBeVisible(); await expect(textarea).toHaveValue(reason)
  const retry = page.waitForResponse((reply) => new URL(reply.url()).pathname === fault.path && reply.status() === 200)
  await page.getByRole('button', { name: '以原請求重試異常操作', exact: true }).click()
  const response = await retry; expect(await response.headerValue('Idempotency-Replayed')).toBe('true')
  expect(fault.attempts()).toBe(3); expect(fault.matching()).toBe(true)
  const receipt = page.getByRole('region', { name: '本次異常操作收據', exact: true }); await expect(receipt).toContainText('這不是目前異常狀態')
  return (await response.json()).data as ExceptionCase
}
async function screenshot(page: Page, info: TestInfo, name: string) {
  await expect(page.getByRole('region', { name: '補件照片預覽', exact: true })).toHaveCount(0)
  await expect(page.locator('img[src^="blob:"]')).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
  expect(await page.locator('input:not([type="hidden"]),textarea,select').evaluateAll((nodes) => nodes.filter((node) => node.getClientRects().length).every((node) => Boolean((node as HTMLInputElement).labels?.length || node.getAttribute('aria-label') || node.getAttribute('aria-labelledby'))))).toBe(true)
  await page.evaluate(() => { if (document.activeElement instanceof HTMLElement) document.activeElement.blur(); scrollTo({ top: 0, left: 0, behavior: 'instant' }) })
  await page.screenshot({ path: info.outputPath(name), fullPage: true })
}
async function setup(manager: Page, driverPage: Page, accountId: string, fixture: ReturnType<typeof exceptionFixtureFor>, sequence: number): Promise<Scenario> {
  const scope = fixture.scope
  const driver = ok(await api<Driver>(manager, '/fms/drivers', 'POST', { driverCode: `SYNTHETIC_TMS_${scope}_D${sequence}`, name: `合成異常司機 ${scope} ${sequence}`, accountId, employmentStatus: 'ACTIVE', dutyStatus: 'ON_DUTY' }), 201)
  const main = ok(await api<Vehicle>(manager, '/fms/vehicles', 'POST', { plateNo: `EXC-${scope[0]}-${randomUUID().slice(0, 10)}`, vehicleType: fixture.vehicleTypes.main.id, capacityTon: '1.00', administrativeStatus: 'AVAILABLE' }), 201)
  const customer = ok(await api<{ id: string }>(manager, '/crm/customers', 'POST', { customerCode: `EXC_${scope}_${randomUUID().slice(0, 8).toUpperCase()}`, name: `合成異常客戶 ${sequence}` }), 201)
  const draft = { type: 'LOCAL', customerId: customer.id, remark: '整單私有內容不應進異常摘要', items: [{ clientRef: 'exception-item', sequence: 1, itemKind: 'CARGO', packageQty: 7, grossWeight: '0.0000000000000000001', cbm: '0' }], tasks: Array.from({ length: 2 }, (_, index) => ({ clientRef: `exception-task-${index}`, itemRef: 'exception-item', taskKind: `SYNTHETIC_TMS_${scope}_EXCEPTION_${sequence}_${index}`, pickupName: '合成起點', pickupAddress: ' 合成 e\u0301 \u0000 起點 ', deliveryName: '合成終點', deliveryAddress: '合成終點地址（非真實）', siteContact: { name: '合成聯絡人', phone: null }, plannedStartAt: '1500-01-02T08:00:00.123456+23:59', plannedEndAt: '1500-01-02T10:00:00.123456+23:59', requiredTask: true, predecessorRefs: index ? ['exception-task-0'] : [] })) }
  const created = await api<Order>(manager, '/orders', 'POST', draft); expect(created.status).toBe(201); expect(created.code).toBe('OK')
  const accepted = await api<Order>(manager, `/orders/${created.data.id}/accept`, 'POST', { expectedVersion: created.data.version }); expect(accepted.status).toBe(200); expect(accepted.code).toBe('OK')
  const taskId = accepted.data.tasks.find((task) => task.clientRef === 'exception-task-0')!.id, dependentId = accepted.data.tasks.find((task) => task.clientRef === 'exception-task-1')!.id
  const assigned = ok(await api<Command>(manager, `/tms/tasks/${taskId}/dispatch`, 'POST', { expectedGeneration: 0, driverId: driver.id, mainVehicleId: main.id, trailerId: null }))
  const departure = ok(await api<{ execution: Execution }>(driverPage, `/tms/my-tasks/${assigned.assignment.id}/depart`, 'POST', { expectedVersion: 0 }))
  return { order: accepted.data, driver, main, assignment: assigned.assignment, taskId, dependentId, execution: departure.execution }
}
function originalUnchanged(value: ExceptionCase, original: ExceptionCase) {
  for (const field of ['id', 'assignmentId', 'taskId', 'taskNo', 'reportedPhase', 'reportedExecutionVersion', 'description', 'reportedAt'] as const) expect(value[field]).toBe(original[field])
}
async function viewPhoto(page: Page) {
  await page.getByRole('button', { name: '查看補件照片 1', exact: true }).click()
  const preview = page.getByRole('region', { name: '補件照片預覽', exact: true })
  await expect(preview.getByRole('img', { name: '異常補件照片', exact: true })).toBeVisible()
  expect(await preview.getByRole('img').evaluate((image) => (image as HTMLImageElement).naturalWidth)).toBe(2)
}

test('manager exception UI preserves raw reports, explicit supplements, historical receipts and private current authorization over TLS', async ({ page, browser }, info) => {
  test.setTimeout(480_000)
  const fixture = exceptionFixtureFor(info.project.name), collector = collectors.get(info.testId)!
  const contexts = await Promise.all(Array.from({ length: 5 }, () => contextFor(browser, info)))
  try {
    const [dispatcher, driverA, driverB, temporary, anonymous] = await Promise.all(contexts.map((context) => context.newPage()))
    await enter(page, fixture.admin); await enter(dispatcher, fixture.dispatcher); await enter(driverA, fixture.driverA); await enter(driverB, fixture.driverB); await enter(temporary, fixture.temporary, true); await anonymous.goto('/')
    await expect(driverA.getByRole('navigation', { name: '主要導覽', exact: true }).getByRole('button', { name: '異常處理', exact: true })).toHaveCount(0)
    await expect(driverA.getByRole('button', { name: '處理異常', exact: true })).toHaveCount(0)
    const red = png(), blue = png(2, 2, 203)
    expect(await driverA.evaluate(async (bytes) => { const bitmap = await createImageBitmap(new Blob([new Uint8Array(bytes)], { type: 'image/png' })); const result = [bitmap.width, bitmap.height]; bitmap.close(); return result }, [...red])).toEqual([2, 2])
    const a = await setup(page, driverA, fixture.driverA.id, fixture, 4001), b = await setup(page, driverB, fixture.driverB.id, fixture, 4002)
    const dispatchBefore = ok(await api<Body>(page, `/tms/tasks/${a.taskId}/dispatch`))
    const description = '  原始異常 e\u0301 \u0000 內容\n第二行保持原文  ', descriptionB = '  合成待完成階段 e\u0301 \u0000 異常  '
    const reportBody = { expectedExecutionVersion: a.execution.version, description }, reportKey = randomUUID()
    const original = record(await api<ExceptionCase>(driverA, selfPath(a), 'POST', reportBody, { key: reportKey }), a, 'OPEN', 0)
    expect(original.description).toBe(description); expect(original.reportedPhase).toBe('DEPARTED'); expect(original.reportedExecutionVersion).toBe(1)
    const summaries = ok(await api<PageData<Body>>(driverA, `${selfPath(a)}?size=1`)); expect(summaries.totalElements).toBe(1)
    exact(summaries.content[0], ['id', 'assignmentId', 'taskId', 'taskNo', 'reportedPhase', 'status', 'version', 'blocking', 'reportedAt'])
    for (const [actor, status, code] of [[anonymous, 401, 'E0401'], [driverA, 403, 'E0403'], [temporary, 403, 'E0403']] as const) rejected(await api(actor, '/tms/exceptions', 'GET'), status, code)
    rejected(await api(page, selfPath(a)), 403, 'E0403'); rejected(await api(dispatcher, `${selfPath(a)}/${original.id}`), 403, 'E0403')
    rejected(await api(driverB, `${selfPath(a)}/${original.id}`), 404, 'E0404'); rejected(await api(driverA, `${selfPath(a)}?driverId=${fixture.driverB.id}`), 400, 'E0400')
    rejected(await api(page, '/tms/exceptions?size=101'), 400, 'E0400')
    await page.getByRole('button', { name: '處理異常', exact: true }).click()
    await openException(page, a, original)
    await expect(page.getByRole('button', { name: '處置異常', exact: true })).toBeDisabled()
    await expect(page.getByRole('complementary', { name: '異常處置政策', exact: true })).toContainText('合成測試政策')
    await screenshot(page, info, 'exception-original-report.png')

    // A real second manager updates the review version after the first manager
    // typed its reason. Conflict never silently adopts that different command.
    const firstReason = '  本機補件 e\u0301 \u0000 理由\n保留空白  ', remoteReason = '  另一位管理者的原始補件理由  '
    await review(page, 'request', firstReason)
    let active = record(await api<ExceptionCase>(dispatcher, `${managerPath(original)}/supplement-requests`, 'POST', { expectedVersion: 0, reason: remoteReason }), a, 'NEEDS_SUPPLEMENT', 1)
    const conflict = await confirm(page, original, 'request', 409); rejected(conflict, 409, 'E1901', 'VERSION_CONFLICT'); expect((conflict.data as Body).actualVersion).toBe(1)
    await expect(page.getByRole('heading', { name: '異常版本或狀態已變更，本機內容已保留', exact: true })).toBeVisible()
    await expect(page.getByLabel('補件要求（司機可見）', { exact: true })).toHaveValue(firstReason)
    await refresh(page, active, true); await raw(page.getByRole('region', { name: '目前異常資料', exact: true }).getByLabel('補件要求原文', { exact: true }), remoteReason)
    const firstPhoto = await supplement(driverA, a, active, red); active = (await current(driverA, active, a)).exception
    expect(active.version).toBe(2); expect(active.status).toBe('NEEDS_SUPPLEMENT'); expect(active.activeRequest!.uploadedCount).toBe(1)
    // Both operations are legal here: a previously valid photo makes explicit
    // submission legal, while another upload still belongs to the active request.
    const uploadPath = `${selfPath(a)}/${active.id}/supplement-requests/${active.activeRequest!.id}/evidence`
    const submitPath = `${selfPath(a)}/${active.id}/supplement-requests/${active.activeRequest!.id}/submit`
    let unsubmittedId: string | undefined
    const [extraUpload, submitted] = await Promise.all([
      api<Uploaded>(driverA, uploadPath, 'POST', undefined, { raw: [...blue], media: 'image/png', exceptionVersion: 2, executionVersion: 1 }),
      api<ExceptionCase>(driverA, submitPath, 'POST', { expectedVersion: 2, note: '  原始補件 e\u0301 \u0000 說明  ', evidenceIds: [firstPhoto.evidence.id] }),
    ])
    expect([extraUpload, submitted].filter((reply) => reply.status === 200)).toHaveLength(1)
    if (submitted.status === 200) { active = record(submitted, a, 'OPEN', 3); rejected(extraUpload, 409, 'E1901', 'VERSION_CONFLICT') }
    else { unsubmittedId = ok(extraUpload).evidence.id; rejected(submitted, 409, 'E1901', 'VERSION_CONFLICT'); active = (await current(driverA, active, a)).exception; active = await submit(driverA, a, active, '  原始補件 e\u0301 \u0000 說明  ', [firstPhoto.evidence.id]) }
    originalUnchanged(active, original)
    await refresh(page, active)

    // The server commits the manager request before its browser response is
    // dropped. The driver then completes it before the same frozen retry.
    await review(page, 'request', firstReason)
    const requestFault = await loseManagerCommit(page, active, 'request', firstReason, collector)
    let requested: ExceptionCase, afterSupplement: ExceptionCase
    try {
      await page.getByRole('button', { name: '確認要求補件', exact: true }).click()
      requested = await requestFault.committed
      await expect(page.getByRole('heading', { name: '異常操作結果尚未確認', exact: true })).toBeVisible()
      const secondPhoto = await supplement(driverA, a, requested, blue)
      const waiting = (await current(driverA, requested, a)).exception
      afterSupplement = await submit(driverA, a, waiting, '  新要求的明確補件 e\u0301 \u0000 內容  ', [secondPhoto.evidence.id])
      const historical = await retryUnknown(page, requestFault, firstReason, 'request')
      expect(isDeepStrictEqual(historical, requested)).toBe(true); expect(historical.status).toBe('NEEDS_SUPPLEMENT'); expect(afterSupplement.status).toBe('OPEN'); expect(afterSupplement.version).toBe(requested.version + 2)
      const receipt = page.getByRole('region', { name: '本次異常操作收據', exact: true })
      await raw(receipt.getByLabel('補件要求原文', { exact: true }), firstReason)
      await expect(page.getByRole('heading', { name: '目前異常尚未重新讀取', exact: true })).toBeVisible()
      await screenshot(page, info, 'exception-historical-request-receipt.png')
    } finally { await requestFault.dispose() }
    active = afterSupplement!
    await refresh(page, active)
    await expect(page.getByRole('button', { name: '處置異常', exact: true })).toBeEnabled()
    const resolution = '  處置 e\u0301 \u0000 結果\n原始首尾空白  '
    await review(page, 'resolve', resolution)
    const resolveFault = await loseManagerCommit(page, active, 'resolve', resolution, collector)
    let resolved: ExceptionCase
    try {
      await page.getByRole('button', { name: '確認處置異常', exact: true }).click()
      resolved = await resolveFault.committed
      const receipt = await retryUnknown(page, resolveFault, resolution, 'resolve')
      expect(isDeepStrictEqual(receipt, resolved)).toBe(true)
    } finally { await resolveFault.dispose() }
    originalUnchanged(resolved!, original); expect(resolved!.resolutionNote).toBe(resolution); expect(resolved!.blocking).toBe(false)
    await refresh(page, resolved!)
    const displayed = page.getByRole('region', { name: '目前異常資料', exact: true })
    await raw(displayed.getByLabel('原始異常描述', { exact: true }), description); await raw(displayed.getByLabel('處置原因原文', { exact: true }), resolution)
    await expect(page.getByRole('button', { name: '要求補件', exact: true })).toBeDisabled(); await expect(page.getByRole('button', { name: '處置異常', exact: true })).toBeDisabled()
    await screenshot(page, info, 'exception-resolved-manager.png')
    const driverHistory = ok(await api<PageData<Body>>(driverA, `${selfPath(a)}/${original.id}/history?size=100`))
    expect(driverHistory.totalElements).toBe(resolved!.version + 1)
    for (const event of driverHistory.content) { exact(event, ['id', 'exceptionId', 'type', 'fromStatus', 'toStatus', 'fromVersion', 'toVersion', 'occurredAt', 'note', 'requestId', 'evidenceIds']); expect(Object.hasOwn(event, 'actor')).toBe(false) }
    expect(driverHistory.content.find((event) => event.type === 'REPORTED')!.note).toBe(description)
    const managerHistory = ok(await api<PageData<Body>>(page, `${managerPath(original)}/history?size=2`))
    expect(managerHistory.totalElements).toBe(driverHistory.totalElements); expect(managerHistory.content).toHaveLength(2)
    for (const event of managerHistory.content) { exact(event, ['id', 'exceptionId', 'type', 'fromStatus', 'toStatus', 'fromVersion', 'toVersion', 'occurredAt', 'note', 'requestId', 'evidenceIds', 'actor']); exact(event.actor as object, ['role', 'displayName']) }
    const historyNav = page.getByRole('navigation', { name: '異常歷程分頁', exact: true })
    await expect(historyNav.getByRole('button', { name: '下一頁異常歷程', exact: true })).toBeEnabled()
    await historyNav.getByRole('button', { name: '下一頁異常歷程', exact: true }).click()
    await expect(historyNav).toContainText('第 2 /')
    await historyNav.getByRole('button', { name: '上一頁異常歷程', exact: true }).click()
    await expect(historyNav).toContainText('第 1 /')
    const photoPage = ok(await api<PageData<LinkedPhoto>>(page, `${managerPath(original)}/evidence?size=100`))
    expect(photoPage.content.some((value) => value.evidence.id === firstPhoto.evidence.id && value.submitted)).toBe(true)
    if (unsubmittedId) expect(photoPage.content.find((value) => value.evidence.id === unsubmittedId)!.submitted).toBe(false)
    const contentA = `${managerPath(original)}/evidence/${firstPhoto.evidence.id}/content`
    await privatePng(page, contentA, red)
    await privatePng(page, contentA, red, { 'If-None-Match': 'synthetic-exception-conditional', 'If-Modified-Since': 'Wed, 01 Jan 2048 00:00:00 GMT' })
    rejected(await api(page, contentA, 'GET', undefined, { headers: { Range: 'bytes=0-0' } }), 400, 'E0400')
    const head = await page.evaluate(async (path) => { const reply = await fetch(`/api/v1${path}`, { credentials: 'same-origin', method: 'HEAD' }); return { status: reply.status, replayed: reply.headers.get('Idempotency-Replayed') } }, contentA)
    expect(head.status).toBe(405); expect(head.replayed).toBeNull()
    rejected(await api(driverA, contentA), 403, 'E0403'); rejected(await api(driverB, contentA), 403, 'E0403')
    await viewPhoto(page)
    const originalUrl = await page.getByRole('region', { name: '補件照片預覽', exact: true }).getByRole('img').getAttribute('src')
    await page.getByRole('button', { name: '關閉照片預覽', exact: true }).click()
    await expect(page.locator('img[src^="blob:"]')).toHaveCount(0)
    expect(await page.evaluate(async (url) => { try { await fetch(url!); return false } catch { return true } }, originalUrl)).toBe(true)
    const historicReport = record(await api<ExceptionCase>(driverA, selfPath(a), 'POST', reportBody, { key: reportKey }), a, 'OPEN', 0, true)
    expect(isDeepStrictEqual(historicReport, original)).toBe(true); expect((await current(page, original)).exception.version).toBe(resolved!.version)
    const dispatchAfter = ok(await api<Body>(page, `/tms/tasks/${a.taskId}/dispatch`)); expect(isDeepStrictEqual(dispatchAfter, dispatchBefore)).toBe(true)
    expect((dispatchAfter.current as Assignment).reservations.every((reservation) => reservation.releasedAt === null)).toBe(true)
    const originalOrder = await api<Order>(page, `/orders/${a.order.id}`); expect(originalOrder.code).toBe('OK'); expect(isDeepStrictEqual(originalOrder.data, a.order)).toBe(true)
    expect(ok(await api<{ execution: Execution }>(driverA, `/tms/my-tasks/${a.assignment.id}`)).execution).toEqual(a.execution)
    rejected(await api(page, `/tms/tasks/${a.dependentId}/dispatch`, 'POST', { expectedGeneration: 0, driverId: b.driver.id, mainVehicleId: b.main.id, trailerId: null }), 409, 'E1604', 'PREREQUISITE_NOT_READY')

    // Advance the second independent assignment using the unchanged M402 API.
    // AWAITING supplement evidence is allowed only by the NEW exception route.
    const oldPathB = `/tms/my-tasks/${b.assignment.id}`
    const oldPhoto1 = ok(await api<Evidence>(driverB, `${oldPathB}/evidence`, 'POST', undefined, { raw: [...red], media: 'image/png', executionVersion: 1 }))
    b.execution = ok(await api<{ execution: Execution }>(driverB, `${oldPathB}/progress`, 'POST', { expectedVersion: 1, targetPhase: 'IN_PROGRESS', evidenceIds: [oldPhoto1.id] })).execution
    const oldPhoto2 = ok(await api<Evidence>(driverB, `${oldPathB}/evidence`, 'POST', undefined, { raw: [...blue], media: 'image/png', executionVersion: 2 }))
    b.execution = ok(await api<{ execution: Execution }>(driverB, `${oldPathB}/progress`, 'POST', { expectedVersion: 2, targetPhase: 'AWAITING_COMPLETION', evidenceIds: [oldPhoto2.id] })).execution
    expect(b.execution.phase).toBe('AWAITING_COMPLETION'); expect(b.execution.version).toBe(3)
    rejected(await api(driverB, `${oldPathB}/evidence`, 'POST', undefined, { raw: [...red], media: 'image/png', executionVersion: 3 }), 409, 'E1802', 'INVALID_STATE')
    const originalB = record(await api<ExceptionCase>(driverB, selfPath(b), 'POST', { expectedExecutionVersion: 3, description: descriptionB }), b, 'OPEN', 0)
    await openException(dispatcher, b, originalB)
    const reasonB = '  調度員補件 e\u0301 \u0000 指示  '
    await review(dispatcher, 'request', reasonB)
    let activeB = record(await confirm(dispatcher, originalB, 'request'), b, 'NEEDS_SUPPLEMENT', 1)
    const photoB = await supplement(driverB, b, activeB, red)
    expect(photoB.evidence.phase).toBe('AWAITING_COMPLETION'); expect(photoB.evidence.executionVersion).toBe(3)
    activeB = (await current(driverB, activeB, b)).exception
    activeB = await submit(driverB, b, activeB, '  待完成階段的明確補件 e\u0301 \u0000 原文  ', [photoB.evidence.id])
    expect(activeB.version).toBe(3); originalUnchanged(activeB, originalB)
    await refresh(dispatcher, activeB)
    await expect(dispatcher.getByRole('button', { name: '要求補件', exact: true })).toBeEnabled(); await expect(dispatcher.getByRole('button', { name: '處置異常', exact: true })).toBeEnabled()
    await screenshot(dispatcher, info, 'exception-dispatcher-ready.png')
    // These two commands are both legal OPEN commands after fulfilled evidence.
    const [requestedB, resolvedB] = await Promise.all([
      api<ExceptionCase>(page, `${managerPath(activeB)}/supplement-requests`, 'POST', { expectedVersion: 3, reason: '合成合法競爭新要求' }),
      api<ExceptionCase>(dispatcher, `${managerPath(activeB)}/resolve`, 'POST', { expectedVersion: 3, reason: '合成合法競爭處置' }),
    ])
    expect([requestedB, resolvedB].filter((reply) => reply.status === 200)).toHaveLength(1)
    if (requestedB.status === 200) { record(requestedB, b, 'NEEDS_SUPPLEMENT', 4); rejected(resolvedB, 409, 'E1901', 'VERSION_CONFLICT') }
    else { record(resolvedB, b, 'RESOLVED', 4); rejected(requestedB, 409, 'E1901', 'VERSION_CONFLICT') }
    activeB = (await current(dispatcher, activeB)).exception
    await refresh(dispatcher, activeB)
    const contentB = `${managerPath(originalB)}/evidence/${photoB.evidence.id}/content`
    await privatePng(dispatcher, contentB, red)
    rejected(await api(page, `${managerPath(original)}/evidence/${photoB.evidence.id}/content`), 404, 'E0404')
    rejected(await api(page, `${managerPath(originalB)}/evidence/${oldPhoto1.id}/content`), 404, 'E0404')
    rejected(await api(page, `${oldPathB}/evidence/${oldPhoto1.id}/content`), 403, 'E0403')
    await privatePng(driverB, `${oldPathB}/evidence/${oldPhoto1.id}/content`, red)

    // Management historical photo access is independent of a driver's present
    // binding, while every self read and receipt retains the stricter scope.
    const unboundA = ok(await api<Driver>(page, `/fms/drivers/${a.driver.id}`, 'PATCH', { version: a.driver.version, accountId: null }))
    rejected(await api(driverA, `${selfPath(a)}/${original.id}`), 404, 'E0404')
    rejected(await api(driverA, selfPath(a), 'POST', reportBody, { key: reportKey }), 404, 'E0404')
    await privatePng(page, contentA, red); expect((await current(page, original)).exception.status).toBe('RESOLVED')
    ok(await api(page, `/fms/drivers/${b.driver.id}`, 'PATCH', { version: b.driver.version, accountId: null }))
    ok(await api(page, `/fms/drivers/${a.driver.id}`, 'PATCH', { version: unboundA.version, accountId: fixture.driverB.id }))
    const rebound = await current(driverB, original, a); expect(rebound.exception.description).toBe(description)
    const actors = ok(await api<Account[]>(page, '/upms/accounts')), actorB = actors.find((actor) => actor.id === fixture.driverB.id)!
    ok(await api(page, `/upms/accounts/${actorB.id}`, 'PATCH', { version: actorB.version, displayName: actorB.displayName, role: actorB.role, enabled: false }))
    rejected(await api(driverB, `${selfPath(a)}/${original.id}`), 401, 'E0401'); await privatePng(page, contentA, red)
    // Keep the private preview only in memory, then force a fresh role/session
    // failure; the manager must clear the original report, photo and objectURL.
    await viewPhoto(dispatcher)
    const revokedPreviewUrl = await dispatcher.getByRole('region', { name: '補件照片預覽', exact: true }).getByRole('img').getAttribute('src')
    const dispatcherActor = ok(await api<Account[]>(page, '/upms/accounts')).find((actor) => actor.id === fixture.dispatcher.id)!
    ok(await api(page, `/upms/accounts/${dispatcherActor.id}`, 'PATCH', { version: dispatcherActor.version, displayName: dispatcherActor.displayName, role: dispatcherActor.role, enabled: false }))
    await dispatcher.getByRole('button', { name: '重新讀取異常', exact: true }).click()
    await expect(dispatcher.getByRole('heading', { name: '登入工作空間', exact: true })).toBeVisible()
    await expect(dispatcher.getByLabel('原始異常描述', { exact: true })).toHaveCount(0); await expect(dispatcher.locator('img[src^="blob:"]')).toHaveCount(0)
    expect(await dispatcher.evaluate(async (url) => { try { await fetch(url!); return false } catch { return true } }, revokedPreviewUrl)).toBe(true)
    rejected(await api(dispatcher, contentB), 401, 'E0401')
    expect((await current(page, originalB)).execution).toEqual(b.execution)
    await privatePng(page, contentB, red)
  } finally { await Promise.all(contexts.map((context) => context.close())) }
})
