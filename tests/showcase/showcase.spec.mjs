import { test, expect } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const audits = new WeakMap()
const orderNo = 'DEMO-LOCAL-1001'
const taskNo = 'T-DEMO-1001'
const foreignKey = 'xk-unrelated-showcase-sentinel'
const foreignValue = 'preserve-this-unrelated-tab-data'

async function auditPage(page, audit) {
  audit.pages.add(page)
  await page.addInitScript(() => {
    window.__xkShowcaseViolations = []
    document.addEventListener('securitypolicyviolation', event => {
      window.__xkShowcaseViolations.push({ directive: event.violatedDirective, blocked: event.blockedURI })
    })
  })
  page.on('pageerror', error => audit.errors.push(error.message))
  page.on('console', message => { if (message.type() === 'error') audit.errors.push(message.text()) })
  page.on('requestfailed', request => audit.failed.push(new URL(request.url()).pathname))
  await page.routeWebSocket('**/*', socket => {
    audit.requests.push({ kind: 'websocket' })
    socket.close({ code: 1008, reason: 'Offline Showcase cannot connect' })
  })
  await page.route('**/*', async route => {
    const request = route.request()
    const url = new URL(request.url())
    const base = new URL(audit.baseURL)
    if (['fetch', 'xhr', 'websocket', 'eventsource', 'ping'].includes(request.resourceType())
      || !['GET', 'HEAD'].includes(request.method()) || url.origin !== base.origin || !url.pathname.startsWith('/logistics/')) {
      audit.requests.push({ kind: request.resourceType(), method: request.method(), path: url.pathname })
      await route.abort()
      return
    }
    await route.continue()
  })
}

test.beforeEach(async ({ page, context, baseURL }) => {
  const audit = { baseURL, pages: new Set(), requests: [], failed: [], errors: [], foreign: false }
  audits.set(context, audit)
  await auditPage(page, audit)
  const response = await page.goto('./', { waitUntil: 'networkidle' })
  expect(response?.status()).toBe(200)
  await expect(page.locator('.app-shell[data-showcase-demo="logistics"]')).toBeVisible()
  await expect(page.getByRole('note')).toContainText('合成互動展示，不連線後端／非正式操作')
  await expect(page.locator('input[type="password"], input[type="file"]')).toHaveCount(0)
})

test.afterEach(async ({ context }) => {
  const audit = audits.get(context)
  expect(audit.requests, 'No API, fetch, XHR, websocket, beacon or outside resource attempts').toEqual([])
  expect(audit.failed, 'Every static resource must load').toEqual([])
  expect(audit.errors, 'No runtime or CSP console errors').toEqual([])
  expect(await context.cookies(), 'No authentication or persistence cookies').toEqual([])
  for (const page of audit.pages) {
    if (page.isClosed()) continue
    const data = await page.evaluate(async () => ({
      violations: window.__xkShowcaseViolations ?? [],
      local: Object.fromEntries(Object.entries(localStorage)),
      session: Object.fromEntries(Object.entries(sessionStorage)),
      databases: typeof indexedDB.databases === 'function' ? (await indexedDB.databases()).length : 0,
      caches: (await caches.keys()).length,
      workers: 'serviceWorker' in navigator ? (await navigator.serviceWorker.getRegistrations()).length : 0,
      width: document.documentElement.scrollWidth,
      viewport: window.innerWidth,
    }))
    expect(data.violations, 'No blocked connection hidden behind CSP').toEqual([])
    expect(data.databases).toBe(0)
    expect(data.caches).toBe(0)
    expect(data.workers).toBe(0)
    expect(data.width).toBeLessThanOrEqual(data.viewport + 1)
    for (const store of [data.local, data.session]) {
      expect(Object.keys(store).filter(key => key !== foreignKey)).toEqual([])
      if (foreignKey in store) {
        expect(audit.foreign).toBe(true)
        expect(store[foreignKey]).toBe(foreignValue)
      }
    }
  }
})

