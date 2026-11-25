import { expect, test, type Page } from '@playwright/test'
import { admFixtureFor, signIn } from './fixtures'

test.describe.configure({ retries: 0 })

interface Category { id: string; code: string; name: string; enabled: boolean; sortOrder: number; version: number; readOnly: boolean }
interface Parameter { id: string; key: string; valueText: string; enabled: boolean; version: number }
interface PageData<T> { content: T[]; totalElements: number }

async function admRequest<T>(page: Page, path: string, method = 'GET', body?: unknown): Promise<{ status: number; data: T }> {
  return page.evaluate(async ({ path, method, body }) => {
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (method !== 'GET') {
      const csrfResponse = await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' })
      const csrf = (await csrfResponse.json()).data
      headers[csrf.headerName] = csrf.token
      headers['Content-Type'] = 'application/json'
    }
    const response = await fetch(`/api/v1/adm${path}`, {
      method, credentials: 'same-origin', headers,
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    })
    return { status: response.status, data: (await response.json()).data }
  }, { path, method, body })
}

async function save(page: Page, button: string, method: string, path: string, status = 200) {
  const response = page.waitForResponse((value) => value.request().method() === method && new URL(value.url()).pathname === `/api/v1/adm${path}`)
  await page.getByRole('button', { name: button, exact: true }).click()
  const result = await response
  expect(result.status()).toBe(status)
  return (await result.json()).data
}

