import { randomUUID } from 'node:crypto'
import { writeFileSync } from 'node:fs'
import { expect, test, type Browser, type BrowserContext, type Page, type Response, type TestInfo } from '@playwright/test'
import { driverFixtureFor, signIn, type Credential } from './fixtures'

// These accounts and license items belong only to this viewport. Runtime,
// database preparation, and private fixture generation stay outside the suite.
test.describe.configure({ retries: 0 })

type Employment = 'ACTIVE' | 'INACTIVE' | 'LEFT'
type Duty = 'ON_DUTY' | 'OFF_DUTY'
interface Driver {
  id: string; driverCode: string; name: string; phone: string | null; email: string | null
  accountId: string | null; accountUsername: string | null; accountDisplayName: string | null; accountBindingValid: boolean
  employmentStatus: Employment; dutyStatus: Duty; licenseType: string | null; licenseTypeCode: string | null
  licenseTypeLabel: string | null; licenseTypeEnabled: boolean; licenseReference: string | null
  licenseDueDate: string | null; version: number; createdAt: string; createdBy: string; updatedAt: string; updatedBy: string
}
interface PageData<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }
interface AccountOption { id: string; username: string; displayName: string; bound: boolean }
interface LicenseType { id: string; code: string; label: string }
interface Reply<T> { status: number; code: string; data: T }
type Schema = 'DriverEnvelope' | 'PageDriverSummaryEnvelope' | 'DriverSelfEnvelope' | 'PageDriverOptionEnvelope' | 'DriverLicenseTypesEnvelope' | 'PageDriverAccountOptionEnvelope' | 'ErrorEnvelope'
interface Captured { schema: Schema; value: unknown }
const selfKeys = ['id', 'driverCode', 'name', 'phone', 'email', 'employmentStatus', 'dutyStatus', 'licenseType', 'licenseTypeCode', 'licenseTypeLabel', 'licenseTypeEnabled', 'licenseDueDate']
const summaryKeys = [...selfKeys, 'accountId', 'accountUsername', 'accountDisplayName', 'accountBindingValid', 'version']
const optionKeys = ['id', 'driverCode', 'name', 'licenseType', 'licenseTypeCode', 'licenseTypeLabel', 'licenseTypeEnabled', 'licenseDueDate', 'version']

