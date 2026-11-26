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
interface FmsFixture extends TomFixture {
  vehicleTypeCategoryId: string
  vehicleTypes: Record<'a' | 'b' | 'c', { id: string; code: string; label: string }>
}
interface DriverCredential extends Credential { id: string }
interface DispatchFixture {
  admin: DriverCredential
  dispatcher: DriverCredential
  driverA: DriverCredential
  driverB: DriverCredential
  scope: 'DESKTOP' | 'MOBILE'
  vehicleTypes: Record<'main' | 'trailer' | 'shared', { id: string; code: string; label: string }>
}
interface DriverFixture {
  admin: DriverCredential
  dispatcher: DriverCredential
  driverA: DriverCredential
  driverB: DriverCredential
  driverC: DriverCredential
  temporary: DriverCredential
  accountSearchPrefix: string
  licenseTypeCategoryId: string
  licenseTypes: Record<'a' | 'b', { id: string; code: string; label: string }>
}
interface ProjectFixture {
  admin: Credential
  dispatcher: Credential
  driver: Credential
  adm?: AdmFixture
  crm?: CrmFixture
  tom?: TomFixture
  orderUi?: TomFixture
  fms?: FmsFixture
  driverFlow?: DriverFixture
  acceptance?: TomFixture
  dispatch?: DispatchFixture
  dispatchUi?: DispatchFixture
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

export function orderUiFixtureFor(project: string): TomFixture {
  const group = fixtureFor(project).orderUi
  if (!group || !['admin', 'dispatcher', 'driver'].every((key) => {
    const account = group[key as keyof TomFixture]
    return account && typeof account.username === 'string' && typeof account.password === 'string'
  })) throw new Error('Order UI test fixture is incomplete for this browser project.')
  return group
}

export function fmsFixtureFor(project: string): FmsFixture {
  const group = fixtureFor(project).fms
  if (!group || !['admin', 'dispatcher', 'driver'].every((key) => {
    const account = group[key as keyof TomFixture]
    return account && typeof account.username === 'string' && typeof account.password === 'string'
  }) || typeof group.vehicleTypeCategoryId !== 'string' || !['a', 'b', 'c'].every((key) => {
    const type = group.vehicleTypes?.[key as 'a' | 'b' | 'c']
    return type && ['id', 'code', 'label'].every((field) => typeof type[field as keyof typeof type] === 'string')
  })) throw new Error('FMS test fixture is incomplete for this browser project.')
  return group
}

export function driverFixtureFor(project: string): DriverFixture {
  const group = fixtureFor(project).driverFlow
  if (!group || !['admin', 'dispatcher', 'driverA', 'driverB', 'driverC', 'temporary'].every((key) => {
    const account = group[key as 'admin' | 'dispatcher' | 'driverA' | 'driverB' | 'driverC' | 'temporary']
    return account && ['id', 'username', 'password'].every((field) => typeof account[field as keyof DriverCredential] === 'string')
  }) || typeof group.accountSearchPrefix !== 'string' || typeof group.licenseTypeCategoryId !== 'string' || !['a', 'b'].every((key) => {
    const type = group.licenseTypes?.[key as 'a' | 'b']
    return type && ['id', 'code', 'label'].every((field) => typeof type[field as keyof typeof type] === 'string')
  })) throw new Error('Driver test fixture is incomplete for this browser project.')
  return group
}

export function acceptanceFixtureFor(project: string): TomFixture {
  const group = fixtureFor(project).acceptance
  if (!group || !['admin', 'dispatcher', 'driver'].every((key) => {
    const account = group[key as keyof TomFixture]
    return account && typeof account.username === 'string' && typeof account.password === 'string'
  })) throw new Error('Acceptance test fixture is incomplete for this browser project.')
  return group
}

export function dispatchFixtureFor(project: string): DispatchFixture {
  const group = fixtureFor(project).dispatch
  if (!group || !['admin', 'dispatcher', 'driverA', 'driverB'].every((key) => {
    const account = group[key as 'admin' | 'dispatcher' | 'driverA' | 'driverB']
    return account && ['id', 'username', 'password'].every((field) => typeof account[field as keyof DriverCredential] === 'string')
  }) || !['DESKTOP', 'MOBILE'].includes(group.scope) || !['main', 'trailer', 'shared'].every((key) => {
    const type = group.vehicleTypes?.[key as keyof DispatchFixture['vehicleTypes']]
    return type && ['id', 'code', 'label'].every((field) => typeof type[field as keyof typeof type] === 'string')
  })) throw new Error('Dispatch test fixture is incomplete for this browser project.')
  return group
}

export function dispatchUiFixtureFor(project: string): DispatchFixture {
  const group = fixtureFor(project).dispatchUi
  if (!group || !['admin', 'dispatcher', 'driverA', 'driverB'].every((key) => {
    const account = group[key as 'admin' | 'dispatcher' | 'driverA' | 'driverB']
    return account && ['id', 'username', 'password'].every((field) => typeof account[field as keyof DriverCredential] === 'string')
  }) || !['DESKTOP', 'MOBILE'].includes(group.scope) || !['main', 'trailer', 'shared'].every((key) => {
    const type = group.vehicleTypes?.[key as keyof DispatchFixture['vehicleTypes']]
    return type && ['id', 'code', 'label'].every((field) => typeof type[field as keyof typeof type] === 'string')
  })) throw new Error('Dispatch UI test fixture is incomplete for this browser project.')
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
