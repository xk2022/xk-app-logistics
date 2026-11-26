import { randomUUID } from 'node:crypto'
import { readFileSync, writeFileSync } from 'node:fs'
import { request as httpsRequest } from 'node:https'
import { isDeepStrictEqual } from 'node:util'
import { expect, test, type Browser, type BrowserContext, type Page, type Request, type Response, type Route, type TestInfo } from '@playwright/test'
import { dispatchUiFixtureFor, signIn, type Credential } from './fixtures'

// Separate actors preserve every original 26-case scenario, including the API
// group's intentional revocations. Business fixtures use only normal real APIs.
test.describe.configure({ retries: 0 })
type Body = Record<string, unknown>
interface PageData<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }
interface Reply<T> { status: number; code: string; data: T; payload: Body; replayed: string | null }
interface Customer { id: string; customerCode: string; name: string; version: number }
interface Driver { id: string; driverCode: string; name: string; accountId: string | null; version: number }
interface Vehicle { id: string; plateNo: string; version: number }
interface Order { id: string; orderNo: string; version: number; acceptanceStatus: string; tasks: { id: string; taskNo: string; clientRef: string }[] }
interface Reservation { resourceId: string; position: string; releasedAt: string | null }
interface Assignment extends Body {
  id: string; taskId: string; status: string; version: number; assignedGeneration: number; endedGeneration: number | null
  driver: { id: string; name: string }; mainVehicle: { id: string; plateNo: string }; trailer: { id: string; plateNo: string } | null
  source: Body; policyMode: string; endReason: string | null; reservations: Reservation[]
}
interface Command { taskId: string; generation: number; assignment: Assignment }
interface Summary { taskId: string; taskNo: string; generation: number; currentAssignmentId: string | null; blockingReasons: string[] }
interface Detail { task: Summary; current: Assignment | null; history: PageData<Assignment> }
interface Account { id: string; role: string; displayName: string; enabled: boolean; version: number }
interface MyTask extends Body { assignmentId: string; taskId: string; taskNo: string }
type TmsSchema = 'TaskPageEnvelope' | 'DispatchDetailEnvelope' | 'CommandResultEnvelope' | 'ResourcePageEnvelope' | 'MyTaskPageEnvelope' | 'ErrorEnvelope'
interface Captured { schema: string; value: unknown }
type Operation = '指派' | '改派' | '撤派'
const PRIVATE_ORDER_NOTE = '整單私有合成備註不可出現在司機本人工作台'

class ResponseCollector {
  readonly tms: Captured[] = []
  readonly orders: Captured[] = []
  readonly acceptances: Captured[] = []
  private readonly pages = new Set<Page>()
  private readonly pending = new Set<Promise<void>>()
  private readonly capture = (response: Response) => {
    const path = new URL(response.url()).pathname, method = response.request().method()
    let schema: string | undefined, destination: Captured[] | undefined
    if (path === '/api/v1/tms/tasks' && method === 'GET') schema = 'TaskPageEnvelope'
    else if (/^\/api\/v1\/tms\/tasks\/[0-9a-f-]{36}\/dispatch$/i.test(path)) schema = method === 'GET' ? 'DispatchDetailEnvelope' : method === 'POST' ? 'CommandResultEnvelope' : undefined
    else if (/^\/api\/v1\/tms\/dispatches\/[0-9a-f-]{36}\/(revoke|reassign)$/i.test(path) && method === 'POST') schema = 'CommandResultEnvelope'
    else if (path === '/api/v1/tms/resources' && method === 'GET') schema = 'ResourcePageEnvelope'
    else if (path === '/api/v1/tms/my-tasks' && method === 'GET') schema = 'MyTaskPageEnvelope'
    if (schema) destination = this.tms
    else if (/^\/api\/v1\/orders\/[0-9a-f-]{36}\/accept$/i.test(path) && method === 'POST') { schema = 'AcceptedOrderEnvelope'; destination = this.acceptances }
    else if (path === '/api/v1/orders' && ['GET', 'POST'].includes(method)) { schema = method === 'GET' ? 'OrderPageEnvelope' : 'OrderEnvelope'; destination = this.orders }
    else if (/^\/api\/v1\/orders\/[0-9a-f-]{36}$/i.test(path) && ['GET', 'PUT'].includes(method)) { schema = 'OrderEnvelope'; destination = this.orders }
    else if (/^\/api\/v1\/orders\/[0-9a-f-]{36}\/acceptance-check$/i.test(path) && method === 'GET') { schema = 'AcceptanceCheckEnvelope'; destination = this.orders }
    if (!schema || !destination) return
    const responseSchema = response.status() >= 400 ? 'ErrorEnvelope' : schema, target = destination
    const pending = (async () => {
      try { target.push({ schema: responseSchema, value: await response.json() }) }
      catch { /* An aborted or unreadable body is not evidence of a server result. */ }
    })()
    this.pending.add(pending)
    void pending.then(() => this.pending.delete(pending), () => this.pending.delete(pending))
  }
  watch(page: Page): void { if (!this.pages.has(page)) { this.pages.add(page); page.on('response', this.capture) } }
  recordTms(schema: TmsSchema, value: Body): void { this.tms.push({ schema, value }) }
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
  // All evidence is synthetic public response bodies only. Never serialize
  // authentication material, request headers, passwords or idempotency keys.
  writeFileSync(info.outputPath('tms-contract-responses.json'), JSON.stringify(collector.tms))
  writeFileSync(info.outputPath('order-contract-responses.json'), JSON.stringify(collector.orders))
  writeFileSync(info.outputPath('acceptance-contract-responses.json'), JSON.stringify(collector.acceptances))
  collectors.delete(info.testId)
})

