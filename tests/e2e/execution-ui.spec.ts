import { createHash, randomUUID } from 'node:crypto'
import { readFileSync, writeFileSync } from 'node:fs'
import { request as httpsRequest } from 'node:https'
import { crc32, deflateSync } from 'node:zlib'
import { isDeepStrictEqual } from 'node:util'
import { expect, test, type Browser, type BrowserContext, type Locator, type Page, type Request, type Response, type Route, type TestInfo } from '@playwright/test'
import { executionUiFixtureFor, fillPassword, signIn, type Credential } from './fixtures'
import { nativeSignIn, startNativeVisibilityBrowser } from './native-browser'

// M406 real DRIVER and manager UI. Original PNG bytes, auth and attempt identity stay in memory.
test.use({ screenshot: 'off' })
test.describe.configure({ retries: 0 })
type Body = Record<string, unknown>
interface Reply<T> { status: number; code: string; data: T; payload: Body; replayed: string | null; cache: string | null; completionRoute: boolean; noLocation: boolean }
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
  record(group: 'closure' | 'exceptions' | 'progress' | 'completion', schema: string, value: Body) { this[group].push({ schema, value }) }
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
    const response = await fetch(`/api/v1${path}`, { method, credentials: 'same-origin', redirect: 'error', headers, ...(wire === undefined ? {} : { body: wire }) })
    const payload = await response.json()
    return { status: response.status, code: payload.code, data: payload.data, payload, replayed: response.headers.get('Idempotency-Replayed'), cache: response.headers.get('Cache-Control'), completionRoute: /^\/(tms\/completions(?:\/|$)|orders\/(?:closure-worklist|[0-9a-f-]{36}\/(close|closure)(?:\/|$)))/i.test(path.split('?')[0]), noLocation: !response.headers.has('Location') }
  }, { path, method, body, options })
}
function exact(value: object, keys: readonly string[]) { expect(Object.keys(value).sort()).toEqual([...keys].sort()) }
function ok<T>(reply: Reply<T>, status = 200, code = '0000'): T { if (reply.completionRoute) { expect(reply.cache).toBe('no-store, private'); expect(reply.noLocation).toBe(true) }; expect(reply.status).toBe(status); expect(reply.code).toBe(code); exact(reply.payload, ['code', 'message', 'data', 'timestamp']); expect(typeof reply.payload.timestamp === 'string' && reply.payload.timestamp.endsWith('+08:00')).toBe(true); return reply.data }
function rejected(reply: Reply<unknown>, status: number, code: string, reason?: string) { const data = ok(reply, status, code); if (reason) expect((data as Body)?.reason).toBe(reason); if ([401, 404, 405, 413, 415, 500].includes(status)) expect(data).toBeNull(); expect(reply.replayed).toBeNull() }
async function contextFor(browser: Browser, info: TestInfo): Promise<BrowserContext> { const use = info.project.use; const context = await browser.newContext({ baseURL: use.baseURL, ignoreHTTPSErrors: use.ignoreHTTPSErrors, viewport: use.viewport, screen: use.screen, deviceScaleFactor: use.deviceScaleFactor, isMobile: use.isMobile, hasTouch: use.hasTouch, userAgent: use.userAgent }); context.on('page', (page) => collectors.get(info.testId)?.watch(page)); return context }
async function enter(page: Page, credential: Credential, temporary = false) { await page.goto('/'); expect(new URL(page.url()).protocol).toBe('https:'); await signIn(page, credential); await expect(page.getByRole(temporary ? 'heading' : 'button', { name: temporary ? '首次登入，請先修改密碼' : '登出', exact: true })).toBeVisible() }
interface Forwarded { status: number; payload: Body; replayed?: string; cache?: string }
const safeForwardFailure = 'The controlled synthetic DRIVER request could not be completed safely.'
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


