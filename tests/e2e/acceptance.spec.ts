import { randomUUID } from 'node:crypto'
import { readFileSync, writeFileSync } from 'node:fs'
import { request as httpsRequest } from 'node:https'
import { isDeepStrictEqual } from 'node:util'
import { expect, test, type Browser, type BrowserContext, type Page, type Request, type Response, type Route, type TestInfo } from '@playwright/test'
import { acceptanceFixtureFor, signIn, type Credential } from './fixtures'

// Separate actors and customer records isolate this flow from all original 22
// cases. Runtime/fixture preparation is explicit and never happens in this file.
test.describe.configure({ retries: 0 })
type Body = Record<string, unknown>
type OrderType = 'IMPORT' | 'EXPORT' | 'LOCAL'
interface Customer { id: string; customerCode: string; name: string; version: number }
interface Order extends Body {
  id: string; orderNo: string; customerId: string; customerName: string; type: OrderType; version: number
  acceptanceStatus: 'DRAFT' | 'ACCEPTED'; lifecycleStatus: string
  items: (Body & { id: string; clientRef: string })[]
  tasks: (Body & { id: string; clientRef: string; predecessorRefs: string[]; predecessorTaskIds: string[] })[]
}
interface Reply<T> { status: number; code: string; data: T; payload: Body; replayed: string | null }
type OrderSchema = 'OrderEnvelope' | 'OrderPageEnvelope' | 'AcceptanceCheckEnvelope' | 'ErrorEnvelope'
interface Captured { schema: string; value: unknown }
class ResponseCollector {
  readonly orders: Captured[] = []
  readonly acceptances: Captured[] = []
  private readonly pages = new Set<Page>()
  private readonly pending = new Set<Promise<void>>()
  private readonly capture = (response: Response) => {
    const path = new URL(response.url()).pathname
    const method = response.request().method()
    const accept = /^\/api\/v1\/orders\/[0-9a-f-]{36}\/accept$/i.test(path) && method === 'POST'
    const collection = path === '/api/v1/orders' && ['GET', 'POST'].includes(method)
    const detail = /^\/api\/v1\/orders\/[0-9a-f-]{36}$/i.test(path) && ['GET', 'PUT'].includes(method)
    const check = /^\/api\/v1\/orders\/[0-9a-f-]{36}\/acceptance-check$/i.test(path) && method === 'GET'
    if (!accept && !collection && !detail && !check) return
    const pending = (async () => {
      try {
        const value: unknown = await response.json()
        if (accept) this.acceptances.push({ schema: response.status() >= 400 ? 'ErrorEnvelope' : 'AcceptedOrderEnvelope', value })
        else this.orders.push({ schema: response.status() >= 400 ? 'ErrorEnvelope' : check ? 'AcceptanceCheckEnvelope' : collection && method === 'GET' ? 'OrderPageEnvelope' : 'OrderEnvelope', value })
      } catch { /* Aborted bodies are not claimed as captured HTTP evidence. */ }
    })()
    this.pending.add(pending)
    void pending.then(() => this.pending.delete(pending), () => this.pending.delete(pending))
  }
  watch(page: Page): void { if (!this.pages.has(page)) { this.pages.add(page); page.on('response', this.capture) } }
  acceptance(value: Body, status: number): void { this.acceptances.push({ schema: status >= 400 ? 'ErrorEnvelope' : 'AcceptedOrderEnvelope', value }) }
  order(value: Body, schema: OrderSchema): void { this.orders.push({ schema, value }) }
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
  // These files contain public synthetic response bodies only. Authentication
  // headers, cookies, CSRF, request bodies and idempotency keys stay in memory.
  writeFileSync(info.outputPath('acceptance-contract-responses.json'), JSON.stringify(collector.acceptances))
  writeFileSync(info.outputPath('order-contract-responses.json'), JSON.stringify(collector.orders))
  collectors.delete(info.testId)
})