async function api<T = Body>(page: Page, path: string, method = 'GET', body?: unknown): Promise<Reply<T>> {
  return page.evaluate(async ({ path, method, body }) => {
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (method !== 'GET') {
      const csrf = (await (await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' })).json()).data
      headers[csrf.headerName] = csrf.token; headers['Content-Type'] = 'application/json'
      if (path.startsWith('/tms/') || path.startsWith('/orders')) headers['Idempotency-Key'] = crypto.randomUUID()
    }
    const response = await fetch(`/api/v1${path}`, { method, credentials: 'same-origin', headers,
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
    const payload = await response.json()
    return { status: response.status, code: payload.code, data: payload.data, payload, replayed: response.headers.get('Idempotency-Replayed') }
  }, { path, method, body })
}
function success<T>(reply: Reply<T>, status = 200): T { expect(reply.status).toBe(status); expect(reply.code).toBe('0000'); return reply.data }
async function newContext(browser: Browser, info: TestInfo): Promise<BrowserContext> {
  const use = info.project.use
  const context = await browser.newContext({ baseURL: use.baseURL, ignoreHTTPSErrors: use.ignoreHTTPSErrors, viewport: use.viewport,
    screen: use.screen, deviceScaleFactor: use.deviceScaleFactor, isMobile: use.isMobile, hasTouch: use.hasTouch, userAgent: use.userAgent })
  const collector = collectors.get(info.testId)
  if (collector) context.on('page', (page) => collector.watch(page))
  return context
}
async function login(page: Page, credential: Credential): Promise<void> {
  await page.goto('/'); expect(new URL(page.url()).protocol).toBe('https:'); await signIn(page, credential)
}
async function navigate(page: Page, title: '訂單工作台' | '調度工作台' | '本人指派任務'): Promise<void> {
  await page.bringToFront()
  await page.getByRole('navigation', { name: '主要導覽', exact: true }).getByRole('button', { name: title === '本人指派任務' ? '本人運輸任務' : title, exact: true }).click()
  await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
}
function draft(customerId: string, scope: string, suffix: string): Body {
  return { type: 'LOCAL', customerId, customerReference: `合成UI受理 ${suffix}`, remark: PRIVATE_ORDER_NOTE,
    items: [{ clientRef: 'item-ui', sequence: 1, itemKind: 'CARGO', packageQty: 7, grossWeight: '0.0000000000000000001', cbm: '0' }],
    tasks: Array.from({ length: 6 }, (_, index) => ({ clientRef: `ui-task-${index + 1}`, itemRef: 'item-ui', taskKind: `SYNTHETIC_TMS_${scope}_UI_${index + 1}`,
      pickupName: `合成UI起點 ${suffix} 第${index + 1}段`, pickupAddress: `合成UI取貨地點 ${suffix} 第${index + 1}段（非真實地址）`,
      deliveryName: `合成UI終點 ${suffix} 第${index + 1}段`, deliveryAddress: `合成UI送貨地點 ${suffix} 第${index + 1}段（非真實地址）`,
      siteContact: { name: `合成UI聯絡 ${suffix} 第${index + 1}段`, phone: `SYNTHETIC-${suffix}-${index + 1}` },
      plannedStartAt: '2026-12-01T08:00:00.123456+08:00', plannedEndAt: '2026-12-01T10:00:00.123456+08:00',
      requiredTask: true, predecessorRefs: index === 2 ? ['ui-task-1'] : [] })) }
}
async function acceptThroughOrderUi(page: Page, customer: Customer, order: Order): Promise<Order> {
  await navigate(page, '訂單工作台')
  await page.getByRole('button', { name: '選擇篩選客戶', exact: true }).click()
  const picker = page.getByRole('region', { name: '選擇篩選客戶', exact: true })
  await picker.getByLabel('篩選客戶搜尋', { exact: true }).fill(customer.customerCode)
  await picker.getByRole('button', { name: '查詢篩選客戶', exact: true }).click()
  await picker.getByRole('button', { name: `篩選客戶：${customer.name}（${customer.customerCode}）`, exact: true }).click()
  const listed = page.waitForResponse((response) => { const url = new URL(response.url()); return url.pathname === '/api/v1/orders' && url.searchParams.get('customerId') === customer.id })
  await page.getByRole('form', { name: '訂單查詢', exact: true }).getByRole('button', { name: '查詢訂單', exact: true }).click()
  expect((await listed).status()).toBe(200)
  await page.getByRole('button', { name: `編輯訂單 ${order.orderNo}`, exact: true }).click()
  await expect(page.getByText(`已保存版本 ${order.version}`, { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '正式受理訂單', exact: true }).click()
  const accepted = page.waitForResponse((response) => response.request().method() === 'POST' && new URL(response.url()).pathname === `/api/v1/orders/${order.id}/accept`)
  await page.getByRole('button', { name: '確認正式受理', exact: true }).click()
  const response = await accepted; expect(response.status()).toBe(200)
  const result = await response.json(); expect(result.code).toBe('OK'); expect(result.data.acceptanceStatus).toBe('ACCEPTED')
  await expect(page.getByText('已受理・待派車', { exact: true })).toBeVisible()
  return result.data as Order
}

interface Forwarded { status: number; payload: Body; replayed?: string }
const safeForwardFailure = 'The controlled dispatch request could not be completed safely.'
/** A Node HTTPS hop avoids APIRequestContext logging authentication headers on
 * failure. Only public synthetic response bodies escape this function. */
async function forward(request: Request, origin: string, signal: AbortSignal, invalidCsrf = false): Promise<Forwarded> {
  try {
    const url = new URL(request.url())
    if (url.origin !== origin || url.protocol !== 'https:' || request.method() !== 'POST'
      || !(url.pathname.startsWith('/api/v1/tms/') || url.pathname === '/api/v1/auth/logout')) throw new Error(safeForwardFailure)
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
          try {
            const value: unknown = JSON.parse(Buffer.concat(chunks).toString('utf8'))
            if (value === null || typeof value !== 'object' || Array.isArray(value)) { fail(); return }
            const payload = value as Body
            if (typeof payload.code !== 'string' || typeof payload.timestamp !== 'string') { fail(); return }
            finish(() => resolve({ status: response.statusCode!, payload, replayed: response.headers['idempotency-replayed'] as string | undefined }))
          } catch { fail() }
        })
      })
      upstream.on('error', fail); deadline = setTimeout(cancel, 15_000)
      signal.addEventListener('abort', cancel, { once: true }); if (signal.aborted) { cancel(); return }
      upstream.end(body)
    })
  } catch { throw new Error(safeForwardFailure) }
}