class ResponseCollector {
  readonly values: Captured[] = []
  private readonly pages = new Set<Page>()
  private readonly pending = new Set<Promise<void>>()
  private readonly capture = (response: Response) => {
    const path = new URL(response.url()).pathname
    const method = response.request().method()
    let schema: Schema | undefined
    if (path === '/api/v1/fms/drivers' && method === 'GET') schema = 'PageDriverSummaryEnvelope'
    else if (path === '/api/v1/fms/drivers' && method === 'POST') schema = 'DriverEnvelope'
    else if (path === '/api/v1/fms/drivers/me' && method === 'GET') schema = 'DriverSelfEnvelope'
    else if (/^\/api\/v1\/fms\/drivers\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(path) && ['GET', 'PATCH'].includes(method)) schema = 'DriverEnvelope'
    else if (path === '/api/v1/fms/driver-options' && method === 'GET') schema = 'PageDriverOptionEnvelope'
    else if (path === '/api/v1/fms/driver-license-types' && method === 'GET') schema = 'DriverLicenseTypesEnvelope'
    else if (path === '/api/v1/fms/driver-account-options' && method === 'GET') schema = 'PageDriverAccountOptionEnvelope'
    if (!schema) return
    const responseSchema = response.status() >= 400 ? 'ErrorEnvelope' : schema
    const pending = (async () => {
      try {
        const value: unknown = await response.json()
        // Public synthetic response bodies only. Requests, credentials, cookie
        // values, authentication headers and CSRF never become attachments.
        this.values.push({ schema: responseSchema, value })
      } catch { /* Aborted or unreadable bodies are not claimed as validated. */ }
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
test.beforeEach(async ({ page }, info) => { const collector = new ResponseCollector(); collectors.set(info.testId, collector); collector.watch(page) })
test.afterEach(async ({}, info) => {
  const collector = collectors.get(info.testId)
  if (!collector) return
  await collector.finish()
  writeFileSync(info.outputPath('driver-contract-responses.json'), JSON.stringify(collector.values))
  collectors.delete(info.testId)
})

function exactKeys(value: object, expected: string[]): void { expect(Object.keys(value).sort()).toEqual([...expected].sort()) }

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

async function contextFor(browser: Browser, info: TestInfo): Promise<BrowserContext> {
  const use = info.project.use
  const context = await browser.newContext({ baseURL: use.baseURL, ignoreHTTPSErrors: use.ignoreHTTPSErrors, viewport: use.viewport,
    screen: use.screen, deviceScaleFactor: use.deviceScaleFactor, isMobile: use.isMobile, hasTouch: use.hasTouch, userAgent: use.userAgent })
  const collector = collectors.get(info.testId)
  if (collector) context.on('page', (page) => collector.watch(page))
  return context
}

async function enter(page: Page, credential: Credential, self = false): Promise<void> {
  await page.goto('/')
  expect(new URL(page.url()).protocol).toBe('https:')
  await signIn(page, credential)
  const title = self ? '本人司機資料' : '司機管理'
  await page.getByRole('navigation', { name: '主要導覽', exact: true }).getByRole('button', { name: title, exact: true }).click()
  await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
  if (self) await expect(page.getByRole('button', { name: '重新讀取本人資料', exact: true })).toBeEnabled()
}

async function save(page: Page, method: 'POST' | 'PATCH', id?: string, status = method === 'POST' ? 201 : 200): Promise<Driver> {
  const path = `/api/v1/fms/drivers${id ? `/${id}` : ''}`
  const pending = page.waitForResponse((response) => response.request().method() === method && new URL(response.url()).pathname === path)
  await page.getByRole('button', { name: method === 'POST' ? '建立司機' : '儲存司機', exact: true }).click()
  const response = await pending
  expect(response.status()).toBe(status)
  const payload = await response.json()
  expect(payload.code).toBe(status < 400 ? '0000' : 'E0409')
  return payload.data as Driver
}

async function search(page: Page, q: string, employment = 'all', duty = 'all', licenseType = '', size = '5'): Promise<void> {
  const form = page.getByRole('form', { name: '司機查詢', exact: true })
  await form.getByLabel('司機搜尋', { exact: true }).fill(q)
  await form.getByLabel('在職狀態篩選', { exact: true }).selectOption(employment)
  await form.getByLabel('出勤狀態篩選', { exact: true }).selectOption(duty)
  await form.getByLabel('駕照類別篩選', { exact: true }).selectOption(licenseType)
  await form.getByLabel('每頁筆數', { exact: true }).selectOption(size)
  const pending = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return response.request().method() === 'GET' && url.pathname === '/api/v1/fms/drivers' && (url.searchParams.get('q') ?? '') === q.normalize('NFC').trim()
  })
  await form.getByRole('button', { name: '搜尋司機', exact: true }).click()
  expect((await pending).status()).toBe(200)
}

async function inspect(page: Page, code: string): Promise<void> {
  await page.getByRole('button', { name: `檢視司機 ${code}`, exact: true }).click()
  await expect(page.getByLabel('司機編號', { exact: true })).toHaveValue(code)
}

async function back(page: Page): Promise<void> {
  await expect(page.getByText('目前內容與已保存資料一致。', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '返回司機列表', exact: true }).click()
  await expect(page.getByRole('form', { name: '司機查詢', exact: true })).toBeVisible()
}

async function visibleOptions(page: Page, q: string, count: number): Promise<void> {
  const region = page.getByRole('region', { name: '行政可用司機選項', exact: true })
  if (!await region.isVisible()) await page.getByRole('button', { name: '查看行政可用司機選項', exact: true }).click()
  await region.getByLabel('行政可用司機搜尋', { exact: true }).fill(q)
  const pending = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return url.pathname === '/api/v1/fms/driver-options' && url.searchParams.get('q') === q
  })
  await region.getByRole('button', { name: '搜尋行政可用司機', exact: true }).click()
  expect((await pending).status()).toBe(200)
  await expect(region.getByRole('listitem')).toHaveCount(count)
  await expect(region).toContainText(/不是實際可派工/)
}

