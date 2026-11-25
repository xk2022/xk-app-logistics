import { randomUUID } from 'node:crypto'
import { readFileSync, writeFileSync } from 'node:fs'
import { request as httpsRequest } from 'node:https'
import { isDeepStrictEqual } from 'node:util'
import { expect, test, type Browser, type BrowserContext, type Page, type Request, type Response, type Route, type TestInfo } from '@playwright/test'
import { orderUiFixtureFor, signIn, type Credential } from './fixtures'

// Each viewport owns independent accounts. Its UI cases run in order so another
// case cannot evict a still-active session or mutate its dedicated customer data.
test.describe.configure({ mode: 'default', retries: 0 })

type Body = Record<string, unknown>
type OrderType = 'IMPORT' | 'EXPORT' | 'LOCAL'
interface Customer { id: string; customerCode: string; name: string; enabled: boolean; version: number }
interface Order extends Body {
  id: string; orderNo: string; type: OrderType; customerId: string; customerName: string; version: number
  items: (Body & { id: string; clientRef: string })[]
  tasks: (Body & { id: string; taskNo: string; clientRef: string; itemRef: string; predecessorRefs: string[]; predecessorTaskIds: string[] })[]
}
interface Envelope<T> { status: number; code: string; data: T }
interface OrderPage { content: Order[]; totalElements: number }
type CapturedSchema = 'OrderEnvelope' | 'OrderPageEnvelope' | 'AcceptanceCheckEnvelope' | 'ErrorEnvelope'
interface Captured { schema: CapturedSchema; value: unknown }
class ResponseCollector {
  readonly values: Captured[] = []
  private readonly pending = new Set<Promise<void>>()
  private readonly pages = new Set<Page>()
  private readonly capture = (response: Response) => {
    const url = new URL(response.url())
    const method = response.request().method()
    const collection = url.pathname === '/api/v1/orders' && ['GET', 'POST'].includes(method)
    const detail = /^\/api\/v1\/orders\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(url.pathname) && ['GET', 'PUT'].includes(method)
    const check = /^\/api\/v1\/orders\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/acceptance-check$/i.test(url.pathname) && method === 'GET'
    if (!collection && !detail && !check) return
    const pending = (async () => {
      try {
        const value: unknown = await response.json()
        const schema: CapturedSchema = response.status() >= 400 ? 'ErrorEnvelope' : check ? 'AcceptanceCheckEnvelope' : collection && method === 'GET' ? 'OrderPageEnvelope' : 'OrderEnvelope'
        this.values.push({ schema, value })
      } catch { /* Aborted/invalid-JSON responses provide no body to validate. */ }
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
test.beforeEach(async ({ page }, testInfo) => {
  const collector = new ResponseCollector()
  collectors.set(testInfo.testId, collector)
  collector.watch(page)
})
test.afterEach(async ({}, testInfo) => {
  const collector = collectors.get(testInfo.testId)
  if (!collector) return
  await collector.finish()
  writeFileSync(testInfo.outputPath('order-contract-responses.json'), JSON.stringify(collector.values))
  collectors.delete(testInfo.testId)
})


async function api<T>(page: Page, path: string, method = 'GET', body?: unknown): Promise<Envelope<T>> {
  return page.evaluate(async ({ path, method, body }) => {
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (method !== 'GET') {
      const csrf = (await (await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' })).json()).data
      headers[csrf.headerName] = csrf.token
      headers['Content-Type'] = 'application/json'
      if (path === '/orders' || path.startsWith('/orders/')) headers['Idempotency-Key'] = crypto.randomUUID()
    }
    const response = await fetch(`/api/v1${path}`, { method, credentials: 'same-origin', headers,
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
    const payload = await response.json()
    // Credentials, cookies, CSRF and idempotency headers never cross this boundary.
    return { status: response.status, code: payload.code, data: payload.data }
  }, { path, method, body })
}

async function createCustomer(page: Page, testInfo: TestInfo, suffix: string): Promise<Customer> {
  const prefix = `UI_${testInfo.project.name === 'chromium' ? 'D' : 'M'}_${randomUUID().replaceAll('-', '').slice(0, 12).toUpperCase()}`
  const response = await api<Customer>(page, '/crm/customers', 'POST', { customerCode: `${prefix}_${suffix}`, name: `合成訂單介面客戶 ${suffix} ${prefix}` })
  expect(response.status).toBe(201)
  expect(response.code).toBe('0000')
  return response.data
}

function onlyDraft(order: Order): Body {
  const pick = (value: Body, keys: string[]) => Object.fromEntries(keys.map((key) => [key, value[key]]))
  return { ...pick(order, ['type', 'customerId', 'customerReference', 'remark', 'shipping']),
    items: order.items.map((item) => pick(item, ['clientRef', 'sequence', 'itemKind', 'containerType', 'containerNo', 'packageQty', 'grossWeight', 'cbm', 'lastPickupDate', 'deliveryCutoffAt'])),
    tasks: order.tasks.map((task) => pick(task, ['clientRef', 'itemRef', 'taskKind', 'pickupName', 'pickupAddress', 'deliveryName', 'deliveryAddress', 'siteContact', 'plannedStartAt', 'plannedEndAt', 'predecessorRefs', 'requiredTask'])) }
}

async function signedIn(page: Page, credential: Credential): Promise<void> {
  await page.goto('/')
  expect(new URL(page.url()).protocol).toBe('https:')
  await signIn(page, credential)
  await expect(page.getByRole('button', { name: '訂單工作台', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '訂單工作台', exact: true }).click()
  await expect(page.getByRole('heading', { name: '訂單工作台', exact: true })).toBeVisible()
}

async function newContext(browser: Browser, testInfo: TestInfo): Promise<BrowserContext> {
  const use = testInfo.project.use
  const context = await browser.newContext({ baseURL: use.baseURL, ignoreHTTPSErrors: use.ignoreHTTPSErrors, viewport: use.viewport,
    screen: use.screen, deviceScaleFactor: use.deviceScaleFactor, isMobile: use.isMobile, hasTouch: use.hasTouch, userAgent: use.userAgent })
  const collector = collectors.get(testInfo.testId)
  if (collector) context.on('page', (page) => collector.watch(page))
  return context
}

async function noOverflow(page: Page): Promise<void> {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true)
}

async function screenshot(page: Page, testInfo: TestInfo, name: string, fullPage = false): Promise<void> {
  // Keyboard/label checks run first; remove only their leftover focus for evidence.
  await page.evaluate((full) => {
    if (document.activeElement instanceof HTMLElement) document.activeElement.blur()
    if (full) window.scrollTo({ top: 0, left: 0, behavior: 'instant' })
  }, fullPage)
  await page.screenshot({ path: testInfo.outputPath(name), fullPage })
}

async function saved(page: Page, method: 'POST' | 'PUT', action: () => Promise<void>, status = method === 'POST' ? 201 : 200): Promise<Order> {
  const pending = page.waitForResponse((response) => response.request().method() === method && /^\/api\/v1\/orders(?:\/[0-9a-f-]+)?$/i.test(new URL(response.url()).pathname))
  await action()
  const response = await pending
  expect(response.status()).toBe(status)
  const payload = await response.json()
  expect(payload.code).toBe(status < 400 ? 'OK' : 'VERSION_CONFLICT')
  return payload.data as Order
}

async function filterCustomer(page: Page, customer: Customer, type = ''): Promise<void> {
  await page.getByRole('button', { name: '選擇篩選客戶', exact: true }).click()
  const picker = page.getByRole('region', { name: '選擇篩選客戶', exact: true })
  await picker.getByLabel('篩選客戶搜尋', { exact: true }).fill(customer.customerCode)
  await picker.getByRole('button', { name: '查詢篩選客戶', exact: true }).click()
  await picker.getByRole('button', { name: `篩選客戶：${customer.name}（${customer.customerCode}）`, exact: true }).click()
  await page.getByLabel('訂單類型篩選', { exact: true }).selectOption(type)
  const pending = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return response.request().method() === 'GET' && url.pathname === '/api/v1/orders' && url.searchParams.get('customerId') === customer.id && (url.searchParams.get('type') ?? '') === type
  })
  await page.getByRole('form', { name: '訂單查詢', exact: true }).getByRole('button', { name: '查詢訂單', exact: true }).click()
  expect((await pending).status()).toBe(200)
}

interface Committed { data: Order; traceId: string; location?: string; replayed?: string }
interface Forwarded { status: number; payload: Body; location?: string; replayed?: string }
const safeForwardFailure = 'The controlled response-loss request could not be completed safely.'

/** Forward only this synthetic same-origin mutation. Node errors cannot acquire
 * Playwright APIRequestContext's automatic Cookie/CSRF header Call log. */
async function forwardToRealBackend(request: Request, origin: string, signal: AbortSignal, invalidCsrf = false): Promise<Forwarded> {
  try {
    const url = new URL(request.url())
    if (url.origin !== origin || url.protocol !== 'https:' || !['POST', 'PUT'].includes(request.method())) throw new Error(safeForwardFailure)
    const headers = { ...await request.allHeaders() }
    const connectionTokens = (headers.connection ?? '').split(',').map((value) => value.trim().toLowerCase())
    for (const name of [...connectionTokens, 'connection', 'proxy-connection', 'keep-alive', 'transfer-encoding', 'upgrade', 'te', 'trailer', 'proxy-authenticate', 'proxy-authorization']) delete headers[name]
    for (const name of Object.keys(headers)) if (name.startsWith(':')) delete headers[name]
    const body = request.postDataBuffer() ?? Buffer.alloc(0)
    headers['content-length'] = String(body.byteLength)
    headers['accept-encoding'] = 'identity'
    if (invalidCsrf) headers['x-csrf-token'] = 'synthetic-invalid-csrf'
    const local = ['localhost', '127.0.0.1'].includes(url.hostname)
    // Trust the generated public DEV certificate, keeping hostname and chain verification on.
    const ca = local ? readFileSync('infrastructure/dev-tls/localhost.crt') : undefined
    return await new Promise<Forwarded>((resolve, reject) => {
      let settled = false
      let deadline: ReturnType<typeof setTimeout> | undefined
      let upstream: ReturnType<typeof httpsRequest> | undefined
      const finish = (complete: () => void) => {
        if (settled) return
        settled = true
        if (deadline) clearTimeout(deadline)
        signal.removeEventListener('abort', cancel)
        complete()
      }
      const fail = () => finish(() => reject(new Error(safeForwardFailure)))
      const cancel = () => { upstream?.destroy(); fail() }
      if (signal.aborted) { fail(); return }
      upstream = httpsRequest(url, { method: request.method(), headers, ...(ca ? { ca } : {}), rejectUnauthorized: true, agent: false }, (response) => {
        const chunks: Buffer[] = []
        let bytes = 0
        response.on('error', fail)
        response.on('aborted', fail)
        response.on('data', (chunk: Buffer) => {
          bytes += chunk.byteLength
          if (bytes > 1_048_576) { response.destroy(); fail() } else chunks.push(chunk)
        })
        response.on('end', () => {
          try {
            const payload: unknown = JSON.parse(Buffer.concat(chunks).toString('utf8'))
            if (payload === null || typeof payload !== 'object' || Array.isArray(payload)) { fail(); return }
            const parsed = payload as Body
            if (typeof parsed.traceId !== 'string' || !parsed.traceId.trim()) { fail(); return }
            if (invalidCsrf) {
              if (response.statusCode !== 403 || parsed.code !== 'CSRF_INVALID' || !Array.isArray(parsed.fieldErrors)) { fail(); return }
            } else {
              const data = parsed.data as Order | undefined
              if (response.statusCode !== (request.method() === 'POST' ? 201 : 200) || parsed.code !== 'OK' || response.headers['idempotency-replayed'] !== 'false' ||
                !data || !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(data.id) || !Number.isSafeInteger(data.version) || data.version < 0 ||
                (request.method() === 'POST' && response.headers.location !== `/api/v1/orders/${data.id}`)) { fail(); return }
            }
            const reply = { status: response.statusCode!, payload: parsed, location: response.headers.location, replayed: response.headers['idempotency-replayed'] as string | undefined }
            finish(() => resolve(reply))
          } catch { fail() }
        })
      })
      upstream.on('error', fail)
      // A total deadline also covers DNS/TLS and a server that trickles bytes.
      deadline = setTimeout(cancel, 15_000)
      signal.addEventListener('abort', cancel, { once: true })
      if (signal.aborted) { cancel(); return }
      upstream.end(body)
    })
  } catch { throw new Error(safeForwardFailure) }
}

async function loseCommittedResponse(page: Page, method: 'POST' | 'PUT', path: string, rejectFirstRetry = false) {
  const origin = new URL(page.url()).origin
  let attempts = 0
  let firstKey: string | undefined
  let firstBody: Buffer | null = null
  let matchingRetry = false
  let resolveCommitted!: (value: Committed) => void
  let rejectCommitted!: (error: Error) => void
  const committed = new Promise<Committed>((resolve, reject) => { resolveCommitted = resolve; rejectCommitted = reject })
  void committed.catch(() => undefined)
  const matches = (url: URL) => url.origin === origin && url.pathname === path
  const cancellation = new AbortController()
  const active = new Set<Promise<void>>()
  const setupDeadline = setTimeout(() => { cancellation.abort(); rejectCommitted(new Error(safeForwardFailure)) }, 20_000)
  const handle = async (route: Route): Promise<void> => {
    try {
      const request = route.request()
      if (request.method() !== method) { await route.continue(); return }
      attempts++
      const key = (await request.allHeaders())['idempotency-key']
      const body = request.postDataBuffer()
      if (attempts === 1) {
        firstKey = key; firstBody = body
        const reply = await forwardToRealBackend(request, origin, cancellation.signal)
        await route.abort('failed')
        clearTimeout(setupDeadline)
        resolveCommitted({ data: reply.payload.data as Order, traceId: reply.payload.traceId as string, location: reply.location, replayed: reply.replayed })
      } else {
        const matchesOriginal = Boolean(firstKey && firstKey === key && firstBody && body && firstBody.equals(body))
        matchingRetry = attempts === 2 ? matchesOriginal : matchingRetry && matchesOriginal
        if (attempts === 2 && rejectFirstRetry) {
          const refusal = await forwardToRealBackend(request, origin, cancellation.signal, true)
          // This is the real backend CSRF refusal. Only its public body crosses
          // Playwright's fulfill boundary; authentication headers remain in Node.
          await route.fulfill({ status: refusal.status, contentType: 'application/json', body: JSON.stringify(refusal.payload) })
        } else await route.continue()
      }
    } catch {
      clearTimeout(setupDeadline)
      rejectCommitted(new Error(safeForwardFailure))
      try { await route.abort('failed') } catch { /* The page may already be closing. */ }
    }
  }
  const handler = (route: Route) => {
    const pending = handle(route)
    active.add(pending)
    void pending.then(() => active.delete(pending), () => active.delete(pending))
    return pending
  }
  await page.route(matches, handler)
  return { committed, attempts: () => attempts, matchingRetry: () => matchingRetry,
    usesDifferentKey: (previous: string | undefined) => Boolean(firstKey && previous && firstKey !== previous),
    dispose: async () => {
      clearTimeout(setupDeadline)
      cancellation.abort()
      rejectCommitted(new Error(safeForwardFailure))
      await Promise.allSettled([...active])
      await page.unroute(matches, handler)
    } }
}

async function accessibility(page: Page): Promise<void> {
  const controlsHaveLabels = await page.locator('input:not([type="hidden"]), textarea, select').evaluateAll((elements) => elements
    .filter((element) => element.getClientRects().length > 0)
    .every((element) => Boolean((element as HTMLInputElement).labels?.length || element.getAttribute('aria-label') || element.getAttribute('aria-labelledby'))))
  expect(controlsHaveLabels).toBe(true)
  expect(await page.locator('[id]').evaluateAll((elements) => new Set(elements.map((element) => element.id)).size === elements.length)).toBe(true)
  await noOverflow(page)
}

async function fillTask(page: Page, index: number, itemRef: string): Promise<void> {
  await page.getByRole('button', { name: '新增任務', exact: true }).click()
  const task = page.getByRole('group', { name: `任務 ${index + 1}`, exact: true })
  await task.getByLabel('所屬項目', { exact: true }).selectOption(itemRef)
  await task.getByLabel('作業類型', { exact: true }).fill(`合成自由作業 ${index + 1}`)
  await task.getByLabel('必要任務標記', { exact: true }).selectOption('true')
  await task.getByLabel('起點名稱', { exact: true }).fill(`合成起點 ${index + 1}`)
  await task.getByLabel('起點地址', { exact: true }).fill(`合成起點測試地址 ${index + 1}（非真實地點）`)
  await task.getByLabel('終點名稱', { exact: true }).fill(`合成終點 ${index + 1}`)
  await task.getByLabel('終點地址', { exact: true }).fill(`合成終點測試地址 ${index + 1}（非真實地點）`)
  await task.getByLabel('預定開始', { exact: true }).fill('2026-12-01T08:00:00.123456+08:00')
  await task.getByLabel('預定結束', { exact: true }).fill('2026-12-01T10:00:00.123456+08:00')
  if (index === 0) {
    await task.getByRole('button', { name: '新增現場聯絡資料', exact: true }).click()
    await task.getByLabel('現場聯絡人姓名', { exact: true }).fill('合成現場窗口')
    await task.getByLabel('現場聯絡電話', { exact: true }).fill('0000000000')
  } else {
    await task.getByRole('group', { name: '前置任務', exact: true }).getByRole('checkbox', { name: /^任務 1/ }).check()
  }
}

async function verifyCommitted(page: Page, witness: Committed, customer: Customer): Promise<void> {
  const current = await api<Order>(page, `/orders/${witness.data.id}`)
  expect(current.status).toBe(200)
  expect(isDeepStrictEqual(current.data, witness.data)).toBe(true)
  const list = await api<OrderPage>(page, `/orders?customerId=${customer.id}`)
  expect(list.status).toBe(200)
  expect(list.data.totalElements).toBe(1)
  expect(list.data.content[0].id).toBe(witness.data.id)
}
async function fillItem(page: Page, index: number, type: OrderType): Promise<string> {
  await page.getByRole('button', { name: '新增項目', exact: true }).click()
  const item = page.getByRole('group', { name: `項目 ${index + 1}`, exact: true })
  await item.getByLabel('項目序號', { exact: true }).fill(String(index + 1))
  await item.getByLabel('標的類型', { exact: true }).selectOption(type === 'LOCAL' ? 'CARGO' : 'CONTAINER')
  if (type === 'LOCAL') await item.getByLabel('件數', { exact: true }).fill(String(index + 7))
  else {
    await item.getByLabel('櫃型', { exact: true }).fill('合成自由櫃型')
    await item.getByLabel('櫃號', { exact: true }).fill('合成櫃號（非正式資料）')
    if (type === 'IMPORT') await item.getByLabel('最後提櫃日', { exact: true }).fill('2026-12-04')
    else await item.getByLabel('交櫃截止時間', { exact: true }).fill('2026-12-03T08:00:00.123456+23:59')
  }
  if (index === 0) {
    await item.getByLabel('毛重 kg', { exact: true }).fill('0.0000000000000000001')
    await item.getByLabel('體積 m³', { exact: true }).fill('0')
  }
  return item.getByLabel('項目參照', { exact: true }).inputValue()
}
async function openNew(page: Page, keyboard = false): Promise<void> {
  const button = page.getByRole('button', { name: '新增訂單', exact: true })
  if (keyboard) { await button.focus(); await button.press('Enter') } else await button.click()
  await expect(page.getByRole('heading', { name: '新增訂單', exact: true })).toBeVisible()
}

async function chooseCustomer(page: Page, customer: Customer): Promise<void> {
  await page.getByLabel('查詢有效客戶', { exact: true }).fill(customer.customerCode)
  await page.getByRole('button', { name: '查詢客戶', exact: true }).click()
  await expect(page.getByLabel('選擇客戶', { exact: true }).getByRole('option', { name: `${customer.name} · ${customer.customerCode}`, exact: true })).toHaveCount(1)
  await page.getByLabel('選擇客戶', { exact: true }).selectOption(customer.id)
}

async function completeEditor(page: Page, customer: Customer, type: OrderType): Promise<void> {
  await page.getByLabel('訂單類型', { exact: true }).selectOption(type)
  await chooseCustomer(page, customer)
  await page.getByLabel('客戶參考號', { exact: true }).fill(`合成參考 ${type}`)
  await page.getByLabel('備註內容狀態', { exact: true }).selectOption('text')
  await page.getByLabel('訂單備註', { exact: true }).fill(' 合成原值 e\u0301 ')
  if (type !== 'LOCAL') {
    await page.getByRole('button', { name: '加入船務資料', exact: true }).click()
    for (const [name, value] of [['船公司', '合成船公司'], ['船名航次', '合成航次'], ['裝貨港', '合成裝貨港'], ['卸貨港', '合成卸貨港'], ['提單號', '合成提單']]) await page.getByLabel(name, { exact: true }).fill(value)
    await page.getByLabel(type === 'IMPORT' ? '預計到港時間' : '預計離港時間', { exact: true }).fill('2026-12-01T08:00:00.123456+23:59')
  }
  for (let index = 0; index < (type === 'LOCAL' ? 2 : 1); index++) {
    const itemRef = await fillItem(page, index, type)
    await fillTask(page, index, itemRef)
  }
}

async function waitSaved(page: Page, order: Order): Promise<void> {
  await expect(page.getByRole('heading', { name: `編輯訂單 ${order.orderNo}`, exact: true })).toBeVisible()
  await expect(page.getByText(`已保存版本 ${order.version}`, { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeEnabled()
}

async function acceptance(page: Page, order: Order, complete: boolean): Promise<Body> {
  const pending = page.waitForResponse((response) => response.request().method() === 'GET' && new URL(response.url()).pathname === `/api/v1/orders/${order.id}/acceptance-check`)
  await page.getByRole('button', { name: '檢查已保存版本', exact: true }).click()
  const response = await pending
  expect(response.status()).toBe(200)
  const payload = await response.json()
  expect(payload.code).toBe('OK')
  expect(payload.data.version).toBe(order.version)
  expect(payload.data.confirmedRulesOutcome).toBe(complete ? 'COMPLETE' : 'INCOMPLETE')
  expect(payload.data.operationalAcceptance).toBe('NOT_GRANTED')
  const panel = page.getByRole('region', { name: '已保存版本完整性', exact: true })
  await expect(panel.getByRole('heading', { name: new RegExp(`${complete ? '已確認規則欄位齊全' : '仍有待補欄位'}.*受檢版本 ${order.version}`) })).toBeVisible()
  await expect(panel.getByText(/尚未授予正式受理（NOT_GRANTED）/)).toBeVisible()
  const after = await api<Order>(page, `/orders/${order.id}`)
  expect(isDeepStrictEqual(after.data, order)).toBe(true)
  return payload.data as Body
}

async function backToList(page: Page): Promise<void> {
  await page.getByRole('button', { name: '返回訂單列表', exact: true }).click()
  await expect(page.getByRole('heading', { name: '訂單工作台', exact: true })).toBeVisible()
}

async function retryOriginal(page: Page, method: 'POST' | 'PUT', path: string, witness: Committed): Promise<Order> {
  const pending = page.waitForResponse((response) => response.request().method() === method && new URL(response.url()).pathname === path)
  await page.getByRole('button', { name: '以原內容重試保存', exact: true }).click()
  const response = await pending
  expect(response.status()).toBe(method === 'POST' ? 201 : 200)
  expect(await response.headerValue('idempotency-replayed')).toBe('true')
  if (method === 'POST') expect(await response.headerValue('location')).toBe(witness.location)
  const payload = await response.json()
  expect(payload.code).toBe('OK')
  expect(isDeepStrictEqual(payload.data, witness.data)).toBe(true)
  expect(Boolean(payload.traceId && payload.traceId !== witness.traceId)).toBe(true)
  await waitSaved(page, payload.data)
  return payload.data as Order
}

async function refuseRetryWithRealCsrf(page: Page, method: 'POST' | 'PUT', path: string): Promise<void> {
  const pending = page.waitForResponse((response) => response.request().method() === method && new URL(response.url()).pathname === path)
  await page.getByRole('button', { name: '以原內容重試保存', exact: true }).click()
  const response = await pending
  expect(response.status()).toBe(403)
  expect((await response.json()).code).toBe('CSRF_INVALID')
  await expect(page.getByRole('heading', { name: '保存結果尚待確認', exact: true })).toBeVisible()
  await expect(page.getByRole('alert')).toContainText('CSRF_INVALID')
  await expect(page.getByLabel('客戶參考號', { exact: true })).toBeDisabled()
}

test('Order UI lets ADMIN and DISPATCHER create and edit all types, related items/tasks, complete checks and paged lists', async ({ page, browser }, testInfo) => {
  test.setTimeout(300_000)
  const fixtures = orderUiFixtureFor(testInfo.project.name)
  const dispatcherContext = await newContext(browser, testInfo)
  try {
    const dispatcher = await dispatcherContext.newPage()
    for (const [member, credential, role] of [[page, fixtures.admin, 'ADMIN'], [dispatcher, fixtures.dispatcher, 'DISPATCHER']] as const) {
      await signedIn(member, credential)
      const customer = await createCustomer(member, testInfo, role)
      await filterCustomer(member, customer)
      await expect(member.getByRole('heading', { name: '尚無符合條件的訂單', exact: true })).toBeVisible()
      const created: Order[] = []
      for (const type of ['IMPORT', 'EXPORT', 'LOCAL'] as const) {
        await openNew(member, type === 'IMPORT')
        if (type === 'IMPORT') {
          await member.getByRole('button', { name: '建立草稿', exact: true }).click()
          await expect(member.getByRole('alert')).toContainText('請選擇客戶')
        }
        await completeEditor(member, customer, type)
        await accessibility(member)
        const first = await saved(member, 'POST', () => member.getByRole('button', { name: '建立草稿', exact: true }).click())
        await waitSaved(member, first)
        expect(first.type).toBe(type)
        expect(first.version).toBe(0)
        expect(first.items.length).toBe(type === 'LOCAL' ? 2 : 1)
        expect(first.tasks.length).toBe(first.items.length)
        expect(first.remark === ' 合成原值 e\u0301 ').toBe(true)
        expect(first.items[0].grossWeight).toBe('0.0000000000000000001')
        expect(first.items[0].cbm).toBe('0')
        if (type === 'LOCAL') {
          expect(first.items[1].grossWeight).toBeNull()
          expect(first.tasks[1].predecessorRefs[0]).toBe(first.tasks[0].clientRef)
          expect(first.tasks[1].predecessorTaskIds[0]).toBe(first.tasks[0].id)
          await member.getByRole('button', { name: '下移項目 1', exact: true }).click()
          await member.getByRole('button', { name: '下移任務 1', exact: true }).click()
        }
        await member.getByLabel('客戶參考號', { exact: true }).fill(`合成已更新 ${role} ${type}`)
        const edited = await saved(member, 'PUT', () => member.getByRole('button', { name: '保存草稿', exact: true }).click())
        await waitSaved(member, edited)
        expect(edited.version).toBe(1)
        for (const item of edited.items) expect(item.id).toBe(first.items.find((old) => old.clientRef === item.clientRef)?.id)
        for (const task of edited.tasks) {
          const old = first.tasks.find((value) => value.clientRef === task.clientRef)
          expect(task.id).toBe(old?.id)
          expect(task.taskNo).toBe(old?.taskNo)
          expect(isDeepStrictEqual(task.predecessorRefs, old?.predecessorRefs)).toBe(true)
        }
        await acceptance(member, edited, true)
        await expect(member.getByRole('button', { name: /^(正式受理|受理|派工|派車|結案|刪除訂單)$/ })).toHaveCount(0)
        if (role === 'ADMIN' && type === 'LOCAL') {
          await screenshot(member, testInfo, 'order-ui-editor-dependencies.png', true)
          await member.getByRole('group', { name: '任務 1', exact: true }).getByRole('group', { name: '前置任務', exact: true }).scrollIntoViewIfNeeded()
          await screenshot(member, testInfo, 'order-ui-readable-task-dependency.png')
        }
        created.push(edited)
        await backToList(member)
      }
      // Dedicated additional records exercise real pagination without replacing
      // the six role/type creations above with test setup requests.
      for (let index = 0; index < 3; index++) expect((await api<Order>(member, '/orders', 'POST', { type: 'LOCAL', customerId: customer.id })).status).toBe(201)
      await member.getByLabel('每頁訂單筆數', { exact: true }).selectOption('5')
      await member.getByLabel('訂單排序', { exact: true }).selectOption('orderNo,asc')
      await filterCustomer(member, customer)
      const expected = (await api<OrderPage>(member, `/orders?customerId=${customer.id}&size=100&sort=orderNo,asc`)).data.content
      expect(expected.length).toBe(6)
      const cards = member.getByRole('article', { name: /^訂單 / })
      await expect(cards).toHaveCount(5)
      expect(await cards.evaluateAll((values) => values.map((value) => value.getAttribute('aria-label')))).toEqual(expected.slice(0, 5).map((value) => `訂單 ${value.orderNo}`))
      await member.getByRole('button', { name: '下一頁訂單', exact: true }).click()
      await expect(cards).toHaveCount(1)
      await expect(cards).toHaveAttribute('aria-label', `訂單 ${expected[5].orderNo}`)
      await filterCustomer(member, customer, 'IMPORT')
      await expect(cards).toHaveCount(1)
      await expect(cards).toHaveAttribute('aria-label', `訂單 ${created[0].orderNo}`)
      await expect(member.getByRole('button', { name: '上一頁訂單', exact: true })).toBeDisabled()
      await member.getByLabel('每頁訂單筆數', { exact: true }).selectOption('20')
      await filterCustomer(member, customer)
      await expect(cards).toHaveCount(6)
      await accessibility(member)
      if (role === 'ADMIN') await screenshot(member, testInfo, 'order-ui-list.png', true)
    }
  } finally { await dispatcherContext.close() }
})

test('Order UI preserves local conflicts and replays committed but lost POST/PUT responses without extra writes', async ({ page, browser }, testInfo) => {
  test.setTimeout(180_000)
  const fixtures = orderUiFixtureFor(testInfo.project.name)
  await signedIn(page, fixtures.admin)
  const customer = await createCustomer(page, testInfo, 'REPLAY')
  await openNew(page)
  await page.getByLabel('訂單類型', { exact: true }).selectOption('LOCAL')
  await chooseCustomer(page, customer)
  await page.getByLabel('客戶參考號', { exact: true }).fill('合成首次確定內容')
  const itemRef = await fillItem(page, 0, 'LOCAL')
  await fillTask(page, 0, itemRef)
  const post = await loseCommittedResponse(page, 'POST', '/api/v1/orders', true)
  let current: Order
  try {
    await page.getByRole('button', { name: '建立草稿', exact: true }).click()
    const witness = await post.committed
    await expect(page.getByRole('heading', { name: '保存結果尚待確認', exact: true })).toBeVisible()
    expect(post.attempts()).toBe(1)
    await expect(page.getByLabel('客戶參考號', { exact: true })).toBeDisabled()
    await verifyCommitted(page, witness, customer)
    await refuseRetryWithRealCsrf(page, 'POST', '/api/v1/orders')
    expect(post.attempts()).toBe(2)
    expect(post.matchingRetry()).toBe(true)
    await verifyCommitted(page, witness, customer)
    current = await retryOriginal(page, 'POST', '/api/v1/orders', witness)
    expect(post.attempts()).toBe(3)
    expect(post.matchingRetry()).toBe(true)
    await verifyCommitted(page, witness, customer)
  } finally { await post.dispose() }
  const dispatcherContext = await newContext(browser, testInfo)
  try {
    const dispatcher = await dispatcherContext.newPage()
    await signedIn(dispatcher, fixtures.dispatcher)
    await page.getByLabel('客戶參考號', { exact: true }).fill('合成本機草稿仍在')
    await page.getByRole('group', { name: '項目 1', exact: true }).getByLabel('項目序號', { exact: true }).fill('2')
    await page.getByRole('group', { name: '任務 1', exact: true }).getByLabel('作業類型', { exact: true }).fill('合成本機任務仍在')
    const remoteDraft = { ...onlyDraft(current), customerReference: '合成遠端第一版' }
    const remote = await api<Order>(dispatcher, `/orders/${current.id}`, 'PUT', { expectedVersion: 0, draft: remoteDraft })
    expect(remote.status).toBe(200)
    expect(remote.data.version).toBe(1)
    let conflictWrites = 0
    let conflictKey: Promise<string | undefined> | undefined
    const observe = (request: Request) => { if (request.method() === 'PUT' && new URL(request.url()).pathname === `/api/v1/orders/${current.id}`) { conflictWrites++; conflictKey = request.allHeaders().then((headers) => headers['idempotency-key']).catch(() => undefined) } }
    page.on('request', observe)
    try {
      await saved(page, 'PUT', () => page.getByRole('button', { name: '保存草稿', exact: true }).click(), 409)
      await expect(page.getByRole('heading', { name: '版本衝突，本機草稿已保留', exact: true })).toBeVisible()
      await expect(page.getByLabel('客戶參考號', { exact: true })).toHaveValue('合成本機草稿仍在')
      await expect(page.getByRole('group', { name: '項目 1', exact: true }).getByLabel('項目序號', { exact: true })).toHaveValue('2')
      await expect(page.getByRole('group', { name: '任務 1', exact: true }).getByLabel('作業類型', { exact: true })).toHaveValue('合成本機任務仍在')
      expect(await page.getByRole('group', { name: '項目 1', exact: true }).getByLabel('項目參照', { exact: true }).inputValue() === itemRef).toBe(true)
      await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeDisabled()
      expect(conflictWrites).toBe(1)
      expect(isDeepStrictEqual((await api<Order>(page, `/orders/${current.id}`)).data, remote.data)).toBe(true)
      await page.getByRole('button', { name: '讀取伺服器版本比較', exact: true }).click()
      await expect(page.getByText(/已讀取伺服器第 1 版/)).toBeVisible()
      await screenshot(page, testInfo, 'order-ui-conflict-local-draft.png', true)
      await page.getByRole('button', { name: '捨棄本機內容，載入伺服器版本', exact: true }).click()
      await page.getByRole('button', { name: '取消版本處理', exact: true }).click()
      await expect(page.getByLabel('客戶參考號', { exact: true })).toHaveValue('合成本機草稿仍在')
      await page.getByRole('button', { name: '捨棄本機內容，載入伺服器版本', exact: true }).click()
      await page.getByRole('button', { name: '確認載入伺服器版本', exact: true }).click()
      await waitSaved(page, remote.data)
      await expect(page.getByLabel('客戶參考號', { exact: true })).toHaveValue('合成遠端第一版')
      expect(conflictWrites).toBe(1)
    } finally { page.off('request', observe) }
    await page.getByLabel('客戶參考號', { exact: true }).fill('合成明確保存第二版')
    const put = await loseCommittedResponse(page, 'PUT', `/api/v1/orders/${current.id}`, true)
    try {
      await page.getByRole('button', { name: '保存草稿', exact: true }).click()
      const witness = await put.committed
      expect(witness.data.version).toBe(2)
      expect(put.usesDifferentKey(await conflictKey)).toBe(true)
      await expect(page.getByRole('heading', { name: '保存結果尚待確認', exact: true })).toBeVisible()
      expect(put.attempts()).toBe(1)
      await expect(page.getByLabel('客戶參考號', { exact: true })).toBeDisabled()
      await verifyCommitted(page, witness, customer)
      await screenshot(page, testInfo, 'order-ui-uncertain-save.png', true)
      await refuseRetryWithRealCsrf(page, 'PUT', `/api/v1/orders/${current.id}`)
      expect(put.attempts()).toBe(2)
      expect(put.matchingRetry()).toBe(true)
      await verifyCommitted(page, witness, customer)
      current = await retryOriginal(page, 'PUT', `/api/v1/orders/${current.id}`, witness)
      expect(put.attempts()).toBe(3)
      expect(put.matchingRetry()).toBe(true)
      await verifyCommitted(page, witness, customer)
    } finally { await put.dispose() }
    expect(current.version).toBe(2)
    expect(await page.evaluate(() => localStorage.length === 0 && sessionStorage.length === 0)).toBe(true)
    await accessibility(page)
  } finally { await dispatcherContext.close() }
})

test('Order UI keeps disabled-customer history and raw values, while DRIVER has no navigation or API access', async ({ page, browser }, testInfo) => {
  test.setTimeout(120_000)
  const fixtures = orderUiFixtureFor(testInfo.project.name)
  await signedIn(page, fixtures.dispatcher)
  const customer = await createCustomer(page, testInfo, 'HISTORY')
  await filterCustomer(page, customer)
  const rawRemark = '合成\u0000歷史備註\r\n'
  const created = await api<Order>(page, '/orders', 'POST', { type: 'LOCAL', customerId: customer.id, remark: rawRemark, shipping: {} })
  expect(created.status).toBe(201)
  await page.getByRole('button', { name: '重新載入訂單列表', exact: true }).click()
  await page.getByRole('button', { name: `編輯訂單 ${created.data.orderNo}`, exact: true }).click()
  await waitSaved(page, created.data)
  const disabled = await api<Customer>(page, `/crm/customers/${customer.id}`, 'PATCH', { version: 0, name: '合成改名並停用的客戶', enabled: false })
  expect(disabled.status).toBe(200)
  await page.getByLabel('查詢有效客戶', { exact: true }).fill(customer.customerCode)
  const options = page.waitForResponse((response) => { const url = new URL(response.url()); return url.pathname === '/api/v1/crm/customer-options' && url.searchParams.get('q') === customer.customerCode })
  await page.getByRole('button', { name: '查詢客戶', exact: true }).click()
  expect((await options).status()).toBe(200)
  await expect(page.getByText('此頁沒有符合條件的有效客戶。', { exact: true })).toBeVisible()
  await expect(page.getByLabel('選擇客戶', { exact: true })).toHaveValue(customer.id)
  await expect(page.getByText(`客戶歷史名稱：${customer.name}`, { exact: true })).toBeVisible()
  await expect(page.getByText(/原值含特殊字元或換行/)).toBeVisible()
  await page.getByLabel('客戶參考號', { exact: true }).fill('合成同客戶歷史維護')
  const edited = await saved(page, 'PUT', () => page.getByRole('button', { name: '保存草稿', exact: true }).click())
  await waitSaved(page, edited)
  expect(edited.customerName).toBe(customer.name)
  expect(edited.remark === rawRemark).toBe(true)
  expect(edited.shipping !== null && Object.values(edited.shipping as Body).every((value) => value === null)).toBe(true)
  const checked = await acceptance(page, edited, false)
  expect((checked.missingFields as Body[]).some((issue) => issue.path === '/customerId' && issue.fieldId === 'OF04' && issue.ruleId === 'ORD-P01')).toBe(true)
  await accessibility(page)
  await screenshot(page, testInfo, 'order-ui-disabled-customer-history.png', true)
  await backToList(page)
  await page.getByRole('button', { name: `只看客戶 ${customer.name}`, exact: true }).click()
  await expect(page.getByRole('article', { name: `訂單 ${edited.orderNo}`, exact: true })).toContainText(customer.name)
  expect((await api(page, '/orders', 'POST', { type: 'LOCAL', customerId: customer.id })).status).toBe(400)
  const driverContext = await newContext(browser, testInfo)
  try {
    const driver = await driverContext.newPage()
    await driver.goto('/')
    await signIn(driver, fixtures.driver)
    await expect(driver.getByRole('heading', { name: '司機工作台', exact: true })).toBeVisible()
    await expect(driver.getByRole('button', { name: '訂單工作台', exact: true })).toHaveCount(0)
    for (const [path, method, body] of [
      ['/orders', 'GET', undefined], ['/orders', 'POST', { type: 'LOCAL', customerId: customer.id }],
      [`/orders/${edited.id}`, 'GET', undefined], [`/orders/${edited.id}`, 'PUT', { expectedVersion: edited.version, draft: onlyDraft(edited) }],
      [`/orders/${edited.id}/acceptance-check`, 'GET', undefined],
    ] as const) {
      const response = await api(driver, path, method, body)
      expect(response.status).toBe(403)
      expect(response.code).toBe('FORBIDDEN')
    }
    await accessibility(driver)
  } finally { await driverContext.close() }
})
