import { expect, test, type Page } from '@playwright/test'
import { crmFixtureFor, signIn } from './fixtures'

test.describe.configure({ retries: 0 })

interface Customer { id: string; customerCode: string; name: string; enabled: boolean; version: number }
interface Child { id: string; customerId: string; version: number }
interface PageData<T> { content: T[]; totalElements: number }

async function crmRequest<T>(page: Page, path: string, method = 'GET', body?: unknown): Promise<{ status: number; data: T }> {
  return page.evaluate(async ({ path, method, body }) => {
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (method !== 'GET') {
      const csrfResponse = await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' })
      const csrf = (await csrfResponse.json()).data
      headers[csrf.headerName] = csrf.token
      headers['Content-Type'] = 'application/json'
    }
    const response = await fetch(`/api/v1/crm${path}`, {
      method, credentials: 'same-origin', headers,
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    })
    return { status: response.status, data: (await response.json()).data }
  }, { path, method, body })
}

async function save<T>(page: Page, button: string, method: string, path: string, status = 200): Promise<T> {
  const pending = page.waitForResponse((response) => response.request().method() === method && new URL(response.url()).pathname === `/api/v1/crm${path}`)
  await page.getByRole('button', { name: button, exact: true }).click()
  const response = await pending
  expect(response.status()).toBe(status)
  return (await response.json()).data as T
}

async function search(page: Page, q: string, enabled = 'all', size = '5'): Promise<void> {
  await page.getByLabel('客戶搜尋', { exact: true }).fill(q)
  await page.getByLabel('客戶狀態', { exact: true }).selectOption(enabled)
  await page.getByLabel('每頁筆數', { exact: true }).selectOption(size)
  const pending = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return response.request().method() === 'GET' && url.pathname === '/api/v1/crm/customers' && url.searchParams.get('q') === q
  })
  await page.getByRole('button', { name: '搜尋客戶', exact: true }).click()
  expect((await pending).status()).toBe(200)
}

async function createCustomer(page: Page, code: string, name: string): Promise<Customer> {
  await page.getByRole('button', { name: '新增客戶', exact: true }).click()
  await page.getByLabel('客戶代碼', { exact: true }).fill(code)
  await page.getByLabel('客戶名稱', { exact: true }).fill(name)
  const created = await save<Customer>(page, '建立客戶', 'POST', '/customers', 201)
  await expect(page.getByLabel('客戶代碼', { exact: true })).toHaveValue(code)
  await expect(page.getByLabel('客戶代碼', { exact: true })).toHaveJSProperty('readOnly', true)
  return created
}