async function api<T>(page: Page, path: string, method = 'GET', body?: unknown): Promise<Reply<T>> {
  return page.evaluate(async ({ path, method, body }) => {
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (method !== 'GET') {
      const csrf = (await (await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' })).json()).data
      headers[csrf.headerName] = csrf.token
      headers['Content-Type'] = 'application/json'
      if (path.startsWith('/orders')) headers['Idempotency-Key'] = crypto.randomUUID()
    }
    const response = await fetch(`/api/v1${path}`, { method, credentials: 'same-origin', headers,
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
    const payload = await response.json()
    return { status: response.status, code: payload.code, data: payload.data, payload, replayed: response.headers.get('Idempotency-Replayed') }
  }, { path, method, body })
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
  await page.getByRole('navigation', { name: '主要導覽', exact: true }).getByRole('button', { name: '訂單工作台', exact: true }).click()
  await expect(page.getByRole('heading', { name: '訂單工作台', exact: true })).toBeVisible()
}
async function customer(page: Page, info: TestInfo, suffix: string): Promise<Customer> {
  const code = `AC_${info.project.name === 'chromium' ? 'D' : 'M'}_${randomUUID().replaceAll('-', '').slice(0, 12).toUpperCase()}_${suffix}`
  const result = await api<Customer>(page, '/crm/customers', 'POST', { customerCode: code, name: `合成受理客戶 ${suffix} ${code}` })
  expect(result.status).toBe(201)
  return result.data
}
function onlyDraft(order: Order): Body {
  const pick = (value: Body, keys: string[]) => Object.fromEntries(keys.map((key) => [key, value[key]]))
  return { ...pick(order, ['type', 'customerId', 'customerReference', 'remark', 'shipping']),
    items: order.items.map((value) => pick(value, ['clientRef', 'sequence', 'itemKind', 'containerType', 'containerNo', 'packageQty', 'grossWeight', 'cbm', 'lastPickupDate', 'deliveryCutoffAt'])),
    tasks: order.tasks.map((value) => pick(value, ['clientRef', 'itemRef', 'taskKind', 'pickupName', 'pickupAddress', 'deliveryName', 'deliveryAddress', 'siteContact', 'plannedStartAt', 'plannedEndAt', 'predecessorRefs', 'requiredTask'])) }
}
function completeDraft(id: string, type: OrderType = 'LOCAL'): Body {
  return { type, customerId: id, customerReference: '合成受理基線', remark: ' 合成 e\u0301 ',
    shipping: type === 'LOCAL' ? {} : { shippingCompany: '合成船公司', vesselVoyage: '合成航次', pol: '合成裝貨港', pod: '合成卸貨港', blNo: '合成提單', ...(type === 'IMPORT' ? { eta: '2026-12-01T08:00:00.123456+23:59' } : { etd: '2026-12-01T08:00:00.123456+23:59' }) },
    items: [{ clientRef: 'item-a', sequence: 1, itemKind: type === 'LOCAL' ? 'CARGO' : 'CONTAINER', packageQty: 7, containerType: '合成自由櫃型', containerNo: '合成非真實櫃號', grossWeight: '0.0000000000000000001', cbm: '0', ...(type === 'IMPORT' ? { lastPickupDate: '2026-12-04' } : type === 'EXPORT' ? { deliveryCutoffAt: '2026-12-03T08:00:00.123456+23:59' } : {}) }],
    tasks: [{ clientRef: 'task-a', itemRef: 'item-a', taskKind: '合成自由作業', pickupName: '合成起點', pickupAddress: '合成測試起點（非真實地點）', deliveryName: '合成終點', deliveryAddress: '合成測試終點（非真實地點）', requiredTask: true, predecessorRefs: [], siteContact: {}, plannedStartAt: '2026-12-01T08:00:00.123456+08:00', plannedEndAt: '2026-12-01T10:00:00.123456+08:00' }] }
}
function stable(order: Order): Body {
  const { version: _version, acceptanceStatus: _status, updatedAt: _at, updatedBy: _by, ...rest } = order
  return { ...rest, items: [...order.items].sort((a, b) => a.clientRef.localeCompare(b.clientRef)),
    tasks: order.tasks.map((task) => ({ ...task, predecessorRefs: [...task.predecessorRefs].sort(), predecessorTaskIds: [...task.predecessorTaskIds].sort() })).sort((a, b) => a.clientRef.localeCompare(b.clientRef)) }
}
function accepted(before: Order, after: Order): void {
  expect(after.id).toBe(before.id)
  expect(after.lifecycleStatus).toBe('OPEN')
  expect(after.acceptanceStatus).toBe('ACCEPTED')
  expect(after.version).toBe(before.version + 1)
  expect(isDeepStrictEqual(stable(before), stable(after))).toBe(true)
}

interface Forwarded { status: number; payload: Body; replayed?: string }
const safeForwardFailure = 'The controlled acceptance request could not be completed safely.'
/** A Node HTTPS hop avoids APIRequestContext's automatic sensitive-header Call
 * log, even on a failed request. Only public response bodies leave this helper. */
async function forward(request: Request, origin: string, signal: AbortSignal, invalidCsrf = false): Promise<Forwarded> {
  try {
    const url = new URL(request.url())
    if (url.origin !== origin || url.protocol !== 'https:' || !['POST', 'PUT'].includes(request.method())) throw new Error(safeForwardFailure)
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
      upstream = httpsRequest(url, { method: request.method(), headers, ...(ca ? { ca } : {}), rejectUnauthorized: true, agent: false }, (response) => {
        const chunks: Buffer[] = []; let bytes = 0
        response.on('error', fail); response.on('aborted', fail)
        response.on('data', (chunk: Buffer) => { bytes += chunk.byteLength; if (bytes > 1_048_576) { response.destroy(); fail() } else chunks.push(chunk) })
        response.on('end', () => {
          try {
            const value: unknown = JSON.parse(Buffer.concat(chunks).toString('utf8'))
            if (value === null || typeof value !== 'object' || Array.isArray(value)) { fail(); return }
            const payload = value as Body
            if (typeof payload.traceId !== 'string' || !payload.traceId.trim() || typeof payload.code !== 'string') { fail(); return }
            finish(() => resolve({ status: response.statusCode!, payload, replayed: response.headers['idempotency-replayed'] as string | undefined }))
          } catch { fail() }
        })
      })
      upstream.on('error', fail)
      deadline = setTimeout(cancel, 15_000)
      signal.addEventListener('abort', cancel, { once: true })
      if (signal.aborted) { cancel(); return }
      upstream.end(body)
    })
  } catch { throw new Error(safeForwardFailure) }
}

async function loseAcceptedResponse(page: Page, before: Order, collector: ResponseCollector) {
  const origin = new URL(page.url()).origin; const path = `/api/v1/orders/${before.id}/accept`
  let attempts = 0; let firstKey: string | undefined; let firstBody: Buffer | null = null; let matching = false
  let resolve!: (reply: Forwarded) => void; let reject!: (error: Error) => void
  const committed = new Promise<Forwarded>((yes, no) => { resolve = yes; reject = no }); void committed.catch(() => undefined)
  const matches = (url: URL) => url.origin === origin && url.pathname === path
  const cancellation = new AbortController(); const active = new Set<Promise<void>>()
  const setupDeadline = setTimeout(() => { cancellation.abort(); reject(new Error(safeForwardFailure)) }, 20_000)
  const handle = async (route: Route) => {
    try {
      const request = route.request()
      if (request.method() !== 'POST') { await route.continue(); return }
      attempts++
      const key = (await request.allHeaders())['idempotency-key']; const body = request.postDataBuffer()
      if (attempts === 1) {
        firstKey = key; firstBody = body
        const reply = await forward(request, origin, cancellation.signal)
        const data = reply.payload.data as Order | undefined
        if (reply.status !== 200 || reply.payload.code !== 'OK' || reply.replayed !== 'false' || !data || data.id !== before.id || data.version !== before.version + 1 || data.lifecycleStatus !== 'OPEN' || data.acceptanceStatus !== 'ACCEPTED') throw new Error(safeForwardFailure)
        collector.acceptance(reply.payload, reply.status)
        await route.abort('failed'); clearTimeout(setupDeadline); resolve(reply)
      } else {
        const same = Boolean(firstKey && firstKey === key && firstBody && body && firstBody.equals(body))
        matching = attempts === 2 ? same : matching && same
        if (attempts === 2) {
          const refusal = await forward(request, origin, cancellation.signal, true)
          if (refusal.status !== 403 || refusal.payload.code !== 'CSRF_INVALID' || !Array.isArray(refusal.payload.fieldErrors)) throw new Error(safeForwardFailure)
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

async function save(page: Page, method: 'POST' | 'PUT', id?: string): Promise<{ order: Order; request: Request }> {
  const path = `/api/v1/orders${id ? `/${id}` : ''}`
  const pending = page.waitForResponse((response) => response.request().method() === method && new URL(response.url()).pathname === path)
  await page.getByRole('button', { name: method === 'POST' ? '建立草稿' : '保存草稿', exact: true }).click()
  const response = await pending
  expect(response.status()).toBe(method === 'POST' ? 201 : 200)
  const payload = await response.json()
  expect(payload.code).toBe('OK')
  await expect(page.getByText(`已保存版本 ${payload.data.version}`, { exact: true })).toBeVisible()
  return { order: payload.data as Order, request: response.request() }
}
async function chooseCustomer(page: Page, value: Customer): Promise<void> {
  await page.getByLabel('查詢有效客戶', { exact: true }).fill(value.customerCode)
  await page.getByRole('button', { name: '查詢客戶', exact: true }).click()
  await page.getByLabel('選擇客戶', { exact: true }).selectOption(value.id)
}
async function createComplete(page: Page, value: Customer, type: OrderType): Promise<{ order: Order; request: Request }> {
  await page.getByRole('button', { name: '新增訂單', exact: true }).click()
  await page.getByLabel('訂單類型', { exact: true }).selectOption(type)
  await chooseCustomer(page, value)
  await page.getByLabel('備註內容狀態', { exact: true }).selectOption('text')
  await page.getByLabel('訂單備註', { exact: true }).fill(' 合成受理原值 e\u0301 ')
  if (type !== 'LOCAL') {
    await page.getByRole('button', { name: '加入船務資料', exact: true }).click()
    for (const [label, text] of [['船公司', '合成船公司'], ['船名航次', '合成航次'], ['裝貨港', '合成裝貨港'], ['卸貨港', '合成卸貨港'], ['提單號', '合成提單']]) await page.getByLabel(label, { exact: true }).fill(text)
    await page.getByLabel(type === 'IMPORT' ? '預計到港時間' : '預計離港時間', { exact: true }).fill('2026-12-01T08:00:00.123456+23:59')
  }
  for (let index = 0; index < (type === 'LOCAL' ? 2 : 1); index++) {
    await page.getByRole('button', { name: '新增項目', exact: true }).click()
    const item = page.getByRole('group', { name: `項目 ${index + 1}`, exact: true })
    await item.getByLabel('項目序號', { exact: true }).fill(String(index + 1))
    await item.getByLabel('標的類型', { exact: true }).selectOption(type === 'LOCAL' ? 'CARGO' : 'CONTAINER')
    if (type === 'LOCAL') await item.getByLabel('件數', { exact: true }).fill('7')
    else {
      await item.getByLabel('櫃型', { exact: true }).fill('合成自由櫃型')
      await item.getByLabel('櫃號', { exact: true }).fill('合成非真實櫃號')
      await item.getByLabel(type === 'IMPORT' ? '最後提櫃日' : '交櫃截止時間', { exact: true }).fill(type === 'IMPORT' ? '2026-12-04' : '2026-12-03T08:00:00.123456+23:59')
    }
    await item.getByLabel('毛重 kg', { exact: true }).fill('0.0000000000000000001')
    const ref = await item.getByLabel('項目參照', { exact: true }).inputValue()
    await page.getByRole('button', { name: '新增任務', exact: true }).click()
    const task = page.getByRole('group', { name: `任務 ${index + 1}`, exact: true })
    await task.getByLabel('所屬項目', { exact: true }).selectOption(ref)
    await task.getByLabel('作業類型', { exact: true }).fill('合成自由作業')
    await task.getByLabel('必要任務標記', { exact: true }).selectOption('true')
    for (const [label, text] of [['起點名稱', '合成起點'], ['起點地址', '合成起點（非真實地點）'], ['終點名稱', '合成終點'], ['終點地址', '合成終點（非真實地點）']]) await task.getByLabel(label, { exact: true }).fill(text)
    await task.getByLabel('預定開始', { exact: true }).fill('2026-12-01T08:00:00.123456+08:00')
    await task.getByLabel('預定結束', { exact: true }).fill('2026-12-01T10:00:00.123456+08:00')
    if (index) await task.getByRole('group', { name: '前置任務', exact: true }).getByRole('checkbox', { name: /^任務 1/ }).check()
  }
  const first = await save(page, 'POST')
  await page.getByLabel('客戶參考號', { exact: true }).fill(`合成受理前已保存 ${type}`)
  return save(page, 'PUT', first.order.id)
}
async function back(page: Page): Promise<void> {
  await page.getByRole('button', { name: '返回訂單列表', exact: true }).click()
  await expect(page.getByRole('heading', { name: '訂單工作台', exact: true })).toBeVisible()
}
async function openOrder(page: Page, value: Customer, order: Order): Promise<void> {
  await page.getByRole('button', { name: '選擇篩選客戶', exact: true }).click()
  const picker = page.getByRole('region', { name: '選擇篩選客戶', exact: true })
  await picker.getByLabel('篩選客戶搜尋', { exact: true }).fill(value.customerCode)
  await picker.getByRole('button', { name: '查詢篩選客戶', exact: true }).click()
  await picker.getByRole('button', { name: `篩選客戶：${value.name}（${value.customerCode}）`, exact: true }).click()
  const pending = page.waitForResponse((response) => { const url = new URL(response.url()); return url.pathname === '/api/v1/orders' && url.searchParams.get('customerId') === value.id })
  await page.getByRole('form', { name: '訂單查詢', exact: true }).getByRole('button', { name: '查詢訂單', exact: true }).click()
  expect((await pending).status()).toBe(200)
  await page.getByRole('button', { name: new RegExp(`^(編輯|檢視)訂單 ${order.orderNo}$`) }).click()
  await expect(page.getByText(`已保存版本 ${order.version}`, { exact: true })).toBeVisible()
}
async function check(page: Page, order: Order, complete = true): Promise<Body> {
  const pending = page.waitForResponse((response) => new URL(response.url()).pathname === `/api/v1/orders/${order.id}/acceptance-check`)
  await page.getByRole('button', { name: '檢查已保存版本', exact: true }).click()
  const response = await pending; expect(response.status()).toBe(200)
  const payload = await response.json()
  expect(payload.data.version).toBe(order.version)
  expect(payload.data.confirmedRulesOutcome).toBe(complete ? 'COMPLETE' : 'INCOMPLETE')
  expect(payload.data.operationalAcceptance).toBe('NOT_GRANTED')
  const panel = page.getByRole('region', { name: '已保存版本完整性', exact: true })
  await expect(panel).toContainText('NOT_GRANTED')
  if (order.acceptanceStatus === 'ACCEPTED') await expect(panel.getByText(/尚未授予正式受理/)).toHaveCount(0)
  return payload.data as Body
}
async function confirm(page: Page, order: Order): Promise<void> {
  await page.getByRole('button', { name: '正式受理訂單', exact: true }).click()
  const group = page.getByRole('group', { name: '正式受理確認', exact: true })
  await expect(group).toContainText(order.orderNo)
  await expect(group).toContainText(order.customerName)
  await expect(group).toContainText(String(order.version))
  await expect(group.getByRole('button', { name: '確認正式受理', exact: true })).toBeFocused()
}
async function submitAcceptance(page: Page, before: Order, status = 200): Promise<Reply<Order>> {
  const pending = page.waitForResponse((response) => response.request().method() === 'POST' && new URL(response.url()).pathname === `/api/v1/orders/${before.id}/accept`)
  await page.getByRole('button', { name: '確認正式受理', exact: true }).click()
  const response = await pending; expect(response.status()).toBe(status)
  const payload = await response.json()
  if (status === 200) { expect(payload.code).toBe('OK'); expect(await response.headerValue('Idempotency-Replayed')).toBe('false'); accepted(before, payload.data as Order) }
  return { status: response.status(), code: payload.code, data: payload.data as Order, payload, replayed: await response.headerValue('Idempotency-Replayed') }
}
async function readonly(page: Page, order: Order): Promise<void> {
  await expect(page.getByRole('heading', { name: `檢視訂單 ${order.orderNo}`, exact: true })).toBeVisible()
  await expect(page.getByText('已受理・待派車', { exact: true })).toBeVisible()
  for (const name of ['保存草稿', '正式受理訂單']) { const button = page.getByRole('button', { name, exact: true }); if (await button.count()) await expect(button).toBeDisabled() }
  const editable = await page.locator('input,textarea,select').evaluateAll((values) => values.filter((value) => value.getClientRects().length > 0).some((value) => !value.matches(':disabled') && !(value as HTMLInputElement).readOnly))
  expect(editable).toBe(false)
  await expect(page.getByRole('button', { name: /^(派車|派工|受理撤回|取消訂單|結案)$/ })).toHaveCount(0)
}
async function verify(page: Page, expected: Order, value: Customer): Promise<void> {
  const current = await api<Order>(page, `/orders/${expected.id}`); expect(current.status).toBe(200)
  expect(isDeepStrictEqual(current.data, expected)).toBe(true)
  const list = await api<{ content: Order[]; totalElements: number }>(page, `/orders?customerId=${value.id}`)
  expect(list.status).toBe(200); expect(list.data.totalElements).toBe(1)
  expect(list.data.content[0].acceptanceStatus).toBe('ACCEPTED'); expect(list.data.content[0].version).toBe(expected.version)
}
async function historicPut(page: Page, saved: { order: Order; request: Request }, expected: Order, collector: ResponseCollector): Promise<void> {
  const reply = await forward(saved.request, new URL(page.url()).origin, new AbortController().signal)
  expect(reply.status).toBe(200); expect(reply.payload.code).toBe('OK'); expect(reply.replayed).toBe('true')
  expect(isDeepStrictEqual(reply.payload.data, saved.order)).toBe(true)
  expect((reply.payload.data as Order).acceptanceStatus).toBe('DRAFT')
  collector.order(reply.payload, 'OrderEnvelope')
  expect(isDeepStrictEqual((await api<Order>(page, `/orders/${expected.id}`)).data, expected)).toBe(true)
}
async function evidence(page: Page, info: TestInfo, name: string): Promise<void> {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true)
  expect(await page.locator('input:not([type="hidden"]),textarea,select').evaluateAll((values) => values.filter((value) => value.getClientRects().length > 0).every((value) => Boolean((value as HTMLInputElement).labels?.length || value.getAttribute('aria-label') || value.getAttribute('aria-labelledby'))))).toBe(true)
  await page.evaluate(() => { if (document.activeElement instanceof HTMLElement) document.activeElement.blur(); window.scrollTo({ top: 0, left: 0, behavior: 'instant' }) })
  await page.screenshot({ path: info.outputPath(name), fullPage: true })
}

test('Explicit acceptance preserves original order graphs, replay, current eligibility and role boundaries over TLS', async ({ page, browser }, info) => {
  test.setTimeout(360_000)
  const fixture = acceptanceFixtureFor(info.project.name)
  const collector = collectors.get(info.testId)!
  const dispatcherContext = await contextFor(browser, info)
  const driverContext = await contextFor(browser, info)
  try {
    const dispatcher = await dispatcherContext.newPage()
    const driver = await driverContext.newPage()
    await enter(page, fixture.admin)
    await enter(dispatcher, fixture.dispatcher)
    await driver.goto('/'); await signIn(driver, fixture.driver)
    await expect(driver.getByRole('navigation', { name: '主要導覽', exact: true }).getByRole('button', { name: '訂單工作台', exact: true })).toHaveCount(0)

    const incompleteCustomer = await customer(page, info, 'INCOMPLETE')
    const incomplete = await api<Order>(page, '/orders', 'POST', { type: 'LOCAL', customerId: incompleteCustomer.id })
    expect(incomplete.status).toBe(201)
    await openOrder(page, incompleteCustomer, incomplete.data)
    await check(page, incomplete.data, false)
    await confirm(page, incomplete.data)
    await page.getByRole('button', { name: '取消受理確認', exact: true }).click()
    expect((await api<Order>(page, `/orders/${incomplete.data.id}`)).data.acceptanceStatus).toBe('DRAFT')
    await confirm(page, incomplete.data)
    const missing = await submitAcceptance(page, incomplete.data, 400)
    expect(missing.code).toBe('VALIDATION_ERROR')
    expect((missing.payload.fieldErrors as Body[]).some((field) => field.ruleId === 'ORD-P02')).toBe(true)
    await back(page)

    let sample!: Order
    for (const type of ['IMPORT', 'EXPORT', 'LOCAL'] as const) {
      const manager = type === 'EXPORT' ? dispatcher : page
      const value = await customer(page, info, type)
      const saved = await createComplete(manager, value, type)
      await check(manager, saved.order)
      if (type === 'IMPORT') {
        const renamed = await api<Customer>(page, `/crm/customers/${value.id}`, 'PATCH', { version: value.version, name: `${value.name} 新名稱` })
        expect(renamed.status).toBe(200)
      }
      await confirm(manager, saved.order)
      if (type === 'LOCAL') await evidence(manager, info, 'acceptance-confirmation.png')
      const result = await submitAcceptance(manager, saved.order)
      await readonly(manager, result.data)
      await check(manager, result.data)
      await verify(manager, result.data, value)
      const rejected = await api(manager, `/orders/${result.data.id}`, 'PUT', { draft: onlyDraft(result.data), expectedVersion: result.data.version })
      expect(rejected.status).toBe(409); expect(rejected.code).toBe('ORDER_NOT_EDITABLE')
      const duplicate = await api(manager, `/orders/${result.data.id}/accept`, 'POST', { expectedVersion: result.data.version })
      expect(duplicate.status).toBe(409); expect(duplicate.code).toBe('ORDER_NOT_EDITABLE')
      await historicPut(manager, saved, result.data, collector)
      await evidence(manager, info, `accepted-${type.toLowerCase()}.png`)
      sample = result.data
      await back(manager)
    }

    // A previously complete check cannot bypass a freshly disabled customer.
    const eligibility = await customer(page, info, 'ELIGIBILITY')
    const eligibleDraft = await api<Order>(page, '/orders', 'POST', completeDraft(eligibility.id))
    expect(eligibleDraft.status).toBe(201)
    await openOrder(page, eligibility, eligibleDraft.data)
    await check(page, eligibleDraft.data)
    const disabled = await api<Customer>(page, `/crm/customers/${eligibility.id}`, 'PATCH', { version: eligibility.version, enabled: false })
    expect(disabled.status).toBe(200)
    await confirm(page, eligibleDraft.data)
    const invalidCustomer = await submitAcceptance(page, eligibleDraft.data, 400)
    expect(invalidCustomer.code).toBe('INVALID_CUSTOMER')
    expect((await api<Order>(page, `/orders/${eligibleDraft.data.id}`)).data.version).toBe(0)
    const enabled = await api<Customer>(page, `/crm/customers/${eligibility.id}`, 'PATCH', { version: disabled.data.version, enabled: true, name: `${eligibility.name} 改名` })
    expect(enabled.status).toBe(200)
    await confirm(page, eligibleDraft.data)
    const eligibleAccepted = await submitAcceptance(page, eligibleDraft.data)
    expect(eligibleAccepted.data.customerName).toBe(eligibility.name)
    await readonly(page, eligibleAccepted.data); await back(page)

    // A second actor's committed acceptance never becomes an editable rebase.
    const conflictCustomer = await customer(page, info, 'CONFLICT')
    const conflictDraft = await api<Order>(page, '/orders', 'POST', completeDraft(conflictCustomer.id))
    expect(conflictDraft.status).toBe(201)
    await openOrder(page, conflictCustomer, conflictDraft.data)
    const remote = await api<Order>(dispatcher, `/orders/${conflictDraft.data.id}/accept`, 'POST', { expectedVersion: 0 })
    expect(remote.status).toBe(200); accepted(conflictDraft.data, remote.data)
    await confirm(page, conflictDraft.data)
    const conflict = await submitAcceptance(page, conflictDraft.data, 409)
    expect(conflict.code).toBe('VERSION_CONFLICT')
    await expect(page.getByText('版本衝突，本機草稿已保留', { exact: true })).toBeVisible()
    await page.getByRole('button', { name: '讀取伺服器版本比較', exact: true }).click()
    await expect(page.getByRole('button', { name: '捨棄本機內容，載入伺服器版本', exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '保留本機草稿，使用此版本繼續', exact: true })).toHaveCount(0)
    await page.getByRole('button', { name: '捨棄本機內容，載入伺服器版本', exact: true }).click()
    await page.getByRole('button', { name: '確認載入伺服器版本', exact: true }).click()
    await readonly(page, remote.data); await back(page)

    // The real server commits first; losing its response leaves a frozen request.
    const lostCustomer = await customer(page, info, 'REPLAY')
    const lostDraft = await api<Order>(dispatcher, '/orders', 'POST', completeDraft(lostCustomer.id))
    expect(lostDraft.status).toBe(201)
    await openOrder(dispatcher, lostCustomer, lostDraft.data)
    const fault = await loseAcceptedResponse(dispatcher, lostDraft.data, collector)
    try {
      await confirm(dispatcher, lostDraft.data)
      await dispatcher.getByRole('button', { name: '確認正式受理', exact: true }).click()
      const committed = await fault.committed
      const original = committed.payload.data as Order
      accepted(lostDraft.data, original)
      await expect(dispatcher.getByRole('heading', { name: '受理結果尚待確認', exact: true })).toBeVisible()
      await expect(dispatcher.getByLabel('客戶參考號', { exact: true })).toBeDisabled()
      await evidence(dispatcher, info, 'acceptance-unknown.png')
      const csrfRefusal = dispatcher.waitForResponse((response) => new URL(response.url()).pathname === `/api/v1/orders/${lostDraft.data.id}/accept` && response.status() === 403)
      await dispatcher.getByRole('button', { name: '以原請求重試受理', exact: true }).click()
      await csrfRefusal
      await expect(dispatcher.getByRole('heading', { name: '受理結果尚待確認', exact: true })).toBeVisible()
      await expect(dispatcher.getByRole('button', { name: '以原請求重試受理', exact: true })).toBeEnabled()
      expect(fault.attempts()).toBe(2)
      const replayResponse = dispatcher.waitForResponse((response) => new URL(response.url()).pathname === `/api/v1/orders/${lostDraft.data.id}/accept` && response.status() === 200)
      await dispatcher.getByRole('button', { name: '以原請求重試受理', exact: true }).click()
      const replay = await replayResponse
      expect(await replay.headerValue('Idempotency-Replayed')).toBe('true')
      expect(isDeepStrictEqual((await replay.json()).data, original)).toBe(true)
      expect(fault.attempts()).toBe(3); expect(fault.matching()).toBe(true)
      await readonly(dispatcher, original); await verify(dispatcher, original, lostCustomer)
      await evidence(dispatcher, info, 'acceptance-replayed.png')
    } finally { await fault.dispose() }

    for (const [path, method, body] of [
      ['/orders', 'GET', undefined], ['/orders', 'POST', completeDraft(lostCustomer.id)],
      [`/orders/${sample.id}`, 'GET', undefined], [`/orders/${sample.id}`, 'PUT', { draft: onlyDraft(sample), expectedVersion: sample.version }],
      [`/orders/${sample.id}/acceptance-check`, 'GET', undefined], [`/orders/${sample.id}/accept`, 'POST', { expectedVersion: sample.version }],
    ] as const) {
      const refusal = await api(driver, path, method, body)
      expect(refusal.status).toBe(403); expect(refusal.code).toBe('FORBIDDEN')
      expect(refusal.payload.data).toBeNull()
    }
  } finally { await dispatcherContext.close(); await driverContext.close() }
})
