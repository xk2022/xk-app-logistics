import { readFileSync } from 'node:fs'
import { mkdir, writeFile } from 'node:fs/promises'
import { dirname } from 'node:path'
import { randomUUID } from 'node:crypto'
import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import { signIn, tomFixtureFor } from './fixtures'

// The runtime and private fixture are prepared by the isolated runner, never by this suite.
test.describe.configure({ retries: 0 })

type Body = Record<string, unknown>
type Schema = 'OrderEnvelope' | 'OrderPageEnvelope' | 'AcceptanceCheckEnvelope' | 'ErrorEnvelope'
interface Captured { schema: Schema; value: unknown }
interface Reply { status: number; body: Body; contentType: string | null; location: string | null; replayed: string | null }
interface ItemRead extends Body { id: string; clientRef: string }
interface TaskRead extends Body { id: string; taskNo: string; orderId: string; itemId: string; clientRef: string; itemRef: string; predecessorRefs: string[]; predecessorTaskIds: string[] }
interface OrderRead extends Body {
  id: string; orderNo: string; type: string; customerId: string; customerName: string; version: number;
  lifecycleStatus: string; acceptanceStatus: string; createdAt: string; updatedAt: string;
  createdBy: string; updatedBy: string; shipping: Body | null; items: ItemRead[]; tasks: TaskRead[];
}
interface Customer { id: string; customerCode: string; name: string; enabled: boolean; version: number }
interface Account { id: string; username: string; displayName: string; role: string; enabled: boolean; version: number }
interface ContractExamples {
  schemaPositive: { id: string; schema: string; value: Body }[]
  graphCases: { id: string; expected: string; draft: Body }[]
}
const examples = JSON.parse(readFileSync('docs/06_api/order-draft-contract-examples.json', 'utf8')) as ContractExamples
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const instantPattern = /^\d{4}-\d{2}-\d{2}[Tt](?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,6})?(?:[Zz]|[+-](?:[01]\d|2[0-3]):[0-5]\d)$/
const orderKeys = ['id', 'orderNo', 'type', 'customerId', 'customerName', 'lifecycleStatus', 'acceptanceStatus', 'version', 'createdAt', 'updatedAt', 'customerReference', 'remark', 'shipping', 'items', 'tasks', 'createdBy', 'updatedBy']
const itemKeys = ['id', 'clientRef', 'sequence', 'itemKind', 'containerType', 'containerNo', 'packageQty', 'grossWeight', 'cbm', 'lastPickupDate', 'deliveryCutoffAt']
const taskKeys = ['id', 'taskNo', 'orderId', 'itemId', 'clientRef', 'itemRef', 'taskKind', 'pickupName', 'pickupAddress', 'deliveryName', 'deliveryAddress', 'siteContact', 'plannedStartAt', 'plannedEndAt', 'predecessorRefs', 'requiredTask', 'predecessorTaskIds']