async function loseCommittedResponse(page: Page, path: string, taskId: string, expectedGeneration: number, collector: ResponseCollector, csrfOnFirstRetry: boolean) {
  const origin = new URL(page.url()).origin, fullPath = `/api/v1${path}`
  let attempts = 0, matching = false; let firstKey: string | undefined; let firstBody: Buffer | null = null
  let resolve!: (reply: Forwarded) => void; let reject!: (failure: Error) => void
  const committed = new Promise<Forwarded>((yes, no) => { resolve = yes; reject = no }); void committed.catch(() => undefined)
  const cancellation = new AbortController(), active = new Set<Promise<void>>()
  const matches = (url: URL) => url.origin === origin && url.pathname === fullPath
  const setupDeadline = setTimeout(() => { cancellation.abort(); reject(new Error(safeForwardFailure)) }, 20_000)
  const handle = async (route: Route) => {
    try {
      const request = route.request()
      if (request.method() !== 'POST') { await route.continue(); return }
      attempts++
      const key = (await request.allHeaders())['idempotency-key'], body = request.postDataBuffer()
      if (attempts === 1) {
        firstKey = key; firstBody = body
        const reply = await forward(request, origin, cancellation.signal), result = reply.payload.data as Command | undefined
        if (reply.status !== 200 || reply.payload.code !== '0000' || reply.replayed !== 'false' || !result || result.taskId !== taskId || result.generation !== expectedGeneration + 1) throw new Error(safeForwardFailure)
        collector.recordTms('CommandResultEnvelope', reply.payload)
        await route.abort('failed'); clearTimeout(setupDeadline); resolve(reply)
      } else {
        const same = Boolean(firstKey && key === firstKey && firstBody && body && firstBody.equals(body))
        matching = attempts === 2 ? same : matching && same
        if (attempts === 2 && csrfOnFirstRetry) {
          const refusal = await forward(request, origin, cancellation.signal, true)
          if (refusal.status !== 403 || refusal.payload.code !== 'E0403' || (refusal.payload.data as Body | null)?.reason !== 'CSRF_INVALID') throw new Error(safeForwardFailure)
          await route.fulfill({ status: refusal.status, contentType: 'application/json', body: JSON.stringify(refusal.payload) })
        } else await route.continue()
      }
    } catch { clearTimeout(setupDeadline); reject(new Error(safeForwardFailure)); try { await route.abort('failed') } catch { /* Closing page. */ } }
  }
  const handler = (route: Route) => { const pending = handle(route); active.add(pending); void pending.then(() => active.delete(pending), () => active.delete(pending)); return pending }
  await page.route(matches, handler)
  return { committed, attempts: () => attempts, matching: () => matching, dispose: async () => {
    clearTimeout(setupDeadline); cancellation.abort(); reject(new Error(safeForwardFailure)); await Promise.allSettled([...active]); await page.unroute(matches, handler)
  } }
}