interface Scenario { order: Order; driver: Driver; main: Vehicle; trailer: Vehicle; assignment: Assignment; taskId: string; taskNo: string; source?: CompleteDetail }
interface ExceptionRecord extends Body { id: string; assignmentId: string; taskId: string; taskNo: string; description: string; version: number; status: string; activeRequest: { id: string; reason: string; evidenceIds: string[]; uploadedCount: number } | null }
interface SupplementResult { exceptionId: string; assignmentId: string; taskId: string; version: number; requestId: string; evidence: Evidence }
type DriverKind = 'depart' | 'upload' | 'progress' | 'report' | 'supplement' | 'submit' | 'complete'
const buttons: Record<DriverKind, { start: string; review: string; final: string }> = {
  depart: { start: '出發回報', review: '檢視出發確認', final: '確認出發' }, upload: { start: '上傳任務照片', review: '檢視任務照片上傳', final: '確認上傳任務照片' }, progress: { start: '提交執行進度', review: '檢視進度確認', final: '確認提交進度' }, report: { start: '回報任務異常', review: '檢視異常回報確認', final: '確認回報異常' }, supplement: { start: '上傳補件照片', review: '檢視補件照片上傳', final: '確認上傳補件照片' }, submit: { start: '提交補件', review: '檢視補件提交確認', final: '確認提交補件' }, complete: { start: '完成任務並解除占用', review: '檢視任務完成確認', final: '確認完成並解除占用' },
}
const self = (scenario: Scenario) => `/tms/my-tasks/${scenario.assignment.id}`
const rawEqual = async (locator: Locator, value: string) => { await expect(locator).toBeVisible(); expect(await locator.textContent()).toBe(value) }
function original<T>(reply: Reply<T>, status = 200): T { expect(reply.status).toBe(status); expect(reply.code).toBe('OK'); exact(reply.payload, ['code', 'message', 'data', 'traceId']); return reply.data }
async function setup(manager: Page, fixture: ReturnType<typeof executionUiFixtureFor>): Promise<Scenario> {
  const driver = ok(await api<Driver>(manager, '/fms/drivers', 'POST', { driverCode: `SYNTHETIC_TMS_${fixture.scope}_D7001`, name: `合成手機操作司機 ${fixture.scope}`, accountId: fixture.driverA.id, employmentStatus: 'ACTIVE', dutyStatus: 'ON_DUTY' }), 201)
  const main = ok(await api<Vehicle>(manager, '/fms/vehicles', 'POST', { plateNo: `UIX-${fixture.scope[0]}-${randomUUID().slice(0, 10)}`, vehicleType: fixture.vehicleTypes.main.id, capacityTon: '1.00', administrativeStatus: 'AVAILABLE' }), 201), trailer = ok(await api<Vehicle>(manager, '/fms/vehicles', 'POST', { plateNo: `UIX-${fixture.scope[0]}-T-${randomUUID().slice(0, 10)}`, vehicleType: fixture.vehicleTypes.trailer.id, administrativeStatus: 'AVAILABLE' }), 201)
  const customer = ok(await api<{ id: string }>(manager, '/crm/customers', 'POST', { customerCode: `EXEC_UI_${fixture.scope}_${randomUUID().slice(0, 8).toUpperCase()}`, name: '合成手機操作客戶' }), 201)
  const draft = { type: 'LOCAL', customerId: customer.id, remark: '整單資料不可顯示在本人操作', items: [{ clientRef: 'ui-item', sequence: 1, itemKind: 'CARGO', packageQty: 7, grossWeight: '0.0000000000000000001', cbm: '0.00' }], tasks: [{ clientRef: 'ui-task', itemRef: 'ui-item', taskKind: `SYNTHETIC_TMS_${fixture.scope}_EXECUTION_UI_7001`, pickupName: ' 原始 e\u0301 取貨名稱 ', pickupAddress: ' 原始\u0000取貨地址\r\n二樓 ', deliveryName: ' 原始 e\u0301 送貨名稱 ', deliveryAddress: ' 原始\u0000送貨地址\r\n三樓 ', siteContact: { name: ' 原始 e\u0301\u0000 聯絡姓名 ', phone: null }, plannedStartAt: '1500-01-02T08:00:00.123456+23:59', plannedEndAt: '1500-01-02T10:00:00.123456+23:59', requiredTask: true, predecessorRefs: [] }] }
  const created = original(await api<Order>(manager, '/orders', 'POST', draft), 201), order = original(await api<Order>(manager, `/orders/${created.id}/accept`, 'POST', { expectedVersion: created.version })), task = order.tasks[0]
  const result = ok(await api<DispatchCommand>(manager, `/tms/tasks/${task.id}/dispatch`, 'POST', { expectedGeneration: 0, driverId: driver.id, mainVehicleId: main.id, trailerId: trailer.id })); expect(result.assignment.reservations).toHaveLength(3)
  return { order, driver, main, trailer, assignment: result.assignment, taskId: task.id, taskNo: task.taskNo }
}
async function openTask(page: Page, scenario: Scenario): Promise<CompleteDetail> {
  await page.bringToFront(); await page.getByRole('navigation', { name: '主要導覽', exact: true }).getByRole('button', { name: '本人運輸任務', exact: true }).click(); await expect(page.getByRole('heading', { name: '本人指派任務', exact: true })).toBeVisible()
  const waiting = page.waitForResponse((response) => response.request().method() === 'GET' && new URL(response.url()).pathname === `/api/v1${self(scenario)}/completion`)
  await page.getByRole('button', { name: `開啟本人任務 ${scenario.taskNo}`, exact: true }).click(); const response = await waiting; expect(response.status()).toBe(200); const detail = (await response.json()).data as CompleteDetail
  exact(detail, [...safeFields, 'execution', 'completion']); expect(detail.assignmentId).toBe(scenario.assignment.id); expect(detail.taskId).toBe(scenario.taskId)
  await expect(page.getByRole('heading', { name: `本人任務 ${scenario.taskNo}`, exact: true })).toBeVisible(); return detail
}
async function sourceUI(page: Page, scenario: Scenario) {
  const region = page.getByRole('region', { name: '本人任務來源快照', exact: true }), source = scenario.source!
  for (const [label, value] of [['原始取貨名稱', source.pickupName], ['原始取貨地址', source.pickupAddress], ['原始送貨名稱', source.deliveryName], ['原始送貨地址', source.deliveryAddress], ['原始現場聯絡姓名', (source.siteContact as Body).name]] as [string, string][]) await rawEqual(region.getByLabel(label, { exact: true }), value)
  for (const name of ['plannedStartAt', 'plannedEndAt']) await expect(region.getByText(source[name] as string, { exact: true })).toBeVisible()
  const item = source.item as Body; await expect(region.getByText(item.grossWeight as string, { exact: true })).toBeVisible(); await expect(region.getByText(item.cbm as string, { exact: true })).toBeVisible()
  for (const value of [scenario.order.id, scenario.order.customerId, scenario.driver.id, scenario.main.id, scenario.trailer.id, scenario.main.plateNo, scenario.trailer.plateNo]) await expect(region).not.toContainText(value as string)
  await expect(region.locator('script')).toHaveCount(0)
}
async function freshDriver(page: Page, scenario: Scenario, adopt = false): Promise<CompleteDetail> {
  await page.bringToFront(); const waiting = page.waitForResponse((response) => response.request().method() === 'GET' && new URL(response.url()).pathname === `/api/v1${self(scenario)}/completion`)
  await page.getByRole('button', { name: '重新讀取本人任務詳情', exact: true }).click(); const response = await waiting; expect(response.status()).toBe(200)
  if (adopt) { const adoption = page.getByRole('button', { name: '採用最新本人資料', exact: true }); await expect(adoption).toBeVisible(); await adoption.click() }
  await expect(page.getByRole('button', { name: '重新讀取本人任務詳情', exact: true })).toBeEnabled()
  return (await response.json()).data as CompleteDetail
}
async function start(page: Page, kind: DriverKind) { await page.bringToFront(); await page.getByRole('button', { name: buttons[kind].start, exact: true }).click() }
async function reviewDriver(page: Page, kind: DriverKind) { await page.getByRole('button', { name: buttons[kind].review, exact: true }).click(); await expect(page.getByRole('group', { name: '本人操作二次確認', exact: true })).toBeVisible(); await expect(page.getByRole('button', { name: buttons[kind].final, exact: true })).toBeFocused() }
async function choosePhoto(page: Page, kind: 'upload' | 'supplement', bytes: Buffer) { await start(page, kind); await page.getByLabel(kind === 'upload' ? '任務照片 PNG' : '補件照片 PNG', { exact: true }).setInputFiles({ name: 'synthetic-evidence.png', mimeType: 'image/png', buffer: bytes }); await reviewDriver(page, kind) }
async function selectEvidence(page: Page, kind: 'progress' | 'submit', ids: string[]) { const group = kind === 'progress' ? page.getByRole('region', { name: '本人任務照片', exact: true }) : page.getByRole('group', { name: '本次補件照片', exact: true }); for (const id of ids) await group.locator(`input[type="checkbox"][value="${id}"]`).check() }
async function finalDriver<T = Body>(page: Page, kind: DriverKind, path: string, status = 200): Promise<Reply<T>> {
  await page.bringToFront(); const waiting = page.waitForResponse((response) => response.request().method() === 'POST' && new URL(response.url()).pathname === `/api/v1${path}`)
  await page.getByRole('button', { name: buttons[kind].final, exact: true }).click(); const response = await waiting, payload = await response.json(); expect(response.status()).toBe(status)
  const reply = { status: response.status(), code: payload.code, data: payload.data as T, payload, replayed: await response.headerValue('Idempotency-Replayed'), cache: await response.headerValue('Cache-Control'), completionRoute: kind !== 'depart', noLocation: (await response.headerValue('Location')) === null }; expect(reply.noLocation).toBe(true); if (kind === 'depart') expect(reply.cache?.split(',').map((token) => token.trim())).toContain('no-store'); else expect(reply.cache).toBe('no-store, private'); return reply
}
async function openException(page: Page, scenario: Scenario, record: ExceptionRecord) {
  await page.bringToFront(); const current = page.getByRole('region', { name: '本人異常明細', exact: true }); if (await current.count()) { await rawEqual(current.getByLabel('本人原始異常描述', { exact: true }), record.description); return }
  await page.getByRole('button', { name: '重新讀取本人異常', exact: true }).click()
  await page.getByRole('button', { name: `開啟本人異常 ${scenario.taskNo} 1`, exact: true }).click(); await rawEqual(page.getByRole('region', { name: '本人異常明細', exact: true }).getByLabel('本人原始異常描述', { exact: true }), record.description)
}
async function privateStorageAbsent(page: Page, values: string[]) { expect(await page.evaluate(async (values) => { const stored = [...Object.values(localStorage), ...Object.values(sessionStorage)], forbidden = values.flatMap((value) => [value, JSON.stringify(value).slice(1, -1)]); return stored.every((value) => forbidden.every((privateValue) => !value.includes(privateValue))) && (await navigator.serviceWorker.getRegistrations()).length === 0 }, values)).toBe(true) }
async function screenshot(page: Page, info: TestInfo, name: string) {
  await page.bringToFront(); await expect(page.getByRole('region', { name: '私有照片預覽', exact: true })).toHaveCount(0); await expect(page.locator('img[src^="blob:"]')).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
  expect(await page.locator('input:not([type="hidden"]),textarea,select').evaluateAll((nodes) => nodes.filter((node) => node.getClientRects().length).every((node) => Boolean((node as HTMLInputElement).labels?.length || node.getAttribute('aria-label') || node.getAttribute('aria-labelledby'))))).toBe(true)
  await page.evaluate(() => { if (document.activeElement instanceof HTMLElement) document.activeElement.blur(); scrollTo({ top: 0, left: 0, behavior: 'instant' }) }); await page.screenshot({ path: info.outputPath(name), fullPage: true })
}

