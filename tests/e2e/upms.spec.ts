import { expect, test, type Page } from '@playwright/test'
import { changeInitialPassword, fillPassword, fixtureFor, signIn, syntheticPassword } from './fixtures'

// Password changes and disabling accounts consume the one-time isolated fixture.
test.describe.configure({ retries: 0 })

interface ApiResult { status: number; body: { code: string; data: unknown } }
interface Csrf { token: string; headerName: string }

async function api(page: Page, path: string, method = 'GET', token?: Csrf): Promise<ApiResult> {
  return page.evaluate(async ({ path, method, token }) => {
    const response = await fetch(`/api/v1${path}`, {
      method, credentials: 'same-origin',
      headers: token ? { [token.headerName]: token.token } : {},
    })
    return { status: response.status, body: await response.json() }
  }, { path, method, token })
}

test('three roles use protected TLS sessions, first-password changes, and administrator controls', async ({ page, browser, context }, testInfo) => {
  test.setTimeout(120_000)
  const fixtures = fixtureFor(testInfo.project.name)
  const passwords = { admin: syntheticPassword(), dispatcher: syntheticPassword(), driver: syntheticPassword() }
  await page.goto('/')
  expect(new URL(page.url()).protocol).toBe('https:')

  await signIn(page, { ...fixtures.admin, password: syntheticPassword() })
  await expect(page.getByRole('alert')).toContainText('登入失敗')
  const oldCsrf = (await api(page, '/auth/csrf')).body.data as Csrf
  const oldSession = (await context.cookies()).find((cookie) => cookie.name === 'XKSESSION')

  await signIn(page, fixtures.admin)
  await expect(page.getByRole('heading', { name: '首次登入，請先修改密碼' })).toBeVisible()
  const firstAuthenticatedSession = (await context.cookies()).find((value) => value.name === 'XKSESSION')
  expect(Boolean(oldSession && firstAuthenticatedSession && oldSession.value !== firstAuthenticatedSession.value)).toBe(true)
  expect((await api(page, '/upms/accounts')).status).toBe(403)
  await expect(page.getByRole('button', { name: '帳號管理', exact: true })).toHaveCount(0)
  await changeInitialPassword(page, fixtures.admin.password, passwords.admin)
  await signIn(page, { ...fixtures.admin, password: passwords.admin })
  await expect(page.getByRole('heading', { name: '管理員工作台', exact: true })).toBeVisible()

  const cookie = (await context.cookies()).find((value) => value.name === 'XKSESSION')
  expect(Boolean(cookie?.secure && cookie.httpOnly && cookie.sameSite === 'Lax' && cookie.path === '/')).toBe(true)
  expect(await page.evaluate(() => document.cookie.includes('XKSESSION='))).toBe(false)
  expect((await api(page, '/auth/logout', 'POST')).status).toBe(403)
  expect((await api(page, '/auth/logout', 'POST', oldCsrf)).status).toBe(403)
  expect((await api(page, '/auth/me')).status).toBe(200)

  await page.getByRole('button', { name: '帳號管理', exact: true }).click()
  await expect(page.getByRole('heading', { name: '帳號管理', exact: true })).toBeVisible()
  const createdUsername = `e2e-${testInfo.project.name}-${Date.now()}`
  await page.getByLabel('帳號', { exact: true }).fill(createdUsername)
  await page.getByLabel('顯示名稱', { exact: true }).fill('合成 E2E 新帳號')
  await page.getByLabel('角色', { exact: true }).selectOption('DRIVER')
  await fillPassword(page.getByLabel('初始密碼', { exact: true }), syntheticPassword())
  let createAttempts = 0
  await page.route('**/api/v1/upms/accounts', async (route) => {
    if (route.request().method() !== 'POST') { await route.continue(); return }
    createAttempts += 1
    const headers = { ...route.request().headers() }
    delete headers['x-csrf-token']
    await route.continue({ headers })
  })
  await page.getByRole('button', { name: '建立帳號', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('權限不足或安全驗證已失效')
  expect(createAttempts).toBe(1)
  await expect(page.getByRole('button', { name: `編輯 ${createdUsername}`, exact: true })).toHaveCount(0)
  await page.unroute('**/api/v1/upms/accounts')
  await fillPassword(page.getByLabel('初始密碼', { exact: true }), syntheticPassword())
  const createdResponse = page.waitForResponse((response) =>
    response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/v1/upms/accounts')
  await page.getByRole('button', { name: '建立帳號', exact: true }).click()
  expect((await createdResponse).status()).toBe(201)
  await expect(page.getByRole('button', { name: `編輯 ${createdUsername}`, exact: true })).toBeVisible()

  const use = testInfo.project.use
  const contextOptions = {
    baseURL: use.baseURL, ignoreHTTPSErrors: use.ignoreHTTPSErrors,
    viewport: use.viewport, screen: use.screen, deviceScaleFactor: use.deviceScaleFactor,
    isMobile: use.isMobile, hasTouch: use.hasTouch, userAgent: use.userAgent,
  }
  const dispatcherContext = await browser.newContext(contextOptions)
  const driverContext = await browser.newContext(contextOptions)
  try {
    const dispatcher = await dispatcherContext.newPage()
    const driver = await driverContext.newPage()
    for (const [role, member, heading] of [
      ['dispatcher', dispatcher, '調度員工作台'], ['driver', driver, '司機工作台'],
    ] as const) {
      await member.goto('/')
      await signIn(member, fixtures[role])
      await changeInitialPassword(member, fixtures[role].password, passwords[role])
      await signIn(member, { ...fixtures[role], password: passwords[role] })
      await expect(member.getByRole('heading', { name: heading, exact: true })).toBeVisible()
      await expect(member.getByRole('button', { name: '帳號管理', exact: true })).toHaveCount(0)
      expect((await api(member, '/upms/accounts')).status).toBe(403)
      expect((await api(member, '/upms/roles')).status).toBe(403)
      expect(await member.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    }

    const refreshedAccounts = page.waitForResponse((response) => response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/upms/accounts')
    await page.getByRole('button', { name: '重新載入帳號', exact: true }).click()
    expect((await refreshedAccounts).status()).toBe(200)
    await page.getByRole('button', { name: `編輯 ${fixtures.dispatcher.username}`, exact: true }).click()
    await page.getByLabel('啟用帳號', { exact: true }).uncheck()
    const updatedResponse = page.waitForResponse((response) => response.request().method() === 'PATCH' &&
      new URL(response.url()).pathname.startsWith('/api/v1/upms/accounts/'))
    await page.getByRole('button', { name: '儲存變更', exact: true }).click()
    expect((await updatedResponse).status()).toBe(200)
    expect((await api(dispatcher, '/auth/me')).status).toBe(401)
    await dispatcher.getByRole('button', { name: '重新確認本人狀態', exact: true }).click()
    await expect(dispatcher.getByRole('heading', { name: '登入工作空間', exact: true })).toBeVisible()
    await expect(dispatcher.getByRole('heading', { name: '調度員工作台', exact: true })).toHaveCount(0)

    await driver.getByRole('button', { name: '登出', exact: true }).click()
    await expect(driver.getByRole('heading', { name: '登入工作空間', exact: true })).toBeVisible()
    expect((await api(driver, '/auth/me')).status).toBe(401)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    await page.screenshot({ path: testInfo.outputPath('upms-accounts.png'), fullPage: true })
    await page.getByRole('button', { name: '登出', exact: true }).click()
    await expect(page.getByRole('heading', { name: '登入工作空間', exact: true })).toBeVisible()
    expect((await api(page, '/auth/me')).status).toBe(401)
  } finally {
    await dispatcherContext.close()
    await driverContext.close()
  }
})