async function filterBoard(page: Page, order: Order, taskNo?: string): Promise<void> {
  await page.bringToFront()
  const back = page.getByRole('button', { name: '返回調度列表', exact: true })
  if (await back.isVisible()) await back.click()
  const query = taskNo ?? order.orderNo
  await page.getByLabel('任務搜尋', { exact: true }).fill(query)
  await page.getByLabel('任務狀態篩選', { exact: true }).selectOption('ALL')
  const queried = page.waitForResponse((response) => { const url = new URL(response.url()); return url.pathname === '/api/v1/tms/tasks' && url.searchParams.get('q') === query && url.searchParams.get('state') === 'ALL' })
  await page.getByRole('button', { name: '查詢任務', exact: true }).click()
  expect((await queried).status()).toBe(200)
}
async function openTask(page: Page, order: Order, task: Order['tasks'][number]): Promise<Detail> {
  await filterBoard(page, order, task.taskNo)
  const loaded = page.waitForResponse((response) => response.request().method() === 'GET' && new URL(response.url()).pathname === `/api/v1/tms/tasks/${task.id}/dispatch`)
  await page.getByRole('button', { name: `開啟派工 ${task.taskNo}`, exact: true }).click()
  const response = await loaded; expect(response.status()).toBe(200)
  await expect(page.getByRole('button', { name: '重新讀取目前派工', exact: true })).toBeEnabled()
  return (await response.json()).data as Detail
}
async function reloadCurrent(page: Page, taskId: string): Promise<Detail> {
  await page.bringToFront()
  const loaded = page.waitForResponse((response) => response.request().method() === 'GET' && new URL(response.url()).pathname === `/api/v1/tms/tasks/${taskId}/dispatch`)
  await page.getByRole('button', { name: '重新讀取目前派工', exact: true }).click()
  const response = await loaded; expect(response.status()).toBe(200)
  const detail = (await response.json()).data as Detail
  await expect(page.getByRole('button', { name: '重新讀取目前派工', exact: true })).toBeEnabled()
  const adoption = page.getByRole('button', { name: '採用最新派工資料', exact: true })
  if (await adoption.isVisible()) await adoption.click()
  return detail
}
async function searchResource(page: Page, label: '司機' | '主車' | '拖板', query: string): Promise<void> {
  await page.bringToFront()
  const position = { 司機: 'DRIVER', 主車: 'MAIN', 拖板: 'TRAILER' }[label]
  await page.getByLabel(`搜尋${label}`, { exact: true }).fill(query)
  const loaded = page.waitForResponse((response) => { const url = new URL(response.url()); return url.pathname === '/api/v1/tms/resources' && url.searchParams.get('position') === position && url.searchParams.get('q') === query })
  await page.getByRole('button', { name: `查詢${label}`, exact: true }).click()
  expect((await loaded).status()).toBe(200)
}
async function chooseResources(page: Page, driver: Driver, main: Vehicle, trailer: Vehicle | null): Promise<void> {
  for (const [label, query, id] of [['司機', driver.driverCode, driver.id], ['主車', main.plateNo, main.id], ...(trailer ? [['拖板', trailer.plateNo, trailer.id]] : [])] as ['司機' | '主車' | '拖板', string, string][]) {
    await searchResource(page, label, query)
    const select = page.getByLabel(`選擇${label}`, { exact: true })
    await expect(select.locator(`option[value="${id}"]`)).toBeEnabled()
    await select.selectOption(id)
  }
  if (!trailer) await page.getByLabel('選擇拖板', { exact: true }).selectOption('')
}
async function reviewCommand(page: Page, operation: Operation, driver?: Driver, main?: Vehicle, trailer: Vehicle | null = null, reason = ''): Promise<void> {
  await page.bringToFront()
  await page.getByRole('button', { name: `${operation}任務`, exact: true }).click()
  if (operation !== '撤派') await chooseResources(page, driver!, main!, trailer)
  if (operation !== '指派') await page.getByLabel(`${operation}原因`, { exact: true }).fill(reason)
  await page.getByRole('button', { name: `檢視${operation}確認`, exact: true }).click()
  await expect(page.getByRole('button', { name: `確認${operation}`, exact: true })).toBeEnabled()
}
async function submitCommand(page: Page, operation: Operation, path: string, status = 200): Promise<Reply<Command>> {
  const submitted = page.waitForResponse((response) => response.request().method() === 'POST' && new URL(response.url()).pathname === `/api/v1${path}`)
  const confirmation = page.getByRole('button', { name: `確認${operation}`, exact: true })
  await confirmation.focus(); await confirmation.press('Enter')
  const response = await submitted; expect(response.status()).toBe(status)
  const payload = await response.json()
  if (status === 200) {
    expect(payload.code).toBe('0000'); expect(await response.headerValue('Idempotency-Replayed')).toBe('false')
    await expect(page.getByRole('heading', { name: '本次操作收據', exact: true })).toBeVisible()
  }
  return { status: response.status(), code: payload.code, data: payload.data as Command, payload, replayed: await response.headerValue('Idempotency-Replayed') }
}
async function lostCommand(page: Page, info: TestInfo, operation: Operation, path: string, taskId: string, generation: number, csrf: boolean): Promise<Command> {
  const fault = await loseCommittedResponse(page, path, taskId, generation, collectors.get(info.testId)!, csrf)
  try {
    await page.getByRole('button', { name: `確認${operation}`, exact: true }).click()
    const committed = await fault.committed, result = committed.payload.data as Command
    await expect(page.getByRole('heading', { name: '操作結果尚未確認', exact: true })).toBeVisible()
    for (const label of ['選擇司機', '選擇主車', '選擇拖板', '改派原因', '撤派原因']) {
      const field = page.getByLabel(label, { exact: true })
      if (await field.isVisible()) await expect(field).toBeDisabled()
    }
    if (operation === '指派') await evidence(page, info, 'dispatch-ui-unknown.png')
    if (csrf) {
      const refused = page.waitForResponse((response) => new URL(response.url()).pathname === `/api/v1${path}` && response.status() === 403)
      await page.getByRole('button', { name: '以原請求重試操作', exact: true }).click(); await refused
      await expect(page.getByRole('heading', { name: '操作結果尚未確認', exact: true })).toBeVisible()
      await expect(page.getByRole('button', { name: '以原請求重試操作', exact: true })).toBeEnabled()
      expect(fault.attempts()).toBe(2)
    }
    const replayed = page.waitForResponse((response) => new URL(response.url()).pathname === `/api/v1${path}` && response.status() === 200)
    await page.getByRole('button', { name: '以原請求重試操作', exact: true }).click()
    const response = await replayed
    expect(await response.headerValue('Idempotency-Replayed')).toBe('true')
    expect(isDeepStrictEqual((await response.json()).data, result)).toBe(true)
    expect(fault.attempts()).toBe(csrf ? 3 : 2); expect(fault.matching()).toBe(true)
    await expect(page.getByRole('heading', { name: '本次操作收據', exact: true })).toBeVisible()
    await expect(page.getByText(/歷史/).first()).toBeVisible()
    return result
  } finally { await fault.dispose() }
}
async function refreshOwn(page: Page, expected: string[], status = 200): Promise<void> {
  await page.bringToFront()
  await expect(page.getByRole('button', { name: '重新讀取本人任務', exact: true })).toBeEnabled()
  const loaded = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/v1/tms/my-tasks' && response.request().method() === 'GET')
  await page.getByRole('button', { name: '重新讀取本人任務', exact: true }).click()
  const response = await loaded; expect(response.status()).toBe(status)
  if (status === 200) {
    const payload = await response.json(), data = payload.data as PageData<MyTask>
    expect(payload.code).toBe('0000'); expect(data.totalElements).toBe(expected.length)
    expect(data.content.map((task) => task.assignmentId).sort()).toEqual([...expected].sort())
  }
  await expect(page.getByRole('article', { name: /^本人任務 / })).toHaveCount(expected.length)
}
async function noPrivateDriverDetails(page: Page, customer: Customer, main: Vehicle): Promise<void> {
  await expect(page.getByText(PRIVATE_ORDER_NOTE, { exact: true })).toHaveCount(0)
  await expect(page.getByText(customer.name, { exact: true })).toHaveCount(0)
  await expect(page.getByText(main.plateNo, { exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /^(指派任務|改派任務|撤派任務|出發|開始運送|完成|回報|照片)$/ })).toHaveCount(0)
}
async function expandHistoryRows(page: Page): Promise<void> {
  await page.bringToFront()
  const summaries = page.getByRole('region', { name: '派工歷史', exact: true }).locator('summary').filter({ hasText: /^指派版本 / })
  await expect(summaries.first()).toBeVisible()
  for (const summary of await summaries.all()) await summary.click()
}
async function evidence(page: Page, info: TestInfo, name: string): Promise<void> {
  await page.bringToFront()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true)
  expect(await page.locator('input:not([type="hidden"]),textarea,select').evaluateAll((values) => values.filter((value) => value.getClientRects().length > 0).every((value) => Boolean((value as HTMLInputElement).labels?.length || value.getAttribute('aria-label') || value.getAttribute('aria-labelledby'))))).toBe(true)
  await page.evaluate(() => { if (document.activeElement instanceof HTMLElement) document.activeElement.blur(); window.scrollTo({ top: 0, left: 0, behavior: 'instant' }) })
  await page.screenshot({ path: info.outputPath(name), fullPage: true })
}