const article = (page, name) => page.getByRole('article', { name, exact: true })
const order = (page, no = orderNo) => article(page, `展示訂單 ${no}`)
const dispatch = (page, no = taskNo) => article(page, `展示派工 ${no}`)
const personal = (page, no = taskNo) => article(page, `本人任務 ${no}`)
async function nav(page, name) { await page.getByRole('navigation', { name: '主要導覽', exact: true }).getByRole('button', { name, exact: true }).click() }
async function role(page, value) { await page.getByLabel('展示角色', { exact: true }).selectOption(value) }
async function confirm(page, action, reason) {
  await action.click()
  const dialog = page.getByRole('dialog', { name: '確認展示操作', exact: true })
  await expect(dialog).toBeVisible()
  if (reason !== undefined) await expect(dialog.getByLabel('本次展示原因原文', { exact: true })).toHaveJSProperty('textContent', reason)
  await dialog.getByRole('button', { name: '確認執行展示操作', exact: true }).click()
  await expect(dialog).toBeHidden()
}
async function accept(page, no = orderNo) {
  await nav(page, '訂單工作台')
  await confirm(page, order(page, no).getByRole('button', { name: '展示受理', exact: true }))
  await expect(order(page, no)).toContainText('已受理')
}
async function assign(page, no = taskNo) {
  await nav(page, '調度工作台')
  const card = dispatch(page, no)
  await card.getByLabel('展示司機', { exact: true }).selectOption('driver-1')
  await card.getByLabel('展示主車', { exact: true }).selectOption('main-1')
  await confirm(page, card.getByRole('button', { name: '建立展示派工', exact: true }))
  await expect(card).toContainText('待出發')
}
async function driver(page) { await role(page, 'DRIVER'); await nav(page, '本人運輸任務') }
async function reachAwaiting(page) {
  await accept(page)
  await assign(page)
  await driver(page)
  const card = personal(page)
  await confirm(page, card.getByRole('button', { name: '展示出發', exact: true }))
  await expect(card).toContainText('已出發')
  for (const target of ['執行中', '待完成確認']) {
    await expect(card.getByRole('button', { name: '提交展示進度', exact: true })).toBeDisabled()
    await card.getByRole('button', { name: '加入合成任務照片', exact: true }).click()
    await card.getByRole('checkbox', { name: '選取進度照片 1', exact: true }).check()
    await confirm(page, card.getByRole('button', { name: '提交展示進度', exact: true }))
    await expect(card).toContainText(target)
  }
}
async function complete(page) {
  const card = personal(page)
  await expect(card.getByRole('checkbox', { name: '我確認展示任務完成並解除合成資源占用', exact: true })).not.toBeChecked()
  await expect(card.getByRole('button', { name: '展示完成並釋放', exact: true })).toBeDisabled()
  await card.getByRole('checkbox', { name: '我確認展示任務完成並解除合成資源占用', exact: true }).check()
  await confirm(page, card.getByRole('button', { name: '展示完成並釋放', exact: true }))
  await expect(card.getByRole('region', { name: '展示任務完成紀錄', exact: true })).toContainText('資源已釋放')
}
async function screenshot(page, info, kind) {
  const directory = resolve(root, 'artifacts/showcase-demo/screenshots')
  await mkdir(directory, { recursive: true })
  const prefix = process.env.SHOWCASE_BASE_URL ? 'live' : 'local'
  // Capture from the real document top so a fixed, offscreen skip link is not
  // composited into the middle of a full-page screenshot after a scrolled action.
  await page.evaluate(() => window.scrollTo(0, 0))
  await page.screenshot({ path: `${directory}/${prefix}-${kind}-${info.project.name}.png`, fullPage: true, animations: 'disabled' })
}

