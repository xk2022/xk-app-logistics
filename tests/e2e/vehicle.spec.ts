import { randomUUID } from 'node:crypto'
import { writeFileSync } from 'node:fs'
import { expect, test, type Page, type Response, type TestInfo } from '@playwright/test'
import { fmsFixtureFor, signIn, type Credential } from './fixtures'

// Every viewport owns independent accounts and ADM items. This suite never
// starts, seeds, or resets a runtime, and never changes the original 18 cases.
test.describe.configure({ retries: 0 })

type Status = 'AVAILABLE' | 'MAINTENANCE' | 'INACTIVE'
interface Vehicle {
  id: string; plateNo: string; vehicleType: string; vehicleTypeCode: string; vehicleTypeLabel: string
  vehicleTypeEnabled: boolean; capacityTon: string | null; registrationNo: string | null
  registrationDate: string | null; inspectionDueDate: string | null; administrativeStatus: Status; version: number
}
interface VehicleType { id: string; code: string; label: string }
interface PageData<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }
interface Reply<T> { status: number; code: string; data: T }
type Schema = 'VehicleEnvelope' | 'PageVehicleEnvelope' | 'VehicleTypesEnvelope' | 'PageVehicleOptionEnvelope' | 'ErrorEnvelope'
interface Captured { schema: Schema; value: unknown }

class ResponseCollector {
  readonly values: Captured[] = []
  private readonly pending = new Set<Promise<void>>()
  private readonly capture = (response: Response) => {
    const path = new URL(response.url()).pathname
    const method = response.request().method()
    const list = path === '/api/v1/fms/vehicles' && method === 'GET'
    const write = path === '/api/v1/fms/vehicles' && method === 'POST'
    const detail = /^\/api\/v1\/fms\/vehicles\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(path) && ['GET', 'PATCH'].includes(method)
    const types = path === '/api/v1/fms/vehicle-types' && method === 'GET'
    const options = path === '/api/v1/fms/vehicle-options' && method === 'GET'
    if (!list && !write && !detail && !types && !options) return
    const pending = (async () => {
      try {
        const value: unknown = await response.json()
        const schema: Schema = response.status() >= 400 ? 'ErrorEnvelope' : list ? 'PageVehicleEnvelope' : types ? 'VehicleTypesEnvelope' : options ? 'PageVehicleOptionEnvelope' : 'VehicleEnvelope'
        // Only public synthetic response bodies are persisted; no requests,
        // authentication headers, cookies, CSRF values, or credentials.
        this.values.push({ schema, value })
      } catch { /* A deliberately aborted response has no JSON body to validate. */ }
    })()
    this.pending.add(pending)
    void pending.then(() => this.pending.delete(pending), () => this.pending.delete(pending))
  }
  constructor(private readonly page: Page) { page.on('response', this.capture) }
  async finish(): Promise<void> {
    this.page.off('response', this.capture)
    await Promise.allSettled([...this.pending])
  }
}

const collectors = new Map<string, ResponseCollector>()
test.beforeEach(async ({ page }, info) => { collectors.set(info.testId, new ResponseCollector(page)) })
test.afterEach(async ({}, info) => {
  const collector = collectors.get(info.testId)
  if (!collector) return
  await collector.finish()
  writeFileSync(info.outputPath('vehicle-contract-responses.json'), JSON.stringify(collector.values))
  collectors.delete(info.testId)
})