async function verifyTaskPaging(page: Page, order: Order): Promise<void> {
  await filterBoard(page, order)
  await page.getByLabel('每頁任務筆數', { exact: true }).selectOption('5')
  const firstPage = page.waitForResponse((response) => { const url = new URL(response.url()); return url.pathname === '/api/v1/tms/tasks' && url.searchParams.get('q') === order.orderNo && url.searchParams.get('size') === '5' && (url.searchParams.get('page') ?? '0') === '0' })
  await page.getByRole('button', { name: '查詢任務', exact: true }).click()
  const first = (await (await firstPage).json()).data as PageData<Summary>
  expect(first.totalElements).toBe(6); expect(first.totalPages).toBe(2); expect(first.content).toHaveLength(5)
  const nextPage = page.waitForResponse((response) => { const url = new URL(response.url()); return url.pathname === '/api/v1/tms/tasks' && url.searchParams.get('q') === order.orderNo && url.searchParams.get('page') === '1' })
  await page.getByRole('button', { name: '下一頁任務', exact: true }).click()
  const next = (await (await nextPage).json()).data as PageData<Summary>
  expect(next.content).toHaveLength(1); expect(next.totalElements).toBe(6)
  expect(first.content.some((task) => task.taskId === next.content[0].taskId)).toBe(false)
}

async function holdRealLogout(page: Page) {
  const origin = new URL(page.url()).origin, cancellation = new AbortController()
  let release!: () => void, resolve!: () => void, reject!: (failure: Error) => void
  const released = new Promise<void>((yes) => { release = yes }), forwarded = new Promise<void>((yes, no) => { resolve = yes; reject = no })
  void forwarded.catch(() => undefined)
  const matches = (url: URL) => url.origin === origin && url.pathname === '/api/v1/auth/logout'
  const handler = async (route: Route) => {
    try {
      const result = await forward(route.request(), origin, cancellation.signal)
      if (result.status !== 200 || result.payload.code !== '0000') throw new Error(safeForwardFailure)
      resolve(); await released
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(result.payload) })
    } catch { reject(new Error(safeForwardFailure)); try { await route.abort('failed') } catch { /* Closing page. */ } }
  }
  await page.route(matches, handler)
  return { forwarded, release, dispose: async () => { release(); cancellation.abort(); await page.unroute(matches, handler) } }
}

