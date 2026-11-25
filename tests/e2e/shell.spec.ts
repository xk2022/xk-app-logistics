import { expect, test } from '@playwright/test'

test('shell reads real backend status through the same-origin proxy', async ({ page }, testInfo) => {
  await page.goto('/')
  await page.getByRole('button', { name: '系統狀態', exact: true }).click()
  await expect(page.getByRole('heading', { name: '系統總覽', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: '系統服務已就緒' })).toBeVisible()
  // The login page also checks status. Bind this assertion to a new shell
  // refresh request so an aborted pre-navigation response cannot be selected.
  const requestPromise = page.waitForRequest((request) =>
    new URL(request.url()).pathname === '/api/v1/status')
  await page.getByRole('button', { name: '重新檢查' }).click()
  const response = await (await requestPromise).response()
  expect(response).not.toBeNull()
  expect(response!.ok()).toBeTruthy()
  expect(await response!.json()).toMatchObject({
    code: '0000',
    data: { service: 'xk-logistics', status: 'UP' },
    timestamp: expect.stringMatching(/\+08:00$/),
  })
  await expect(page.getByRole('heading', { name: '系統總覽', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: '系統服務已就緒' })).toBeVisible()
  await expect(page.getByRole('navigation', { name: '主要導覽' })).toBeVisible()
  await expect(page.getByRole('button', { name: '重新檢查' })).toBeEnabled()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('shell.png'), fullPage: true })
})

test('a failed status request is visible and can be retried', async ({ page }) => {
  await page.route('**/api/v1/status', (route) => route.abort('failed'))
  await page.goto('/')
  await page.getByRole('button', { name: '系統狀態', exact: true }).click()
  await expect(page.getByRole('heading', { name: '暫時無法取得服務狀態' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '系統服務已就緒' })).toHaveCount(0)
  await page.unroute('**/api/v1/status')
  await page.getByRole('button', { name: '重新連線' }).click()
  await expect(page.getByRole('heading', { name: '系統服務已就緒' })).toBeVisible()
})