function exactKeys(value: Body, keys: string[]): void { expect(Object.keys(value).sort()).toEqual([...keys].sort()) }
function issue(value: Body): void {
  exactKeys(value, ['path', 'fieldId', 'ruleId', 'message'])
  for (const key of ['path', 'fieldId', 'ruleId', 'message']) expect(typeof value[key]).toBe('string')
}
function envelope(reply: Reply, status: number, code = 'OK'): void {
  expect(reply.status).toBe(status)
  expect(reply.contentType?.split(';')[0].trim().toLowerCase()).toBe('application/json')
  exactKeys(reply.body, code === 'OK' ? ['code', 'message', 'data', 'traceId'] : ['code', 'message', 'data', 'traceId', 'fieldErrors'])
  expect(reply.body.code).toBe(code)
  expect(typeof reply.body.message).toBe('string')
  expect(typeof reply.body.traceId).toBe('string')
  expect((reply.body.traceId as string).trim().length).toBeGreaterThan(0)
  expect((reply.body.traceId as string).length).toBeLessThanOrEqual(200)
  if (code !== 'OK') {
    expect((reply.body.message as string).trim().length).toBeGreaterThan(0)
    expect((reply.body.message as string).length).toBeLessThanOrEqual(2000)
    expect(Array.isArray(reply.body.fieldErrors)).toBe(true)
    for (const value of reply.body.fieldErrors as Body[]) issue(value)
    if (reply.body.data !== null) {
      expect(Object.keys(reply.body.data as Body).every((key) => key === 'actualVersion')).toBe(true)
      if ('actualVersion' in (reply.body.data as Body)) {
        expect(Number.isSafeInteger((reply.body.data as Body).actualVersion)).toBe(true)
        expect((reply.body.data as Body).actualVersion as number).toBeGreaterThanOrEqual(0)
      }
    }
  }
}
function order(reply: Reply, status = 200): OrderRead {
  envelope(reply, status)
  const value = reply.body.data as OrderRead
  exactKeys(value, orderKeys)
  for (const key of ['id', 'customerId', 'createdBy', 'updatedBy']) expect(value[key]).toMatch(uuidPattern)
  expect(value.orderNo.trim().length).toBeGreaterThan(0)
  expect(value.orderNo.length).toBeLessThanOrEqual(100)
  expect(value.lifecycleStatus).toBe('OPEN')
  expect(value.acceptanceStatus).toBe('DRAFT')
  expect(Number.isSafeInteger(value.version) && value.version >= 0).toBe(true)
  expect(value.createdAt).toMatch(instantPattern)
  expect(value.updatedAt).toMatch(instantPattern)
  expect(Array.isArray(value.items) && Array.isArray(value.tasks)).toBe(true)
  for (const item of value.items) { exactKeys(item, itemKeys); expect(item.id).toMatch(uuidPattern) }
  for (const task of value.tasks) {
    exactKeys(task, taskKeys)
    expect(task.id).toMatch(uuidPattern)
    expect(task.orderId).toBe(value.id)
    expect(task.itemId).toBe(value.items.find((item) => item.clientRef === task.itemRef)?.id)
    expect([...task.predecessorTaskIds].sort()).toEqual(task.predecessorRefs.map((ref) => value.tasks.find((item) => item.clientRef === ref)?.id).sort())
    expect(task.requiredTask === true || task.requiredTask === null).toBe(true)
    if (task.siteContact !== null) exactKeys(task.siteContact as Body, ['name', 'phone'])
  }
  if (value.shipping !== null) exactKeys(value.shipping, ['shippingCompany', 'vesselVoyage', 'pol', 'pod', 'eta', 'etd', 'blNo'])
  return value
}
function check(reply: Reply, version: number, outcome: 'COMPLETE' | 'INCOMPLETE'): Body {
  envelope(reply, 200)
  const data = reply.body.data as Body
  exactKeys(data, ['orderId', 'version', 'checkedRules', 'confirmedRulesOutcome', 'missingFields', 'pendingPolicyIds', 'operationalAcceptance'])
  expect(data.version).toBe(version)
  expect([...(data.checkedRules as string[])].sort()).toEqual(['ORD-P01', 'ORD-P02', 'ORD-P03'])
  expect(data.confirmedRulesOutcome).toBe(outcome)
  expect(data.operationalAcceptance).toBe('NOT_GRANTED')
  expect(Array.isArray(data.missingFields) && Array.isArray(data.pendingPolicyIds)).toBe(true)
  expect(new Set(data.pendingPolicyIds as string[]).size).toBe((data.pendingPolicyIds as string[]).length)
  for (const value of data.missingFields as Body[]) issue(value)
  if (outcome === 'COMPLETE') expect(data.missingFields).toEqual([])
  else expect((data.missingFields as Body[]).length).toBeGreaterThan(0)
  return data
}
function draft(id: string, customerId: string): Body {
  const value = examples.schemaPositive.find((example) => example.id === id)?.value
  if (!value) throw new Error('The committed synthetic order contract example is missing.')
  return { ...structuredClone(value), customerId }
}
function reverseProperties(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(reverseProperties)
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).reverse().map(([key, child]) => [key, reverseProperties(child)]))
  return value
}
function byRef<T extends { ref: string }>(values: T[]): T[] { return values.sort((a, b) => a.ref < b.ref ? -1 : a.ref > b.ref ? 1 : 0) }
function comparableOrder(value: OrderRead): Body {
  return { ...value, items: [...value.items].sort((a, b) => a.clientRef < b.clientRef ? -1 : a.clientRef > b.clientRef ? 1 : 0),
    tasks: value.tasks.map((task) => ({ ...task, predecessorRefs: [...task.predecessorRefs].sort(), predecessorTaskIds: [...task.predecessorTaskIds].sort() }))
      .sort((a, b) => a.clientRef < b.clientRef ? -1 : a.clientRef > b.clientRef ? 1 : 0) }
}