interface LostExpected { body: Body | Buffer; executionVersion?: number; exceptionVersion?: number; group: 'exceptions' | 'progress' | 'completion'; schema: string; valid: (data: Body) => boolean }
async function loseCommitted(page: Page, path: string, expected: LostExpected, collector: ResponseCollector) {
  const origin = new URL(page.url()).origin, fullPath = `/api/v1${path}`, cancellation = new AbortController(), active = new Set<Promise<void>>()
  let attempts = 0, matching = false, original: Buffer | null = null, key: string | undefined, versionHeaders: string | undefined
  let resolve!: (value: Body) => void, reject!: (error: Error) => void
  const committed = new Promise<Body>((yes, no) => { resolve = yes; reject = no }); void committed.catch(() => undefined)
  const deadline = setTimeout(() => { cancellation.abort(); reject(new Error(safeForwardFailure)) }, 30_000), matches = (url: URL) => url.origin === origin && url.pathname === fullPath
  const handle = async (route: Route) => {
    try {
      const request = route.request(); if (request.method() !== 'POST') { await route.continue(); return }
      attempts++; const headers = await request.allHeaders(), incomingKey = headers['idempotency-key'], incoming = request.postDataBuffer(), versions = JSON.stringify([headers['x-execution-version'], headers['x-exception-version']])
      if (attempts === 1) {
        original = incoming; key = incomingKey; versionHeaders = versions
        const sameBody = incoming && (Buffer.isBuffer(expected.body) ? incoming.equals(expected.body) : isDeepStrictEqual(JSON.parse(incoming.toString('utf8')), expected.body))
        if (!sameBody || !key || (expected.executionVersion !== undefined && headers['x-execution-version'] !== String(expected.executionVersion)) || (expected.exceptionVersion !== undefined && headers['x-exception-version'] !== String(expected.exceptionVersion))) throw new Error(safeForwardFailure)
        const response = await forward(request, origin, fullPath, cancellation.signal)
        if (response.status !== 200 || response.payload.code !== '0000' || response.cache !== 'no-store, private' || response.replayed !== 'false' || !expected.valid(response.payload.data as Body)) throw new Error(safeForwardFailure)
        collector.record(expected.group, expected.schema, response.payload); await route.abort('failed'); clearTimeout(deadline); resolve(response.payload.data as Body)
      } else {
        const same = Boolean(key && incomingKey === key && original && incoming && original.equals(incoming) && versions === versionHeaders); matching = attempts === 2 ? same : matching && same; if (!same) throw new Error(safeForwardFailure)
        if (attempts === 2) {
          const response = await forward(request, origin, fullPath, cancellation.signal, true)
          if (response.status !== 403 || response.payload.code !== 'E0403' || response.cache !== 'no-store, private' || (response.payload.data as Body)?.reason !== 'CSRF_INVALID') throw new Error(safeForwardFailure)
          await route.fulfill({ status: 403, headers: { 'Content-Type': 'application/json', 'Cache-Control': response.cache }, body: JSON.stringify(response.payload) })
        } else await route.continue()
      }
    } catch { clearTimeout(deadline); reject(new Error(safeForwardFailure)); try { await route.abort('failed') } catch { /* Closed/aborted page. */ } }
  }
  const handler = (route: Route) => { const work = handle(route); active.add(work); void work.then(() => active.delete(work), () => active.delete(work)); return work }
  await page.route(matches, handler)
  return { path: fullPath, committed, attempts: () => attempts, matching: () => matching,
    actorMiss: async (actorPage: Page) => { if (!original || !key) throw new Error(safeForwardFailure); return api(actorPage, path, 'POST', undefined, { raw: original.toString('utf8'), key }) },
    dispose: async () => { clearTimeout(deadline); cancellation.abort(); reject(new Error(safeForwardFailure)); await Promise.allSettled([...active]); await page.unroute(matches, handler) } }
}
async function retryUnknown(page: Page, fault: Awaited<ReturnType<typeof loseCommitted>>, note?: string): Promise<Body> {
  await page.bringToFront(); await expect(page.getByRole('heading', { name: '本人操作結果尚未確認', exact: true })).toBeVisible()
  if (note !== undefined) { await expect(page.getByLabel('補件說明', { exact: true })).toHaveValue(note); await expect(page.getByLabel('補件說明', { exact: true })).toBeDisabled() }
  const forbidden = page.waitForResponse((response) => response.request().method() === 'POST' && new URL(response.url()).pathname === fault.path && response.status() === 403)
  await page.getByRole('button', { name: '以原請求重試本人操作', exact: true }).click(); await forbidden; await expect(page.getByRole('heading', { name: '本人操作結果尚未確認', exact: true })).toBeVisible()
  const replayed = page.waitForResponse((response) => response.request().method() === 'POST' && new URL(response.url()).pathname === fault.path && response.status() === 200)
  await page.getByRole('button', { name: '以原請求重試本人操作', exact: true }).click(); const response = await replayed; expect(await response.headerValue('Idempotency-Replayed')).toBe('true'); expect(fault.attempts()).toBe(3); expect(fault.matching()).toBe(true)
  await expect(page.getByRole('region', { name: '本次本人操作收據', exact: true })).toBeVisible(); return (await response.json()).data as Body
}
async function holdOriginalPost(page: Page, path: string) {
  const origin = new URL(page.url()).origin, fullPath = `/api/v1${path}`; let reached!: () => void, release!: () => void, used = false
  const arrived = new Promise<void>((resolve) => { reached = resolve }), gate = new Promise<void>((resolve) => { release = resolve }), matches = (url: URL) => url.origin === origin && url.pathname === fullPath
  const deadline = setTimeout(release, 60_000)
  const handler = async (route: Route) => { if (route.request().method() !== 'POST' || used) { await route.continue(); return }; used = true; reached(); await gate; clearTimeout(deadline); try { await route.continue() } catch { /* Page may have retired while held. */ } }
  await page.route(matches, handler)
  return { arrived, release, dispose: async () => { clearTimeout(deadline); release(); await page.unroute(matches, handler) } }
}
async function managerException(page: Page, scenario: Scenario, record: ExceptionRecord) {
  await page.bringToFront(); const back = page.getByRole('button', { name: '返回異常列表', exact: true }); if (await back.count()) await back.click()
  await page.getByRole('navigation', { name: '主要導覽', exact: true }).getByRole('button', { name: '異常處理', exact: true }).click(); await page.getByLabel('指派識別篩選', { exact: true }).fill(scenario.assignment.id); await page.getByLabel('異常狀態', { exact: true }).selectOption('')
  const waiting = page.waitForResponse((response) => { const url = new URL(response.url()); return url.pathname === '/api/v1/tms/exceptions' && url.searchParams.get('assignmentId') === scenario.assignment.id })
  await page.getByRole('button', { name: '查詢異常', exact: true }).click(); expect((await waiting).status()).toBe(200); await page.getByRole('button', { name: `開啟異常 ${scenario.taskNo}`, exact: true }).click(); await rawEqual(page.getByRole('region', { name: '目前異常資料', exact: true }).getByLabel('原始異常描述', exactOptions), record.description)
}
const exactOptions = { exact: true } as const
async function managerDisposition(page: Page, scenario: Scenario, record: ExceptionRecord, kind: 'request' | 'resolve', reason: string): Promise<ExceptionRecord> {
  await managerException(page, scenario, record)
  await page.getByRole('button', { name: kind === 'request' ? '要求補件' : '處置異常', exact: true }).click(); await page.getByLabel(kind === 'request' ? '補件要求（司機可見）' : '處置原因（司機可見）', exactOptions).fill(reason); await page.getByRole('button', { name: kind === 'request' ? '檢視補件要求確認' : '檢視異常處置確認', exact: true }).click()
  const path = `/api/v1/tms/exceptions/${record.id}/${kind === 'request' ? 'supplement-requests' : 'resolve'}`, waiting = page.waitForResponse((response) => response.request().method() === 'POST' && new URL(response.url()).pathname === path)
  await page.getByRole('button', { name: kind === 'request' ? '確認要求補件' : '確認處置異常', exact: true }).click(); const response = await waiting; expect(response.status()).toBe(200); expect(await response.headerValue('Idempotency-Replayed')).toBe('false'); return (await response.json()).data as ExceptionRecord
}
async function managerConfirmAndClose(page: Page, scenario: Scenario, completion: Witness) {
  await page.bringToFront(); const nav = page.getByRole('navigation', { name: '主要導覽', exact: true }); await nav.getByRole('button', { name: '確認與結案', exact: true }).click(); await page.getByRole('tab', { name: '完成管理確認', exact: true }).click(); await page.getByLabel('行政確認狀態', exactOptions).selectOption('ALL'); await page.getByLabel('確認訂單識別', exactOptions).fill(scenario.order.id)
  const list = page.waitForResponse((response) => { const url = new URL(response.url()); return url.pathname === '/api/v1/tms/completions' && url.searchParams.get('orderId') === scenario.order.id })
  await page.getByRole('button', { name: '查詢完成確認', exact: true }).click(); expect((await list).status()).toBe(200); await page.getByRole('button', { name: `開啟完成確認 ${scenario.taskNo}`, exact: true }).click(); await page.getByRole('button', { name: '記錄管理確認', exact: true }).click(); await page.getByLabel('管理確認原因', exactOptions).fill(' 合成手機流程管理確認 e\u0301 \u0000 '); await page.getByRole('button', { name: '檢視管理確認', exact: true }).click()
  const confirmed = page.waitForResponse((response) => response.request().method() === 'POST' && new URL(response.url()).pathname === `/api/v1/tms/completions/${completion.id}/confirm`)
  await page.getByRole('button', { name: '確認記錄', exact: true }).click(); const confirmation = await confirmed; expect(confirmation.status()).toBe(200); expect((await confirmation.json()).data.version).toBe(1)
  await page.getByRole('button', { name: '重新讀取行政確認', exact: true }).click(); await expect(page.getByRole('region', { name: '目前行政確認資料', exact: true })).toBeVisible(); await page.getByRole('tab', { name: '訂單結案', exact: true }).click()
  await page.getByLabel('每頁結案筆數', exactOptions).selectOption('50'); const worklist = page.waitForResponse((response) => { const url = new URL(response.url()); return url.pathname === '/api/v1/orders/closure-worklist' && url.searchParams.get('size') === '50' })
  await page.getByRole('button', { name: '查詢結案訂單', exact: true }).click(); expect((await worklist).status()).toBe(200)
  const orderButton = page.getByRole('button', { name: `開啟訂單結案 ${scenario.order.orderNo}`, exact: true }), listRegion = page.getByRole('region', { name: '結案清單', exact: true }); await expect(listRegion.getByRole('status')).toHaveCount(0); let pageNumber = 0
  while (!(await orderButton.count())) { const next = page.getByRole('button', { name: '下一頁結案清單', exact: true }); await expect(next).toBeEnabled(); pageNumber++; const paged = page.waitForResponse((response) => { const url = new URL(response.url()); return url.pathname === '/api/v1/orders/closure-worklist' && url.searchParams.get('page') === String(pageNumber) }); await next.click(); expect((await paged).status()).toBe(200); await expect(listRegion.getByRole('status')).toHaveCount(0) }
  await orderButton.click(); await page.getByRole('button', { name: '結案訂單', exact: true }).click(); await page.getByLabel('訂單結案原因', exactOptions).fill(' 合成手機流程結案 e\u0301 \u0000 '); await page.getByRole('button', { name: '檢視結案確認', exact: true }).click()
  const closed = page.waitForResponse((response) => response.request().method() === 'POST' && new URL(response.url()).pathname === `/api/v1/orders/${scenario.order.id}/close`)
  await page.getByRole('button', { name: '確認結案', exact: true }).click(); const closure = await closed; expect(closure.status()).toBe(200); const result = (await closure.json()).data; expect(result).toMatchObject({ lifecycleStatus: 'CLOSED', acceptanceStatus: 'ACCEPTED', version: scenario.order.version + 1 }); expect(result.closure.sourceVersion).toBe(scenario.order.version)
  await page.getByRole('button', { name: '重新讀取訂單結案', exact: true }).click(); await expect(page.getByRole('region', { name: '目前訂單結案資料', exact: true })).toContainText('已結案')
}