test('three order types, multiple items and dependent tasks retain edits and reject premature dispatch', async ({ page }, info) => {
  await screenshot(page, info, 'dashboard')
  await role(page, 'DISPATCHER')
  await nav(page, '訂單工作台')
  for (const [type, no] of [['LOCAL', orderNo], ['IMPORT', 'DEMO-IMPORT-1002'], ['EXPORT', 'DEMO-EXPORT-1003']]) {
    await order(page, no).getByRole('button', { name: '編輯展示草稿', exact: true }).click()
    await expect(page.getByLabel('訂單類型', { exact: true })).toHaveValue(type)
    await page.getByLabel('客戶參考號', { exact: true }).fill(`SYNTHETIC-${type}`)
    await page.getByLabel('展示備註', { exact: true }).fill(`合成 ${type} 編輯保存`)
    if (type === 'LOCAL') {
      await page.getByRole('button', { name: '新增項目', exact: true }).click()
      const item = page.locator('fieldset[data-order-path="/items/1"]')
      await item.getByLabel('項目序號', { exact: true }).fill('2')
      await item.getByLabel('標的類型', { exact: true }).selectOption('CARGO')
      await item.getByLabel('件數', { exact: true }).fill('3')
      await page.getByRole('button', { name: '新增任務', exact: true }).click()
      const task = page.locator('fieldset[data-order-path="/tasks/1"]')
      const itemOptions = task.getByLabel('所屬項目', { exact: true }).getByRole('option')
      const itemValue = await itemOptions.nth(2).getAttribute('value')
      expect(itemValue).toBeTruthy()
      await task.getByLabel('所屬項目', { exact: true }).selectOption(itemValue)
      await task.getByLabel('作業類型', { exact: true }).fill('合成接續配送')
      await task.getByLabel('必要任務標記', { exact: true }).selectOption('true')
      for (const [label, value] of [['起點名稱', '合成中繼站'], ['起點地址', '虛構中繼路 3 號'], ['終點名稱', '合成末站'], ['終點地址', '虛構末站路 4 號']]) await task.getByLabel(label, { exact: true }).fill(value)
      await task.getByRole('checkbox', { name: '任務 1 · 合成運送', exact: true }).check()
    }
    await page.getByRole('button', { name: '保存展示草稿', exact: true }).click()
    await expect(order(page, no)).toContainText(`合成 ${type} 編輯保存`)
  }
  await expect(order(page)).toContainText('2 個項目 · 2 段任務')
  for (const no of [orderNo, 'DEMO-IMPORT-1002', 'DEMO-EXPORT-1003']) await accept(page, no)
  await nav(page, '調度工作台')
  await confirm(page, dispatch(page, 'T-DEMO-1002').getByRole('button', { name: '建立展示派工', exact: true }))
  await expect(page.getByRole('alert')).toContainText('前置任務尚未完成')
  await expect(dispatch(page, 'T-DEMO-1002')).toContainText('待派工')
  await role(page, 'DRIVER')
  for (const name of ['訂單工作台', '調度工作台', '異常處理', '確認與結案']) await expect(page.getByRole('navigation', { name: '主要導覽' }).getByRole('button', { name, exact: true })).toHaveCount(0)
  await nav(page, '本人運輸任務')
  await expect(page.getByRole('heading', { name: '目前沒有本人展示任務', exact: true })).toBeVisible()
  await role(page, 'ADMIN')
  await page.getByRole('button', { name: '重置展示', exact: true }).click()
  await page.getByRole('dialog', { name: '確認重置展示', exact: true }).getByRole('button', { name: '保留目前展示', exact: true }).click()
  await nav(page, '訂單工作台')
  await expect(order(page)).toContainText('已受理')
  await page.getByRole('button', { name: '重置展示', exact: true }).click()
  await page.getByRole('dialog', { name: '確認重置展示', exact: true }).getByRole('button', { name: '確認重置', exact: true }).click()
  await nav(page, '訂單工作台')
  await expect(page.getByRole('article')).toHaveCount(3)
  await expect(order(page)).toContainText('1 個項目 · 1 段任務')
  await expect(order(page).getByRole('button', { name: '展示受理', exact: true })).toBeVisible()
})

test('role handoff, fresh safety confirmation, completion, closure and resource reuse are distinct', async ({ page }, info) => {
  await reachAwaiting(page)
  if (info.project.name === 'mobile') {
    for (const control of [page.getByLabel('展示角色', { exact: true }), personal(page).getByRole('button', { name: '展示完成並釋放', exact: true })]) {
      const box = await control.boundingBox()
      expect(box).not.toBeNull()
      expect(box.height).toBeGreaterThanOrEqual(44)
      expect(box.width).toBeGreaterThanOrEqual(44)
    }
  }
  await screenshot(page, info, 'driver')
  const checkbox = personal(page).getByRole('checkbox', { name: '我確認展示任務完成並解除合成資源占用', exact: true })
  await checkbox.check()
  await role(page, 'ADMIN')
  await nav(page, '確認與結案')
  const closure = article(page, `展示結案 ${orderNo}`)
  await expect(closure.getByRole('button', { name: '展示結案', exact: true })).toBeDisabled()
  await driver(page)
  await complete(page)
  await role(page, 'DISPATCHER')
  await nav(page, '確認與結案')
  await expect(closure.getByRole('button', { name: '展示結案', exact: true })).toBeDisabled()
  const confirmation = article(page, `展示管理確認 ${taskNo}`)
  const reason = '合成管理確認：完成與結案分開'
  await confirmation.getByLabel('展示管理確認原因', { exact: true }).fill(reason)
  await confirm(page, confirmation.getByRole('button', { name: '記錄展示管理確認', exact: true }), reason)
  await closure.getByLabel('展示結案原因', { exact: true }).fill('合成訂單已完整結案')
  await confirm(page, closure.getByRole('button', { name: '展示結案', exact: true }), '合成訂單已完整結案')
  await expect(closure).toContainText('已結案')
  await screenshot(page, info, 'closed')
  await accept(page, 'DEMO-IMPORT-1002')
  await assign(page, 'T-DEMO-IMPORT-1002-1')
  await driver(page)
  await expect(personal(page).getByRole('region', { name: '展示任務完成紀錄', exact: true })).toContainText('資源已釋放')
  await expect(personal(page, 'T-DEMO-IMPORT-1002-1')).toContainText('待出發')
})