async function api<T>(page: Page, path: string, method = 'GET', body?: unknown): Promise<Reply<T>> {
  return page.evaluate(async ({ path, method, body }) => {
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (method !== 'GET') {
      const csrf = (await (await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' })).json()).data
      headers[csrf.headerName] = csrf.token
      headers['Content-Type'] = 'application/json'
    }
    const response = await fetch(`/api/v1${path}`, { method, credentials: 'same-origin', headers,
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
    const payload = await response.json()
    return { status: response.status, code: payload.code, data: payload.data }
  }, { path, method, body })
}

async function enter(page: Page, credential: Credential): Promise<void> {
  await page.goto('/')
  expect(new URL(page.url()).protocol).toBe('https:')
  await signIn(page, credential)
  await page.getByRole('button', { name: '車輛管理', exact: true }).click()
  await expect(page.getByRole('heading', { name: '車輛管理', exact: true })).toBeVisible()
}

async function save(page: Page, button: string, method: 'POST' | 'PATCH', id?: string, status = method === 'POST' ? 201 : 200): Promise<Reply<Vehicle>> {
  const path = `/api/v1/fms/vehicles${id ? `/${id}` : ''}`
  const pending = page.waitForResponse((response) => response.request().method() === method && new URL(response.url()).pathname === path)
  await page.getByRole('button', { name: button, exact: true }).click()
  const response = await pending
  expect(response.status()).toBe(status)
  const payload = await response.json()
  expect(payload.code).toBe(status < 400 ? '0000' : 'E0409')
  return { status: response.status(), code: payload.code, data: payload.data as Vehicle }
}

async function search(page: Page, q: string, vehicleType = '', status = 'all', size = '5'): Promise<void> {
  const normalized = q.normalize('NFC').trim()
  const form = page.getByRole('form', { name: '車輛查詢', exact: true })
  await form.getByLabel('車牌搜尋', { exact: true }).fill(q)
  await form.getByLabel('車種篩選', { exact: true }).selectOption(vehicleType)
  await form.getByLabel('管理狀態篩選', { exact: true }).selectOption(status)
  await form.getByLabel('每頁筆數', { exact: true }).selectOption(size)
  const pending = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return response.request().method() === 'GET' && url.pathname === '/api/v1/fms/vehicles' && (url.searchParams.get('q') ?? '') === normalized
  })
  await form.getByRole('button', { name: '搜尋車輛', exact: true }).click()
  expect((await pending).status()).toBe(200)
}

async function inspect(page: Page, plate: string): Promise<void> {
  await page.getByRole('button', { name: `檢視車輛 ${plate}`, exact: true }).click()
  await expect(page.getByLabel('車牌', { exact: true })).toHaveValue(plate)
}

async function backToList(page: Page): Promise<void> {
  await expect(page.getByText('目前內容與已保存資料一致。', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '返回車輛列表', exact: true }).click()
  await expect(page.getByRole('form', { name: '車輛查詢', exact: true })).toBeVisible()
}

async function options(page: Page, q: string): Promise<void> {
  const region = page.getByRole('region', { name: '主檔可用車輛選項', exact: true })
  if (!await region.isVisible()) await page.getByRole('button', { name: '查看主檔可用選項', exact: true }).click()
  await region.getByLabel('可用車輛搜尋', { exact: true }).fill(q)
  const pending = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return url.pathname === '/api/v1/fms/vehicle-options' && url.searchParams.get('q') === q
  })
  await region.getByRole('button', { name: '搜尋可用車輛', exact: true }).click()
  expect((await pending).status()).toBe(200)
  await expect(region).toContainText(/不代表.*派工/)
}

async function a11y(page: Page): Promise<void> {
  expect(await page.locator('input:not([type="hidden"]), textarea, select').evaluateAll((elements) => elements
    .filter((element) => element.getClientRects().length > 0)
    .every((element) => Boolean((element as HTMLInputElement).labels?.length || element.getAttribute('aria-label') || element.getAttribute('aria-labelledby'))))).toBe(true)
  expect(await page.locator('[id]').evaluateAll((elements) => new Set(elements.map((element) => element.id)).size === elements.length)).toBe(true)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true)
}

async function evidence(page: Page, info: TestInfo, name: string): Promise<void> {
  await a11y(page)
  await page.evaluate(() => { if (document.activeElement instanceof HTMLElement) document.activeElement.blur(); window.scrollTo(0, 0) })
  await page.screenshot({ path: info.outputPath(name), fullPage: true })
}