async function previewPhoto(page: Page, scenario: Scenario, evidence: Evidence, bytes: Buffer, supplement = false, ordinal = 1): Promise<string> {
  await page.bringToFront(); const path = `/api/v1${self(scenario)}/evidence/${evidence.id}/content`, waiting = page.waitForResponse((response) => response.request().method() === 'GET' && new URL(response.url()).pathname === path)
  await page.getByRole('button', { name: `${supplement ? '預覽補件照片' : '預覽任務照片'} ${ordinal}`, exact: true }).click(); const response = await waiting, actual = await response.body()
  expect(response.status()).toBe(200); expect(actual.equals(bytes)).toBe(true); expect(hash(actual)).toBe(evidence.sha256)
  for (const [header, value] of [['Content-Type', 'image/png'], ['Content-Length', String(bytes.length)], ['Cache-Control', 'no-store, private'], ['X-Content-Type-Options', 'nosniff'], ['Content-Disposition', 'inline; filename="evidence.png"']]) expect(await response.headerValue(header)).toBe(value)
  for (const header of ['ETag', 'Last-Modified', 'Accept-Ranges', 'Location', 'Content-Location']) expect(await response.headerValue(header)).toBeNull()
  const preview = page.getByRole('region', { name: '私有照片預覽', exact: true }), img = preview.getByRole('img', { name: '本人私有任務照片', exact: true }); await expect(img).toBeVisible(); expect(await img.evaluate(async (node: HTMLImageElement) => { await node.decode(); return [node.naturalWidth, node.naturalHeight] })).toEqual([2, 2]); return (await img.getAttribute('src'))!
}
async function closePhoto(page: Page, url: string) { await page.getByRole('button', { name: '關閉私有照片預覽', exact: true }).click(); await expect(page.getByRole('region', { name: '私有照片預覽', exact: true })).toHaveCount(0); expect(await page.evaluate(async (url) => { try { await fetch(url); return false } catch { return true } }, url)).toBe(true) }
interface PrivateForwarded { bytes: Buffer; headers: Record<string, string> }
async function forwardPrivate(request: Request, origin: string, path: string, signal: AbortSignal): Promise<PrivateForwarded> {
  try {
    const url = new URL(request.url()); if (url.origin !== origin || url.protocol !== 'https:' || url.pathname !== path || request.method() !== 'GET') throw new Error(safeForwardFailure)
    const headers = { ...await request.allHeaders() }; for (const name of ['connection', 'proxy-connection', 'keep-alive', 'transfer-encoding', 'upgrade', 'te', 'trailer', 'proxy-authenticate', 'proxy-authorization']) delete headers[name]; for (const name of Object.keys(headers)) if (name.startsWith(':')) delete headers[name]; headers['accept-encoding'] = 'identity'
    const ca = ['localhost', '127.0.0.1'].includes(url.hostname) ? readFileSync('infrastructure/dev-tls/localhost.crt') : undefined
    return await new Promise<PrivateForwarded>((resolve, reject) => {
      let settled = false; let deadline: ReturnType<typeof setTimeout> | undefined; let upstream: ReturnType<typeof httpsRequest> | undefined
      const finish = (complete: () => void) => { if (settled) return; settled = true; if (deadline) clearTimeout(deadline); signal.removeEventListener('abort', cancel); complete() }, fail = () => finish(() => reject(new Error(safeForwardFailure))), cancel = () => { upstream?.destroy(); fail() }
      if (signal.aborted) { fail(); return }
      upstream = httpsRequest(url, { method: 'GET', headers, ...(ca ? { ca } : {}), rejectUnauthorized: true, agent: false }, (response) => {
        const chunks: Buffer[] = []; let size = 0
        response.on('error', fail); response.on('aborted', fail); response.on('data', (chunk: Buffer) => { size += chunk.length; if (size > 1_048_576) { response.destroy(); fail() } else chunks.push(chunk) })
        response.on('end', () => {
          const values: Record<string, string> = {}; for (const name of ['content-type', 'content-length', 'cache-control', 'x-content-type-options', 'content-disposition']) { const value = response.headers[name]; if (typeof value === 'string') values[name] = value }
          if (response.statusCode !== 200 || values['content-type'] !== 'image/png' || values['content-length'] !== String(size) || values['cache-control'] !== 'no-store, private' || values['x-content-type-options'] !== 'nosniff' || values['content-disposition'] !== 'inline; filename="evidence.png"' || ['etag', 'last-modified', 'accept-ranges', 'location', 'content-location'].some((name) => response.headers[name] !== undefined)) { fail(); return }
          finish(() => resolve({ bytes: Buffer.concat(chunks), headers: values }))
        })
      }); upstream.on('error', fail); deadline = setTimeout(cancel, 15_000); signal.addEventListener('abort', cancel, { once: true }); upstream.end()
    })
  } catch { throw new Error(safeForwardFailure) }
}
async function actualVisibilityAndLateBytes(page: Page, otherTab: Page, scenario: Scenario, evidence: Evidence, bytes: Buffer) {
  const origin = new URL(page.url()).origin, path = `/api/v1${self(scenario)}/evidence/${evidence.id}/content`, matches = (url: URL) => url.origin === origin && url.pathname === path, cancellation = new AbortController()
  let release!: () => void, ready!: () => void, fail!: (error: Error) => void
  const gate = new Promise<void>((resolve) => { release = resolve }), fetched = new Promise<void>((resolve, reject) => { ready = resolve; fail = reject }); void fetched.catch(() => undefined)
  const active = new Set<Promise<void>>(), deadline = setTimeout(() => { cancellation.abort(); fail(new Error(safeForwardFailure)); release() }, 30_000)
  const handle = async (route: Route) => { try { const actual = await forwardPrivate(route.request(), origin, path, cancellation.signal); if (!actual.bytes.equals(bytes) || hash(actual.bytes) !== evidence.sha256) throw new Error(safeForwardFailure); ready(); await gate; try { await route.fulfill({ status: 200, headers: actual.headers, body: actual.bytes }) } catch { /* The actual hidden lifecycle may already have aborted this GET. */ } } catch { fail(new Error(safeForwardFailure)); try { await route.abort('failed') } catch { /* Aborted. */ } } }
  const handler = (route: Route) => { const work = handle(route); active.add(work); void work.then(() => active.delete(work), () => active.delete(work)); return work }
  await page.route(matches, handler)
  try {
    await page.getByRole('button', { name: '預覽任務照片 1', exact: true }).click(); await fetched; await otherTab.bringToFront()
    await expect.poll(() => page.evaluate(() => document.visibilityState), { message: 'A real background tab must become hidden; report runner inability instead of faking a visibility event.' }).toBe('hidden')
    await expect(page.getByRole('region', { name: '目前本人任務資料', exact: true })).toHaveCount(0); await expect(page.getByRole('region', { name: '本人任務來源快照', exact: true })).toHaveCount(0); await expect(page.getByLabel('原始取貨地址', exactOptions)).toHaveCount(0); await expect(page.locator('img[src^="blob:"]')).toHaveCount(0); release(); await Promise.allSettled([...active])
    await page.bringToFront(); await expect.poll(() => page.evaluate(() => document.visibilityState)).toBe('visible'); await freshDriver(page, scenario); await expect(page.locator('img[src^="blob:"]')).toHaveCount(0)
  } finally { clearTimeout(deadline); cancellation.abort(); release(); await Promise.allSettled([...active]); await page.unroute(matches, handler) }
}
async function failedLogoutRetainsUnknown(page: Page, note: string, attempt: Awaited<ReturnType<typeof loseCommitted>>, accountId: string) {
  const origin = new URL(page.url()).origin, matches = (url: URL) => url.origin === origin && url.pathname === '/api/v1/auth/logout'; let release!: () => void, reached!: () => void
  const gate = new Promise<void>((resolve) => { release = resolve }), arrived = new Promise<void>((resolve) => { reached = resolve }), deadline = setTimeout(release, 15_000)
  const handler = async (route: Route) => { reached(); await gate; try { await route.abort('failed') } catch { /* Page retired. */ } }
  await page.route(matches, handler)
  try {
    await page.bringToFront(); await page.getByRole('button', { name: '登出', exact: true }).click(); const warning = page.getByRole('alert', { name: '確認切換工作頁面', exact: true }); await expect(warning).toBeVisible(); await warning.getByRole('button', { name: '留在本人任務頁面', exact: true }).click(); await expect(page.getByLabel('補件說明', exactOptions)).toHaveValue(note)
    await page.getByRole('button', { name: '登出', exact: true }).click(); await warning.getByRole('button', { name: '捨棄本人任務內容並登出', exact: true }).click(); await arrived
    await expect(page.getByRole('region', { name: '目前本人任務資料', exact: true })).toHaveCount(0); await expect(page.getByRole('region', { name: '本人任務來源快照', exact: true })).toHaveCount(0); await expect(page.getByLabel('原始取貨地址', exactOptions)).toHaveCount(0); await expect(page.getByLabel('補件說明', exactOptions)).toHaveCount(0); await expect(page.locator('img[src^="blob:"]')).toHaveCount(0); release()
    await expect(page.getByRole('heading', { name: '本人操作結果尚未確認', exact: true })).toBeVisible(); await expect(page.getByLabel('補件說明', exactOptions)).toHaveValue(note); expect(attempt.attempts()).toBe(1)
    expect(await page.evaluate(async (id) => { const response = await fetch('/api/v1/auth/me', { credentials: 'same-origin', redirect: 'error' }); return response.status === 200 && (await response.json()).data.id === id }, accountId)).toBe(true)
    await page.getByRole('button', { name: '登出', exact: true }).click(); await expect(warning).toBeVisible(); await warning.getByRole('button', { name: '留在本人任務頁面', exact: true }).click(); expect(attempt.attempts()).toBe(1)
  } finally { clearTimeout(deadline); release(); await page.unroute(matches, handler) }
}