test('photo upload, explicit supplement, resolution and completion preserve separate boundaries', async ({ page }) => {
  await reachAwaiting(page)
  const description = '  合成延誤 e\u0301\n等待補件  '
  await personal(page).getByLabel('展示異常描述', { exact: true }).fill(description)
  await confirm(page, personal(page).getByRole('button', { name: '回報展示異常', exact: true }), description)
  await expect(personal(page).getByRole('button', { name: '展示完成並釋放', exact: true })).toBeDisabled()
  await role(page, 'ADMIN')
  await nav(page, '異常處理')
  const exception = page.getByRole('article').filter({ has: page.getByLabel('展示異常描述原文', { exact: true }) })
  await expect(exception.getByLabel('展示異常描述原文', { exact: true })).toHaveJSProperty('textContent', description)
  await expect(exception.getByRole('button', { name: '處置展示異常', exact: true })).toHaveCount(0)
  const request = '  合成補件 e\u0301\n請明確提交  '
  await exception.getByLabel('補件要求（司機可見）', { exact: true }).fill(request)
  await confirm(page, exception.getByRole('button', { name: '要求展示補件', exact: true }), request)
  await driver(page)
  const mine = personal(page).getByRole('article').filter({ has: page.getByLabel('展示異常描述原文', { exact: true }) })
  await mine.getByRole('button', { name: '加入合成補件照片', exact: true }).click()
  await expect(mine).toContainText('待補件')
  await expect(personal(page).getByRole('button', { name: '展示完成並釋放', exact: true })).toBeDisabled()
  await mine.getByRole('checkbox', { name: '選取補件照片 1', exact: true }).check()
  await mine.getByLabel('展示補件說明', { exact: true }).fill('僅提交本次要求的合成照片')
  await confirm(page, mine.getByRole('button', { name: '提交展示補件', exact: true }), '僅提交本次要求的合成照片')
  await expect(mine).toContainText('待管理處置')
  await expect(personal(page).getByRole('button', { name: '展示完成並釋放', exact: true })).toBeDisabled()
  await role(page, 'DISPATCHER')
  await nav(page, '異常處理')
  await exception.getByLabel('處置原因（司機可見）', { exact: true }).fill('合成補件已明確提交並核對')
  await confirm(page, exception.getByRole('button', { name: '處置展示異常', exact: true }), '合成補件已明確提交並核對')
  await driver(page)
  await expect(personal(page)).toContainText('沿用最近一次進度提交的 1 張合成照片')
  await complete(page)
})

test('memory reset, refresh and new tabs preserve unrelated origin storage and never become real authentication', async ({ page, context, baseURL }) => {
  const audit = audits.get(context)
  audit.foreign = true
  await page.evaluate(([key, value]) => { localStorage.setItem(key, value); sessionStorage.setItem(key, value) }, [foreignKey, foreignValue])
  await accept(page)
  await assign(page)
  await driver(page)
  await expect(personal(page)).toContainText('待出發')
  const other = await context.newPage()
  await auditPage(other, audit)
  await other.goto(baseURL, { waitUntil: 'networkidle' })
  await nav(other, '訂單工作台')
  await expect(order(other).getByRole('button', { name: '展示受理', exact: true })).toBeVisible()
  await expect(personal(page)).toContainText('待出發')
  await page.getByRole('button', { name: '重置展示', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '確認重置展示', exact: true })
  await expect(dialog.getByRole('button', { name: '保留目前展示', exact: true })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  await expect(personal(page)).toContainText('待出發')
  await page.getByRole('button', { name: '重置展示', exact: true }).click()
  await page.getByRole('dialog', { name: '確認重置展示', exact: true }).getByRole('button', { name: '確認重置', exact: true }).click()
  await nav(page, '訂單工作台')
  await expect(order(page).getByRole('button', { name: '展示受理', exact: true })).toBeVisible()
  await page.reload({ waitUntil: 'networkidle' })
  await nav(page, '訂單工作台')
  await expect(page.getByRole('article')).toHaveCount(3)
  await expect(order(page).getByRole('button', { name: '展示受理', exact: true })).toBeVisible()
  expect(await page.evaluate(key => ({ local: localStorage.getItem(key), session: sessionStorage.getItem(key) }), foreignKey)).toEqual({ local: foreignValue, session: foreignValue })
  await expect(page.locator('input[type="password"], input[type="file"]')).toHaveCount(0)
})