async function accountChoices(page: Page, q: string): Promise<void> {
  await page.getByLabel('綁定帳號搜尋', { exact: true }).fill(q)
  const pending = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return url.pathname === '/api/v1/fms/driver-account-options' && url.searchParams.get('q') === q
  })
  await page.getByRole('button', { name: '查詢可綁定帳號', exact: true }).click()
  expect((await pending).status()).toBe(200)
}

async function selfRead(page: Page, id: string, code: string): Promise<Driver> {
  const result = await api<Driver>(page, '/fms/drivers/me')
  expect(result.status).toBe(200)
  expect(result.code).toBe('0000')
  exactKeys(result.data, selfKeys)
  expect(result.data.id).toBe(id)
  expect(result.data.driverCode).toBe(code)
  await expect(page.getByText(`司機編號 ${code}`, { exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: result.data.name, exact: true })).toBeVisible()
  return result.data
}

async function refreshSelf(page: Page, status: number): Promise<void> {
  const pending = page.waitForResponse((response) => response.request().method() === 'GET' && new URL(response.url()).pathname === '/api/v1/fms/drivers/me')
  await page.getByRole('button', { name: '重新讀取本人資料', exact: true }).click()
  expect((await pending).status()).toBe(status)
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

test('Driver management preserves independent states, narrow bindings, minimal self data and current-actor isolation over TLS', async ({ page, browser }, info) => {
  test.setTimeout(360_000)
  const fixture = driverFixtureFor(info.project.name)
  const prefix = `XD${info.project.name === 'chromium' ? 'D' : 'M'}${randomUUID().replaceAll('-', '').slice(0, 10).toUpperCase()}`
  const code = `${prefix}-A-STRASSE`
  const reference = `SYNTHETIC-LREF-${prefix}`
  const contexts: BrowserContext[] = []
  const childPage = async () => { const context = await contextFor(browser, info); contexts.push(context); return context.newPage() }
  try {
    await enter(page, fixture.admin)
    expect((await api(page, '/fms/drivers/me')).status).toBe(403)
    await expect(page.getByRole('button', { name: /^(刪除司機|派工|派車)$/ })).toHaveCount(0)
    const types = await api<LicenseType[]>(page, '/fms/driver-license-types')
    expect(types.status).toBe(200)
    for (const type of Object.values(fixture.licenseTypes)) expect(types.data.some((value) => value.id === type.id && value.label === type.label)).toBe(true)
    await search(page, prefix)
    const table = page.getByRole('table', { name: '公司司機列表', exact: true })
    const pager = page.getByRole('navigation', { name: '司機列表分頁', exact: true })
    await expect(table.getByRole('rowheader')).toHaveCount(0)
    const add = page.getByRole('button', { name: '新增司機', exact: true })
    await add.focus(); await add.press('Enter')
    await page.getByLabel('司機編號', { exact: true }).fill(`  ${prefix.toLowerCase()}-a-straße  `)
    await page.getByLabel('姓名', { exact: true }).fill(' 合成司機 e\u0301 ')
    await page.getByLabel('電話', { exact: true }).fill(' 合成聯絡方式（非真實號碼） ')
    await page.getByLabel('電子郵件', { exact: true }).fill('合成@example.invalid')
    await page.getByLabel('駕照類別', { exact: true }).selectOption(fixture.licenseTypes.a.id)
    await page.getByLabel('證照參考說明', { exact: true }).fill(reference)
    await page.getByLabel('證照期限', { exact: true }).fill('2026-02-30')
    await page.getByRole('button', { name: '建立司機', exact: true }).click()
    await expect(page.getByRole('alert')).toContainText(/自然日|YYYY-MM-DD/)
    await page.getByLabel('證照期限', { exact: true }).fill('1500-03-01')
    await page.getByLabel('出勤狀態', { exact: true }).selectOption('ON_DUTY')
    await accountChoices(page, fixture.accountSearchPrefix)
    await page.getByLabel('綁定登入帳號', { exact: true }).selectOption(fixture.driverA.id)
    let current = await save(page, 'POST')
    const id = current.id
    expect(current.driverCode).toBe(code)
    expect(current.name).toBe('合成司機 é')
    expect(current.licenseDueDate).toBe('1500-03-01')
    expect(current.accountId).toBe(fixture.driverA.id)
    expect(current.accountBindingValid).toBe(true)
    await expect(page.getByLabel('司機編號', { exact: true })).toHaveJSProperty('readOnly', true)
    await back(page)
    const other: Driver[] = []
    for (const suffix of ['B', 'C', 'D', 'E', 'F']) {
      const created = await api<Driver>(page, '/fms/drivers', 'POST', { driverCode: `${prefix}-${suffix}`, name: `合成其他司機 ${suffix}`,
        dutyStatus: 'ON_DUTY', licenseType: fixture.licenseTypes.b.id, ...(suffix === 'B' ? { accountId: fixture.driverB.id } : {}) })
      expect(created.status).toBe(201)
      other.push(created.data)
    }
    const all = await api<PageData<Driver>>(page, `/fms/drivers?q=${prefix}&size=100`)
    expect(all.data.totalElements).toBe(6)
    for (const summary of all.data.content) exactKeys(summary, summaryKeys)
    const options = await api<PageData<Driver>>(page, `/fms/driver-options?q=${prefix}&size=100`)
    expect(options.data.totalElements).toBe(6)
    for (const option of options.data.content) exactKeys(option, optionKeys)
    expect(options.data.content.some((value) => value.id === other[1].id)).toBe(true)

    const firstCandidates = await api<PageData<AccountOption>>(page, `/fms/driver-account-options?q=${fixture.accountSearchPrefix}&size=2`)
    const secondCandidates = await api<PageData<AccountOption>>(page, `/fms/driver-account-options?q=${fixture.accountSearchPrefix}&size=2&page=1`)
    expect(firstCandidates.status).toBe(200)
    expect(secondCandidates.status).toBe(200)
    for (const candidates of [firstCandidates.data, secondCandidates.data]) {
      expect(candidates.totalElements).toBe(4)
      expect(candidates.totalPages).toBe(2)
      expect(candidates.content.length).toBe(2)
      for (const candidate of candidates.content) exactKeys(candidate, ['id', 'username', 'displayName', 'bound'])
    }
    expect(firstCandidates.data.content.map((value) => value.id)).toEqual([fixture.driverA.id, fixture.driverB.id])
    expect(firstCandidates.data.content.every((value) => value.bound === true)).toBe(true)
    expect(secondCandidates.data.content.map((value) => value.id)).toEqual([fixture.driverC.id, fixture.temporary.id])
    expect(secondCandidates.data.content.every((value) => value.bound === false)).toBe(true)

    await search(page, prefix)
    await expect(table.getByRole('rowheader')).toHaveText(['A', 'B', 'C', 'D', 'E'].map((suffix) => new RegExp(`${prefix}-${suffix}`)))
    await expect(pager).toContainText('共 6 筆')
    await pager.getByRole('button', { name: '下一頁', exact: true }).click()
    await expect(table.getByRole('rowheader')).toHaveText([new RegExp(`${prefix}-F`)])
    await search(page, `${prefix}%`)
    await expect(table.getByRole('rowheader')).toHaveCount(0)
    await search(page, reference)
    await expect(table.getByRole('rowheader')).toHaveCount(0)
    await search(page, `${prefix.toLowerCase()}-a-straße`, 'all', 'all', fixture.licenseTypes.a.id)
    await expect(table.getByRole('rowheader')).toHaveText([new RegExp(code)])
    await inspect(page, code)
    await accountChoices(page, fixture.accountSearchPrefix)
    await expect(page.getByLabel('綁定登入帳號', { exact: true }).locator(`option[value="${fixture.driverA.id}"]`)).toHaveJSProperty('disabled', false)
    await expect(page.getByLabel('綁定登入帳號', { exact: true }).locator(`option[value="${fixture.driverB.id}"]`)).toHaveJSProperty('disabled', true)
    for (const body of [{ driverCode: 'FORBIDDEN_CHANGE' }, { name: null }, { licenseDueDate: '0000-01-01' }, { licenseDueDate: '10000-01-01' }, { licenseDueDate: '2026-02-30' }, { accountId: fixture.admin.id }]) {
      expect((await api(page, `/fms/drivers/${id}`, 'PATCH', { ...body, version: current.version })).status).toBe(400)
    }
    expect((await api(page, `/fms/drivers/${id}`, 'PATCH', { accountId: fixture.driverB.id, version: current.version })).status).toBe(409)
    expect((await api(page, '/fms/driver-options?employmentStatus=INACTIVE')).status).toBe(400)
    expect((await api(page, '/fms/drivers?sort=driverCode,desc')).status).toBe(400)
    for (const [employment, duty] of [['INACTIVE', 'ON_DUTY'], ['ACTIVE', 'OFF_DUTY'], ['ACTIVE', 'ON_DUTY']] as const) {
      await page.getByLabel('在職狀態', { exact: true }).selectOption(employment)
      await page.getByLabel('出勤狀態', { exact: true }).selectOption(duty)
      current = await save(page, 'PATCH', id)
      expect(current.employmentStatus).toBe(employment)
      expect(current.dutyStatus).toBe(duty)
      expect((await api<PageData<Driver>>(page, `/fms/driver-options?q=${code}`)).data.content.some((value) => value.id === id)).toBe(employment === 'ACTIVE' && duty === 'ON_DUTY')
      await back(page)
      await visibleOptions(page, code, employment === 'ACTIVE' && duty === 'ON_DUTY' ? 1 : 0)
      await search(page, prefix, employment, duty)
      await expect(table.getByRole('rowheader')).toHaveCount(employment === 'ACTIVE' && duty === 'ON_DUTY' ? 5 : 1)
      await inspect(page, code)
    }
    const remote = await api<Driver>(page, `/fms/drivers/${id}`, 'PATCH', { phone: '合成遠端聯絡更新', version: current.version })
    expect(remote.status).toBe(200)
    await page.getByLabel('姓名', { exact: true }).fill('合成本機衝突內容')
    await save(page, 'PATCH', id, 409)
    await expect(page.getByLabel('姓名', { exact: true })).toHaveValue('合成本機衝突內容')
    await page.getByRole('button', { name: '重新讀取司機', exact: true }).click()
    await page.getByRole('button', { name: '確認載入最新司機', exact: true }).click()
    await expect(page.getByLabel('電話', { exact: true })).toHaveValue('合成遠端聯絡更新')
    await page.getByLabel('電子郵件', { exact: true }).fill('合成更新@example.invalid')
    current = await save(page, 'PATCH', id)
    expect(current.version).toBe(remote.data.version + 1)

    const items = await api<PageData<{ id: string; sortOrder: number; version: number }>>(page, `/adm/categories/${fixture.licenseTypeCategoryId}/items?size=100`)
    const type = items.data.content.find((value) => value.id === fixture.licenseTypes.a.id)
    if (!type) throw new Error('The isolated synthetic license type is missing.')
    const historicalLabel = `${fixture.licenseTypes.a.label}（更新後封存）`
    expect((await api(page, `/adm/items/${type.id}`, 'PATCH', { label: historicalLabel, enabled: false, sortOrder: type.sortOrder, version: type.version })).status).toBe(200)
    expect((await api<LicenseType[]>(page, '/fms/driver-license-types')).data.some((value) => value.id === type.id)).toBe(false)
    const archived = await api<Driver>(page, `/fms/drivers/${id}`)
    expect(archived.data.licenseTypeLabel).toBe(historicalLabel)
    expect(archived.data.licenseTypeEnabled).toBe(false)
    expect((await api<PageData<Driver>>(page, `/fms/driver-options?q=${code}`)).data.content.some((value) => value.id === id && value.licenseTypeEnabled === false)).toBe(true)
    expect((await api(page, '/fms/drivers', 'POST', { driverCode: `${prefix}-DENIED`, name: '禁止新增封存類別', licenseType: type.id })).status).toBe(400)
    expect((await api(page, `/fms/drivers/${other[0].id}`, 'PATCH', { licenseType: type.id, version: other[0].version })).status).toBe(400)
    await page.getByRole('button', { name: '重新讀取司機', exact: true }).click()
    await page.getByRole('button', { name: '確認載入最新司機', exact: true }).click()
    await page.getByLabel('姓名', { exact: true }).fill('合成封存類別仍可維護')
    current = await save(page, 'PATCH', id)
    expect(current.licenseType).toBe(type.id)
    expect(current.licenseTypeEnabled).toBe(false)
    const refreshedTypes = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/v1/fms/driver-license-types')
    await page.getByRole('button', { name: '重新讀取駕照類別', exact: true }).click()
    expect((await refreshedTypes).status()).toBe(200)
    await expect(page.getByLabel('駕照類別', { exact: true })).toHaveValue(type.id)
    await expect(page.getByLabel('駕照類別', { exact: true })).toContainText(historicalLabel)
    await evidence(page, info, 'driver-management.png')
    await back(page)
    await visibleOptions(page, code, 1)
    await expect(page.getByRole('region', { name: '行政可用司機選項', exact: true })).toContainText('駕照類別已封存')
    await evidence(page, info, 'driver-options-archived.png')

    const temporary = await api<Driver>(page, '/fms/drivers', 'POST', { driverCode: `${prefix}-TEMP`, name: '合成臨時帳號綁定', accountId: fixture.temporary.id, licenseDueDate: '0001-01-01' })
    expect(temporary.status).toBe(201)
    expect(temporary.data.accountBindingValid).toBe(true)
    const a = await childPage(); const b = await childPage(); const c = await childPage()
    await enter(a, fixture.driverA, true)
    await enter(b, fixture.driverB, true)
    await enter(c, fixture.driverC, true)
    await selfRead(a, id, code)
    await selfRead(b, other[0].id, other[0].driverCode)
    expect((await api(c, '/fms/drivers/me')).status).toBe(404)
    await expect(a.getByRole('button', { name: '司機管理', exact: true })).toHaveCount(0)
    await expect(a.getByText(reference, { exact: true })).toHaveCount(0)
    for (const [path, method, body] of [
      ['/fms/drivers', 'GET', undefined], ['/fms/drivers', 'POST', { driverCode: `${prefix}-NO`, name: '禁止司機新增' }],
      [`/fms/drivers/${other[0].id}`, 'GET', undefined], [`/fms/drivers/${id}`, 'GET', undefined], [`/fms/drivers/${id}`, 'PATCH', { name: '禁止本人管理寫入', version: current.version }],
      ['/fms/driver-options', 'GET', undefined], ['/fms/driver-license-types', 'GET', undefined], ['/fms/driver-account-options', 'GET', undefined],
      ['/fms/vehicles', 'GET', undefined], ['/crm/customers', 'GET', undefined], ['/orders', 'GET', undefined], ['/upms/accounts', 'GET', undefined], ['/adm/categories', 'GET', undefined],
    ] as const) expect((await api(a, path, method, body)).status).toBe(403)
    expect((await api(a, `/fms/drivers/me?driverId=${other[0].id}`)).status).toBe(400)

    const patch = async (body: object): Promise<Driver> => {
      const response = await api<Driver>(page, `/fms/drivers/${id}`, 'PATCH', { ...body, version: current.version })
      expect(response.status).toBe(200)
      current = response.data
      return current
    }
    await patch({ accountId: null })
    await refreshSelf(a, 404)
    await expect(a.getByRole('heading', { name: '目前沒有可顯示的本人司機資料', exact: true })).toBeVisible()
    await expect(a.getByText(`司機編號 ${code}`, { exact: true })).toHaveCount(0)
    expect((await api<PageData<Driver>>(page, `/fms/driver-options?q=${code}`)).data.content.some((value) => value.id === id)).toBe(true)
    await patch({ accountId: fixture.driverC.id })
    await refreshSelf(c, 200)
    await selfRead(c, id, code)
    expect((await api(a, '/fms/drivers/me')).status).toBe(404)
    await selfRead(b, other[0].id, other[0].driverCode)
    expect((await api(page, `/fms/drivers/${id}`, 'PATCH', { accountId: fixture.driverB.id, version: current.version })).status).toBe(409)
    await selfRead(c, id, code)
    const unboundB = await api<Driver>(page, `/fms/drivers/${other[0].id}`, 'PATCH', { accountId: null, version: other[0].version })
    expect(unboundB.status).toBe(200)
    expect((await api(b, '/fms/drivers/me')).status).toBe(404)
    await patch({ accountId: fixture.driverB.id })
    await refreshSelf(b, 200)
    await selfRead(b, id, code)
    expect((await api(c, '/fms/drivers/me')).status).toBe(404)
    const accounts = await api<{ id: string; displayName: string; role: string; version: number }[]>(page, '/upms/accounts')
    const target = accounts.data.find((account) => account.id === fixture.driverB.id)
    if (!target) throw new Error('The isolated driver account is missing.')
    expect((await api(page, `/upms/accounts/${target.id}`, 'PATCH', { displayName: target.displayName, role: target.role, enabled: false, version: target.version })).status).toBe(200)
    await refreshSelf(b, 401)
    await expect(b.getByRole('heading', { name: '登入工作空間', exact: true })).toBeVisible()
    const invalidBinding = await api<Driver>(page, `/fms/drivers/${id}`)
    expect(invalidBinding.data.accountId).toBe(fixture.driverB.id)
    expect(invalidBinding.data.accountBindingValid).toBe(false)
    await search(page, code)
    await inspect(page, code)
    await expect(page.getByLabel('綁定登入帳號', { exact: true })).toHaveValue(fixture.driverB.id)
    await expect(page.getByText(/原綁定帳號目前無法讀取本人資料/)).toBeVisible()
    await page.getByLabel('電話', { exact: true }).fill('合成無效舊綁定仍可維護')
    current = await save(page, 'PATCH', id)
    expect(current.accountBindingValid).toBe(false)
    expect(current.accountId).toBe(fixture.driverB.id)
    expect((await api<PageData<Driver>>(page, `/fms/driver-options?q=${code}`)).data.content.some((value) => value.id === id)).toBe(true)
    await back(page)
    await patch({ accountId: null })
    await patch({ accountId: fixture.driverC.id, employmentStatus: 'LEFT', dutyStatus: 'ON_DUTY' })
    await refreshSelf(c, 200)
    expect((await selfRead(c, id, code)).employmentStatus).toBe('LEFT')
    expect((await api<PageData<Driver>>(page, `/fms/driver-options?q=${code}`)).data.content.some((value) => value.id === id)).toBe(false)
    expect((await api(page, `/fms/drivers/${id}`, 'PATCH', { employmentStatus: 'ACTIVE', version: current.version })).status).toBe(409)
    expect((await api(page, '/fms/drivers', 'POST', { driverCode: code.toLowerCase(), name: '禁止重用離職編號' })).status).toBe(409)
    await patch({ name: '合成離職主檔仍可維護' })
    await refreshSelf(c, 200)
    await selfRead(c, id, code)
    await evidence(c, info, 'driver-self.png')
    expect(await c.evaluate(() => localStorage.length === 0 && sessionStorage.length === 0)).toBe(true)

    const temp = await childPage()
    await temp.goto('/')
    await signIn(temp, fixture.temporary)
    await expect(temp.getByRole('heading', { name: '首次登入，請先修改密碼', exact: true })).toBeVisible()
    await expect(temp.getByRole('button', { name: '本人司機資料', exact: true })).toHaveCount(0)
    expect((await api(temp, '/fms/drivers/me')).status).toBe(403)
    await temp.getByRole('button', { name: '登出', exact: true }).click()

    const dispatcher = await childPage()
    await enter(dispatcher, fixture.dispatcher)
    await expect(dispatcher.getByRole('button', { name: '系統管理', exact: true })).toHaveCount(0)
    expect((await api(dispatcher, '/adm/categories')).status).toBe(403)
    expect((await api(dispatcher, '/upms/accounts')).status).toBe(403)
    expect((await api(dispatcher, '/fms/drivers/me')).status).toBe(403)
    const narrow = await api<PageData<AccountOption>>(dispatcher, `/fms/driver-account-options?q=${fixture.accountSearchPrefix}&size=2`)
    expect(narrow.status).toBe(200)
    for (const candidate of narrow.data.content) exactKeys(candidate, ['id', 'username', 'displayName', 'bound'])
    await dispatcher.getByRole('button', { name: '新增司機', exact: true }).click()
    await dispatcher.getByLabel('司機編號', { exact: true }).fill(`${prefix}-G`)
    await dispatcher.getByLabel('姓名', { exact: true }).fill('合成調度員建立無帳號司機')
    await dispatcher.getByLabel('證照期限', { exact: true }).fill('9999-12-31')
    let managed = await save(dispatcher, 'POST')
    expect(managed.accountId).toBeNull()
    expect(managed.licenseType).toBeNull()
    expect(managed.employmentStatus).toBe('ACTIVE')
    expect(managed.dutyStatus).toBe('OFF_DUTY')
    expect(managed.licenseDueDate).toBe('9999-12-31')
    await dispatcher.getByLabel('在職狀態', { exact: true }).selectOption('INACTIVE')
    await dispatcher.getByLabel('出勤狀態', { exact: true }).selectOption('ON_DUTY')
    managed = await save(dispatcher, 'PATCH', managed.id)
    expect(managed.employmentStatus).toBe('INACTIVE')
    expect(managed.dutyStatus).toBe('ON_DUTY')
    await dispatcher.getByLabel('在職狀態', { exact: true }).selectOption('LEFT')
    managed = await save(dispatcher, 'PATCH', managed.id)
    expect(managed.employmentStatus).toBe('LEFT')
    expect(managed.dutyStatus).toBe('ON_DUTY')
    await expect(dispatcher.getByLabel('在職狀態', { exact: true }).locator('option[value="ACTIVE"]')).toHaveJSProperty('disabled', true)
    await expect(dispatcher.getByLabel('在職狀態', { exact: true }).locator('option[value="INACTIVE"]')).toHaveJSProperty('disabled', true)
    expect((await api(dispatcher, `/fms/drivers/${managed.id}`, 'PATCH', { employmentStatus: 'ACTIVE', version: managed.version })).status).toBe(409)
    await dispatcher.getByLabel('姓名', { exact: true }).fill('合成調度員更新無帳號司機')
    managed = await save(dispatcher, 'PATCH', managed.id)
    expect(managed.version).toBe(3)
    const beforeNoOp = managed.updatedAt
    managed = await save(dispatcher, 'PATCH', managed.id)
    expect(managed.version).toBe(4)
    expect(managed.updatedAt !== beforeNoOp).toBe(true)
    await evidence(dispatcher, info, 'driver-dispatcher-management.png')
    await a11y(a)
    await a11y(c)
  } finally { await Promise.allSettled(contexts.map((context) => context.close())) }
})