async function request(page: Page, path: string, method = 'GET', body?: unknown, key?: string, csrf: 'valid' | 'omit' | 'invalid' = 'valid', raw?: string): Promise<Reply> {
  return page.evaluate(async ({ path, method, body, key, csrf, raw }) => {
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (method !== 'GET') {
      headers['Content-Type'] = 'application/json'
      if (csrf === 'valid') {
        const token = (await (await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' })).json()).data
        headers[token.headerName] = token.token
      } else if (csrf === 'invalid') headers['X-CSRF-TOKEN'] = 'synthetic-invalid-csrf'
      if (key !== undefined) headers['Idempotency-Key'] = key
    }
    const response = await fetch(`/api/v1${path}`, { method, credentials: 'same-origin', headers,
      ...(raw !== undefined ? { body: raw } : body !== undefined ? { body: JSON.stringify(body) } : {}) })
    return { status: response.status, body: await response.json(), contentType: response.headers.get('content-type'), location: response.headers.get('location'), replayed: response.headers.get('idempotency-replayed') }
  }, { path, method, body, key, csrf, raw })
}
async function upmsOrCrm<T>(page: Page, path: string, method = 'GET', body?: unknown, status = 200): Promise<T> {
  const response = await request(page, path, method, body)
  expect(response.status).toBe(status)
  expect(response.body.code).toBe('0000')
  return response.body.data as T
}

async function createWithAliasOnly(page: Page, context: BrowserContext, body: unknown, key: string): Promise<Reply> {
  // Keep the CSRF secret inside one pending browser evaluation. Only a boolean
  // readiness flag crosses the boundary while the canonical cookie is removed.
  const previousAlias = (await context.cookies()).find((cookie) => cookie.name === 'JSESSIONID')
  const pending = page.evaluate(async ({ body, key }) => {
    const token = (await (await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' })).json()).data
    await new Promise<void>((resolve) => {
      document.addEventListener('tom-j-only-proceed', () => {
        delete document.documentElement.dataset.tomCsrfReady
        resolve()
      }, { once: true })
      document.documentElement.dataset.tomCsrfReady = 'true'
    })
    const response = await fetch('/api/v1/orders', {
      method: 'POST', credentials: 'same-origin',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json', [token.headerName]: token.token, 'Idempotency-Key': key },
      body: JSON.stringify(body),
    })
    return { status: response.status, body: await response.json(), contentType: response.headers.get('content-type'), location: response.headers.get('location'), replayed: response.headers.get('idempotency-replayed') }
  }, { body, key })
  // Context cleanup rejects the pending browser call if a precondition fails.
  void pending.catch(() => undefined)
  await page.waitForFunction(() => document.documentElement.dataset.tomCsrfReady === 'true')
  await context.clearCookies({ name: 'XKSESSION' })
  const cookies = await context.cookies()
  expect(cookies.some((cookie) => cookie.name === 'XKSESSION')).toBe(false)
  expect(Boolean(previousAlias && cookies.find((cookie) => cookie.name === 'JSESSIONID')?.value === previousAlias.value)).toBe(true)
  await page.evaluate(() => document.dispatchEvent(new Event('tom-j-only-proceed')))
  return pending
}

test('TOM preserves the five original order APIs, role guards, replay, versions, and CRM snapshots over TLS', async ({ page, browser, context }, testInfo) => {
  test.setTimeout(180_000)
  const fixtures = tomFixtureFor(testInfo.project.name)
  const captured: Captured[] = []
  const api = async (member: Page, path: string, method = 'GET', body?: unknown, key?: string, csrf: 'valid' | 'omit' | 'invalid' = 'valid', raw?: string): Promise<Reply> => {
    const response = await request(member, path, method, body, key, csrf, raw)
    const schema: Schema = response.status >= 400 ? 'ErrorEnvelope' : path.includes('/acceptance-check') ? 'AcceptanceCheckEnvelope' : method === 'GET' && path.split('?')[0] === '/orders' ? 'OrderPageEnvelope' : 'OrderEnvelope'
    captured.push({ schema, value: response.body })
    return response
  }
  const use = testInfo.project.use
  const contextOptions = { baseURL: use.baseURL, ignoreHTTPSErrors: use.ignoreHTTPSErrors, viewport: use.viewport, screen: use.screen,
    deviceScaleFactor: use.deviceScaleFactor, isMobile: use.isMobile, hasTouch: use.hasTouch, userAgent: use.userAgent }
  const dispatcherContext = await browser.newContext(contextOptions)
  const driverContext = await browser.newContext(contextOptions)
  try {
    await page.goto('/')
    expect(new URL(page.url()).protocol).toBe('https:')
    const absentId = randomUUID()
    const minimal = { type: 'IMPORT', customerId: absentId }
    envelope(await api(page, '/orders'), 401, 'UNAUTHENTICATED')
    envelope(await api(page, '/orders', 'POST', minimal, randomUUID(), 'omit'), 401, 'UNAUTHENTICATED')
    expect(await page.evaluate(async () => (await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' })).ok)).toBe(true)
    const anonymousSession = (await context.cookies()).find((cookie) => cookie.name === 'XKSESSION')
    await signIn(page, fixtures.admin)
    await expect(page.getByRole('heading', { name: '管理員工作台', exact: true })).toBeVisible()
    const authenticatedCookies = await context.cookies()
    const canonicalSession = authenticatedCookies.find((cookie) => cookie.name === 'XKSESSION')
    const aliasSession = authenticatedCookies.find((cookie) => cookie.name === 'JSESSIONID')
    expect(Boolean(anonymousSession && canonicalSession && anonymousSession.value !== canonicalSession.value)).toBe(true)
    expect(Boolean(canonicalSession && aliasSession && canonicalSession.value === aliasSession.value && aliasSession.secure && aliasSession.httpOnly && aliasSession.sameSite === 'Lax' && aliasSession.path === '/')).toBe(true)
    const prefix = `TOM_${testInfo.project.name === 'chromium' ? 'D' : 'M'}_${randomUUID().replaceAll('-', '').slice(0, 12).toUpperCase()}`
    let customerA = await upmsOrCrm<Customer>(page, '/crm/customers', 'POST', { customerCode: `${prefix}_A`, name: '合成 TOM 客戶甲' }, 201)
    const customerB = await upmsOrCrm<Customer>(page, '/crm/customers', 'POST', { customerCode: `${prefix}_B`, name: '合成 TOM 客戶乙' }, 201)
    const initialName = customerA.name
    const query = `/orders?customerId=${customerA.id}&page=0&size=5&sort=orderNo,asc`
    const empty = await api(page, query)
    envelope(empty, 200)
    exactKeys(empty.body.data as Body, ['content', 'page', 'size', 'totalElements', 'totalPages'])
    expect((empty.body.data as Body).content).toEqual([])
    expect((empty.body.data as Body).totalElements).toBe(0)
    envelope(await api(page, `/orders/${absentId}`), 404, 'ORDER_NOT_FOUND')
    envelope(await api(page, '/orders?page=-1'), 400, 'VALIDATION_ERROR')
    envelope(await api(page, '/orders', 'POST', { type: 'LOCAL', customerId: customerA.id }), 400, 'VALIDATION_ERROR')
    envelope(await api(page, '/orders', 'POST', { type: 'LOCAL', customerId: customerA.id }, randomUUID(), 'omit'), 403, 'CSRF_INVALID')
    envelope(await api(page, `/orders/${absentId}`, 'PUT', { expectedVersion: 0, draft: minimal }, randomUUID(), 'invalid'), 403, 'CSRF_INVALID')
    const originalBody = { type: 'IMPORT', customerId: customerA.id }
    const postKey = randomUUID()
    const first = await api(page, '/orders', 'POST', originalBody, postKey)
    let current = order(first, 201)
    expect(first.replayed).toBe('false')
    expect(first.location).toBeTruthy()
    expect(first.location).toBe(`/api/v1/orders/${current.id}`)
    expect(current.version).toBe(0)
    expect(current.customerName).toBe(initialName)
    expect(current.shipping).toBeNull()
    expect(current.items).toEqual([])
    expect(current.tasks).toEqual([])
    const replay = await api(page, '/orders', 'POST', reverseProperties(originalBody), postKey)
    expect(order(replay, 201)).toEqual(current)
    expect(replay.location).toBe(first.location)
    expect(replay.replayed).toBe('true')
    expect(replay.body.traceId).not.toBe(first.body.traceId)
    envelope(await api(page, '/orders', 'POST', { ...originalBody, remark: null }, postKey), 409, 'IDEMPOTENCY_KEY_REUSED')
    check(await api(page, `/orders/${current.id}/acceptance-check`), 0, 'INCOMPLETE')
    expect(comparableOrder(order(await api(page, `/orders/${current.id}`)))).toEqual(comparableOrder(current))
    await context.clearCookies({ name: 'XKSESSION' })
    expect((await context.cookies()).some((cookie) => cookie.name === 'XKSESSION')).toBe(false)
    expect(order(await api(page, `/orders/${current.id}`)).id).toBe(current.id)
    const aliasOnlyCreate = await createWithAliasOnly(page, context, { type: 'LOCAL', customerId: customerB.id }, randomUUID())
    captured.push({ schema: aliasOnlyCreate.status >= 400 ? 'ErrorEnvelope' : 'OrderEnvelope', value: aliasOnlyCreate.body })
    expect(order(aliasOnlyCreate, 201).customerName).toBe(customerB.name)

    for (const type of ['IMPORT', 'EXPORT', 'LOCAL']) {
      const created = order(await api(page, '/orders', 'POST', draft(`DEMO-${type}-CONTRACT-create`, customerA.id), randomUUID()), 201)
      check(await api(page, `/orders/${created.id}/acceptance-check`), 0, 'COMPLETE')
      expect(comparableOrder(order(await api(page, `/orders/${created.id}`)))).toEqual(comparableOrder(created))
    }
    const listed = await api(page, query)
    envelope(listed, 200)
    const pageData = listed.body.data as Body
    expect(pageData.totalElements).toBe(4)
    expect((pageData.content as Body[]).map((item) => item.orderNo)).toEqual([...(pageData.content as Body[])].map((item) => item.orderNo).sort())
    const localPage = await api(page, `/orders?customerId=${customerA.id}&type=LOCAL&size=1`)
    envelope(localPage, 200)
    expect((localPage.body.data as Body).totalElements).toBe(1)
    expect(((localPage.body.data as Body).content as Body[])[0].type).toBe('LOCAL')

    customerA = await upmsOrCrm<Customer>(page, `/crm/customers/${customerA.id}`, 'PATCH', { name: '合成 TOM 已更名客戶甲', version: customerA.version })
    expect(order(await api(page, `/orders/${current.id}`)).customerName).toBe(initialName)
    const localDraft = draft('DEMO-LOCAL-CONTRACT-create', customerA.id)
    const putBody = { expectedVersion: 0, draft: localDraft }
    const putKey = randomUUID()
    const firstPut = await api(page, `/orders/${current.id}`, 'PUT', putBody, putKey)
    current = order(firstPut)
    expect(current.version).toBe(1)
    expect(current.customerName).toBe(initialName)
    expect(firstPut.replayed).toBe('false')
    const stableItems = byRef(current.items.map((item) => ({ id: item.id, ref: item.clientRef })))
    const stableTasks = byRef(current.tasks.map((task) => ({ id: task.id, no: task.taskNo, ref: task.clientRef })))
    const literalRemark = '  e\u0301 合成第二次完整替換  '
    const literalReference = '  合成客戶參照  '
    current = order(await api(page, `/orders/${current.id}`, 'PUT', { expectedVersion: 1, draft: { ...localDraft, remark: literalRemark, customerReference: literalReference } }, randomUUID()))
    expect(current.version).toBe(2)
    expect(current.remark).toBe(literalRemark)
    expect(current.customerReference).toBe(literalReference)
    expect(byRef(current.items.map((item) => ({ id: item.id, ref: item.clientRef })))).toEqual(stableItems)
    expect(byRef(current.tasks.map((task) => ({ id: task.id, no: task.taskNo, ref: task.clientRef })))).toEqual(stableTasks)
    const replayPut = await api(page, `/orders/${current.id}`, 'PUT', reverseProperties(putBody), putKey)
    expect(order(replayPut)).toEqual(firstPut.body.data)
    expect(replayPut.replayed).toBe('true')
    expect(replayPut.body.traceId).not.toBe(firstPut.body.traceId)
    const normalizedPathReplay = await api(page, `/orders/${current.id.toUpperCase()}`, 'PUT', putBody, putKey)
    expect(order(normalizedPathReplay)).toEqual(firstPut.body.data)
    expect(normalizedPathReplay.replayed).toBe('true')
    expect(comparableOrder(order(await api(page, `/orders/${current.id}`)))).toEqual(comparableOrder(current))
    const stale = await api(page, `/orders/${current.id}`, 'PUT', { expectedVersion: 0, draft: localDraft }, randomUUID())
    envelope(stale, 409, 'VERSION_CONFLICT')
    expect(stale.body.data).toEqual({ actualVersion: 2 })
    const reversedTasks = { ...putBody, draft: { ...localDraft, tasks: [...localDraft.tasks as unknown[]].reverse() } }
    envelope(await api(page, `/orders/${current.id}`, 'PUT', reversedTasks, putKey), 409, 'IDEMPOTENCY_KEY_REUSED')

    const cyclic = structuredClone(examples.graphCases.find((value) => value.id === 'cyclic-predecessors')!.draft)
    cyclic.customerId = customerA.id
    const failedKey = randomUUID()
    envelope(await api(page, `/orders/${current.id}`, 'PUT', { expectedVersion: 2, draft: cyclic }, failedKey), 400, 'VALIDATION_ERROR')
    expect(comparableOrder(order(await api(page, `/orders/${current.id}`)))).toEqual(comparableOrder(current))
    current = order(await api(page, `/orders/${current.id}`, 'PUT', { expectedVersion: 2, draft: { ...localDraft, remark: literalRemark, customerReference: literalReference } }, failedKey))
    expect(current.version).toBe(3)
    const unknownField = { ...originalBody, id: randomUUID() }
    envelope(await api(page, '/orders', 'POST', unknownField, randomUUID()), 400, 'VALIDATION_ERROR')
    envelope(await api(page, '/orders', 'POST', undefined, randomUUID(), 'valid', `{"type":"LOCAL","type":"IMPORT","customerId":"${customerA.id}"}`), 400, 'VALIDATION_ERROR')
    current = order(await api(page, `/orders/${current.id}`, 'PUT', { expectedVersion: 3, draft: { type: 'LOCAL', customerId: customerA.id, shipping: {} } }, randomUUID()))
    expect(current.version).toBe(4)
    expect(current.items).toEqual([])
    expect(current.tasks).toEqual([])
    expect(current.customerReference).toBeNull()
    expect(current.remark).toBeNull()
    expect(current.shipping).toEqual({ shippingCompany: null, vesselVoyage: null, pol: null, pod: null, eta: null, etd: null, blNo: null })
    const contactDraft = structuredClone(localDraft)
    ;(contactDraft.tasks as Body[])[0].siteContact = {}
    current = order(await api(page, `/orders/${current.id}`, 'PUT', { expectedVersion: 4, draft: contactDraft }, randomUUID()))
    expect(current.version).toBe(5)
    expect(current.shipping).toBeNull()
    expect(current.tasks.find((task) => task.clientRef === 'task-1')?.siteContact).toEqual({ name: null, phone: null })
    expect(current.tasks.find((task) => task.clientRef === 'task-2')?.siteContact).toBeNull()

    const dispatcher = await dispatcherContext.newPage()
    await dispatcher.goto('/')
    await signIn(dispatcher, fixtures.dispatcher)
    await expect(dispatcher.getByRole('heading', { name: '調度員工作台', exact: true })).toBeVisible()
    const dispatcherPost = await api(dispatcher, '/orders', 'POST', originalBody, postKey)
    let dispatcherOrder = order(dispatcherPost, 201)
    expect(dispatcherOrder.id).not.toBe((first.body.data as Body).id)
    expect(dispatcherOrder.customerName).toBe(customerA.name)
    expect(dispatcherPost.replayed).toBe('false')
    envelope(await api(dispatcher, query), 200)
    expect(order(await api(dispatcher, `/orders/${current.id}`)).id).toBe(current.id)
    dispatcherOrder = order(await api(dispatcher, `/orders/${dispatcherOrder.id}`, 'PUT', { expectedVersion: 0, draft: localDraft }, randomUUID()))
    check(await api(dispatcher, `/orders/${dispatcherOrder.id}/acceptance-check`), 1, 'COMPLETE')

    const driver = await driverContext.newPage()
    await driver.goto('/')
    await signIn(driver, fixtures.driver)
    await expect(driver.getByRole('heading', { name: '司機工作台', exact: true })).toBeVisible()
    for (const [path, method, body, key] of [
      ['/orders', 'POST', originalBody, postKey], ['/orders', 'GET'], [`/orders/${current.id}`, 'GET'],
      [`/orders/${current.id}`, 'PUT', { expectedVersion: current.version, draft: localDraft }, randomUUID()],
      [`/orders/${current.id}/acceptance-check`, 'GET'],
    ] as [string, string, unknown?, string?][]) envelope(await api(driver, path, method, body, key), 403, 'FORBIDDEN')

    customerA = await upmsOrCrm<Customer>(page, `/crm/customers/${customerA.id}`, 'PATCH', { enabled: false, version: customerA.version })
    envelope(await api(page, '/orders', 'POST', originalBody, randomUUID()), 400, 'INVALID_CUSTOMER')
    const disabledReplay = await api(page, '/orders', 'POST', originalBody, postKey)
    expect(order(disabledReplay, 201)).toEqual(first.body.data)
    expect(disabledReplay.replayed).toBe('true')
    const historicalPutReplay = await api(page, `/orders/${current.id}`, 'PUT', putBody, putKey)
    expect(order(historicalPutReplay)).toEqual(firstPut.body.data)
    expect(historicalPutReplay.replayed).toBe('true')
    const disabledCheck = check(await api(page, `/orders/${current.id}/acceptance-check`), current.version, 'INCOMPLETE')
    expect((disabledCheck.missingFields as Body[]).some((field) => field.ruleId === 'ORD-P01')).toBe(true)
    current = order(await api(page, `/orders/${current.id}`, 'PUT', { expectedVersion: current.version, draft: localDraft }, randomUUID()))
    expect(current.customerName).toBe(initialName)
    current = order(await api(page, `/orders/${current.id}`, 'PUT', { expectedVersion: current.version, draft: { ...localDraft, customerId: customerB.id } }, randomUUID()))
    expect(current.customerName).toBe(customerB.name)
    expect(current.customerId).toBe(customerB.id)
    check(await api(page, `/orders/${current.id}/acceptance-check`), current.version, 'COMPLETE')

    const accounts = await upmsOrCrm<Account[]>(page, '/upms/accounts')
    const dispatcherAccount = accounts.find((account) => account.username === fixtures.dispatcher.username)
    if (!dispatcherAccount) throw new Error('The isolated TOM dispatcher account is missing.')
    await upmsOrCrm(page, `/upms/accounts/${dispatcherAccount.id}`, 'PATCH', { displayName: dispatcherAccount.displayName, role: 'DISPATCHER', enabled: false, version: dispatcherAccount.version })
    envelope(await api(dispatcher, '/orders', 'POST', originalBody, postKey), 401, 'UNAUTHENTICATED')
    const beforeLogoutAlias = (await context.cookies()).find((cookie) => cookie.name === 'JSESSIONID')
    await page.getByRole('button', { name: '登出', exact: true }).click()
    await expect(page.getByRole('heading', { name: '登入工作空間', exact: true })).toBeVisible()
    const afterLogoutAlias = (await context.cookies()).find((cookie) => cookie.name === 'JSESSIONID')
    expect(Boolean(beforeLogoutAlias && (!afterLogoutAlias || beforeLogoutAlias.value !== afterLogoutAlias.value))).toBe(true)
    envelope(await api(page, '/orders', 'POST', originalBody, postKey), 401, 'UNAUTHENTICATED')
  } finally {
    await Promise.allSettled([dispatcherContext.close(), driverContext.close()])
    // Only synthetic order responses are retained: no credentials, request context, headers, cookies, or tokens.
    const output = testInfo.outputPath('order-contract-responses.json')
    await mkdir(dirname(output), { recursive: true })
    await writeFile(output, JSON.stringify(captured, null, 2), 'utf8')
  }
})