test('FMS preserves vehicle masters, management roles, filters, versions, archived types, exact values, and DRIVER refusal over TLS', async ({ page }, info) => {
  test.setTimeout(240_000)
  const fixture = fmsFixtureFor(info.project.name)
  const prefix = `XF${info.project.name === 'chromium' ? 'D' : 'M'}${randomUUID().replaceAll('-', '').slice(0, 10).toUpperCase()}`
  const plate = `${prefix}-A-STRASSE`
  const exactDecimal = `0.${'0'.repeat(29)}1`
  await enter(page, fixture.admin)
  await expect(page.getByRole('button', { name: /^(刪除車輛|派工|派車)$/ })).toHaveCount(0)
  const types = await api<VehicleType[]>(page, '/fms/vehicle-types')
  expect(types.status).toBe(200)
  for (const type of Object.values(fixture.vehicleTypes)) expect(types.data.some((value) => value.id === type.id && value.label === type.label)).toBe(true)
  await search(page, prefix)
  const table = page.getByRole('table', { name: '公司車輛列表', exact: true })
  const pager = page.getByRole('navigation', { name: '車輛列表分頁', exact: true })
  await expect(table.getByRole('rowheader')).toHaveCount(0)
  await expect(page.getByText(/尚無符合條件的車輛/)).toBeVisible()

  const add = page.getByRole('button', { name: '新增車輛', exact: true })
  await add.focus(); await add.press('Enter')
  for (const type of Object.values(fixture.vehicleTypes)) await expect(page.getByLabel('車種', { exact: true }).getByRole('option', { name: new RegExp(type.label) })).toHaveCount(1)
  await page.getByLabel('車牌', { exact: true }).fill(`  ${prefix.toLowerCase()}-a-straße  `)
  await page.getByLabel('車種', { exact: true }).selectOption(fixture.vehicleTypes.a.id)
  await page.getByLabel('載重（噸）', { exact: true }).fill('1e3')
  await page.getByRole('button', { name: '建立車輛', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText(/十進位/)
  await page.getByLabel('載重（噸）', { exact: true }).fill(exactDecimal)
  await page.getByLabel('車籍編號', { exact: true }).fill('  合成車籍 e\u0301  ')
  await page.getByLabel('車籍登記日', { exact: true }).fill('1500-03-01')
  await page.getByLabel('檢驗期限', { exact: true }).fill('0001-01-01')
  let current = (await save(page, '建立車輛', 'POST')).data
  expect(current.plateNo).toBe(plate)
  expect(current.capacityTon).toBe(exactDecimal)
  expect(current.registrationNo).toBe('合成車籍 é')
  expect(current.registrationDate).toBe('1500-03-01')
  expect(current.inspectionDueDate).toBe('0001-01-01')
  expect(current.administrativeStatus).toBe('AVAILABLE')
  const id = current.id
  await expect(page.getByLabel('管理狀態', { exact: true }).getByRole('option', { name: /使用中|IN_USE/ })).toHaveCount(0)
  await backToList(page)
  await options(page, plate)
  const available = page.getByRole('region', { name: '主檔可用車輛選項', exact: true })
  await expect(available.getByRole('listitem')).toContainText([plate])

  // Partial PATCH, null versus known zero, and date endpoints are real HTTP
  // boundary checks; native date inputs cannot represent an invalid date.
  for (const body of [
    { capacityTon: '-1' }, { capacityTon: '1e3' }, { capacityTon: '9'.repeat(33) }, { capacityTon: 10 },
    { registrationDate: '0000-01-01' }, { inspectionDueDate: '10000-01-01' }, { registrationDate: '2026-02-30' },
    { administrativeStatus: 'IN_USE' }, { vehicleType: fixture.vehicleTypes.b.id, driverId: randomUUID() },
  ]) expect((await api(page, `/fms/vehicles/${id}`, 'PATCH', { ...body, version: current.version })).status).toBe(400)
  expect((await api(page, '/fms/vehicles', 'POST', { plateNo: ` ${plate.toLowerCase()} `, vehicleType: fixture.vehicleTypes.b.id })).status).toBe(409)
  expect((await api(page, '/fms/vehicle-options?administrativeStatus=INACTIVE')).status).toBe(400)
  expect((await api(page, '/fms/vehicles?sort=plateNo,desc')).status).toBe(400)
  for (const suffix of ['B', 'C', 'D', 'E', 'F']) {
    const capacityTon = suffix === 'B' ? '9'.repeat(32) : null
    const created = await api<Vehicle>(page, '/fms/vehicles', 'POST', { plateNo: `${prefix}-${suffix}`, vehicleType: fixture.vehicleTypes.b.id, capacityTon })
    expect(created.status).toBe(201)
    expect(created.data.capacityTon).toBe(capacityTon)
  }
  await search(page, prefix)
  await expect(table.getByRole('rowheader')).toHaveText(['A', 'B', 'C', 'D', 'E'].map((suffix) => new RegExp(`${prefix}-${suffix}`)))
  await expect(pager).toContainText('共 6 筆')
  await pager.getByRole('button', { name: '下一頁', exact: true }).click()
  await expect(table.getByRole('rowheader')).toHaveText([new RegExp(`${prefix}-F`)])
  await search(page, `${prefix}%`)
  await expect(table.getByRole('rowheader')).toHaveCount(0)
  await search(page, `${prefix.toLowerCase()}-a-straße`)
  await expect(table.getByRole('rowheader')).toHaveText([new RegExp(plate)])
  await search(page, '車籍 e\u0301', fixture.vehicleTypes.a.id)
  await expect(table.getByRole('rowheader')).toHaveText([new RegExp(plate)])
  await search(page, prefix, fixture.vehicleTypes.b.id)
  await expect(table.getByRole('rowheader')).toHaveCount(5)
  await search(page, prefix, fixture.vehicleTypes.a.id)
  await expect(table.getByRole('rowheader')).toHaveText([new RegExp(plate)])
  await inspect(page, plate)
  for (const status of ['MAINTENANCE', 'INACTIVE', 'AVAILABLE'] as const) {
    await page.getByLabel('管理狀態', { exact: true }).selectOption(status)
    current = (await save(page, '儲存車輛', 'PATCH', id)).data
    expect(current.id).toBe(id)
    expect(current.administrativeStatus).toBe(status)
    if (status === 'INACTIVE') expect((await api(page, '/fms/vehicles', 'POST', { plateNo: plate, vehicleType: fixture.vehicleTypes.b.id })).status).toBe(409)
    await backToList(page)
    await options(page, plate)
    await expect(available.getByRole('listitem')).toHaveCount(status === 'AVAILABLE' ? 1 : 0)
    await search(page, prefix, '', status)
    await expect(table.getByRole('rowheader')).toHaveCount(status === 'AVAILABLE' ? 5 : 1)
    await inspect(page, plate)
  }
  // A second successful writer advances the real aggregate while the editor
  // retains its older version. No mocked 409 or invented version is used.
  const remote = await api<Vehicle>(page, `/fms/vehicles/${id}`, 'PATCH', { capacityTon: '25.2500', version: current.version })
  expect(remote.status).toBe(200)
  expect(remote.data.registrationNo).toBe(current.registrationNo)
  expect(remote.data.registrationDate).toBe('1500-03-01')
  expect(remote.data.inspectionDueDate).toBe('0001-01-01')
  await page.getByLabel('車籍編號', { exact: true }).fill('合成本機不可覆寫')
  await save(page, '儲存車輛', 'PATCH', id, 409)
  await expect(page.getByRole('heading', { name: '車輛資料衝突，本機內容已保留', exact: true })).toBeVisible()
  await expect(page.getByLabel('車籍編號', { exact: true })).toHaveValue('合成本機不可覆寫')
  expect((await api<Vehicle>(page, `/fms/vehicles/${id}`)).data.version).toBe(remote.data.version)
  await page.getByRole('button', { name: '重新讀取車輛', exact: true }).click()
  await page.getByRole('button', { name: '確認載入最新車輛', exact: true }).click()
  await expect(page.getByLabel('載重（噸）', { exact: true })).toHaveValue('25.2500')
  await page.getByLabel('載重（噸）', { exact: true }).fill('0')
  await page.getByLabel('車籍登記日', { exact: true }).fill('0001-01-01')
  await page.getByLabel('檢驗期限', { exact: true }).fill('9999-12-31')
  current = (await save(page, '儲存車輛', 'PATCH', id)).data
  expect(current.version).toBe(remote.data.version + 1)
  expect(current.capacityTon).toBe('0')
  expect(current.registrationDate).toBe('0001-01-01')
  expect(current.inspectionDueDate).toBe('9999-12-31')
  await evidence(page, info, 'fms-vehicle-master.png')

  const items = await api<PageData<{ id: string; sortOrder: number; version: number }>>(page, `/adm/categories/${fixture.vehicleTypeCategoryId}/items?size=100`)
  const type = items.data.content.find((value) => value.id === fixture.vehicleTypes.a.id)
  if (!type) throw new Error('The isolated synthetic vehicle type is missing.')
  const historicalLabel = `${fixture.vehicleTypes.a.label}（更新後封存）`
  expect((await api(page, `/adm/items/${type.id}`, 'PATCH', { label: historicalLabel, enabled: false, sortOrder: type.sortOrder, version: type.version })).status).toBe(200)
  const archived = await api<Vehicle>(page, `/fms/vehicles/${id}`)
  expect(archived.data.vehicleTypeLabel).toBe(historicalLabel)
  expect(archived.data.vehicleTypeEnabled).toBe(false)
  expect((await api<VehicleType[]>(page, '/fms/vehicle-types')).data.some((value) => value.id === type.id)).toBe(false)
  expect((await api(page, '/fms/vehicles', 'POST', { plateNo: `${prefix}-DENIED`, vehicleType: type.id })).status).toBe(400)
  const historicOption = await api<PageData<Vehicle>>(page, `/fms/vehicle-options?q=${plate}`)
  expect(historicOption.data.content.some((value) => value.id === id && value.vehicleTypeEnabled === false)).toBe(true)
  await page.getByRole('button', { name: '登出', exact: true }).click()

  await enter(page, fixture.dispatcher)
  await expect(page.getByRole('button', { name: '系統管理', exact: true })).toHaveCount(0)
  expect((await api(page, '/adm/categories')).status).toBe(403)
  await search(page, plate)
  await inspect(page, plate)
  await expect(page.getByLabel('車種', { exact: true })).toHaveValue(type.id)
  await expect(page.getByLabel('車種', { exact: true })).toContainText(historicalLabel)
  await page.getByLabel('車籍編號', { exact: true }).fill('合成調度員維護封存車種')
  await page.getByLabel('載重（噸）', { exact: true }).fill('')
  current = (await save(page, '儲存車輛', 'PATCH', id)).data
  expect(current.vehicleType).toBe(type.id)
  expect(current.vehicleTypeEnabled).toBe(false)
  expect(current.capacityTon).toBeNull()
  await backToList(page)
  await options(page, plate)
  await expect(available.getByRole('listitem')).toContainText([plate])
  await expect(available).toContainText('車種已封存')
  await evidence(page, info, 'fms-archived-type-history.png')
  await page.getByRole('button', { name: '新增車輛', exact: true }).click()
  await page.getByLabel('車牌', { exact: true }).fill(`${prefix}-G`)
  await page.getByLabel('車種', { exact: true }).selectOption(fixture.vehicleTypes.c.id)
  const dispatcherCreated = (await save(page, '建立車輛', 'POST')).data
  expect(dispatcherCreated.capacityTon).toBeNull()
  expect((await api(page, `/fms/vehicles/${dispatcherCreated.id}`, 'PATCH', { vehicleType: type.id, version: dispatcherCreated.version })).status).toBe(400)
  await page.getByLabel('車牌', { exact: true }).fill(`${prefix}-H`)
  await page.getByLabel('管理狀態', { exact: true }).selectOption('MAINTENANCE')
  const dispatcherEdited = (await save(page, '儲存車輛', 'PATCH', dispatcherCreated.id)).data
  expect(dispatcherEdited.id).toBe(dispatcherCreated.id)
  expect(dispatcherEdited.plateNo).toBe(`${prefix}-H`)
  expect(dispatcherEdited.administrativeStatus).toBe('MAINTENANCE')
  expect(dispatcherEdited.version).toBe(1)
  await a11y(page)
  await page.getByRole('button', { name: '登出', exact: true }).click()

  await signIn(page, fixture.driver)
  await expect(page.getByRole('heading', { name: '司機工作台', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '車輛管理', exact: true })).toHaveCount(0)
  for (const [path, method, body] of [
    ['/fms/vehicles', 'GET', undefined], ['/fms/vehicles', 'POST', { plateNo: `${prefix}-DRIVER`, vehicleType: fixture.vehicleTypes.b.id }],
    [`/fms/vehicles/${id}`, 'GET', undefined], [`/fms/vehicles/${id}`, 'PATCH', { administrativeStatus: 'INACTIVE', version: current.version }],
    ['/fms/vehicle-options', 'GET', undefined], ['/fms/vehicle-types', 'GET', undefined],
  ] as const) {
    const response = await api(page, path, method, body)
    expect(response.status).toBe(403)
    expect(response.code).toBe('E0403')
  }
  await a11y(page)
  await page.getByRole('button', { name: '登出', exact: true }).click()
  await expect(page.getByRole('heading', { name: '登入工作空間', exact: true })).toBeVisible()
})