test('DRIVER uses all seven real actions, recovers original bytes and receipts, and reads completed work after closure on desktop and phone', async ({ page, browser }, info) => {
  test.setTimeout(900_000)
  const fixture = executionUiFixtureFor(info.project.name), collector = collectors.get(info.testId)!, contexts = await Promise.all(Array.from({ length: 6 }, () => contextFor(browser, info)))
  try {
    const [dispatcher, driverA, driverB, temporary, anonymous, control] = await Promise.all(contexts.map((context) => context.newPage()))
    await enter(page, fixture.admin); await enter(dispatcher, fixture.dispatcher); await enter(driverA, fixture.driverA); await enter(driverB, fixture.driverB); await enter(temporary, fixture.temporary, true); await enter(control, fixture.driverA); await anonymous.goto('/')
    const scenario = await setup(page, fixture), base = self(scenario), red = png(), blue = png(2, 2, 203), green = png(2, 2, 151), gray = png(2, 2, 53)
    for (const [actor, status, code] of [[page, 403, 'E0403'], [dispatcher, 403, 'E0403'], [temporary, 403, 'E0403'], [anonymous, 401, 'E0401']] as const) rejected(await api(actor, `${base}/completion`), status, code)
    rejected(await api(driverB, `${base}/completion`), 404, 'E0404')
    for (const actor of [page, dispatcher, temporary]) await expect(actor.getByRole('navigation', { name: '主要導覽', exact: true }).getByRole('button', { name: '本人運輸任務', exact: true })).toHaveCount(0)
    scenario.source = await openTask(driverA, scenario); expect(scenario.source.execution).toEqual({ phase: 'WAITING', version: 0, departedAt: null }); expect(scenario.source.completion).toBeNull(); await sourceUI(driverA, scenario); await screenshot(driverA, info, 'driver-ui-original-safe-source.png')
    await start(driverA, 'depart'); await reviewDriver(driverA, 'depart'); const departure = ok(await finalDriver<{ assignmentId: string; taskId: string; execution: Execution }>(driverA, 'depart', `${base}/depart`)); expect(departure.execution.phase).toBe('DEPARTED'); expect(departure.execution.version).toBe(1); const departedAt = departure.execution.departedAt; expect(departedAt).not.toBeNull(); await freshDriver(driverA, scenario)
    await choosePhoto(driverA, 'upload', red)
    const ordinaryLoss = await loseCommitted(driverA, `${base}/evidence`, { body: red, executionVersion: 1, group: 'progress', schema: 'EvidenceEnvelope', valid: (data) => data.assignmentId === scenario.assignment.id && data.taskId === scenario.taskId && data.sha256 === hash(red) && data.byteSize === red.length && data.executionVersion === 1 && data.phase === 'DEPARTED' }, collector)
    let firstPhoto!: Evidence
    try { await driverA.getByRole('button', { name: buttons.upload.final, exact: true }).click(); firstPhoto = await ordinaryLoss.committed as unknown as Evidence; expect(isDeepStrictEqual(await retryUnknown(driverA, ordinaryLoss), firstPhoto)).toBe(true) } finally { await ordinaryLoss.dispose() }
    expect((await freshDriver(driverA, scenario)).execution).toEqual({ phase: 'DEPARTED', version: 1, departedAt })
    const firstUrl = await previewPhoto(driverA, scenario, firstPhoto, red); await closePhoto(driverA, firstUrl)
    // The native default context is DRIVER A's third normal session, within
    // UPMS's three-session limit. Only the visibility/late-byte proof moves;
    // all seven business commands remain in the original project contexts.
    const nativeBaseURL = String(info.project.use.baseURL), native = await startNativeVisibilityBrowser({ baseURL: nativeBaseURL, viewport: info.project.use.viewport ?? { width: 1280, height: 720 } })
    collector.watch(native.page); native.context.on('page', (opened) => collector.watch(opened))
    let nativeLoggedIn = false
    try {
      await native.page.goto(new URL('/', nativeBaseURL).href); const login = await nativeSignIn(native.page, fixture.driverA, fillPassword); nativeLoggedIn = login.status && login.role && login.account; expect(login, 'Native UI login requires real focus and a successful normal DRIVER response.').toEqual({ visible: true, hasFocus: true, formsubmit: true, status: true, role: true, account: true, ready: true })
      const nativeDetail = await openTask(native.page, scenario); expect(nativeDetail.execution).toEqual({ phase: 'DEPARTED', version: 1, departedAt }); expect(nativeDetail.completion).toBeNull()
      const privacyTab = await native.context.newPage(); await privacyTab.goto('about:blank'); await native.page.bringToFront(); await actualVisibilityAndLateBytes(native.page, privacyTab, scenario, firstPhoto, red)
    } finally {
      try { if (nativeLoggedIn && !native.page.isClosed()) { if (native.page.url() === 'about:blank') await native.page.goto(new URL('/', nativeBaseURL).href); await native.page.bringToFront(); await native.page.getByRole('button', { name: '登出', exact: true }).click(); await expect(native.page.getByRole('heading', { name: '登入工作空間', exact: true })).toBeVisible() } } finally { await native.dispose() }
    }
    await driverA.bringToFront(); await sourceUI(driverA, scenario)
    await start(driverA, 'progress'); await selectEvidence(driverA, 'progress', [firstPhoto.id]); await reviewDriver(driverA, 'progress'); const progressing = ok(await finalDriver<{ execution: Execution }>(driverA, 'progress', `${base}/progress`)); expect(progressing.execution).toEqual({ phase: 'IN_PROGRESS', version: 2, departedAt }); await freshDriver(driverA, scenario)
    const readyPhotos: Evidence[] = []
    for (const bytes of [blue, green]) { await choosePhoto(driverA, 'upload', bytes); const uploaded = ok(await finalDriver<Evidence>(driverA, 'upload', `${base}/evidence`)); expect(uploaded.sha256).toBe(hash(bytes)); expect(uploaded.executionVersion).toBe(2); readyPhotos.push(uploaded); expect((await freshDriver(driverA, scenario)).execution.version).toBe(2) }
    const readyIds = readyPhotos.map((photo) => photo.id).sort(), description = ' 原始異常 e\u0301\n\u0000 <b>純文字</b> 🚚 ', note = ' 原始補件說明 e\u0301\n\u0000 <script>純文字</script> ', requestReason = ' 管理要求原文 e\u0301\n\u0000 '
    await start(driverA, 'report'); await driverA.getByLabel('原始異常描述', exactOptions).fill(description); await reviewDriver(driverA, 'report'); await rawEqual(driverA.getByRole('group', { name: '本人操作二次確認', exact: true }).getByLabel('本次異常描述原文', exactOptions), description)
    // The original user-confirmed request is held unchanged before the server.
    // Another legal UI operation changes EXEC; no response/token is fabricated.
    const reportGate = await holdOriginalPost(driverA, `${base}/exceptions`), reportWaiting = finalDriver(driverA, 'report', `${base}/exceptions`, 409); void reportWaiting.catch(() => undefined)
    try {
      await reportGate.arrived; const logout = driverA.getByRole('button', { name: '登出', exact: true }); if (await logout.isEnabled()) { await logout.click(); const warning = driverA.getByRole('alert', { name: '確認切換工作頁面', exact: true }); if (await warning.count()) { await expect(warning.getByRole('button', { name: '捨棄本人任務內容並登出', exact: true })).toBeDisabled(); await warning.getByRole('button', { name: '留在本人任務頁面', exact: true }).click() } }
      await openTask(control, scenario); await start(control, 'progress'); await selectEvidence(control, 'progress', readyIds); await reviewDriver(control, 'progress'); const advanced = ok(await finalDriver<{ execution: Execution }>(control, 'progress', `${base}/progress`)); expect(advanced.execution).toEqual({ phase: 'AWAITING_COMPLETION', version: 3, departedAt })
      expect(await driverA.evaluate(() => document.visibilityState), 'The independent control session must not hide and abort the original paused UI request.').toBe('visible'); reportGate.release(); const stale = await reportWaiting; rejected(stale, 409, 'E1901', 'VERSION_CONFLICT'); exact(stale.data, ['reason', 'actualExecutionVersion']); expect(stale.data.actualExecutionVersion).toBe(3)
    } finally { reportGate.release(); await reportGate.dispose(); await reportWaiting.catch(() => undefined) }
    await driverA.bringToFront(); await expect(driverA.getByLabel('原始異常描述', exactOptions)).toHaveValue(description); await freshDriver(driverA, scenario, true)
    await start(driverA, 'report'); await driverA.getByLabel('原始異常描述', exactOptions).fill(description); await reviewDriver(driverA, 'report'); const report = ok(await finalDriver<ExceptionRecord>(driverA, 'report', `${base}/exceptions`)); expect(report.description).toBe(description); expect(report.version).toBe(0); expect(report.status).toBe('OPEN'); await freshDriver(driverA, scenario)
    const requested = await managerDisposition(dispatcher, scenario, report, 'request', requestReason); expect(requested.status).toBe('NEEDS_SUPPLEMENT'); expect(requested.version).toBe(1); const requestId = requested.activeRequest!.id, supplementPath = `${base}/exceptions/${report.id}/supplement-requests/${requestId}`
    await freshDriver(driverA, scenario); await openException(driverA, scenario, report); await rawEqual(driverA.getByRole('region', { name: '本次補件要求', exact: true }).getByLabel('本次補件要求原文', exactOptions), requestReason)
    await choosePhoto(driverA, 'supplement', red)
    const supplementGate = await holdOriginalPost(driverA, `${supplementPath}/evidence`), staleUpload = finalDriver(driverA, 'supplement', `${supplementPath}/evidence`, 409); void staleUpload.catch(() => undefined); let otherPhoto!: SupplementResult
    try {
      await supplementGate.arrived; await freshDriver(control, scenario); await openException(control, scenario, report); await choosePhoto(control, 'supplement', gray); otherPhoto = ok(await finalDriver<SupplementResult>(control, 'supplement', `${supplementPath}/evidence`)); expect(otherPhoto.version).toBe(2); expect(otherPhoto.evidence.sha256).toBe(hash(gray))
      expect(await driverA.evaluate(() => document.visibilityState), 'The independent control session must not hide and abort the original paused UI request.').toBe('visible'); supplementGate.release(); const conflict = await staleUpload; rejected(conflict, 409, 'E1901', 'VERSION_CONFLICT'); exact(conflict.data, ['reason', 'actualVersion']); expect(conflict.data.actualVersion).toBe(2)
    } finally { supplementGate.release(); await supplementGate.dispose(); await staleUpload.catch(() => undefined) }
    await freshDriver(driverA, scenario, true); await openException(driverA, scenario, report); await choosePhoto(driverA, 'supplement', red)
    const supplementLoss = await loseCommitted(driverA, `${supplementPath}/evidence`, { body: red, executionVersion: 3, exceptionVersion: 2, group: 'exceptions', schema: 'SupplementUploadEnvelope', valid: (data) => data.exceptionId === report.id && data.requestId === requestId && data.version === 3 && (data.evidence as Body)?.sha256 === hash(red) && (data.evidence as Body)?.executionVersion === 3 }, collector)
    let supplement!: SupplementResult
    try { await driverA.getByRole('button', { name: buttons.supplement.final, exact: true }).click(); supplement = await supplementLoss.committed as unknown as SupplementResult; expect(isDeepStrictEqual(await retryUnknown(driverA, supplementLoss), supplement)).toBe(true) } finally { await supplementLoss.dispose() }
    expect((await freshDriver(driverA, scenario)).execution).toEqual({ phase: 'AWAITING_COMPLETION', version: 3, departedAt }); await openException(driverA, scenario, report); await expect(driverA.getByRole('region', { name: '本次補件要求', exact: true })).toContainText('尚需明確提交補件')
    const supplementIds = [otherPhoto.evidence.id, supplement.evidence.id].sort(), firstSupplement = supplementIds[0] === supplement.evidence.id ? supplement.evidence : otherPhoto.evidence, firstSupplementBytes = firstSupplement.id === supplement.evidence.id ? red : gray
    const supplementUrl = await previewPhoto(driverA, scenario, firstSupplement, firstSupplementBytes, true); await closePhoto(driverA, supplementUrl)
    const ordinaryUnavailable = driverA.getByRole('button', { name: buttons.upload.start, exact: true }); if (await ordinaryUnavailable.count()) await expect(ordinaryUnavailable).toBeDisabled()
    await start(driverA, 'submit'); await driverA.getByLabel('補件說明', exactOptions).fill(note); await selectEvidence(driverA, 'submit', supplementIds); await reviewDriver(driverA, 'submit'); await rawEqual(driverA.getByRole('group', { name: '本人操作二次確認', exact: true }).getByLabel('本次補件說明原文', exactOptions), note)
    const submitBody = { expectedVersion: 3, note, evidenceIds: supplementIds }, submitLoss = await loseCommitted(driverA, `${supplementPath}/submit`, { body: submitBody, group: 'exceptions', schema: 'ExceptionRecordEnvelope', valid: (data) => data.id === report.id && data.version === 4 && data.status === 'OPEN' && data.activeRequest === null && data.description === description }, collector)
    let submitted!: ExceptionRecord
    try {
      await driverA.getByRole('button', { name: buttons.submit.final, exact: true }).click(); submitted = await submitLoss.committed as ExceptionRecord
      await failedLogoutRetainsUnknown(driverA, note, submitLoss, fixture.driverA.id)
      const resolved = await managerDisposition(dispatcher, scenario, report, 'resolve', ' 管理另行處置 e\u0301 \u0000 '); expect(resolved.status).toBe('RESOLVED'); expect(resolved.version).toBe(5)
      const historical = await retryUnknown(driverA, submitLoss, note); expect(isDeepStrictEqual(historical, submitted)).toBe(true); expect(historical.status).toBe('OPEN'); expect(historical.version).toBe(4)
      await rawEqual(driverA.getByRole('region', { name: '本次本人操作收據', exact: true }).getByLabel('本人補件說明原文', exactOptions), note)
    } finally { await submitLoss.dispose() }
    await freshDriver(driverA, scenario); await openException(driverA, scenario, report); const history = driverA.getByRole('region', { name: '本人異常歷程', exact: true }); await expect(history).toBeVisible(); await rawEqual(history.getByLabel('本人補件說明原文', exactOptions), note); await rawEqual(driverA.getByRole('region', { name: '本人異常明細', exact: true }).getByLabel('本人原始異常描述', exactOptions), description); await privateStorageAbsent(driverA, [description, note, scenario.assignment.id, scenario.source!.pickupAddress as string]); await screenshot(driverA, info, 'driver-ui-supplement-restored.png')
    await start(driverA, 'complete'); const required = driverA.getByRole('region', { name: '本次完成必要證據', exact: true }); await required.getByText('完成採用的照片識別', exactOptions).click(); for (const id of readyIds) await expect(required.getByText(id, exactOptions)).toBeVisible(); for (const id of supplementIds) await expect(required).not.toContainText(id); await driverA.getByLabel('我確認本次任務完成並可解除其資源占用', exactOptions).check(); await reviewDriver(driverA, 'complete')
    const completeBody = { expectedVersion: 3, evidenceIds: readyIds, confirmSafeRelease: true }, completeLoss = await loseCommitted(driverA, `${base}/complete`, { body: completeBody, group: 'completion', schema: 'CompleteMyTaskEnvelope', valid: (data) => data.assignmentId === scenario.assignment.id && data.taskId === scenario.taskId && (data.execution as Body)?.phase === 'COMPLETED' && (data.execution as Body)?.version === 4 && (data.execution as Body)?.departedAt === departedAt && (data.completion as Body)?.safeReleaseConfirmed === true }, collector)
    let result!: CompleteResult
    try {
      await driverA.getByRole('button', { name: buttons.complete.final, exact: true }).click(); result = await completeLoss.committed as unknown as CompleteResult; expect(result.completion.completedAt).toBe(result.completion.releasedAt); expect(result.completion.policyMode).toBe('SYNTHETIC_TEST_ONLY')
      await managerConfirmAndClose(page, scenario, result.completion)
      const customer = ok(await api<{ id: string }>(page, '/crm/customers', 'POST', { customerCode: `UI_REUSE_${fixture.scope}_${randomUUID().slice(0, 8).toUpperCase()}`, name: '合成手機完成後重用客戶' }), 201)
      const newOrder = original(await api<Order>(page, '/orders', 'POST', { type: 'LOCAL', customerId: customer.id, items: [{ clientRef: 'reuse-item', sequence: 1, itemKind: 'CARGO', packageQty: 1 }], tasks: [{ clientRef: 'reuse-task', itemRef: 'reuse-item', taskKind: `SYNTHETIC_TMS_${fixture.scope}_EXECUTION_UI_REUSE_7002`, pickupName: '合成重用起點', pickupAddress: '合成重用地址', deliveryName: '合成重用終點', deliveryAddress: '合成重用地址', requiredTask: true, predecessorRefs: [] }] }), 201), accepted = original(await api<Order>(page, `/orders/${newOrder.id}/accept`, 'POST', { expectedVersion: 0 }))
      const reused = ok(await api<DispatchCommand>(page, `/tms/tasks/${accepted.tasks[0].id}/dispatch`, 'POST', { expectedGeneration: 0, driverId: scenario.driver.id, mainVehicleId: scenario.main.id, trailerId: scenario.trailer.id })); expect(reused.assignment.reservations).toHaveLength(3)
      const current = await freshDriver(driverA, scenario); expect(current.execution).toEqual(result.execution); expect(current.completion).toEqual(result.completion); expect(isDeepStrictEqual(Object.fromEntries(safeFields.map((key) => [key, current[key]])), Object.fromEntries(safeFields.map((key) => [key, scenario.source![key]])))).toBe(true); await expect(driverA.getByRole('heading', { name: '本人操作結果尚未確認', exact: true })).toBeVisible()
      const oldReads: string[] = [], oldListener = (request: Request) => { const path = new URL(request.url()).pathname; if (request.method() === 'GET' && (path === `/api/v1${base}` || path.startsWith(`/api/v1${base}/progress`) || path.startsWith(`/api/v1${base}/evidence`) || path.startsWith(`/api/v1${base}/exceptions`))) oldReads.push(path) }; driverA.on('request', oldListener)
      try { expect(isDeepStrictEqual(await retryUnknown(driverA, completeLoss), result)).toBe(true); await freshDriver(driverA, scenario); await expect(driverA.getByRole('region', { name: '本人任務完成紀錄', exact: true })).toBeVisible(); await sourceUI(driverA, scenario); await screenshot(driverA, info, 'driver-ui-completed-after-closed-and-reuse.png'); expect(oldReads.length).toBe(0) } finally { driverA.off('request', oldListener) }
      for (const path of [base, `${base}/progress-policy`, `${base}/evidence`, `${base}/exceptions`]) rejected(await api(driverA, path), 404, 'E0404')
      for (const kind of ['upload', 'progress', 'report', 'supplement', 'submit'] as const) await expect(driverA.getByRole('button', { name: buttons[kind].start, exact: true })).toHaveCount(0)
      const originalOrder = original(await api<Order>(page, `/orders/${scenario.order.id}`)); expect(originalOrder.lifecycleStatus).toBe('CLOSED'); expect(originalOrder.version).toBe(scenario.order.version + 1); expect(originalOrder.tasks).toEqual(scenario.order.tasks)
      const unbound = ok(await api<Driver>(page, `/fms/drivers/${scenario.driver.id}`, 'PATCH', { version: scenario.driver.version, accountId: null })); await driverA.bringToFront(); await driverA.getByRole('button', { name: '重新讀取本人任務詳情', exact: true }).click(); await expect(driverA.getByRole('region', { name: '目前本人任務資料', exact: true })).toHaveCount(0); await expect(driverA.locator('img[src^="blob:"]')).toHaveCount(0)
      ok(await api(page, `/fms/drivers/${scenario.driver.id}`, 'PATCH', { version: unbound.version, accountId: fixture.driverB.id })); const rebound = await openTask(driverB, scenario); expect(rebound.completion).toEqual(result.completion); await sourceUI(driverB, scenario); await expect(driverB.getByRole('region', { name: '本次本人操作收據', exact: true })).toHaveCount(0); await expect(driverB.getByRole('heading', { name: '本人操作結果尚未確認', exact: true })).toHaveCount(0)
      // No completed UI offers a new command. This negative actor-scope probe
      // reuses only an in-memory key; it does not transfer a UI capability.
      const actorMiss = await completeLoss.actorMiss(driverB); rejected(actorMiss, 409, 'E2001', 'VERSION_CONFLICT'); expect(actorMiss.data.actualVersion).toBe(4)
      const actor = ok(await api<Account[]>(page, '/upms/accounts')).find((entry) => entry.id === fixture.driverB.id)!; ok(await api(page, `/upms/accounts/${actor.id}`, 'PATCH', { version: actor.version, displayName: actor.displayName, role: actor.role, enabled: false }))
      await driverB.bringToFront(); await driverB.getByRole('button', { name: '重新讀取本人任務詳情', exact: true }).click(); await expect(driverB.getByRole('heading', { name: '登入工作空間', exact: true })).toBeVisible(); await expect(driverB.getByLabel('原始取貨地址', exactOptions)).toHaveCount(0); await expect(driverB.locator('img[src^="blob:"]')).toHaveCount(0)
      await driverA.bringToFront(); await driverA.getByRole('button', { name: '登出', exact: true }).click(); await expect(driverA.getByRole('heading', { name: '登入工作空間', exact: true })).toBeVisible(); await expect(driverA.getByLabel('原始取貨地址', exactOptions)).toHaveCount(0); await expect(driverA.getByRole('region', { name: '本次本人操作收據', exact: true })).toHaveCount(0)
    } finally { await completeLoss.dispose() }
  } finally { await Promise.all(contexts.map((context) => context.close())) }
})