test('CRM supports two management roles, customer history, children, search, pages, and conflicts', async ({ page }, testInfo) => {
  test.setTimeout(180_000)
  const fixture = crmFixtureFor(testInfo.project.name)
  const prefix = `E2E_CRM_${testInfo.project.name === 'chromium' ? 'D' : 'M'}_${Date.now()}`
  await page.goto('/')
  expect(new URL(page.url()).protocol).toBe('https:')
  await signIn(page, fixture.admin)
  await page.getByRole('button', { name: '客戶管理', exact: true }).click()
  await expect(page.getByRole('heading', { name: '客戶管理', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: /刪除/ })).toHaveCount(0)
  await search(page, prefix)
  await expect(page.getByText(/^目前沒有符合條件的客戶。/)).toBeVisible()

  await page.getByRole('button', { name: '新增客戶', exact: true }).click()
  await page.getByLabel('客戶代碼', { exact: true }).fill('invalid-code')
  await page.getByLabel('客戶名稱', { exact: true }).fill('合成客戶甲')
  await page.getByRole('button', { name: '建立客戶', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('客戶代碼')
  await page.getByLabel('客戶代碼', { exact: true }).fill(`${prefix}_A`)
  await page.getByLabel('客戶電話', { exact: true }).fill('0000000000')
  await page.getByLabel('客戶電子郵件', { exact: true }).fill('customer@example.invalid')
  const customer = await save<Customer>(page, '建立客戶', 'POST', '/customers', 201)
  await expect(page.getByLabel('客戶代碼', { exact: true })).toHaveJSProperty('readOnly', true)
  const contacts = page.getByRole('region', { name: '聯絡人', exact: true })
  const addresses = page.getByRole('region', { name: '常用地址', exact: true })
  await expect(contacts.getByText('尚無聯絡人資料。')).toBeVisible()
  await expect(addresses.getByText('尚無常用地址資料。')).toBeVisible()
  const createdContacts: Child[] = []
  const createdAddresses: Child[] = []
  for (const suffix of ['甲', '乙']) {
    await page.getByLabel('聯絡人姓名', { exact: true }).fill(`合成聯絡${suffix}`)
    await page.getByLabel(/^聯絡人職稱/).fill('合成窗口')
    await page.getByLabel(/^聯絡人電話/).fill('0000000000')
    await page.getByLabel(/^聯絡人電子郵件/).fill('contact@example.invalid')
    const contact = await save<Child>(page, '建立聯絡人', 'POST', `/customers/${customer.id}/contacts`, 201)
    expect(contact.customerId).toBe(customer.id)
    createdContacts.push(contact)
    await page.getByLabel(/^地址標籤/).fill(`合成地址${suffix}`)
    await page.getByLabel('地址內容', { exact: true }).fill(`合成測試地址${suffix}（非真實地點）`)
    await page.getByLabel(/^地址類型說明/).fill('合成用途')
    const address = await save<Child>(page, '建立地址', 'POST', `/customers/${customer.id}/addresses`, 201)
    expect(address.customerId).toBe(customer.id)
    createdAddresses.push(address)
  }
  await expect(contacts.getByRole('rowheader')).toHaveCount(2)
  await expect(addresses.getByRole('rowheader')).toHaveCount(2)
  await page.getByRole('button', { name: '編輯聯絡人 合成聯絡甲', exact: true }).click()
  await page.getByLabel('聯絡人姓名', { exact: true }).fill('合成聯絡甲更新')
  await page.getByLabel('啟用聯絡人', { exact: true }).uncheck()
  await save<Child>(page, '儲存聯絡人', 'PATCH', `/customers/${customer.id}/contacts/${createdContacts[0].id}`)
  await page.getByRole('button', { name: '編輯地址 合成地址甲', exact: true }).click()
  await page.getByLabel('地址內容', { exact: true }).fill('合成更新地址（非真實地點）')
  await page.getByLabel('啟用地址', { exact: true }).uncheck()
  await save<Child>(page, '儲存地址', 'PATCH', `/customers/${customer.id}/addresses/${createdAddresses[0].id}`)
  await expect(contacts.getByRole('rowheader')).toHaveCount(2)
  await expect(addresses.getByRole('rowheader')).toHaveCount(2)
  await expect(contacts.getByRole('row').filter({ has: page.getByRole('button', { name: '編輯聯絡人 合成聯絡甲更新', exact: true }) })).toContainText('停用')
  await expect(addresses.getByRole('row').filter({ has: page.getByRole('button', { name: '編輯地址 合成地址甲', exact: true }) })).toContainText('停用')

  const otherCustomers: Customer[] = []
  for (const suffix of ['B', 'C', 'D', 'E', 'F']) {
    otherCustomers.push(await createCustomer(page, `${prefix}_${suffix}`, `合成客戶${suffix}`))
  }
  expect((await crmRequest(page, `/customers/${otherCustomers[0].id}/contacts/${createdContacts[0].id}`, 'PATCH', {
    name: '拒絕跨客戶聯絡人操作', version: 0,
  })).status).toBe(404)
  expect((await crmRequest(page, `/customers/${otherCustomers[0].id}/addresses/${createdAddresses[0].id}`, 'PATCH', {
    address: '拒絕跨客戶地址操作', version: 0,
  })).status).toBe(404)
  expect((await crmRequest(page, `/customers/${customer.id}`, 'PATCH', {
    customerCode: `${prefix}_MOVED`, version: customer.version,
  })).status).toBe(400)

  await search(page, prefix)
  const table = page.getByRole('table', { name: '公司客戶列表', exact: true })
  const pager = page.getByRole('navigation', { name: '客戶列表分頁', exact: true })
  await expect(table.getByRole('rowheader')).toHaveCount(5)
  await expect(pager).toContainText('共 6 筆')
  await pager.getByRole('button', { name: '下一頁', exact: true }).click()
  await expect(table.getByRole('rowheader')).toHaveText([new RegExp(`${prefix}_F`)])
  await pager.getByRole('button', { name: '上一頁', exact: true }).click()
  await expect(table.getByRole('rowheader')).toHaveCount(5)
  await search(page, `${prefix}_B`)
  await expect(table.getByRole('rowheader')).toHaveText([new RegExp(`${prefix}_B`)])
  await expect(pager.getByRole('button', { name: '下一頁', exact: true })).toBeDisabled()
  await search(page, `${prefix}%`)
  await expect(table.getByRole('rowheader')).toHaveCount(0)
  await search(page, `${prefix}_A`)
  await page.getByRole('button', { name: `檢視客戶 ${customer.customerCode}`, exact: true }).click()
  await expect(page.getByLabel('客戶名稱', { exact: true })).toHaveValue('合成客戶甲')

  const current = await crmRequest<Customer>(page, `/customers/${customer.id}`)
  expect((await crmRequest(page, `/customers/${customer.id}`, 'PATCH', { name: '合成遠端客戶更新', version: current.data.version })).status).toBe(200)
  await page.getByLabel('客戶名稱', { exact: true }).fill('不得覆寫較新客戶資料')
  await save(page, '儲存客戶', 'PATCH', `/customers/${customer.id}`, 409)
  await expect(page.getByText(/資料版本或代碼衝突。請重新載入最新資料/)).toBeVisible()
  await page.getByRole('button', { name: '重新載入客戶', exact: true }).click()
  await expect(page.getByLabel('客戶名稱', { exact: true })).toHaveValue('合成遠端客戶更新')
  await page.getByLabel('客戶名稱', { exact: true }).fill('合成已重讀客戶')
  const refreshed = await save<Customer>(page, '儲存客戶', 'PATCH', `/customers/${customer.id}`)
  expect(refreshed.customerCode).toBe(customer.customerCode)

  await page.getByRole('button', { name: '查看有效客戶選項', exact: true }).click()
  const options = page.getByRole('region', { name: '有效客戶選項', exact: true })
  await page.getByLabel('選項搜尋', { exact: true }).fill(prefix)
  await page.getByRole('button', { name: '查詢有效客戶', exact: true }).click()
  await expect(options.getByRole('listitem')).toHaveCount(6)
  await page.getByLabel('啟用客戶', { exact: true }).uncheck()
  await save(page, '儲存客戶', 'PATCH', `/customers/${customer.id}`)
  await expect(options.getByRole('listitem')).toHaveCount(5)
  await expect(contacts.getByRole('rowheader')).toHaveCount(2)
  await expect(addresses.getByRole('rowheader')).toHaveCount(2)
  const active = await crmRequest<PageData<Customer>>(page, `/customer-options?q=${encodeURIComponent(prefix)}`)
  expect(active.status).toBe(200)
  expect(active.data.content.some((item) => item.id === customer.id)).toBe(false)
  expect((await crmRequest(page, '/customer-options?enabled=false')).status).toBe(400)
  await search(page, prefix, 'false')
  await expect(table.getByRole('rowheader')).toHaveText([new RegExp(customer.customerCode)])
  await page.getByRole('button', { name: `檢視客戶 ${customer.customerCode}`, exact: true }).click()
  await page.getByLabel('啟用客戶', { exact: true }).check()
  await save(page, '儲存客戶', 'PATCH', `/customers/${customer.id}`)
  await expect(options.getByRole('listitem')).toHaveCount(6)
  await expect(contacts.getByRole('rowheader')).toHaveCount(2)
  await expect(addresses.getByRole('rowheader')).toHaveCount(2)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.evaluate(() => window.scrollTo(0, 0))
  await page.screenshot({ path: testInfo.outputPath('crm-customer-details.png'), fullPage: true })
  await page.getByRole('button', { name: '登出', exact: true }).click()

  await signIn(page, fixture.dispatcher)
  await expect(page.getByRole('heading', { name: '調度員工作台', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '系統管理', exact: true })).toHaveCount(0)
  expect((await page.evaluate(async () => (await fetch('/api/v1/adm/categories', { credentials: 'same-origin' })).status))).toBe(403)
  await page.getByRole('button', { name: '客戶管理', exact: true }).click()
  await search(page, customer.customerCode)
  await page.getByRole('button', { name: `檢視客戶 ${customer.customerCode}`, exact: true }).click()
  await page.getByLabel('客戶名稱', { exact: true }).fill('合成調度員客戶更新')
  await save(page, '儲存客戶', 'PATCH', `/customers/${customer.id}`)
  await page.getByRole('button', { name: '編輯聯絡人 合成聯絡乙', exact: true }).click()
  await page.getByLabel('聯絡人姓名', { exact: true }).fill('合成調度聯絡乙')
  const dispatcherContact = await save<Child>(page, '儲存聯絡人', 'PATCH', `/customers/${customer.id}/contacts/${createdContacts[1].id}`)
  expect(dispatcherContact.customerId).toBe(customer.id)
  await page.getByLabel(/^地址標籤/).fill('合成調度新增地址')
  await page.getByLabel('地址內容', { exact: true }).fill('合成調度地址（非真實地點）')
  await save(page, '建立地址', 'POST', `/customers/${customer.id}/addresses`, 201)
  await expect(addresses.getByRole('rowheader')).toHaveCount(3)
  await createCustomer(page, `${prefix}_G`, '合成調度員新增客戶')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.getByRole('button', { name: '登出', exact: true }).click()

  await signIn(page, fixture.driver)
  await expect(page.getByRole('heading', { name: '司機工作台', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '客戶管理', exact: true })).toHaveCount(0)
  for (const path of ['/customers', `/customers/${customer.id}`, `/customers/${customer.id}/contacts`, `/customers/${customer.id}/addresses`, '/customer-options']) {
    expect((await crmRequest(page, path)).status).toBe(403)
  }
  expect((await crmRequest(page, '/customers', 'POST', { customerCode: `${prefix}_DENIED`, name: '禁止司機新增客戶' })).status).toBe(403)
  await page.getByRole('button', { name: '登出', exact: true }).click()
  await expect(page.getByRole('heading', { name: '登入工作空間', exact: true })).toBeVisible()
})