test('ADM manages stable dictionaries, typed parameters, conflicts, and role denial over TLS', async ({ page }, testInfo) => {
  test.setTimeout(180_000)
  const fixture = admFixtureFor(testInfo.project.name)
  const categoryCode = `E2E_${testInfo.project.name === 'chromium' ? 'DESKTOP' : 'MOBILE'}_${Date.now()}`
  await page.goto('/')
  expect(new URL(page.url()).protocol).toBe('https:')
  await signIn(page, fixture.admin)
  await expect(page.getByRole('heading', { name: '管理員工作台', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '系統管理', exact: true }).click()
  await page.getByRole('button', { name: '字典管理', exact: true }).click()
  await expect(page.getByRole('heading', { name: '字典管理', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: /刪除/ })).toHaveCount(0)

  const categories = await admRequest<PageData<Category>>(page, '/categories?size=100')
  expect(categories.status).toBe(200)
  const fixed = categories.data.content.find((value) => value.code === 'ORDER_TYPE')!
  expect(fixed.readOnly).toBe(true)
  expect((await admRequest(page, `/categories/${fixed.id}`, 'PATCH', {
    name: '禁止更改固定枚舉', enabled: false, sortOrder: 99, version: fixed.version,
  })).status).toBe(400)
  await expect(page.getByRole('button', { name: '編輯分類 ORDER_TYPE', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '管理項目 ORDER_TYPE', exact: true }).click()
  const items = page.getByRole('region', { name: /^字典項目：/ })
  await expect(items.getByText(/固定業務枚舉，僅供唯讀對照/)).toBeVisible()
  await expect(items.getByRole('button', { name: '建立項目', exact: true })).toHaveCount(0)

  await page.getByLabel('分類代碼', { exact: true }).fill('invalid-code')
  await page.getByLabel('分類名稱', { exact: true }).fill('合成 E2E 分類')
  await page.getByLabel('分類排序', { exact: true }).fill('50')
  await page.getByRole('button', { name: '建立分類', exact: true }).click()
  await expect(page.getByRole('alert')).toBeVisible()
  await page.getByLabel('分類代碼', { exact: true }).fill(categoryCode)
  const category = await save(page, '建立分類', 'POST', '/categories', 201) as Category
  await page.getByRole('button', { name: `管理項目 ${categoryCode}`, exact: true }).click()
  await expect(items.getByText('尚無字典項目，正式值請依核准需求建立。')).toBeVisible()
  for (const [code, label, order] of [['BETA', '合成項目 B', '20'], ['ALPHA', '合成項目 A', '10']]) {
    await page.getByLabel('項目代碼', { exact: true }).fill(code)
    await page.getByLabel('項目標籤', { exact: true }).fill(label)
    await page.getByLabel('項目排序', { exact: true }).fill(order)
    await save(page, '建立項目', 'POST', `/categories/${category.id}/items`, 201)
  }
  await expect(items.getByRole('rowheader')).toHaveText([/ALPHA/, /BETA/])
  const preview = items.locator('.options-preview')
  await expect(preview.getByRole('listitem')).toHaveText([/ALPHA/, /BETA/])
  const itemList = await admRequest<PageData<{ id: string; code: string }>>(page, `/categories/${category.id}/items`)
  const beta = itemList.data.content.find((value) => value.code === 'BETA')!
  await page.getByRole('button', { name: '編輯項目 BETA', exact: true }).click()
  await expect(page.getByLabel('項目代碼', { exact: true })).toHaveJSProperty('readOnly', true)
  await page.getByLabel('項目標籤', { exact: true }).fill('合成項目 B 更新')
  await page.getByLabel('項目排序', { exact: true }).fill('5')
  await page.getByLabel('啟用項目', { exact: true }).uncheck()
  await save(page, '儲存項目', 'PATCH', `/items/${beta.id}`)
  await expect(items.getByRole('rowheader')).toHaveText([/BETA/, /ALPHA/])
  await expect(preview.getByRole('listitem')).toHaveText([/ALPHA/])

  await page.getByRole('button', { name: `編輯分類 ${categoryCode}`, exact: true }).click()
  const newer = await admRequest<Category>(page, `/categories/${category.id}`, 'PATCH', {
    name: '合成遠端更新', enabled: true, sortOrder: 9, version: category.version,
  })
  expect(newer.status).toBe(200)
  await page.getByLabel('分類名稱', { exact: true }).fill('不可覆寫遠端資料')
  await save(page, '儲存分類', 'PATCH', `/categories/${category.id}`, 409)
  await expect(page.getByText(/資料版本或代碼衝突。請重新載入最新資料/)).toBeVisible()
  await page.getByRole('button', { name: '重新載入分類', exact: true }).click()
  await page.getByRole('button', { name: `編輯分類 ${categoryCode}`, exact: true }).click()
  await expect(page.getByLabel('分類名稱', { exact: true })).toHaveValue('合成遠端更新')
  await expect(page.getByLabel('分類代碼', { exact: true })).toHaveJSProperty('readOnly', true)
  await page.getByLabel('分類名稱', { exact: true }).fill('合成已重新載入分類')
  await save(page, '儲存分類', 'PATCH', `/categories/${category.id}`)

  await page.getByRole('button', { name: `編輯分類 ${categoryCode}`, exact: true }).click()
  await page.getByLabel('啟用分類', { exact: true }).uncheck()
  await save(page, '儲存分類', 'PATCH', `/categories/${category.id}`)
  await page.getByRole('button', { name: `管理項目 ${categoryCode}`, exact: true }).click()
  await expect(items.getByText('分類已停用，可用選項為空。')).toBeVisible()
  await expect(items.getByRole('rowheader')).toHaveCount(2)
  expect((await admRequest<unknown[]>(page, `/options/${categoryCode}`)).data).toEqual([])
  await page.getByRole('button', { name: `編輯分類 ${categoryCode}`, exact: true }).click()
  await page.getByLabel('啟用分類', { exact: true }).check()
  await save(page, '儲存分類', 'PATCH', `/categories/${category.id}`)
  await expect(preview.getByRole('listitem')).toHaveText([/ALPHA/])
  await page.getByRole('button', { name: '編輯項目 BETA', exact: true }).click()
  await page.getByLabel('啟用項目', { exact: true }).check()
  await save(page, '儲存項目', 'PATCH', `/items/${beta.id}`)
  await expect(preview.getByRole('listitem')).toHaveText([/BETA/, /ALPHA/])
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.evaluate(() => window.scrollTo(0, 0))
  await page.screenshot({ path: testInfo.outputPath('adm-dictionaries.png'), fullPage: true })

  await page.getByRole('button', { name: '系統參數', exact: true }).click()
  await expect(page.getByRole('heading', { name: '系統參數', exact: true })).toBeVisible()
  expect((await admRequest(page, '/parameters', 'POST', {
    key: 'UNAPPROVED_E2E', name: '未核准合成參數', valueType: 'TEXT', valueText: '合成測試', description: '', enabled: true,
  })).status).toBe(400)
  const parameters: Partial<Record<'text' | 'number' | 'boolean', Parameter>> = {}
  for (const kind of ['text', 'number', 'boolean'] as const) {
    const key = fixture.parameterKeys[kind]
    await page.getByLabel('核准參數鍵', { exact: true }).selectOption(key)
    await page.getByLabel('參數名稱', { exact: true }).fill(`合成 ${kind} 參數`)
    await page.getByLabel('參數說明', { exact: true }).fill('隔離 E2E 合成資料，非正式核准參數')
    const value = page.getByLabel('參數值', { exact: true })
    if (kind === 'number') {
      await value.fill('1e3')
      await page.getByRole('button', { name: '建立參數', exact: true }).click()
      await expect(page.getByRole('alert')).toContainText('十進位')
      const unchanged = await admRequest<PageData<Parameter>>(page, '/parameters?size=100')
      expect(unchanged.data.content.some((item) => item.key === key)).toBe(false)
      await value.fill('12.50')
    } else if (kind === 'text') await value.fill('合成測試值')
    else await value.selectOption('true')
    parameters[kind] = await save(page, '建立參數', 'POST', '/parameters', 201) as Parameter
    await expect(page.getByRole('button', { name: `編輯參數 ${key}`, exact: true })).toBeVisible()
  }
  await page.getByRole('button', { name: `編輯參數 ${fixture.parameterKeys.boolean}`, exact: true }).click()
  await expect(page.getByLabel('核准參數鍵', { exact: true })).toBeDisabled()
  await page.getByLabel('參數值', { exact: true }).selectOption('false')
  await page.getByLabel('啟用參數', { exact: true }).uncheck()
  const updatedBoolean = await save(page, '儲存參數', 'PATCH', `/parameters/${parameters.boolean!.id}`) as Parameter
  expect(updatedBoolean.enabled).toBe(false)
  expect(updatedBoolean.valueText).toBe('false')
  await page.getByRole('button', { name: `編輯參數 ${fixture.parameterKeys.number}`, exact: true }).click()
  await page.getByLabel('參數值', { exact: true }).fill('101')
  await page.getByRole('button', { name: '儲存參數', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('不得大於 100')
  await page.getByLabel('參數值', { exact: true }).fill('75.5')
  const updatedNumber = await save(page, '儲存參數', 'PATCH', `/parameters/${parameters.number!.id}`) as Parameter
  expect(Number(updatedNumber.valueText)).toBe(75.5)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.evaluate(() => window.scrollTo(0, 0))
  await page.screenshot({ path: testInfo.outputPath('adm-parameters.png'), fullPage: true })

  await page.getByRole('button', { name: '異動紀錄', exact: true }).click()
  await expect(page.getByRole('heading', { name: '異動紀錄', exact: true })).toBeVisible()
  await expect(page.getByRole('table', { name: 'ADM 異動紀錄列表', exact: true })).toBeVisible()
  await page.getByText('檢視前後值', { exact: true }).first().click()
  await expect(page.getByRole('heading', { name: '變更後', exact: true }).first()).toBeVisible()
  const logs = await admRequest<PageData<{ targetId: string }>>(page, '/change-logs?size=100')
  expect(logs.status).toBe(200)
  expect(logs.data.content.some((entry) => entry.targetId === category.id)).toBe(true)
  await page.getByRole('button', { name: '登出', exact: true }).click()
  for (const [role, heading] of [['dispatcher', '調度員工作台'], ['driver', '司機工作台']] as const) {
    await signIn(page, fixture[role])
    await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible()
    for (const name of ['系統管理', '字典管理', '系統參數', '異動紀錄']) {
      await expect(page.getByRole('button', { name, exact: true })).toHaveCount(0)
    }
    for (const path of ['/categories', `/categories/${category.id}/items`, '/parameters', '/parameter-definitions', '/change-logs', '/options/ORDER_TYPE']) {
      expect((await admRequest(page, path)).status).toBe(403)
    }
    await page.getByRole('button', { name: '登出', exact: true }).click()
    await expect(page.getByRole('heading', { name: '登入工作空間', exact: true })).toBeVisible()
  }
})