test('Dispatch UI joins accepted orders, explicit resource commands, replay and current-driver mobile privacy over TLS', async ({ page, browser }, info) => {
  test.setTimeout(360_000)
  const fixture = dispatchUiFixtureFor(info.project.name), scope = fixture.scope, suffix = `${scope}-UI`
  const contexts = await Promise.all([newContext(browser, info), newContext(browser, info), newContext(browser, info)])
  try {
    const [dispatcher, driverA, driverB] = await Promise.all(contexts.map((context) => context.newPage()))
    await login(page, fixture.admin); await login(dispatcher, fixture.dispatcher)
    await login(driverA, fixture.driverA); await login(driverB, fixture.driverB)
    for (const driver of [driverA, driverB]) await expect(driver.getByRole('navigation', { name: '主要導覽', exact: true }).getByRole('button', { name: '調度工作台', exact: true })).toHaveCount(0)

    const customer = success(await api<Customer>(page, '/crm/customers', 'POST', { customerCode: `UI_TMS_${scope}_${randomUUID().slice(0, 8).toUpperCase()}`, name: `合成調度UI客戶 ${scope}` }), 201)
    const personA = success(await api<Driver>(page, '/fms/drivers', 'POST', { driverCode: `SYNTHETIC_TMS_${scope}_D1001`, name: `合成UI司機甲 ${scope}`, accountId: fixture.driverA.id, dutyStatus: 'ON_DUTY' }), 201)
    const personB = success(await api<Driver>(page, '/fms/drivers', 'POST', { driverCode: `SYNTHETIC_TMS_${scope}_D1002`, name: `合成UI司機乙 ${scope}`, accountId: fixture.driverB.id, dutyStatus: 'ON_DUTY' }), 201)
    const outside = success(await api<Driver>(page, '/fms/drivers', 'POST', { driverCode: `UNAPPROVED_UI_${scope}`, name: `合成未核准UI司機 ${scope}`, dutyStatus: 'ON_DUTY' }), 201)
    const makeVehicle = async (type: string, name: string) => success(await api<Vehicle>(page, '/fms/vehicles', 'POST', { plateNo: `UI-${scope[0]}-${randomUUID().slice(0, 8)}-${name}`, vehicleType: type, capacityTon: '1.00' }), 201)
    const mainA = await makeVehicle(fixture.vehicleTypes.main.id, 'A'), mainB = await makeVehicle(fixture.vehicleTypes.main.id, 'B')
    const trailerA = await makeVehicle(fixture.vehicleTypes.trailer.id, 'A'), trailerB = await makeVehicle(fixture.vehicleTypes.trailer.id, 'B')
    const created = await api<Order>(page, '/orders', 'POST', draft(customer.id, scope, suffix))
    expect(created.status).toBe(201); expect(created.code).toBe('OK')
    await navigate(page, '調度工作台'); await filterBoard(page, created.data)
    await expect(page.getByRole('button', { name: /^開啟派工 / })).toHaveCount(0)
    const order = await acceptThroughOrderUi(page, customer, created.data)
    const taskA = order.tasks.find((task) => task.clientRef === 'ui-task-1')!
    const taskB = order.tasks.find((task) => task.clientRef === 'ui-task-2')!
    const dependent = order.tasks.find((task) => task.clientRef === 'ui-task-3')!
    const routeA = `合成UI取貨地點 ${suffix} 第1段（非真實地址）`, routeB = `合成UI取貨地點 ${suffix} 第2段（非真實地址）`
    const contactA = `合成UI聯絡 ${suffix} 第1段`, contactB = `合成UI聯絡 ${suffix} 第2段`

    await navigate(driverA, '本人指派任務'); await navigate(driverB, '本人指派任務')
    await refreshOwn(driverA, []); await refreshOwn(driverB, [])
    await navigate(page, '調度工作台'); await navigate(dispatcher, '調度工作台')
    await verifyTaskPaging(page, order)
    const unassigned = await openTask(page, order, taskA)
    expect(unassigned.task.generation).toBe(0); expect(unassigned.current).toBeNull()
    await reviewCommand(page, '指派', personA, mainA, trailerA)
    await evidence(page, info, 'dispatch-ui-confirmation.png')
    const first = success(await submitCommand(page, '指派', `/tms/tasks/${taskA.id}/dispatch`))
    expect(first.generation).toBe(1); expect(first.assignment.reservations).toHaveLength(3)
    const current = await reloadCurrent(page, taskA.id); expect(current.current!.id).toBe(first.assignment.id)
    await evidence(page, info, 'dispatch-ui-current.png')
    await refreshOwn(driverB, []); await refreshOwn(driverA, [first.assignment.id])
    await expect(driverA.getByText(routeA, { exact: true })).toBeVisible()
    await noPrivateDriverDetails(driverA, customer, mainA)
    await evidence(driverA, info, 'dispatch-ui-driver.png')

    // Both management roles work through the UI. The first browser deliberately
    // retains generation 1 while the second commits generation 2.
    await openTask(dispatcher, order, taskA)
    const changeReason = `合成UI正常改派 ${scope}`
    await reviewCommand(dispatcher, '改派', personB, mainB, trailerB, changeReason)
    const changed = success(await submitCommand(dispatcher, '改派', `/tms/dispatches/${first.assignment.id}/reassign`))
    expect(changed.generation).toBe(2); await reloadCurrent(dispatcher, taskA.id)
    await refreshOwn(driverA, []); await expect(driverA.getByText(routeA, { exact: true })).toHaveCount(0)
    await refreshOwn(driverB, [changed.assignment.id]); await noPrivateDriverDetails(driverB, customer, mainB)
    const staleReason = `合成UI保留過期撤派原因 ${scope}`
    await reviewCommand(page, '撤派', undefined, undefined, null, staleReason)
    const conflict = await submitCommand(page, '撤派', `/tms/dispatches/${first.assignment.id}/revoke`, 409)
    expect(conflict.code).toBe('E1601')
    await expect(page.getByLabel('撤派原因', { exact: true })).toHaveValue(staleReason)
    await expect(page.getByRole('button', { name: '檢視撤派確認', exact: true })).toBeDisabled()
    const refreshed = await reloadCurrent(page, taskA.id); expect(refreshed.current!.id).toBe(changed.assignment.id)
    await reviewCommand(page, '撤派', undefined, undefined, null, `合成UI明確重讀後撤派 ${scope}`)
    const revoked = success(await submitCommand(page, '撤派', `/tms/dispatches/${changed.assignment.id}/revoke`))
    expect(revoked.generation).toBe(3); await reloadCurrent(page, taskA.id)
    await refreshOwn(driverB, []); await expect(driverB.getByText(contactA, { exact: true })).toHaveCount(0)
    const history = page.getByRole('region', { name: '派工歷史', exact: true })
    await expandHistoryRows(page)
    await expect(history).toContainText(changeReason)
    await expect(history.getByText(`結束原因：${changeReason}`, { exact: true }).first()).toBeVisible()
    await expect(history).toContainText('已由新派工取代')
    await expect(history).toContainText('已撤派')

    // A real competing assignment drives disabled candidate options. It is not
    // removed from the server's candidate page or described as formally eligible.
    const occupied = success(await api<Command>(page, `/tms/tasks/${taskB.id}/dispatch`, 'POST', { expectedGeneration: 0, driverId: personA.id, mainVehicleId: mainA.id, trailerId: trailerA.id }))
    await page.getByRole('button', { name: '指派任務', exact: true }).click()
    for (const [label, query, id] of [['司機', personA.driverCode, personA.id], ['主車', mainA.plateNo, mainA.id]] as const) {
      await searchResource(page, label, query)
      const option = page.getByLabel(`選擇${label}`, { exact: true }).locator(`option[value="${id}"]`)
      await expect(option).toBeDisabled(); await expect(option).toContainText('資源已被占用')
    }
    await searchResource(page, '司機', outside.driverCode)
    const unapproved = page.getByLabel('選擇司機', { exact: true }).locator(`option[value="${outside.id}"]`)
    await expect(unapproved).toBeDisabled(); await expect(unapproved).toContainText('派工政策尚未核准')
    await page.getByRole('button', { name: '檢視指派確認', exact: true }).click()
    await expect(page.getByRole('alert').filter({ hasText: 'E0400' })).toBeVisible()
    await expect(page.getByRole('button', { name: '確認指派', exact: true })).toHaveCount(0)
    await reloadCurrent(page, taskA.id)
    await openTask(page, order, dependent)
    await expect(page.getByText('前置任務尚未就緒', { exact: true }).first()).toBeVisible()
    await expect(page.getByRole('button', { name: '指派任務', exact: true })).toBeDisabled()
    success(await api(page, `/tms/dispatches/${occupied.assignment.id}/revoke`, 'POST', { expectedGeneration: 1, reason: '合成UI占用提示驗收釋放' }))
    await openTask(page, order, taskA)

    // Each command truly commits, loses its response and is recovered explicitly.
    // The assign retry is first refused by real CSRF middleware and must stay frozen.
    await reviewCommand(page, '指派', personA, mainA, trailerA)
    const recoveredAssign = await lostCommand(page, info, '指派', `/tms/tasks/${taskA.id}/dispatch`, taskA.id, 3, true)
    expect(recoveredAssign.generation).toBe(4)
    await reloadCurrent(page, taskA.id); await refreshOwn(driverA, [recoveredAssign.assignment.id])
    await reviewCommand(page, '改派', personB, mainB, trailerB, `合成UI遺失回應改派 ${scope}`)
    const recoveredChange = await lostCommand(page, info, '改派', `/tms/dispatches/${recoveredAssign.assignment.id}/reassign`, taskA.id, 4, false)
    expect(recoveredChange.generation).toBe(5)
    await reloadCurrent(page, taskA.id); await refreshOwn(driverA, []); await refreshOwn(driverB, [recoveredChange.assignment.id])
    await reviewCommand(page, '撤派', undefined, undefined, null, `合成UI遺失回應撤派 ${scope}`)
    const recoveredRevoke = await lostCommand(page, info, '撤派', `/tms/dispatches/${recoveredChange.assignment.id}/revoke`, taskA.id, 5, false)
    expect(recoveredRevoke.generation).toBe(6)
    const finalDetail = await reloadCurrent(page, taskA.id)
    expect(finalDetail.current).toBeNull(); expect(finalDetail.history.totalElements).toBe(4)
    expect(finalDetail.history.content.every((assignment) => assignment.reservations.every((reservation) => reservation.releasedAt !== null))).toBe(true)
    await refreshOwn(driverB, [])
    // Add distinct historical receipts through real APIs so the UI must fetch a
    // second bounded history page instead of displaying an unbounded local list.
    for (let generation = 6; generation < 12; generation += 2) {
      const historyOnly = success(await api<Command>(page, `/tms/tasks/${taskA.id}/dispatch`, 'POST', { expectedGeneration: generation, driverId: personA.id, mainVehicleId: mainA.id, trailerId: null }))
      success(await api(page, `/tms/dispatches/${historyOnly.assignment.id}/revoke`, 'POST', { expectedGeneration: generation + 1, reason: `合成UI歷史分頁 ${scope} ${generation}` }))
    }
    const pagedHistory = await reloadCurrent(page, taskA.id)
    expect(pagedHistory.history.totalElements).toBe(7); expect(pagedHistory.history.content).toHaveLength(5)
    const nextHistory = page.waitForResponse((response) => { const url = new URL(response.url()); return url.pathname === `/api/v1/tms/tasks/${taskA.id}/dispatch` && url.searchParams.get('historyPage') === '1' })
    await page.getByRole('button', { name: '下一頁歷史', exact: true }).click()
    const nextHistoryBody = (await (await nextHistory).json()).data as Detail
    expect(nextHistoryBody.history.content).toHaveLength(2); expect(nextHistoryBody.history.totalElements).toBe(7)
    await expandHistoryRows(page)
    await expect(page.getByRole('region', { name: '派工歷史', exact: true })).toContainText(changeReason)
    await evidence(page, info, 'dispatch-ui-history.png')

    // Fresh current binding and auth determine the DOM, not an old successful
    // task response cached in a component. No original account group is reused.
    const privateCurrent = success(await api<Command>(page, `/tms/tasks/${taskB.id}/dispatch`, 'POST', { expectedGeneration: 2, driverId: personA.id, mainVehicleId: mainA.id, trailerId: null }))
    await refreshOwn(driverA, [privateCurrent.assignment.id]); await expect(driverA.getByText(routeB, { exact: true })).toBeVisible()
    const logout = await holdRealLogout(driverA)
    try {
      await driverA.getByRole('button', { name: '登出', exact: true }).click(); await logout.forwarded
      await expect(driverA.getByText(routeB, { exact: true })).toHaveCount(0)
      await expect(driverA.getByRole('article', { name: /^本人任務 / })).toHaveCount(0)
      logout.release(); await expect(driverA.getByRole('heading', { name: '登入工作空間', exact: true })).toBeVisible()
    } finally { await logout.dispose() }
    await login(driverA, fixture.driverA); await navigate(driverA, '本人指派任務'); await refreshOwn(driverA, [privateCurrent.assignment.id])
    let latestA = success(await api<Driver>(page, `/fms/drivers/${personA.id}`))
    latestA = success(await api<Driver>(page, `/fms/drivers/${personA.id}`, 'PATCH', { version: latestA.version, accountId: null }))
    await refreshOwn(driverA, [], 404)
    await expect(driverA.getByText(routeB, { exact: true })).toHaveCount(0); await expect(driverA.getByText(contactB, { exact: true })).toHaveCount(0)
    latestA = success(await api<Driver>(page, `/fms/drivers/${personA.id}`, 'PATCH', { version: latestA.version, accountId: fixture.driverA.id }))
    await refreshOwn(driverA, [privateCurrent.assignment.id])
    const latestB = success(await api<Driver>(page, `/fms/drivers/${personB.id}`))
    success(await api(page, `/fms/drivers/${personB.id}`, 'PATCH', { version: latestB.version, accountId: null }))
    success(await api(page, `/fms/drivers/${personA.id}`, 'PATCH', { version: latestA.version, accountId: fixture.driverB.id }))
    await refreshOwn(driverA, [], 404); await expect(driverA.getByText(routeB, { exact: true })).toHaveCount(0)
    await refreshOwn(driverB, [privateCurrent.assignment.id]); await noPrivateDriverDetails(driverB, customer, mainA)
    await expect(driverB.getByText(routeB, { exact: true })).toBeVisible(); await expect(driverB.getByText(routeA, { exact: true })).toHaveCount(0)
    const boundActor = success(await api<Account[]>(page, '/upms/accounts')).find((actor) => actor.id === fixture.driverB.id)!
    success(await api(page, `/upms/accounts/${boundActor.id}`, 'PATCH', { version: boundActor.version, displayName: boundActor.displayName, role: boundActor.role, enabled: false }))
    await refreshOwn(driverB, [], 401)
    await expect(driverB.getByText(routeB, { exact: true })).toHaveCount(0); await expect(driverB.getByText(contactB, { exact: true })).toHaveCount(0)
    await expect(driverB.getByRole('heading', { name: '登入工作空間', exact: true })).toBeVisible()
    success(await api(dispatcher, `/tms/dispatches/${privateCurrent.assignment.id}/revoke`, 'POST', { expectedGeneration: 3, reason: '合成UI本人撤權後驗收結束釋放' }))
    const untouched = await api<Order>(page, `/orders/${order.id}`)
    expect(untouched.status).toBe(200); expect(isDeepStrictEqual(untouched.data, order)).toBe(true)
  } finally { await Promise.all(contexts.map((context) => context.close())) }
})
