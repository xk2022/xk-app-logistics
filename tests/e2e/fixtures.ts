import { readFileSync } from 'node:fs'
import { randomBytes } from 'node:crypto'
import { expect, type Locator, type Page } from '@playwright/test'

export interface Credential { username: string; password: string }
interface AdmFixture {
  admin: Credential
  dispatcher: Credential
  driver: Credential
  parameterKeys: { text: string; number: string; boolean: string }
}
interface CrmFixture {
  admin: Credential
  dispatcher: Credential
  driver: Credential
}
interface TomFixture {
  admin: Credential
  dispatcher: Credential
  driver: Credential
}
interface ProjectFixture {
  admin: Credential
  dispatcher: Credential
  driver: Credential
  adm?: AdmFixture
  crm?: CrmFixture
  tom?: TomFixture
}

export function fixtureFor(project: string): ProjectFixture {
  const file = process.env.E2E_FIXTURE_PATH ?? '/tmp/xk-m2-e2e-fixture.json'
  let fixture: unknown
  try { fixture = JSON.parse(readFileSync(file, 'utf8')) as unknown } catch {
    throw new Error('Create the isolated UPMS test fixture before running E2E.')
  }
  const group = (fixture as Record<string, ProjectFixture>)[project]
  if (!group || !['admin', 'dispatcher', 'driver'].every((key) => {
    const account = group[key as 'admin' | 'dispatcher' | 'driver']
    return account && typeof account.username === 'string' && typeof account.password === 'string'
  })) throw new Error('UPMS test fixture is incomplete for this browser project.')
  return group
}

export function admFixtureFor(project: string): AdmFixture {
  const group = fixtureFor(project).adm
  if (!group || !['admin', 'dispatcher', 'driver'].every((key) => {
    const account = group[key as 'admin' | 'dispatcher' | 'driver']
    return account && typeof account.username === 'string' && typeof account.password === 'string'
  }) || !['text', 'number', 'boolean'].every((key) => typeof group.parameterKeys?.[key as keyof AdmFixture['parameterKeys']] === 'string')) {
    throw new Error('ADM test fixture is incomplete for this browser project.')
  }
  return group
}

export function crmFixtureFor(project: string): CrmFixture {
  const group = fixtureFor(project).crm
  if (!group || !['admin', 'dispatcher', 'driver'].every((key) => {
    const account = group[key as keyof CrmFixture]
    return account && typeof account.username === 'string' && typeof account.password === 'string'
  })) throw new Error('CRM test fixture is incomplete for this browser project.')
  return group
}

export function tomFixtureFor(project: string): TomFixture {
  const group = fixtureFor(project).tom
  if (!group || !['admin', 'dispatcher', 'driver'].every((key) => {
    const account = group[key as keyof TomFixture]
    return account && typeof account.username === 'string' && typeof account.password === 'string'
  })) throw new Error('TOM test fixture is incomplete for this browser project.')
  return group
}

export async function changeInitialPassword(page: Page, currentPassword: string, newPassword: string): Promise<void> {
  await expect(page.getByRole('heading', { name: '首次登入，請先修改密碼' })).toBeVisible()
  await fillPassword(page.getByLabel('目前密碼', { exact: true }), currentPassword)
  await fillPassword(page.getByLabel('新密碼', { exact: true }), newPassword)
  await fillPassword(page.getByLabel('確認新密碼', { exact: true }), newPassword)
  await page.getByRole('button', { name: '確認修改密碼', exact: true }).click()
  await expect(page.getByRole('heading', { name: '登入工作空間' })).toBeVisible()
}

export function syntheticPassword(): string { return randomBytes(24).toString('base64url') }

export async function fillPassword(locator: Locator, password: string): Promise<void> {
  await expect(locator).toBeVisible()
  await expect(locator).toHaveAttribute('type', 'password')
  // Keep credential values out of Playwright's action titles and HTML reports.
  await locator.evaluate((element, value) => {
    const input = element as HTMLInputElement
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value)
    input.dispatchEvent(new Event('input', { bubbles: true }))
    input.dispatchEvent(new Event('change', { bubbles: true }))
  }, password)
}

export async function signIn(page: Page, credential: Credential): Promise<void> {
  await page.getByLabel('帳號', { exact: true }).fill(credential.username)
  const password = page.getByLabel('密碼', { exact: true })
  await fillPassword(password, credential.password)
  await expect(page.getByRole('button', { name: '登入', exact: true })).toBeEnabled()
  await password.press('Enter')
}
